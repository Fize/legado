package io.legado.app.ui.book.searchContent

import android.app.Activity
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.appDb
import io.legado.app.ui.AppActivity
import io.legado.app.ui.navigation.SearchContentRoute
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SearchContentRouteUiTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private fun result()=compose.onNodeWithTag("content_search_hit_0")
    private fun ready() { compose.waitUntil(15_000) { compose.onAllNodesWithTag("content_search_hit_0").fetchSemanticsNodes().size==1 }; compose.waitForIdle() }
    @Test fun routeRestoresQueryAndReturnsOnlyPersistentResultKeys() {
        ContentSearchFixture().use { fixture ->
            ActivityScenario.launchActivityForResult<AppActivity>(AppActivity.startIntent(context,SearchContentRoute(fixture.book.bookUrl,"needle"))).use { scenario ->
                ready(); result().assertTextContains("needle",substring=true); scenario.recreate(); ready()
                compose.onNodeWithTag("content_search_query").assertTextContains("needle")
                result().performClick()
                try {
                    compose.waitUntil(10_000) { scenario.state==androidx.lifecycle.Lifecycle.State.DESTROYED }
                } catch(error: Throwable) {
                    println("SEARCH_RESULT_STATE ${scenario.state}")
                    println(runCatching { compose.onRoot(useUnmergedTree=true).printToString().take(6000) }.getOrDefault("Activity composition already removed"))
                    throw error
                }
                if(scenario.state!=androidx.lifecycle.Lifecycle.State.DESTROYED) {
                    fail("Search did not finish: "+compose.onRoot(useUnmergedTree=true).printToString().take(6000))
                }
                assertEquals(Activity.RESULT_OK,scenario.result.resultCode)
                val data=checkNotNull(scenario.result.resultData)
                val id=checkNotNull(data.getStringExtra("contentSearchSessionId"))
                assertEquals(setOf("contentSearchSessionId","contentSearchOrdinal"),data.extras!!.keySet())
                assertEquals(0,data.getIntExtra("contentSearchOrdinal",-1))
                assertEquals("needle",appDb.bookContentSearchDao.get(id)!!.query)
            }
        }
    }
    @Test fun anUnrecreatedRootSearchReturnsItsPersistentSelection() {
        ContentSearchFixture().use { fixture ->
            ActivityScenario.launchActivityForResult<AppActivity>(AppActivity.startIntent(context,SearchContentRoute(fixture.book.bookUrl,"needle"))).use { scenario ->
                ready(); result().performClick()
                compose.waitUntil(10_000) { scenario.state==androidx.lifecycle.Lifecycle.State.DESTROYED }
                assertEquals(androidx.lifecycle.Lifecycle.State.DESTROYED,scenario.state)
                assertEquals(Activity.RESULT_OK,scenario.result.resultCode)
            }
        }
    }
    @Test fun retryCanReloadABookThatWasMissingAtInitialization() {
        ContentSearchFixture().use { fixture ->
            appDb.bookDao.delete(fixture.book)
            ActivityScenario.launch<AppActivity>(AppActivity.startIntent(context,SearchContentRoute(fixture.book.bookUrl,"needle"))).use {
                compose.waitUntil(10_000) { compose.onAllNodesWithTag("content_search_error").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("content_search_query").assertTextContains("needle")
                appDb.bookDao.insert(fixture.book); appDb.bookChapterDao.insert(fixture.chapter)
                io.legado.app.help.book.BookHelp.saveText(fixture.book,fixture.chapter,"prefix needle suffix")
                compose.onNodeWithText(context.getString(io.legado.app.R.string.retry)).performClick(); ready()
                compose.onNodeWithTag("content_search_error").assertDoesNotExist()
            }
        }
    }
    @Test fun anOversizedInitialQueryCanBeCorrectedWithoutLosingTheSearchAction() {
        ContentSearchFixture().use { fixture ->
            ActivityScenario.launch<AppActivity>(AppActivity.startIntent(context,SearchContentRoute(fixture.book.bookUrl,"x".repeat(2049)))).use {
                compose.waitUntil(10_000) { compose.onAllNodesWithTag("content_search_error").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("content_search_query").performTextReplacement("needle")
                compose.onNodeWithTag("content_search_submit").performClick(); ready()
                compose.onNodeWithTag("content_search_error").assertDoesNotExist()
            }
        }
    }
    @Test fun resultCardsRespectPaneWidthAndHaveAccessibleTouchTargets() {
        ContentSearchFixture().use { fixture ->
            ActivityScenario.launch<AppActivity>(AppActivity.startIntent(context,SearchContentRoute(fixture.book.bookUrl,"needle"))).use {
                ready(); result().assertHasClickAction().assertHeightIsAtLeast(48.dp)
                val card=result().fetchSemanticsNode().boundsInRoot
                val pane=compose.onNodeWithTag("content_search_results").fetchSemanticsNode().boundsInRoot
                assertTrue(card.width>pane.width*.8f && card.left>=pane.left && card.right<=pane.right)
                val screenshot=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                try {
                    java.io.File(context.cacheDir,"compose-content-search.png").outputStream().use {
                        assertTrue(screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it))
                    }
                } finally { screenshot.recycle() }
            }
        }
    }
    @Test fun resultPagesRemainBoundedAndTheSelectedPageSurvivesRecreation() {
        ContentSearchFixture().use { fixture ->
            io.legado.app.help.book.BookHelp.saveText(fixture.book,fixture.chapter,"needle ".repeat(240))
            ActivityScenario.launch<AppActivity>(AppActivity.startIntent(context,SearchContentRoute(fixture.book.bookUrl,"needle"))).use { scenario ->
                compose.waitUntil(15_000) { compose.onAllNodesWithTag("content_search_count").fetchSemanticsNodes().any {
                    it.config[androidx.compose.ui.semantics.SemanticsProperties.Text].single().text.endsWith(": 240") } }
                compose.onNodeWithText(context.getString(io.legado.app.R.string.next_page)).performClick()
                compose.waitUntil(10_000) { compose.onAllNodesWithTag("content_search_hit_100").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("content_search_hit_0").assertDoesNotExist()
                compose.onNodeWithTag("content_search_results").performScrollToIndex(10)
                compose.onNodeWithTag("content_search_hit_110").assertIsDisplayed()
                scenario.recreate()
                compose.waitUntil(10_000) { compose.onAllNodesWithTag("content_search_hit_110").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("content_search_hit_110").assertIsDisplayed()
            }
        }
    }
    @Test fun invalidRegexShowsAnErrorAndTheQueryCanBeCorrected() {
        ContentSearchFixture().use { fixture ->
            ActivityScenario.launch<AppActivity>(AppActivity.startIntent(context,SearchContentRoute(fixture.book.bookUrl,"needle"))).use {
                ready(); compose.onNodeWithText(context.getString(io.legado.app.R.string.regex)).performClick()
                compose.onNodeWithTag("content_search_query").performTextReplacement("(")
                compose.onNodeWithTag("content_search_submit").performClick()
                compose.waitUntil(10_000) { compose.onAllNodesWithTag("content_search_error").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("content_search_query").performTextReplacement("needle")
                compose.onNodeWithTag("content_search_submit").performClick(); ready()
                compose.onNodeWithTag("content_search_error").assertDoesNotExist()
            }
        }
    }
}
