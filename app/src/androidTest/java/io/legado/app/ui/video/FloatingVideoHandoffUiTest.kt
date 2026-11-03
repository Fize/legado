package io.legado.app.ui.video

import io.legado.app.help.storage.*
import io.legado.app.data.entities.*
import io.legado.app.data.repository.PlatformReaderReturnRepository
import io.legado.app.help.gsyVideo.VideoPlayer
import io.legado.app.R
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.shuyu.gsyvideoplayer.listener.VideoAllCallBack
import com.shuyu.gsyvideoplayer.video.base.GSYVideoView
import io.legado.app.constant.BookType
import io.legado.app.constant.IntentAction
import io.legado.app.constant.SourceType
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.help.book.BookHelp
import io.legado.app.help.CacheManager
import io.legado.app.help.LifecycleHelp
import io.legado.app.help.globalExecutor
import io.legado.app.help.gsyVideo.FloatingPlayer
import io.legado.app.model.VideoPlay
import io.legado.app.service.VideoPlayService
import io.legado.app.ui.AppActivity
import io.legado.app.ui.book.read.syntheticVideoFixture
import io.legado.app.ui.recovery.RestoreRecoveryActivity
import io.legado.app.utils.defaultSharedPreferences
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import java.io.File
import java.lang.ref.WeakReference
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

/** Actual decoded video in a real application overlay, with app-op changes scoped to this fixture. */
@RunWith(AndroidJUnit4::class)
class FloatingVideoHandoffUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val id = UUID.randomUUID().toString()
    private val clip = File(context.cacheDir, "floating-recovery-$id.mp4")
    private val source = BookSource("http://floating-$id.invalid", id)
    private val prefs = context.getSharedPreferences(VideoPlay.VIDEO_PREF_NAME, 0)
    private val originalVideoPreferences = HashMap(prefs.all)
    private var beforeJournal: Map<String, *>? = null
    private var overlayMode: String? = null
    private var fixtureBook: Book? = null
    private var inserted = false
    private var started = false
    private var ownedJournal = false
    private lateinit var main: AppActivity
    private val ownedActivities = linkedSetOf<VideoPlayerActivity>()
    private val ledger = File(context.cacheDir, "handoff-test-fixtures/$id")
    private val clipUrl get() = Uri.fromFile(clip).toString()

    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }

    @Before fun setup() {
        assertFalse(RestoreRecoveryGate.isQuarantined)
        assertFalse(RestoreFileRecovery.hasPending(context))
        assertTrue(services().isEmpty())
        recoverHostRepairedInterruptedFixtures()
        val result = shell("appops get ${context.packageName} SYSTEM_ALERT_WINDOW")
        overlayMode = Regex("SYSTEM_ALERT_WINDOW: (allow|deny|ignore|default|foreground)").find(result)?.groupValues?.get(1)
            ?: if (result.contains("No operations")) "default" else error("Unknown original overlay app-op: $result")
        writePreferenceSnapshot(context, ledger.path, "video-before") {
            writeValues(this, originalVideoPreferences, originalVideoPreferences.keys)
        }
        java.io.FileOutputStream(File(ledger, "video-before.xml"), true).use { it.fd.sync() }
        val state = org.json.JSONObject().put("id", id).put("appop", overlayMode)
            .put("bookUrl", "handoff-book-$id").put("source", source.bookSourceUrl).put("clip", clip.path).toString().toByteArray()
        java.io.FileOutputStream(File(ledger, "state.json")).use { it.write(state); it.fd.sync() }
        val directory = android.system.Os.open(ledger.path, android.system.OsConstants.O_RDONLY, 0)
        try { android.system.Os.fsync(directory) } finally { android.system.Os.close(directory) }
        shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")
        assertTrue(Settings.canDrawOverlays(context))
        clip.writeBytes(syntheticVideoFixture(clip, frameCount = 300))
        appDb.bookSourceDao.insert(source); inserted = true
        VideoPlay.autoPlay = true; VideoPlay.defaultFloatWindow = false; VideoPlay.startFull = false
        main = instrumentation.startActivitySync(Intent(context, AppActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as AppActivity
    }

    private fun recoverHostRepairedInterruptedFixtures() {
        val folders = File(context.cacheDir, "handoff-test-fixtures").listFiles().orEmpty()
        for (folder in folders) {
            if (folder.name == id) continue
            val prior = runCatching { UUID.fromString(folder.name).toString() }.getOrNull() ?: continue
            check(prior == folder.name)
            val marker = File(folder, "host-repaired.json")
            check(marker.isFile) { "Interrupted fixture needs its saved inputs repaired before reuse: $prior" }
            val proof = org.json.JSONObject(marker.readText())
            check(proof.getString("id") == prior && proof.getBoolean("video_preferences_restored") && proof.getBoolean("overlay_mode_restored"))
            val key = "handoff-book-$prior"
            val origin = "http://floating-$prior.invalid"
            val parentKey = "handoff-parent-$prior"
            var ownedBook: Book? = null
            var bookRemoved = false
            var sourceRemoved = false
            appDb.runInTransaction {
                ownedBook = appDb.bookDao.getBook(key)
                ownedBook?.let { check(it.origin == origin && it.name == prior) }
                appDb.bookSourceDao.getBookSource(origin)?.let { check(it.bookSourceName == prior) }
                dao.get(parentKey)?.let { check(it.ownerToken == prior && it.initialBookUrl == key && it.bookUrl == key) }
                check(!dao.anotherRequestUsesBook(key, parentKey))
                val before = unrelatedRows(key, origin, parentKey)
                dao.close(parentKey, prior)
                appDb.bookChapterDao.delByBook(key)
                ownedBook?.let { appDb.bookDao.delete(it); bookRemoved = true }
                appDb.bookSourceDao.getBookSource(origin)?.let { appDb.bookSourceDao.delete(it); sourceRemoved = true }
                check(appDb.bookDao.getBook(key) == null && appDb.bookSourceDao.getBookSource(origin) == null && dao.get(parentKey) == null)
                check(before == unrelatedRows(key, origin, parentKey)) { "Unrelated rows changed during owned fixture cleanup" }
            }
            ownedBook?.let(BookHelp::clearCache)
            File(context.cacheDir, "floating-recovery-$prior.mp4").delete()
            val audit = File(context.cacheDir, "handoff-repair-audits/$prior.json").apply { parentFile!!.mkdirs() }
            val data = org.json.JSONObject().put("id", prior).put("book_removed", bookRemoved).put("source_removed", sourceRemoved)
                .put("unrelated_rows_in_16_tables_unchanged", true).put("status", "passed").toString().toByteArray()
            java.io.FileOutputStream(audit).use { it.write(data); it.fd.sync() }
            folder.deleteRecursively()
        }
    }

    private fun unrelatedRows(bookKey: String, sourceKey: String, parentKey: String): Map<String, List<String>> {
        val tables = listOf("books", "chapters", "book_groups", "book_sources", "bookmarks", "readRecord", "highlights", "servers",
            "replace_rules", "cookies", "highlightRules", "httpTTS", "keyboardAssists", "book_source_check_states", "book_info_requests", "book_info_child_requests")
        return tables.associateWith { table ->
            val hashes = mutableListOf<String>()
            appDb.openHelper.writableDatabase.query("SELECT * FROM \"$table\"").use { cursor ->
                val column = when (table) {
                    "books", "chapters" -> "bookUrl"
                    "book_sources", "book_source_check_states" -> "bookSourceUrl"
                    "book_info_requests" -> "id"
                    "book_info_child_requests" -> "requestId"
                    else -> null
                }
                val expected = when (column) { "bookUrl" -> bookKey; "bookSourceUrl" -> sourceKey; else -> parentKey }
                while (cursor.moveToNext()) {
                    if (column != null && cursor.getString(cursor.getColumnIndexOrThrow(column)) == expected) continue
                    val bytes = java.io.ByteArrayOutputStream()
                    java.io.DataOutputStream(bytes).use { out ->
                        for (index in 0 until cursor.columnCount) {
                            val type = cursor.getType(index); out.writeInt(type)
                            if (type != android.database.Cursor.FIELD_TYPE_NULL) {
                                val value = if (type == android.database.Cursor.FIELD_TYPE_BLOB) cursor.getBlob(index) else cursor.getString(index).toByteArray()
                                out.writeInt(value.size); out.write(value)
                            }
                        }
                    }
                    hashes += java.security.MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray())
                        .joinToString("") { "%02x".format(it) }
                }
            }
            hashes.sorted()
        }
    }

    @After fun cleanup() {
        val errors = mutableListOf<Throwable>()
        fun attempt(block: () -> Unit) { try { block() } catch (failure: Throwable) { errors += failure } }
        attempt {
            instrumentation.runOnMainSync {
                resumed()?.let(ownedActivities::add)
                ownedActivities.filterNot { it.isDestroyed }.forEach(Activity::finish)
            }
            await { ownedActivities.all { it.isDestroyed } }
        }
        attempt {
            if (started) instrumentation.runOnMainSync { context.stopService(Intent(context, VideoPlayService::class.java)) }
            await { services().isEmpty() }
            if (started) instrumentation.runOnMainSync { VideoPlay.stopLoading(); VideoPlay.releaseAllVideos() }
            globalExecutor.submit {}.get(10, TimeUnit.SECONDS)
        }
        attempt { if (ownedJournal) runBlocking { backupRestoreMutex.recover {
            RestoreFileRecovery.recover(context, cleanup = true)
            assertEquals(beforeJournal, context.defaultSharedPreferences.all)
            RestoreRecoveryGate.onStartupReady()
        } } }
        if (!RestoreRecoveryGate.isQuarantined) {
            attempt { if (parentOwned) dao.close(requestId, id); extraParents.forEach { dao.close(it, id) } }
            attempt { fixtureBook?.let { book -> appDb.bookChapterDao.delByBook(book.bookUrl); appDb.bookDao.delete(book); BookHelp.clearCache(book) } }
            attempt { if (inserted) appDb.bookSourceDao.delete(source) }
            attempt {
                prefs.edit().apply { writeValues(this, originalVideoPreferences, listOf("autoPlay", "defaultFloatWindow", "startFull")); check(commit()) }
                assertEquals(originalVideoPreferences, prefs.all)
            }
            attempt { clip.delete() }
        }
        attempt { instrumentation.runOnMainSync {
            if (ownedJournal) ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<io.legado.app.ui.recovery.RestoreRecoveryActivity>().forEach(Activity::finish)
            if (::main.isInitialized && !main.isDestroyed) main.finish()
        } }
        attempt { overlayMode?.let { shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW $it") } }
        if (errors.isEmpty()) ledger.deleteRecursively()
        else throw AssertionError("Owned handoff fixture cleanup failed; before-images retained in ${ledger.name}", errors.first())
    }

    private fun writeValues(editor: android.content.SharedPreferences.Editor, values: Map<String, *>, keys: Collection<String>) {
        for (key in keys) when (val value = values[key]) {
            null -> editor.remove(key)
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is String -> editor.putString(key, value)
            is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            else -> error("Unsupported preference type")
        }
    }

    private val requestId = "handoff-parent-$id"
    private val childId = "handoff-child-$id"
    private var parentOwned = false
    private val dao get() = appDb.bookInfoRequestDao

    private fun open(durable: Boolean, defaultFloating: Boolean = false) {
        VideoPlay.defaultFloatWindow = defaultFloating
        val book = Book(bookUrl = "handoff-book-$id", origin = source.bookSourceUrl, name = id,
            type = BookType.video, durChapterPos = 6400, totalChapterNum = 1)
        fixtureBook = book
        appDb.bookDao.insert(book)
        appDb.bookChapterDao.insert(BookChapter(bookUrl = book.bookUrl, url = clipUrl, title = id, index = 0))
        if (durable) {
            dao.claim(BookInfoRequest(requestId, book.bookUrl, book.bookUrl, id, updatedAt = 1), id)
            parentOwned = true
            checkNotNull(dao.begin(requestId, id, 0, book.bookUrl, childId,
                BookInfoChildKind.Reader, BookInfoNavigationPosition(0, 6400), 2))
        }
        val input = Intent(context, VideoPlayerActivity::class.java).putExtra("bookUrl", book.bookUrl)
            .putExtra("forceNormalPlayer", !defaultFloating)
        if (durable) input.putExtra(PlatformReaderReturnRepository.REQUEST_ID, childId)
        instrumentation.runOnMainSync { started = true; main.startActivity(input) }
        if (defaultFloating) await { services().singleOrNull() != null && resumed() == null && playing(FloatingPlayer::class.java) }
        else await { resumed() != null && playing(VideoPlayer::class.java) }
        if (durable) assertDisplayed()
    }

    private fun floatPlayer(): VideoPlayService {
        var label = ""
        lateinit var outgoing: VideoPlayerActivity
        instrumentation.runOnMainSync { outgoing = resumed()!!; ownedActivities.add(outgoing); label = outgoing.getString(R.string.float_window) }
        compose.onNodeWithContentDescription(label).performClick()
        var service: VideoPlayService? = null
        await {
            service = services().singleOrNull()
            service != null && outgoing.isDestroyed && resumed() == null && playing(FloatingPlayer::class.java) &&
                (VideoPlay.videoManager.listener() as FloatingPlayer).isAttachedToWindow
        }
        return checkNotNull(service)
    }

    private fun fullscreen(service: VideoPlayService) {
        instrumentation.runOnMainSync { (VideoPlay.videoManager.listener() as FloatingPlayer).fullscreenB.performClick() }
        await { resumed() != null && service.lifecycle.currentState == Lifecycle.State.DESTROYED && playing(VideoPlayer::class.java) }
    }

    @Test fun decodedBookKeepsPlayingAcrossActivityFloatingActivityFloating() {
        open(false)
        var position = 0L
        instrumentation.runOnMainSync { position = VideoPlay.videoManager.currentPosition }
        val first = floatPlayer()
        instrumentation.runOnMainSync { assertTrue(VideoPlay.videoManager.currentPosition >= position - 500) }
        fullscreen(first)
        instrumentation.runOnMainSync { assertTrue(VideoPlay.videoManager.currentPosition >= position - 500); position = VideoPlay.videoManager.currentPosition }
        val second = floatPlayer()
        instrumentation.runOnMainSync { assertTrue(VideoPlay.videoManager.currentPosition >= position - 500) }
        instrumentation.runOnMainSync { context.startService(Intent(context, VideoPlayService::class.java).setAction(IntentAction.stop)) }
        await { second.lifecycle.currentState == Lifecycle.State.DESTROYED }
        globalExecutor.submit {}.get(10, TimeUnit.SECONDS)
        assertTrue(appDb.bookDao.getBook(fixtureBook!!.bookUrl)!!.durChapterPos >= 5900)
    }

    @Test fun delayedContentStartsTheFullscreenPlayerAtTheSavedPosition() {
        val requestStarted = CountDownLatch(1)
        val releaseResponse = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val worker = Thread {
            try {
                server.accept().use { socket ->
                    socket.soTimeout = 15_000
                    val reader = socket.getInputStream().bufferedReader()
                    while (true) {
                        val line = reader.readLine() ?: error("Incomplete HTTP request")
                        if (line.isEmpty()) break
                    }
                    requestStarted.countDown()
                    check(releaseResponse.await(15, TimeUnit.SECONDS))
                    val body = org.json.JSONObject().put("content", clipUrl).toString().toByteArray()
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(body)
                        flush()
                    }
                }
            } catch (error: Throwable) {
                failure.set(error)
                requestStarted.countDown()
            }
        }.apply { isDaemon = true; start() }
        try {
            source.getContentRule().content = "$.content"
            appDb.bookSourceDao.update(source)
            val book = Book(bookUrl = "handoff-book-$id", origin = source.bookSourceUrl, name = id,
                type = BookType.video, durChapterPos = 6400, totalChapterNum = 1)
            fixtureBook = book
            appDb.bookDao.insert(book)
            appDb.bookChapterDao.insert(BookChapter(bookUrl = book.bookUrl,
                url = "http://127.0.0.1:${server.localPort}/chapter", title = id, index = 0))
            instrumentation.runOnMainSync {
                started = true
                main.startActivity(Intent(context, VideoPlayerActivity::class.java)
                    .putExtra("bookUrl", book.bookUrl).putExtra("forceNormalPlayer", true))
            }
            assertTrue("Content request did not start", requestStarted.await(15, TimeUnit.SECONDS))
            failure.get()?.let { throw AssertionError("Content server failed", it) }
            var fullscreen: VideoPlayer? = null
            instrumentation.runOnMainSync {
                val activity = checkNotNull(resumed())
                checkNotNull(activity.findViewById<View>(R.id.fullscreen)).performClick()
                fullscreen = activity.findViewById(R.id.full_id)
                assertNotNull("Fullscreen player not created", fullscreen)
                assertEquals(6400L, fullscreen!!.seekOnStart)
            }
            releaseResponse.countDown()
            await { VideoPlay.videoManager.listener() === fullscreen && playing(VideoPlayer::class.java) }
            instrumentation.runOnMainSync {
                assertTrue(VideoPlay.videoManager.currentPosition >= 5900)
            }
            failure.get()?.let { throw AssertionError("Content server failed", it) }
        } finally {
            releaseResponse.countDown()
            server.close()
            worker.join(5000)
        }
    }

    @Test fun videoKeepsPlayingAcrossActualFreeformResizes() {
        assumeTrue(context.packageManager.hasSystemFeature(PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT))
        val originalSize = shell("wm size")
        val originalDensity = shell("wm density")
        val trace = org.json.JSONArray()
        try {
            // Give the real task room for the 840dp adaptive boundary.
            shell("wm size 2400x1600")
            shell("wm density 320")
            val launch = shell("am start --windowingMode 5 --activity-single-top -n ${context.packageName}/io.legado.app.ui.AppActivity")
            check(!launch.contains("Error") && !launch.contains("Exception")) { launch }
            var host: AppActivity? = null
            await {
                host = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<AppActivity>().firstOrNull()
                host?.isInMultiWindowMode == true
            }
            val previous = main
            main = checkNotNull(host)
            if (previous !== main && !previous.isDestroyed) instrumentation.runOnMainSync { previous.finish() }
            assertTrue(clip.delete())
            clip.writeBytes(syntheticVideoFixture(clip, frameCount = 300, patterned = true))
            open(true)
            val playerActivity = checkNotNull(instrumentation.run {
                var value: VideoPlayerActivity? = null
                runOnMainSync { value = resumed() }
                value
            })
            assertTrue("Video activity is not in a real multi-window task", playerActivity.isInMultiWindowMode)
            val taskId = playerActivity.taskId
            var previousPosition = 0L
            instrumentation.runOnMainSync { previousPosition = VideoPlay.videoManager.currentPosition }
            for (widthDp in listOf(360, 599, 600, 839, 840, 900)) {
                val width = widthDp * 2
                val resize = shell("am task resize $taskId 50 50 ${50 + width} 1250")
                check(!resize.contains("Error") && !resize.contains("Exception")) { resize }
                await { resumed()?.let { it.taskId == taskId && it.isInMultiWindowMode &&
                    kotlin.math.abs(it.window.decorView.width - width) <= 8 } == true && playing(VideoPlayer::class.java) }
                SystemClock.sleep(1_000)
                var position = 0L
                var playerBounds = android.graphics.Rect()
                var actualFontScale = 0f
                instrumentation.runOnMainSync {
                    position = VideoPlay.videoManager.currentPosition
                    actualFontScale = checkNotNull(resumed()).resources.configuration.fontScale
                    val player = VideoPlay.videoManager.listener() as VideoPlayer
                    val location = IntArray(2); player.getLocationOnScreen(location)
                    playerBounds = android.graphics.Rect(location[0], location[1], location[0] + player.width, location[1] + player.height)
                }
                assertTrue("Video position moved backwards after resizing: $previousPosition -> $position", position >= previousPosition - 500)
                previousPosition = position
                val screenshot = instrumentation.uiAutomation.takeScreenshot()
                try {
                    val bounds = android.graphics.Rect(playerBounds)
                    assertTrue("Video player has no visible bounds: $bounds", bounds.intersect(0, 0, screenshot.width, screenshot.height))
                    var dark = 0; var light = 0
                    // A regular 10% grid aliases the checkerboard boundaries;
                    // stagger the samples so they cover both tile interiors.
                    for (sample in 1..150) {
                        val x = 17 + (sample * 37) % 67
                        val y = 17 + (sample * 53) % 67
                        val color = screenshot.getPixel(bounds.left + bounds.width() * x / 100, bounds.top + bounds.height() * y / 100)
                        val luminance = (android.graphics.Color.red(color) + android.graphics.Color.green(color) + android.graphics.Color.blue(color)) / 3
                        if (luminance < 85) dark++ else if (luminance > 165) light++
                    }
                    File(context.cacheDir, "video-freeform-$widthDp.png").outputStream().use {
                        screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                    trace.put(org.json.JSONObject().put("width_dp", widthDp).put("task_id", taskId)
                        .put("font_scale", actualFontScale).put("position", position)
                        .put("player_bounds", bounds.toShortString()).put("dark", dark).put("light", light))
                    File(context.cacheDir, "video-freeform-samples.json").writeText(trace.toString(2))
                    assertTrue("Video checkerboard is not visible at ${widthDp}dp: dark=$dark light=$light", dark >= 10 && light >= 10)
                } finally { screenshot.recycle() }
                assertDisplayed()
            }
            instrumentation.runOnMainSync { playerActivity.finish() }
            await { playerActivity.isDestroyed && resumed() == null }
            globalExecutor.submit {}.get(10, TimeUnit.SECONDS)
            assertEquals(BookInfoChildPhase.Returned, dao.child(childId)!!.phase)
            assertEquals(fixtureBook!!.bookUrl, dao.child(childId)!!.returnBookUrl)
            assertTrue("Saved video position moved backwards on return",
                appDb.bookDao.getBook(fixtureBook!!.bookUrl)!!.durChapterPos >= previousPosition - 500)
        } finally {
            instrumentation.runOnMainSync { resumed()?.finish(); if (!main.isDestroyed) main.finish() }
            val size = Regex("Override size: (\\d+x\\d+)").find(originalSize)?.groupValues?.get(1)
            val density = Regex("Override density: (\\d+)").find(originalDensity)?.groupValues?.get(1)
            shell("wm size ${size ?: "reset"}")
            shell("wm density ${density ?: "reset"}")
        }
    }

    @Test fun durableRequestStaysDisplayedUntilTheFinalFloatingPlayerStops() {
        open(true)
        val first = floatPlayer()
        globalExecutor.submit {}.get(10, TimeUnit.SECONDS)
        assertDisplayed()
        fullscreen(first)
        assertDisplayed()
        val second = floatPlayer()
        globalExecutor.submit {}.get(10, TimeUnit.SECONDS)
        assertDisplayed()
        instrumentation.runOnMainSync { context.startService(Intent(context, VideoPlayService::class.java).setAction(IntentAction.stop)) }
        await { second.lifecycle.currentState == Lifecycle.State.DESTROYED }
        globalExecutor.submit {}.get(10, TimeUnit.SECONDS)
        assertEquals(BookInfoChildPhase.Returned, dao.child(childId)!!.phase)
        assertEquals(fixtureBook!!.bookUrl, dao.child(childId)!!.returnBookUrl)
        assertTrue(appDb.bookDao.getBook(fixtureBook!!.bookUrl)!!.durChapterPos >= 5900)
    }

    @Test fun defaultFloatingEntryKeepsTheRequestAcrossTheFirstActivityDestruction() {
        open(true, defaultFloating = true)
        globalExecutor.submit {}.get(10, TimeUnit.SECONDS)
        assertDisplayed()
        val service = services().single()
        fullscreen(service)
        assertDisplayed()
        lateinit var finished: VideoPlayerActivity
        instrumentation.runOnMainSync { finished = resumed()!!; finished.finish() }
        await { finished.isDestroyed && resumed() == null }
        globalExecutor.submit {}.get(10, TimeUnit.SECONDS)
        assertEquals(BookInfoChildPhase.Returned, dao.child(childId)!!.phase)
    }

    @Test fun recreatingTheReturnedFullscreenHostDoesNotReplayItsOldHandoff() {
        open(true)
        fullscreen(floatPlayer())
        lateinit var old: VideoPlayerActivity
        var position = 0L
        instrumentation.runOnMainSync { old = resumed()!!; position = VideoPlay.videoManager.currentPosition; old.recreate() }
        await { resumed() != null && resumed() !== old && old.isDestroyed && playing(VideoPlayer::class.java) }
        instrumentation.runOnMainSync { assertTrue(VideoPlay.videoManager.currentPosition >= position - 500) }
        assertDisplayed()
        lateinit var finished: VideoPlayerActivity
        instrumentation.runOnMainSync { finished = resumed()!!; finished.finish() }
        await { finished.isDestroyed && resumed() == null }
        globalExecutor.submit {}.get(10, TimeUnit.SECONDS)
        assertEquals(BookInfoChildPhase.Returned, dao.child(childId)!!.phase)
    }

    @Test fun recoveryDuringTheQueuedFullscreenHandoffDoesNotPublishAReturnOrWriteProgress() {
        open(true)
        val service = floatPlayer()
        globalExecutor.submit {}.get(10, TimeUnit.SECONDS)
        val beforeBook = appDb.bookDao.getBook(fixtureBook!!.bookUrl)!!
        val beforeChild = dao.child(childId)!!
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val blocker = globalExecutor.submit { entered.countDown(); check(release.await(15, TimeUnit.SECONDS)) }
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            instrumentation.runOnMainSync {
                (VideoPlay.videoManager.listener() as FloatingPlayer).fullscreenB.performClick()
                runBlocking { backupRestoreMutex.recover {
                    beforeJournal = HashMap(context.defaultSharedPreferences.all)
                    val transaction = RestoreSettingsTransaction.prepare(context, UUID.randomUUID().toString(), false)
                    ownedJournal = true
                    transaction.editDefault().putString("handoff-fault-$id", "pending").apply()
                    RestoreRecoveryGate.requireRecovery()
                } }
            }
        } finally { release.countDown(); blocker.get(10, TimeUnit.SECONDS) }
        await { service.lifecycle.currentState == Lifecycle.State.DESTROYED && services().isEmpty() &&
            ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .any { it is io.legado.app.ui.recovery.RestoreRecoveryActivity } }
        globalExecutor.submit {}.get(10, TimeUnit.SECONDS)
        assertEquals(beforeBook, appDb.bookDao.getBook(beforeBook.bookUrl))
        assertEquals(beforeChild, dao.child(childId))
        assertFalse(VideoPlay.videoManager.isPlaying)
    }

    @Test fun stoppingWhileFullscreenClaimIsQueuedCannotLeaveAReaderOrReceiptAlive() {
        open(true)
        val service = floatPlayer()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val blocker = globalExecutor.submit { entered.countDown(); check(release.await(20, TimeUnit.SECONDS)) }
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            instrumentation.runOnMainSync { (VideoPlay.videoManager.listener() as FloatingPlayer).fullscreenB.performClick() }
            await { resumed() != null }
            instrumentation.runOnMainSync { context.startService(Intent(context, VideoPlayService::class.java).setAction(IntentAction.stop)) }
            await { service.lifecycle.currentState == Lifecycle.State.DESTROYED }
        } finally { release.countDown(); blocker.get(10, TimeUnit.SECONDS) }
        globalExecutor.submit {}.get(10, TimeUnit.SECONDS)
        instrumentation.waitForIdleSync()
        assertEquals(BookInfoChildPhase.Returned, dao.child(childId)!!.phase)
        await { resumed() == null && services().isEmpty() }
        instrumentation.runOnMainSync { assertFalse(VideoPlay.videoManager.isPlaying) }
    }

    @Test fun aSupersededQueuedStartCannotStopOrSeekTheNewerRequest() {
        open(true)
        val service = floatPlayer()
        val second = newRequest("second", 12000)
        val third = newRequest("third", 18000)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val blocker = globalExecutor.submit { entered.countDown(); check(release.await(20, TimeUnit.SECONDS)) }
        val startId = VideoPlayService::class.java.getDeclaredField("latestPlaybackStartId").apply { isAccessible = true }
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            var previous = 0
            instrumentation.runOnMainSync { previous = startId.getInt(service); context.startService(requestIntent(second)) }
            await { startId.getInt(service) > previous }
            instrumentation.runOnMainSync { previous = startId.getInt(service); context.startService(requestIntent(third)) }
            await { startId.getInt(service) > previous }
        } finally { release.countDown(); blocker.get(10, TimeUnit.SECONDS) }
        await { playing(FloatingPlayer::class.java) && VideoPlay.videoManager.currentPosition >= 17500 &&
            dao.child(third)?.phase == BookInfoChildPhase.Displayed }
        assertEquals(BookInfoChildPhase.Returned, dao.child(childId)!!.phase)
        assertNotEquals(BookInfoChildPhase.Displayed, dao.child(second)!!.phase)
        assertTrue(service.lifecycle.currentState != Lifecycle.State.DESTROYED)
        instrumentation.runOnMainSync { context.startService(Intent(context, VideoPlayService::class.java).setAction(IntentAction.stop)) }
        await { service.lifecycle.currentState == Lifecycle.State.DESTROYED }
        globalExecutor.submit {}.get(10, TimeUnit.SECONDS)
        assertEquals(BookInfoChildPhase.Returned, dao.child(third)!!.phase)
        assertTrue(appDb.bookDao.getBook(fixtureBook!!.bookUrl)!!.durChapterPos >= 17500)
    }

    @Test fun aNewPlainInputCannotBeReplacedByAnOlderQueuedOwnedInitialization() {
        open(false)
        val queued = newRequest("queued-native", 18000)
        val marker = UUID.randomUUID().toString()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val blocker = globalExecutor.submit { entered.countDown(); check(release.await(20, TimeUnit.SECONDS)) }
        lateinit var receiver: VideoPlayerActivity
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            instrumentation.runOnMainSync {
                receiver = resumed()!!
                main.startActivity(Intent(context, VideoPlayerActivity::class.java)
                    .putExtra("bookUrl", fixtureBook!!.bookUrl).putExtra("forceNormalPlayer", true)
                    .putExtra(PlatformReaderReturnRepository.REQUEST_ID, queued))
            }
            await { resumed() === receiver && receiver.intent.getStringExtra(PlatformReaderReturnRepository.REQUEST_ID) == queued }
            instrumentation.runOnMainSync {
                main.startActivity(Intent(context, VideoPlayerActivity::class.java)
                    .putExtra("bookUrl", fixtureBook!!.bookUrl).putExtra("forceNormalPlayer", true)
                    .putExtra("replacementInput", marker))
            }
            await { resumed() === receiver && receiver.intent.getStringExtra("replacementInput") == marker }
        } finally { release.countDown(); blocker.get(10, TimeUnit.SECONDS) }
        globalExecutor.submit {}.get(10, TimeUnit.SECONDS)
        // Give the old asynchronous completion a main-loop turn, then observe the real player.
        instrumentation.waitForIdleSync()
        await { playing(VideoPlayer::class.java) }
        instrumentation.runOnMainSync {
            assertEquals("An obsolete preparation replaced the new plain input", marker,
                receiver.intent.getStringExtra("replacementInput"))
            assertFalse(receiver.isFinishing)
        }
        val unclaimed = checkNotNull(dao.child(queued))
        assertEquals(BookInfoChildPhase.Requested, unclaimed.phase)
        assertEquals(18000, unclaimed.position?.chapterPosition)
    }

    @Test fun aRepeatedFullscreenClickWhileTheClaimIsQueuedIsIgnored() {
        open(true)
        val service = floatPlayer()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val blocker = globalExecutor.submit { entered.countDown(); check(release.await(20, TimeUnit.SECONDS)) }
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            instrumentation.runOnMainSync {
                val player = VideoPlay.videoManager.listener() as FloatingPlayer
                assertTrue(player.fullscreenB.performClick())
                assertTrue(player.fullscreenB.performClick())
            }
        } finally { release.countDown(); blocker.get(10, TimeUnit.SECONDS) }
        await { resumed() != null && service.lifecycle.currentState == Lifecycle.State.DESTROYED && playing(VideoPlayer::class.java) }
        assertDisplayed()
    }

    @Test fun stoppingBeforeTheFloatingReceiverClaimsCannotLeavePlaybackOrAReceiptAlive() {
        open(true)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val blocker = globalExecutor.submit { entered.countDown(); check(release.await(20, TimeUnit.SECONDS)) }
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            lateinit var outgoing: VideoPlayerActivity
            var label = ""
            instrumentation.runOnMainSync { outgoing = resumed()!!; label = outgoing.getString(R.string.float_window) }
            compose.onNodeWithContentDescription(label).performClick()
            var receiver: VideoPlayService? = null
            await { receiver = services().singleOrNull(); outgoing.isDestroyed && receiver != null }
            instrumentation.runOnMainSync { context.startService(Intent(context, VideoPlayService::class.java).setAction(IntentAction.stop)) }
            await { receiver!!.lifecycle.currentState == Lifecycle.State.DESTROYED }
        } finally { release.countDown(); blocker.get(10, TimeUnit.SECONDS) }
        globalExecutor.submit {}.get(10, TimeUnit.SECONDS)
        assertEquals(BookInfoChildPhase.Returned, dao.child(childId)!!.phase)
        await { resumed() == null && services().isEmpty() }
        instrumentation.runOnMainSync { assertFalse(VideoPlay.videoManager.isPlaying) }
    }

    @Test fun anUnownedQueuedFullscreenReceiverCanBeStopped() = queuedUnownedStop(revokeReceipt = false)

    @Test fun aQueuedFullscreenReceiverWithAClosedReceiptCanBeStopped() = queuedUnownedStop(revokeReceipt = true)

    private fun queuedUnownedStop(revokeReceipt: Boolean) {
        open(revokeReceipt)
        if (revokeReceipt) PlatformReaderReturnRepository(appDb).closeBridge(childId, fixtureBook!!.bookUrl)
        val service = floatPlayer()
        val before = if (revokeReceipt) dao.child(childId) else null
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val blocker = globalExecutor.submit { entered.countDown(); check(release.await(20, TimeUnit.SECONDS)) }
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            instrumentation.runOnMainSync { (VideoPlay.videoManager.listener() as FloatingPlayer).fullscreenB.performClick() }
            await { resumed() != null }
            instrumentation.runOnMainSync { context.startService(Intent(context, VideoPlayService::class.java).setAction(IntentAction.stop)) }
            await { service.lifecycle.currentState == Lifecycle.State.DESTROYED }
        } finally { release.countDown(); blocker.get(10, TimeUnit.SECONDS) }
        globalExecutor.submit {}.get(10, TimeUnit.SECONDS)
        await { resumed() == null && services().isEmpty() }
        if (revokeReceipt) assertEquals(before, dao.child(childId))
        instrumentation.runOnMainSync { assertFalse(VideoPlay.videoManager.isPlaying) }
    }

    @Test fun aDefaultUnownedFloatingPlayerCanReturnToFullscreen() {
        open(false, defaultFloating = true)
        val service = services().single()
        fullscreen(service)
        instrumentation.runOnMainSync { assertTrue(VideoPlay.videoManager.isPlaying); resumed()!!.finish() }
        await { resumed() == null && services().isEmpty() }
    }

    @Test fun aLateFloatingStopAlsoFinishesItsAlreadyPlayingUnownedReceiver() {
        open(false)
        val source = floatPlayer()
        lateinit var delayedClose: android.view.View
        instrumentation.runOnMainSync { delayedClose = (VideoPlay.videoManager.listener() as FloatingPlayer).backButton }
        fullscreen(source)
        lateinit var receiver: VideoPlayerActivity
        instrumentation.runOnMainSync { receiver = resumed()!! }
        // Dispatch the original real button callback after its source window has retired.
        instrumentation.runOnMainSync { assertTrue(delayedClose.performClick()) }
        await { receiver.isDestroyed && resumed() == null && services().isEmpty() && !VideoPlay.videoManager.isPlaying }
        instrumentation.runOnMainSync { assertFalse(VideoPlay.videoManager.isPlaying) }
    }

    @Test fun aLateFloatingStopDoesNotFinishANewIndependentInputForTheSameBook() {
        open(false)
        val source = floatPlayer()
        lateinit var delayedClose: android.view.View
        instrumentation.runOnMainSync { delayedClose = (VideoPlay.videoManager.listener() as FloatingPlayer).backButton }
        fullscreen(source)
        val marker = UUID.randomUUID().toString()
        instrumentation.runOnMainSync {
            main.startActivity(Intent(context, VideoPlayerActivity::class.java)
                .putExtra("bookUrl", fixtureBook!!.bookUrl).putExtra("forceNormalPlayer", true)
                .putExtra("replacementInput", marker))
        }
        await { resumed()?.intent?.getStringExtra("replacementInput") == marker && playing(VideoPlayer::class.java) }
        instrumentation.runOnMainSync { assertTrue(delayedClose.performClick()) }
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            assertEquals(marker, resumed()!!.intent.getStringExtra("replacementInput"))
            assertTrue(VideoPlay.videoManager.isPlaying)
            resumed()!!.finish()
        }
        await { resumed() == null && services().isEmpty() }
    }

    @Test fun playingFullscreenCheckpointsWithoutLeavingThePageOrFinishingItsReceipt() {
        open(true)
        await { appDb.bookDao.getBook(fixtureBook!!.bookUrl)!!.durChapterPos >= 10500 && playing(VideoPlayer::class.java) }
        instrumentation.runOnMainSync { assertNotNull(resumed()) }
        assertDisplayed()
    }

    @Test fun playingFloatingCheckpointsWithoutClosingItsWindowOrReceipt() {
        open(true)
        val service = floatPlayer()
        globalExecutor.submit {}.get(10, TimeUnit.SECONDS)
        val initial = appDb.bookDao.getBook(fixtureBook!!.bookUrl)!!.durChapterPos
        await { appDb.bookDao.getBook(fixtureBook!!.bookUrl)!!.durChapterPos >= initial + 4000 && playing(FloatingPlayer::class.java) }
        assertTrue(service.lifecycle.currentState != Lifecycle.State.DESTROYED)
        assertDisplayed()
    }

    @Test fun pausingAfterASeekSavesTheCurrentPositionBeforeLeavingThePage() {
        open(true)
        instrumentation.runOnMainSync { (VideoPlay.videoManager.listener() as VideoPlayer).seekTo(18000) }
        await { VideoPlay.videoManager.currentPosition >= 17500 }
        instrumentation.runOnMainSync { (VideoPlay.videoManager.listener() as VideoPlayer).onVideoPause() }
        globalExecutor.submit {}.get(10, TimeUnit.SECONDS)
        assertTrue(appDb.bookDao.getBook(fixtureBook!!.bookUrl)!!.durChapterPos >= 17500)
        instrumentation.runOnMainSync { assertNotNull(resumed()) }
        assertDisplayed()
    }

    private val extraParents = mutableListOf<String>()
    private fun newRequest(name: String, position: Int): String {
        val parent = "handoff-$name-parent-$id"
        val child = "handoff-$name-child-$id"
        extraParents += parent
        val stateFile = File(ledger, "state.json")
        val state = org.json.JSONObject(stateFile.readText()).put("extraParents", org.json.JSONArray(extraParents))
        java.io.FileOutputStream(stateFile).use { it.write(state.toString().toByteArray()); it.fd.sync() }
        val book = fixtureBook!!
        dao.claim(BookInfoRequest(parent, book.bookUrl, book.bookUrl, id, updatedAt = 1), id)
        checkNotNull(dao.begin(parent, id, 0, book.bookUrl, child, BookInfoChildKind.Reader,
            BookInfoNavigationPosition(0, position), 2))
        return child
    }
    private fun requestIntent(child: String) = Intent(context, VideoPlayService::class.java)
        .putExtra("bookUrl", fixtureBook!!.bookUrl).putExtra("isNew", true)
        .putExtra(PlatformReaderReturnRepository.REQUEST_ID, child)

    private fun assertDisplayed() {
        assertEquals(BookInfoChildPhase.Displayed, dao.child(childId)!!.phase)
        assertNull(dao.child(childId)!!.position)
    }
    private fun playing(type: Class<*>) = type.isInstance(VideoPlay.videoManager.listener()) &&
        VideoPlay.videoManager.isPlaying && VideoPlay.videoManager.currentVideoWidth == 160 &&
        VideoPlay.videoManager.currentPosition >= 5900
    private fun resumed() = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
        .filterIsInstance<VideoPlayerActivity>().singleOrNull()?.also(ownedActivities::add)

    @Suppress("UNCHECKED_CAST")
    private fun services(): List<VideoPlayService> = (LifecycleHelp::class.java.getDeclaredField("services").apply { isAccessible = true }
        .get(LifecycleHelp) as List<WeakReference<*>>).mapNotNull { it.get() as? VideoPlayService }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 30_000
        do {
            var ready = false
            instrumentation.runOnMainSync { ready = condition() }
            if (ready) return
            SystemClock.sleep(50)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError("Floating video condition was not met")
    }
}
