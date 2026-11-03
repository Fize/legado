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
import io.legado.app.data.entities.rule.*
import io.legado.app.data.repository.*
import io.legado.app.help.book.BookHelp
import io.legado.app.service.BookDownloadTaskRunner
import io.legado.app.service.BookTransferEntryPoint
import io.legado.app.ui.test.runCleanupSteps
import io.legado.app.utils.defaultSharedPreferences
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class RestoreBookDownloadTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val id = UUID.randomUUID().toString()
    private val ownedTasks = linkedSetOf<String>()
    private val ownedBatches = linkedSetOf<String>()
    private val restore = NormalRestoreFixture(context, id, { ownedTasks }, { ownedBatches })
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val entered = CountDownLatch(1)
    private val release = CountDownLatch(1)
    private val requests = java.util.concurrent.CopyOnWriteArrayList<String>()
    private val tasks = BookTransferRepository(appDb)
    private val runner = BookDownloadTaskRunner(appDb, tasks, CacheResourceLeases())
    private val prefs = context.defaultSharedPreferences
    private val original = listOf(PreferKey.cronet, PreferKey.autoBackup).associateWith { prefs.all[it] }
    private val trigger = "compat_download_" + id.replace("-", "")
    private var ownsTrigger = false
    private var ownsJournal = false
    private var seeded = false
    private var server: NanoHTTPD? = null
    private lateinit var book: Book
    private lateinit var source: BookSource
    private lateinit var chapter: BookChapter
    @Volatile private var heldPath: String? = null
    @Volatile private var images = false
    private val png by lazy {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        try { ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() } }
        finally { bitmap.recycle() }
    }
    private val base get() = "http://127.0.0.1:${server!!.listeningPort}"

    @Before fun setup() {
        assertFalse(RestoreRecoveryGate.isQuarantined); assertFalse(RestoreFileRecovery.hasPending(context))
        // A real restore will retire all unfinished downloads. Never alter unrelated work.
        assertTrue(appDb.bookTransferDao.active("download").isEmpty())
        appDb.openHelper.readableDatabase.query("SELECT count(*) FROM book_transfer_batches WHERE kind='download' AND phase NOT IN ('completed','failed','cancelled')").use {
            assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
        }
        check(prefs.edit().putBoolean(PreferKey.cronet, false).putBoolean(PreferKey.autoBackup, false).commit())
        server = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response {
                requests.add(session.uri)
                if (session.uri == heldPath) {
                    entered.countDown()
                    if (!release.await(30, TimeUnit.SECONDS)) return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, "text/plain", "fixture timeout")
                }
                return when (session.uri) {
                    "/image.png" -> newFixedLengthResponse(Response.Status.OK, "image/png", png.inputStream(), png.size.toLong())
                    "/toc" -> newFixedLengthResponse("<a href='$base/body'>Chapter</a>")
                    "/info" -> newFixedLengthResponse("<h1>Info</h1><a href='$base/toc'>TOC</a>")
                    else -> newFixedLengthResponse("<body>Owned $id${if (images) "<img src='$base/image.png'>" else ""}</body>")
                }
            }
        }.apply { start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
        source = BookSource(bookSourceUrl = "$base/$id", bookSourceName = id,
            ruleContent = ContentRule(content = "body@html"), ruleBookInfo = BookInfoRule(name = "h1@text", tocUrl = "a@href"),
            ruleToc = TocRule(chapterList = "a", chapterName = "text", chapterUrl = "href"))
        book = Book(bookUrl = "$base/info#$id", origin = source.bookSourceUrl, name = id, author = id, tocUrl = "$base/toc", totalChapterNum = 1)
        chapter = BookChapter(bookUrl = book.bookUrl, url = "$base/body", title = id, index = 0)
        assertNull(appDb.bookDao.getBook(book.bookUrl)); assertNull(appDb.bookSourceDao.getBookSource(source.bookSourceUrl))
        appDb.bookSourceDao.insert(source); appDb.bookDao.insert(book); appDb.bookChapterDao.insert(chapter); seeded = true
    }

    @After fun cleanup() = runCleanupSteps(
        { release.countDown(); scope.cancel(); runBlocking { scope.coroutineContext[Job]!!.join() }; server?.stop() },
        { if (ownsTrigger) appDb.openHelper.writableDatabase.execSQL("DROP TRIGGER IF EXISTS $trigger") },
        { if (ownsJournal) runBlocking { backupRestoreMutex.recover {
            RestoreFileRecovery.recover(context, cleanup = true); RestoreRecoveryGate.onStartupReady()
        } } },
        { restore.cleanup() },
        { if (seeded) {
            BookHelp.clearCache(book)
            ownedTasks.forEach(appDb.bookTransferDao::deleteTask)
            ownedBatches.forEach { batch ->
                assertTrue(appDb.bookTransferDao.batchTasks(batch).isEmpty())
                appDb.openHelper.writableDatabase.execSQL("DELETE FROM book_transfer_batches WHERE id=?", arrayOf(batch))
            }
            appDb.bookChapterDao.delByBook(book.bookUrl); appDb.bookDao.delete(book); appDb.bookSourceDao.delete(source)
        } },
        { check(prefs.edit().apply { original.forEach { (key, value) ->
            if (value == null) remove(key) else putBoolean(key, value as Boolean)
        } }.commit()) },
    )

    private suspend fun queued(): BookTransferTask {
        val ids = tasks.enqueue(listOf(CacheBookRef(book.bookUrl, transferBookKey(book))), "download", BookTransferConfig(start = 0, end = 0, threads = 1))
        ownedTasks.addAll(ids)
        return appDb.bookTransferDao.get(ids.single())!!.also { ownedBatches.add(it.batchId!!) }
    }
    private suspend fun claim(): BookTransferTask {
        val task = queued(); val batch = appDb.bookTransferDao.batch(task.batchId!!)!!
        return tasks.claimNext(tasks.claimBatch(batch.id, batch.dispatch)!!)!!
    }
    private suspend fun restored() = restore.restore(mapOf("bookSource.json" to "[]"))
    private suspend fun quarantine() = backupRestoreMutex.recover {
        val journal = RestoreSettingsTransaction.prepare(context, UUID.randomUUID().toString(), false); ownsJournal = true
        check(journal.editDefault().putString("book-download-$id", "pending").commit()); RestoreRecoveryGate.requireRecovery()
    }
    private fun waitForResponse() = assertTrue(entered.await(15, TimeUnit.SECONDS))
    private suspend fun stopped(work: Deferred<Unit>) = withTimeout(15_000) {
        assertTrue(runCatching { work.await() }.exceptionOrNull() is CancellationException)
    }

    @Test fun quarantineRejectsTheRunnerWithoutChangingTaskOrCallingHttp() = runBlocking {
        val task = claim(); quarantine()
        val error = runCatching { runner.run(task) }.exceptionOrNull()
        assertEquals(task, appDb.bookTransferDao.get(task.id)); assertTrue(requests.isEmpty())
        assertTrue(error is CancellationException)
    }

    @Test fun lateBodyCannotPublishOrUpdateProgressDuringQuarantine() = runBlocking {
        heldPath = "/body"; val task = claim(); val work = scope.async { runner.run(task) }; waitForResponse()
        val before = appDb.bookTransferDao.get(task.id); quarantine(); release.countDown()
        val failure = runCatching { withTimeout(15_000) { work.await() } }.exceptionOrNull()
        assertEquals(before, appDb.bookTransferDao.get(task.id)); assertNull(BookHelp.getContent(book, chapter))
        assertTrue(failure is CancellationException)
    }

    @Test fun lateImageCannotPublishOrUpdateProgressDuringQuarantine() = runBlocking {
        images = true; heldPath = "/image.png"; val task = claim(); val work = scope.async { runner.run(task) }; waitForResponse()
        val before = appDb.bookTransferDao.get(task.id); val content = BookHelp.getContent(book, chapter)
        assertNotNull(content); quarantine(); release.countDown()
        val failure = runCatching { withTimeout(15_000) { work.await() } }.exceptionOrNull()
        assertEquals(before, appDb.bookTransferDao.get(task.id)); assertEquals(content, BookHelp.getContent(book, chapter))
        assertFalse(BookHelp.getImage(book, "$base/image.png").exists()); assertTrue(failure is CancellationException)
    }

    @Test fun successfulRestoreRejectsLateBodyWithUnchangedBookIdentityAndAllowsRetry() = runBlocking {
        heldPath = "/body"; val task = claim(); val work = scope.async { runner.run(task) }; waitForResponse()
        restored(); release.countDown(); stopped(work)
        assertNull(BookHelp.getContent(book, chapter)); assertEquals("interrupted", appDb.bookTransferDao.get(task.id)!!.phase)
        assertEquals("cancelled", appDb.bookTransferDao.batch(task.batchId!!)!!.phase)
        heldPath = null; tasks.retry(task.id)
        val retry = appDb.bookTransferDao.get(task.id)!!; ownedBatches.add(retry.batchId!!)
        val batch = appDb.bookTransferDao.batch(retry.batchId)!!
        runner.run(tasks.claimNext(tasks.claimBatch(batch.id, batch.dispatch)!!)!!)
        assertEquals("completed", appDb.bookTransferDao.get(task.id)!!.phase); assertTrue(BookHelp.getContent(book, chapter)!!.contains(id))
    }

    @Test fun successfulRestoreRejectsTheLateImageAndPreservesPublishedBody() = runBlocking {
        images = true; heldPath = "/image.png"; val task = claim(); val work = scope.async { runner.run(task) }; waitForResponse()
        val content = BookHelp.getContent(book, chapter); assertNotNull(content)
        restored(); release.countDown(); stopped(work)
        assertEquals(content, BookHelp.getContent(book, chapter)); assertFalse(BookHelp.getImage(book, "$base/image.png").exists())
    }

    @Test fun successfulRestoreRejectsTheLateDirectory() = runBlocking {
        appDb.bookChapterDao.delByBook(book.bookUrl); heldPath = "/toc"
        val task = claim(); val work = scope.async { runner.run(task) }; waitForResponse()
        restored(); release.countDown(); stopped(work)
        assertEquals(0, appDb.bookChapterDao.getChapterCount(book.bookUrl)); assertFalse(requests.contains("/body"))
    }

    @Test fun queuedBatchCannotBeAutomaticallyResumedAfterRestore() = runBlocking {
        val task = queued(); val batch = appDb.bookTransferDao.batch(task.batchId!!)!!
        restored()
        assertEquals("interrupted", appDb.bookTransferDao.get(task.id)!!.phase)
        assertNull(tasks.claimBatch(batch.id, batch.dispatch, allowSystemDownloadResume = true, allowProcessResume = true))
        assertTrue(requests.isEmpty())
    }

    @Test fun runningRuntimeDoesNotRequeueOrFinishRowsDuringQuarantine() = runBlocking {
        heldPath = "/body"; val task = queued(); val batch = appDb.bookTransferDao.batch(task.batchId!!)!!
        val runtime = EntryPointAccessors.fromApplication(context.applicationContext, BookTransferEntryPoint::class.java).transferRuntime()
        val work = scope.async { runtime.execute(batch.id, batch.dispatch, true, { true }) }; waitForResponse()
        val beforeTask = appDb.bookTransferDao.get(task.id); val beforeBatch = appDb.bookTransferDao.batch(batch.id)
        quarantine(); release.countDown()
        val failure = runCatching { withTimeout(15_000) { work.await() } }.exceptionOrNull()
        assertEquals(beforeTask, appDb.bookTransferDao.get(task.id)); assertEquals(beforeBatch, appDb.bookTransferDao.batch(batch.id))
        assertNull(BookHelp.getContent(book, chapter)); assertTrue(failure is CancellationException)
    }

    @Test fun aLateRestoreFailureRollsBackTaskAndBatchRetirement() = runBlocking {
        val task = queued(); val batch = appDb.bookTransferDao.batch(task.batchId!!)!!
        ownsTrigger = true
        appDb.openHelper.writableDatabase.execSQL("""CREATE TRIGGER $trigger BEFORE INSERT ON caches
            WHEN NEW.key = '${RestoreFileRecovery.COMMIT_KEY}' BEGIN SELECT RAISE(ABORT,'owned download restore failure'); END""")
        assertTrue(runCatching { restored() }.isFailure)
        assertEquals(task, appDb.bookTransferDao.get(task.id)); assertEquals(batch, appDb.bookTransferDao.batch(batch.id))
        assertFalse(RestoreRecoveryGate.isQuarantined); assertFalse(RestoreFileRecovery.hasPending(context))
    }

    @Test fun failedRestoreStopsTheOldResponseWithoutLeavingItsBatchRunning() = runBlocking {
        heldPath = "/body"; val task = queued(); val batch = appDb.bookTransferDao.batch(task.batchId!!)!!
        val runtime = EntryPointAccessors.fromApplication(context.applicationContext, BookTransferEntryPoint::class.java).transferRuntime()
        val work = scope.async { runtime.execute(batch.id, batch.dispatch, true, { true }) }; waitForResponse()
        val beforeTask = appDb.bookTransferDao.get(task.id); val beforeBatch = appDb.bookTransferDao.batch(batch.id)
        ownsTrigger = true
        appDb.openHelper.writableDatabase.execSQL("""CREATE TRIGGER $trigger BEFORE INSERT ON caches
            WHEN NEW.key = '${RestoreFileRecovery.COMMIT_KEY}' BEGIN SELECT RAISE(ABORT,'owned running download restore failure'); END""")
        assertTrue(runCatching { restored() }.isFailure)
        assertEquals(beforeTask, appDb.bookTransferDao.get(task.id)); assertEquals(beforeBatch, appDb.bookTransferDao.batch(batch.id))
        release.countDown(); stopped(work)
        assertEquals("interrupted", appDb.bookTransferDao.get(task.id)!!.phase)
        assertEquals("interrupted", appDb.bookTransferDao.batch(batch.id)!!.phase)
        assertNull(BookHelp.getContent(book, chapter)); assertFalse(RestoreRecoveryGate.isQuarantined)
    }

    @Test fun invalidPreflightPreservesTheExistingDownload() = runBlocking {
        heldPath = "/body"; val task = claim(); val work = scope.async { runner.run(task) }; waitForResponse()
        assertTrue(runCatching { restore.restore(mapOf("bookshelf.json" to "[{")) }.isFailure)
        release.countDown(); withTimeout(15_000) { work.await() }
        assertEquals("completed", appDb.bookTransferDao.get(task.id)!!.phase); assertTrue(BookHelp.getContent(book, chapter)!!.contains(id))
    }
}
