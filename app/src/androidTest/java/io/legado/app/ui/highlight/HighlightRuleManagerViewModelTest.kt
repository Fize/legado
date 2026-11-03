package io.legado.app.ui.highlight

import androidx.core.content.FileProvider
import androidx.lifecycle.*
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.constant.AppConst
import io.legado.app.data.*
import io.legado.app.data.entities.HighlightRule
import io.legado.app.data.repository.*
import io.legado.app.utils.GSON
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class HighlightRuleManagerViewModelTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    private val db=Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).addLegadoMigrations().build()
    private val files=HighlightRuleFiles(context)
    private val repo=HighlightRuleManagerRepository(db,files)
    private val stores=mutableListOf<ViewModelStore>()
    private val payloads=mutableSetOf<String>()
    private var rule=HighlightRule(name="Name",pattern="amber",group="Group")
    @Before fun setup() { rule=rule.copy(id=db.highlightRuleDao.insert(rule).single()) }
    @After fun cleanup() { instrumentation.runOnMainSync { stores.forEach(ViewModelStore::clear) }; payloads.forEach(files::remove); db.close() }
    private fun await(test: () -> Boolean)=runBlocking { withTimeout(10_000) { while(!test()) delay(10) } }
    private fun model(handle: SavedStateHandle=SavedStateHandle()): HighlightRuleViewModel {
        lateinit var model: HighlightRuleViewModel
        instrumentation.runOnMainSync {
            val store=ViewModelStore(); stores+=store
            model=ViewModelProvider(store,object: ViewModelProvider.Factory {
                override fun <T: ViewModel> create(modelClass: Class<T>): T=checkNotNull(modelClass.cast(HighlightRuleViewModel(handle,repo)))
            })[HighlightRuleViewModel::class.java]
        }
        await { !model.state.value.loading && model.state.value.rules.isNotEmpty() }
        assertFalse(model.state.value.error); return model
    }
    private fun draft(id: String)=GSON.fromJson(db.highlightRuleManagerDao.get(id)!!.state,HighlightRuleManagerDraft::class.java)
    @Test fun filtersAndSelectionRestoreWithoutEmbeddingRuleDataInSavedState() {
        val handle=SavedStateHandle(); val first=model(handle)
        instrumentation.runOnMainSync { first.filter("named","Group"); first.select(rule.uuid) }
        await { draft(first.id).selected==listOf(rule.uuid) }
        val second=model(SavedStateHandle(mapOf("highlight.manager.id" to first.id)))
        assertEquals("named",second.state.value.draft.filter); assertEquals("Group",second.state.value.draft.group)
        assertEquals(listOf(rule.uuid),second.state.value.draft.selected)
        assertEquals(setOf("highlight.manager.id"),handle.keys())
    }
    @Test fun pendingRenameTextAndItsOriginalSnapshotRestoreInANewViewModel() {
        val first=model()
        instrumentation.runOnMainSync { first.groups(true); first.groupAction("Group",true) }
        val dialog=first.state.value.draft.dialog!!
        instrumentation.runOnMainSync { first.renameText(dialog.id,"Uncommitted 😀") }
        await { draft(first.id).dialog?.text=="Uncommitted 😀" }
        val second=model(SavedStateHandle(mapOf("highlight.manager.id" to first.id)))
        assertEquals(dialog.refs,second.state.value.draft.dialog!!.refs)
        assertEquals("Uncommitted 😀",second.state.value.draft.dialog!!.text)
        assertEquals("Group",db.highlightRuleDao.findById(rule.id)!!.group)
    }
    @Test fun anOldDeleteDialogCannotConsumeNewlyImportedGroupMembers() {
        val model=model()
        instrumentation.runOnMainSync { model.groups(true); model.groupAction("Group",false) }
        val id=model.state.value.draft.dialog!!.id
        val added=HighlightRule(name="New",pattern="new",group="Group"); db.highlightRuleDao.insert(added)
        instrumentation.runOnMainSync { model.confirm(id) }
        await { model.state.value.error && !model.state.value.busy }
        assertEquals(2,db.highlightRuleDao.all.size); assertNotNull(model.state.value.draft.dialog)
    }
    @Test fun oldWindowCloseDoesNotCloseTheReplacementManager() {
        val first=model(); val second=model(SavedStateHandle(mapOf("highlight.manager.id" to first.id)))
        instrumentation.runOnMainSync { first.close() }
        await { first.state.value.closed }
        assertFalse(db.highlightRuleManagerDao.get(first.id)!!.closed)
        assertFalse(second.state.value.closed)
    }
    @Test fun aPickerResultReceivedBeforeLoadingIsAppliedAfterOwnershipIsRestored()=runBlocking {
        val id=UUID.randomUUID().toString(); val owner=UUID.randomUUID().toString()
        repo.open(id,owner,HighlightManagerOwnership.next())
        val refs=repo.observe().first().map { it.ref }
        val transfer=repo.prepare(id,owner,1,refs,"export").draft.transfer!!; payloads+=transfer.id
        val awaiting=repo.transferPhase(id,owner,2,transfer.id,"awaiting").draft.transfer!!
        val target=File(context.filesDir,"rule-vm-output-${UUID.randomUUID()}.json")
        try {
            target.writeText("before"); val uri=FileProvider.getUriForFile(context,AppConst.authority,target).toString()
            val model=model(SavedStateHandle(mapOf("highlight.manager.id" to id,"highlight.manager.result.id" to transfer.id,"highlight.manager.result.attempt" to awaiting.attempt,"highlight.manager.result.uri" to uri)))
            await { model.state.value.draft.exportedUri==uri }
            assertTrue(target.readText().contains("amber")); assertNull(model.state.value.draft.transfer)
        } finally { target.delete() }
    }
    @Test fun aStalePickerResultDoesNotConsumeTheCurrentPendingTransfer()=runBlocking {
        val id=UUID.randomUUID().toString(); val owner=UUID.randomUUID().toString()
        repo.open(id,owner,HighlightManagerOwnership.next())
        val transfer=repo.prepare(id,owner,1,repo.observe().first().map { it.ref },"export").draft.transfer!!; payloads+=transfer.id
        repo.transferPhase(id,owner,2,transfer.id,"awaiting")
        val model=model(SavedStateHandle(mapOf("highlight.manager.id" to id)))
        instrumentation.runOnMainSync { model.result(UUID.randomUUID().toString(),UUID.randomUUID().toString(),null) }
        assertEquals(transfer.id,model.state.value.draft.transfer!!.id)
        assertNull(model.state.value.draft.exportedUri)
    }
    private suspend fun deliver(model: HighlightRuleViewModel): HighlightRuleTransfer {
        withContext(Dispatchers.Main) { assertTrue(model.deliver(model.state.value.draft.transfer!!.id)) }
        return model.state.value.draft.transfer!!
    }
    private suspend fun preparedModel(): HighlightRuleViewModel {
        val model=model()
        instrumentation.runOnMainSync { model.prepare(model.state.value.rules.map { it.ref }) }
        await { !model.state.value.busy && model.state.value.draft.transfer!=null }
        payloads+=model.state.value.draft.transfer!!.id
        return model
    }
    @Test fun anEarlierPickerAttemptCannotCancelTheSamePayloadsNewAttempt()=runBlocking {
        val model=preparedModel(); val first=deliver(model); val second=deliver(model)
        assertEquals(first.id,second.id); assertNotEquals(first.attempt,second.attempt)
        instrumentation.runOnMainSync { model.result(first.id,first.attempt,null) }
        assertEquals(second,model.state.value.draft.transfer)
        instrumentation.runOnMainSync { model.result(second.id,second.attempt,null) }
        await { !model.state.value.busy && model.state.value.draft.transfer==null }
        assertNull(model.state.value.draft.exportedUri)
    }
    @Test fun launchFailureRetainsThePayloadAndAllowsAFreshPickerAttempt()=runBlocking {
        val model=preparedModel(); val first=deliver(model)
        instrumentation.runOnMainSync { model.launchFailed(first.id,first.attempt!!) }
        await { !model.state.value.busy && model.state.value.draft.transfer?.phase=="failed" }
        assertTrue(model.state.value.error); assertNull(model.state.value.draft.exportedUri)
        assertTrue(File(context.filesDir,"highlight-rule-transfers/${first.id}.json").exists())
        val restored=model(SavedStateHandle(mapOf("highlight.manager.id" to model.id)))
        assertEquals("failed",restored.state.value.draft.transfer!!.phase)
        val second=deliver(restored)
        assertNotEquals(first.attempt,second.attempt); assertEquals(first.digest,second.digest)
        instrumentation.runOnMainSync { restored.launchFailed(first.id,first.attempt!!) }
        assertEquals("awaiting",restored.state.value.draft.transfer!!.phase)
    }
    @Test fun aRestoredPendingRegistrationDoesNotAutomaticallyLaunchAgain()=runBlocking {
        val model=preparedModel(); val transfer=deliver(model)
        instrumentation.runOnMainSync {
            assertTrue(model.consumeLaunch(transfer.attempt!!)); assertFalse(model.consumeLaunch(transfer.attempt))
        }
        val restored=model(SavedStateHandle(mapOf("highlight.manager.id" to model.id)))
        assertEquals(transfer,restored.state.value.draft.transfer)
        instrumentation.runOnMainSync { assertFalse(restored.consumeLaunch(transfer.attempt!!)) }
    }
    @Test fun aDeferredOldAttemptCannotConsumeTheCurrentAttemptAfterRestoring()=runBlocking {
        val first=preparedModel(); val old=deliver(first); val current=deliver(first)
        val restored=model(SavedStateHandle(mapOf("highlight.manager.id" to first.id,
            "highlight.manager.result.id" to old.id,"highlight.manager.result.attempt" to old.attempt,"highlight.manager.result.uri" to "")))
        assertEquals(current,restored.state.value.draft.transfer); assertNull(restored.state.value.draft.exportedUri)
    }

    @Test fun importAttemptsRestoreWithoutRepeatingTheirLaunchOrKeepingUrisInSavedState() {
        val handle=SavedStateHandle(); val first=model(handle)
        instrumentation.runOnMainSync { first.beginImport() }
        await { !first.state.value.busy && first.state.value.draft.importRequest!=null }
        val request=first.state.value.draft.importRequest!!
        instrumentation.runOnMainSync { assertTrue(first.consumeImportLaunch(request.id)); assertFalse(first.consumeImportLaunch(request.id)) }
        val second=model(SavedStateHandle(mapOf("highlight.manager.id" to first.id)))
        assertEquals(request,second.state.value.draft.importRequest)
        instrumentation.runOnMainSync { assertFalse(second.consumeImportLaunch(request.id)) }
        assertEquals(setOf("highlight.manager.id"),handle.keys())
    }
    @Test fun aStaleImportResultCannotConsumeTheLatestPickerAttempt() {
        val model=model()
        instrumentation.runOnMainSync { model.beginImport() }; await { !model.state.value.busy && model.state.value.draft.importRequest!=null }
        val old=model.state.value.draft.importRequest!!
        instrumentation.runOnMainSync { model.beginImport() }; await { !model.state.value.busy && model.state.value.draft.importRequest?.id!=old.id }
        val current=model.state.value.draft.importRequest!!
        instrumentation.runOnMainSync { model.importResult(old.id,"content://fixture/old") }
        assertEquals(current,model.state.value.draft.importRequest); assertNull(db.highlightRuleImportDao.get(old.id))
        instrumentation.runOnMainSync { model.importResult(current.id,"content://fixture/current") }
        await { !model.state.value.busy && model.state.value.draft.importRequest?.phase=="received" }
        assertEquals(model.id,db.highlightRuleImportDao.get(current.id)!!.parent)
        assertEquals("content://fixture/current",db.highlightRuleImportDao.get(current.id)!!.source)
    }
    @Test fun aFinishedChildClearsItsDurableManagerReceiptAndAllowsANewImport() {
        val first=model(); instrumentation.runOnMainSync { first.beginImport() }
        await { !first.state.value.busy && first.state.value.draft.importRequest!=null }
        val request=first.state.value.draft.importRequest!!
        instrumentation.runOnMainSync { first.importResult(request.id,"content://fixture/current") }
        await { !first.state.value.busy && first.state.value.draft.importRequest?.phase=="received" }
        val child=db.highlightRuleImportDao.get(request.id)!!
        db.highlightRuleImportDao.update(child.copy(phase="cancelled"))
        await { !first.state.value.busy && first.state.value.draft.importRequest==null }
        instrumentation.runOnMainSync { first.beginImport() }
        await { !first.state.value.busy && first.state.value.draft.importRequest!=null }
        assertNotEquals(request.id,first.state.value.draft.importRequest!!.id)
    }
    @Test fun aDeferredImportResultIsAppliedOnlyAfterTheManagerIsClaimed()=runBlocking {
        val id=UUID.randomUUID().toString(); val owner=UUID.randomUUID().toString()
        repo.open(id,owner,HighlightManagerOwnership.next())
        val request=repo.startImport(id,owner,1).draft.importRequest!!
        val model=model(SavedStateHandle(mapOf("highlight.manager.id" to id,"highlight.manager.import.result.id" to request.id,
            "highlight.manager.import.result.uri" to "content://fixture/deferred")))
        await { !model.state.value.busy && model.state.value.draft.importRequest?.phase=="received" }
        assertEquals("content://fixture/deferred",db.highlightRuleImportDao.get(request.id)!!.source)
    }
    @Test fun anOlderSavedStateImportUriIsConvertedIntoAnOwnedRoomRequest() {
        val handle=SavedStateHandle(mapOf("highlight.manager.import" to "content://fixture/upgrade"))
        val model=model(handle); val request=model.state.value.draft.importRequest!!
        assertEquals("received",request.phase); assertEquals(model.id,db.highlightRuleImportDao.get(request.id)!!.parent)
        assertEquals(setOf("highlight.manager.id"),handle.keys())
    }

    @Test fun anImportResultWaitsForAnAlreadyRunningManagerWrite() {
        val handle=SavedStateHandle(); val model=model(handle)
        instrumentation.runOnMainSync { model.beginImport() }
        await { !model.state.value.busy && model.state.value.draft.importRequest!=null }
        val attempt=model.state.value.draft.importRequest!!.id
        instrumentation.runOnMainSync {
            model.act(model.state.value.rules.map { it.ref },"disable")
            assertTrue(model.state.value.busy)
            model.importResult(attempt,"content://fixture/busy-result")
        }
        await { !model.state.value.busy && model.state.value.draft.importRequest?.phase=="received" }
        assertEquals("content://fixture/busy-result",db.highlightRuleImportDao.get(attempt)!!.source)
        assertFalse(db.highlightRuleDao.findById(rule.id)!!.isEnabled)
        assertEquals(setOf("highlight.manager.id"),handle.keys())
    }
    @Test fun anExportResultWaitsForAnAlreadyRunningManagerWrite()=runBlocking {
        val model=preparedModel(); val transfer=deliver(model)
        val output=File(context.filesDir,"highlight-busy-output-${UUID.randomUUID()}.json")
        try {
            output.writeText("before"); val uri=FileProvider.getUriForFile(context,AppConst.authority,output).toString()
            instrumentation.runOnMainSync {
                model.act(model.state.value.rules.map { it.ref },"disable")
                assertTrue(model.state.value.busy); model.result(transfer.id,transfer.attempt,uri)
            }
            await { !model.state.value.busy && model.state.value.draft.exportedUri==uri }
            assertTrue(output.readText().contains("amber")); assertFalse(db.highlightRuleDao.findById(rule.id)!!.isEnabled)
        } finally { output.delete() }
    }

}
