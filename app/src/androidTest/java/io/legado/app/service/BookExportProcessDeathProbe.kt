package io.legado.app.service

import android.os.Process
import android.os.SystemClock
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.result.contract.ActivityResultContracts
import androidx.test.core.app.ActivityScenario
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.AppDatabase
import io.legado.app.data.addLegadoMigrations
import io.legado.app.data.entities.Book
import io.legado.app.data.repository.BookExportJournal
import io.legado.app.utils.*
import io.legado.app.ui.AppActivity
import io.legado.app.ui.navigation.AboutRoute
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Only the host harness opts in. A normal connected test run must never kill its process. */
@RunWith(AndroidJUnit4::class)
class BookExportProcessDeathProbe {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val arguments get() = InstrumentationRegistry.getArguments()
    private val context get() = instrumentation.targetContext
    private fun option(name: String, default: String = "") = arguments.getString(name) ?: default
    private fun caseRoot(): File {
        assumeTrue("Run through tools/check_android_export_process_death.py", option("exportProcessDeath") == "yes")
        val id = option("exportCaseId")
        require(UUID.fromString(id).toString() == id)
        return File(context.filesDir, "compat-export-process/$id")
    }
    private fun database(root: File) = Room.databaseBuilder(context, AppDatabase::class.java,
        File(root, "publication.db").absolutePath).addLegadoMigrations().build()
    private fun bytes(kind: String) = "$kind:${option("exportCaseId")}"
    private fun safTree(): Uri? = option("exportTree").takeIf { it.isNotEmpty() }?.let { value ->
        val uri = Uri.parse(value)
        require(uri.authority == "com.android.externalstorage.documents")
        val prefix = "primary:Documents/legado-export-process-"
        val id = DocumentsContract.getTreeDocumentId(uri).removePrefix(prefix)
        require(UUID.fromString(id).toString() == id)
        require(DocumentsContract.getTreeDocumentId(uri) == prefix + id)
        uri
    }
    private fun output(root: File): FileDoc = safTree()?.let { tree ->
        checkNotNull(FileDoc.fromDir(tree).find(option("exportCaseId")))
    } ?: FileDoc.fromFile(File(root, "output"))
    private fun read(folder: FileDoc, name: String): String? = folder.find(name)?.readText()
    private fun write(folder: FileDoc, name: String, text: String) = folder.createFileIfNotExist(name).writeText(text)
    private fun outputs(root: File): JSONObject = JSONObject().apply {
        output(root).list().orEmpty().sortedBy { it.name }.forEach { file ->
            put(file.name, MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) })
        }
    }
    private fun save(root: File, name: String, value: JSONObject) {
        FileOutputStream(File(root, name)).use { stream ->
            stream.write(value.toString().toByteArray(Charsets.UTF_8))
            stream.fd.sync()
        }
    }
    private fun die(root: File, stage: String, phase: String, inTransaction: Boolean) {
        save(root, "marker.json", JSONObject().put("pid", Process.myPid()).put("stage", stage)
            .put("phase", phase).put("transaction_open", inTransaction).put("outputs", outputs(root)))
        Process.killProcess(Process.myPid())
        throw AssertionError("SIGKILL did not terminate the process")
    }

    @Test fun publishAndDieAtCheckpoint() {
        val root = caseRoot()
        check(!root.exists()) { "A process-death fixture cannot be reused" }
        check(root.mkdirs())
        val output = safTree()?.let { tree ->
            val parent = FileDoc.fromDir(tree)
            check(parent.find(option("exportCaseId")) == null)
            parent.createFolderIfNotExist(option("exportCaseId"))
        } ?: FileDoc.fromFile(File(root, "output").apply { check(mkdir()) })
        write(output, "unrelated.txt", bytes("UNRELATED"))
        if (option("exportOriginal", "yes") == "yes") write(output, "book.txt", bytes("ORIGINAL"))
        val staged = output.createFileIfNotExist(".staged.book.txt").apply { writeText(bytes("GENERATED")) }
        val phase = option("exportPhase")
        require(phase in setOf("prepared", "backed_up", "installed", "published"))
        val db = database(root)
        try {
            val journal = BookExportJournal(File(root, "journal"), "${Process.myPid()}-${UUID.randomUUID()}") { record ->
                if (record.phase == phase) die(root, "publication", phase, db.inTransaction())
            }
            journal.publish(output, staged, "book.txt", ".backup.book.txt", {}, { publish ->
                if (phase == "prepared") {
                    val record = File(root, "journal").listFiles()!!.single { it.name.endsWith(".json") }
                    assertEquals("prepared", JSONObject(record.readText()).getString("phase"))
                    die(root, "publication", phase, db.inTransaction())
                }
                db.runInTransaction<FileDoc> {
                    db.bookDao.insert(Book(bookUrl = "uncommitted-${option("exportCaseId")}", name = "Publication transaction row"))
                    publish()
                }
            })
            fail("The publication did not reach its kill checkpoint")
        } finally { db.close() }
    }

    private fun recover(killAgain: Boolean) {
        val root = caseRoot()
        val output = output(root)
        val expectedCommitted = option("exportPhase") == "published"
        fun checkRoomState(): Boolean {
            val db = database(root)
            return try {
                val present = db.bookDao.getBook("uncommitted-${option("exportCaseId")}") != null
                assertEquals("Room commit/rollback did not survive SIGKILL", expectedCommitted, present)
                present
            } finally { db.close() }
        }
        checkRoomState()
        val action = option("exportAction")
        require(action in setOf("restore", "keep"))
        val point = option("exportRecoveryPoint")
        val journal = BookExportJournal(File(root, "journal"), "${Process.myPid()}-${UUID.randomUUID()}") { record ->
            if (killAgain && record.phase == "recovering") {
                val reached = when (point) {
                    "generated_staged" -> output.find("book.txt") == null && read(output, ".staged.book.txt") == bytes("GENERATED")
                    "original_restored" -> read(output, "book.txt") == bytes("ORIGINAL")
                    "generated_installed" -> read(output, "book.txt") == bytes("GENERATED")
                    else -> error("Unknown recovery checkpoint")
                }
                if (reached) die(root, "recovery", point, false)
            }
        }
        val item = journal.items().single()
        assertFalse(item.corrupt)
        assertNull("An old process command resumed automatically", journal.next())
        when (option("exportExternal")) {
            "target" -> write(output, "book.txt", bytes("EXTERNAL"))
            "backup" -> write(output, ".backup.book.txt", bytes("EXTERNAL_BACKUP"))
            "" -> Unit
            else -> error("Unknown external modification fixture")
        }
        val before = outputs(root).toString()
        val ref = journal.request(item.id, action, UUID.randomUUID().toString(), output.toString())
        val failure = runCatching { runBlocking { journal.execute(ref) } }.exceptionOrNull()
        if (killAgain) throw AssertionError("Recovery did not reach its kill checkpoint", failure)
        val external = option("exportExternal").isNotEmpty()
        if (external) {
            assertNotNull("External modification was overwritten", failure)
            assertEquals(before, outputs(root).toString())
            assertEquals("failed", journal.items().single().phase)
        } else {
            failure?.let { throw AssertionError("Recovery failed", it) }
            val original = option("exportOriginal", "yes") == "yes"
            if (action == "restore" && !original) assertNull(output.find("book.txt"))
            else assertEquals(bytes(if (action == "restore") "ORIGINAL" else "GENERATED"), read(output, "book.txt"))
            assertNull(output.find(".backup.book.txt"))
            assertNull(output.find(".staged.book.txt"))
            assertTrue(journal.items().isEmpty())
        }
        assertEquals(bytes("UNRELATED"), read(output, "unrelated.txt"))
        val present = checkRoomState()
        save(root, "result.json", JSONObject().put("pid", Process.myPid()).put("room_row_absent", !present)
            .put("room_row_present", present)
            .put("external_failure_expected", external).put("outputs", outputs(root)).put("records", journal.items().size))
    }

    @Test fun recoverAndVerify() = recover(false)
    @Test fun recoverAndDieAgain() = recover(true)
    @Test fun cleanupFixture() {
        val root = caseRoot()
        safTree()?.let { tree -> FileDoc.fromDir(tree).find(option("exportCaseId"))?.let { folder ->
            folder.delete()
            assertNull(FileDoc.fromDir(tree).find(option("exportCaseId")))
        } }
        if (root.exists()) assertTrue("Cannot remove the owned test fixture", root.deleteRecursively())
    }

    private fun nativeClick(labels: Set<String>) {
        fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.text?.toString()?.lowercase() in labels && node.isEnabled) return node
            repeat(node.childCount) { index -> find(node.getChild(index))?.let { return it } }
            return null
        }
        val deadline = SystemClock.elapsedRealtime() + 20_000
        while (SystemClock.elapsedRealtime() < deadline) {
            var node = find(instrumentation.uiAutomation.rootInActiveWindow)
            if (node != null) {
                while (!node!!.isClickable && node.parent != null) node = node.parent
                assertTrue("Native directory picker button $labels", node!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                return
            }
            SystemClock.sleep(100)
        }
        fail("Directory picker button not found: $labels")
    }

    @Test fun prepareSafGrant() {
        val root = caseRoot()
        check(!root.exists()); check(root.mkdirs())
        acquireSafGrant(root)
    }

    @Test fun reacquireSafGrant() {
        val root = caseRoot()
        check(root.isDirectory)
        acquireSafGrant(root)
    }

    private fun acquireSafGrant(root: File) {
        val tree = checkNotNull(safTree())
        val granted = CountDownLatch(1)
        ActivityScenario.launch<AppActivity>(AppActivity.startIntent(context, AboutRoute)).use { scenario ->
            scenario.onActivity { activity ->
                activity.activityResultRegistry.register("export-process-grant", ActivityResultContracts.OpenDocumentTree()) { uri ->
                    if (uri == tree) {
                        context.contentResolver.takePersistableUriPermission(uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                        granted.countDown()
                    }
                }.launch(DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)))
            }
            nativeClick(setOf("use this folder", "使用此文件夹"))
            nativeClick(setOf("allow", "允许"))
            assertTrue("Real persisted tree grant was not received", granted.await(20, TimeUnit.SECONDS))
        }
        assertTrue(context.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission && it.isWritePermission })
        save(root, "grant.json", JSONObject().put("tree", tree.toString()).put("persisted", true))
    }

    @Test fun revokeSafGrantAndRejectRecovery() {
        val root = caseRoot()
        val tree = checkNotNull(safTree())
        val folder = output(root)
        val target = checkNotNull(folder.find("book.txt"))
        val before = outputs(root)
        context.contentResolver.releasePersistableUriPermission(tree,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        assertFalse("The owned persisted grant was not revoked", context.contentResolver.persistedUriPermissions.any { it.uri == tree })
        val denied = runCatching { context.contentResolver.openInputStream(target.uri)?.use { it.read() } }.exceptionOrNull()
        assertTrue("The provider still allowed reads after revocation: $denied", denied is SecurityException)
        val journal = BookExportJournal(File(root, "journal"), "${Process.myPid()}-${UUID.randomUUID()}")
        val item = journal.items().single()
        assertFalse(item.corrupt)
        val ref = journal.request(item.id, option("exportAction"), UUID.randomUUID().toString(), folder.toString())
        val failure = runCatching { runBlocking { journal.execute(ref) } }.exceptionOrNull()
        assertNotNull("Recovery unexpectedly succeeded without directory access", failure)
        assertEquals("failed", journal.items().single().phase)
        assertNull("An access failure must not automatically enqueue recovery again", journal.next())
        save(root, "revocation.json", JSONObject().put("pid", Process.myPid()).put("persisted", false)
            .put("read_denied", true).put("recovery_failed", true).put("records", journal.items().size)
            .put("before", before).put("failure_type", failure!!::class.java.name))
    }

    @Test fun cleanupSafGrant() {
        val root = caseRoot()
        val tree = checkNotNull(safTree())
        if (context.contentResolver.persistedUriPermissions.any { it.uri == tree })
            context.contentResolver.releasePersistableUriPermission(tree,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        assertFalse(context.contentResolver.persistedUriPermissions.any { it.uri == tree })
        if (root.exists()) assertTrue(root.deleteRecursively())
    }
}
