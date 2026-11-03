package io.legado.app.ui.highlight

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.activity.result.*
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.*
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.constant.AppConst
import io.legado.app.data.*
import io.legado.app.data.entities.HighlightRule
import io.legado.app.data.repository.*
import io.legado.app.ui.AppActivity
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class HighlightTransferLauncherUiTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    private val db=Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).addLegadoMigrations().build()
    private val files=HighlightRuleFiles(context)
    private val store=ViewModelStore()
    private lateinit var model: HighlightRuleViewModel
    private var scenario: ActivityScenario<AppActivity>?=null
    private val payloads=mutableSetOf<String>()
    private var show by mutableStateOf(true)
    private val requests=mutableListOf<Int>()
    private val intents=mutableListOf<Intent>()
    private val imports=mutableListOf<Pair<String,String>>()
    private val inputs=mutableListOf<File>()
    private var missing=false
    private val registry=object: ActivityResultRegistry() {
        override fun <I,O> onLaunch(requestCode: Int,contract: ActivityResultContract<I,O>,input: I,options: ActivityOptionsCompat?) {
            requests+=requestCode; intents+=contract.createIntent(context,input)
            if(missing) throw ActivityNotFoundException("Fixture: no document provider")
        }
    }
    @Before fun setup() {
        db.highlightRuleDao.insert(HighlightRule(name="Own transfer",pattern="owned-payload"))
        scenario=ActivityScenario.launch(Intent(context,AppActivity::class.java))
        scenario!!.onActivity { activity ->
            model=ViewModelProvider(store,object: ViewModelProvider.Factory {
                override fun <T: ViewModel> create(modelClass: Class<T>): T=checkNotNull(modelClass.cast(HighlightRuleViewModel(SavedStateHandle(),HighlightRuleManagerRepository(db,files))))
            })[HighlightRuleViewModel::class.java]
            activity.setContent {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides object: ActivityResultRegistryOwner {
                    override val activityResultRegistry=registry
                }) { MaterialTheme { if(show) HighlightRuleScreen(onBack={},onImport={ id,uri,_ -> imports+=id to uri },onEdit={},viewModel=model) } }
            }
        }
        await { !model.state.value.loading && model.state.value.rules.isNotEmpty() }
    }
    @After fun cleanup() {
        scenario?.close(); instrumentation.runOnMainSync { store.clear() }
        if(this::model.isInitialized) model.state.value.draft.transfer?.id?.let(payloads::add)
        payloads.forEach(files::remove); inputs.forEach(File::delete); db.close()
    }
    private fun await(condition: () -> Boolean) {
        compose.waitForIdle(); compose.waitUntil(15_000) { condition() }; compose.waitForIdle()
    }
    private fun export() {
        compose.onNodeWithTag("legadoTopAppBarOverflow").performClick()
        compose.onNodeWithText(context.getString(R.string.export_all)).performClick()
        await { requests.isNotEmpty() }
        payloads+=model.state.value.draft.transfer!!.id
    }
    @Test fun aMissingDocumentProviderShowsRecoveryAndPreservesThePayload() {
        instrumentation.runOnMainSync { missing=true }; export()
        await { !model.state.value.busy && model.state.value.draft.transfer?.phase=="failed" }
        assertTrue(model.state.value.error)
        compose.onNodeWithTag("highlightTransferChoose").assertIsEnabled()
        compose.onNodeWithTag("highlightExportSuccess").assertDoesNotExist()
        assertTrue(File(context.filesDir,"highlight-rule-transfers/${model.state.value.draft.transfer!!.id}.json").exists())
        instrumentation.runOnMainSync { missing=false }
        compose.onNodeWithTag("highlightTransferChoose").performClick()
        await { requests.size==2 && !model.state.value.busy }
        assertEquals("awaiting",model.state.value.draft.transfer!!.phase)
    }
    @Test fun anOldRegistryResultCannotConsumeTheReselectedDocumentAttempt() {
        export(); val first=model.state.value.draft.transfer!!
        compose.onNodeWithTag("highlightTransferChoose").performClick()
        await { requests.size==2 && !model.state.value.busy }
        val second=model.state.value.draft.transfer!!
        assertNotEquals(first.attempt,second.attempt); assertNotEquals(requests[0],requests[1])
        instrumentation.runOnMainSync { registry.dispatchResult(requests[0],Activity.RESULT_CANCELED,null) }
        compose.waitForIdle(); assertEquals(second,model.state.value.draft.transfer)
        val output=File(context.filesDir,"rule-registry-${UUID.randomUUID()}.json")
        try {
            output.writeText("before")
            val uri=FileProvider.getUriForFile(context,AppConst.authority,output)
            instrumentation.runOnMainSync { registry.dispatchResult(requests[1],Activity.RESULT_OK,Intent().setData(uri)) }
            await { model.state.value.draft.exportedUri==uri.toString() }
            assertTrue(output.readText().contains("owned-payload"))
            compose.onNodeWithTag("highlightExportSuccess").assertExists()
        } finally { output.delete() }
    }
    @Test fun restoringARegistrationReceivesItsResultWithoutLaunchingAnotherPicker() {
        export(); val request=requests.single()
        instrumentation.runOnMainSync { show=false }; compose.waitForIdle()
        instrumentation.runOnMainSync { show=true }; compose.waitForIdle()
        assertEquals(1,requests.size)
        instrumentation.runOnMainSync { registry.dispatchResult(request,Activity.RESULT_CANCELED,null) }
        await { !model.state.value.busy && model.state.value.draft.transfer==null }
        compose.onNodeWithTag("highlightExportSuccess").assertDoesNotExist()
    }
    private fun importFile() {
        compose.onNodeWithTag("legadoTopAppBarOverflow").performClick()
        compose.onNodeWithText(context.getString(R.string.import_local)).performClick()
        await { requests.isNotEmpty() }
    }
    private fun inputUri(): android.net.Uri {
        val input=File(context.filesDir,"registry-import-${UUID.randomUUID()}.json").also(inputs::add)
        input.writeText("[]")
        return FileProvider.getUriForFile(context,AppConst.authority,input)
    }
    @Test fun aMissingImportProviderAllowsReselectionWithoutCreatingAChild() {
        instrumentation.runOnMainSync { missing=true }; importFile()
        await { !model.state.value.busy && model.state.value.draft.importRequest?.phase=="failed" }
        val failed=model.state.value.draft.importRequest!!
        assertNull(db.highlightRuleImportDao.get(failed.id)); assertTrue(imports.isEmpty())
        instrumentation.runOnMainSync { missing=false }
        compose.onNodeWithTag("highlightImportChoose").performClick()
        await { requests.size==2 && !model.state.value.busy }
        assertNotEquals(failed.id,model.state.value.draft.importRequest!!.id)
        assertEquals("awaiting",model.state.value.draft.importRequest!!.phase)
    }
    @Test fun aRetiredImportPickerCannotCreateAChildOrNavigateTheNewAttempt() {
        importFile(); val old=model.state.value.draft.importRequest!!
        compose.onNodeWithTag("highlightImportChoose").performClick()
        await { requests.size==2 && !model.state.value.busy }
        val current=model.state.value.draft.importRequest!!; val uri=inputUri()
        instrumentation.runOnMainSync { registry.dispatchResult(requests[0],Activity.RESULT_OK,Intent().setData(uri)) }
        compose.waitForIdle(); assertEquals(current,model.state.value.draft.importRequest)
        assertTrue(imports.isEmpty()); assertNull(db.highlightRuleImportDao.get(old.id))
        instrumentation.runOnMainSync { registry.dispatchResult(requests[1],Activity.RESULT_OK,Intent().setData(uri)) }
        await { imports.size==1 && !model.state.value.busy }
        assertEquals(current.id to uri.toString(),imports.single())
        assertEquals(model.id,db.highlightRuleImportDao.get(current.id)!!.parent)
        val child=db.highlightRuleImportDao.get(current.id)!!
        db.highlightRuleImportDao.update(child.copy(phase="cancelled"))
        await { model.state.value.draft.importRequest==null }
        assertEquals(1,imports.size)
    }
    @Test fun anImportRegistrationRestoresItsPendingResultWithoutReopeningThePicker() {
        importFile(); val request=requests.single()
        instrumentation.runOnMainSync { show=false }; compose.waitForIdle()
        instrumentation.runOnMainSync { show=true }; compose.waitForIdle()
        assertEquals(1,requests.size)
        instrumentation.runOnMainSync { registry.dispatchResult(request,Activity.RESULT_CANCELED,null) }
        await { !model.state.value.busy && model.state.value.draft.importRequest==null }
        assertTrue(imports.isEmpty())
    }
    @Test fun sharingLaunchesTheActualChooserWithAReadableStreamClipAndReadGrant() {
        instrumentation.runOnMainSync { model.prepare(model.state.value.rules.map { it.ref },true) }
        await { requests.isNotEmpty() && !model.state.value.busy }
        val transfer=model.state.value.draft.transfer!!; payloads+=transfer.id
        val chooser=intents.single(); assertEquals(Intent.ACTION_CHOOSER,chooser.action)
        val send=androidx.core.content.IntentCompat.getParcelableExtra(chooser,Intent.EXTRA_INTENT,Intent::class.java)!!
        val uri=androidx.core.content.IntentCompat.getParcelableExtra(send,Intent.EXTRA_STREAM,android.net.Uri::class.java)!!
        assertEquals(Intent.ACTION_SEND,send.action); assertEquals("application/json",send.type)
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION!=0)
        assertEquals(uri,send.clipData!!.getItemAt(0).uri)
        assertTrue(context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }.contains("owned-payload"))
        instrumentation.runOnMainSync { registry.dispatchResult(requests.single(),Activity.RESULT_CANCELED,null) }
        await { !model.state.value.busy && model.state.value.draft.transfer==null }
        assertTrue(File(context.filesDir,"highlight-rule-transfers/${transfer.id}.json").exists())
        assertTrue(File(context.filesDir,"highlight-rule-transfers/${transfer.id}.retained").exists())
    }
    @Test fun aMissingShareHandlerRetainsThePayloadForAnotherChooserAttempt() {
        instrumentation.runOnMainSync { missing=true; model.prepare(model.state.value.rules.map { it.ref },true) }
        await { requests.isNotEmpty() && !model.state.value.busy && model.state.value.draft.transfer?.phase=="failed" }
        val first=model.state.value.draft.transfer!!; payloads+=first.id
        assertTrue(File(context.filesDir,"highlight-rule-transfers/${first.id}.json").exists())
        instrumentation.runOnMainSync { missing=false }
        compose.onNodeWithTag("highlightTransferChoose").performClick()
        await { requests.size==2 && !model.state.value.busy }
        assertNotEquals(first.attempt,model.state.value.draft.transfer!!.attempt)
    }

}
