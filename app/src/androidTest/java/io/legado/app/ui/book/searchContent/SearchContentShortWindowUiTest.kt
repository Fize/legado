package io.legado.app.ui.book.searchContent

import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.ui.AppActivity
import io.legado.app.help.book.BookHelp
import io.legado.app.ui.navigation.AboutRoute
import io.legado.app.ui.navigation.SearchContentRoute
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class SearchContentShortWindowUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun resultAnchorSurvivesChangingBetweenShortAndRegularHeight() {
        ContentSearchFixture().use { fixture ->
            BookHelp.saveText(fixture.book, fixture.chapter, (0 until 60).joinToString("\n") { "Result $it needle" })
            val height = mutableIntStateOf(600)
            ActivityScenario.launch<AppActivity>(AppActivity.startIntent(context, AboutRoute)).use { scenario ->
                scenario.onActivity { activity -> activity.setContent {
                    MaterialTheme {
                        Box(Modifier.size(width = 600.dp, height = height.intValue.dp)) {
                            SearchContentScreen(SearchContentRoute(fixture.book.bookUrl, "needle"), {}, {})
                        }
                    }
                } }
                compose.waitUntil(30_000) { compose.onAllNodesWithTag("content_search_hit_0").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("content_search_results").performScrollToIndex(30)
                compose.onNodeWithTag("content_search_hit_30").assertIsDisplayed()
                compose.runOnIdle { height.intValue = 220 }
                compose.onNodeWithTag("content_search_hit_30").assertIsDisplayed()
                compose.runOnIdle { height.intValue = 600 }
                compose.onNodeWithTag("content_search_hit_30").assertIsDisplayed()
            }
        }
    }

    @Test fun resultsAndQueryRemainReachableInShortWindow() {
        ContentSearchFixture().use { fixture ->
            val result = AtomicReference<Intent?>()
            ActivityScenario.launch<AppActivity>(AppActivity.startIntent(context, AboutRoute)).use { scenario ->
                scenario.onActivity { activity -> activity.setContent {
                    MaterialTheme {
                        Box(Modifier.size(width = 600.dp, height = 220.dp)) {
                            SearchContentScreen(SearchContentRoute(fixture.book.bookUrl, "needle"), {}, result::set)
                        }
                    }
                } }
                compose.waitUntil(30_000) {
                    compose.onAllNodesWithTag("content_search_hit_0").fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithTag("content_search_hit_0").assertIsDisplayed()
                compose.onNodeWithTag("content_search_results").performScrollToNode(hasTestTag("content_search_query"))
                compose.onNodeWithTag("content_search_query").assertIsDisplayed().assertTextContains("needle")
                compose.onNodeWithTag("content_search_results").performScrollToNode(hasTestTag("content_search_hit_0"))
                compose.onNodeWithTag("content_search_hit_0").assertIsDisplayed().performClick()
                compose.waitUntil(10_000) { result.get() != null }
                assertNotNull(result.get()?.getStringExtra("contentSearchSessionId"))
            }
        }
    }
}
