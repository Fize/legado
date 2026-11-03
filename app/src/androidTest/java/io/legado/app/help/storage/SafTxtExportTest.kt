package io.legado.app.help.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.appDb
import io.legado.app.data.cache.CacheResourceLeases
import io.legado.app.data.entities.*
import io.legado.app.data.preferences.CacheExportOptions
import io.legado.app.data.repository.*
import io.legado.app.help.book.BookHelp
import io.legado.app.service.BookExportTaskRunner
import io.legado.app.service.BookExportEngine
import io.legado.app.ui.test.runCleanupSteps
import io.legado.app.utils.MD5Utils
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.UUID

/** Real runner, separate DocumentsProvider process and synchronous kernel-backed proxy writes. */
@RunWith(AndroidJUnit4::class)
class SafTxtExportTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val id = UUID.randomUUID().toString()
    private val fixture = SafSidecarFixture(context, id)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tasks = BookTransferRepository(appDb)
    private val runner = BookExportTaskRunner(context, appDb, tasks, CacheResourceLeases())
    private val ownedTasks = linkedSetOf<String>()
    private val ownedBatches = linkedSetOf<String>()
    private val restore = NormalRestoreFixture(context, id, { ownedTasks }, { ownedBatches })
    private val journal = exportJournal(context)
    private var initialJournal = emptySet<String>()
    private val book = Book(bookUrl = "https://example.invalid/saf/$id", origin = "https://example.invalid/saf/$id/source",
        name = id, author = id, totalChapterNum = 1)
    private val chapter = BookChapter(bookUrl = book.bookUrl, url = "https://example.invalid/saf/$id/chapter", title = "Chapter", index = 0)
    private val src = "https://example.invalid/saf/$id/image.png"
    private val image = "${book.name}_${book.author}/images/Chapter/1-${MD5Utils.md5Encode16(src)}.jpg"
    private val bytes = ByteArray(131_089) { (it % 251).toByte() }
    private var seeded = false
    private var recovery = false

    @Before fun setup() {
        assertFalse(RestoreRecoveryGate.isQuarantined); assertFalse(RestoreFileRecovery.hasPending(context))
        assertTrue(appDb.bookTransferDao.active("download").isEmpty()); assertTrue(appDb.bookTransferDao.active("export").isEmpty())
        initialJournal = journal.items().map { it.id }.toSet()
        fixture.create(); fixture.seed("owned.txt", "OLD TXT".toByteArray()); fixture.seed(image, "OLD IMAGE".toByteArray())
        assertNull(appDb.bookDao.getBook(book.bookUrl)); appDb.bookDao.insert(book); appDb.bookChapterDao.insert(chapter); seeded = true
        assertTrue(BookHelp.saveContent(BookSource(bookSourceUrl = book.origin), book, chapter, "Body\n<img src=\"$src\">"))
        BookHelp.getImage(book, src).apply { check(parentFile!!.isDirectory || parentFile!!.mkdirs()); writeBytes(bytes) }
    }
    @After fun cleanup() = runCleanupSteps(
        { fixture.release(); scope.cancel(); runBlocking { scope.coroutineContext[Job]!!.join(); fixture.awaitClosed() } },
        { if (recovery) runBlocking { backupRestoreMutex.recover {
            RestoreFileRecovery.recover(context, cleanup = true); RestoreRecoveryGate.onStartupReady()
        } } },
        { restore.cleanup() },
        { if (seeded) {
            ownedTasks.forEach(appDb.bookTransferDao::deleteTask)
            ownedBatches.forEach { batch ->
                check(appDb.bookTransferDao.batchTasks(batch).isEmpty())
                appDb.openHelper.writableDatabase.execSQL("DELETE FROM book_transfer_batches WHERE id=?", arrayOf(batch))
            }
            BookHelp.clearCache(book); appDb.bookChapterDao.delByBook(book.bookUrl); appDb.bookDao.delete(book)
        } },
        { assertEquals(initialJournal, journal.items().map { it.id }.toSet()); fixture.close() },
    )
    private fun options() = CacheExportOptions(directory = fixture.tree.toString(), fileName = "'owned'", pictures = true, useReplace = false)
    private suspend fun claim(): BookTransferTask {
        val config = BookTransferConfig(options())
        val ids = tasks.enqueue(listOf(CacheBookRef(book.bookUrl, transferBookKey(book))), "export", config); ownedTasks.addAll(ids)
        val queued = appDb.bookTransferDao.get(ids.single())!!; ownedBatches.add(queued.batchId!!)
        val batch = appDb.bookTransferDao.batch(queued.batchId)!!
        return tasks.claimNext(tasks.claimBatch(batch.id, batch.dispatch)!!)!!
    }
    private suspend fun blocked(mode: String = "hold"): Pair<BookTransferTask, Deferred<Unit>> {
        fixture.arm(mode); val task = claim(); val work = scope.async { runner.run(task) }; fixture.awaitBlocked()
        if (mode == "hold") assertTrue(fixture.snapshot().getString("entered")!!.contains(".pending."))
        else assertEquals("read", fixture.snapshot().getString("operation"))
        assertFalse(work.isCompleted)
        assertArrayEquals("OLD IMAGE".toByteArray(), fixture.files()[image])
        return task to work
    }
    private suspend fun finished(work: Deferred<Unit>) = runCatching { withTimeout(10_000) { work.await() } }.exceptionOrNull()
    private fun preserved() {
        val files = fixture.files(); assertEquals(setOf("owned.txt", image), files.keys)
        assertArrayEquals("OLD IMAGE".toByteArray(), files[image]); assertArrayEquals("OLD TXT".toByteArray(), files["owned.txt"])
        assertEquals(initialJournal, journal.items().map { it.id }.toSet())
    }
    private fun readable() {
        val files = fixture.files(); assertEquals(setOf("owned.txt", image), files.keys)
        assertArrayEquals(bytes, files[image]); assertTrue(files["owned.txt"]!!.toString(Charsets.UTF_8).contains("Body"))
        assertEquals(initialJournal, journal.items().map { it.id }.toSet())
        assertTrue(fixture.snapshot().getInt("renames") >= 4)
    }

    @Test fun normalRunnerKeepsThePublishedImageWhenRenamePreservesItsUri() = runBlocking {
        val task = claim(); runner.run(task); fixture.awaitClosed(); readable()
        assertEquals("completed", appDb.bookTransferDao.get(task.id)!!.phase)
    }
    private suspend fun restoredWhileBlocked(mode: String) {
        val (task, work) = blocked(mode)
        withTimeout(8_000) { restore.restore(mapOf("bookSource.json" to "[]")) }
        fixture.release(); assertTrue(finished(work) is CancellationException); fixture.awaitClosed(); preserved()
        assertEquals("interrupted", appDb.bookTransferDao.get(task.id)!!.phase)
        assertEquals("cancelled", appDb.bookTransferDao.batch(task.batchId!!)!!.phase)
        tasks.retry(task.id); val retry = appDb.bookTransferDao.get(task.id)!!; ownedBatches.add(retry.batchId!!)
        fixture.arm("normal"); val batch = appDb.bookTransferDao.batch(retry.batchId)!!
        runner.run(tasks.claimNext(tasks.claimBatch(batch.id, batch.dispatch)!!)!!); fixture.awaitClosed(); readable()
        assertEquals("completed", appDb.bookTransferDao.get(task.id)!!.phase)
    }
    @Test fun restoreWhileTheProviderBlocksTheImageWritePreservesBothOldFilesAndAllowsRetry() = runBlocking { restoredWhileBlocked("hold") }
    @Test fun restoreWhileTheProviderBlocksTheJournalReadPreservesBothOldFilesAndAllowsRetry() = runBlocking { restoredWhileBlocked("hold_read") }
    @Test fun coroutineCancellationWhileTheProviderBlocksCannotPublishLateBytes() = runBlocking {
        val (task, work) = blocked(); work.cancel(); fixture.release()
        assertTrue(finished(work) is CancellationException); withTimeout(10_000) { work.join() }; fixture.awaitClosed(); preserved()
        assertEquals("interrupted", appDb.bookTransferDao.get(task.id)!!.phase)
    }
    @Test fun cancellingTheTaskWhileTheProviderBlocksCannotPublishLateBytes() = runBlocking {
        val (task, work) = blocked(); tasks.cancelIds(listOf(task.id)); fixture.release()
        assertNotNull(finished(work)); fixture.awaitClosed(); preserved()
        assertEquals("cancelled", appDb.bookTransferDao.get(task.id)!!.phase)
    }
    @Test fun quarantineWhileTheProviderBlocksPreservesTaskFieldsAndOldFiles() = runBlocking {
        val (task, work) = blocked(); val previous = appDb.bookTransferDao.get(task.id)
        backupRestoreMutex.recover {
            val pending = RestoreSettingsTransaction.prepare(context, UUID.randomUUID().toString(), false); recovery = true
            check(pending.editDefault().putString("saf-sidecar-$id", "pending").commit()); RestoreRecoveryGate.requireRecovery()
        }
        fixture.release(); assertTrue(finished(work) is CancellationException); fixture.awaitClosed(); preserved()
        assertEquals(previous, appDb.bookTransferDao.get(task.id))
    }
    @Test fun changingTheCatalogWhileTheProviderBlocksRejectsTheImagePublication() = runBlocking {
        val (task, work) = blocked(); appDb.bookChapterDao.update(chapter.copy(title = "Changed")); fixture.release()
        assertNotNull(finished(work)); fixture.awaitClosed(); preserved(); assertEquals("failed", appDb.bookTransferDao.get(task.id)!!.phase)
    }
    @Test fun anOrdinaryBackupLockWaitsAfterTheProviderReleasesWithoutLosingTheExport() = runBlocking {
        val (task, work) = blocked()
        backupRestoreMutex.recover {
            fixture.release(); delay(200); assertFalse(work.isCompleted)
            assertArrayEquals("OLD IMAGE".toByteArray(), fixture.files()[image])
        }
        assertNull(finished(work)); fixture.awaitClosed(); readable(); assertEquals("completed", appDb.bookTransferDao.get(task.id)!!.phase)
    }
    @Test fun aProviderEnospcErrorPreservesTheOldImageAndMainFile() = runBlocking {
        fixture.arm("write_fail"); val task = claim()
        assertNotNull(runCatching { runner.run(task) }.exceptionOrNull()); fixture.awaitClosed(); preserved()
        assertEquals("failed", appDb.bookTransferDao.get(task.id)!!.phase); assertTrue(fixture.snapshot().getInt("writes") > 0)
    }
    @Test fun aProviderActivationRenameFailureRollsBackTheOldImage() = runBlocking {
        fixture.arm("rename_fail"); val task = claim()
        assertNotNull(runCatching { runner.run(task) }.exceptionOrNull()); fixture.awaitClosed(); preserved()
        assertEquals("failed", appDb.bookTransferDao.get(task.id)!!.phase); assertTrue(fixture.snapshot().getInt("renames") >= 2)
    }
    @Test fun aProviderReadErrorPreservesTheOldImageAndMainFile() = runBlocking {
        fixture.arm("read_fail"); val task = claim()
        assertNotNull(runCatching { runner.run(task) }.exceptionOrNull()); fixture.awaitClosed(); preserved()
        assertEquals("failed", appDb.bookTransferDao.get(task.id)!!.phase); assertTrue(fixture.snapshot().getInt("reads") > 0)
    }
    @Test fun lostTreeAccessRetainsScratchOwnershipUntilRegrantAndExplicitRetry() = runBlocking {
        val (task, work) = blocked(); fixture.revoke(); fixture.release()
        assertNotNull(finished(work)); fixture.awaitClosed()
        assertEquals("failed", appDb.bookTransferDao.get(task.id)!!.phase)
        assertArrayEquals("OLD IMAGE".toByteArray(), fixture.files()[image]); assertArrayEquals("OLD TXT".toByteArray(), fixture.files()["owned.txt"])
        assertTrue(fixture.files().keys.any { it.contains(".pending.") })
        fixture.grant(); tasks.retry(task.id)
        val retry = appDb.bookTransferDao.get(task.id)!!; ownedBatches.add(retry.batchId!!)
        val batch = appDb.bookTransferDao.batch(retry.batchId)!!; fixture.arm("normal")
        runner.run(tasks.claimNext(tasks.claimBatch(batch.id, batch.dispatch)!!)!!); fixture.awaitClosed(); readable()
        assertEquals("completed", appDb.bookTransferDao.get(task.id)!!.phase)
    }
    @Test fun theRetainedDirectCopyPathReproducesTheOldImageOverwriteDuringRestore() = runBlocking {
        fixture.arm("hold")
        val work = scope.async {
            val boundary = ExportRestoreBoundary(appDb, restoreWriteGeneration.capture(), currentCoroutineContext())
            BookExportEngine(context, appDb, options(), { _, _ -> }, { boundary.checkCurrent() },
                publication = { action -> boundary.synchronous(action) }).export(book)
        }
        fixture.awaitBlocked(); assertFalse(fixture.snapshot().getString("entered")!!.contains(".pending."))
        assertEquals(0, fixture.files()[image]!!.size) // The old writer already truncated the canonical image.
        withTimeout(8_000) { restore.restore(mapOf("bookSource.json" to "[]")) }
        fixture.release(); assertTrue(finished(work) is CancellationException); fixture.awaitClosed()
        val files = fixture.files(); assertEquals(setOf("owned.txt", image), files.keys)
        assertArrayEquals(bytes, files[image]); assertArrayEquals("OLD TXT".toByteArray(), files["owned.txt"])
        assertEquals(initialJournal, journal.items().map { it.id }.toSet())
    }
}
