package io.legado.app.help.storage

import android.app.ActivityManager
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import io.legado.app.data.appDb
import io.legado.app.data.entities.*
import io.legado.app.data.preferences.CacheExportOptions
import io.legado.app.data.repository.*
import io.legado.app.help.book.BookHelp
import io.legado.app.service.BookExportForegroundService
import io.legado.app.service.BookTransferEntryPoint
import io.legado.app.ui.AppActivity
import io.legado.app.ui.test.runCleanupSteps
import io.legado.app.utils.MD5Utils
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.UUID

/** Real scheduler, Hilt service/runtime and app database; owned separate-UID SAF tree. */
@RunWith(AndroidJUnit4::class)
class ExportForegroundServiceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val id = UUID.randomUUID().toString()
    private val fixture = SafSidecarFixture(context, id)
    private val tasks = BookTransferRepository(appDb)
    private val scheduler get() = EntryPointAccessors.fromApplication(context, BookTransferEntryPoint::class.java).transferScheduler()
    private val book = Book(bookUrl = "https://example.invalid/export-service/$id", origin = "https://example.invalid/export-service/$id/source",
        name = id, author = id, totalChapterNum = 1)
    private val chapter = BookChapter(bookUrl = book.bookUrl, url = "https://example.invalid/export-service/$id/chapter", title = "Chapter", index = 0)
    private val src = "https://example.invalid/export-service/$id/image.png"
    private val image = "${book.name}_${book.author}/images/Chapter/1-${MD5Utils.md5Encode16(src)}.jpg"
    private val bytes = ByteArray(131_089) { (it % 251).toByte() }
    private val batches = linkedSetOf<String>()
    private var taskId: String? = null
    private var seeded = false
    private var scenario: ActivityScenario<AppActivity>? = null
    @Suppress("DEPRECATION")
    private fun service() = context.getSystemService(ActivityManager::class.java).getRunningServices(Int.MAX_VALUE)
        .singleOrNull { it.service.className == BookExportForegroundService::class.java.name }
    private suspend fun until(message: String, condition: () -> Boolean) {
        withTimeout(15_000) { while (!condition()) delay(25) }
        assertTrue(message, condition())
    }
    private fun task() = appDb.bookTransferDao.get(checkNotNull(taskId))!!
    private fun batch() = appDb.bookTransferDao.batch(checkNotNull(task().batchId))!!

    @Before fun setup() {
        assertNull("Never interfere with an existing export service", service())
        assertTrue(appDb.bookTransferDao.active("download").isEmpty())
        assertTrue(appDb.bookTransferDao.active("export").isEmpty())
        assertFalse(RestoreRecoveryGate.isQuarantined)
        assertFalse(RestoreFileRecovery.hasPending(context))
        assertTrue(exportJournal(context).items().isEmpty())
        // A visible resumed activity supplies the real foreground-service launch eligibility.
        scenario = ActivityScenario.launch(AppActivity::class.java)
        fixture.create()
        fixture.seed("owned.txt", "OLD TXT".toByteArray()); fixture.seed(image, "OLD IMAGE".toByteArray())
        fixture.seed("unrelated.txt", id.toByteArray())
        assertNull(appDb.bookDao.getBook(book.bookUrl))
        appDb.bookDao.insert(book); appDb.bookChapterDao.insert(chapter); seeded = true
        assertTrue(BookHelp.saveContent(BookSource(bookSourceUrl = book.origin), book, chapter, "Body\n<img src=\"$src\">"))
        BookHelp.getImage(book, src).apply { check(parentFile!!.isDirectory || parentFile!!.mkdirs()); writeBytes(bytes) }
    }

    @After fun cleanup() = runCleanupSteps(
        { runBlocking {
            taskId?.let { scheduler.cancelTasks(listOf(it)) }
            fixture.release()
            instrumentation.runOnMainSync { context.stopService(Intent(context, BookExportForegroundService::class.java)) }
            fixture.awaitClosed()
            until("Owned service stopped") { service() == null }
            taskId?.let { until("Owned batch released") { batch().owner.isEmpty() } }
        } },
        { scenario?.close() },
        { if (seeded) {
            taskId?.let(appDb.bookTransferDao::deleteTask)
            batches.forEach { batch ->
                check(appDb.bookTransferDao.batchTasks(batch).isEmpty())
                appDb.openHelper.writableDatabase.execSQL("DELETE FROM book_transfer_batches WHERE id=?", arrayOf(batch))
            }
            BookHelp.clearCache(book); appDb.bookChapterDao.delByBook(book.bookUrl); appDb.bookDao.delete(book)
        } },
        { assertTrue(exportJournal(context).items().isEmpty()); fixture.close() },
    )

    private suspend fun start(): BookTransferTask {
        fixture.arm("hold")
        taskId = tasks.enqueue(listOf(CacheBookRef(book.bookUrl, transferBookKey(book))), "export",
            BookTransferConfig(CacheExportOptions(directory = fixture.tree.toString(), fileName = "'owned'", pictures = true, useReplace = false))).single()
        batches.add(checkNotNull(task().batchId))
        scheduler.schedule(listOf(task().id))
        fixture.awaitBlocked()
        assertEquals("running", task().phase); assertEquals("running", batch().phase)
        assertTrue(task().owner.isNotEmpty()); assertTrue(batch().owner.isNotEmpty())
        val active = checkNotNull(service())
        assertTrue("Actual Android service must be foreground", active.foreground)
        assertEquals(android.os.Process.myPid(), active.pid)
        preserved()
        return task()
    }
    private fun preserved() {
        assertArrayEquals("OLD TXT".toByteArray(), fixture.files()["owned.txt"])
        assertArrayEquals("OLD IMAGE".toByteArray(), fixture.files()[image])
        assertArrayEquals(id.toByteArray(), fixture.files()["unrelated.txt"])
    }
    private suspend fun completed() {
        until("Task completed") { task().phase in setOf("completed", "failed") }
        assertEquals(task().error, "completed", task().phase)
        until("Batch completed and service stopped") { batch().phase == "completed" && service() == null }
        fixture.awaitClosed()
        assertEquals(setOf("owned.txt", image, "unrelated.txt"), fixture.files().keys)
        assertArrayEquals(bytes, fixture.files()[image])
        assertTrue(fixture.files()["owned.txt"]!!.toString(Charsets.UTF_8).contains("Body"))
        assertArrayEquals(id.toByteArray(), fixture.files()["unrelated.txt"])
        assertEquals("", task().owner); assertEquals("", batch().owner)
    }
    private suspend fun retry(old: BookTransferTask) {
        tasks.retry(old.id); batches.add(checkNotNull(task().batchId))
        assertNotEquals(old.batchId, task().batchId)
        fixture.arm("hold"); scheduler.schedule(listOf(old.id)); fixture.awaitBlocked()
        assertEquals("running", task().phase); assertNotEquals(old.owner, task().owner)
        assertTrue(checkNotNull(service()).foreground)
        preserved()
        fixture.release(); completed()
    }

    @Test fun schedulerStartsActualForegroundServiceAndFinishes() = runBlocking {
        start(); fixture.release(); completed()
    }
    @Test fun cancellationThroughSchedulerRetiresOwnerAndExplicitRetryCompletes() = runBlocking {
        val old = start()
        scheduler.cancelTasks(listOf(old.id))
        until("Cancelled task and batch") { task().phase == "cancelled" && batch().phase == "cancelled" }
        assertEquals("", task().owner)
        preserved(); fixture.release(); fixture.awaitClosed()
        until("Cancelled service stopped") { service() == null }
        preserved(); retry(old)
    }
    @Test fun actualServiceStopInterruptsTaskAndExplicitRetryCompletes() = runBlocking {
        val old = start()
        instrumentation.runOnMainSync {
            assertTrue(context.stopService(Intent(context, BookExportForegroundService::class.java)))
        }
        until("Service was destroyed") { service() == null }
        preserved()
        // Synchronous provider I/O may outlive service destruction. Let it return,
        // then require runtime cleanup before issuing a new user retry.
        fixture.release(); fixture.awaitClosed()
        until("Stopped execution interrupted") { task().phase == "interrupted" && batch().phase == "interrupted" }
        assertEquals("", task().owner); assertEquals("", batch().owner)
        preserved(); retry(old)
    }
}
