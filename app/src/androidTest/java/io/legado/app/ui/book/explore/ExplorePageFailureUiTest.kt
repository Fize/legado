package io.legado.app.ui.book.explore

import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.ui.AppActivity
import io.legado.app.ui.navigation.AboutRoute
import io.legado.app.ui.theme.LegadoTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExplorePageFailureUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun failedPageShowsCauseAndRetry() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var retried = false
        var retryText = ""
        ActivityScenario.launch<AppActivity>(AppActivity.startIntent(context, AboutRoute)).use { scenario ->
            scenario.onActivity { activity ->
                retryText = activity.getString(R.string.retry)
                activity.setContent { LegadoTheme { ExploreShowScreen(
                    title = "Discovery", onBack = {}, page = 1, oldPage = 1, showCategories = false,
                    categories = emptyList(), selectedCategory = null, addBooksBusy = false,
                    books = emptyList(), topLoading = false, topError = null, bottomLoading = false,
                    bottomHasMore = false, bottomError = "Fixture connection failure", adapterRefreshVersion = 0,
                    isInBookshelf = { false }, onToggleCategories = {}, onAddLoadedBooks = {},
                    onChangePageClick = {}, onSelectCategory = {}, onLoadBottom = { retried = it },
                    onLoadTop = {}, onBookClick = {},
                ) } }
            }
            compose.onNodeWithText("Fixture connection failure").assertIsDisplayed()
            compose.onNodeWithText(retryText).performClick()
            compose.runOnIdle { assertTrue(retried) }
        }
    }
}
