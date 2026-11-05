package io.legado.app.ui.book.read

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.espresso.Espresso
import io.legado.app.lib.permission.PermissionActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import fi.iki.elonen.NanoHTTPD
import io.legado.app.R
import io.legado.app.constant.BookType
import io.legado.app.constant.Status
import io.legado.app.data.appDb
import io.legado.app.data.entities.*
import io.legado.app.help.book.BookHelp
import io.legado.app.help.storage.*
import kotlinx.coroutines.runBlocking
import io.legado.app.help.globalExecutor
import io.legado.app.model.AudioPlay
import io.legado.app.model.VideoPlay
import io.legado.app.service.AudioPlayService
import io.legado.app.service.VideoPlayService
import io.legado.app.help.gsyVideo.FloatingPlayer
import io.legado.app.help.gsyVideo.VideoPlayer
import io.legado.app.ui.AppActivity
import io.legado.app.ui.book.audio.AudioPlayActivity
import io.legado.app.ui.navigation.*
import io.legado.app.ui.video.VideoPlayerActivity
import io.legado.app.utils.defaultSharedPreferences
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class PlatformMediaEntryUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val id = UUID.randomUUID().toString()
    private val requests = AtomicInteger()
    @Volatile private var video: ByteArray? = null
    private val samples = ByteArray(8000 * 2 * 40)
    private val wav by lazy {
        ByteBuffer.allocate(44 + samples.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + samples.size); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(8000); putInt(16000); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(samples.size); put(samples)
        }.array()
    }
    private val server = object : NanoHTTPD("127.0.0.1", 0) {
        override fun serve(session: IHTTPSession): Response {
            requests.incrementAndGet()
            val bytes = video ?: wav
            return newFixedLengthResponse(Response.Status.OK, if (video == null) "audio/wav" else "video/mp4",
                bytes.inputStream(), bytes.size.toLong())
        }
    }
    private lateinit var source: BookSource
    private lateinit var book: Book
    private lateinit var route: BookInfoRoute
    private var scenario: ActivityScenario<AppActivity>? = null
    private var opened: Activity? = null
    private var oldAutoPlay = false
    private var oldFloat = false
    private var oldFull = false
    private var overlayMode: String? = null
    private fun overlayShell(command: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
    private fun allowOwnedOverlay() {
        val state = overlayShell("appops get ${context.packageName} SYSTEM_ALERT_WINDOW")
        overlayMode = Regex("SYSTEM_ALERT_WINDOW: (allow|deny|ignore|default|foreground)").find(state)?.groupValues?.get(1)
            ?: if (state.contains("No operations")) "default" else error("Unknown overlay app-op")
        overlayShell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")
    }

    @Before fun setup() {
        server.start()
        oldAutoPlay = VideoPlay.autoPlay; oldFloat = VideoPlay.defaultFloatWindow; oldFull = VideoPlay.startFull
        VideoPlay.autoPlay = false; VideoPlay.defaultFloatWindow = false; VideoPlay.startFull = false
    }
    private fun fixture(type: Int) {
        source = BookSource("http://127.0.0.1:${server.listeningPort}/$id", "Media fixture")
        book = Book(bookUrl = source.bookSourceUrl + "/book", origin = source.bookSourceUrl,
            tocUrl = source.bookSourceUrl + "/toc", name = "Media $id", author = id,
            type = type, totalChapterNum = 2, durChapterPos = 6400)
        route = BookInfoRoute(book.bookUrl)
        appDb.bookSourceDao.insert(source); appDb.bookDao.insert(book)
        repeat(2) { index ->
            appDb.bookChapterDao.insert(BookChapter(bookUrl = book.bookUrl,
                url = "http://127.0.0.1:${server.listeningPort}/media/$index.${if (type == BookType.video) "mp4" else "wav"}", title = "Chapter $index", index = index))
        }
    }
    @After fun cleanup() {
        try {
            dismissPermissionRationale()
            instrumentation.runOnMainSync {
                opened?.takeUnless { it.isDestroyed }?.finish()
                AudioPlay.stop()
                context.stopService(Intent(context, AudioPlayService::class.java))
                context.stopService(Intent(context, VideoPlayService::class.java))
            }
            instrumentation.waitForIdleSync()
            dismissPermissionRationale()
            val scenarioFailure = runCatching { scenario?.close() }.exceptionOrNull()
            globalExecutor.submit {}.get(10, TimeUnit.SECONDS)
            if (::route.isInitialized) {
                for (request in listOf(route.requestId, "seek-detail-$id")) {
                    appDb.bookInfoRequestDao.get(request)?.let { appDb.bookInfoRequestDao.close(it.id, it.ownerToken) }
                }
            }
            if (::book.isInitialized) { appDb.bookDao.delete(book); BookHelp.clearCache(book) }
            if (::source.isInitialized) appDb.bookSourceDao.delete(source)
            VideoPlay.autoPlay = oldAutoPlay; VideoPlay.defaultFloatWindow = oldFloat; VideoPlay.startFull = oldFull
            server.stop()
            scenarioFailure?.let { throw it }
        } finally { overlayMode?.let { overlayShell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW $it") } }
    }
    private fun <T : Activity> resumed(host: Class<T>): T? {
        var activity: T? = null
        instrumentation.runOnMainSync {
            activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .firstOrNull { host.isInstance(it) }?.let(host::cast)
        }
        return activity
    }
    private fun <T : Activity> openDetail(host: Class<T>): BookInfoChildRequest {
        scenario = ActivityScenario.launch(AppActivity.startIntent(context, route))
        var reading = ""
        scenario!!.onActivity { reading = it.getString(R.string.continue_read) }
        try {
            compose.waitUntil(15_000) { compose.onAllNodesWithText(reading).fetchSemanticsNodes().isNotEmpty() }
        } catch (error: Throwable) {
            compose.onAllNodes(isRoot(), useUnmergedTree = true).printToLog("MediaTest", maxDepth = 50)
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                java.io.File(context.cacheDir, "media-detail-failure.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
            var destination: AppRoute? = null
            scenario!!.onActivity { destination = it.currentDestination }
            throw AssertionError("Detail not ready: expected=$route actual=$destination label=$reading request=${appDb.bookInfoRequestDao.get(route.requestId)}", error)
        }
        compose.onNodeWithText(reading).performClick()
        compose.waitUntil(20_000) {
            dismissPermissionRationale()
            opened = resumed(host)
            opened != null && appDb.bookInfoRequestDao.activeChild(route.requestId)?.phase == BookInfoChildPhase.Displayed
        }
        return appDb.bookInfoRequestDao.activeChild(route.requestId)!!
    }
    private fun dismissPermissionRationale() {
        instrumentation.runOnMainSync {
            val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<PermissionActivity>().firstOrNull() ?: return@runOnMainSync
            val field = PermissionActivity::class.java.getDeclaredField("rationaleDialog").apply { isAccessible = true }
            val dialog = field.get(activity) as? androidx.appcompat.app.AlertDialog
            // Cancel this known request's dialog; a global Back can hit the detail task instead.
            if (!activity.isFinishing && dialog?.isShowing == true) dialog.cancel()
        }
    }

    private fun finishAndCheckReturn(child: BookInfoChildRequest) {
        instrumentation.runOnMainSync { opened!!.finish() }
        compose.waitUntil(20_000) {
            dismissPermissionRationale()
            var atDetail = false
            scenario!!.onActivity { atDetail = it.currentDestination == route }
            atDetail && appDb.bookInfoRequestDao.child(child.id)?.phase == BookInfoChildPhase.Consumed
        }
        assertEquals(book.bookUrl, appDb.bookInfoRequestDao.child(child.id)!!.returnBookUrl)
    }

    @Test fun audioDetailUsesTheMatureHostAndKeepsBackgroundPlaybackAfterReturning() {
        fixture(BookType.audio)
        val child = openDetail(AudioPlayActivity::class.java)
        compose.waitUntil(15_000) { AudioPlay.book?.bookUrl == book.bookUrl && AudioPlay.durChapter != null }
        val playLabel = opened!!.getString(R.string.audio_play)
        try {
            compose.waitUntil(15_000) {
                compose.onAllNodesWithContentDescription(playLabel, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
        } catch (error: Throwable) {
            compose.onAllNodes(isRoot(), useUnmergedTree = true).printToLog("MediaTest", maxDepth = 50)
            throw AssertionError("Audio play button not found; label=$playLabel, host=${opened?.javaClass?.name}, book=${AudioPlay.book?.bookUrl}", error)
        }
        compose.onNodeWithContentDescription(playLabel, useUnmergedTree = true).performClick()
        compose.waitUntil(25_000) { requests.get() > 0 && AudioPlay.status == Status.PLAY && AudioPlayService.isRun }
        compose.waitUntil(10_000) {
            dismissPermissionRationale()
            resumed(AudioPlayActivity::class.java) != null
        }
        finishAndCheckReturn(child)
        assertEquals(Status.PLAY, AudioPlay.status)
        assertTrue(AudioPlayService.isRun)
        assertEquals(book.bookUrl, AudioPlay.book!!.bookUrl)
    }

    @Test fun audioRecoveryStopsBackgroundPlaybackAndRejectsLateTransport() {
        fixture(BookType.audio)
        val child = openDetail(AudioPlayActivity::class.java)
        compose.waitUntil(15_000) { AudioPlay.book?.bookUrl == book.bookUrl && AudioPlay.durChapter != null }
        val playLabel = opened!!.getString(R.string.audio_play)
        compose.waitUntil(15_000) {
            compose.onAllNodesWithContentDescription(playLabel, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription(playLabel, useUnmergedTree = true).performClick()
        compose.waitUntil(25_000) { requests.get() > 0 && AudioPlayService.isPlaying }
        compose.waitUntil(10_000) { dismissPermissionRationale(); resumed(AudioPlayActivity::class.java) != null }
        finishAndCheckReturn(child)
        assertTrue(AudioPlayService.isRun)
        // Freeze the writer before taking the baseline. Playback may legitimately advance while
        // the settings journal is being prepared; only writes after the fault must be rejected.
        val writerEntered = java.util.concurrent.CountDownLatch(1)
        val releaseWriter = java.util.concurrent.CountDownLatch(1)
        val writer = globalExecutor.submit { writerEntered.countDown(); check(releaseWriter.await(15, TimeUnit.SECONDS)) }
        assertTrue(writerEntered.await(10, TimeUnit.SECONDS))
        val oldBook = appDb.bookDao.getBook(book.bookUrl)!!
        val oldChapter = appDb.bookChapterDao.getChapter(book.bookUrl, 0)!!
        val oldPreferences = HashMap(context.defaultSharedPreferences.all)
        try {
            try {
                runBlocking {
                    backupRestoreMutex.recover {
                        val transaction = RestoreSettingsTransaction.prepare(context, UUID.randomUUID().toString(), false)
                        transaction.editDefault().putString("audio-recovery-$id", "pending").apply()
                        RestoreRecoveryGate.requireRecovery()
                    }
                }
            } finally { releaseWriter.countDown(); writer.get(10, TimeUnit.SECONDS) }
            compose.waitUntil(10_000) { !AudioPlayService.isRun && !AudioPlayService.isPlaying }
            instrumentation.runOnMainSync {
                AudioPlay.setSpeed(2.75f)
                AudioPlay.playPositionChanged(27_000)
                AudioPlay.saveDurChapter(123_456)
                AudioPlay.resume(context)
                AudioPlay.play()
            }
            globalExecutor.submit {}.get(10, TimeUnit.SECONDS)
            instrumentation.waitForIdleSync()
            val after = appDb.bookDao.getBook(book.bookUrl)!!
            assertEquals(oldBook.durChapterPos, after.durChapterPos)
            assertEquals(oldBook.durChapterTime, after.durChapterTime)
            assertEquals(oldBook.getPlaySpeed(), after.getPlaySpeed(), 0.001f)
            assertEquals(oldChapter.end, appDb.bookChapterDao.getChapter(book.bookUrl, 0)!!.end)
            assertFalse(AudioPlayService.isRun)
        } finally {
            runBlocking { backupRestoreMutex.recover {
                RestoreFileRecovery.recover(context, cleanup = true)
                assertEquals(oldPreferences, context.defaultSharedPreferences.all)
                RestoreRecoveryGate.onStartupReady()
            } }
        }
    }

    @Test fun audioSameChapterNewRequestSeeksOnceAndSurvivesRecreation() {
        fixture(BookType.audio)
        val originalChild = openDetail(AudioPlayActivity::class.java)
        compose.waitUntil(15_000) { AudioPlay.book?.bookUrl == book.bookUrl && AudioPlay.durChapter != null }
        val nextDetail = "seek-detail-$id"
        val nextChild = "seek-player-$id"
        val dao = appDb.bookInfoRequestDao
        dao.claim(BookInfoRequest(nextDetail, book.bookUrl, book.bookUrl, "seek-owner", updatedAt = 1), "seek-owner")
        checkNotNull(dao.begin(nextDetail, "seek-owner", 0, book.bookUrl, nextChild,
            BookInfoChildKind.Reader, BookInfoNavigationPosition(0, 12_000), 2))
        instrumentation.runOnMainSync {
            opened!!.startActivity(Intent(context, AudioPlayActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("bookUrl", book.bookUrl)
                .putExtra(io.legado.app.data.repository.PlatformReaderReturnRepository.REQUEST_ID, nextChild))
        }
        compose.waitUntil(15_000) {
            AudioPlay.durChapterPos == 12_000 && dao.child(nextChild)?.phase == BookInfoChildPhase.Displayed
        }
        assertEquals(BookInfoChildPhase.Returned, dao.child(originalChild.id)!!.phase)
        instrumentation.runOnMainSync { AudioPlay.adjustProgress(13_000) }
        globalExecutor.submit {}.get(10, TimeUnit.SECONDS)
        val previous = opened!!
        instrumentation.runOnMainSync { previous.recreate() }
        compose.waitUntil(15_000) {
            opened = resumed(AudioPlayActivity::class.java)
            opened != null && opened !== previous && AudioPlay.durChapterPos == 13_000
        }
        instrumentation.runOnMainSync { opened!!.finish() }
        compose.waitUntil(15_000) { dao.child(nextChild)?.phase == BookInfoChildPhase.Returned }
        assertEquals(13_000, appDb.bookDao.getBook(book.bookUrl)!!.durChapterPos)
        assertNull(dao.child(nextChild)!!.position)
        dao.close(nextDetail, "seek-owner")
    }

    @Test fun videoDetailKeepsTheUnpreparedPositionAcrossRecreationAndReturn() {
        fixture(BookType.video)
        val child = openDetail(VideoPlayerActivity::class.java)
        compose.waitUntil(15_000) { VideoPlay.book?.bookUrl == book.bookUrl && VideoPlay.chapter != null }
        assertEquals(6400, VideoPlay.durChapterPos)
        val previous = opened!!
        val previousBook = VideoPlay.book
        instrumentation.runOnMainSync { previous.recreate() }
        compose.waitUntil(20_000) {
            opened = resumed(VideoPlayerActivity::class.java)
            opened != null && opened !== previous && VideoPlay.book?.bookUrl == book.bookUrl && VideoPlay.book !== previousBook
        }
        finishAndCheckReturn(child)
        assertEquals(6400, appDb.bookDao.getBook(book.bookUrl)!!.durChapterPos)
        assertEquals(0, appDb.bookDao.getBook(book.bookUrl)!!.durChapterIndex)
    }

    @Test fun actualVideoDecodesAndSavesItsPositionThroughTheDetailReturn() {
        video = syntheticVideoFixture(java.io.File(context.cacheDir, "media-video-$id.mp4"))
        VideoPlay.autoPlay = true
        fixture(BookType.video)
        val child = openDetail(VideoPlayerActivity::class.java)
        var width = 0
        compose.waitUntil(25_000) {
            var playing = false
            instrumentation.runOnMainSync {
                val player = VideoPlay.videoManager.listener() as? com.shuyu.gsyvideoplayer.video.StandardGSYVideoPlayer
                if (player?.currentState == com.shuyu.gsyvideoplayer.video.base.GSYVideoView.CURRENT_STATE_PLAYING) {
                    width = VideoPlay.videoManager.currentVideoWidth
                    playing = VideoPlay.videoManager.isPlaying && width > 0 && VideoPlay.videoManager.currentPosition > 6500
                }
            }
            playing
        }
        assertTrue(requests.get() > 0)
        assertEquals(160, width)
        finishAndCheckReturn(child)
        assertTrue(appDb.bookDao.getBook(book.bookUrl)!!.durChapterPos >= 6400)
    }

    @Test fun defaultFloatingVideoReturnsToTheActualDetailOnlyAfterFinalStop() {
        allowOwnedOverlay()
        video = syntheticVideoFixture(java.io.File(context.cacheDir, "detail-float-$id.mp4"), frameCount = 300)
        VideoPlay.autoPlay = true; VideoPlay.defaultFloatWindow = true
        fixture(BookType.video)
        scenario = ActivityScenario.launch(AppActivity.startIntent(context, route))
        var reading = ""
        scenario!!.onActivity { reading = it.getString(R.string.continue_read) }
        compose.waitUntil(15_000) { compose.onAllNodesWithText(reading).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(reading).performClick()
        awaitFloating()
        val child = appDb.bookInfoRequestDao.activeChild(route.requestId)!!
        assertEquals(BookInfoChildPhase.Displayed, child.phase)
        assertNull(child.position)
        instrumentation.runOnMainSync { (VideoPlay.videoManager.listener() as FloatingPlayer).fullscreenB.performClick() }
        compose.waitUntil(20_000) { opened = resumed(VideoPlayerActivity::class.java); opened != null &&
            mainVideoPlaying(VideoPlayer::class.java) }
        assertEquals(BookInfoChildPhase.Displayed, appDb.bookInfoRequestDao.child(child.id)!!.phase)
        finishAndCheckReturn(child)
        assertTrue(appDb.bookDao.getBook(book.bookUrl)!!.durChapterPos >= 5900)
    }

    @Test fun explicitFloatingRoundTripKeepsTheActualDetailRequestUntilPlaybackEnds() {
        allowOwnedOverlay()
        video = syntheticVideoFixture(java.io.File(context.cacheDir, "detail-roundtrip-$id.mp4"), frameCount = 300)
        VideoPlay.autoPlay = true
        fixture(BookType.video)
        val child = openDetail(VideoPlayerActivity::class.java)
        compose.waitUntil(20_000) { mainVideoPlaying(VideoPlayer::class.java) }
        var floating = ""
        instrumentation.runOnMainSync { floating = opened!!.getString(R.string.float_window) }
        compose.onNodeWithContentDescription(floating).performClick()
        awaitFloating()
        globalExecutor.submit {}.get(10, TimeUnit.SECONDS)
        assertEquals(BookInfoChildPhase.Displayed, appDb.bookInfoRequestDao.child(child.id)!!.phase)
        instrumentation.runOnMainSync { (VideoPlay.videoManager.listener() as FloatingPlayer).fullscreenB.performClick() }
        compose.waitUntil(20_000) { opened = resumed(VideoPlayerActivity::class.java); opened != null &&
            mainVideoPlaying(VideoPlayer::class.java) }
        compose.onNodeWithContentDescription(floating).performClick()
        awaitFloating()
        assertEquals(BookInfoChildPhase.Displayed, appDb.bookInfoRequestDao.child(child.id)!!.phase)
        instrumentation.runOnMainSync { context.startService(Intent(context, VideoPlayService::class.java)
            .setAction(io.legado.app.constant.IntentAction.stop)) }
        compose.waitUntil(20_000) {
            var atDetail = false
            scenario!!.onActivity { atDetail = it.currentDestination == route }
            atDetail && appDb.bookInfoRequestDao.child(child.id)?.phase == BookInfoChildPhase.Consumed
        }
        assertEquals(book.bookUrl, appDb.bookInfoRequestDao.child(child.id)!!.returnBookUrl)
        assertTrue(appDb.bookDao.getBook(book.bookUrl)!!.durChapterPos >= 5900)
    }

    private fun mainVideoPlaying(type: Class<*>): Boolean {
        var playing = false
        instrumentation.runOnMainSync {
            playing = type.isInstance(VideoPlay.videoManager.listener()) && VideoPlay.videoManager.isPlaying &&
                VideoPlay.videoManager.currentVideoWidth == 160
        }
        return playing
    }

    private fun awaitFloating() {
        compose.waitUntil(25_000) {
            var ready = false
            instrumentation.runOnMainSync {
                ready = VideoPlay.videoManager.listener() is FloatingPlayer && VideoPlay.videoManager.isPlaying &&
                    VideoPlay.videoManager.currentVideoWidth == 160 && VideoPlay.videoManager.currentPosition >= 5900 &&
                    (VideoPlay.videoManager.listener() as FloatingPlayer).isAttachedToWindow
            }
            ready
        }
    }

    @Test fun actualVideoKeepsPlayingAcrossIgnoredOrientationAndLargeScreenResize() {
        io.legado.app.ui.compat.OrientationWindowSession("native-video").use { windows ->
            val clip = java.io.File(context.cacheDir, "media-video-$id.mp4")
            try {
                // A 60-second clip keeps natural end-of-playback outside the resize window.
                video = syntheticVideoFixture(clip, frameCount = 300, patterned = true)
                VideoPlay.autoPlay = true
                fixture(BookType.video)
                val child = openDetail(VideoPlayerActivity::class.java)
                fun playerState(): Pair<Boolean, Long> {
                    var state = false to 0L
                    instrumentation.runOnMainSync {
                        val player = VideoPlay.videoManager.listener() as? com.shuyu.gsyvideoplayer.video.StandardGSYVideoPlayer
                        state = (VideoPlay.videoManager.isPlaying && VideoPlay.videoManager.currentVideoWidth == 160 &&
                            player?.isIfCurrentIsFullscreen == true) to VideoPlay.videoManager.currentPosition
                    }
                    return state
                }
                compose.waitUntil(25_000) {
                    var playing = false
                    instrumentation.runOnMainSync { playing = VideoPlay.videoManager.isPlaying && VideoPlay.videoManager.currentVideoWidth == 160 }
                    playing
                }
                instrumentation.runOnMainSync {
                    val player = VideoPlay.videoManager.listener() as com.shuyu.gsyvideoplayer.video.StandardGSYVideoPlayer
                    if (!player.isIfCurrentIsFullscreen) player.fullscreenButton.performClick()
                    opened!!.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                }
                compose.waitUntil(10_000) { playerState().first }
                val anchor = playerState().second
                for ((width, height) in listOf(1280 to 800, 800 to 1280, 900 to 850, 1280 to 800)) {
                    windows.resize(width, height, { resumed(VideoPlayerActivity::class.java)!!.also { opened = it } }) {
                        compose.waitUntil(15_000) { playerState().first }
                        assertEquals(book.bookUrl, VideoPlay.book?.bookUrl)
                        assertTrue("Playback must not rewind during resize", playerState().second >= anchor)
                        assertEquals(BookInfoChildPhase.Displayed, appDb.bookInfoRequestDao.child(child.id)?.phase)
                        org.json.JSONObject().put("playingAndFullscreen", playerState().first)
                            .put("openedPositionMs", anchor).put("positionMs", playerState().second)
                            .put("ownedReturnRequestDisplayed", true)
                    }
                }
                Espresso.pressBack()
                compose.waitUntil(10_000) {
                    var fullscreen = true
                    instrumentation.runOnMainSync {
                        fullscreen = (VideoPlay.videoManager.listener() as? com.shuyu.gsyvideoplayer.video.StandardGSYVideoPlayer)?.isIfCurrentIsFullscreen == true
                    }
                    !fullscreen
                }
                compose.onNodeWithText(book.name).assertIsDisplayed()
                finishAndCheckReturn(child)
                assertTrue(appDb.bookDao.getBook(book.bookUrl)!!.durChapterPos >= anchor)
            } finally {
                clip.delete()
            }
        }
    }

    @Test fun actualVideoSameBookSeekRotationRecreationAndReturnPreservePositionOnce() {
        org.junit.Assume.assumeTrue(Build.VERSION.SDK_INT in 33..35)
        val originalAccelerometerRotation =
            overlayShell("settings get system accelerometer_rotation").trim()
        val originalUserRotation = overlayShell("settings get system user_rotation").trim()
        val clip = java.io.File(context.cacheDir, "media-rotation-$id.mp4")
        fun restoreSetting(name: String, value: String) {
            overlayShell(
                if (value == "null") "settings delete system $name"
                else "settings put system $name $value"
            )
        }
        try {
            overlayShell("settings put system accelerometer_rotation 0")
            overlayShell("settings put system user_rotation 0")
            video = syntheticVideoFixture(clip, frameCount = 300, patterned = true)
            VideoPlay.autoPlay = true
            fixture(BookType.video)
            val originalChild = openDetail(VideoPlayerActivity::class.java)
            fun playerState(): Pair<Boolean, Long> {
                var state = false to 0L
                instrumentation.runOnMainSync {
                    val listener = VideoPlay.videoManager.listener()
                    state = (
                        VideoPlayer::class.java.isInstance(listener) &&
                            VideoPlay.videoManager.isPlaying &&
                            VideoPlay.videoManager.currentVideoWidth == 160
                        ) to VideoPlay.videoManager.currentPosition
                }
                return state
            }
            compose.waitUntil(25_000) {
                playerState().let { it.first && it.second >= 6500 }
            }

            val nextDetail = "rotation-detail-$id"
            val nextChild = "rotation-player-$id"
            val dao = appDb.bookInfoRequestDao
            dao.claim(
                BookInfoRequest(nextDetail, book.bookUrl, book.bookUrl, "rotation-owner", updatedAt = 1),
                "rotation-owner"
            )
            checkNotNull(
                dao.begin(
                    nextDetail,
                    "rotation-owner",
                    0,
                    book.bookUrl,
                    nextChild,
                    BookInfoChildKind.Reader,
                    BookInfoNavigationPosition(0, 12_000),
                    2
                )
            )
            instrumentation.runOnMainSync {
                opened!!.startActivity(
                    Intent(context, VideoPlayerActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        .putExtra("bookUrl", book.bookUrl)
                        .putExtra(io.legado.app.data.repository.PlatformReaderReturnRepository.REQUEST_ID, nextChild)
                )
            }
            compose.waitUntil(25_000) {
                playerState().let { it.first && it.second >= 12_000 } &&
                    dao.child(nextChild)?.phase == BookInfoChildPhase.Displayed
            }
            assertEquals(BookInfoChildPhase.Returned, dao.child(originalChild.id)!!.phase)
            assertNull(dao.child(nextChild)!!.position)

            val recreatedPosition = playerState().second
            val beforeRecreation = opened!!
            instrumentation.runOnMainSync { beforeRecreation.recreate() }
            compose.waitUntil(25_000) {
                opened = resumed(VideoPlayerActivity::class.java)
                opened != null && opened !== beforeRecreation &&
                    playerState().let { it.first && it.second >= recreatedPosition - 500 }
            }
            assertEquals(BookInfoChildPhase.Displayed, dao.child(nextChild)!!.phase)

            val beforeRotation = playerState().second
            overlayShell("settings put system user_rotation 1")
            compose.waitUntil(25_000) {
                opened = resumed(VideoPlayerActivity::class.java)
                opened != null &&
                    opened!!.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
                    playerState().let { it.first && it.second >= beforeRotation - 500 }
            }
            val rotatedPosition = playerState().second
            assertEquals(book.bookUrl, VideoPlay.book?.bookUrl)
            assertEquals(BookInfoChildPhase.Displayed, dao.child(nextChild)!!.phase)

            instrumentation.runOnMainSync { opened!!.finish() }
            compose.waitUntil(25_000) { dao.child(nextChild)?.phase == BookInfoChildPhase.Returned }
            assertEquals(book.bookUrl, dao.child(nextChild)!!.returnBookUrl)
            val savedPosition = appDb.bookDao.getBook(book.bookUrl)!!.durChapterPos
            assertTrue("Rotated video position must remain non-zero", savedPosition > 0)
            assertTrue(
                "Rotated video position moved backwards: $rotatedPosition -> $savedPosition",
                savedPosition >= rotatedPosition - 500
            )
            dao.close(nextDetail, "rotation-owner")
        } finally {
            restoreSetting("accelerometer_rotation", originalAccelerometerRotation)
            restoreSetting("user_rotation", originalUserRotation)
            clip.delete()
        }
    }

    @Test fun malformedOwnedMediaRouteCannotOpenAnUnownedPlayer() {
        fixture(BookType.video)
        val monitor = instrumentation.addMonitor(VideoPlayerActivity::class.java.name, null, true)
        try {
            scenario = ActivityScenario.launch(AppActivity.startIntent(context,
                VideoPlayerRoute(book.bookUrl, returnToDetails = true)))
            compose.waitUntil(10_000) {
                var closed = scenario!!.state == androidx.lifecycle.Lifecycle.State.DESTROYED
                if (!closed) {
                    instrumentation.runOnMainSync {
                        closed = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                            .filterIsInstance<AppActivity>().any { it.currentDestination == BookshelfRoute }
                    }
                }
                closed
            }
            assertEquals(0, monitor.hits)
        } finally { instrumentation.removeMonitor(monitor) }
    }
}
