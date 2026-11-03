package io.legado.app.help.storage

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import fi.iki.elonen.NanoHTTPD
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.cache.CacheResourceLeases
import io.legado.app.data.entities.*
import io.legado.app.data.preferences.CacheExportOptions
import io.legado.app.data.repository.*
import io.legado.app.service.BookExportTaskRunner
import io.legado.app.service.BookTransferEntryPoint
import io.legado.app.ui.test.runCleanupSteps
import io.legado.app.utils.defaultSharedPreferences
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipFile

/** Real engine/journal outputs and HTTP PUT, scoped to UUID-owned rows, files and credentials. */
@RunWith(AndroidJUnit4::class)
class RestoreBookExportTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val id = UUID.randomUUID().toString()
    private val ownedTasks = linkedSetOf<String>()
    private val ownedBatches = linkedSetOf<String>()
    private val restore = NormalRestoreFixture(context, id, { ownedTasks }, { ownedBatches })
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val root = File(context.cacheDir, "export-restore-$id")
    private val tasks = BookTransferRepository(appDb)
    private val runner = BookExportTaskRunner(context, appDb, tasks, CacheResourceLeases())
    private val journal = exportJournal(context)
    private var journalIds = emptySet<String>()
    private val prefs = context.defaultSharedPreferences
    private val keys = listOf(PreferKey.cronet, PreferKey.autoBackup, PreferKey.webDavAccount,
        PreferKey.webDavPassword, PreferKey.webDavUrl, PreferKey.webDavDir)
    private val previous = keys.associateWith { prefs.all[it] }
    private val entered = CountDownLatch(1)
    private val release = CountDownLatch(1)
    private val putStarted = AtomicInteger()
    private val putCommitted = AtomicInteger()
    private val uploaded = AtomicReference<ByteArray?>()
    private var server: NanoHTTPD? = null
    private lateinit var book: Book
    private var seeded = false
    private var ownsRoot = false
    private var ownsJournal = false
    private var ownsTrigger = false
    private val trigger = "compat_export_" + id.replace("-", "")
    @Volatile private var holdCover = false
    @Volatile private var holdPut = false
    private val base get() = "http://127.0.0.1:${server!!.listeningPort}"
    private val png by lazy {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        try { ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() } }
        finally { bitmap.recycle() }
    }

    @Before fun setup() {
        assertFalse(RestoreRecoveryGate.isQuarantined); assertFalse(RestoreFileRecovery.hasPending(context))
        assertTrue(appDb.bookTransferDao.active("download").isEmpty()); assertTrue(appDb.bookTransferDao.active("export").isEmpty())
        appDb.openHelper.readableDatabase.query("SELECT count(*) FROM book_transfer_batches WHERE phase NOT IN ('completed','failed','cancelled')").use {
            assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
        }
        check(!root.exists()); check(root.mkdirs()); ownsRoot = true
        journalIds = journal.items().map { it.id }.toSet()
        server = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response {
                if (session.method == Method.PUT) {
                    val files = mutableMapOf<String, String>(); session.parseBody(files)
                    val bytes = File(checkNotNull(files["content"])).readBytes()
                    putStarted.incrementAndGet(); entered.countDown()
                    if (holdPut && !release.await(30, TimeUnit.SECONDS)) return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, "text/plain", "fixture timeout")
                    uploaded.set(bytes); putCommitted.incrementAndGet()
                    return newFixedLengthResponse(Response.Status.CREATED, "text/plain", "created")
                }
                if (holdCover) {
                    entered.countDown()
                    if (!release.await(30, TimeUnit.SECONDS)) return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, "text/plain", "fixture timeout")
                }
                return newFixedLengthResponse(Response.Status.OK, "image/png", png.inputStream(), png.size.toLong())
            }
        }.apply { start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
        check(prefs.edit().putBoolean(PreferKey.cronet, false).putBoolean(PreferKey.autoBackup, false)
            .putString(PreferKey.webDavAccount, "owned-$id").putString(PreferKey.webDavPassword, "owned-secret-$id")
            .putString(PreferKey.webDavUrl, "$base/dav/$id/").putString(PreferKey.webDavDir, "").commit())
        book = Book(bookUrl = "$base/book/$id", origin = "$base/source/$id", name = id, author = id,
            coverUrl = "$base/cover.png?owner=$id", totalChapterNum = 1)
        assertNull(appDb.bookDao.getBook(book.bookUrl)); appDb.bookDao.insert(book)
        appDb.bookChapterDao.insert(BookChapter(bookUrl = book.bookUrl, url = "volume-$id", title = id, isVolume = true))
        seeded = true
    }

    @After fun cleanup() = runCleanupSteps(
        { release.countDown(); scope.cancel(); runBlocking { scope.coroutineContext[Job]!!.join() }; server?.stop() },
        { if (ownsTrigger) appDb.openHelper.writableDatabase.execSQL("DROP TRIGGER IF EXISTS $trigger") },
        { if (ownsJournal) runBlocking { backupRestoreMutex.recover {
            RestoreFileRecovery.recover(context, cleanup = true); RestoreRecoveryGate.onStartupReady()
        } } },
        { restore.cleanup() },
        { if (seeded) {
            ownedTasks.forEach(appDb.bookTransferDao::deleteTask)
            ownedBatches.forEach { batch ->
                assertTrue(appDb.bookTransferDao.batchTasks(batch).isEmpty())
                appDb.openHelper.writableDatabase.execSQL("DELETE FROM book_transfer_batches WHERE id=?", arrayOf(batch))
            }
            appDb.bookChapterDao.delByBook(book.bookUrl); appDb.bookDao.delete(book)
        } },
        { check(prefs.edit().apply { previous.forEach { (key, value) -> when (value) {
            null -> remove(key); is Boolean -> putBoolean(key, value); is String -> putString(key, value)
            else -> error("Unexpected export preference type")
        } } }.commit()) },
        { if (ownsRoot) {
            // A retained journal keeps its output and before image available for recovery.
            assertEquals(journalIds, journal.items().map { it.id }.toSet())
            check(root.deleteRecursively() || !root.exists())
        } },
    )

    private suspend fun queued(format: Int = 1, webDav: Boolean = false): BookTransferTask {
        val targetKey = if (webDav) webDavTargetKey() else null
        val config = BookTransferConfig(CacheExportOptions(format = format, directory = root.path, fileName = "'owned'", webDav = webDav), webDavKey = targetKey)
        val ids = tasks.enqueue(listOf(CacheBookRef(book.bookUrl, transferBookKey(book))), "export", config)
        ownedTasks.addAll(ids)
        return appDb.bookTransferDao.get(ids.single())!!.also { ownedBatches.add(it.batchId!!) }
    }
    private fun webDavTargetKey(): String = io.legado.app.service.BookExportWebDavTarget.capture(context).key
    private suspend fun claim(format: Int = 1, webDav: Boolean = false): BookTransferTask {
        val task = queued(format, webDav); val batch = appDb.bookTransferDao.batch(task.batchId!!)!!
        return tasks.claimNext(tasks.claimBatch(batch.id, batch.dispatch)!!)!!
    }
    private suspend fun restored() = restore.restore(mapOf("bookSource.json" to "[]"))
    private suspend fun quarantine() = backupRestoreMutex.recover {
        val pending = RestoreSettingsTransaction.prepare(context, UUID.randomUUID().toString(), false); ownsJournal = true
        check(pending.editDefault().putString("book-export-$id", "pending").commit()); RestoreRecoveryGate.requireRecovery()
    }
    private fun before(format: Int = 1) = File(root, "owned.${listOf("txt", "epub", "pdf")[format]}").apply { writeText("PREVIOUS $id") }
    private fun waitForResponse() = assertTrue(entered.await(15, TimeUnit.SECONDS))
    private suspend fun failure(work: Deferred<Unit>): Throwable? = runCatching { withTimeout(15_000) { work.await() } }.exceptionOrNull()
    private fun unchanged(file: File) { assertEquals("PREVIOUS $id", file.readText()); assertEquals(listOf(file.name), root.list()!!.toList()) }
    private fun injectFailure() {
        ownsTrigger = true
        appDb.openHelper.writableDatabase.execSQL("""CREATE TRIGGER $trigger BEFORE INSERT ON caches
            WHEN NEW.key = '${RestoreFileRecovery.COMMIT_KEY}' BEGIN SELECT RAISE(ABORT,'owned export restore failure'); END""")
    }

    private fun cold(format: Int) = runBlocking {
        val output = before(format); val task = claim(format); quarantine()
        val error = runCatching { runner.run(task) }.exceptionOrNull()
        assertEquals(task, appDb.bookTransferDao.get(task.id)); unchanged(output); assertTrue(error is CancellationException)
    }
    @Test fun quarantinedTxtDoesNotPublishOrChangeTask() = cold(0)
    @Test fun quarantinedEpubDoesNotPublishOrChangeTask() = cold(1)
    @Test fun quarantinedPdfDoesNotPublishOrChangeTask() = cold(2)

    @Test fun lateCoverCannotPublishOrCompleteDuringQuarantine() = runBlocking {
        val output = before(); holdCover = true; val task = claim(); val work = scope.async { runner.run(task) }; waitForResponse()
        val state = appDb.bookTransferDao.get(task.id); quarantine(); release.countDown(); val error = failure(work)
        assertEquals(state, appDb.bookTransferDao.get(task.id)); unchanged(output); assertTrue(error is CancellationException)
    }

    @Test fun restoreRejectsLateEpubAndManualRetryProducesAReadableArchive() = runBlocking {
        val output = before(); holdCover = true; val task = claim(); val work = scope.async { runner.run(task) }; waitForResponse()
        restored(); release.countDown(); val error = failure(work)
        unchanged(output); assertTrue(error is CancellationException)
        assertEquals("interrupted", appDb.bookTransferDao.get(task.id)!!.phase)
        assertEquals("cancelled", appDb.bookTransferDao.batch(task.batchId!!)!!.phase)
        tasks.retry(task.id); val retry = appDb.bookTransferDao.get(task.id)!!; ownedBatches.add(retry.batchId!!)
        val batch = appDb.bookTransferDao.batch(retry.batchId)!!
        runner.run(tasks.claimNext(tasks.claimBatch(batch.id, batch.dispatch)!!)!!)
        ZipFile(output).use { assertEquals("application/epub+zip", it.getInputStream(it.getEntry("mimetype")).reader().readText()) }
        assertEquals("completed", appDb.bookTransferDao.get(task.id)!!.phase)
    }

    @Test fun exportWaitingBehindRestoreCannotPublish() = runBlocking {
        val output = before(0); val task = claim(0); var saved: Deferred<Unit>? = null; var work: Deferred<Unit>? = null
        backupRestoreMutex.recover {
            saved = scope.async(start = CoroutineStart.UNDISPATCHED) { restored() }
            work = scope.async(start = CoroutineStart.UNDISPATCHED) { runner.run(task) }
            delay(100); assertFalse(work!!.isCompleted)
        }
        saved!!.await(); assertTrue(failure(work!!) is CancellationException); unchanged(output)
    }

    @Test fun queuedExportDispatchCannotBeReclaimedAfterRestore() = runBlocking {
        val task = queued(0); val batch = appDb.bookTransferDao.batch(task.batchId!!)!!; restored()
        assertEquals("interrupted", appDb.bookTransferDao.get(task.id)!!.phase)
        assertNull(tasks.claimBatch(batch.id, batch.dispatch, allowSystemDownloadResume = true, allowProcessResume = true))
    }

    @Test fun liveRuntimeDoesNotCompleteExportRowsDuringQuarantine() = runBlocking {
        val output = before(); holdCover = true; val task = queued(); val batch = appDb.bookTransferDao.batch(task.batchId!!)!!
        val runtime = EntryPointAccessors.fromApplication(context.applicationContext, BookTransferEntryPoint::class.java).transferRuntime()
        val work = scope.async { runtime.execute(batch.id, batch.dispatch, true, { false }) }; waitForResponse()
        val oldTask = appDb.bookTransferDao.get(task.id); val oldBatch = appDb.bookTransferDao.batch(batch.id)
        quarantine(); release.countDown(); val error = failure(work)
        assertEquals(oldTask, appDb.bookTransferDao.get(task.id)); assertEquals(oldBatch, appDb.bookTransferDao.batch(batch.id))
        unchanged(output); assertTrue(error is CancellationException)
    }

    @Test fun invalidPreflightKeepsTheExistingExportValid() = runBlocking {
        val output = before(); holdCover = true; val task = claim(); val work = scope.async { runner.run(task) }; waitForResponse()
        assertTrue(runCatching { restore.restore(mapOf("bookshelf.json" to "[{")) }.isFailure)
        release.countDown(); withTimeout(15_000) { work.await() }
        ZipFile(output).use { assertNotNull(it.getEntry("mimetype")) }; assertEquals("completed", appDb.bookTransferDao.get(task.id)!!.phase)
    }

    @Test fun lateRestoreFailureRollsBackQueuedExportRetirement() = runBlocking {
        val task = queued(0); val batch = appDb.bookTransferDao.batch(task.batchId!!)!!; injectFailure()
        assertTrue(runCatching { restored() }.isFailure)
        assertEquals(task, appDb.bookTransferDao.get(task.id)); assertEquals(batch, appDb.bookTransferDao.batch(batch.id))
    }

    @Test fun runningExportAfterRollbackDoesNotRemainRunningOrPublish() = runBlocking {
        val output = before(); holdCover = true; val task = queued(); val batch = appDb.bookTransferDao.batch(task.batchId!!)!!
        val runtime = EntryPointAccessors.fromApplication(context.applicationContext, BookTransferEntryPoint::class.java).transferRuntime()
        val work = scope.async { runtime.execute(batch.id, batch.dispatch, true, { false }) }; waitForResponse()
        val oldTask = appDb.bookTransferDao.get(task.id); val oldBatch = appDb.bookTransferDao.batch(batch.id)
        injectFailure(); assertTrue(runCatching { restored() }.isFailure)
        assertEquals(oldTask, appDb.bookTransferDao.get(task.id)); assertEquals(oldBatch, appDb.bookTransferDao.batch(batch.id))
        release.countDown(); assertTrue(failure(work) is CancellationException)
        assertEquals("interrupted", appDb.bookTransferDao.get(task.id)!!.phase); assertEquals("interrupted", appDb.bookTransferDao.batch(batch.id)!!.phase)
        unchanged(output)
    }

    @Test fun restoreBeforeUploadDoesNotIssueTheOldPut() = runBlocking {
        val output = before(); holdCover = true; val task = claim(webDav = true); val work = scope.async { runner.run(task) }; waitForResponse()
        restored(); release.countDown(); val error = failure(work)
        assertEquals(0, putStarted.get()); unchanged(output); assertTrue(error is CancellationException)
    }

    @Test fun anAlreadyReceivedPutCanCommitAfterRestoreButCannotCompleteTheOldTask() = runBlocking {
        holdPut = true; val task = claim(0, true); val work = scope.async { runner.run(task) }; waitForResponse()
        val output = File(root, "owned.txt"); val bytes = output.readBytes(); restored(); release.countDown(); val error = failure(work)
        assertEquals(1, putStarted.get()); assertEquals(1, putCommitted.get()); assertArrayEquals(bytes, uploaded.get())
        assertArrayEquals(bytes, output.readBytes()); assertEquals("interrupted", appDb.bookTransferDao.get(task.id)!!.phase)
        assertTrue(error is CancellationException)
    }

    @Test fun anAlreadyReceivedPutDoesNotChangeTaskStateDuringQuarantine() = runBlocking {
        holdPut = true; val task = claim(0, true); val work = scope.async { runner.run(task) }; waitForResponse()
        val state = appDb.bookTransferDao.get(task.id); quarantine(); release.countDown(); val error = failure(work)
        assertEquals(1, putStarted.get()); assertEquals(1, putCommitted.get()); assertEquals(state, appDb.bookTransferDao.get(task.id))
        assertTrue(error is CancellationException)
    }
}
