package io.legado.app.data.repository

import androidx.room.Room
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.constant.AppConst
import io.legado.app.data.*
import io.legado.app.data.entities.HighlightRule
import io.legado.app.data.entities.HighlightRuleFile
import io.legado.app.utils.GSON
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class HighlightRuleManagerRepositoryTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private val db=Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).addLegadoMigrations().build()
    private val files=HighlightRuleFiles(context)
    private val repo=HighlightRuleManagerRepository(db,files)
    private val id=UUID.randomUUID().toString(); private val owner=UUID.randomUUID().toString()
    private val ownFiles=mutableSetOf<String>()
    private fun insert(name: String="Name",group: String?="Group"): HighlightRule {
        val row=HighlightRule(name=name,pattern=name,group=group,style="{\"extension\":7}")
        return row.copy(id=db.highlightRuleDao.insert(row).single())
    }
    private suspend fun rows()=withTimeout(10_000) { repo.observe().first() }
    private suspend fun open()=repo.open(id,owner,10)
    private suspend fun dialog(kind: String,source: String?="Group",refs: List<HighlightRuleRef>?=null,text: String="Renamed"): HighlightManagerDialog {
        val action=HighlightManagerDialog(kind=kind,source=source,refs=refs ?: rows().filter { it.group==source }.map { it.ref },text=text)
        repo.persist(id,owner,1,HighlightRuleManagerDraft(dialog=action)); return action
    }
    private suspend fun transfer(kind: String="export"): HighlightRuleTransfer {
        val result=repo.prepare(id,owner,1,rows().map { it.ref },kind)
        return result.draft.transfer!!.also { ownFiles+=it.id }
    }
    @After fun cleanup() { ownFiles.forEach(files::remove); db.close() }
    @Test fun sameIdMetadataUpdatesPublishImmutableRows()=runBlocking {
        val first=insert(); val seen=mutableListOf<List<HighlightRuleItem>>()
        val job=launch { repo.observe().take(2).toList(seen) }
        withTimeout(10_000) { while(seen.isEmpty()) delay(10) }
        db.highlightRuleDao.update(first.copy(name="Changed",group="Other",isEnabled=false))
        withTimeout(10_000) { job.join() }
        assertEquals("Changed",seen.last().single().name); assertFalse(seen.last().single().enabled)
        assertEquals("Other",seen.last().single().group); assertNotEquals(seen.first(),seen.last())
    }
    @Test fun selectionFilterAndRenameDraftRestoreWithANewOwner()=runBlocking {
        val row=insert(); open(); val action=HighlightManagerDialog(kind="rename",refs=rows().map { it.ref },source="Group",text="pending 😀")
        repo.persist(id,owner,1,HighlightRuleManagerDraft(filter="named",group="Group",selected=listOf(row.uuid),groupsOpen=true,dialog=action))
        val restored=repo.open(id,UUID.randomUUID().toString(),11)
        assertEquals("pending 😀",restored.draft.dialog!!.text); assertEquals(listOf(row.uuid),restored.draft.selected)
        assertEquals("named",restored.draft.filter); assertTrue(restored.draft.groupsOpen)
    }
    @Test fun oldClaimsAndOwnersCannotOverwriteTheNewManager()=runBlocking {
        open(); val replacement=UUID.randomUUID().toString(); repo.open(id,replacement,20)
        assertTrue(runCatching { repo.open(id,owner,15) }.isFailure)
        assertTrue(runCatching { repo.persist(id,owner,1,HighlightRuleManagerDraft()) }.isFailure)
        assertTrue(runCatching { repo.close(id,owner) }.isFailure)
        assertEquals(replacement,db.highlightRuleManagerDao.get(id)!!.owner)
    }
    @Test fun staleRevisionCannotReplaceTheLastSelection()=runBlocking {
        open(); repo.persist(id,owner,2,HighlightRuleManagerDraft(selected=listOf("last")))
        assertTrue(runCatching { repo.persist(id,owner,1,HighlightRuleManagerDraft()) }.isFailure)
        assertEquals(listOf("last"),repo.open(id,owner,11).draft.selected)
    }
    @Test fun toggleChangesOnlyTheSelectedFlagAndKeepsRawStyle()=runBlocking {
        val rule=insert(); open(); repo.act(id,owner,1,rows().map { it.ref },"disable")
        val saved=db.highlightRuleDao.findById(rule.id)!!
        assertFalse(saved.isEnabled); assertEquals(rule.pattern,saved.pattern); assertEquals(rule.style,saved.style)
    }
    @Test fun anOldToggleCannotOverwriteAnEditedRule()=runBlocking {
        val rule=insert(); open(); val refs=rows().map { it.ref }; db.highlightRuleDao.update(rule.copy(pattern="changed"))
        assertTrue(runCatching { repo.act(id,owner,1,refs,"disable") }.isFailure)
        assertTrue(db.highlightRuleDao.findById(rule.id)!!.isEnabled)
    }
    @Test fun batchChangesAreAtomicWhenOneRuleWasReplaced()=runBlocking {
        val first=insert(); val second=insert("Other"); open(); val refs=rows().map { it.ref }
        db.highlightRuleDao.update(second.copy(uuid=UUID.randomUUID().toString()))
        assertTrue(runCatching { repo.act(id,owner,1,refs,"disable") }.isFailure)
        assertTrue(db.highlightRuleDao.findById(first.id)!!.isEnabled)
    }
    @Test fun renamingAGroupUpdatesItsExactMembersAndClearsTheCommand()=runBlocking {
        val first=insert(); val other=insert("Other","Else"); open(); val action=dialog("rename")
        val result=repo.confirm(id,owner,2,action.id)
        assertEquals("Renamed",db.highlightRuleDao.findById(first.id)!!.group)
        assertEquals("Else",db.highlightRuleDao.findById(other.id)!!.group); assertNull(result.draft.dialog)
        assertTrue(runCatching { repo.confirm(id,owner,3,action.id) }.isFailure)
    }
    @Test fun anImportedGroupMemberInvalidatesAnOldDeleteConfirmation()=runBlocking {
        val first=insert(); open(); val action=dialog("deleteGroup"); val added=insert("Imported")
        assertTrue(runCatching { repo.confirm(id,owner,2,action.id) }.isFailure)
        assertNotNull(db.highlightRuleDao.findById(first.id)); assertNotNull(db.highlightRuleDao.findById(added.id))
    }
    @Test fun metadataChangesInvalidateAnOldRenameConfirmation()=runBlocking {
        val rule=insert(); open(); val action=dialog("rename"); db.highlightRuleDao.update(rule.copy(name="Imported"))
        assertTrue(runCatching { repo.confirm(id,owner,2,action.id) }.isFailure)
        assertEquals("Group",db.highlightRuleDao.findById(rule.id)!!.group)
    }
    @Test fun moveToUngroupedKeepsEveryRuleAndItsIdentity()=runBlocking {
        val rule=insert(); open(); val action=dialog("moveGroup"); repo.confirm(id,owner,2,action.id,null)
        assertNull(db.highlightRuleDao.findById(rule.id)!!.group); assertEquals(rule.uuid,db.highlightRuleDao.all.single().uuid)
    }
    @Test fun aRenamedMoveTargetDoesNotCreateAnOldGroupAgain()=runBlocking {
        insert(); val target=insert("Other","Target"); open(); val action=dialog("moveGroup")
        db.highlightRuleDao.update(target.copy(group="New target"))
        assertTrue(runCatching { repo.confirm(id,owner,2,action.id,"Target") }.isFailure)
        assertTrue(db.highlightRuleDao.all.none { it.group=="Target" })
    }
    @Test fun aDeletedSelectionIsNotReplacedWithANewSameIdRule()=runBlocking {
        val rule=insert(); open(); val action=dialog("delete",null,rows().map { it.ref })
        db.highlightRuleDao.update(rule.copy(uuid=UUID.randomUUID().toString()))
        assertTrue(runCatching { repo.confirm(id,owner,2,action.id) }.isFailure)
        assertEquals(1,db.highlightRuleDao.all.size)
    }
    @Test fun exportedPayloadIsDurableAndContainsTheExactCapturedRules()=runBlocking {
        val first=insert(); val second=insert("Other","Other"); open(); val transfer=transfer()
        db.highlightRuleDao.update(first.copy(name="Later"))
        val file=File(context.filesDir,"highlight-rule-transfers/${transfer.id}.json")
        val value=GSON.fromJson(file.readText(),HighlightRuleFile::class.java)
        assertEquals(HighlightRuleFile.TYPE,value.type); assertEquals(setOf(first.uuid,second.uuid),value.rules!!.map { it!!.uuid }.toSet())
        assertTrue(value.rules!!.any { it!!.name=="Name" })
        val restored=repo.open(id,UUID.randomUUID().toString(),11)
        assertEquals(transfer,restored.draft.transfer)
    }
    @Test fun aCancelledPickerHasNoSuccessReceiptAndRemovesItsOwnPayload()=runBlocking {
        insert(); open(); val transfer=transfer(); repo.transferPhase(id,owner,2,transfer.id,"awaiting")
        val result=repo.clearTransfer(id,owner,3,transfer.id)
        assertNull(result.draft.exportedUri); assertNull(result.draft.transfer)
        assertFalse(File(context.filesDir,"highlight-rule-transfers/${transfer.id}.json").exists())
    }
    @Test fun oldPickerResultsCannotConsumeAnotherTransfer()=runBlocking {
        insert(); open(); val transfer=transfer()
        assertTrue(runCatching { repo.transferPhase(id,owner,2,UUID.randomUUID().toString(),"awaiting") }.isFailure)
        assertEquals(transfer.id,repo.open(id,owner,11).draft.transfer!!.id)
    }
    @Test fun writingProducesTheCapturedJsonAndOnlyThenPublishesSuccess()=runBlocking {
        insert(); open(); val transfer=transfer(); repo.transferPhase(id,owner,2,transfer.id,"awaiting")
        val target=File(context.filesDir,"rule-output-${UUID.randomUUID()}.json")
        try {
            target.writeText("old content"); val uri=FileProvider.getUriForFile(context,AppConst.authority,target)
            repo.transferPhase(id,owner,3,transfer.id,"writing",uri.toString())
            val result=repo.write(id,owner,transfer.id)
            assertEquals(uri.toString(),result.draft.exportedUri); assertNull(result.draft.transfer)
            assertEquals("Name",GSON.fromJson(target.readText(),HighlightRuleFile::class.java).rules!!.single()!!.name)
        } finally { target.delete() }
    }
    @Test fun interruptedWritingRestoresTheSameUriAsAnExplicitRetry()=runBlocking {
        insert(); open(); val transfer=transfer(); repo.transferPhase(id,owner,2,transfer.id,"awaiting")
        repo.transferPhase(id,owner,3,transfer.id,"writing","content://fixture/output")
        val result=repo.open(id,UUID.randomUUID().toString(),11).draft.transfer!!
        assertEquals(transfer.id,result.id); assertEquals("failed",result.phase); assertEquals("content://fixture/output",result.uri)
    }
    @Test fun corruptedPayloadIsRejectedBeforeTouchingTheOutput()=runBlocking {
        insert(); open(); val transfer=transfer(); repo.transferPhase(id,owner,2,transfer.id,"awaiting")
        File(context.filesDir,"highlight-rule-transfers/${transfer.id}.json").writeText("corrupt")
        val target=File(context.filesDir,"rule-output-${UUID.randomUUID()}.json")
        try {
            target.writeText("untouched"); val uri=FileProvider.getUriForFile(context,AppConst.authority,target)
            repo.transferPhase(id,owner,3,transfer.id,"writing",uri.toString())
            assertTrue(runCatching { repo.write(id,owner,transfer.id) }.isFailure)
            assertEquals("untouched",target.readText())
        } finally { target.delete() }
    }
    @Test fun sharingUsesAReadableGrantedContentUriWithoutDeletingItOnReturn()=runBlocking {
        insert(); open(); val transfer=transfer("share"); val uri=repo.shareUri(transfer)
        assertEquals("content",uri.scheme)
        assertTrue(context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }.contains("Name"))
        repo.transferPhase(id,owner,2,transfer.id,"awaiting"); repo.clearTransfer(id,owner,3,transfer.id)
        assertTrue(File(context.filesDir,"highlight-rule-transfers/${transfer.id}.json").exists())
    }
    @Test fun closingTheManagerRejectsLateWritesAndRestoresAsClosed()=runBlocking {
        open(); repo.close(id,owner)
        assertTrue(runCatching { repo.persist(id,owner,1,HighlightRuleManagerDraft()) }.isFailure)
        assertTrue(repo.open(id,owner,11).closed)
    }
    @Test fun malformedKeysCannotEscapeThePrivatePayloadDirectory() {
        assertTrue(runCatching { files.remove("../outside") }.isFailure)
    }
    @Test fun anOwnedCloseCanClearACorruptDraftWithoutChangingRules()=runBlocking {
        val rule=insert(); open()
        db.highlightRuleManagerDao.update(db.highlightRuleManagerDao.get(id)!!.copy(state="not json"))
        val replacement=UUID.randomUUID().toString()
        assertTrue(runCatching { repo.open(id,replacement,11) }.isFailure)
        assertEquals(replacement,db.highlightRuleManagerDao.get(id)!!.owner)
        repo.close(id,replacement)
        assertTrue(db.highlightRuleManagerDao.get(id)!!.closed)
        assertEquals("",db.highlightRuleManagerDao.get(id)!!.state)
        assertNotNull(db.highlightRuleDao.findById(rule.id))
    }
    @Test fun closingAnUnfinishedExportRemovesOnlyItsPrivatePayload()=runBlocking {
        insert(); open(); val transfer=transfer()
        repo.close(id,owner)
        assertFalse(File(context.filesDir,"highlight-rule-transfers/${transfer.id}.json").exists())
        assertEquals(1,db.highlightRuleDao.all.size)
    }

    @Test fun reselectingAnOutputCreatesANewAttemptAndClearsThePreviousFailedUri()=runBlocking {
        insert(); open(); val transfer=transfer()
        val first=repo.transferPhase(id,owner,2,transfer.id,"awaiting").draft.transfer!!
        repo.transferPhase(id,owner,3,transfer.id,"writing","content://old/output")
        repo.transferPhase(id,owner,4,transfer.id,"failed")
        val next=repo.transferPhase(id,owner,5,transfer.id,"awaiting").draft.transfer!!
        assertNotNull(first.attempt); assertNotEquals(first.attempt,next.attempt)
        assertNull(next.uri); assertEquals(transfer.digest,next.digest)
    }

    @Test fun pickerImportsCreateOwnedDurableChildrenAndRejectOlderAttempts()=runBlocking {
        open(); val first=repo.startImport(id,owner,1).draft.importRequest!!
        val second=repo.startImport(id,owner,2).draft.importRequest!!
        assertNotEquals(first.id,second.id)
        assertTrue(runCatching { repo.importResult(id,owner,3,first.id,"content://fixture/old") }.isFailure)
        val received=repo.importResult(id,owner,3,second.id,"content://fixture/current")
        assertEquals("received",received.draft.importRequest!!.phase)
        val child=db.highlightRuleImportDao.get(second.id)!!
        assertEquals(id,child.parent); assertEquals("content://fixture/current",child.source)
        assertNull(db.highlightRuleImportDao.get(first.id))
        assertEquals(second.id,repo.importDestination(id,owner,second.id)!!.id)
    }
    @Test fun cancelledAndFailedImportPickersHaveNoChildOrImportResult()=runBlocking {
        open(); val first=repo.startImport(id,owner,1).draft.importRequest!!
        assertNull(repo.importResult(id,owner,2,first.id,null).draft.importRequest)
        assertNull(db.highlightRuleImportDao.get(first.id))
        val second=repo.startImport(id,owner,3).draft.importRequest!!
        assertEquals("failed",repo.importResult(id,owner,4,second.id,null,true).draft.importRequest!!.phase)
        assertNull(db.highlightRuleImportDao.get(second.id))
        val third=repo.startImport(id,owner,5).draft.importRequest!!
        assertNotEquals(second.id,third.id)
    }
    @Test fun receivedImportRequestsRestoreUntilTheirChildIsFinished()=runBlocking {
        open(); val request=repo.startImport(id,owner,1).draft.importRequest!!
        repo.importResult(id,owner,2,request.id,"content://fixture/current")
        val next=UUID.randomUUID().toString(); val restored=repo.open(id,next,11)
        assertEquals(request.id,restored.draft.importRequest!!.id)
        assertNotNull(repo.importDestination(id,next,request.id))
        val child=db.highlightRuleImportDao.get(request.id)!!
        db.highlightRuleImportDao.update(child.copy(phase="cancelled"))
        assertNull(repo.importDestination(id,next,request.id))
        assertNull(repo.clearFinishedImport(id,next,3,request.id).draft.importRequest)
    }
    @Test fun oldOwnersCannotPublishOrAbandonTheReplacementOwnersImportRequest()=runBlocking {
        open(); val request=repo.startImport(id,owner,1).draft.importRequest!!
        val next=UUID.randomUUID().toString(); repo.open(id,next,11)
        assertTrue(runCatching { repo.importResult(id,owner,2,request.id,"content://fixture/current") }.isFailure)
        assertTrue(runCatching { repo.abandonImport(id,owner,2,request.id) }.isFailure)
        assertNull(db.highlightRuleImportDao.get(request.id))
        assertEquals(request.id,repo.open(id,next,12).draft.importRequest!!.id)
    }
    @Test fun closingEvenACorruptParentCancelsItsPendingImportChildren()=runBlocking {
        open(); val request=repo.startImport(id,owner,1).draft.importRequest!!
        repo.importResult(id,owner,2,request.id,"content://fixture/current")
        db.highlightRuleManagerDao.update(db.highlightRuleManagerDao.get(id)!!.copy(state="corrupt"))
        repo.close(id,owner)
        assertEquals("cancelled",db.highlightRuleImportDao.get(request.id)!!.phase)
        assertEquals("",db.highlightRuleImportDao.get(request.id)!!.source)
    }
    @Test fun deletingAParentCascadesOnlyItsOwnImportSessionAndCandidates()=runBlocking {
        open(); val request=repo.startImport(id,owner,1).draft.importRequest!!
        repo.importResult(id,owner,2,request.id,"content://fixture/current")
        val other=UUID.randomUUID().toString()
        db.highlightRuleImportDao.insert(newHighlightImportRequest(other,"content://fixture/standalone"))
        db.highlightRuleManagerDao.delete(id)
        assertNull(db.highlightRuleImportDao.get(request.id)); assertNotNull(db.highlightRuleImportDao.get(other))
    }

}
