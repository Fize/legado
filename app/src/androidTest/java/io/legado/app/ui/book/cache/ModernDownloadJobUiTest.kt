package io.legado.app.ui.book.cache

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import dagger.hilt.android.EntryPointAccessors
import fi.iki.elonen.NanoHTTPD
import io.legado.app.data.appDb
import io.legado.app.data.entities.*
import io.legado.app.data.entities.rule.ContentRule
import io.legado.app.help.book.BookHelp
import io.legado.app.service.BookTransferEntryPoint
import io.legado.app.service.BookTransferScheduler
import io.legado.app.ui.AppActivity
import io.legado.app.ui.navigation.CacheRoute
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TestName
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/** Actual constrained UIDT jobs: no force-run, manual Worker or direct service invocation. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 34)
class ModernDownloadJobUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val testName = TestName()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val id = UUID.randomUUID().toString()
    private val permission = InstrumentationRegistry.getArguments().getString("compatNotifications", "granted")!!
        .also { require(it in listOf("granted", "denied")) }
    private val retryWait = InstrumentationRegistry.getArguments().getString("compatRetryWaitMs", "2100000")!!.toLong()
        .also { require(it in 90_000L..2_400_000L) }
    private val networkWait = InstrumentationRegistry.getArguments().getString("compatNetworkWaitMs", "120000")!!.toLong()
        .also { require(it in 30_000L..180_000L) }
    private val cancelQuiet = InstrumentationRegistry.getArguments().getString("compatCancelQuietMs", "90000")!!.toLong()
        .also { require(it in 30_000L..180_000L) }
    private val allowUnvalidatedNetwork =
        InstrumentationRegistry.getArguments().getString("compatAllowUnvalidatedNetwork") == "yes"
    private val connectivity get() = context.getSystemService(ConnectivityManager::class.java)
    private val scheduler get() = context.getSystemService(JobScheduler::class.java).forNamespace(BookTransferScheduler.NAMESPACE)
    private val notifications get() = context.getSystemService(NotificationManager::class.java)
    private val requests = CopyOnWriteArrayList<Int>()
    private val gates = CopyOnWriteArrayList<CountDownLatch>()
    private val returned = AtomicInteger()
    private val trace = JSONArray()
    private val windowSamples = JSONArray()
    @Volatile private var responseGate: CountDownLatch? = null
    private val server = object : NanoHTTPD("127.0.0.1", 0) {
        override fun serve(session: IHTTPSession): Response {
            val gate = responseGate
            val attempt = requests.size + 1
            requests.add(attempt)
            gate?.await(90, TimeUnit.SECONDS)
            returned.incrementAndGet()
            return newFixedLengthResponse(Response.Status.OK, "text/html", "<body>Modern fixture $id attempt=$attempt</body>")
        }
    }
    private lateinit var source: BookSource
    private lateinit var book: Book
    private lateinit var group: BookGroup
    private lateinit var chapter: BookChapter
    private var activity: AppActivity? = null
    private var networkChanged = false
    private var wifi = false
    private var mobile = false
    private val ownedManagers = linkedSetOf<String>()
    private var ownedRecordsRemoved = false
    private fun managerIds() = appDb.openHelper.readableDatabase.query("SELECT id FROM cache_manager_states").use { cursor ->
        buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
    }
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
    private fun resumed(): AppActivity? {
        var result: AppActivity? = null
        instrumentation.runOnMainSync {
            result = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<AppActivity>()
                .firstOrNull()
        }
        return result
    }
    private fun task() = appDb.bookTransferDao.lease(book.bookUrl, "download")?.let(appDb.bookTransferDao::get)
    private fun gate() = CountDownLatch(1).also { gates.add(it); responseGate = it }
    private fun connected(): Boolean {
        val capabilities = connectivity.activeNetwork?.let(connectivity::getNetworkCapabilities) ?: return false
        if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return true
        return allowUnvalidatedNetwork &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
    }
    private fun restoreNetwork() {
        if (!networkChanged) return
        shell("svc wifi ${if (wifi) "enable" else "disable"}")
        shell("svc data ${if (mobile) "enable" else "disable"}")
        compose.waitUntil(networkWait) { connected() }
        networkChanged = false
    }
    private fun checkpoint(event: String, job: JobInfo? = null) {
        val current = if (::book.isInitialized) task() else null
        trace.put(JSONObject().put("event", event).put("permission", permission)
            .put("elapsed_realtime_ms", SystemClock.elapsedRealtime())
            .put("notifications_enabled", notifications.areNotificationsEnabled())
            .put("validated_network", connected()).put("request_count", requests.size)
            .put("task_id", current?.id).put("task_phase", current?.phase).put("task_owner", current?.owner)
            .put("batch_id", current?.batchId).put("process", current?.process)
            .put("batch_phase", current?.batchId?.let(appDb.bookTransferDao::batch)?.phase)
            .put("job_id", job?.id).put("user_initiated", job?.isUserInitiated))
        trace.getJSONObject(trace.length() - 1).put("owned_manager_ids", JSONArray(ownedManagers.toList()))
        File(context.cacheDir, "modern-download-${testName.methodName}.json").writeText(trace.toString(2))
    }

    private fun resizeDownloadWindow(widthDp: Int, heightDp: Int): JSONObject {
        val host = checkNotNull(activity)
        val density = host.resources.displayMetrics.density
        val width = (widthDp * density).toInt()
        val height = (heightDp * density).toInt()
        val taskId = host.taskId
        val result = shell("am task resize $taskId 50 50 ${50 + width} ${50 + height}")
        check(!result.contains("Error") && !result.contains("Exception")) { result }
        compose.waitUntil(15_000) {
            val measuredActivity = resumed()
            activity = measuredActivity
            var measuredWidth = 0
            var measuredHeight = 0
            measuredActivity?.let { current ->
                instrumentation.runOnMainSync {
                    measuredWidth = current.window.decorView.width
                    measuredHeight = current.window.decorView.height
                }
            }
            val current = measuredActivity
            File(context.cacheDir, "modern-download-resize-probe.json").writeText(
                JSONObject()
                    .put("task_id_before", taskId)
                    .put("task_id_now", current?.taskId)
                    .put("requested_width_px", width)
                    .put("requested_height_px", height)
                    .put("actual_width_px", measuredWidth)
                    .put("actual_height_px", measuredHeight)
                    .put("configuration_width_dp", current?.resources?.configuration?.screenWidthDp)
                    .put("multi_window", current?.isInMultiWindowMode)
                    .put("route", current?.currentDestination?.toString())
                    .toString(2)
            )
            current?.taskId == taskId && abs(measuredWidth - width) <= 2 * density &&
                abs(measuredHeight - height) <= 2 * density &&
                current.resources.configuration.screenWidthDp == widthDp
        }
        compose.waitForIdle()
        val currentActivity = checkNotNull(activity)
        assertEquals("Window resize changed the task", taskId, currentActivity.taskId)
        assertEquals(CacheRoute(checkNotNull(group).groupId), currentActivity.currentDestination)
        compose.onNodeWithTag("cacheDownload:${book.bookUrl}").assertIsDisplayed()
        val current = checkNotNull(task())
        assertEquals("running", current.phase)
        assertTrue(requests.size >= 1)
        return JSONObject()
            .put("width_dp", widthDp)
            .put("height_dp", heightDp)
            .put("actual_width_px", currentActivity.window.decorView.width)
            .put("actual_height_px", currentActivity.window.decorView.height)
            .put("density", density)
            .put("font_scale", currentActivity.resources.configuration.fontScale)
            .put("task_id", taskId)
            .put("activity_identity", System.identityHashCode(currentActivity))
            .put("activity_recreated", currentActivity !== host)
            .put("transfer_id", current.id)
            .put("batch_id", current.batchId)
            .put("owner", current.owner)
            .put("phase", current.phase)
            .put("requests", requests.size)
    }

    @Before fun setup() {
        assertEquals("Set the notification permission before starting instrumentation", permission == "granted",
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
        assertEquals(permission == "granted", notifications.areNotificationsEnabled())
        assertTrue("Requires a validated or explicitly allowed INTERNET-capable emulator network; this suite never force-runs a job", connected())
        wifi = Settings.Global.getInt(context.contentResolver, Settings.Global.WIFI_ON, 0) != 0
        mobile = Settings.Global.getInt(context.contentResolver, "mobile_data", 0) != 0
        server.start()
        val groupId = (0..60).map { 1L shl it }.first { appDb.bookGroupDao.getByID(it) == null }
        group = BookGroup(groupId, "Modern worker $id")
        source = BookSource("http://127.0.0.1:${server.listeningPort}", "Modern worker $id", ruleContent = ContentRule(content = "body@text"))
        book = Book(bookUrl = source.bookSourceUrl + "/book/$id", tocUrl = source.bookSourceUrl + "/toc/$id",
            origin = source.bookSourceUrl, name = "Modern worker $id", author = id, group = groupId,
            totalChapterNum = 1, durChapterIndex = 0, canUpdate = false)
        chapter = BookChapter(bookUrl = book.bookUrl, url = source.bookSourceUrl + "/chapter/0", title = "Chapter 0", index = 0)
        appDb.bookGroupDao.insert(group); appDb.bookSourceDao.insert(source)
        appDb.bookDao.insert(book); appDb.bookChapterDao.insert(chapter)
        val priorManagers = managerIds()
        val uri = AppActivity.startIntent(context, CacheRoute(groupId))
            .toUri(Intent.URI_INTENT_SCHEME)
        check(uri.none(Char::isWhitespace))
        val launch = shell("am start --windowingMode 5 $uri")
        check(!launch.contains("Error") && !launch.contains("Exception")) { launch }
        compose.waitUntil(20_000) {
            activity = resumed()
            activity?.isInMultiWindowMode == true &&
                activity?.currentDestination == CacheRoute(groupId)
        }
        compose.waitUntil(20_000) { (managerIds() - priorManagers).isNotEmpty() }
        ownedManagers.addAll(managerIds() - priorManagers)
        assertEquals("Only the newly opened owned cache entry may be cleaned", 1, ownedManagers.size)
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("cacheDownload:${book.bookUrl}").fetchSemanticsNodes().isNotEmpty() }
        checkpoint("ready")
    }

    @After fun cleanup() {
        val errors = arrayListOf<Throwable>()
        fun finish(action: () -> Unit) { runCatching(action).exceptionOrNull()?.let(errors::add) }
        finish { gates.forEach(CountDownLatch::countDown) }
        finish { restoreNetwork() }
        finish { instrumentation.runOnMainSync { activity?.takeUnless { it.isDestroyed }?.finish() } }
        finish {
            if (::book.isInitialized) {
                val own = appDb.openHelper.readableDatabase.query("SELECT id FROM book_transfer_tasks WHERE bookUrl=?", arrayOf(book.bookUrl)).use { cursor ->
                    buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
                }
                val batches = own.mapNotNull { appDb.bookTransferDao.get(it)?.batchId }.toSet()
                runBlocking { EntryPointAccessors.fromApplication(context, BookTransferEntryPoint::class.java).transferScheduler().cancelTasks(own) }
                checkpoint("cleanup")
                BookHelp.clearCache(book)
                appDb.bookChapterDao.delByBook(book.bookUrl)
                own.forEach(appDb.bookTransferDao::deleteTask)
                batches.forEach { batch ->
                    check(appDb.bookTransferDao.batchTasks(batch).isEmpty())
                    appDb.openHelper.writableDatabase.execSQL("DELETE FROM book_transfer_batches WHERE id=?", arrayOf(batch))
                }
                assertTrue(own.all { appDb.bookTransferDao.get(it) == null })
                ownedRecordsRemoved = true
                appDb.bookDao.delete(book)
            }
        }
        finish { if (::source.isInitialized) appDb.bookSourceDao.delete(source) }
        finish { if (::group.isInitialized) appDb.bookGroupDao.delete(group) }
        finish {
            ownedManagers.forEach(appDb.bookTransferDao::deleteManager)
            assertTrue(ownedManagers.all { appDb.bookTransferDao.manager(it) == null })
        }
        finish { server.stop() }
        finish {
            assertEquals(permission == "granted", context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
            assertEquals(wifi, Settings.Global.getInt(context.contentResolver, Settings.Global.WIFI_ON, 0) != 0)
            assertEquals(mobile, Settings.Global.getInt(context.contentResolver, "mobile_data", 0) != 0)
            trace.put(JSONObject().put("settings_restored", true).put("owned_records_removed", ownedRecordsRemoved)
                .put("owned_managers_removed", ownedManagers.all { appDb.bookTransferDao.manager(it) == null }))
        trace.getJSONObject(trace.length() - 1).put("window_samples", windowSamples)
        }
        File(context.cacheDir, "modern-download-${testName.methodName}.json").writeText(trace.toString(2))
        if (errors.isNotEmpty()) throw AssertionError("Modern download cleanup failed", errors.first())
    }

    private fun start(): Pair<BookTransferTask, JobInfo> {
        compose.onNodeWithTag("cacheDownload:${book.bookUrl}").performClick()
        compose.onNodeWithTag("cacheConfirm").performClick()
        compose.waitUntil(30_000) { task()?.phase == "running" && requests.isNotEmpty() }
        val current = checkNotNull(task())
        val job = scheduler.allPendingJobs.single { it.extras.getString("batchId") == current.batchId }
        assertTrue(job.isUserInitiated)
        assertNotNull(job.requiredNetwork)
        checkpoint("running", job)
        return current to job
    }
    private fun completed(attempt: Int) {
        compose.waitUntil(30_000) { task()?.phase in listOf("completed", "failed") }
        val done = checkNotNull(task())
        assertEquals(done.error, "completed", done.phase)
        assertEquals(1, done.progress); assertEquals(1, done.total)
        val content = checkNotNull(BookHelp.getContent(book, chapter))
        assertTrue(content, content.contains("Modern fixture $id attempt=$attempt"))
        compose.waitUntil(10_000) { scheduler.allPendingJobs.none { it.extras.getString("batchId") == done.batchId } }
        checkpoint("completed")
    }
    private fun resumed(first: BookTransferTask, release: CountDownLatch, waitMillis: Long = 90_000) {
        compose.waitUntil(waitMillis) { task()?.let { it.phase == "running" && it.owner != first.owner && requests.size >= 2 } == true }
        val current = checkNotNull(task())
        assertEquals(first.id, current.id); assertEquals(first.batchId, current.batchId)
        assertEquals(first.process, current.process); assertNotEquals(first.owner, current.owner)
        assertNull("The previous response must not commit after system stop", BookHelp.getContent(book, chapter))
        checkpoint("resumed")
        release.countDown(); completed(2)
        assertEquals(2, requests.size)
    }

    @Test fun downloadCompletesUnderTheExplicitNotificationPermissionState() {
        val held = gate(); start(); held.countDown(); completed(1)
        assertEquals(1, requests.size)
    }

    @Test fun downloadKeepsItsTaskAcrossContinuousWindowChangesAndThenCompletes() {
        Assume.assumeTrue("Requires actual task resizing",
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT))
        val held = gate()
        val (first, _) = start()
        compose.waitUntil(10_000) { requests.size == 1 }
        val sequence = listOf(
            600 to 600,
            360 to 480,
            599 to 600,
            840 to 360,
            900 to 600,
            360 to 480,
        )
        for ((width, height) in sequence) {
            windowSamples.put(resizeDownloadWindow(width, height))
            val current = checkNotNull(task())
            assertEquals(first.id, current.id)
            assertEquals(first.batchId, current.batchId)
            assertEquals(first.owner, current.owner)
            assertEquals("running", current.phase)
        }
        assertEquals(1, requests.size)
        held.countDown()
        completed(1)
        assertEquals(1, requests.size)
    }

    @Test fun notificationCancellationRejectsTheBlockedResponseAndDoesNotResume() {
        assertEquals("granted", permission)
        val held = gate(); val (first, job) = start()
        compose.waitUntil(10_000) { notifications.activeNotifications.any { it.id == job.id } }
        val notification = notifications.activeNotifications.single { it.id == job.id }.notification
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        notification.actions.single().actionIntent.send()
        compose.waitUntil(15_000) { task()?.phase == "cancelled" && scheduler.allPendingJobs.none { it.id == job.id } }
        held.countDown()
        compose.waitUntil(10_000) { returned.get() == 1 }
        val until = SystemClock.elapsedRealtime() + cancelQuiet
        compose.waitUntil(cancelQuiet + 5_000) {
            assertEquals("cancelled", task()?.phase); assertEquals(first.batchId, task()?.batchId)
            assertEquals(1, requests.size); assertNull(BookHelp.getContent(book, chapter))
            SystemClock.elapsedRealtime() >= until
        }
        checkpoint("cancelled_response_rejected")
    }

    @Test fun lostConnectivityStopsAndAutomaticallyResumesWithANewOwner() {
        val firstGate = gate(); val (first, _) = start()
        val nextGate = gate()
        networkChanged = true
        shell("svc wifi disable"); shell("svc data disable")
        compose.waitUntil(30_000) { connectivity.activeNetwork == null }
        compose.waitUntil(30_000) { task()?.phase == "queued" && appDb.bookTransferDao.batch(first.batchId!!)?.phase == "waiting" }
        checkpoint("network_lost_waiting")
        firstGate.countDown()
        restoreNetwork()
        checkpoint("network_restored_validated")
        resumed(first, nextGate)
    }

    @Test fun systemTimeoutAutomaticallyResumesTheSameBatchWithoutAForceRun() {
        val firstGate = gate(); val (first, job) = start()
        val nextGate = gate()
        val result = shell("cmd jobscheduler timeout -n ${BookTransferScheduler.NAMESPACE} ${context.packageName} ${job.id}")
        assertTrue(result, result.contains("Stopping job:") && result.contains(job.id.toString()))
        compose.waitUntil(15_000) { task()?.phase == "queued" && appDb.bookTransferDao.batch(first.batchId!!)?.phase == "waiting" }
        checkpoint("system_timeout_waiting")
        // A timeout can put the retry into the OS's ordinary batching window.
        // Preserve the actual policy and wait for it; never force-run the job or
        // change device JobScheduler constants to turn this into an immediate retry.
        File(context.cacheDir, "modern-download-timeout-jobscheduler.txt").writeText(shell("dumpsys jobscheduler"))
        firstGate.countDown()
        resumed(first, nextGate, retryWait)
    }
}
