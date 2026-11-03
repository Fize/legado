package io.legado.app.help.storage

import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import android.speech.tts.UtteranceProgressListener
import androidx.lifecycle.Lifecycle
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.cache.NoOpCacheEvictor
import io.legado.app.model.localBook.TextFile
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import fi.iki.elonen.NanoHTTPD
import io.legado.app.constant.AppConst
import io.legado.app.constant.BookType
import io.legado.app.constant.IntentAction
import io.legado.app.constant.PageAnim
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.*
import io.legado.app.help.LifecycleHelp
import io.legado.app.help.globalExecutor
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.service.*
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.recovery.RestoreRecoveryActivity
import io.legado.app.utils.defaultSharedPreferences
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.lang.ref.WeakReference
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Real services and a local HTTP audio response; TTS callback delivery does not require a voice. */
@RunWith(AndroidJUnit4::class)
class RestoreReadAloudServiceUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val id = UUID.randomUUID().toString()
    private val root = File(context.cacheDir, "speech-recovery-$id")
    private val text = File(root, "book.txt")
    private val book = Book(bookUrl = text.absolutePath, originName = text.name, name = id, author = id,
        charset = "UTF-8", type = BookType.local or BookType.text, totalChapterNum = 1).apply { setPageAnim(PageAnim.noAnim) }
    private val prefs = context.defaultSharedPreferences
    private val oldStream = prefs.all[PreferKey.streamReadAloudAudio]
    private val oldHttp = ReadAloud.httpTTS
    private val classField = ReadAloud::class.java.getDeclaredField("aloudClass").apply { isAccessible = true }
    private val oldClass = classField.get(ReadAloud)
    private var scenario: ActivityScenario<ReadBookActivity>? = null
    private var ownedJournal = false
    private var setupComplete = false
    private var engine: HttpTTS? = null
    private var server: NanoHTTPD? = null
    private val ownedCacheKeys = mutableSetOf<String>()
    private var streamCacheUsed = false
    private val requests = AtomicInteger()

    @Before fun setup() {
        assertFalse(RestoreRecoveryGate.isQuarantined)
        assertFalse(RestoreFileRecovery.hasPending(context))
        assertFalse(BaseReadAloudService.isRun)
        check(root.mkdirs())
        text.writeText((0..60).joinToString("\n") { "Owned speech recovery paragraph $it. " + "Readable text. ".repeat(20) })
        appDb.bookDao.insert(book)
        appDb.bookChapterDao.insert(BookChapter(bookUrl = book.bookUrl, url = "speech-$id", title = id, index = 0, start = 0, end = text.length()))
        setupComplete = true
        scenario = ActivityScenario.launch(Intent(context, ReadBookActivity::class.java).putExtra("bookUrl", book.bookUrl))
        await { ReadBook.book?.bookUrl == book.bookUrl && ReadBook.curTextChapter?.isCompleted == true }
    }

    @After fun cleanup() {
        if (setupComplete) {
            instrumentation.runOnMainSync {
                context.stopService(Intent(context, TTSReadAloudService::class.java))
                context.stopService(Intent(context, HttpReadAloudService::class.java))
            }
            await { services().none { it is BaseReadAloudService } && !BaseReadAloudService.isRun }
            globalExecutor.submit {}.get(10, TimeUnit.SECONDS)
        }
        if (ownedJournal) runBlocking { backupRestoreMutex.recover {
            RestoreFileRecovery.recover(context, cleanup = true)
            RestoreRecoveryGate.onStartupReady()
        } }
        instrumentation.runOnMainSync {
            ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<RestoreRecoveryActivity>().forEach(Activity::finish)
        }
        scenario?.close()
        server?.stop()
        if (ownedCacheKeys.isNotEmpty()) {
            File(context.cacheDir, "httpTTS").listFiles()?.filter { it.name.removeSuffix(".mp3") in ownedCacheKeys }?.forEach {
                check(it.delete())
            }
            val cacheFolder = File(context.cacheDir, "httpTTS_cache")
            if (streamCacheUsed && cacheFolder.isDirectory) {
                val cache = SimpleCache(cacheFolder, NoOpCacheEvictor(), StandaloneDatabaseProvider(context))
                try {
                    val others = cache.keys - ownedCacheKeys
                    cache.keys.filter { it in ownedCacheKeys }.forEach(cache::removeResource)
                    assertEquals(others, cache.keys)
                } finally { cache.release() }
            }
        }
        engine?.let { appDb.httpTTSDao.delete(it) }
        ReadAloud.httpTTS = oldHttp; classField.set(ReadAloud, oldClass)
        prefs.edit().apply {
            if (oldStream == null) remove(PreferKey.streamReadAloudAudio)
            else putBoolean(PreferKey.streamReadAloudAudio, oldStream as Boolean)
        }.commit()
        if (setupComplete) { appDb.bookChapterDao.delByBook(book.bookUrl); appDb.bookDao.delete(book) }
        root.deleteRecursively()
        if (setupComplete) TextFile.clear()
    }

    @Test fun ttsRecoveryStopsTheRunningService() {
        val service = startTts()
        backgroundReader()
        val before = appDb.bookDao.getBook(book.bookUrl)!!
        quarantine()
        await { service.lifecycle.currentState == Lifecycle.State.DESTROYED && !BaseReadAloudService.isRun }
        assertPersistedPosition(before)
    }

    @Test fun ttsCallbackQueuedBeforeTheFaultCannotAdvanceSpeechProgress() {
        val service = startTts()
        backgroundReader()
        val session = TTSReadAloudService::class.java.getDeclaredField("playbackSessionId").apply { isAccessible = true }
            .get(service) as AtomicLong
        val listener = TTSReadAloudService::class.java.getDeclaredField("ttsUtteranceListener").apply { isAccessible = true }
            .get(service) as UtteranceProgressListener
        instrumentation.runOnMainSync {
            listener.onRangeStart("${AppConst.APP_TAG}:${session.get()}:0:0:false", 777, 778, 0)
            quarantine()
        }
        instrumentation.waitForIdleSync()
        assertNotEquals(777, BaseReadAloudService.readAloudChapterStart)
        await { service.lifecycle.currentState == Lifecycle.State.DESTROYED && !BaseReadAloudService.isRun }
    }

    @Test fun downloadedHttpSpeechStopsAndRejectsLatePlayerCallbacks() = verifyHttp(false)
    @Test fun streamingHttpSpeechStopsAndRejectsLatePlayerCallbacks() = verifyHttp(true)

    private fun verifyHttp(stream: Boolean) {
        val samples = ByteArray(8000 * 2 * 40)
        val wav = ByteBuffer.allocate(44 + samples.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + samples.size); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(8000); putInt(16000); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(samples.size); put(samples)
        }.array()
        val host = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response {
                requests.incrementAndGet()
                return newFixedLengthResponse(Response.Status.OK, "audio/wav", wav.inputStream(), wav.size.toLong())
            }
        }.also { server = it; it.start() }
        val tts = HttpTTS(id = -System.nanoTime(), name = "Speech $id", url = "http://127.0.0.1:${host.listeningPort}/$id.wav")
        engine = tts; appDb.httpTTSDao.insert(tts)
        check(tts.url.contains(id))
        streamCacheUsed = stream
        prefs.edit().putBoolean(PreferKey.streamReadAloudAudio, stream).commit()
        scenario!!.onActivity { activity ->
            ReadBook.book!!.setTtsEngine(tts.id.toString()); ReadAloud.upReadAloudClass()
            activity.startService(Intent(activity, HttpReadAloudService::class.java).setAction(IntentAction.play))
        }
        lateinit var service: HttpReadAloudService
        lateinit var player: ExoPlayer
        await {
            val current = services().filterIsInstance<HttpReadAloudService>().firstOrNull()
            if (current != null) {
                service = current
                current.textChapter?.let { chapter ->
                    val rate = HttpReadAloudService::class.java.getDeclaredField("speechRate").apply { isAccessible = true }.getInt(current)
                    fun key(content: String) = buildHttpTtsCacheFileName(chapter.title, tts.url, rate,
                        tts.getVariable().orEmpty(), tts.getLoginHeader().orEmpty(), content)
                    current.contentList.forEach { ownedCacheKeys += key(it) }
                    current.contentList.getOrNull(current.nowSpeak)?.let { paragraph ->
                        ownedCacheKeys += key(paragraph.substring(current.paragraphStartPos.coerceIn(0, paragraph.length)))
                    }
                }
                player = HttpReadAloudService::class.java.getDeclaredMethod("getExoPlayer").apply { isAccessible = true }.invoke(current) as ExoPlayer
                requests.get() > 0 && player.isPlaying
            } else false
        }
        backgroundReader()
        val before = appDb.bookDao.getBook(book.bookUrl)!!
        quarantine()
        await { service.lifecycle.currentState == Lifecycle.State.DESTROYED && !BaseReadAloudService.isRun }
        instrumentation.runOnMainSync {
            service.onTimelineChanged(androidx.media3.common.Timeline.EMPTY, androidx.media3.common.Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED)
            service.onPlaybackStateChanged(androidx.media3.common.Player.STATE_READY)
            service.upTtsProgress(9999)
            service.resumeReadAloud()
        }
        assertNotEquals(9999, BaseReadAloudService.readAloudChapterStart)
        assertPersistedPosition(before)
    }

    @Test fun destroyedTtsProgressCallbackCannotRestartAForegroundService() {
        val service = startTts()
        instrumentation.runOnMainSync { context.stopService(Intent(context, TTSReadAloudService::class.java)) }
        await { service.lifecycle.currentState == Lifecycle.State.DESTROYED }
        quarantine()
        instrumentation.runOnMainSync { service.upTtsProgress(9999); service.resumeReadAloud() }
        assertNotEquals(9999, BaseReadAloudService.readAloudChapterStart)
        assertFalse(BaseReadAloudService.isRun)
    }

    private fun backgroundReader() {
        lateinit var activity: ReadBookActivity
        scenario!!.onActivity { activity = it; assertTrue(it.moveTaskToBack(true)) }
        await { !activity.isDestroyed && !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
        globalExecutor.submit {}.get(10, TimeUnit.SECONDS)
    }

    private fun startTts(): TTSReadAloudService {
        scenario!!.onActivity { activity ->
            ReadBook.book!!.setTtsEngine(""); ReadAloud.upReadAloudClass()
            activity.startService(Intent(activity, TTSReadAloudService::class.java).setAction(IntentAction.pause))
        }
        var result: TTSReadAloudService? = null
        await { result = services().filterIsInstance<TTSReadAloudService>().firstOrNull(); result != null && BaseReadAloudService.isRun }
        return requireNotNull(result)
    }

    private fun quarantine() = runBlocking { backupRestoreMutex.recover {
        val transaction = RestoreSettingsTransaction.prepare(context, UUID.randomUUID().toString(), false)
        ownedJournal = true
        transaction.editDefault().putString("speech-$id", "pending").apply()
        RestoreRecoveryGate.requireRecovery()
    } }

    private fun assertPersistedPosition(before: Book) {
        globalExecutor.submit {}.get(10, TimeUnit.SECONDS)
        val after = appDb.bookDao.getBook(book.bookUrl)!!
        assertEquals(before.durChapterIndex, after.durChapterIndex)
        assertEquals(before.durChapterPos, after.durChapterPos)
        assertEquals(before.durChapterTime, after.durChapterTime)
    }

    @Suppress("UNCHECKED_CAST")
    private fun services(): List<Any> = (LifecycleHelp::class.java.getDeclaredField("services").apply { isAccessible = true }
        .get(LifecycleHelp) as List<WeakReference<*>>).mapNotNull { it.get() }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 30_000
        do {
            var ready = false
            instrumentation.runOnMainSync { ready = condition() }
            if (ready) return
            SystemClock.sleep(50)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError("Speech recovery condition not met; running=${BaseReadAloudService.isRun}")
    }
}
