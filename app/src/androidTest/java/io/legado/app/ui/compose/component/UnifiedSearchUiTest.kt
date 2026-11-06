package io.legado.app.ui.compose.component

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.ui.AppActivity
import io.legado.app.ui.navigation.AboutRoute
import io.legado.app.ui.theme.LegadoTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UnifiedSearchUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun expandedFilterCanBeClearedAndClosed() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ActivityScenario.launch<AppActivity>(AppActivity.startIntent(context, AboutRoute)).use { scenario ->
            var clearLabel = ""
            scenario.onActivity { activity ->
                clearLabel = activity.getString(R.string.clear)
                activity.setContent { LegadoTheme {
                    var query by remember { mutableStateOf("") }
                    LegadoTopAppBar(title = "Fixture", searchQuery = query, onSearchQueryChange = { query = it }, modernSearch = true)
                } }
            }
            compose.onNodeWithTag("legadoTopAppBarSearch").performClick()
            compose.onNodeWithTag("unified_search_field").performTextInput("sample")
            compose.onNodeWithTag("unified_search_field").performImeAction()
            compose.onNodeWithTag("unified_search_field").assertIsNotFocused()
            compose.onNodeWithContentDescription(clearLabel).performClick()
            compose.onNodeWithTag("unified_search_field").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
            compose.onNodeWithTag("legadoTopAppBarNavigation").performClick()
            compose.onNodeWithText("Fixture").assertIsDisplayed()
        }
    }
    @Test fun narrowToolbarKeepsSearchAndActionsUsable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ActivityScenario.launch<AppActivity>(AppActivity.startIntent(context, AboutRoute)).use { scenario ->
            var density = 1f
            scenario.onActivity { activity -> activity.setContent { LegadoTheme {
                density = androidx.compose.ui.platform.LocalDensity.current.density
                var query by remember { mutableStateOf("") }
                androidx.compose.foundation.layout.Box(Modifier.width(320.dp)) {
                    LegadoTopAppBar(title = "Fixture", searchQuery = query, onSearchQueryChange = { query = it }, modernSearch = true, actions = {
                        repeat(3) { androidx.compose.material3.TextButton(onClick = {}, modifier = Modifier.testTag("action-$it")) { androidx.compose.material3.Text("Action") } }
                    })
                }
            } } }
            compose.onNodeWithTag("legadoTopAppBarSearch").performClick()
            val width = compose.onNodeWithTag("unified_search_field").fetchSemanticsNode().boundsInRoot.width / density
            org.junit.Assert.assertTrue("Search input is too narrow: $width dp", width >= 180f)
            repeat(3) { compose.onNodeWithTag("action-$it").assertIsDisplayed() }
        }
    }

}
