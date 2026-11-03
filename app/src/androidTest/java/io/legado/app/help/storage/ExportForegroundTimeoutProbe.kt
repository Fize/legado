package io.legado.app.help.storage

import android.app.ActivityManager
import android.content.Intent
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.util.AtomicFile
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
import io.legado.app.service.ExportStagingFiles
import io.legado.app.utils.FileDoc
import io.legado.app.ui.AppActivity
import io.legado.app.ui.test.runCleanupSteps
import io.legado.app.utils.MD5Utils
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

/** Opt-in only: the host temporarily changes the OS dataSync time allowance. */
@RunWith(AndroidJUnit4::class)
class ExportForegroundTimeoutProbe {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    @Suppress("DEPRECATION")
    private fun service() = context.getSystemService(ActivityManager::class.java).getRunningServices(Int.MAX_VALUE)
        .singleOrNull { it.service.className == BookExportForegroundService::class.java.name }
    private suspend fun until(condition: () -> Boolean) = withTimeout(15_000) { while (!condition()) delay(25) }
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test fun cleanupOwnedFixtureAfterHostFailure(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("exportTimeout") == "yes")
        val id = checkNotNull(args.getString("caseId")).also { require(UUID.fromString(it).toString() == it) }
        val fixture = SafSidecarFixture(context, id)
        val attached = try { fixture.attach(); true } catch (missing: IllegalStateException) {
            if (!missing.message.orEmpty().contains("Unknown fixture")) throw missing
            false
        }
        val scheduler = EntryPointAccessors.fromApplication(context, BookTransferEntryPoint::class.java).transferScheduler()
        val url = "https://example.invalid/export-timeout/$id"
        val ids = appDb.openHelper.readableDatabase.query("SELECT id FROM book_transfer_tasks WHERE bookUrl=?", arrayOf(url)).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        scheduler.cancelTasks(ids)
        instrumentation.runOnMainSync { context.stopService(Intent(context, BookExportForegroundService::class.java)) }
        until { service() == null }
        if (attached) {
            fixture.release(); fixture.awaitClosed(); fixture.grant()
            val journal = exportJournal(context)
            journal.items().filter { it.directory.contains(id) }.forEach {
                journal.execute(journal.request(it.id, "restore", UUID.randomUUID().toString(), it.directory))
            }
            ExportStagingFiles(context, FileDoc.fromDir(fixture.tree), journal).use {
                backupRestoreMutex.withLock { it.recoverAbandoned() }
            }
            fixture.close()
        }
        // Only this UUID-owned test tree is being disposed. No production directory
        // is expired or inferred finished from age by this test cleanup.
        File(context.noBackupFilesDir, "book-export-staging-v1").listFiles().orEmpty().filter { it.name.endsWith(".json") }.forEach {
            val record = JSONObject(it.readText())
            if (record.getString("directory").contains(id)) AtomicFile(it).delete()
        }
        val prepared = File(context.filesDir, "compat-export-timeout/$id.prepared.json")
        val oldBatch = prepared.takeIf { it.exists() }?.let { JSONObject(it.readText()).getString("old_batch") }
        val batches = ids.mapNotNull { appDb.bookTransferDao.get(it)?.batchId }.toSet() + listOfNotNull(oldBatch)
        ids.forEach(appDb.bookTransferDao::deleteTask)
        batches.forEach { batch ->
            check(appDb.bookTransferDao.batchTasks(batch).isEmpty())
            appDb.openHelper.writableDatabase.execSQL("DELETE FROM book_transfer_batches WHERE id=?", arrayOf(batch))
        }
        appDb.bookDao.getBook(url)?.let { BookHelp.clearCache(it); appDb.bookChapterDao.delByBook(url); appDb.bookDao.delete(it) }
        File(context.filesDir, "book-export-journal").listFiles().orEmpty().filter { it.name.endsWith(".json") }.forEach {
            if (JSONObject(it.readText()).getString("directory").contains(id)) AtomicFile(it).delete()
        }
        File(context.filesDir, "compat-export-timeout").listFiles().orEmpty().filter { it.name.startsWith("$id.") }.forEach { check(it.delete()) }
    }

    @Test fun actualSystemTimeoutAndExplicitRetry(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Use check_android_export_timeout.py", args.getString("exportTimeout") == "yes")
        require(Build.VERSION.SDK_INT >= 35)
        val id = checkNotNull(args.getString("caseId")).also { require(UUID.fromString(it).toString() == it) }
        val mode = checkNotNull(args.getString("mode"))
        require(mode in setOf("hold", "hold_read", "hold_main_create_before", "hold_main_create_after",
            "hold_image_create_before", "hold_image_create_after", "hold_after_backup", "hold_before_activation", "hold_after_activation"))
        val root = File(context.filesDir, "compat-export-timeout").apply { check(isDirectory || mkdirs()) }
        val resultFile = File(root, "$id.json"); check(!resultFile.exists())
        val preparedFile = File(root, "$id.prepared.json")
        val completing = args.getString("stage") == "complete"
        var detached = false
        val tasks = BookTransferRepository(appDb)
        val scheduler = EntryPointAccessors.fromApplication(context, BookTransferEntryPoint::class.java).transferScheduler()
        val fixture = SafSidecarFixture(context, id)
        val book = Book(bookUrl = "https://example.invalid/export-timeout/$id", origin = "https://example.invalid/export-timeout/$id/source",
            name = id, author = id, totalChapterNum = 1)
        val chapter = BookChapter(bookUrl = book.bookUrl, url = "https://example.invalid/export-timeout/$id/chapter", title = "Chapter", index = 0)
        val src = "https://example.invalid/export-timeout/$id/image.png"
        val image = "${id}_${id}/images/Chapter/1-${MD5Utils.md5Encode16(src)}.jpg"
        val bytes = ByteArray(131_089) { (it % 251).toByte() }
        val journal = exportJournal(context)
        val batches = linkedSetOf<String>()
        var taskId: String? = null
        var seeded = false
        var activity: ActivityScenario<AppActivity>? = null
        var result: JSONObject? = null
        fun task() = appDb.bookTransferDao.get(checkNotNull(taskId))!!
        fun batch() = appDb.bookTransferDao.batch(task().batchId!!)!!
        fun ownJournal() = journal.items().filter { it.directory.contains(id) }
        fun canonicalOld() {
            assertArrayEquals("OLD TXT".toByteArray(), fixture.files()["owned.txt"])
            assertArrayEquals("OLD IMAGE".toByteArray(), fixture.files()[image])
            assertArrayEquals(id.toByteArray(), fixture.files()["unrelated.txt"])
        }
        assertFalse(RestoreRecoveryGate.isQuarantined); assertFalse(RestoreFileRecovery.hasPending(context))
        if (!completing) {
            assertNull(service()); assertTrue(journal.items().isEmpty())
            assertTrue(appDb.bookTransferDao.active("export").isEmpty()); assertTrue(appDb.bookTransferDao.active("download").isEmpty())
            Log.i("LegadoExportTimeout", "BEGIN:$id:${Process.myPid()}")
        }
        try {
            if (!completing) {
            activity = ActivityScenario.launch(AppActivity::class.java)
            fixture.create()
            fixture.seed("owned.txt", "OLD TXT".toByteArray()); fixture.seed(image, "OLD IMAGE".toByteArray()); fixture.seed("unrelated.txt", id.toByteArray())
            assertNull(appDb.bookDao.getBook(book.bookUrl)); appDb.bookDao.insert(book); appDb.bookChapterDao.insert(chapter); seeded = true
            assertTrue(BookHelp.saveContent(BookSource(bookSourceUrl = book.origin), book, chapter, "Body\n<img src=\"$src\">"))
            BookHelp.getImage(book, src).apply { check(parentFile!!.isDirectory || parentFile!!.mkdirs()); writeBytes(bytes) }
            taskId = tasks.enqueue(listOf(CacheBookRef(book.bookUrl, transferBookKey(book))), "export",
                BookTransferConfig(CacheExportOptions(directory = fixture.tree.toString(), fileName = "'owned'", pictures = true, useReplace = false))).single()
            batches.add(task().batchId!!)
            fixture.arm(mode); scheduler.schedule(listOf(task().id)); fixture.awaitBlocked()
            val old = task(); val oldBatch = batch()
            assertEquals("running", old.phase); assertEquals("running", oldBatch.phase); assertTrue(old.owner.isNotEmpty())
            val running = checkNotNull(service()); assertTrue(running.foreground); assertEquals(Process.myPid(), running.pid)
            val services = shell("dumpsys activity services ${context.packageName}")
            File(root, "$id.services.txt").writeText(services)
            File(root, "$id.instrumented.txt").writeText(shell("dumpsys activity -p ${context.packageName}"))
            val token = Regex("ServiceRecord\\{([0-9a-f]+)[^\\n]*BookExportForegroundService").find(services)!!.groupValues[1]
            val blocked = fixture.files().mapValues { hash(it.value) }
            val prepared = JSONObject().put("id", id).put("pid", Process.myPid()).put("uid", Process.myUid()).put("task", old.id)
                .put("old_owner", old.owner).put("old_batch", oldBatch.id).put("old_dispatch", oldBatch.dispatch)
                .put("service_token", token).put("blocked", JSONObject(blocked))
            FileOutputStream(preparedFile).use { it.write(prepared.toString().toByteArray()); it.fd.sync() }
            detached = true
            return@runBlocking
            }
            val prepared = JSONObject(preparedFile.readText())
            assertEquals(id, prepared.getString("id")); assertEquals(prepared.getInt("pid"), Process.myPid())
            taskId = prepared.getString("task"); seeded = true; batches.add(prepared.getString("old_batch"))
            fixture.attach()
            val old = task()
            val oldBatchId = prepared.getString("old_batch"); val oldDispatch = prepared.getString("old_dispatch")
            val oldOwner = prepared.getString("old_owner"); val token = prepared.getString("service_token")
            val blocked = prepared.getJSONObject("blocked").let { value -> value.keys().asSequence().associateWith { value.getString(it) } }
            val backgroundAt = checkNotNull(args.getString("backgroundAt")).toLong()
            until { service() == null }
            val timeoutAt = SystemClock.elapsedRealtime()
            assertTrue("OS timeout must precede the provider's 20 second safety bound", timeoutAt - backgroundAt < 15_000)
            val active = fixture.snapshot()
            assertTrue("I/O must still be blocked when the service exits", active.getInt("descriptors") > 0 || active.getInt("activeCreates") > 0 || active.getInt("activeRenames") > 0)
            assertEquals(blocked, fixture.files().mapValues { hash(it.value) })
            fixture.release(); fixture.awaitClosed()
            until { task().phase == "interrupted" && batch().phase == "interrupted" }
            assertEquals("", task().owner); assertEquals("", batch().owner)
            // Actual foreground interaction resets the exhausted allowance. Do not
            // silently restart another dataSync service while still in background.
            activity = ActivityScenario.launch(AppActivity::class.java)
            val records = ownJournal()
            records.forEach {
                scheduler.scheduleRecovery(journal.request(it.id, "restore", UUID.randomUUID().toString(), it.directory))
                until { ownJournal().none { item -> item.id == it.id } && service() == null }
            }
            canonicalOld()
            tasks.retry(old.id); batches.add(task().batchId!!)
            val nextBatch = batch(); assertNotEquals(oldBatchId, nextBatch.id); assertNotEquals(oldDispatch, nextBatch.dispatch)
            fixture.arm("hold"); scheduler.schedule(listOf(old.id)); fixture.awaitBlocked()
            assertTrue(checkNotNull(service()).foreground); assertNotEquals(oldOwner, task().owner); canonicalOld()
            fixture.release(); fixture.awaitClosed()
            until { task().phase in setOf("completed", "failed") }
            assertEquals(task().error, "completed", task().phase)
            until { batch().phase == "completed" && service() == null }
            assertEquals(setOf("owned.txt", image, "unrelated.txt"), fixture.files().keys)
            assertArrayEquals(bytes, fixture.files()[image]); assertArrayEquals(id.toByteArray(), fixture.files()["unrelated.txt"])
            assertTrue(fixture.files()["owned.txt"]!!.toString(Charsets.UTF_8).contains("Body")); assertTrue(ownJournal().isEmpty())
            result = JSONObject().put("id", id).put("mode", mode).put("pid", Process.myPid()).put("provider_pid", fixture.providerPid)
                .put("service_token", token).put("service_foreground_before", true).put("service_gone_while_io_blocked", true)
                .put("background_elapsed_ms", backgroundAt).put("timeout_observed_elapsed_ms", timeoutAt)
                .put("old_task_interrupted", true).put("old_owner_cleared", true).put("original_preserved_before_retry", true)
                .put("new_batch", nextBatch.id).put("old_batch", oldBatchId).put("new_dispatch", nextBatch.dispatch).put("old_dispatch", oldDispatch)
                .put("manual_restore_records", records.size).put("foreground_return_before_retry", true).put("retry_completed", true)
                .put("service_stopped_after_retry", true).put("fragments_after_retry", 0).put("unrelated_preserved", true)
        } finally {
            if (!detached) runCleanupSteps(
                { fixture.release(); runBlocking { fixture.awaitClosed(); taskId?.let { scheduler.cancelTasks(listOf(it)) }
                    instrumentation.runOnMainSync { context.stopService(Intent(context, BookExportForegroundService::class.java)) }; until { service() == null } } },
                { activity?.close() },
                { if (seeded) { taskId?.let(appDb.bookTransferDao::deleteTask); batches.forEach { batch ->
                    check(appDb.bookTransferDao.batchTasks(batch).isEmpty()); appDb.openHelper.writableDatabase.execSQL("DELETE FROM book_transfer_batches WHERE id=?", arrayOf(batch)) }
                    BookHelp.clearCache(book); appDb.bookChapterDao.delByBook(book.bookUrl); appDb.bookDao.delete(book) } },
                { fixture.close() },
            )
        }
        FileOutputStream(resultFile).use { it.write(checkNotNull(result).toString().toByteArray()); it.fd.sync() }
        Log.i("LegadoExportTimeout", "END:$id:${Process.myPid()}")
    }
}
