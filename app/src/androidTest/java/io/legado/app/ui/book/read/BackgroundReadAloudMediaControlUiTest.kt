package io.legado.app.ui.book.read

import android.content.Intent
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fi.iki.elonen.NanoHTTPD
import io.legado.app.constant.BookType
import io.legado.app.constant.PageAnim
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.HttpTTS
import io.legado.app.help.LifecycleHelp
import io.legado.app.help.globalExecutor
import io.legado.app.help.storage.RestoreFileRecovery
import io.legado.app.help.storage.RestoreRecoveryGate
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.model.localBook.TextFile
import io.legado.app.service.BaseReadAloudService
import io.legado.app.service.HttpReadAloudService
import io.legado.app.service.TTSReadAloudService
import io.legado.app.utils.defaultSharedPreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.lang.ref.WeakReference
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Exercises the reader's real foreground speech service with system media controls. */
@RunWith(AndroidJUnit4::class)
class BackgroundReadAloudMediaControlUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val id = UUID.randomUUID().toString()
    private val root = File(context.cacheDir, "background-speech-$id")
    private val text = File(root, "book.txt")
    private val book = Book(
        bookUrl = text.absolutePath,
        originName = text.name,
        name = id,
        author = id,
        charset = "UTF-8",
        type = BookType.local or BookType.text,
        totalChapterNum = 1,
    ).apply { setPageAnim(PageAnim.noAnim) }
    private val prefs = context.defaultSharedPreferences
    private val oldStream = prefs.all[PreferKey.streamReadAloudAudio]
    private val oldHttp = ReadAloud.httpTTS
    private val classField =
        ReadAloud::class.java.getDeclaredField("aloudClass").apply { isAccessible = true }
    private val oldClass = classField.get(ReadAloud)
    private val existingSpeechFiles =
        File(context.cacheDir, "httpTTS").listFiles()?.mapTo(mutableSetOf()) { it.name }.orEmpty()
    private val requests = AtomicInteger()
    private var setupComplete = false
    private var scenario: ActivityScenario<ReadBookActivity>? = null
    private var engine: HttpTTS? = null
    private var server: NanoHTTPD? = null

    @Before
    fun setup() {
        assertFalse(RestoreRecoveryGate.isQuarantined)
        assertFalse(RestoreFileRecovery.hasPending(context))
        assertFalse(BaseReadAloudService.isRun)
        check(root.mkdirs())
        text.writeText(
            (0..18).joinToString("\n") {
                "Background speech paragraph $it. " + "Readable text. ".repeat(12)
            },
        )
        appDb.bookDao.insert(book)
        appDb.bookChapterDao.insert(
            BookChapter(
                bookUrl = book.bookUrl,
                url = "background-speech-$id",
                title = id,
                index = 0,
                start = 0,
                end = text.length(),
            ),
        )

        val samples = ByteArray(8_000 * 2 * 40)
        val wav = ByteBuffer.allocate(44 + samples.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt(36 + samples.size)
            put("WAVEfmt ".toByteArray())
            putInt(16)
            putShort(1)
            putShort(1)
            putInt(8_000)
            putInt(16_000)
            putShort(2)
            putShort(16)
            put("data".toByteArray())
            putInt(samples.size)
            put(samples)
        }.array()
        server = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response {
                requests.incrementAndGet()
                return newFixedLengthResponse(
                    Response.Status.OK,
                    "audio/wav",
                    wav.inputStream(),
                    wav.size.toLong(),
                )
            }
        }.also { it.start() }
        engine = HttpTTS(
            id = -System.nanoTime(),
            name = "Background speech $id",
            url = "http://127.0.0.1:${server!!.listeningPort}/$id.wav",
        ).also(appDb.httpTTSDao::insert)
        prefs.edit().putBoolean(PreferKey.streamReadAloudAudio, false).commit()
        setupComplete = true

        scenario = ActivityScenario.launch(
            Intent(context, ReadBookActivity::class.java).putExtra("bookUrl", book.bookUrl),
        )
        await {
            ReadBook.book?.bookUrl == book.bookUrl &&
                ReadBook.curTextChapter?.isCompleted == true
        }
    }

    @Test
    fun productionReadAloudContinuesInBackgroundAndObeysSystemMediaAndHeadsetEvents() {
        scenario!!.onActivity { activity ->
            ReadBook.book!!.setTtsEngine(checkNotNull(engine).id.toString())
            ReadAloud.upReadAloudClass()
            ReadBook.readAloud(rewindToSentenceStart = false)
            assertTrue("Reader task must remain available while speech starts", !activity.isFinishing)
        }
        await {
            speechService() != null &&
                requests.get() > 0 &&
                playerState().second
        }
        val beforeBackground = playerState().first

        scenario!!.onActivity { activity ->
            assertTrue("Could not move the production reader to the background", activity.moveTaskToBack(true))
        }
        await { !readerStarted() }
        await { playerState().first > beforeBackground + 250 }
        assertTrue(BaseReadAloudService.isRun)
        assertTrue(playerState().second)

        val sessions = runShell("cmd media_session list-sessions")
        assertTrue("Active speech session missing from Android media controls:\n$sessions",
            sessions.contains(context.packageName))

        dispatchMedia("pause")
        await { BaseReadAloudService.pause && !playerState().second }
        val pausedPosition = playerState().first
        SystemClock.sleep(1_200)
        assertTrue(
            "Paused speech continued to advance",
            playerState().first <= pausedPosition + 250,
        )

        dispatchMedia("play")
        await { !BaseReadAloudService.pause && playerState().second }
        val resumedPosition = playerState().first
        await {
            val now = playerState()
            now.second && now.first > resumedPosition + 250
        }

        dispatchMedia("headsethook")
        await { BaseReadAloudService.pause && !playerState().second }
        assertTrue("The headset media key must not stop the speech service", BaseReadAloudService.isRun)

        dispatchMedia("play")
        await { !BaseReadAloudService.pause && playerState().second }
        assertTrue(BaseReadAloudService.isRun)
    }

    private fun dispatchMedia(key: String) {
        runShell("cmd media_session dispatch $key")
    }

    private fun runShell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command),
        ).bufferedReader().use { it.readText() }

    private fun speechProgress(): Int = ReadAloud.readAloudChapterStart

    private fun readerStarted(): Boolean {
        var started = false
        scenario!!.onActivity {
            started = it.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        }
        return started
    }

    private fun playerState(): Pair<Long, Boolean> {
        var state = 0L to false
        instrumentation.runOnMainSync {
            val service = speechServiceOnMain()
            if (service != null) {
                val player = HttpReadAloudService::class.java
                    .getDeclaredMethod("getExoPlayer")
                    .apply { isAccessible = true }
                    .invoke(service) as ExoPlayer
                state = player.currentPosition to player.isPlaying
            }
        }
        return state
    }

    @Suppress("UNCHECKED_CAST")
    private fun speechService(): HttpReadAloudService? {
        var service: HttpReadAloudService? = null
        instrumentation.runOnMainSync {
            service = speechServiceOnMain()
        }
        return service
    }

    @Suppress("UNCHECKED_CAST")
    private fun speechServiceOnMain(): HttpReadAloudService? {
        val services = LifecycleHelp::class.java
            .getDeclaredField("services")
            .apply { isAccessible = true }
            .get(LifecycleHelp) as List<WeakReference<*>>
        return services.mapNotNull { it.get() }
            .filterIsInstance<HttpReadAloudService>()
            .singleOrNull()
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 30_000
        do {
            if (condition()) return
            SystemClock.sleep(50)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError(
            "Speech condition not met; running=${BaseReadAloudService.isRun}, " +
                "paused=${BaseReadAloudService.pause}, requests=${requests.get()}, " +
                "progress=${speechProgress()}, player=${playerState()}",
        )
    }

    @After
    fun cleanup() {
        if (setupComplete) {
            instrumentation.runOnMainSync {
                context.stopService(Intent(context, TTSReadAloudService::class.java))
                context.stopService(Intent(context, HttpReadAloudService::class.java))
            }
            val deadline = SystemClock.uptimeMillis() + 15_000
            while (
                (speechService() != null || BaseReadAloudService.isRun) &&
                SystemClock.uptimeMillis() < deadline
            ) {
                SystemClock.sleep(50)
            }
            globalExecutor.submit {}.get(10, TimeUnit.SECONDS)
        }
        scenario?.close()
        server?.stop()
        File(context.cacheDir, "httpTTS").listFiles()
            ?.filterNot { it.name in existingSpeechFiles }
            ?.forEach(File::delete)
        engine?.let(appDb.httpTTSDao::delete)
        ReadAloud.httpTTS = oldHttp
        classField.set(ReadAloud, oldClass)
        prefs.edit().apply {
            if (oldStream == null) remove(PreferKey.streamReadAloudAudio)
            else putBoolean(PreferKey.streamReadAloudAudio, oldStream as Boolean)
        }.commit()
        if (setupComplete) {
            appDb.bookChapterDao.delByBook(book.bookUrl)
            appDb.bookDao.delete(book)
            TextFile.clear()
        }
        root.deleteRecursively()
        assertEquals(false, BaseReadAloudService.isRun)
    }
}
