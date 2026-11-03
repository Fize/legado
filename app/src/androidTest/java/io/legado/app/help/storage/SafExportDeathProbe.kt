package io.legado.app.help.storage

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Intent
import android.os.Build
import android.os.Process
import android.os.SystemClock
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import io.legado.app.data.AppDatabase
import io.legado.app.data.addLegadoMigrations
import io.legado.app.data.appDb
import io.legado.app.data.cache.CacheResourceLeases
import io.legado.app.data.entities.*
import io.legado.app.data.preferences.CacheExportOptions
import io.legado.app.data.repository.*
import io.legado.app.help.book.BookHelp
import io.legado.app.service.BookExportTaskRunner
import io.legado.app.service.BookExportForegroundService
import io.legado.app.service.BookTransferEntryPoint
import io.legado.app.service.ExportStagingFiles
import io.legado.app.ui.AppActivity
import io.legado.app.ui.navigation.CacheRoute
import io.legado.app.utils.FileDoc
import io.legado.app.utils.MD5Utils
import kotlinx.coroutines.*
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

/** Opt-in host harness only. Normal connected tests must never kill the application. */
@RunWith(AndroidJUnit4::class)
class SafExportDeathProbe {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val args get() = InstrumentationRegistry.getArguments()
    private val serviceMode get() = args.getString("foregroundService") == "yes"
    private val termination get() = args.getString("termination") ?: "sigkill"
    private val scheduler get() = EntryPointAccessors.fromApplication(context, BookTransferEntryPoint::class.java).transferScheduler()
    @Suppress("DEPRECATION")
    private fun service() = context.getSystemService(ActivityManager::class.java).getRunningServices(Int.MAX_VALUE)
        .singleOrNull { it.service.className == BookExportForegroundService::class.java.name }
    private suspend fun until(condition: () -> Boolean) = withTimeout(15_000) { while (!condition()) delay(25) }
    private fun id(): String {
        assumeTrue("Use check_android_saf_export_death.py", args.getString("safExportDeath") == "yes")
        return checkNotNull(args.getString("caseId")).also { require(UUID.fromString(it).toString() == it) }
    }
    private fun root() = File(context.filesDir, "compat-saf-export-death/${id()}")
    private fun book() = Book(bookUrl = "https://example.invalid/saf-death/${id()}", origin = "https://example.invalid/saf-death/${id()}/source",
        name = id(), author = id(), totalChapterNum = 1)
    private fun chapter() = BookChapter(bookUrl = book().bookUrl, url = "https://example.invalid/saf-death/${id()}/chapter", title = "Chapter", index = 0)
    private fun src() = "https://example.invalid/saf-death/${id()}/image.png"
    private fun image() = "${id()}_${id()}/images/Chapter/1-${MD5Utils.md5Encode16(src())}.jpg"
    private fun bytes() = ByteArray(131_089) { (it % 251).toByte() }
    private fun database() = if (serviceMode) appDb else Room.databaseBuilder(context, AppDatabase::class.java,
        File(root(), "tasks.db").absolutePath).addLegadoMigrations().build()
    private fun closeDatabase(db: AppDatabase) { if (!serviceMode) db.close() }
    private fun managerIds() = appDb.openHelper.readableDatabase.query("SELECT id FROM cache_manager_states").use { cursor ->
        buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
    }
    /** Start through the real scheduler; an ordinary backup lock keeps the service
     * visible long enough to verify it before its runtime is allowed to claim work. */
    private suspend fun runService(task: String): JSONObject {
        val queued = appDb.bookTransferDao.get(task)!!
        val batch = appDb.bookTransferDao.batch(queued.batchId!!)!!
        assertEquals("queued", queued.phase)
        assertNull(service())
        val observed = backupRestoreMutex.withLock {
            scheduler.schedule(listOf(task))
            until { service()?.foreground == true }
            val active = service()!!
            assertEquals(Process.myPid(), active.pid)
            assertEquals("queued", appDb.bookTransferDao.get(task)!!.phase)
            JSONObject().put("pid", active.pid).put("foreground", active.foreground)
                .put("component", active.service.className).put("batch", batch.id).put("dispatch", batch.dispatch)
        }
        until { appDb.bookTransferDao.get(task)!!.phase in setOf("completed", "failed") }
        assertEquals(appDb.bookTransferDao.get(task)!!.error, "completed", appDb.bookTransferDao.get(task)!!.phase)
        until { appDb.bookTransferDao.batch(batch.id)!!.phase == "completed" && service() == null }
        return observed
    }
    private fun hash(value: ByteArray) = MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it) }
    private fun files(fixture: SafSidecarFixture) = JSONObject().apply {
        fixture.files().toSortedMap().forEach { (name, data) -> put(name, JSONObject().put("size", data.size).put("sha256", hash(data))) }
    }
    private fun save(name: String, value: JSONObject) {
        FileOutputStream(File(root(), name)).use { it.write(value.toString().toByteArray()); it.fd.sync() }
    }
    private fun metadata() = JSONObject(File(root(), "case.json").readText()).also { check(it.getString("id") == id()) }
    private fun ownItems() = exportJournal(context).items().filter { it.directory.contains(id()) }
    private fun durableRecords() = File(context.filesDir, "book-export-journal").listFiles().orEmpty()
        .filter { it.name.endsWith(".json") }.map { JSONObject(it.readText()) }.filter { it.optString("directory").contains(id()) }
    private fun stagingRecords() = File(context.noBackupFilesDir, "book-export-staging-v1").listFiles().orEmpty()
        .filter { it.name.endsWith(".json") }.map { JSONObject(it.readText()) }.filter { it.optString("directory").contains(id()) }
    private fun fragments(fixture: SafSidecarFixture) = JSONObject().apply {
        fixture.files().toSortedMap().filterKeys { it.substringAfterLast('/').contains(".pending.") || it.substringAfterLast('/').contains(".backup.") }
            .forEach { (name, bytes) -> put(name, JSONObject().put("size", bytes.size).put("sha256", hash(bytes))) }
    }
    private fun unrelated(fixture: SafSidecarFixture) = assertArrayEquals("UNRELATED:${id()}".toByteArray(), fixture.files()["unrelated.txt"])

    @Test fun exportAndKillWhileProviderIsBlocked(): Unit = runBlocking {
        require(termination in setOf("sigkill", "user-stop"))
        if (termination == "user-stop") require(serviceMode && Build.VERSION.SDK_INT >= 33)
        check(!root().exists()); check(root().mkdirs())
        check(!RestoreRecoveryGate.isQuarantined && !RestoreFileRecovery.hasPending(context))
        check(appDb.bookTransferDao.active("download").isEmpty() && appDb.bookTransferDao.active("export").isEmpty())
        check(exportJournal(context).items().isEmpty())
        assertNull(service())
        val activity = if (serviceMode) ActivityScenario.launch(AppActivity::class.java) else null
        val fixture = SafSidecarFixture(context, id()); fixture.create(persistable = true)
        save("case.json", JSONObject().put("id", id()).put("provider_pid", fixture.providerPid)
            .put("seeded", false).put("foreground_service", serviceMode))
        val input = book(); val chapter = chapter(); val db = database(); val tasks = BookTransferRepository(db)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            fixture.seed("owned.txt", "OLD TXT".toByteArray()); fixture.seed(image(), "OLD IMAGE".toByteArray())
            fixture.seed("unrelated.txt", "UNRELATED:${id()}".toByteArray())
            if (!serviceMode) { db.bookDao.insert(input); db.bookChapterDao.insert(chapter) }
            check(!File(BookHelp.cachePath, input.getFolderName()).exists())
            save("case.json", metadata().put("seeded", true))
            // Cache maintenance consults the application catalog after restart. Keep
            // the UUID-owned source book registered there. Service cases use the real app DB.
            assertNull(appDb.bookDao.getBook(input.bookUrl))
            appDb.runInTransaction { appDb.bookDao.insert(input); appDb.bookChapterDao.insert(chapter) }
            assertTrue(BookHelp.saveContent(BookSource(bookSourceUrl = input.origin), input, chapter, "Body\n<img src=\"${src()}\">"))
            BookHelp.getImage(input, src()).apply { check(parentFile!!.isDirectory || parentFile!!.mkdirs()); writeBytes(bytes()) }
            val config = BookTransferConfig(CacheExportOptions(directory = fixture.tree.toString(), fileName = "'owned'", pictures = true, useReplace = false))
            val taskId = tasks.enqueue(listOf(CacheBookRef(input.bookUrl, transferBookKey(input))), "export", config).single()
            val queued = db.bookTransferDao.get(taskId)!!; val batch = db.bookTransferDao.batch(queued.batchId!!)!!
            val claimed = if (serviceMode) queued else tasks.claimNext(tasks.claimBatch(batch.id, batch.dispatch)!!)!!
            val mode = checkNotNull(args.getString("mode"))
            require(mode in setOf("hold", "hold_read", "hold_after_backup", "hold_before_activation", "hold_after_activation",
                "hold_main_create_before", "hold_main_create_after", "hold_image_create_before", "hold_image_create_after"))
            save("case.json", metadata().put("task", claimed.id).put("batch", batch.id).put("dispatch", batch.dispatch)
                .put("owner", claimed.owner).put("process", claimed.process).put("mode", mode))
            fixture.arm(mode)
            val work = if (serviceMode) { scheduler.schedule(listOf(taskId)); null }
                else scope.async { BookExportTaskRunner(context, db, tasks, CacheResourceLeases()).run(claimed) }
            fixture.awaitBlocked()
            if (work != null) assertFalse(work.isCompleted)
            val execution = db.bookTransferDao.get(taskId)!!
            assertEquals("running", execution.phase); assertTrue(execution.owner.isNotEmpty())
            val serviceProof = if (serviceMode) {
                val active = checkNotNull(service()); assertTrue(active.foreground); assertEquals(Process.myPid(), active.pid)
                assertEquals("running", db.bookTransferDao.batch(batch.id)!!.phase)
                save("case.json", metadata().put("owner", execution.owner).put("process", execution.process))
                JSONObject().put("pid", active.pid).put("foreground", active.foreground).put("component", active.service.className)
                    .put("batch", batch.id).put("dispatch", batch.dispatch).put("task_owner", execution.owner)
            } else null
            val snapshot = fixture.snapshot(); unrelated(fixture)
            // The UI list deliberately hides an active publication; inspect its durable
            // record while the writer is blocked, then use the public recovery API after death.
            val records = durableRecords()
            val staging = stagingRecords(); val unbound = staging.filter { it.isNull("stagingUri") }
            var creationVisible: Boolean? = null
            if (mode.contains("_create_")) {
                assertTrue(records.isEmpty()); assertEquals(1, snapshot.getInt("activeCreates")); assertEquals(1, unbound.size)
                assertEquals(if (mode.endsWith("before")) "create_before" else "create_after", snapshot.getString("operation"))
                assertEquals(snapshot.getString("entered"), unbound.single().getString("name"))
                assertEquals(if (mode.startsWith("hold_main")) 1 else 2, staging.size)
                val path = (if (mode.startsWith("hold_main")) "" else image().substringBeforeLast('/') + "/") + unbound.single().getString("name")
                creationVisible = fixture.files().containsKey(path)
                assertEquals(mode.endsWith("after"), creationVisible)
                assertArrayEquals("OLD IMAGE".toByteArray(), fixture.files()[image()]); assertArrayEquals("OLD TXT".toByteArray(), fixture.files()["owned.txt"])
            } else if (mode in setOf("hold", "hold_read")) {
                assertTrue(records.isEmpty()); assertArrayEquals("OLD IMAGE".toByteArray(), fixture.files()[image()])
                assertEquals(if (mode == "hold") "write" else "read", snapshot.getString("operation"))
            } else {
                assertEquals(1, records.size); assertEquals(1, snapshot.getInt("activeRenames"))
                assertTrue(snapshot.getString("operation")!!.startsWith("rename_"))
                assertEquals(if (mode == "hold_after_backup") "prepared" else "backed_up", records.single().getString("phase"))
            }
            save("marker.json", JSONObject().put("id", id()).put("pid", Process.myPid())
                .put("provider_pid", fixture.providerPid).put("mode", mode).put("operation", snapshot.getString("operation"))
                .put("active_descriptors", snapshot.getInt("descriptors")).put("active_renames", snapshot.getInt("activeRenames"))
                .put("active_creates", snapshot.getInt("activeCreates")).put("staging_records", staging.size)
                .put("unbound_intents", unbound.size).put("creation_visible", creationVisible)
                .put("late_create", args.getString("lateCreate") == "yes")
                .put("foreground_service", serviceMode).put("service", serviceProof)
                .put("termination", termination).put("wall_time_ms", System.currentTimeMillis())
                .put("journal_records", records.size).put("persisted_grant", fixture.hasPersistedGrant())
                .put("kill_requested_elapsed_ms", SystemClock.elapsedRealtime()).put("journal_phase", records.singleOrNull()?.optString("phase"))
                .put("files", files(fixture)))
            if (termination == "user-stop") {
                // Only the explicit host harness may issue the platform stop-app
                // command, after it reads the durable marker and verifies this PID.
                check(File(root(), "marker.ready").createNewFile())
                delay(90_000)
                error("The host did not issue the requested system user stop")
            } else {
                Process.killProcess(Process.myPid())
                error("SIGKILL did not terminate the application")
            }
        } finally { fixture.release(); scope.cancel(); scope.coroutineContext[Job]!!.join(); activity?.close(); closeDatabase(db) }
    }

    @Test fun reopenRecoverAndRetryInANewProcess() = runBlocking {
        val meta = metadata(); val marker = JSONObject(File(root(), "marker.json").readText())
        assertEquals(serviceMode, meta.getBoolean("foreground_service"))
        assertNotEquals(marker.getInt("pid"), Process.myPid())
        assertEquals(termination, marker.getString("termination"))
        val exitInfo = if (termination == "user-stop") {
            require(Build.VERSION.SDK_INT >= 33 && serviceMode)
            val manager = context.getSystemService(ActivityManager::class.java)
            until { manager.getHistoricalProcessExitReasons(context.packageName, marker.getInt("pid"), 8).isNotEmpty() }
            val exit = manager.getHistoricalProcessExitReasons(context.packageName, marker.getInt("pid"), 8)
                .single { it.pid == marker.getInt("pid") }
            assertEquals(ApplicationExitInfo.REASON_USER_REQUESTED, exit.reason)
            assertEquals(context.packageName, exit.processName)
            assertTrue(exit.timestamp >= marker.getLong("wall_time_ms"))
            JSONObject().put("pid", exit.pid).put("reason", exit.reason).put("process", exit.processName)
                .put("timestamp", exit.timestamp).put("description", exit.description)
        } else null
        val fixture = SafSidecarFixture(context, id()); fixture.attach()
        assertEquals(meta.getInt("provider_pid"), fixture.providerPid)
        assertTrue("A real selected tree must remain authorized after death", fixture.hasPersistedGrant())
        val late = args.getString("lateCreate") == "yes"
        if (late) {
            require(meta.getString("mode") in setOf("hold_main_create_before", "hold_image_create_before"))
            assertEquals(1, fixture.snapshot().getInt("activeCreates")); fixture.allowNewRequests()
        } else { fixture.release(); fixture.awaitClosed() }
        val db = database(); val tasks = BookTransferRepository(db)
        var activity: ActivityScenario<AppActivity>? = null
        try {
            val task = db.bookTransferDao.get(meta.getString("task"))!!
            assertEquals("running", task.phase); assertEquals(meta.getString("owner"), task.owner)
            assertEquals(meta.getString("process"), task.process); assertNotEquals(BookTransferProcess.id, task.process)
            val before = files(fixture).toString()
            var cacheEntryRecovered = false
            if (serviceMode) {
                assertNull(service())
                // Real cache entry runs the production stale-process recovery policy.
                val priorManagers = managerIds()
                activity = ActivityScenario.launch(AppActivity.startIntent(context, CacheRoute()))
                until { db.bookTransferDao.get(task.id)!!.phase == "interrupted" }
                until { (managerIds() - priorManagers).size == 1 }
                save("case.json", metadata().put("cache_managers", JSONArray((managerIds() - priorManagers).toList())))
                assertEquals("", db.bookTransferDao.get(task.id)!!.owner)
                cacheEntryRecovered = true
            }
            assertNull(tasks.claimBatch(meta.getString("batch"), meta.getString("dispatch"), allowSystemDownloadResume = true, allowProcessResume = true))
            assertEquals(before, files(fixture).toString()); assertEquals("interrupted", db.bookTransferDao.get(task.id)!!.phase)
            val action = checkNotNull(args.getString("action")); require(action in setOf("restore", "keep"))
            val journal = exportJournal(context); val records = ownItems()
            assertEquals(marker.getInt("journal_records"), records.size)
            records.forEach {
                val command = journal.request(it.id, action, UUID.randomUUID().toString(), it.directory)
                if (serviceMode) {
                    scheduler.scheduleRecovery(command)
                    until { ownItems().none { item -> item.id == it.id } && service() == null }
                } else journal.execute(command)
            }
            assertTrue(ownItems().isEmpty()); unrelated(fixture)
            val imageBefore = if (records.isNotEmpty() && action == "keep") bytes() else "OLD IMAGE".toByteArray()
            assertArrayEquals(imageBefore, fixture.files()[image()]); assertArrayEquals("OLD TXT".toByteArray(), fixture.files()["owned.txt"])
            val abandoned = fragments(fixture)
            tasks.retry(task.id); val retry = db.bookTransferDao.get(task.id)!!; val batch = db.bookTransferDao.batch(retry.batchId!!)!!
            if (!late) fixture.arm("normal")
            val retryService = if (serviceMode) runService(task.id) else {
                val owned = tasks.claimBatch(batch.id, batch.dispatch)!!; val claimed = tasks.claimNext(owned)!!
                assertNotEquals(meta.getString("owner"), claimed.owner)
                BookExportTaskRunner(context, db, tasks, CacheResourceLeases()).run(claimed); tasks.finishBatch(owned)
                null
            }
            assertNotEquals(meta.getString("batch"), batch.id); assertNotEquals(meta.getString("dispatch"), batch.dispatch)
            if (!late) fixture.awaitClosed()
            assertEquals("completed", db.bookTransferDao.get(task.id)!!.phase); assertEquals("completed", db.bookTransferDao.batch(batch.id)!!.phase)
            assertArrayEquals(bytes(), fixture.files()[image()]); assertTrue(fixture.files()["owned.txt"]!!.toString(Charsets.UTF_8).contains("Body"))
            unrelated(fixture); assertTrue(ownItems().isEmpty())
            if (late) {
                withTimeout(5_000) { while (fixture.snapshot().getInt("descriptors") != 0) delay(25) }
                val providerStillActive = fixture.snapshot().getInt("activeCreates") == 1
                val intentRetained = stagingRecords().count { it.isNull("stagingUri") } == 1
                val canonicalBefore = fixture.files().filterKeys { !it.contains(".pending.") }
                    .mapValues { hash(it.value) }
                fixture.release(); fixture.awaitClosed()
                val lateFile = fragments(fixture)
                val lateTracked = stagingRecords().count { it.isNull("stagingUri") } == 1
                save("late-observation.json", JSONObject().put("provider_active_during_first_retry", providerStillActive)
                    .put("first_retry_completed", true).put("intent_retained", intentRetained).put("late_file_tracked", lateTracked)
                    .put("fragments_after_late_creation", lateFile).put("files", files(fixture)))
                assertTrue("Older create must remain pending until the first retry completes", providerStillActive)
                assertTrue("Absence must not discard a creation intent before a late provider effect", intentRetained && lateTracked)
                assertEquals(1, lateFile.length())
                assertEquals(canonicalBefore, fixture.files().filterKeys { !it.contains(".pending.") }.mapValues { hash(it.value) })
                val fresh = tasks.enqueue(listOf(CacheBookRef(book().bookUrl, transferBookKey(book()))), "export",
                    BookTransferConfig(CacheExportOptions(directory = fixture.tree.toString(), fileName = "'owned'", pictures = true, useReplace = false))).single()
                val queued = db.bookTransferDao.get(fresh)!!; val nextBatch = db.bookTransferDao.batch(queued.batchId!!)!!
                if (serviceMode) runService(fresh) else {
                    val nextOwned = tasks.claimBatch(nextBatch.id, nextBatch.dispatch)!!
                    BookExportTaskRunner(context, db, tasks, CacheResourceLeases()).run(tasks.claimNext(nextOwned)!!); tasks.finishBatch(nextOwned)
                }
                fixture.awaitClosed()
                assertEquals("completed", db.bookTransferDao.get(fresh)!!.phase)
                assertEquals(0, fragments(fixture).length()); assertTrue(stagingRecords().isEmpty()); unrelated(fixture)
                assertEquals(canonicalBefore, fixture.files().filterKeys { !it.contains(".pending.") }.mapValues { hash(it.value) })
            }
            save("result.json", JSONObject().put("id", id()).put("pid", Process.myPid()).put("provider_pid", fixture.providerPid)
                .put("mode", meta.getString("mode")).put("action", action).put("old_dispatch_rejected", true)
                .put("foreground_service", serviceMode).put("retry_service", retryService)
                .put("termination", termination).put("exit_info", exitInfo)
                .put("cache_entry_recovered", cacheEntryRecovered).put("service_stopped_after_retry", serviceMode && service() == null)
                .put("recovery_service_used", serviceMode && records.isNotEmpty())
                .put("recovered_elapsed_ms", SystemClock.elapsedRealtime()).put("persisted_grant", fixture.hasPersistedGrant())
                .put("journal_records_recovered", records.size).put("retry_completed", true).put("unrelated_preserved", true)
                .put("late_create", late).put("late_intent_retained", late).put("late_file_observed", late)
                .put("provider_active_during_first_retry", late).put("followup_export_completed", late)
                .put("fragments_before_retry", abandoned).put("fragments_after_retry", fragments(fixture)).put("files", files(fixture)))
        } finally { activity?.close(); closeDatabase(db) }
    }

    @Test fun cleanupOwnedFixture() = runBlocking {
        val directory = root()
        if (!directory.exists()) return@runBlocking
        val meta = metadata(); val fixture = SafSidecarFixture(context, id()); fixture.attach()
        assertEquals(serviceMode, meta.getBoolean("foreground_service"))
        fixture.release(); fixture.awaitClosed()
        if (serviceMode) {
            val ownTasks = appDb.openHelper.readableDatabase.query("SELECT id FROM book_transfer_tasks WHERE bookUrl=?", arrayOf(book().bookUrl)).use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
            }
            scheduler.cancelTasks(ownTasks)
            InstrumentationRegistry.getInstrumentation().runOnMainSync { context.stopService(Intent(context, BookExportForegroundService::class.java)) }
            until { service() == null }
            val ownedBatches = ownTasks.mapNotNull { appDb.bookTransferDao.get(it)?.batchId }.toSet() +
                listOf(meta.optString("batch")).filter { it.isNotBlank() }
            ownTasks.forEach(appDb.bookTransferDao::deleteTask)
            ownedBatches.forEach { batch ->
                assertTrue(appDb.bookTransferDao.batchTasks(batch).isEmpty())
                appDb.openHelper.writableDatabase.execSQL("DELETE FROM book_transfer_batches WHERE id=?", arrayOf(batch))
            }
            meta.optJSONArray("cache_managers")?.let { ids ->
                for (index in 0 until ids.length()) {
                    val manager = ids.getString(index)
                    require(UUID.fromString(manager).toString() == manager)
                    appDb.bookTransferDao.deleteManager(manager)
                }
            }
        }
        val journal = exportJournal(context)
        ownItems().forEach { journal.execute(journal.request(it.id, "restore", UUID.randomUUID().toString(), it.directory)) }
        assertTrue(ownItems().isEmpty())
        ExportStagingFiles(context, FileDoc.fromDir(fixture.tree), journal).use { staging ->
            backupRestoreMutex.withLock { staging.recoverAbandoned() }
        }
        fixture.close()
        if (meta.optBoolean("seeded")) {
            val input = book()
            appDb.bookDao.getBook(input.bookUrl)?.let { assertEquals(transferBookKey(input), transferBookKey(it)) }
            BookHelp.clearCache(input)
            appDb.bookChapterDao.delByBook(input.bookUrl); appDb.bookDao.delete(input)
        }
        assertTrue(directory.deleteRecursively())
    }
}
