package io.legado.app.ui.association

import android.graphics.Bitmap
import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.pressBackUnconditionally
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fi.iki.elonen.NanoHTTPD
import io.legado.app.R
import io.legado.app.constant.AppConst
import io.legado.app.data.appDb
import io.legado.app.data.entities.*
import io.legado.app.ui.AppActivity
import io.legado.app.ui.navigation.*
import io.legado.app.utils.GSON
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class HighlightRuleImportRouteUiTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    private val first=HighlightRule(name="Import first ${UUID.randomUUID()}",pattern="incoming-first")
    private val second=HighlightRule(name="Import second ${UUID.randomUUID()}",pattern="incoming-second")
    private val input=File(context.filesDir,"highlight-import-ui-${UUID.randomUUID()}.json")
    private val id=UUID.randomUUID().toString()
    private val importArtifacts=HighlightImportArtifacts()
    private val managerArtifacts=io.legado.app.ui.highlight.HighlightManagerArtifacts()
    private var scenario: ActivityScenario<AppActivity>?=null
    private val source get()=FileProvider.getUriForFile(context,AppConst.authority,input).toString()
    private fun write() { input.writeText(GSON.toJson(HighlightRuleFile(HighlightRuleFile.TYPE,listOf(first,second)))) }
    private fun launch(source: String=this.source) {
        scenario=ActivityScenario.launch(AppActivity.startIntent(context,ImportRoute(source,ImportKind.HighlightRule,id)))
    }
    private fun await(condition: () -> Boolean) { compose.waitForIdle(); compose.waitUntil(15_000,condition); compose.waitForIdle() }
    private fun ready()=await { compose.onAllNodesWithTag("highlightImportChoice:${first.uuid}").fetchSemanticsNodes().isNotEmpty() }
    private fun screenshot(name: String) {
        compose.waitForIdle(); compose.mainClock.advanceTimeBy(500)
        instrumentation.waitForIdleSync()
        // Dialog window fades run on the device clock, independently of Compose's test clock.
        android.os.SystemClock.sleep(500); instrumentation.waitForIdleSync()
        val image=instrumentation.uiAutomation.takeScreenshot()!!
        try { File(context.getExternalFilesDir("ui-regression"),"$name.png").outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,it)) } }
        finally { image.recycle() }
    }
    @After fun cleanup() {
        scenario?.close(); importArtifacts.cleanup(); managerArtifacts.cleanup(); appDb.highlightRuleImportDao.delete(id)
        appDb.highlightRuleDao.all.filter { it.uuid in setOf(first.uuid,second.uuid) }.forEach(appDb.highlightRuleDao::delete)
        input.delete()
    }
    @Test fun selectionAndCandidatesRestoreAfterRecreationWithoutTheOriginalProviderFile() {
        write(); launch(); ready()
        compose.onNodeWithTag("highlightImportChoice:${second.uuid}").performClick()
        await { appDb.highlightRuleImportDao.choices(id).count { it.selected }==1 }
        input.delete(); scenario!!.recreate(); ready()
        assertEquals(1,appDb.highlightRuleImportDao.choices(id).count { it.selected })
        screenshot("highlight-import-restored")
        compose.onNodeWithTag("highlightRuleImportConfirm").performClick()
        await { appDb.highlightRuleImportDao.get(id)?.phase=="completed" }
        assertTrue(appDb.highlightRuleDao.all.any { it.uuid==first.uuid })
        assertFalse(appDb.highlightRuleDao.all.any { it.uuid==second.uuid })
        assertTrue(appDb.highlightRuleImportDao.rows(id).isEmpty())
    }
    @Test fun concurrentChangesRequireReviewAndAnExplicitSecondConfirmation() {
        val local=first.copy(pattern="before").let { it.copy(id=appDb.highlightRuleDao.insert(it).single()) }
        write(); launch(); ready(); appDb.highlightRuleDao.update(local.copy(pattern="concurrent"))
        compose.onNodeWithTag("highlightRuleImportConfirm").performClick()
        await { compose.onAllNodesWithTag("highlightRuleImportRetry").fetchSemanticsNodes().isNotEmpty() }
        assertEquals("concurrent",appDb.highlightRuleDao.findById(local.id)!!.pattern)
        assertFalse(appDb.highlightRuleDao.all.any { it.uuid==second.uuid })
        screenshot("highlight-import-conflict")
        input.delete(); compose.onNodeWithTag("highlightRuleImportRetry").performClick()
        await { compose.onAllNodesWithTag("highlightRuleImportRetry").fetchSemanticsNodes().isEmpty() }
        ready(); compose.onNodeWithTag("highlightRuleImportConfirm").performClick()
        await { appDb.highlightRuleImportDao.get(id)?.phase=="completed" }
        assertEquals(first.pattern,appDb.highlightRuleDao.findById(local.id)!!.pattern)
        assertTrue(appDb.highlightRuleDao.all.any { it.uuid==second.uuid })
    }
    @Test fun malformedFilesCanBeCorrectedAndRetriedWithoutReopeningTheRoute() {
        input.writeText("not-json"); launch()
        await { compose.onAllNodesWithTag("highlightRuleImportRetry").fetchSemanticsNodes().isNotEmpty() }
        assertEquals("failed",appDb.highlightRuleImportDao.get(id)!!.phase)
        write(); compose.onNodeWithTag("highlightRuleImportRetry").performClick(); ready()
        assertEquals("ready",appDb.highlightRuleImportDao.get(id)!!.phase)
        assertFalse(appDb.highlightRuleDao.all.any { it.uuid==first.uuid })
    }
    @Test fun cancellingThePreviewConsumesItsCandidatesWithoutImportingAnyRule() {
        write(); launch(); ready()
        compose.onNodeWithText(context.getString(R.string.cancel)).performClick()
        await { appDb.highlightRuleImportDao.get(id)?.phase=="cancelled" }
        assertTrue(appDb.highlightRuleImportDao.rows(id).isEmpty())
        assertFalse(appDb.highlightRuleDao.all.any { it.uuid in setOf(first.uuid,second.uuid) })
    }
    @Test fun backCancelsAnHttpPreviewBeforeItsDelayedResponseIsReleased() {
        val arrived=CountDownLatch(1); val release=CountDownLatch(1)
        val body=GSON.toJson(HighlightRuleFile(HighlightRuleFile.TYPE,listOf(first)))
        val server=object: NanoHTTPD("127.0.0.1",0) {
            override fun serve(session: IHTTPSession): Response {
                arrived.countDown(); release.await(30,TimeUnit.SECONDS)
                return newFixedLengthResponse(Response.Status.OK,"application/json",body)
            }
        }
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT,false)
        try {
            launch("http://127.0.0.1:${server.listeningPort}/rules")
            await { arrived.count==0L }; pressBackUnconditionally()
            await { appDb.highlightRuleImportDao.get(id)?.phase=="cancelled" }
            assertEquals(1L,release.count)
            assertFalse(appDb.highlightRuleDao.all.any { it.uuid==first.uuid })
            assertTrue(appDb.highlightRuleImportDao.rows(id).isEmpty())
        } finally { release.countDown(); server.stop() }
    }
    @Test fun theManagerPickerImportsIntoItsOwnedChildAndReturnsToTheOriginalList() {
        write(); val uri=FileProvider.getUriForFile(context,AppConst.authority,input)
        val monitor=object: Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if(intent.action!=Intent.ACTION_OPEN_DOCUMENT) return null
                return Instrumentation.ActivityResult(Activity.RESULT_OK,Intent().setData(uri))
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            scenario=ActivityScenario.launch(AppActivity.startIntent(context,HighlightRuleRoute))
            await { compose.onAllNodesWithTag("legadoTopAppBarOverflow").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("legadoTopAppBarOverflow").performClick()
            compose.onNodeWithText(context.getString(R.string.import_local)).performClick(); ready()
            var route: ImportRoute?=null
            scenario!!.onActivity { route=it.currentDestination as? ImportRoute }
            val child=checkNotNull(route); val parent=checkNotNull(child.parentManagerId)
            assertEquals(parent,appDb.highlightRuleImportDao.get(child.requestId)!!.parent)
            assertEquals(uri.toString(),child.payload)
            compose.onNodeWithTag("highlightRuleImportConfirm").performClick()
            await { compose.onAllNodesWithTag("highlightRuleImportDialog").fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodesWithTag("highlightRuleRow:${first.uuid}").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("highlightRuleRow:${second.uuid}").assertExists()
            assertEquals("completed",appDb.highlightRuleImportDao.get(child.requestId)!!.phase)
            await { !appDb.highlightRuleManagerDao.get(parent)!!.state.contains(child.requestId) }
            screenshot("highlight-import-manager-roundtrip")
        } finally { instrumentation.removeMonitor(monitor) }
    }

    @Test fun onlineHighlightLinksRestoreTheirFetchedPreviewWithoutDownloadingAgain() {
        val requests=java.util.concurrent.atomic.AtomicInteger()
        val body=GSON.toJson(HighlightRuleFile(HighlightRuleFile.TYPE,listOf(first,second)))
        val server=object: NanoHTTPD("127.0.0.1",0) {
            override fun serve(session: IHTTPSession): Response {
                requests.incrementAndGet()
                return newFixedLengthResponse(Response.Status.OK,"application/json",body)
            }
        }
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT,false)
        try {
            val link=android.net.Uri.Builder().scheme("legado").authority("import").path("/highlightRule")
                .appendQueryParameter("src","http://127.0.0.1:${server.listeningPort}/rules").build().toString()
            scenario=ActivityScenario.launch(AppActivity.startIntent(context,OnlineImportRoute(link))); ready()
            assertEquals(1,requests.get()); assertFalse(appDb.highlightRuleDao.all.any { it.uuid==first.uuid })
            scenario!!.recreate(); ready(); assertEquals(1,requests.get())
            compose.onNodeWithTag("highlightRuleImportConfirm").performClick()
            await { appDb.highlightRuleDao.all.any { it.uuid==first.uuid } && appDb.highlightRuleDao.all.any { it.uuid==second.uuid } }
            assertEquals(1,requests.get())
        } finally { server.stop() }
    }

}
