package io.legado.app.help.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.appDb
import io.legado.app.data.cache.CacheResourceLeases
import io.legado.app.data.entities.*
import io.legado.app.data.preferences.CacheExportOptions
import io.legado.app.data.repository.*
import io.legado.app.help.book.BookHelp
import io.legado.app.service.BookExportEngine
import io.legado.app.service.BookExportTaskRunner
import io.legado.app.service.TxtExportSidecars
import io.legado.app.ui.test.runCleanupSteps
import io.legado.app.utils.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Local-file publication boundaries, using the retained engine and real restore, not a fake exporter. */
@RunWith(AndroidJUnit4::class)
class TxtExportSidecarsTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val id = UUID.randomUUID().toString()
    private val root = File(context.cacheDir, "txt-sidecar-$id")
    private val output = File(root, "output")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tasks = BookTransferRepository(appDb)
    private val ownedTasks = linkedSetOf<String>()
    private val ownedBatches = linkedSetOf<String>()
    private val restore = NormalRestoreFixture(context, id, { ownedTasks }, { ownedBatches })
    private val book = Book(bookUrl = "https://example.invalid/$id", origin = "https://example.invalid/$id/source",
        name = id, author = id, totalChapterNum = 1)
    private val chapter = BookChapter(bookUrl = book.bookUrl, url = "https://example.invalid/$id/chapter", title = "Chapter", index = 0)
    private val src = "https://example.invalid/$id/image.png"
    private val imageName = "1-${MD5Utils.md5Encode16(src)}.jpg"
    private val target get() = File(output, "${book.name}_${book.author}/images/${chapter.title}/$imageName")
    private val main get() = File(output, "owned.txt")
    private val bytes = ByteArray(131_089) { (it % 251).toByte() }
    private val entered = CountDownLatch(1)
    private val release = CountDownLatch(1)
    @Volatile private var hold = false
    @Volatile private var failAfterImage = false
    private var ownsRoot = false
    private var seeded = false
    private var ownsRecovery = false
    private val legacy = InstrumentationRegistry.getArguments().getString("sidecarLegacy") == "true"
    private val journal = BookExportJournal(File(root, "journal"), id)
    private fun verifyFixture() {
        if (failAfterImage && target.length() == bytes.size.toLong()) throw java.io.IOException("Owned main-file failure")
        // Pause AFTER the real image copy and BEFORE publication. The retained path
        // has already overwritten the image; the adapter has only written a stage.
        val copied = target.length() == bytes.size.toLong() || target.parentFile!!.listFiles().orEmpty().any {
            it.name.contains(".pending.") && it.name.endsWith(imageName) && it.length() == bytes.size.toLong()
        }
        if (hold && copied) {
            entered.countDown()
            check(release.await(15, TimeUnit.SECONDS)) { "Owned sidecar fixture timed out" }
        }
    }

    @Before fun setup() {
        assertFalse(RestoreRecoveryGate.isQuarantined); assertFalse(RestoreFileRecovery.hasPending(context))
        assertTrue(appDb.bookTransferDao.active("download").isEmpty()); assertTrue(appDb.bookTransferDao.active("export").isEmpty())
        check(!root.exists()); check(output.mkdirs()); ownsRoot = true
        assertNull(appDb.bookDao.getBook(book.bookUrl))
        appDb.bookDao.insert(book); appDb.bookChapterDao.insert(chapter); seeded = true
        assertTrue(BookHelp.saveContent(BookSource(bookSourceUrl = book.origin), book, chapter, "Body\n<img src=\"$src\">"))
        BookHelp.getImage(book, src).apply { check(parentFile!!.isDirectory || parentFile!!.mkdirs()); writeBytes(bytes) }
        target.apply { check(parentFile!!.mkdirs()); writeText("OLD IMAGE") }
        main.writeText("OLD TXT")
    }

    @After fun cleanup() = runCleanupSteps(
        { release.countDown(); scope.cancel(); runBlocking { scope.coroutineContext[Job]!!.join() } },
        { if (ownsRecovery) runBlocking { backupRestoreMutex.recover {
            RestoreFileRecovery.recover(context, cleanup = true); RestoreRecoveryGate.onStartupReady()
        } } },
        { restore.cleanup() },
        { if (seeded) {
            ownedTasks.forEach(appDb.bookTransferDao::deleteTask)
            ownedBatches.forEach { batch ->
                check(appDb.bookTransferDao.batchTasks(batch).isEmpty())
                appDb.openHelper.writableDatabase.execSQL("DELETE FROM book_transfer_batches WHERE id=?", arrayOf(batch))
            }
            BookHelp.clearCache(book)
            appDb.bookChapterDao.delByBook(book.bookUrl); appDb.bookDao.delete(book)
        } },
        { if (ownsRoot) { assertTrue(journal.items().isEmpty()); check(root.deleteRecursively() || !root.exists()) } },
    )

    private fun options() = CacheExportOptions(directory = output.path, fileName = "'owned'", pictures = true, useReplace = false)
    private suspend fun export() = withContext(Dispatchers.IO) {
        val boundary = ExportRestoreBoundary(appDb, restoreWriteGeneration.capture(), currentCoroutineContext())
        val sidecars = TxtExportSidecars(appDb, book, FileDoc.fromDir(output.path), journal, boundary, ::verifyFixture, { action -> appDb.runInTransaction<FileDoc> { action() } })
        val engine = BookExportEngine(context, appDb, options(), { _, _ -> },
            { boundary.checkCurrent(); sidecars.captureCatalog(); verifyFixture() },
            publication = { action -> boundary.synchronous { appDb.runInTransaction<FileDoc> { action() } } }, journal = journal)
        try {
            if (legacy) engine.export(book)
            else withContext(FileDocWriteContext(sidecars)) { engine.export(book) }
        } finally { sidecars.close() }
    }
    private fun paused(): Deferred<Unit> {
        hold = true
        return scope.async { export() }.also { work ->
            check(entered.await(10, TimeUnit.SECONDS)) {
                "Pause not reached; completed=${work.isCompleted}; failure=" +
                    if (work.isCompleted) runBlocking { runCatching { work.await() }.exceptionOrNull() } else "still running"
            }
        }
    }
    private suspend fun finished(work: Deferred<Unit>): Throwable? = runCatching { withTimeout(10_000) { work.await() } }.exceptionOrNull()
    private fun preserved() {
        assertArrayEquals("OLD IMAGE".toByteArray(), target.readBytes()); assertEquals("OLD TXT", main.readText())
        assertTrue(output.walkTopDown().none { it.name.contains(".pending.") || it.name.contains(".backup.") })
        assertTrue(journal.items().isEmpty())
    }
    private fun readable() {
        assertArrayEquals(bytes, target.readBytes())
        assertTrue(main.readText().contains("Body"))
        assertEquals(setOf("owned.txt", "${book.name}_${book.author}/images/Chapter/$imageName"),
            output.walkTopDown().filter { it.isFile }.map { it.relativeTo(output).path }.toSet())
        assertTrue(journal.items().isEmpty())
    }

    @Test fun normalExportKeepsTheLegacyImagePathAndRawBytes() = runBlocking { export(); readable() }

    @Test fun realRunnerPublishesImagesAndCompletesItsOwnedTask() = runBlocking {
        val ids = tasks.enqueue(listOf(CacheBookRef(book.bookUrl, transferBookKey(book))), "export", BookTransferConfig(options()))
        ownedTasks.addAll(ids)
        val queued = appDb.bookTransferDao.get(ids.single())!!; ownedBatches.add(queued.batchId!!)
        val batch = appDb.bookTransferDao.batch(queued.batchId)!!
        val task = tasks.claimNext(tasks.claimBatch(batch.id, batch.dispatch)!!)!!
        BookExportTaskRunner(context, appDb, tasks, CacheResourceLeases()).run(task)
        assertEquals("completed", appDb.bookTransferDao.get(task.id)!!.phase); readable()
    }

    @Test fun restoreBeforePublicationPreservesTheOldImageAndFreshRetryWorks() = runBlocking {
        val work = paused(); restore.restore(mapOf("bookSource.json" to "[]")); release.countDown()
        assertTrue(finished(work) is CancellationException); preserved()
        export(); readable()
    }

    @Test fun cancelledCallerCannotReplaceTheOldImage() = runBlocking {
        val work = paused(); work.cancel(); release.countDown()
        assertTrue(finished(work) is CancellationException); withTimeout(10_000) { work.join() }; preserved()
    }

    @Test fun changedChapterCannotReplaceTheOldImage() = runBlocking {
        val work = paused(); appDb.bookChapterDao.update(chapter.copy(title = "Changed chapter")); release.countDown()
        assertNotNull(finished(work)); preserved()
    }

    @Test fun quarantinedRestoreCannotReplaceTheOldImage() = runBlocking {
        val work = paused()
        backupRestoreMutex.recover {
            val pending = RestoreSettingsTransaction.prepare(context, UUID.randomUUID().toString(), false); ownsRecovery = true
            check(pending.editDefault().putString("txt-sidecar-$id", "pending").commit()); RestoreRecoveryGate.requireRecovery()
        }
        release.countDown(); assertTrue(finished(work) is CancellationException); preserved()
    }

    @Test fun ordinaryLockContentionWaitsWithoutChangingTheTarget() = runBlocking {
        val work = paused()
        backupRestoreMutex.recover {
            release.countDown(); delay(150)
            assertFalse(work.isCompleted); assertArrayEquals("OLD IMAGE".toByteArray(), target.readBytes()); assertEquals("OLD TXT", main.readText())
        }
        assertNull(finished(work)); readable()
    }

    @Test fun aCopyReadFailurePreservesTheOldImageAndTxt() = runBlocking {
        BookHelp.getImage(book, src).apply { check(delete()); check(mkdir()) }
        assertNotNull(runCatching { export() }.exceptionOrNull()); preserved()
    }

    @Test fun anImageAlreadyPublishedRemainsWhenTheLaterTxtFails() = runBlocking {
        failAfterImage = true
        assertNotNull(runCatching { export() }.exceptionOrNull())
        assertArrayEquals(bytes, target.readBytes()); assertEquals("OLD TXT", main.readText())
        assertTrue(journal.items().isEmpty())
        assertTrue(output.walkTopDown().none { it.name.contains(".pending.") || it.name.contains(".backup.") })
    }
}
