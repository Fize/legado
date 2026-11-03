package io.legado.app.ui.association

import androidx.core.content.FileProvider
import androidx.lifecycle.*
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.constant.AppConst
import io.legado.app.data.*
import io.legado.app.data.entities.*
import io.legado.app.data.repository.*
import io.legado.app.utils.GSON
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class HighlightRuleImportViewModelTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    private val db=Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).addLegadoMigrations().build()
    private val repo=HighlightRuleImportRepository(context,db)
    private val id=UUID.randomUUID().toString()
    private val input=File(context.filesDir,"highlight-import-vm-${UUID.randomUUID()}.json")
    private val source get()=FileProvider.getUriForFile(context,AppConst.authority,input).toString()
    private val stores=mutableListOf<ViewModelStore>()
    private val first=HighlightRule(name="First",pattern="first")
    private val second=HighlightRule(name="Second",pattern="second")
    private fun write() { input.writeText(GSON.toJson(HighlightRuleFile(HighlightRuleFile.TYPE,listOf(first,second)))) }
    private fun await(condition: () -> Boolean)=runBlocking { withTimeout(10_000) { while(!condition()) delay(10) } }
    private fun model(): ImportHighlightRuleViewModel {
        lateinit var model: ImportHighlightRuleViewModel
        instrumentation.runOnMainSync {
            val store=ViewModelStore(); stores+=store
            model=ViewModelProvider(store,object: ViewModelProvider.Factory {
                override fun <T: ViewModel> create(modelClass: Class<T>): T=checkNotNull(modelClass.cast(ImportHighlightRuleViewModel(repo)))
            })[ImportHighlightRuleViewModel::class.java]
            model.load(id,source)
        }
        await { !model.uiState.value.loading }; return model
    }
    @After fun cleanup() { instrumentation.runOnMainSync { stores.forEach(ViewModelStore::clear) }; db.close(); input.delete() }
    @Test fun aNewViewModelRestoresSelectionAndCandidatesWithoutReadingTheOriginalFile() {
        write(); val firstModel=model(); assertFalse(firstModel.uiState.value.error)
        instrumentation.runOnMainSync { firstModel.toggle(first.uuid,false) }
        await { !firstModel.uiState.value.importing && firstModel.uiState.value.selectedCount==1 }
        input.delete(); val restored=model()
        assertFalse(restored.uiState.value.error); assertEquals(1,restored.uiState.value.selectedCount)
        assertFalse(restored.uiState.value.items.single { it.uuid==first.uuid }.selected)
        assertTrue(db.highlightRuleDao.all.isEmpty())
    }
    @Test fun repeatedConfirmationCommitsOnceAndProducesADurableCompletionReceipt() {
        write(); val model=model()
        instrumentation.runOnMainSync { model.importSelected(); model.importSelected() }
        await { model.uiState.value.completed && !model.uiState.value.importing }
        assertEquals(2,db.highlightRuleDao.all.size); assertEquals("completed",db.highlightRuleImportDao.get(id)!!.phase)
        assertTrue(db.highlightRuleImportDao.rows(id).isEmpty()); input.delete()
        val restored=model(); assertTrue(restored.uiState.value.completed); assertEquals(2,db.highlightRuleDao.all.size)
    }
    @Test fun conflictRecoveryReviewsDurableCandidatesBeforeAnotherExplicitConfirmation() {
        val local=first.copy(pattern="old").let { it.copy(id=db.highlightRuleDao.insert(it).single()) }
        write(); val model=model(); db.highlightRuleDao.update(local.copy(pattern="concurrent"))
        instrumentation.runOnMainSync { model.importSelected() }
        await { model.uiState.value.error && !model.uiState.value.importing }
        assertEquals("concurrent",db.highlightRuleDao.all.single().pattern)
        input.delete(); instrumentation.runOnMainSync { model.retry() }
        await { !model.uiState.value.error && !model.uiState.value.importing }
        assertFalse(model.uiState.value.completed); assertEquals(1,db.highlightRuleDao.all.size)
        instrumentation.runOnMainSync { model.importSelected() }
        await { model.uiState.value.completed && !model.uiState.value.importing }
        assertEquals(first.pattern,db.highlightRuleDao.findById(local.id)!!.pattern); assertEquals(2,db.highlightRuleDao.all.size)
    }
    @Test fun cancellingAnOldWindowDoesNotCancelTheReplacementOwnersImport() {
        write(); val old=model(); val current=model()
        instrumentation.runOnMainSync { old.cancel() }
        await { old.uiState.value.completed }
        assertEquals("ready",db.highlightRuleImportDao.get(id)!!.phase); assertFalse(current.uiState.value.completed)
        instrumentation.runOnMainSync { current.importSelected() }
        await { current.uiState.value.completed && !current.uiState.value.importing }
        assertEquals(2,db.highlightRuleDao.all.size)
    }
    @Test fun aFailedPreviewCanBeCancelledWithoutImportingAnyCandidate() {
        input.writeText("invalid"); val model=model(); assertTrue(model.uiState.value.error)
        instrumentation.runOnMainSync { model.cancel() }
        await { model.uiState.value.completed && !model.uiState.value.importing }
        assertEquals("cancelled",db.highlightRuleImportDao.get(id)!!.phase); assertTrue(db.highlightRuleDao.all.isEmpty())
    }
}
