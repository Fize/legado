package io.legado.app.help.storage

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.ViewGroup
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.help.globalExecutor
import io.legado.app.model.ReadBook
import io.legado.app.model.AudioPlay
import io.legado.app.model.VideoPlay
import io.legado.app.receiver.MediaButtonReceiver
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.book.audio.AudioPlayActivity
import io.legado.app.ui.video.VideoPlayerActivity
import io.legado.app.ui.recovery.RestoreRecoveryActivity
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.work.BookInfoDeleteWorker
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class RestoreRecoveryGateTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val id = UUID.randomUUID().toString()
    private val root = File(context.cacheDir, "recovery-gate-fixture-$id")
    private val key = "restore_gate_$id"
    private val preferences = context.defaultSharedPreferences
    private val original = HashMap(preferences.all)
    private val stage = File(Backup.backupPath, "gate-$id.bin")
    private val archive = File(root, "existing.zip")
    private val journal = File(context.filesDir, "restore-settings-v1.bin")
    private lateinit var valid: ByteArray
    private var oldZipHash: String? = null
    private val work = "restore-gate-work-$id"

    @Before fun setup() = runBlocking {
        assertFalse(RestoreFileRecovery.hasPending(context)); assertFalse(RestoreRecoveryGate.isQuarantined)
        backupRestoreMutex.recover {
            check(root.mkdirs()); archive.writeText("existing archive $id")
            stage.parentFile!!.mkdirs(); stage.writeText("active input $id")
            oldZipHash = File(Backup.zipFilePath).takeIf { it.isFile }?.let(::hash)
            val transaction = RestoreSettingsTransaction.prepare(context, UUID.randomUUID().toString(), false)
            transaction.editDefault().putString(key, "mixed-$id").apply()
            valid = journal.readBytes()
            RestoreRecoveryGate.requireRecovery()
        }
    }

    @After fun cleanup() = runBlocking {
        WorkManager.getInstance(context).cancelUniqueWork(work).result.get(10, TimeUnit.SECONDS)
        withTimeout(15_000) { while (RestoreRecoveryGate.status.value == RestoreRecoveryStatus.Retrying) delay(10) }
        backupRestoreMutex.recover {
            if (journal.isFile) journal.writeBytes(valid)
            RestoreFileRecovery.recover(context, cleanup = true)
            assertEquals(original, preferences.all)
            assertEquals(oldZipHash, File(Backup.zipFilePath).takeIf { it.isFile }?.let(::hash))
            check(stage.delete()); root.deleteRecursively()
            // This test's fixture state is restored; production does this on a fresh Application.
            RestoreRecoveryGate.onStartupReady()
        }
    }

    @Test fun directTextReaderLaunchSkipsContentAndRedirectsToRecovery() = nativeEntry(ReadBookActivity::class.java)
    @Test fun directAudioLaunchSkipsContentAndRedirectsToRecovery() = nativeEntry(AudioPlayActivity::class.java)
    @Test fun directVideoLaunchSkipsContentAndRedirectsToRecovery() = nativeEntry(VideoPlayerActivity::class.java)

    private fun nativeEntry(type: Class<out Activity>) = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = context.applicationContext as Application
        val observed = AtomicReference<Activity>()
        val childCount = AtomicReference<Int>()
        val callback = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, state: Bundle?) {
                if (activity.javaClass == type) {
                    observed.set(activity)
                    childCount.set(activity.findViewById<ViewGroup>(android.R.id.content)?.childCount ?: 0)
                }
            }
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        }
        val before = journal.readBytes()
        application.registerActivityLifecycleCallbacks(callback)
        try {
            context.startActivity(Intent(context, type).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            withTimeout(15_000) {
                while (true) {
                    var ready = false
                    instrumentation.runOnMainSync {
                        ready = observed.get()?.isDestroyed == true &&
                            ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                                .any { it is RestoreRecoveryActivity }
                    }
                    if (ready) break
                    delay(25)
                }
            }
            assertEquals(0, childCount.get())
            assertTrue(RestoreRecoveryGate.isQuarantined)
            assertArrayEquals(before, journal.readBytes())
            assertSentinels()
        } finally {
            application.unregisterActivityLifecycleCallbacks(callback)
            instrumentation.runOnMainSync {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<RestoreRecoveryActivity>().forEach { it.finish() }
            }
            instrumentation.waitForIdleSync()
        }
    }

    @Test fun mediaButtonsDoNotReopenPlaybackWhileRecoveryIsRequired() {
        val oldText = ReadBook.book
        val oldAudio = AudioPlay.book
        assertFalse(MediaButtonReceiver.handleIntent(context, Intent(Intent.ACTION_MEDIA_BUTTON)
            .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))))
        MediaButtonReceiver.readAloud(context, isMediaKey = false)
        assertSame(oldText, ReadBook.book); assertSame(oldAudio, AudioPlay.book)
        assertSentinels()
    }

    @Test fun readerProgressSaversCannotPublishStaleMemoryAfterRecoveryFailure() = progressWrites(false)
    @Test fun progressWritesQueuedBeforeRecoveryFailureAreDiscardedBeforeExecution() = progressWrites(true)

    private fun progressWrites(queued: Boolean) {
        val text = Book(bookUrl = "recovery-text-$id", name = "Text $id", durChapterIndex = 7, durChapterPos = 11)
        val audio = text.copy(bookUrl = "recovery-audio-$id", name = "Audio $id")
        val video = text.copy(bookUrl = "recovery-video-$id", name = "Video $id")
        val books = listOf(text, audio, video)
        val oldText = ReadBook.book; val oldTextIndex = ReadBook.durChapterIndex; val oldTextPos = ReadBook.durChapterPos
        val oldAudio = AudioPlay.book; val oldAudioIndex = AudioPlay.durChapterIndex; val oldAudioPos = AudioPlay.durChapterPos
        val oldVideo = VideoPlay.book; val oldVideoPos = VideoPlay.durChapterPos
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)
        try {
            appDb.bookDao.insert(*books.toTypedArray())
            ReadBook.book = text.copy(); ReadBook.durChapterIndex = 29; ReadBook.durChapterPos = 999
            AudioPlay.book = audio.copy(); AudioPlay.durChapterIndex = 29; AudioPlay.durChapterPos = 999
            VideoPlay.book = video.copy()
            if (queued) {
                globalExecutor.execute { entered.countDown(); check(release.await(15, TimeUnit.SECONDS)) }
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                RestoreRecoveryGate.onStartupReady()
            }
            ReadBook.saveRead(); AudioPlay.saveRead(); VideoPlay.saveRead(999)
            RestoreRecoveryGate.requireRecovery()
            release.countDown()
            globalExecutor.submit {}.get(15, TimeUnit.SECONDS)
            books.forEach { book ->
                val persisted = requireNotNull(appDb.bookDao.getBook(book.bookUrl))
                // Book.equals only compares URLs, so compare the actual persisted progress columns.
                assertEquals(book.durChapterIndex, persisted.durChapterIndex)
                assertEquals(book.durChapterPos, persisted.durChapterPos)
                assertEquals(book.durChapterTime, persisted.durChapterTime)
                assertEquals(book.durChapterTitle, persisted.durChapterTitle)
                assertEquals(book.durVolumeIndex, persisted.durVolumeIndex)
                assertEquals(book.chapterInVolumeIndex, persisted.chapterInVolumeIndex)
            }
        } finally {
            release.countDown()
            RestoreRecoveryGate.requireRecovery()
            globalExecutor.submit {}.get(15, TimeUnit.SECONDS)
            ReadBook.book = oldText; ReadBook.durChapterIndex = oldTextIndex; ReadBook.durChapterPos = oldTextPos
            AudioPlay.book = oldAudio; AudioPlay.durChapterIndex = oldAudioIndex; AudioPlay.durChapterPos = oldAudioPos
            VideoPlay.book = oldVideo; VideoPlay.durChapterPos = oldVideoPos
            appDb.bookDao.delete(*books.toTypedArray())
        }
    }

    @Test fun malformedRecordBlocksRealBackupLanSnapshotAndStagingCleanup() = runBlocking {
        journal.writeBytes(valid.copyOf().apply { this[size / 2] = (this[size / 2].toInt() xor 1).toByte() })
        val before = hash(journal)
        rejected { Backup.backupLocked(context, root.path, uploadWebDav = false) }
        rejected { Backup.backupForLanTransferLocked(context) }
        Backup.clearCache()
        assertEquals(before, hash(journal)); assertSentinels()
    }

    @Test fun anUnresolvedRecordIsDetectedWithoutAnExistingUiBarrier() = runBlocking {
        RestoreRecoveryGate.onStartupReady()
        assertFalse(RestoreRecoveryGate.isQuarantined)
        rejected { Backup.backupLocked(context, root.path, uploadWebDav = false) }
        assertEquals(RestoreRecoveryStatus.Required, RestoreRecoveryGate.status.value)
        assertFalse(backupRestoreMutex.isLocked)
        assertFalse(backupRestoreMutex.tryLock())
        assertFalse(backupRestoreMutex.isLocked)
        assertEquals("mixed-$id", preferences.getString(key, null))
        assertSentinels()
    }

    @Test fun resolvedRecordsWaitingForCleanupDoNotRequireUserRecovery() = runBlocking {
        backupRestoreMutex.recover { RestoreFileRecovery.recover(context, cleanup = false) }
        RestoreRecoveryGate.onStartupReady()
        assertTrue(RestoreFileRecovery.hasPending(context)); assertTrue(RestoreFileRecovery.pendingIsResolved(context))
        var entered = false
        backupRestoreMutex.withLock { entered = true }
        assertTrue(entered); assertTrue(backupRestoreMutex.tryLock()); backupRestoreMutex.unlock()
        assertFalse(RestoreRecoveryGate.isQuarantined)
    }

    @Test fun successfulRetryPreservesDataAndStillBlocksBackupsUntilReopen() = runBlocking {
        RestoreRecoveryGate.retry(context)
        withTimeout(15_000) { while (RestoreRecoveryGate.status.value == RestoreRecoveryStatus.Retrying) delay(10) }
        assertEquals(RestoreRecoveryStatus.ReopenRequired, RestoreRecoveryGate.status.value)
        assertEquals(original, preferences.all); assertFalse(RestoreFileRecovery.hasPending(context))
        rejected { Backup.backupLocked(context, root.path, uploadWebDav = false) }
        Backup.clearCache(); assertSentinels()
    }

    @Test fun failedRetryKeepsTheRecordAndNeverEnablesBackups() = runBlocking {
        journal.writeText("damaged fixture $id")
        val before = journal.readBytes()
        RestoreRecoveryGate.retry(context)
        withTimeout(15_000) { while (RestoreRecoveryGate.status.value == RestoreRecoveryStatus.Retrying) delay(10) }
        assertEquals(RestoreRecoveryStatus.RetryFailed, RestoreRecoveryGate.status.value)
        assertArrayEquals(before, journal.readBytes())
        assertEquals("mixed-$id", preferences.getString(key, null))
        rejected { Backup.backupLocked(context, root.path, uploadWebDav = false) }
        assertSentinels()
    }

    @Test fun actualWorkManagerDefersBeforeConstructingTheBusinessWorker() = runBlocking {
        val manager = WorkManager.getInstance(context)
        // Without the recovery factory, missing commandId makes the real worker fail immediately.
        val request = OneTimeWorkRequestBuilder<BookInfoDeleteWorker>().build()
        manager.enqueueUniqueWork(work, ExistingWorkPolicy.KEEP, request).result.get(10, TimeUnit.SECONDS)
        val info = withTimeout(20_000) {
            while (true) {
                val current = manager.getWorkInfoById(request.id).get(10, TimeUnit.SECONDS)
                check(current?.state?.isFinished != true) { "Business worker ran while recovery was blocked: ${current?.state}" }
                if (current != null && current.runAttemptCount > 0 && current.state == WorkInfo.State.ENQUEUED) return@withTimeout current
                delay(50)
            }
            error("unreachable")
        }
        assertEquals(WorkInfo.State.ENQUEUED, info.state)
        assertTrue(info.runAttemptCount > 0); assertSentinels()
    }

    @Test fun theActualReaderProviderRejectsBusinessReadsDuringRecovery() {
        val result = runCatching {
            context.contentResolver.query(Uri.parse("content://${context.packageName}.readerProvider/books/query"), null, null, null, null)?.use { it.count }
        }
        assertTrue(result.exceptionOrNull() is IllegalStateException)
        assertSentinels()
    }

    private suspend fun rejected(block: suspend () -> Any?) {
        val failure = runCatching { block() }.exceptionOrNull()
        assertTrue("Expected the recovery barrier, got $failure", failure is RestoreRecoveryRequiredException)
    }
    private fun assertSentinels() {
        assertEquals("active input $id", stage.readText())
        assertEquals("existing archive $id", archive.readText())
        assertEquals(oldZipHash, File(Backup.zipFilePath).takeIf { it.isFile }?.let(::hash))
    }
    private fun hash(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
}
