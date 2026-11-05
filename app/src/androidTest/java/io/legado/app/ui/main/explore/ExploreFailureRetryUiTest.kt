package io.legado.app.ui.main.explore

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.rule.ExploreRule
import io.legado.app.help.CacheManager
import io.legado.app.help.source.clearExploreKindsCache
import io.legado.app.ui.AppActivity
import io.legado.app.ui.navigation.BookshelfRoute
import io.legado.app.ui.navigation.ExploreRoute
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ExploreFailureRetryUiTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val id = UUID.randomUUID().toString()
    private val source = BookSource(bookSourceUrl = "https://retry.invalid/$id", bookSourceName = "Retry source $id",
        exploreUrl = "@js: if (!source.getLoginHeader()) throw new Error('fixture error'); 'Recovered::https://example.invalid'",
        ruleExplore = ExploreRule(bookList = "tag.a", name = "text", bookUrl = "href"))
    @get:Rule val compose = createEmptyComposeRule()
    @Before fun setup() { appDb.bookSourceDao.insert(source) }
    @After fun cleanup() {
        runBlocking { source.clearExploreKindsCache() }
        CacheManager.delete("loginHeader_${source.getKey()}")
        appDb.bookSourceDao.delete(source.bookSourceUrl)
    }
    @Test fun failedSourceCanBeRetriedInPlace() {
        ActivityScenario.launch<AppActivity>(AppActivity.startIntent(context, BookshelfRoute)).use { scenario ->
            scenario.onActivity { it.navigateTo(ExploreRoute) }
            compose.waitUntil(30_000) { compose.onAllNodesWithText(source.bookSourceName).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(source.bookSourceName).performClick()
            compose.waitUntil(30_000) { compose.onAllNodesWithTag("explore-source-error:${source.bookSourceUrl}").fetchSemanticsNodes().isNotEmpty() }
            Thread.sleep(500)
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let { bitmap ->
                java.io.FileOutputStream(java.io.File(context.getExternalFilesDir(null), "stage4-explore-error.png")).use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            compose.onNodeWithTag("explore-source-details:${source.bookSourceUrl}").performClick()
            compose.onNodeWithText("fixture error", substring = true).assertIsDisplayed()
            compose.onNodeWithTag("explore-error-details-close").performClick()
            source.putLoginHeader("ready")
            compose.onNodeWithTag("explore-source-retry:${source.bookSourceUrl}").performClick()
            compose.waitUntil(30_000) { compose.onAllNodesWithText("Recovered").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Recovered").assertIsDisplayed()
        }
    }
}
