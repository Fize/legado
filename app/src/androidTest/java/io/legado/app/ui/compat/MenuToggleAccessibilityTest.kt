package io.legado.app.ui.compat

import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.ui.AppActivity
import io.legado.app.ui.compose.component.LegadoMenuAction
import io.legado.app.ui.compose.component.LegadoTopAppBar
import io.legado.app.ui.navigation.AboutRoute
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The real menu must expose its state through Android accessibility and keep one click action. */
@RunWith(AndroidJUnit4::class)
class MenuToggleAccessibilityTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val label = "Maintenance fixture action"

    private fun containsLabel(node: AccessibilityNodeInfo): Boolean {
        if (node.text?.toString() == label) return true
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            if (containsLabel(child)) return true
        }
        return false
    }

    private fun checkableAction(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isCheckable && containsLabel(node)) return node
        for (index in 0 until node.childCount) {
            checkableAction(node.getChild(index))?.let { return it }
        }
        return null
    }

    private fun exercise(initial: Boolean?) {
        var checked by mutableStateOf(initial)
        var clicks = 0
        val trace = JSONArray()
        val more = instrumentation.targetContext.getString(R.string.more_menu)
        ActivityScenario.launch<AppActivity>(AppActivity.startIntent(instrumentation.targetContext, AboutRoute)).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                MaterialTheme {
                    LegadoTopAppBar("Menu fixture", onBack = {}, menuActions = listOf(
                        LegadoMenuAction(label, checked = checked, onClick = {
                            clicks++
                            checked = checked?.not()
                        }),
                    ))
                }
            } }
            fun openAndCheck(expected: Boolean?) {
                compose.onNodeWithContentDescription(more).performClick()
                val item = compose.onNodeWithTag("legadoTopAppBarMenuAction_0")
                item.assertIsDisplayed().assertIsEnabled().assertHasClickAction()
                if (expected == null) {
                    item.assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ToggleableState))
                    assertNull(checkableAction(instrumentation.uiAutomation.rootInActiveWindow))
                    trace.put(JSONObject().put("checkable", false))
                } else {
                    item.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))
                    item.assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState(expected)))
                    var native: AccessibilityNodeInfo? = null
                    compose.waitUntil(5_000) {
                        native = checkableAction(instrumentation.uiAutomation.rootInActiveWindow)
                        native?.isChecked == expected
                    }
                    assertNotNull("Android must expose the actual menu item as checkable", native)
                    assertEquals(expected, native!!.isChecked)
                    trace.put(JSONObject().put("checkable", native!!.isCheckable)
                        .put("checked", native!!.isChecked).put("class", native!!.className.toString()))
                }
            }
            openAndCheck(initial)
            compose.onNodeWithTag("legadoTopAppBarMenuAction_0").performTouchInput { click() }
            compose.waitForIdle()
            compose.runOnIdle { assertEquals("One pointer tap must invoke the action once", 1, clicks) }
            compose.onNodeWithTag("legadoTopAppBarMenuAction_0").assertDoesNotExist()
            openAndCheck(initial?.not())
            compose.runOnIdle { assertEquals(1, clicks) }
        }
        val directory = File(instrumentation.targetContext.filesDir, "compat-menu-toggle").apply { mkdirs() }
        File(directory, (initial?.toString() ?: "ordinary") + ".json").writeText(
            JSONObject().put("snapshots", trace).put("pointer_clicks", clicks)
                .put("menu_closed_after_pointer", true).toString(),
        )
    }

    @Test fun checkedItemAnnouncesBothStatesAndInvokesOnce() = exercise(true)
    @Test fun uncheckedItemAnnouncesBothStatesAndInvokesOnce() = exercise(false)
    @Test fun ordinaryActionRemainsNonCheckableAndInvokesOnce() = exercise(null)
}
