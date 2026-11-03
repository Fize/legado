package io.legado.app.help.storage

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.model.VideoPlay
import io.legado.app.utils.defaultSharedPreferences
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RestoreVideoWriteGateTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs = context.getSharedPreferences(VideoPlay.VIDEO_PREF_NAME, Context.MODE_PRIVATE)
    private val keys = setOf("autoPlay", "startFull", "defaultFloatWindow", "longPressSpeed", "fullBottomProgressBar")
    private val before = HashMap(prefs.all)
    private val defaultBefore = HashMap(context.defaultSharedPreferences.all)
    private val oldBook = VideoPlay.book
    private val oldSource = VideoPlay.source
    private val oldToc = VideoPlay.toc
    private val oldEpisodes = VideoPlay.episodes
    private val oldVolume = VideoPlay.durVolume
    private val oldIndex = VideoPlay.chapterInVolumeIndex
    private val oldVolumeIndex = VideoPlay.durVolumeIndex
    private var owned = false

    @Before fun setup() {
        assertFalse(RestoreRecoveryGate.isQuarantined)
        assertFalse(RestoreFileRecovery.hasPending(context))
    }

    private fun quarantine() = runBlocking {
        backupRestoreMutex.recover {
            RestoreSettingsTransaction.prepare(context, UUID.randomUUID().toString(), false)
            owned = true
            RestoreRecoveryGate.requireRecovery()
        }
    }

    @After fun cleanup() {
        if (owned) runBlocking { backupRestoreMutex.recover {
            RestoreFileRecovery.recover(context, cleanup = true)
            assertEquals(defaultBefore, context.defaultSharedPreferences.all)
            RestoreRecoveryGate.onStartupReady()
        } }
        prefs.edit().apply {
            for (key in keys) {
                when (val value = before[key]) {
                    null -> remove(key)
                    is Boolean -> putBoolean(key, value)
                    is Int -> putInt(key, value)
                    else -> error("Unexpected fixture preference type")
                }
            }
        }.commit()
        VideoPlay.book = oldBook; VideoPlay.source = oldSource; VideoPlay.toc = oldToc
        VideoPlay.episodes = oldEpisodes; VideoPlay.durVolume = oldVolume
        VideoPlay.chapterInVolumeIndex = oldIndex; VideoPlay.durVolumeIndex = oldVolumeIndex
        assertEquals(before, prefs.all)
    }

    private fun baseline() {
        VideoPlay.autoPlay = false; VideoPlay.startFull = false; VideoPlay.defaultFloatWindow = false
        VideoPlay.longPressSpeed = 30; VideoPlay.fullBottomProgressBar = false
    }

    @Test fun readyVideoSettingsStillPersist() {
        baseline()
        VideoPlay.autoPlay = true; VideoPlay.startFull = true; VideoPlay.defaultFloatWindow = true
        VideoPlay.longPressSpeed = 45; VideoPlay.fullBottomProgressBar = true
        assertTrue(prefs.getBoolean("autoPlay", false)); assertTrue(prefs.getBoolean("startFull", false))
        assertTrue(prefs.getBoolean("defaultFloatWindow", false))
        assertEquals(45, prefs.getInt("longPressSpeed", 0)); assertTrue(prefs.getBoolean("fullBottomProgressBar", false))
    }

    @Test fun lateVideoSettingsDoNotOverwriteRecoveryBeforeImages() {
        baseline(); val stable = HashMap(prefs.all)
        quarantine()
        VideoPlay.autoPlay = true; VideoPlay.startFull = true; VideoPlay.defaultFloatWindow = true
        VideoPlay.longPressSpeed = 45; VideoPlay.fullBottomProgressBar = true
        assertEquals(stable, prefs.all)
    }

    @Test fun lateSourceInitializationDoesNotReplaceTheCurrentSession() {
        val book = Book(bookUrl = "video-gate-${UUID.randomUUID()}")
        VideoPlay.book = book
        quarantine()
        assertFalse(VideoPlay.initSource(null, null, null, null))
        assertSame(book, VideoPlay.book)
    }

    @Test fun lateEpisodeRefreshDoesNotReplaceTheCurrentList() {
        val chapter = BookChapter(bookUrl = "video-gate", url = UUID.randomUUID().toString(), title = "owned", index = 0)
        val episodes = listOf(chapter)
        VideoPlay.episodes = episodes
        VideoPlay.toc = emptyList()
        quarantine()
        VideoPlay.upEpisodes()
        assertSame(episodes, VideoPlay.episodes)
    }
}
