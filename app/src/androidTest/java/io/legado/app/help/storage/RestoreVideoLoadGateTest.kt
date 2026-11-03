package io.legado.app.help.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fi.iki.elonen.NanoHTTPD
import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.rule.ContentRule
import io.legado.app.help.book.BookHelp
import io.legado.app.help.gsyVideo.FloatingPlayer
import io.legado.app.model.VideoPlay
import io.legado.app.utils.defaultSharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Delay a real chapter response across the recovery boundary, then drain its actual load job. */
@RunWith(AndroidJUnit4::class)
class RestoreVideoLoadGateTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val id = UUID.randomUUID().toString()
    private val entered = CountDownLatch(1)
    private val release = CountDownLatch(1)
    private val served = CountDownLatch(1)
    private val before = HashMap(context.defaultSharedPreferences.all)
    private val videoPrefs = context.getSharedPreferences(VideoPlay.VIDEO_PREF_NAME, 0)
    private val autoPlay = videoPrefs.all["autoPlay"]
    private val oldSource = VideoPlay.source
    private val oldBook = VideoPlay.book
    private val oldChapter = VideoPlay.chapter
    private val oldEpisodes = VideoPlay.episodes
    private val oldUrl = VideoPlay.videoUrl
    private val oldTitle = VideoPlay.videoTitle
    private val oldSingle = VideoPlay.singleUrl
    private val oldPos = VideoPlay.durChapterPos
    private val oldIndex = VideoPlay.chapterInVolumeIndex
    private val oldDanmakuFile = VideoPlay.danmakuFile
    private val oldDanmakuStr = VideoPlay.danmakuStr
    private val server = object : NanoHTTPD("127.0.0.1", 0) {
        override fun serve(session: IHTTPSession): Response {
            entered.countDown()
            check(release.await(20, TimeUnit.SECONDS))
            served.countDown()
            return newFixedLengthResponse(Response.Status.OK, "text/html", "<html><body>http://127.0.0.1:$listeningPort/$id.mp4</body></html>")
        }
    }
    private lateinit var book: Book
    private lateinit var player: FloatingPlayer
    private var owned = false
    private val loadScope get() = VideoPlay::class.java.getDeclaredField("loadScope").apply { isAccessible = true }.get(VideoPlay) as CoroutineScope

    @Before fun setup() {
        assertFalse(RestoreRecoveryGate.isQuarantined)
        assertFalse(RestoreFileRecovery.hasPending(context))
        assertFalse(loadScope.coroutineContext[kotlinx.coroutines.Job]!!.children.any())
        server.start()
        val source = BookSource("http://127.0.0.1:${server.listeningPort}/$id", id, ruleContent = ContentRule(content = "body@text"))
        book = Book(bookUrl = source.bookSourceUrl + "/book", origin = source.bookSourceUrl, name = id, type = BookType.video)
        val chapter = BookChapter(bookUrl = book.bookUrl, url = source.bookSourceUrl + "/chapter", title = id, index = 0)
        VideoPlay.source = source; VideoPlay.book = book; VideoPlay.episodes = listOf(chapter)
        VideoPlay.chapterInVolumeIndex = 0; VideoPlay.singleUrl = false; VideoPlay.videoUrl = null; VideoPlay.durChapterPos = 0
        VideoPlay.autoPlay = false
        instrumentation.runOnMainSync { player = FloatingPlayer(context); VideoPlay.startPlay(player) }
        assertTrue("Actual chapter request was not received", entered.await(15, TimeUnit.SECONDS))
    }

    @After fun cleanup() {
        release.countDown()
        instrumentation.runOnMainSync { VideoPlay.stopLoading() }
        awaitLoads()
        server.stop()
        if (owned) runBlocking { backupRestoreMutex.recover {
            RestoreFileRecovery.recover(context, cleanup = true)
            assertEquals(before, context.defaultSharedPreferences.all)
            RestoreRecoveryGate.onStartupReady()
        } }
        if (::player.isInitialized) instrumentation.runOnMainSync { player.release() }
        if (::book.isInitialized) BookHelp.clearCache(book)
        videoPrefs.edit().apply { if (autoPlay == null) remove("autoPlay") else putBoolean("autoPlay", autoPlay as Boolean) }.commit()
        VideoPlay.source = oldSource; VideoPlay.book = oldBook; VideoPlay.chapter = oldChapter; VideoPlay.episodes = oldEpisodes
        VideoPlay.videoUrl = oldUrl; VideoPlay.videoTitle = oldTitle; VideoPlay.singleUrl = oldSingle
        VideoPlay.durChapterPos = oldPos; VideoPlay.chapterInVolumeIndex = oldIndex
        VideoPlay.danmakuFile = oldDanmakuFile; VideoPlay.danmakuStr = oldDanmakuStr
    }

    @Test fun readyChapterResponseStillConfiguresThePlayer() {
        finishResponse()
        assertEquals("http://127.0.0.1:${server.listeningPort}/$id.mp4", VideoPlay.videoUrl)
    }

    @Test fun chapterResponseDeliveredAfterTheFaultCannotReplaceTheVideoUrl() {
        runBlocking { backupRestoreMutex.recover {
            RestoreSettingsTransaction.prepare(context, UUID.randomUUID().toString(), false)
            owned = true
            RestoreRecoveryGate.requireRecovery()
        } }
        finishResponse()
        assertNull(VideoPlay.videoUrl)
    }

    private fun finishResponse() {
        release.countDown()
        assertTrue(served.await(15, TimeUnit.SECONDS))
        awaitLoads()
    }

    private fun awaitLoads() {
        val deadline = android.os.SystemClock.uptimeMillis() + 15_000
        while (loadScope.coroutineContext[kotlinx.coroutines.Job]!!.children.any()) {
            check(android.os.SystemClock.uptimeMillis() < deadline) { "Video load did not finish" }
            android.os.SystemClock.sleep(30)
        }
        instrumentation.waitForIdleSync()
    }
}
