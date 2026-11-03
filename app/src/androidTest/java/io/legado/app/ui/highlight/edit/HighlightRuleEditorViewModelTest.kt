package io.legado.app.ui.highlight.edit

import androidx.lifecycle.*
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.*
import io.legado.app.data.repository.HighlightRuleEditorRepository
import io.legado.app.ui.navigation.HighlightRuleEditRoute
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class HighlightRuleEditorViewModelTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val db=Room.inMemoryDatabaseBuilder(instrumentation.targetContext,AppDatabase::class.java).addLegadoMigrations().build()
    private val repo=HighlightRuleEditorRepository(db)
    private val stores=mutableListOf<ViewModelStore>()
    private val id=UUID.randomUUID().toString()
    private val route=HighlightRuleEditRoute(requestId=id)
    @After fun cleanup() { instrumentation.runOnMainSync { stores.forEach(ViewModelStore::clear) }; db.close() }
    private fun await(test: () -> Boolean)=runBlocking { withTimeout(10_000) { while(!test()) delay(10) } }
    private fun model(handle: SavedStateHandle=SavedStateHandle()): HighlightRuleEditorViewModel {
        lateinit var model: HighlightRuleEditorViewModel
        instrumentation.runOnMainSync {
            val store=ViewModelStore(); stores+=store
            model=ViewModelProvider(store,object: ViewModelProvider.Factory {
                override fun <T: ViewModel> create(modelClass: Class<T>): T=checkNotNull(modelClass.cast(HighlightRuleEditorViewModel(handle,repo)))
            })[HighlightRuleEditorViewModel::class.java]
            model.initialize(route)
        }
        await { !model.state.value.loading }; assertFalse(model.state.value.failed)
        return model
    }
    @Test fun rapidStyleAndTextChangesRestoreInANewViewModelWithoutLargeSavedState() {
        val handle=SavedStateHandle(); val first=model(handle); val text="amber 😀".repeat(20_000)
        instrumentation.runOnMainSync {
            first.style(id) { it.copy(bold=true,fontSize=42f) }
            first.edit(id) { it.copy(pattern=text) }
            first.edit(id) { it.copy(name="last name") }
        }
        await { db.highlightRuleDraftDao.get(id)?.revision==3L }
        val restored=model()
        assertEquals(text,restored.state.value.value!!.pattern); assertEquals("last name",restored.state.value.value!!.name)
        assertTrue(restored.state.value.style.bold); assertEquals(42f,restored.state.value.style.fontSize)
        assertEquals(setOf("highlight.editor.id"),handle.keys()); assertEquals(id,handle.get<String>("highlight.editor.id"))
    }
    @Test fun immediateConfirmationFlushesAllPendingInputsAndOnlyInsertsOnce() {
        val model=model()
        instrumentation.runOnMainSync {
            model.style(id) { it.copy(bold=true) }
            model.edit(id) { it.copy(pattern="amber",scope=id) }
            model.finish(id,true); model.finish(id,true)
        }
        await { model.state.value.closed }
        assertEquals(1,db.highlightRuleDao.all.size); val saved=db.highlightRuleDao.all.single()
        assertEquals("amber",saved.pattern); assertTrue(saved.styleObj().bold)
    }
    @Test fun oldViewModelInputAndCancelCannotChangeTheReplacementDraft() {
        val first=model(); val second=model()
        instrumentation.runOnMainSync { second.edit(id) { it.copy(pattern="new owner") } }
        await { db.highlightRuleDraftDao.get(id)?.revision==1L }
        instrumentation.runOnMainSync { first.edit(id) { it.copy(pattern="old owner") }; first.finish(id,false) }
        await { first.state.value.closed }
        assertEquals("editing",db.highlightRuleDraftDao.get(id)!!.phase)
        assertEquals("new owner",repo.decode(db.highlightRuleDraftDao.get(id)!!).pattern)
    }
    @Test fun aFontResultFromAnotherRequestIsIgnored() {
        val model=model()
        instrumentation.runOnMainSync { model.style(UUID.randomUUID().toString()) { it.copy(fontPath="file:///stale.ttf") } }
        assertEquals("",model.state.value.style.fontPath)
        assertEquals(0L,db.highlightRuleDraftDao.get(id)!!.revision)
    }
    @Test fun cancellingACorruptClaimedDraftClearsItsLargePayload()=runBlocking {
        val draft=repo.open(id,null,UUID.randomUUID().toString())
        db.highlightRuleDraftDao.update(draft.copy(value="not json"))
        lateinit var model: HighlightRuleEditorViewModel
        instrumentation.runOnMainSync {
            val store=ViewModelStore(); stores+=store
            model=ViewModelProvider(store,object: ViewModelProvider.Factory {
                override fun <T: ViewModel> create(modelClass: Class<T>): T=checkNotNull(modelClass.cast(HighlightRuleEditorViewModel(SavedStateHandle(),repo)))
            })[HighlightRuleEditorViewModel::class.java]
            model.initialize(route)
        }
        await { !model.state.value.loading }; assertTrue(model.state.value.failed)
        instrumentation.runOnMainSync { model.finish(id,false) }
        await { model.state.value.closed }
        assertEquals("cancelled",db.highlightRuleDraftDao.get(id)!!.phase)
        assertEquals("",db.highlightRuleDraftDao.get(id)!!.value)
    }

}
