package io.legado.app.ui.highlight

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.data.entities.HighlightRule
import io.legado.app.ui.AppActivity
import io.legado.app.ui.navigation.HighlightRuleRoute
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class HighlightRuleManagerUiTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private val group="Group-${UUID.randomUUID()}"
    private var rule=HighlightRule(name=group,pattern="amber",group=group)
    private var scenario: ActivityScenario<AppActivity>?=null
    private val artifacts=HighlightManagerArtifacts()
    @Before fun setup() {
        rule=rule.copy(id=appDb.highlightRuleDao.insert(rule).single())
        scenario=ActivityScenario.launch(AppActivity.startIntent(context,HighlightRuleRoute))
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("legadoTopAppBarOverflow").fetchSemanticsNodes().isNotEmpty() }
    }
    @After fun cleanup() {
        scenario?.close(); artifacts.cleanup()
        appDb.highlightRuleDao.findById(rule.id)?.takeIf { it.uuid==rule.uuid }?.let(appDb.highlightRuleDao::delete)
        appDb.highlightRuleDao.all.filter { it.group==group && it.uuid!=rule.uuid }.forEach(appDb.highlightRuleDao::delete)
    }
    private fun menu(res: Int) { compose.onNodeWithTag("legadoTopAppBarOverflow").performClick(); compose.onNodeWithText(context.getString(res)).performClick() }
    private fun filter() {
        menu(R.string.highlight_rule_group_filter)
        compose.onNodeWithTag("highlightChoiceList:filter").performScrollToNode(hasTestTag("highlightChoice:filter:group:$group"))
        compose.onNodeWithTag("highlightChoice:filter:group:$group").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("highlightRuleRow:${rule.uuid}").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun groups() {
        menu(R.string.highlight_rule_group_manage)
        compose.onNodeWithTag("highlightGroupList").performScrollToNode(hasTestTag("highlightGroupRow:$group"))
    }
    @Test fun aNamedFilterAndRowSelectionSurviveActivityRecreation() {
        filter(); compose.onNodeWithTag("highlightRuleRow:${rule.uuid}").performClick()
        val checkbox=SemanticsMatcher.expectValue(SemanticsProperties.Role,Role.Checkbox)
        compose.onNodeWithTag("highlightRuleRow:${rule.uuid}").onChildren().filter(checkbox).onFirst().assertIsOn()
        scenario!!.recreate()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("highlightRuleRow:${rule.uuid}").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("highlightRuleRow:${rule.uuid}").onChildren().filter(checkbox).onFirst().assertIsOn()
        assertEquals(group,appDb.highlightRuleDao.findById(rule.id)!!.group)
    }
    @Test fun renameTextAndTheComposeGroupManagerRestoreWithoutSavingTheRule() {
        groups(); compose.onNodeWithTag("highlightGroupEdit:$group").performClick()
        compose.onNodeWithTag("highlightGroupName").performTextReplacement("Uncommitted $group")
        compose.waitForIdle()
        // Wait for the exact own draft to reach Room before recreating the Activity.
        compose.waitUntil(10_000) {
            appDb.openHelper.readableDatabase.query("SELECT state FROM highlight_rule_manager_states WHERE state LIKE ?",arrayOf("%Uncommitted $group%"))
                .use { it.moveToFirst() }
        }
        scenario!!.recreate()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("highlightGroupName").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("highlightGroupName").assertTextContains("Uncommitted $group")
        compose.onNodeWithText(context.getString(R.string.cancel)).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("highlightManagerDialog").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("highlightGroupManager").assertExists()
        assertEquals(group,appDb.highlightRuleDao.findById(rule.id)!!.group)
    }
    @Test fun anImportedMemberMakesTheOldGroupDeleteFailWithoutDeletingEitherRule() {
        groups(); compose.onNodeWithTag("highlightGroupDelete:$group").performClick()
        val added=HighlightRule(name="Imported $group",pattern="new",group=group)
        val addedId=appDb.highlightRuleDao.insert(added).single()
        compose.onNodeWithTag("highlightManagerConfirm").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("highlightManagerError").fetchSemanticsNodes().isNotEmpty() }
        assertNotNull(appDb.highlightRuleDao.findById(rule.id)); assertNotNull(appDb.highlightRuleDao.findById(addedId))
        assertEquals(group,appDb.highlightRuleDao.findById(rule.id)!!.group)
    }
    @Test fun aRuleSwitchUpdatesRoomAndRestoresTheSameVisibleState() {
        filter(); compose.onNodeWithTag("highlightRuleEnabled:${rule.uuid}").performClick()
        compose.waitUntil(10_000) { appDb.highlightRuleDao.findById(rule.id)?.isEnabled==false }
        compose.onNodeWithTag("highlightRuleEnabled:${rule.uuid}").assertIsOff()
        scenario!!.recreate()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("highlightRuleEnabled:${rule.uuid}").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("highlightRuleEnabled:${rule.uuid}").assertIsOff()
    }
}
