package io.legado.app.help.storage

import android.app.Activity
import android.content.Intent
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
import org.junit.runner.RunWith
import java.io.File
import java.lang.ref.WeakReference
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

/** Actual decoded video in a real application overlay, with app-op changes scoped to this fixture. */
@RunWith(AndroidJUnit4::class)
class RestoreFloatingVideoServiceUiTest {
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
    private var scenario: ActivityScenario<AppActivity>? = null
    private val clipUrl get() = Uri.fromFile(clip).toString()

    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }

    @Before fun setup() {
        assertFalse(RestoreRecoveryGate.isQuarantined)
        assertFalse(RestoreFileRecovery.hasPending(context))
        assertTrue(services().isEmpty())
        val result = shell("appops get ${context.packageName} SYSTEM_ALERT_WINDOW")
        overlayMode = Regex("SYSTEM_ALERT_WINDOW: (allow|deny|ignore|default|foreground)").find(result)?.groupValues?.get(1)
            ?: if (result.contains("No operations")) "default" else error("Unknown original overlay app-op: $result")
        shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")
        assertTrue(Settings.canDrawOverlays(context))
        clip.writeBytes(syntheticVideoFixture(clip, frameCount = 300))
        appDb.bookSourceDao.insert(source); inserted = true
        VideoPlay.autoPlay = true
        scenario = ActivityScenario.launch(Intent(context, AppActivity::class.java))
    }

    @After fun cleanup() {
        try {
            if (started) {
                instrumentation.runOnMainSync { context.stopService(Intent(context, VideoPlayService::class.java)) }
                await { services().isEmpty() }
            }
            if (started) instrumentation.runOnMainSync { VideoPlay.stopLoading(); VideoPlay.releaseAllVideos() }
            globalExecutor.submit {}.get(10, TimeUnit.SECONDS)
            if (ownedJournal) runBlocking { backupRestoreMutex.recover {
                RestoreFileRecovery.recover(context, cleanup = true)
                assertEquals(beforeJournal, context.defaultSharedPreferences.all)
                RestoreRecoveryGate.onStartupReady()
            } }
            instrumentation.runOnMainSync {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<RestoreRecoveryActivity>().forEach(Activity::finish)
            }
            scenario?.close()
            prefs.edit().apply {
                when (val old = originalVideoPreferences["autoPlay"]) {
                    null -> remove("autoPlay")
                    is Boolean -> putBoolean("autoPlay", old)
                    is String -> putString("autoPlay", old)
                    is Int -> putInt("autoPlay", old)
                    is Long -> putLong("autoPlay", old)
                    is Float -> putFloat("autoPlay", old)
                    is Set<*> -> putStringSet("autoPlay", old.filterIsInstance<String>().toSet())
                    else -> error("Unsupported original preference")
                }
            }.commit()
            assertEquals(originalVideoPreferences, prefs.all)
            fixtureBook?.let { book ->
                appDb.bookChapterDao.delByBook(book.bookUrl)
                appDb.bookDao.delete(book)
                BookHelp.clearCache(book)
            }
            if (inserted) appDb.bookSourceDao.delete(source)
            if (started) CacheManager.delete("video_pos_$clipUrl")
            clip.delete()
        } finally {
            overlayMode?.let { shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW $it") }
        }
    }

    private fun start(bookshelf: Boolean = false): Pair<VideoPlayService, View> {
        if (bookshelf) {
            val book = Book(bookUrl = "floating-book-$id", origin = source.bookSourceUrl, name = id,
                type = BookType.video, durChapterPos = 6400, totalChapterNum = 1)
            fixtureBook = book
            appDb.bookDao.insert(book)
            appDb.bookChapterDao.insert(BookChapter(bookUrl = book.bookUrl, url = clipUrl, title = id, index = 0))
        }
        scenario!!.onActivity { activity ->
            started = true
            activity.startForegroundService(Intent(activity, VideoPlayService::class.java)
                .putExtra("videoUrl", if (bookshelf) null as String? else clipUrl).putExtra("videoTitle", id)
                .putExtra("bookUrl", fixtureBook?.bookUrl)
                .putExtra("sourceKey", source.bookSourceUrl).putExtra("sourceType", SourceType.book))
        }
        lateinit var service: VideoPlayService
        lateinit var window: View
        await {
            val current = services().firstOrNull()
            if (current == null) false else {
                service = current
                val player = VideoPlay.videoManager.listener() as? FloatingPlayer
                if (player == null) false else {
                    window = player.parent as View
                    window.isAttachedToWindow && VideoPlay.videoManager.isPlaying &&
                        VideoPlay.videoManager.currentVideoWidth == 160 && VideoPlay.videoManager.currentPosition > 100
                }
            }
        }
        lateinit var activity: AppActivity
        scenario!!.onActivity { activity = it; assertTrue(it.moveTaskToBack(true)) }
        await { !activity.isDestroyed && !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
        return service to window
    }

    private fun quarantine() = runBlocking { backupRestoreMutex.recover {
        beforeJournal = HashMap(context.defaultSharedPreferences.all)
        val transaction = RestoreSettingsTransaction.prepare(context, UUID.randomUUID().toString(), false)
        ownedJournal = true
        transaction.editDefault().putString("floating-$id", "pending").apply()
        RestoreRecoveryGate.requireRecovery()
    } }

    private fun callback(): VideoAllCallBack = GSYVideoView::class.java.getDeclaredField("mVideoAllCallBack")
        .apply { isAccessible = true }.get(VideoPlay.videoManager.listener()) as VideoAllCallBack

    @Test fun readyFloatingPlaybackStillStopsAndRemovesTheOverlay() {
        val (service, window) = start()
        instrumentation.runOnMainSync { service.onStartCommand(Intent(context, VideoPlayService::class.java).setAction(IntentAction.stop), 0, 10) }
        await { service.lifecycle.currentState == Lifecycle.State.DESTROYED && !window.isAttachedToWindow }
        assertTrue(VideoPlayService.pause)
        assertFalse(VideoPlay.videoManager.isPlaying)
    }

    @Test fun recoveryStopsTheBackgroundFloatingPlayerAndRemovesItsWindow() {
        val (service, window) = start()
        val position = CacheManager.getLong("video_pos_$clipUrl")
        quarantine()
        await { service.lifecycle.currentState == Lifecycle.State.DESTROYED && !window.isAttachedToWindow }
        assertTrue(VideoPlayService.pause)
        assertFalse(VideoPlay.videoManager.isPlaying)
        assertEquals(position, CacheManager.getLong("video_pos_$clipUrl"))
    }

    @Test fun recoveryStopsTheBookshelfFloatingPlayerWithoutLateProgressWrites() {
        val (service, window) = start(bookshelf = true)
        globalExecutor.submit {}.get(10, TimeUnit.SECONDS)
        val before = appDb.bookDao.getBook(fixtureBook!!.bookUrl)!!
        assertTrue(before.durChapterPos >= 6400)
        quarantine()
        await { service.lifecycle.currentState == Lifecycle.State.DESTROYED && !window.isAttachedToWindow }
        globalExecutor.submit {}.get(10, TimeUnit.SECONDS)
        assertEquals(before, appDb.bookDao.getBook(before.bookUrl))
        assertFalse(VideoPlay.videoManager.isPlaying)
    }

    @Test fun resumeQueuedBeforeTheFaultCannotRestartTheFloatingPlayer() {
        val (service, window) = start()
        lateinit var callback: VideoAllCallBack
        val delivered = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        instrumentation.runOnMainSync {
            callback = callback()
            (VideoPlay.videoManager.listener() as FloatingPlayer).onVideoPause()
            callback.onClickStop(clipUrl)
            assertTrue(VideoPlayService.pause)
            assertTrue(Handler(Looper.getMainLooper()).post {
                try { callback.onClickResume(clipUrl) }
                catch (error: Throwable) { failure.set(error) }
                finally { delivered.countDown() }
            })
            quarantine()
        }
        assertTrue(delivered.await(10, TimeUnit.SECONDS))
        assertNull(failure.get())
        instrumentation.runOnMainSync { assertTrue(VideoPlayService.pause) }
        await { service.lifecycle.currentState == Lifecycle.State.DESTROYED && !window.isAttachedToWindow }
    }

    @Test fun aDestroyedServiceRejectsItsOldResumeCallbackEvenWhenRecoveryIsReady() {
        val (service, window) = start()
        lateinit var callback: VideoAllCallBack
        instrumentation.runOnMainSync {
            callback = callback()
            (VideoPlay.videoManager.listener() as FloatingPlayer).onVideoPause()
            callback.onClickStop(clipUrl)
            context.stopService(Intent(context, VideoPlayService::class.java))
        }
        await { service.lifecycle.currentState == Lifecycle.State.DESTROYED && !window.isAttachedToWindow }
        assertFalse(RestoreRecoveryGate.isQuarantined)
        instrumentation.runOnMainSync { callback.onClickResume(clipUrl); assertTrue(VideoPlayService.pause) }
    }

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
