package io.legado.app.help.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.repository.BookExportJournal
import io.legado.app.ui.test.runCleanupSteps
import io.legado.app.utils.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SafExportJournalDirectoryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val id = UUID.randomUUID().toString()
    private val fixture = SafSidecarFixture(context, id)
    private val records = File(context.cacheDir, "saf-image-journal-$id")
    private lateinit var folder: FileDoc
    private val nested = "Book/images/Chapter"
    private class InterruptedCheckpoint : Error("Owned interrupted publication checkpoint")
    @Before fun setup() {
        check(!records.exists()); fixture.create()
        fixture.seed("image.jpg", "UNRELATED ROOT FILE".toByteArray())
        fixture.seed("$nested/image.jpg", "ORIGINAL IMAGE".toByteArray())
        fixture.seed("$nested/stage.jpg", "NEW IMAGE".toByteArray())
        folder = FileDoc.fromDir(fixture.tree).createFolderIfNotExist("Book", "images", "Chapter")
    }
    @After fun cleanup() = runCleanupSteps({ fixture.close() }, { check(records.deleteRecursively() || !records.exists()) })

    @Test fun aPlainTreeUriStillResolvesToItsSelectedRoot() {
        val root = FileDoc.fromDir(fixture.tree)
        assertEquals(setOf("Book", "image.jpg"), root.list()!!.map { it.name }.toSet())
    }
    @Test fun serializingASubdirectoryDoesNotTurnItIntoTheTreeRoot() {
        val restored = FileDoc.fromDir(folder.toString())
        assertEquals(folder.uri, restored.uri); assertEquals("Chapter", restored.name)
        assertEquals(setOf("image.jpg", "stage.jpg"), restored.list()!!.map { it.name }.toSet())
    }
    private fun interrupted(phase: String): String {
        val journal = BookExportJournal(records, "old") { if (it.phase == phase) throw InterruptedCheckpoint() }
        val failure = runCatching { journal.publish(folder, folder.find("stage.jpg")!!, "image.jpg", "backup.jpg", {}, { it() }) }.exceptionOrNull()
        assertTrue(failure is InterruptedCheckpoint)
        return journal.items().single().id
    }
    private suspend fun recovered(phase: String, action: String) {
        val record = interrupted(phase); val next = BookExportJournal(records, "new")
        next.execute(next.request(record, action, UUID.randomUUID().toString(), folder.toString()))
        val files = fixture.files(); assertEquals(setOf("image.jpg", "$nested/image.jpg"), files.keys)
        assertArrayEquals("UNRELATED ROOT FILE".toByteArray(), files["image.jpg"])
        assertArrayEquals((if (action == "restore") "ORIGINAL IMAGE" else "NEW IMAGE").toByteArray(), files["$nested/image.jpg"])
        assertTrue(next.items().isEmpty())
    }
    @Test fun anInterruptedImageBackupCanRestoreInsideItsOriginalSubdirectory() = runBlocking { recovered("backed_up", "restore") }
    @Test fun anInterruptedImageBackupCanPublishInsideItsOriginalSubdirectory() = runBlocking { recovered("backed_up", "keep") }
    @Test fun anActivatedImageCanRestoreInsideItsOriginalSubdirectory() = runBlocking { recovered("installed", "restore") }
}
