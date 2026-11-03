package io.legado.app.ui.highlight.edit

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.appDb
import io.legado.app.data.entities.HighlightRule
import io.legado.app.data.repository.HighlightRuleEditorRepository
import io.legado.app.ui.AppActivity
import io.legado.app.ui.navigation.HighlightRuleEditRoute
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class HighlightRuleEditorUiTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private val id=UUID.randomUUID().toString()
    private var rule=HighlightRule(name=id,pattern="amber",scope=id,isEnabled=false,order=25,timeoutMillisecond=31,style="{\"extension\":9,\"fill\":1}")
    private var route=HighlightRuleEditRoute(requestId=id)
    private var scenario: ActivityScenario<AppActivity>?=null
    @Before fun setup() { rule=rule.copy(id=appDb.highlightRuleDao.insert(rule).single()) }
    @After fun cleanup() {
        scenario?.close()
        appDb.highlightRuleDao.all.filter { it.uuid==rule.uuid || it.scope==id }.forEach { appDb.highlightRuleDao.delete(it) }
        appDb.highlightRuleDraftDao.delete(route.requestId)
    }
    private fun ready() { compose.waitUntil(15_000) { compose.onAllNodes(hasTestTag("highlightRuleEditPattern") and isEnabled()).fetchSemanticsNodes().isNotEmpty() } }
    private fun launch(existing: Boolean=true) {
        if(existing) route=HighlightRuleEditRoute(rule.id,id)
        scenario=ActivityScenario.launch(AppActivity.startIntent(context,route)); ready()
    }
    private fun persisted(test: () -> Boolean) { compose.waitUntil(10_000,test) }
    @Test fun metadataAndStyleDraftSurviveRecreationAndCommitWithoutResettingOtherFields() {
        launch()
        compose.onNodeWithTag("highlightRuleEditName").performTextReplacement("Changed $id")
        compose.onNodeWithTag("highlightRuleEditGroup").performTextReplacement("  New group  ")
        compose.onNodeWithTag("highlightRuleEditPattern").performTextReplacement("violet 😀")
        compose.onNodeWithTag("highlightRuleEditTitle").performScrollTo().performClick()
        compose.onNodeWithTag("highlightRuleEditStyle").performScrollTo().performClick()
        compose.onNodeWithTag("annotation_bold").performScrollTo().performClick()
        persisted { appDb.highlightRuleDraftDao.get(id)?.let { val value=HighlightRuleEditorRepository(appDb).decode(it); value.pattern=="violet 😀" && value.styleObj().bold }==true }
        scenario!!.recreate(); ready()
        compose.onNodeWithTag("highlightRuleEditName").assertTextContains("Changed $id")
        compose.onNodeWithTag("highlightRuleEditPattern").assertTextContains("violet 😀")
        compose.onNodeWithTag("highlightRuleEditSave").performClick()
        persisted { appDb.highlightRuleDraftDao.get(id)?.phase=="saved" }
        val saved=appDb.highlightRuleDao.findById(rule.id)!!
        assertEquals("New group",saved.group); assertTrue(saved.applyToTitle); assertTrue(saved.styleObj().bold)
        assertEquals(rule.uuid,saved.uuid); assertFalse(saved.isEnabled); assertEquals(25,saved.order); assertEquals(31L,saved.timeoutMillisecond)
        assertTrue(saved.style.contains("extension"))
    }
    @Test fun cancellationDiscardsDraftAndLeavesOriginalRuleUnchanged() {
        launch(); compose.onNodeWithTag("highlightRuleEditPattern").performTextReplacement("unsaved")
        compose.onNodeWithTag("highlightRuleEditCancel").performClick()
        persisted { appDb.highlightRuleDraftDao.get(id)?.phase=="cancelled" }
        assertEquals("amber",appDb.highlightRuleDao.findById(rule.id)!!.pattern)
    }
    @Test fun invalidRegexShowsAnErrorAndDoesNotCommit() {
        launch(); compose.onNodeWithTag("highlightRuleEditPattern").performTextReplacement("[")
        compose.onNodeWithTag("highlightRuleEditRegex").performScrollTo().performClick()
        compose.onNodeWithTag("highlightRuleEditSave").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("highlightRuleEditInvalid").fetchSemanticsNodes().isNotEmpty() }
        assertEquals("editing",appDb.highlightRuleDraftDao.get(id)!!.phase)
        assertEquals("amber",appDb.highlightRuleDao.findById(rule.id)!!.pattern)
    }
    @Test fun concurrentRuleChangesAreNotOverwrittenByTheEditor() {
        launch(); compose.onNodeWithTag("highlightRuleEditName").performTextReplacement("my changes")
        appDb.highlightRuleDao.update(rule.copy(name="external changes"))
        compose.onNodeWithTag("highlightRuleEditSave").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("highlightRuleEditError").fetchSemanticsNodes().isNotEmpty() }
        assertEquals("external changes",appDb.highlightRuleDao.findById(rule.id)!!.name)
        compose.onNodeWithTag("highlightRuleEditCancel").performClick()
        persisted { appDb.highlightRuleDraftDao.get(id)?.phase=="cancelled" }
    }
    @Test fun aPreparedSelectionCreatesOneRuleAndRetainsTheSourceStyle() {
        val request=runBlocking { HighlightRuleEditorRepository(appDb).prepare("selected 😀",scope=id,style="{\"extension\":7,\"bold\":true}") }
        route=HighlightRuleEditRoute(requestId=request); launch(false)
        compose.onNodeWithTag("highlightRuleEditPattern").assertTextContains("selected 😀")
        compose.onNodeWithTag("highlightRuleEditSave").performClick()
        persisted { appDb.highlightRuleDraftDao.get(request)?.phase=="saved" }
        val saved=appDb.highlightRuleDao.all.single { it.scope==id && it.uuid!=rule.uuid }
        assertEquals("selected 😀",saved.pattern); assertTrue(saved.styleObj().bold); assertTrue(saved.style.contains("extension"))
    }
    @Test fun anInvalidColorBlocksConfirmationUntilCorrected() {
        launch(); compose.onNodeWithTag("highlightRuleEditStyle").performScrollTo().performClick()
        compose.onNodeWithTag("annotation_fill_color").performScrollTo().performTextReplacement("invalid")
        compose.onNodeWithTag("highlightRuleEditSave").assertIsNotEnabled()
        compose.onNodeWithTag("annotation_fill_color").performTextReplacement("#ff00ff00")
        compose.onNodeWithTag("highlightRuleEditSave").assertIsEnabled().performClick()
        persisted { appDb.highlightRuleDraftDao.get(id)?.phase=="saved" }
        assertEquals(0xff00ff00.toInt(),appDb.highlightRuleDao.findById(rule.id)!!.styleObj().fill)
    }
}
