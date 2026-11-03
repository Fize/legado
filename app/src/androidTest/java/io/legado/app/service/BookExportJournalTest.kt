package io.legado.app.service

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.*
import io.legado.app.data.entities.Book
import io.legado.app.data.repository.*
import io.legado.app.utils.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BookExportJournalTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private val root=File(context.cacheDir,"export-journal-${UUID.randomUUID()}").apply { mkdirs() }
    private val records=File(root,"records")
    private val output=File(root,"output").apply { mkdirs() }
    private val nonce=UUID.randomUUID().toString()
    private val filename="book.txt"
    private val staged=File(output,".$nonce.pending.$filename").apply { writeText("NEW OUTPUT") }
    private val backup=File(output,".$nonce.previous.$filename")
    private val target=File(output,filename)
    private class Killed: Error("Simulated process interruption")
    @After fun cleanup() { root.deleteRecursively() }
    private fun interrupted(phase: String="backed_up",original: Boolean=true): String {
        if(original) target.writeText("ORIGINAL")
        val journal=BookExportJournal(records,"previous") { if(it.phase==phase) throw Killed() }
        assertTrue(runCatching { journal.publish(FileDoc.fromFile(output),FileDoc.fromFile(staged),filename,backup.name,{}, { it() }) }.exceptionOrNull() is Killed)
        return journal.items().single().id
    }
    private suspend fun recover(journal: BookExportJournal,id: String,action: String) {
        val command=journal.request(id,action,UUID.randomUUID().toString(),output.path)
        journal.execute(command)
    }
    @Test fun successfulPublicationKeepsTheOriginalBackupUntilItsOwnershipTransactionReturns() {
        target.writeText("ORIGINAL"); val journal=BookExportJournal(records,"current")
        val result=journal.publish(FileDoc.fromFile(output),FileDoc.fromFile(staged),filename,backup.name,{}, { action ->
            val installed=action()
            assertEquals("ORIGINAL",backup.readText()); assertEquals("NEW OUTPUT",target.readText())
            installed
        })
        assertEquals("NEW OUTPUT",result.readText()); assertFalse(backup.exists()); assertFalse(staged.exists())
        assertTrue(journal.items().isEmpty()); assertEquals(listOf(filename),output.list()!!.toList())
    }
    @Test fun aNewProcessCanRestoreTheOriginalAfterTheBackupRename()=runBlocking {
        val id=interrupted(); assertFalse(target.exists()); assertEquals("ORIGINAL",backup.readText())
        val next=BookExportJournal(records,"next"); assertNull(next.next())
        recover(next,id,"restore")
        assertEquals("ORIGINAL",target.readText()); assertFalse(staged.exists()); assertFalse(backup.exists()); assertTrue(next.items().isEmpty())
    }
    @Test fun aNewProcessCanPublishTheGeneratedFileAfterTheBackupRename()=runBlocking {
        val id=interrupted(); val next=BookExportJournal(records,"next")
        recover(next,id,"keep")
        assertEquals("NEW OUTPUT",target.readText()); assertEquals(listOf(filename),output.list()!!.toList()); assertTrue(next.items().isEmpty())
    }
    @Test fun anActivatedOutputCanStillRestoreTheOriginalAfterInterruption()=runBlocking {
        val id=interrupted("installed"); assertEquals("NEW OUTPUT",target.readText()); assertEquals("ORIGINAL",backup.readText())
        val next=BookExportJournal(records,"next"); recover(next,id,"restore")
        assertEquals("ORIGINAL",target.readText()); assertEquals(listOf(filename),output.list()!!.toList()); assertTrue(next.items().isEmpty())
    }
    @Test fun keepingAnAlreadyActivatedOutputRemovesOnlyItsOwnBackup()=runBlocking {
        val id=interrupted("installed"); val other=File(output,"unrelated.txt").apply { writeText("UNRELATED") }
        val next=BookExportJournal(records,"next"); recover(next,id,"keep")
        assertEquals("NEW OUTPUT",target.readText()); assertEquals("UNRELATED",other.readText()); assertFalse(backup.exists()); assertTrue(next.items().isEmpty())
    }
    @Test fun recoveryDoesNotOverwriteAFileChangedByAnotherWriter()=runBlocking {
        val id=interrupted(); target.writeText("EXTERNAL CHANGE"); val next=BookExportJournal(records,"next")
        assertTrue(runCatching { recover(next,id,"keep") }.isFailure)
        assertEquals("EXTERNAL CHANGE",target.readText()); assertEquals("ORIGINAL",backup.readText()); assertEquals("NEW OUTPUT",staged.readText())
        assertEquals("failed",next.items().single().phase)
        assertTrue(runCatching { recover(next,id,"restore") }.isFailure); assertEquals("EXTERNAL CHANGE",target.readText())
    }
    @Test fun aChangedBackupIsPreservedAndCannotReplaceTheDestination()=runBlocking {
        val id=interrupted(); backup.writeText("EXTERNAL BACKUP"); val next=BookExportJournal(records,"next")
        assertTrue(runCatching { recover(next,id,"restore") }.isFailure)
        assertFalse(target.exists()); assertEquals("EXTERNAL BACKUP",backup.readText()); assertEquals("NEW OUTPUT",staged.readText())
    }
    @Test fun cancellingBeforeRecoveryStartsDoesNotMoveAnyFile()=runBlocking {
        val id=interrupted(); val next=BookExportJournal(records,"next")
        val command=next.request(id,"keep",UUID.randomUUID().toString(),output.path); next.cancel(command); next.execute(command)
        assertFalse(target.exists()); assertEquals("ORIGINAL",backup.readText()); assertEquals("NEW OUTPUT",staged.readText())
        assertEquals("cancelled",next.items().single().phase)
    }
    @Test fun anOldDispatchCannotRunOrCancelAReplacementRecoveryCommand()=runBlocking {
        val id=interrupted(); val next=BookExportJournal(records,"next")
        val old=next.request(id,"keep",UUID.randomUUID().toString(),output.path)
        val current=next.request(id,"restore",UUID.randomUUID().toString(),output.path)
        next.cancel(old); next.execute(old); assertTrue(next.pending(current)); assertFalse(target.exists())
        next.execute(current); assertEquals("ORIGINAL",target.readText()); assertTrue(next.items().isEmpty())
    }
    @Test fun oldProcessCommandsNeverStartAutomatically()=runBlocking {
        val id=interrupted(); val first=BookExportJournal(records,"first")
        val old=first.request(id,"keep",UUID.randomUUID().toString(),output.path)
        val next=BookExportJournal(records,"next"); assertNull(next.next()); next.execute(old)
        assertFalse(target.exists()); assertEquals("interrupted",next.items().single().phase)
        recover(next,id,"restore"); assertEquals("ORIGINAL",target.readText())
    }
    @Test fun recoveryCanBeInterruptedAgainAfterRestoringTheOriginalAndThenRetried()=runBlocking {
        val id=interrupted("installed")
        val first=BookExportJournal(records,"first") { if(it.phase=="recovering" && target.exists()) throw Killed() }
        assertTrue(runCatching { recover(first,id,"restore") }.exceptionOrNull() is Killed)
        val next=BookExportJournal(records,"next"); recover(next,id,"restore")
        assertEquals("ORIGINAL",target.readText()); assertEquals(listOf(filename),output.list()!!.toList()); assertTrue(next.items().isEmpty())
    }
    @Test fun anOutputWithoutAnOriginalCanBeDiscardedAfterActivation()=runBlocking {
        val id=interrupted("installed",false); val next=BookExportJournal(records,"next")
        recover(next,id,"restore"); assertTrue(output.list()!!.isEmpty()); assertTrue(next.items().isEmpty())
    }
    @Test fun publicationRollbackCannotRollBackTheIndependentRecoveryRecord()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).addLegadoMigrations().build()
        target.writeText("ORIGINAL"); val journal=BookExportJournal(records,"first")
        try {
            assertTrue(runCatching { journal.publish(FileDoc.fromFile(output),FileDoc.fromFile(staged),filename,backup.name,{}, { action ->
                db.runInTransaction<FileDoc> { db.bookDao.insert(Book(bookUrl="rollback",name="Rolled back")); action(); throw Killed() }
            }) }.exceptionOrNull() is Killed)
            assertNull(db.bookDao.getBook("rollback")); assertEquals("NEW OUTPUT",target.readText()); assertEquals("ORIGINAL",backup.readText())
            val next=BookExportJournal(records,"next"); recover(next,next.items().single().id,"restore")
            assertEquals("ORIGINAL",target.readText())
        } finally { db.close() }
    }
    @Test fun aDifferentDirectoryCannotRedirectRecoveryAndUnfinishedOutputBlocksAnotherPublication() {
        val id=interrupted(); val next=BookExportJournal(records,"next")
        assertTrue(runCatching { next.request(id,"keep",UUID.randomUUID().toString(),root.path) }.isFailure)
        assertTrue(runCatching { next.checkDestination(FileDoc.fromFile(output),filename) }.isFailure)
        next.checkDestination(FileDoc.fromFile(output),"another.txt")
        assertFalse(target.exists()); assertEquals("ORIGINAL",backup.readText())
    }
    @Test fun corruptRecordsArePreservedUntilExplicitlyDiscardedWithoutTouchingExternalFiles() {
        records.mkdirs(); val id=UUID.randomUUID().toString(); val record=File(records,"$id.json").apply { writeText("{broken") }
        val journal=BookExportJournal(records,"next")
        assertTrue(journal.items().single().corrupt); assertEquals("{broken",record.readText())
        assertTrue(runCatching { journal.checkDestination(FileDoc.fromFile(output),filename) }.isFailure)
        journal.forgetCorrupt(id)
        assertFalse(record.exists()); assertEquals("NEW OUTPUT",staged.readText()); assertTrue(journal.items().isEmpty())
    }
}
