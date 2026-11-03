package io.legado.app.ui.book.cache

import android.app.Notification
import android.app.NotificationManager
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.AtomicFile
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dagger.hilt.android.EntryPointAccessors
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookGroup
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.rule.ContentRule
import io.legado.app.help.book.BookHelp
import io.legado.app.service.BookTransferEntryPoint
import io.legado.app.service.BookTransferScheduler
import io.legado.app.ui.AppActivity
import io.legado.app.ui.navigation.CacheRoute
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Segmented probe for the pre-34 WorkManager contract. Cancellation is
 * performed by the production notification action, then the host performs a
 * real kernel reboot and invokes the observe stage in a new process.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 26, maxSdkVersion = 33)
class LegacyDownloadCancelRebootProbe {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val args = InstrumentationRegistry.getArguments()
    private val id get() = checkNotNull(args.getString("caseId")).also {
        require(UUID.fromString(it).toString() == it)
    }
    private val root get() = File(context.filesDir, "compat-legacy-download-cancel-reboot")
        .apply { check(isDirectory || mkdirs()) }
    private val stateFile get() = File(root, "$id.state.json")
    private val manager get() = WorkManager.getInstance(context)

    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)
    ).bufferedReader().use { it.readText() }

    private fun save(file: File, value: JSONObject) {
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            stream.write(value.toString(2).toByteArray())
            atomic.finishWrite(stream)
        } catch (failure: Throwable) {
            atomic.failWrite(stream)
            throw failure
        }
    }

    private suspend fun until(timeout: Long = 20_000, condition: () -> Boolean) =
        withTimeout(timeout) {
            while (!condition()) delay(25)
        }

    private fun task(state: JSONObject) =
        checkNotNull(appDb.bookTransferDao.get(state.getString("task")))

    private fun work(batch: String) =
        manager.getWorkInfosForUniqueWork(BookTransferScheduler.workName(batch))
            .get(15, TimeUnit.SECONDS).lastOrNull()

    private fun managers() = appDb.openHelper.readableDatabase
        .query("SELECT id FROM cache_manager_states").use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }

    private fun content(state: JSONObject, index: Int): String? {
        val book = checkNotNull(appDb.bookDao.getBook(state.getString("url")))
        return BookHelp.getContent(book, checkNotNull(appDb.bookChapterDao.getChapter(book.bookUrl, index)))
    }

    private fun open(state: JSONObject): ActivityScenario<AppActivity> {
        val activity = ActivityScenario.launch<AppActivity>(
            AppActivity.startIntent(context, CacheRoute(state.getLong("group")))
        )
        compose.waitUntil(20_000) {
            compose.onAllNodesWithTag("cacheBook:${state.getString("url")}")
                .fetchSemanticsNodes().isNotEmpty()
        }
        return activity
    }

    private fun prepare(state: JSONObject) = runBlocking {
        check(!stateFile.exists())
        val port = args.getString("serverPort")!!.toInt().also { require(it in 1024..65535) }
        val endpoint = "http://127.0.0.1:$port/$id"
        val group = BookGroup(
            (0..60).map { 1L shl it }.first { appDb.bookGroupDao.getByID(it) == null },
            "Legacy cancelled reboot $id"
        )
        val source = BookSource(
            endpoint,
            "Legacy cancelled reboot $id",
            ruleContent = ContentRule(content = "body@text")
        )
        val book = Book(
            bookUrl = "$endpoint/book",
            tocUrl = "$endpoint/toc",
            origin = endpoint,
            name = "Legacy cancelled reboot $id",
            author = id,
            group = group.groupId,
            totalChapterNum = 2,
            durChapterIndex = 0,
            canUpdate = false
        )
        val chapters = (0..1).map {
            BookChapter(
                bookUrl = book.bookUrl,
                url = "$endpoint/chapter/$it",
                title = "Chapter $it",
                index = it
            )
        }
        state.put("id", id).put("url", book.bookUrl).put("source", endpoint)
            .put("group", group.groupId).put("baseline_managers", JSONArray(managers().toList()))
            .put("cached_content", "Preserved legacy cached chapter $id")
            .put("cancelled_at_wall_time_ms", System.currentTimeMillis())
            .put("uid", Process.myUid()).put("pid", Process.myPid())
        save(stateFile, state)
        appDb.bookGroupDao.insert(group)
        appDb.bookSourceDao.insert(source)
        appDb.bookDao.insert(book)
        appDb.bookChapterDao.insert(*chapters.toTypedArray())
        assertTrue(BookHelp.saveContent(source, book, chapters[0], state.getString("cached_content")))

        val activity = open(state)
        try {
            compose.onNodeWithTag("cacheDownloadMenu").performClick()
            compose.onNodeWithTag("cacheConfirm").performClick()
            compose.waitUntil(30_000) {
                appDb.bookTransferDao.active("download")
                    .any { transfer -> transfer.bookUrl == book.bookUrl && transfer.phase == "running" }
            }
            val running = appDb.bookTransferDao.active("download")
                .single { transfer -> transfer.bookUrl == book.bookUrl }
            val batch = checkNotNull(running.batchId)
            val batchRow = checkNotNull(appDb.bookTransferDao.batch(batch))
            compose.waitUntil(20_000) { work(batch)?.state == WorkInfo.State.RUNNING }
            val notificationManager = context.getSystemService(NotificationManager::class.java)
            val notificationId = (batch.hashCode() and Int.MAX_VALUE).coerceAtLeast(1)
            compose.waitUntil(15_000) {
                notificationManager.activeNotifications.any { it.id == notificationId }
            }
            val notification = notificationManager.activeNotifications
                .single { it.id == notificationId }.notification
            assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
            assertEquals(1, notification.actions.size)
            // WorkManager publishes its foreground notification before the
            // worker performs the uncached-chapter request. Keep the request
            // deterministically in flight before exercising the real action.
            delay(5_000)
            assertEquals("running", taskById(running.id).phase)
            assertEquals(WorkInfo.State.RUNNING, work(batch)?.state)
            notification.actions.single().actionIntent.send()
            until(20_000) {
                taskById(running.id).phase == "cancelled" && work(batch)?.state == WorkInfo.State.CANCELLED
            }
            val cancelled = taskById(running.id)
            assertEquals("", cancelled.owner)
            assertEquals(batch, cancelled.batchId)
            assertEquals(state.getString("cached_content"), content(state, 0))
            assertNull("The cancelled network response must not publish a chapter", content(state, 1))
            state.put("task", cancelled.id).put("batch", batch).put("dispatch", batchRow.dispatch)
                .put("owner", running.owner).put("process", running.process)
                .put("task_phase", cancelled.phase).put("task_owner", cancelled.owner)
                .put("work_state", work(batch)!!.state.name)
                .put("notification_cancel_action", true).put("cached_chapter_preserved", true)
                .put("uncached_chapter_absent_at_cancel", true)
            save(stateFile, state)
            File(root, "$id.instrumented.txt").writeText(
                shell("dumpsys activity -p ${context.packageName}")
            )
        } finally {
            activity.close()
        }
    }

    private fun taskById(taskId: String) =
        checkNotNull(appDb.bookTransferDao.get(taskId))

    private fun observe(state: JSONObject) = runBlocking {
        val before = state.getString("boot_before")
        val after = checkNotNull(args.getString("bootAfter"))
        require(UUID.fromString(before).toString() == before)
        require(UUID.fromString(after).toString() == after)
        assertNotEquals("A new PID is not proof of a kernel reboot", before, after)

        val activity = open(state)
        try {
            until {
                val current = task(state)
                current.phase == "cancelled" && current.owner.isEmpty()
            }
            val current = task(state)
            assertEquals(state.getString("batch"), current.batchId)
            assertEquals(state.getString("cached_content"), content(state, 0))
            assertNull("A reboot or UI opening must not start the cancelled transfer", content(state, 1))
            val observedWork = work(state.getString("batch"))?.state
            assertTrue(
                "Cancelled unique work must stay cancelled or be absent",
                observedWork == null || observedWork == WorkInfo.State.CANCELLED
            )
            val result = JSONObject()
                .put("id", id)
                .put("boot_before", before)
                .put("boot_after", after)
                .put("old_pid", state.getInt("pid"))
                .put("pid", Process.myPid())
                .put("task", current.id)
                .put("old_batch", state.getString("batch"))
                .put("task_phase", current.phase)
                .put("task_owner", current.owner)
                .put("work_state", observedWork?.name ?: "ABSENT")
                .put("cancelled_task_preserved", true)
                .put("cached_chapter_preserved", true)
                .put("uncached_chapter_absent", true)
                .put("execution_after_reboot_unobserved_by_probe", true)
            save(File(root, "$id.observed.json"), result)
        } finally {
            activity.close()
        }
    }

    private fun cleanup(state: JSONObject) = runBlocking {
        val url = state.getString("url")
        val own = appDb.openHelper.readableDatabase.query(
            "SELECT id FROM book_transfer_tasks WHERE bookUrl=?",
            arrayOf(url)
        ).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        val batches = own.mapNotNull { appDb.bookTransferDao.get(it)?.batchId }.toSet() +
            listOfNotNull(state.optString("batch").takeIf(String::isNotEmpty))
        val scheduler = EntryPointAccessors.fromApplication(
            context,
            BookTransferEntryPoint::class.java
        ).transferScheduler()
        batches.forEach { batch ->
            appDb.bookTransferDao.batch(batch)?.let { scheduler.cancelBatch(batch, it.dispatch) }
        }
        own.forEach(appDb.bookTransferDao::deleteTask)
        batches.forEach { batch ->
            assertTrue(appDb.bookTransferDao.batchTasks(batch).isEmpty())
            appDb.openHelper.writableDatabase.execSQL(
                "DELETE FROM book_transfer_batches WHERE id=?",
                arrayOf(batch)
            )
        }
        appDb.bookDao.getBook(url)?.let {
            BookHelp.clearCache(it)
            appDb.bookChapterDao.delByBook(url)
            appDb.bookDao.delete(it)
        }
        appDb.bookSourceDao.getBookSource(state.getString("source"))?.let(appDb.bookSourceDao::delete)
        appDb.bookGroupDao.getByID(state.getLong("group"))?.let(appDb.bookGroupDao::delete)
        val baseline = state.getJSONArray("baseline_managers").let { array ->
            (0 until array.length()).map(array::getString).toSet()
        }
        (managers() - baseline).forEach(appDb.bookTransferDao::deleteManager)
        assertEquals(baseline, managers())
        save(
            File(root, "$id.cleanup.json"),
            JSONObject().put("id", id)
                .put("owned_records_removed", own.all { appDb.bookTransferDao.get(it) == null })
                .put("baseline_managers_preserved", managers() == baseline)
                .put("removed_tasks", JSONArray(own))
        )
    }

    @Test fun exerciseCancelledWorkAcrossKernelReboot(): Unit = runBlocking {
        assumeTrue(
            "Use check_android_legacy_download_reboot.py",
            args.getString("legacyCancelReboot") == "yes"
        )
        val stage = checkNotNull(args.getString("stage"))
        if (stage == "prepare") {
            prepare(JSONObject().put("boot_before", args.getString("bootBefore")))
            return@runBlocking
        }
        val state = JSONObject(stateFile.readText())
        require(state.getString("id") == id)
        when (stage) {
            "observe" -> observe(state)
            "cleanup" -> cleanup(state)
            else -> error("Unknown stage $stage")
        }
    }
}
