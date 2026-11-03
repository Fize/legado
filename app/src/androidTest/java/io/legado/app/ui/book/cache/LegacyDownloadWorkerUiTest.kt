package io.legado.app.ui.book.cache

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.net.ConnectivityManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
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
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Runs the production pre-34 scheduler and its network constraint; never force-runs a job. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 26, maxSdkVersion = 33)
class LegacyDownloadWorkerUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val legacyWifiRoot = InstrumentationRegistry.getArguments().getString("compatLegacyWifiRoot") == "true"
    private val id = UUID.randomUUID().toString()
    private val requests = CopyOnWriteArrayList<String>()
    @Volatile private var gate: CountDownLatch? = null
    private val server = object : NanoHTTPD("127.0.0.1", 0) {
        override fun serve(session: IHTTPSession): Response {
            requests.add(session.uri)
            gate?.await(45, TimeUnit.SECONDS)
            return newFixedLengthResponse(Response.Status.OK, "text/html", "<body>Legacy download ${session.uri}</body>")
        }
    }
    private lateinit var source: BookSource
    private lateinit var book: Book
    private lateinit var group: BookGroup
    private lateinit var chapters: List<BookChapter>
    private var scenario: ActivityScenario<AppActivity>? = null
    private var networkChanged = false
    private var wifi = false
    private var mobile = false
    private val manager get() = WorkManager.getInstance(context)
    private val connectivity get() = context.getSystemService(ConnectivityManager::class.java)
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
    private fun task() = appDb.bookTransferDao.lease(book.bookUrl, "download")?.let(appDb.bookTransferDao::get)
    private fun work(batch: String) = manager.getWorkInfosForUniqueWork(BookTransferScheduler.workName(batch))
        .get(10, TimeUnit.SECONDS).lastOrNull()
    private fun wifi(enabled: Boolean) {
        // Android 8's shell lacks CHANGE_WIFI_STATE. Only an explicitly selected
        // disposable AOSP userdebug device may use its su command for this fixture.
        val prefix = if (legacyWifiRoot) {
            check(Build.VERSION.SDK_INT <= 28)
            "su 0 "
        } else ""
        shell("${prefix}svc wifi ${if (enabled) "enable" else "disable"}")
        compose.waitUntil(10_000) { (Settings.Global.getInt(context.contentResolver, Settings.Global.WIFI_ON, 0) != 0) == enabled }
    }
    private fun restoreNetwork() {
        if (!networkChanged) return
        wifi(wifi)
        shell("svc data ${if (mobile) "enable" else "disable"}")
        networkChanged = false
    }

    @Before fun setup() {
        if (Build.VERSION.SDK_INT >= 33) assertEquals("Enable notification permission on the disposable test device",
            android.content.pm.PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS))
        server.start()
        val groupId = (0..60).map { 1L shl it }.first { appDb.bookGroupDao.getByID(it) == null }
        group = BookGroup(groupId, "Legacy worker $id")
        source = BookSource("http://127.0.0.1:${server.listeningPort}", "Legacy worker $id",
            ruleContent = ContentRule(content = "body@text"))
        book = Book(bookUrl = source.bookSourceUrl + "/book/$id", tocUrl = source.bookSourceUrl + "/toc/$id",
            origin = source.bookSourceUrl, name = "Legacy worker $id", author = id, group = groupId,
            totalChapterNum = 2, durChapterIndex = 0, canUpdate = false)
        chapters = (0..1).map { BookChapter(bookUrl = book.bookUrl, url = source.bookSourceUrl + "/chapter/$it",
            title = "Chapter $it", index = it) }
        appDb.bookGroupDao.insert(group); appDb.bookSourceDao.insert(source)
        appDb.bookDao.insert(book); appDb.bookChapterDao.insert(*chapters.toTypedArray())
        scenario = ActivityScenario.launch(AppActivity.startIntent(context, CacheRoute(groupId)))
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("cacheExport:${book.bookUrl}").fetchSemanticsNodes().isNotEmpty() }
    }
    @After fun cleanup() = runBlocking {
        gate?.countDown(); restoreNetwork()
        scenario?.close()
        if (::book.isInitialized) {
            val ids = appDb.bookTransferDao.active("download").filter { it.bookUrl == book.bookUrl }.map { it.id }
            EntryPointAccessors.fromApplication(context, BookTransferEntryPoint::class.java).transferScheduler().cancelTasks(ids)
            BookHelp.clearCache(book); appDb.bookDao.delete(book)
        }
        if (::source.isInitialized) appDb.bookSourceDao.delete(source)
        if (::group.isInitialized) appDb.bookGroupDao.delete(group)
        server.stop()
    }
    private fun start() {
        compose.onNodeWithTag("cacheDownloadMenu").performClick()
        compose.onNodeWithTag("cacheConfirm").performClick()
        compose.waitUntil(15_000) { task()?.batchId != null }
    }
    private fun completed() {
        compose.waitUntil(40_000) { task()?.phase in listOf("completed", "failed") }
        val done = checkNotNull(task())
        assertEquals(done.error, "completed", done.phase)
        assertEquals(2, done.progress)
        assertEquals(2, done.total)
        compose.waitUntil(10_000) { work(checkNotNull(done.batchId))?.state == WorkInfo.State.SUCCEEDED }
        chapters.forEach { assertTrue(BookHelp.getContent(book, it)!!.contains("Legacy download /chapter/${it.index}")) }
    }

    @Test fun schedulerUsesTheRealForegroundWorkerAndCompletesBothChapters() {
        start(); completed()
        assertTrue(requests.containsAll(listOf("/chapter/0", "/chapter/1")))
    }

    @Test fun notificationCancelStopsTheWorkerAndAnExplicitRetryUsesANewBatch() {
        gate = CountDownLatch(1)
        start()
        compose.waitUntil(30_000) { task()?.phase == "running" && requests.isNotEmpty() }
        val running = checkNotNull(task())
        val batch = checkNotNull(running.batchId)
        assertEquals(WorkInfo.State.RUNNING, work(batch)?.state)
        val notifications = context.getSystemService(NotificationManager::class.java)
        val notificationId = (batch.hashCode() and Int.MAX_VALUE).coerceAtLeast(1)
        compose.waitUntil(10_000) { notifications.activeNotifications.any { it.id == notificationId } }
        val notification = notifications.activeNotifications.single { it.id == notificationId }.notification
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        notification.actions.single().actionIntent.send()
        compose.waitUntil(15_000) { task()?.phase == "cancelled" && work(batch)?.state == WorkInfo.State.CANCELLED }
        gate!!.countDown(); gate = null
        assertNull(BookHelp.getContent(book, chapters[0]))
        compose.onNodeWithTag("cacheRetry:${running.id}").performClick()
        compose.waitUntil(15_000) { task()?.batchId != batch }
        completed()
        assertEquals(running.id, task()!!.id)
    }

    @Test fun disconnectedWorkWaitsAndAutomaticallyDownloadsAfterConnectivityReturns() {
        wifi = Settings.Global.getInt(context.contentResolver, Settings.Global.WIFI_ON, 0) != 0
        mobile = Settings.Global.getInt(context.contentResolver, "mobile_data", 0) != 0
        networkChanged = true
        wifi(false); shell("svc data disable")
        compose.waitUntil(15_000) { connectivity.activeNetwork == null }
        start()
        val batch = checkNotNull(task()!!.batchId)
        compose.waitUntil(10_000) { work(batch)?.state == WorkInfo.State.ENQUEUED }
        val until = SystemClock.elapsedRealtime() + 1500
        compose.waitUntil(5000) { assertTrue(requests.isEmpty()); SystemClock.elapsedRealtime() >= until }
        assertEquals("queued", task()!!.phase)
        restoreNetwork()
        completed()
    }
}
