package io.legado.app.help.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.globalExecutor
import io.legado.app.model.AudioPlay
import io.legado.app.service.AudioPlayService
import io.legado.app.utils.defaultSharedPreferences
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class RestoreAudioWriteGateTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val id = UUID.randomUUID().toString()
    private val book = Book(bookUrl = "audio-gate-$id", name = id, author = id, durChapterPos = 6400)
    private val chapter = BookChapter(bookUrl = book.bookUrl, url = "chapter-$id", title = id, index = 0, end = 40_000)
    private val originalPreferences = HashMap(context.defaultSharedPreferences.all)
    private val oldBook = AudioPlay.book
    private val oldChapter = AudioPlay.durChapter
    private val oldPosition = AudioPlay.durChapterPos
    private val oldSize = AudioPlay.durAudioSize
    private val oldMode = AudioPlay.playMode
    private val oldSpeed = AudioPlayService.playSpeed
    private var owned = false

    @Before fun setup() {
        assertFalse(AudioPlayService.isRun)
        assertFalse(RestoreRecoveryGate.isQuarantined)
        assertFalse(RestoreFileRecovery.hasPending(context))
        appDb.bookDao.insert(book); appDb.bookChapterDao.insert(chapter)
        AudioPlay.book = book; AudioPlay.durChapter = chapter
        AudioPlay.durChapterPos = 6400; AudioPlay.durAudioSize = 40_000
        AudioPlay.playMode = AudioPlay.PlayMode.LIST_END_STOP
        AudioPlayService.playSpeed = 1f
    }

    private fun quarantine() = runBlocking {
        backupRestoreMutex.recover {
            val transaction = RestoreSettingsTransaction.prepare(context, UUID.randomUUID().toString(), false)
            owned = true
            transaction.editDefault().putString("audio-gate-$id", "pending").apply()
            RestoreRecoveryGate.requireRecovery()
        }
    }

    @After fun cleanup() {
        globalExecutor.submit {}.get(10, TimeUnit.SECONDS)
        if (owned) runBlocking { backupRestoreMutex.recover {
            RestoreFileRecovery.recover(context, cleanup = true)
            assertEquals(originalPreferences, context.defaultSharedPreferences.all)
            RestoreRecoveryGate.onStartupReady()
        } }
        AudioPlay.book = oldBook; AudioPlay.durChapter = oldChapter
        AudioPlay.durChapterPos = oldPosition; AudioPlay.durAudioSize = oldSize
        AudioPlay.playMode = oldMode; AudioPlayService.playSpeed = oldSpeed
        appDb.bookChapterDao.delByBook(book.bookUrl); appDb.bookDao.delete(book)
    }

    @Test fun lateAudioMetadataDoNotMutateMemoryOrRows() {
        quarantine()
        AudioPlay.setSpeed(2.75f); AudioPlay.changePlayMode()
        AudioPlay.saveDurChapter(123_456); AudioPlay.playPositionChanged(27_000)
        globalExecutor.submit {}.get(10, TimeUnit.SECONDS)
        assertEquals(1f, AudioPlayService.playSpeed, 0.001f)
        assertEquals(AudioPlay.PlayMode.LIST_END_STOP, AudioPlay.playMode)
        assertEquals(6400, AudioPlay.durChapterPos); assertEquals(40_000, AudioPlay.durAudioSize)
        assertOriginalRows()
        assertFalse(AudioPlayService.isRun)
    }

    @Test fun audioMetadataQueuedBeforeTheFaultCannotOverwriteRecoveredRows() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val blocker = globalExecutor.submit { entered.countDown(); check(release.await(15, TimeUnit.SECONDS)) }
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            AudioPlay.setSpeed(2.75f); AudioPlay.changePlayMode(); AudioPlay.saveDurChapter(123_456)
            quarantine()
        } finally { release.countDown(); blocker.get(10, TimeUnit.SECONDS) }
        globalExecutor.submit {}.get(10, TimeUnit.SECONDS)
        assertOriginalRows()
    }

    @Test fun aPreviouslyCurrentPlayerGenerationCannotExecuteAfterTheFault() {
        val generation = AudioPlay.currentPlaybackGeneration()
        var executed = false
        quarantine()
        assertFalse(AudioPlay.runIfPlaybackCurrent(generation) { executed = true })
        assertFalse(executed)
    }

    private fun assertOriginalRows() {
        val after = appDb.bookDao.getBook(book.bookUrl)!!
        assertEquals(6400, after.durChapterPos)
        assertEquals(1f, after.getPlaySpeed(), 0.001f)
        assertEquals(0, after.getPlayMode())
        assertEquals(40_000L, appDb.bookChapterDao.getChapter(book.bookUrl, 0)!!.end)
    }
}
