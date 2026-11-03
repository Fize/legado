package io.legado.app.help.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.repository.BookExportJournal
import io.legado.app.service.ExportStagingFiles
import io.legado.app.ui.test.runCleanupSteps
import io.legado.app.utils.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Model unbound durable intents; actual process-death creation windows use the separate probe. */
@RunWith(AndroidJUnit4::class)
class ExportStagingIntentTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val id = UUID.randomUUID().toString()
    private val root = File(context.cacheDir, "staging-intent-$id").apply { check(mkdir()) }
    private val records = File(root, "records")
    private val fixture = SafSidecarFixture(context, id)
    private val journal = BookExportJournal(File(root, "journal"), "intent-test")
    private lateinit var folder: FileDoc
    private val name = ".${UUID.randomUUID()}.pending.owned.txt"
    private fun registry() = ExportStagingFiles(context, folder, journal, records)
    private lateinit var record: File
    private lateinit var originalBinding: String
    @Before fun setup() {
        fixture.create(); folder = FileDoc.fromDir(fixture.tree)
        fixture.seed("owned.txt", "CANONICAL".toByteArray()); fixture.seed("unrelated.txt", "UNRELATED".toByteArray())
        lateinit var intent: JSONObject
        registry().use { owner ->
            owner.create(folder, name).writeText("APP TEMP")
            record = records.listFiles()!!.single { it.name.endsWith(".json") }; intent = JSONObject(record.readText())
            originalBinding = intent.getString("stagingUri")
        }
        assertFalse(record.exists()); assertFalse(fixture.files().containsKey(name))
        record.writeText(intent.put("stagingUri", JSONObject.NULL).put("process", UUID.randomUUID().toString()).put("pid", Int.MAX_VALUE).toString())
    }
    @After fun cleanup() = runCleanupSteps({ fixture.close() }, { check(root.deleteRecursively() || !root.exists()) })
    private fun originals() {
        assertArrayEquals("CANONICAL".toByteArray(), fixture.files()["owned.txt"])
        assertArrayEquals("UNRELATED".toByteArray(), fixture.files()["unrelated.txt"])
    }
    @Test fun intentWithoutAVisibleFileRetainsOwnershipOfAPossibleLateCreation() {
        registry().use { it.recoverAbandoned() }
        assertTrue(record.exists()); assertEquals(setOf("owned.txt", "unrelated.txt"), fixture.files().keys); originals()
    }
    @Test fun aBoundFileThatIsAlreadyGoneCanRetireItsOwnershipRecord() {
        record.writeText(JSONObject(record.readText()).put("stagingUri", originalBinding).toString())
        registry().use { it.recoverAbandoned() }
        assertFalse(record.exists()); assertEquals(setOf("owned.txt", "unrelated.txt"), fixture.files().keys); originals()
    }
    @Test fun aUniqueCreatedFileCanBeCollectedBeforeItsUriWasBound() {
        fixture.seed(name, "APP TEMP".toByteArray())
        registry().use { it.recoverAbandoned() }
        assertFalse(record.exists()); assertEquals(setOf("owned.txt", "unrelated.txt"), fixture.files().keys); originals()
    }
    @Test fun ambiguousUnboundDocumentsArePreservedForDiagnosis() {
        fixture.seed(name, "APP TEMP".toByteArray()); fixture.duplicate(name)
        assertEquals(2, fixture.snapshot().getStringArrayList("paths")!!.count { it == name })
        registry().use { assertNotNull(runCatching { it.recoverAbandoned() }.exceptionOrNull()) }
        assertEquals(2, fixture.snapshot().getStringArrayList("paths")!!.count { it == name })
        assertTrue(record.exists()); originals()
    }
    @Test fun anUnboundNameThatBecameADirectoryIsNeverRecursivelyDeleted() {
        fixture.seed("$name/child.txt", "KEEP CHILD".toByteArray())
        registry().use { assertNotNull(runCatching { it.recoverAbandoned() }.exceptionOrNull()) }
        assertTrue(record.exists()); assertArrayEquals("KEEP CHILD".toByteArray(), fixture.files()["$name/child.txt"]); originals()
    }
}
