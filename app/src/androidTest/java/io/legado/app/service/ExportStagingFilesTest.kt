package io.legado.app.service

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.repository.BookExportJournal
import io.legado.app.utils.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ExportStagingFilesTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.cacheDir, "export-staging-${UUID.randomUUID()}").apply { check(mkdir()) }
    private val output = File(root, "output").apply { check(mkdir()) }
    private val records = File(root, "records")
    private val journal = BookExportJournal(File(root, "journal"), "test")
    private val folder get() = FileDoc.fromFile(output)
    private fun registry(directory: FileDoc = folder, log: BookExportJournal = journal) = ExportStagingFiles(context, directory, log, records)
    private fun name() = ".${UUID.randomUUID()}.pending.owned.txt"
    private fun recordFile() = records.listFiles()!!.single { it.name.endsWith(".json") }
    @After fun cleanup() { check(root.deleteRecursively() || !root.exists()) }

    /** Model a durable older process; real SIGKILL is separately covered by the host probe. */
    private fun abandoned(): Pair<File, File> {
        lateinit var record: JSONObject; lateinit var path: File; lateinit var metadata: File
        registry().use { owner ->
            val stage = owner.create(folder, name()); stage.writeText("PARTIAL"); path = stage.asFile()!!
            metadata = recordFile(); record = JSONObject(metadata.readText())
        }
        assertFalse(path.exists()); assertFalse(metadata.exists())
        path.writeText("PARTIAL")
        metadata.writeText(record.put("process", UUID.randomUUID().toString()).put("pid", Int.MAX_VALUE).toString())
        return path to metadata
    }

    @Test fun anotherLiveSessionCannotDeleteAnActiveRender() {
        registry().use { owner ->
            val stage = owner.create(folder, name()); stage.writeText("ACTIVE")
            registry().use { it.recoverAbandoned(); assertEquals("ACTIVE", stage.readText()); assertTrue(recordFile().isFile) }
            assertEquals("ACTIVE", stage.readText())
        }
        assertTrue(output.list()!!.isEmpty()); assertTrue(records.list()!!.isEmpty())
    }
    @Test fun anAbandonedStageIsRemovedWithoutTouchingTheCanonicalOrUnrelatedFiles() {
        val (stage, metadata) = abandoned()
        val canonical = File(output, "owned.txt").apply { writeText("OLD") }
        val unrelated = File(output, "unrelated.txt").apply { writeText("KEEP") }
        registry().use { it.recoverAbandoned() }
        assertFalse(stage.exists()); assertFalse(metadata.exists()); assertEquals("OLD", canonical.readText()); assertEquals("KEEP", unrelated.readText())
    }
    @Test fun aDifferentSelectedRootCannotCleanAnotherDirectoriesStages() {
        val (stage, metadata) = abandoned()
        val other = File(root, "other").apply { check(mkdir()) }
        registry(FileDoc.fromFile(other)).use { it.recoverAbandoned() }
        assertEquals("PARTIAL", stage.readText()); assertTrue(metadata.exists())
    }
    @Test fun closingAfterPublicationCannotDeleteThePublishedCanonicalFile() {
        registry().use { owner ->
            val stage = owner.create(folder, name()); stage.writeText("NEW")
            File(output, "owned.txt").writeText("OLD")
            journal.publish(folder, stage, "owned.txt", ".backup.owned.txt", {}, { it() })
        }
        assertEquals("NEW", File(output, "owned.txt").readText()); assertEquals(listOf("owned.txt"), output.list()!!.toList())
        assertTrue(records.list()!!.isEmpty())
    }
    @Test fun aNativePathAliasDoesNotMakeItsOwnedFileLookLikeAnotherDocument() {
        val alias = File(root, "alias"); Files.createSymbolicLink(alias.toPath(), output.toPath())
        val selected = FileDoc.fromFile(alias)
        registry(selected).use { owner -> owner.create(selected, name()).writeText("PARTIAL THROUGH ALIAS") }
        assertTrue(output.list()!!.isEmpty()); assertTrue(records.list()!!.isEmpty()); assertTrue(alias.isDirectory)
    }
    @Test fun changedDocumentIdentityIsPreservedInsteadOfDeleted() {
        val (stage, metadata) = abandoned(); val record = JSONObject(metadata.readText())
        metadata.writeText(record.put("stagingUri", FileDoc.fromFile(File(root, "different.txt")).uri.toString()).toString())
        registry().use { assertNotNull(runCatching { it.recoverAbandoned() }.exceptionOrNull()) }
        assertEquals("PARTIAL", stage.readText()); assertTrue(metadata.exists())
    }
    @Test fun publicationJournalKeepsPriorityUntilTheUserRecoversItsFile() = runBlocking {
        class Interrupted : Error()
        val log = BookExportJournal(File(root, "journal"), "old") { if (it.phase == "backed_up") throw Interrupted() }
        registry(log = log).use { owner ->
            val stage = owner.create(folder, name()); stage.writeText("NEW"); File(output, "owned.txt").writeText("OLD")
            assertTrue(runCatching { log.publish(folder, stage, "owned.txt", ".backup.owned.txt", {}, { it() }) }.exceptionOrNull() is Interrupted)
        }
        assertTrue(recordFile().exists())
        registry(log = log).use { next ->
            next.recoverAbandoned(); assertTrue(recordFile().exists()); assertEquals(2, output.list()!!.size)
            val recovered = BookExportJournal(File(root, "journal"), "next"); val item = recovered.items().single()
            recovered.execute(recovered.request(item.id, "restore", UUID.randomUUID().toString(), output.path))
            next.recoverAbandoned()
        }
        assertEquals("OLD", File(output, "owned.txt").readText()); assertTrue(records.list()!!.isEmpty())
    }
}
