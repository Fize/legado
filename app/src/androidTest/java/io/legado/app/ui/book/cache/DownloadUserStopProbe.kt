package io.legado.app.ui.book.cache

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.app.job.JobScheduler
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.AtomicFile
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.data.entities.*
import io.legado.app.data.entities.rule.ContentRule
import io.legado.app.help.book.BookHelp
import io.legado.app.service.BookTransferEntryPoint
import io.legado.app.service.BookTransferScheduler
import io.legado.app.ui.AppActivity
import io.legado.app.ui.navigation.CacheRoute
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Host HTTP and OS lifecycle events live outside the killed process. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 34)
class DownloadUserStopProbe {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val args = InstrumentationRegistry.getArguments()
    private val id get() = checkNotNull(args.getString("caseId")).also { require(UUID.fromString(it).toString() == it) }
    private val root get() = File(context.filesDir, "compat-download-user-stop").apply { check(isDirectory || mkdirs()) }
    private val stateFile get() = File(root, "$id.state.json")
    private val jobs get() = context.getSystemService(JobScheduler::class.java).forNamespace(BookTransferScheduler.NAMESPACE)
    private fun managers() = appDb.openHelper.readableDatabase.query("SELECT id FROM cache_manager_states").use { cursor ->
        buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
    }
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
    private fun save(file: File, value: JSONObject) {
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try { stream.write(value.toString(2).toByteArray()); atomic.finishWrite(stream) }
        catch (failure: Throwable) { atomic.failWrite(stream); throw failure }
    }
    private fun oldContent() = "Saved before user stop $id"
    private suspend fun until(timeout: Long = 20_000, condition: () -> Boolean) = withTimeout(timeout) {
        while (!condition()) delay(25)
    }
    private fun node(label: String): AccessibilityNodeInfo? {
        fun find(value: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (value == null) return null
            if (value.packageName?.toString() == context.packageName && value.isVisibleToUser && value.isEnabled &&
                (value.text?.toString() == label || value.contentDescription?.toString() == label)) return value
            repeat(value.childCount) { find(value.getChild(it))?.let { found -> return found } }
            return null
        }
        return find(instrumentation.uiAutomation.rootInActiveWindow)
    }
    private suspend fun click(label: String): Rect {
        until { node(label) != null }
        var target = checkNotNull(node(label))
        while (!target.isClickable && target.parent != null) target = target.parent
        assertTrue(target.isClickable); assertTrue(target.isEnabled)
        val bounds = Rect().also(target::getBoundsInScreen)
        assertFalse(bounds.isEmpty)
        assertTrue("Actual native action: $label", target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        return bounds
    }
    private fun task(state: JSONObject) = checkNotNull(appDb.bookTransferDao.get(state.getString("task")))
    private fun checkSaved(state: JSONObject, downloaded: Boolean) {
        val book = checkNotNull(appDb.bookDao.getBook(state.getString("url")))
        val chapters = appDb.bookChapterDao.getChapterList(book.bookUrl)
        assertEquals(2, chapters.size)
        assertEquals(oldContent(), BookHelp.getContent(book, chapters[0]))
        val body = BookHelp.getContent(book, chapters[1])
        if (downloaded) assertTrue(body.orEmpty(), body?.contains(state.optString("expected_body", "Fresh after user stop $id attempt=2")) == true)
        else assertNull("The killed request must not publish its late response", body)
    }
    private suspend fun open(state: JSONObject): ActivityScenario<AppActivity> {
        val before = managers()
        val prior = state.optJSONArray("managers") ?: JSONArray()
        val activity = ActivityScenario.launch<AppActivity>(AppActivity.startIntent(context, CacheRoute(state.getLong("group"))))
        until { (managers() - before).isNotEmpty() }
        val added = managers() - before
        assertEquals(1, added.size)
        added.forEach(prior::put); state.put("managers", prior); save(stateFile, state)
        until { node(context.getString(R.string.cache_download_book, "User stop $id")) != null }
        return activity
    }

    private suspend fun rebooted(state: JSONObject, stage: String) {
        require(args.getString("deathMode") == "reboot" && state.getString("death_mode") == "reboot")
        val before = state.getString("boot_before")
        val after = checkNotNull(args.getString("bootAfter"))
        require(UUID.fromString(before).toString() == before && UUID.fromString(after).toString() == after)
        assertNotEquals("A new PID is not proof of a device reboot", before, after)
        assertFalse(state.getBoolean("job_persisted"))
        assertTrue(jobs.allPendingJobs.none { it.extras.getString("batchId") == state.getString("batch") })
        val activity = open(state)
        try {
            until { task(state).phase == "interrupted" }
            assertEquals("", task(state).owner); assertEquals(state.getString("batch"), task(state).batchId)
            checkSaved(state, false)
            until { node(context.getString(R.string.retry)) != null }
            val result = JSONObject().put("id", id).put("boot_before", before).put("boot_after", after)
                .put("old_pid", state.getInt("pid")).put("pid", Process.myPid())
                .put("task", task(state).id).put("old_batch", state.getString("batch"))
                .put("old_job_absent", true).put("interrupted_owner_empty", true).put("saved_chapter_preserved", true)
            if (stage == "reboot-retry") {
                val bounds = click(context.getString(R.string.retry))
                try {
                    until(90_000) {
                        task(state).phase in listOf("completed", "failed") &&
                            task(state).batchId?.let { appDb.bookTransferDao.batch(it)?.phase } in listOf("completed", "failed")
                    }
                } catch (timeout: TimeoutCancellationException) {
                    val current=task(state); val batch=current.batchId?.let(appDb.bookTransferDao::batch)
                    val pending=JSONArray().apply {
                        jobs.allPendingJobs.forEach { job ->
                            put(JSONObject().put("id", job.id).put("batch", job.extras.getString("batchId"))
                                .put("dispatch", job.extras.getString("dispatch"))
                                .put("user_initiated", job.isUserInitiated))
                        }
                    }
                    save(File(root, "$id.reboot-retry-timeout.json"), JSONObject().put("id", id)
                        .put("task_phase", current.phase).put("task_owner", current.owner).put("task_process", current.process)
                        .put("task_batch", current.batchId).put("task_error", current.error)
                        .put("batch_phase", batch?.phase).put("batch_owner", batch?.owner).put("batch_process", batch?.process)
                        .put("batch_dispatch", batch?.dispatch).put("pending_jobs", pending)
                        .put("retry_button_bounds", bounds.toShortString()))
                    throw timeout
                }
                val current = task(state); assertEquals(current.error, "completed", current.phase)
                assertNotEquals(state.getString("batch"), current.batchId); assertNotEquals(state.getString("process"), current.process)
                val batch = appDb.bookTransferDao.batch(current.batchId!!)!!
                assertNotEquals(state.getString("dispatch"), batch.dispatch); assertEquals("", current.owner)
                checkSaved(state, true)
                result.put("new_batch", batch.id).put("new_dispatch", batch.dispatch).put("new_process", current.process)
                    .put("phase", current.phase).put("actual_retry_button", true).put("retry_button_bounds", bounds.toShortString())
                    .put("fresh_chapter_saved", true)
            }
            save(File(root, "$id.$stage.json"), result)
        } finally { activity.close() }
    }

    @Test fun exerciseOwnedDownload(): Unit = runBlocking {
        Assume.assumeTrue("Use check_android_download_user_stop.py", args.getString("downloadUserStop") == "yes")
        val stage = checkNotNull(args.getString("stage"))
        if (stage == "cleanup") {
            val ids = if (stateFile.exists()) {
                val state = JSONObject(stateFile.readText()); require(state.getString("id") == id)
                val url = state.getString("url")
                val own = appDb.openHelper.readableDatabase.query("SELECT id FROM book_transfer_tasks WHERE bookUrl=?", arrayOf(url)).use { cursor ->
                    buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
                }
                val batches = own.mapNotNull { appDb.bookTransferDao.get(it)?.batchId }.toSet() +
                    listOfNotNull(state.optString("batch").takeIf(String::isNotEmpty))
                val scheduler = EntryPointAccessors.fromApplication(context, BookTransferEntryPoint::class.java).transferScheduler()
                batches.forEach { batch -> appDb.bookTransferDao.batch(batch)?.let { scheduler.cancelBatch(batch, it.dispatch) } }
                own.forEach(appDb.bookTransferDao::deleteTask)
                batches.forEach { batch ->
                    assertTrue(appDb.bookTransferDao.batchTasks(batch).isEmpty())
                    appDb.openHelper.writableDatabase.execSQL("DELETE FROM book_transfer_batches WHERE id=?", arrayOf(batch))
                }
                appDb.bookDao.getBook(url)?.let { BookHelp.clearCache(it); appDb.bookChapterDao.delByBook(url); appDb.bookDao.delete(it) }
                appDb.bookSourceDao.getBookSource(state.getString("source"))?.let(appDb.bookSourceDao::delete)
                appDb.bookGroupDao.getByID(state.getLong("group"))?.let(appDb.bookGroupDao::delete)
                val baseline = state.getJSONArray("baseline_managers").let { a -> (0 until a.length()).map(a::getString).toSet() }
                val ownManagers = managers() - baseline
                ownManagers.forEach(appDb.bookTransferDao::deleteManager)
                assertTrue(own.all { appDb.bookTransferDao.get(it) == null })
                assertTrue(batches.none { batch -> jobs.allPendingJobs.any { it.extras.getString("batchId") == batch } })
                assertEquals(baseline, managers())
                own
            } else emptyList()
            save(File(root, "$id.cleanup.json"), JSONObject().put("id", id).put("owned_records_removed", true)
                .put("baseline_managers_preserved", true).put("removed_tasks", JSONArray(ids)))
            return@runBlocking
        }
        if (stage == "prepare") {
            check(!stateFile.exists())
            val port = args.getString("serverPort")!!.toInt().also { require(it in 1024..65535) }
            val endpoint = "http://127.0.0.1:$port/$id"
            val group = BookGroup((0..60).map { 1L shl it }.first { appDb.bookGroupDao.getByID(it) == null }, "User stop $id")
            val source = BookSource(endpoint, "User stop $id", ruleContent = ContentRule(content = "body@text"))
            val book = Book(bookUrl = "$endpoint/book", tocUrl = "$endpoint/toc", origin = endpoint,
                name = "User stop $id", author = id, group = group.groupId, totalChapterNum = 2, durChapterIndex = 1, canUpdate = false)
            val chapters = (0..1).map { BookChapter(bookUrl = book.bookUrl, url = "$endpoint/chapter/$it", title = "Chapter $it", index = it) }
            val state = JSONObject().put("id", id).put("url", book.bookUrl).put("source", endpoint).put("group", group.groupId)
                .put("baseline_managers", JSONArray(managers().toList())).put("managers", JSONArray())
            if (args.getString("deathMode") == "sigkill") state.put("death_mode", "sigkill")
                .put("expected_body", "Fresh after process death $id attempt=2")
            if (args.getString("deathMode") == "reboot") {
                val boot = checkNotNull(args.getString("bootBefore")).also { require(UUID.fromString(it).toString() == it) }
                state.put("death_mode", "reboot").put("boot_before", boot).put("expected_body", "Fresh after reboot $id attempt=2")
            }
            save(stateFile, state)
            appDb.bookGroupDao.insert(group); appDb.bookSourceDao.insert(source); appDb.bookDao.insert(book)
            appDb.bookChapterDao.insert(*chapters.toTypedArray()); assertTrue(BookHelp.saveContent(source, book, chapters[0], oldContent()))
            val activity = open(state)
            var detached = false
            try {
                click(context.getString(R.string.cache_download_book, book.name))
                click(context.getString(R.string.ok))
                until(30_000) {
                    appDb.bookTransferDao.lease(book.bookUrl, "download")?.let(appDb.bookTransferDao::get)?.phase == "running"
                }
                val current = appDb.bookTransferDao.get(appDb.bookTransferDao.lease(book.bookUrl, "download")!!)!!
                val batch = appDb.bookTransferDao.batch(current.batchId!!)!!
                val job = jobs.allPendingJobs.single { it.extras.getString("batchId") == batch.id }
                assertTrue(job.isUserInitiated); assertNotNull(job.requiredNetwork); assertTrue(current.owner.isNotEmpty())
                state.put("task", current.id).put("batch", batch.id).put("dispatch", batch.dispatch).put("owner", current.owner)
                    .put("process", current.process).put("pid", Process.myPid()).put("uid", Process.myUid())
                    .put("app_label", context.applicationInfo.loadLabel(context.packageManager).toString())
                    .put("job_persisted", job.isPersisted)
                    .put("job", job.id).put("user_initiated", job.isUserInitiated).put("wall_time_ms", System.currentTimeMillis())
                checkSaved(state, false); save(stateFile, state)
                File(root, "$id.instrumented.txt").writeText(shell("dumpsys activity -p ${context.packageName}"))
                detached = true // Host checks that instrumentation really detached and this PID remained live.
            } finally { if (!detached) activity.close() }
            return@runBlocking
        }
        require(stage in listOf("observe", "retry", "auto-running", "auto-complete", "reboot-observe", "reboot-retry"))
        val state = JSONObject(stateFile.readText()); require(state.getString("id") == id)
        if (stage.startsWith("reboot-")) {
            rebooted(state, stage)
            return@runBlocking
        }
        val manager = context.getSystemService(ActivityManager::class.java)
        val oldPid = state.getInt("pid")
        assertNotEquals(oldPid, Process.myPid())
        val exit = manager.getHistoricalProcessExitReasons(context.packageName, oldPid, 16).single { it.pid == oldPid }
        assertEquals(context.packageName, exit.processName); assertTrue(exit.timestamp >= state.getLong("wall_time_ms"))
        if (stage.startsWith("auto-")) {
            require(args.getString("deathMode") == "sigkill" && state.getString("death_mode") == "sigkill")
            assertEquals(ApplicationExitInfo.REASON_SIGNALED, exit.reason); assertEquals(9, exit.status)
            // Do not open CacheRoute or call recover/retry: the OS must already
            // have started this process and reclaimed the same batch itself.
            val current = task(state)
            val batch = appDb.bookTransferDao.batch(current.batchId!!)!!
            assertEquals(state.getString("batch"), batch.id); assertEquals(state.getString("dispatch"), batch.dispatch)
            assertNotEquals(state.getString("process"), current.process); assertEquals(current.process, batch.process)
            val result = JSONObject().put("id", id).put("old_pid", oldPid).put("pid", Process.myPid())
                .put("exit_reason", exit.reason).put("exit_status", exit.status).put("exit_time", exit.timestamp)
                .put("task", current.id).put("batch", batch.id).put("dispatch", batch.dispatch)
                .put("process", current.process).put("owner", current.owner).put("phase", current.phase)
                .put("no_ui_or_manual_recovery", true).put("saved_chapter_preserved", true)
            if (stage == "auto-running") {
                assertEquals("running", current.phase); assertEquals("running", batch.phase)
                assertTrue(current.owner.isNotEmpty()); assertNotEquals(state.getString("owner"), current.owner)
                val job = jobs.allPendingJobs.single { it.extras.getString("batchId") == batch.id }
                assertEquals(state.getInt("job"), job.id); assertTrue(job.isUserInitiated)
                checkSaved(state, false)
            } else {
                until(30_000) {
                    task(state).phase in listOf("completed", "failed") &&
                        appDb.bookTransferDao.batch(batch.id)?.phase in listOf("completed", "failed") &&
                        jobs.allPendingJobs.none { it.id == state.getInt("job") }
                }
                val done = task(state); assertEquals(done.error, "completed", done.phase); assertEquals("", done.owner)
                checkSaved(state, true)
                result.put("phase", done.phase).put("owner", done.owner).put("fresh_chapter_saved", true)
            }
            save(File(root, "$id.$stage.json"), result)
            return@runBlocking
        }
        assertEquals(ApplicationExitInfo.REASON_USER_REQUESTED, exit.reason)
        val activity = open(state)
        try {
            until { task(state).phase == "interrupted" }
            assertEquals("", task(state).owner); assertEquals(state.getString("batch"), task(state).batchId)
            checkSaved(state, false)
            until { node(context.getString(R.string.retry)) != null }
            val result = JSONObject().put("id", id).put("old_pid", oldPid).put("pid", Process.myPid())
                .put("exit_reason", exit.reason).put("exit_time", exit.timestamp).put("old_batch", state.getString("batch"))
                .put("task", task(state).id).put("interrupted_owner_empty", true).put("saved_chapter_preserved", true)
            if (stage == "retry") {
                val bounds = click(context.getString(R.string.retry))
                until(30_000) { task(state).phase in listOf("completed", "failed") }
                val current = task(state); assertEquals(current.error, "completed", current.phase)
                assertNotEquals(state.getString("batch"), current.batchId); assertNotEquals(state.getString("process"), current.process)
                val batch = appDb.bookTransferDao.batch(current.batchId!!)!!
                assertNotEquals(state.getString("dispatch"), batch.dispatch); assertEquals("", current.owner)
                checkSaved(state, true)
                result.put("new_batch", batch.id).put("new_dispatch", batch.dispatch).put("new_process", current.process)
                    .put("phase", current.phase).put("actual_retry_button", true).put("retry_button_bounds", bounds.toShortString())
                    .put("fresh_chapter_saved", true)
            }
            save(File(root, "$id.$stage.json"), result)
        } finally { activity.close() }
    }
}
