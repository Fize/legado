package io.legado.app.ui.main.bookshelf

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.ui.AppActivity
import io.legado.app.ui.navigation.BookshelfSearchRoute
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BookshelfSearchUiTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val key = UUID.randomUUID().toString().take(8)
    private val shelf = Book(bookUrl = "shelf-search://$key", name = "Shelf $key", author = "Author", origin = BookType.localTag, type = BookType.local or BookType.text)
    private val preview = shelf.copy(bookUrl = "preview-search://$key", name = "Preview $key", type = shelf.type or BookType.notShelf)
    @get:Rule val compose = createEmptyComposeRule()
    @Before fun insert() { appDb.bookDao.insert(shelf); appDb.bookDao.insert(preview) }
    @After fun cleanup() { appDb.bookDao.delete(shelf); appDb.bookDao.delete(preview) }

    @Test fun searchesOnlyBooksOnShelfAndRestoresQueryAfterDetails() {
        ActivityScenario.launch<AppActivity>(AppActivity.startIntent(context, BookshelfSearchRoute)).use { scenario ->
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("shelf-search-query").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("shelf-search-query").performTextInput(key)
            compose.waitUntil(10_000) { compose.onAllNodesWithText(shelf.name).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(preview.name).assertDoesNotExist()
            compose.onNodeWithText(shelf.name).performClick()
            compose.waitUntil(10_000) { var detail = false; scenario.onActivity { detail = it.currentDestination is io.legado.app.ui.navigation.BookInfoRoute }; detail }
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("shelf-search-query").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("shelf-search-query").assertTextContains(key)
            compose.onNodeWithText(shelf.name).assertIsDisplayed()
        }
    }
    @Test fun returningFromDetailsRestoresResultPosition() {
        val books = (0..24).map { index -> shelf.copy(bookUrl = "shelf-scroll://$key/$index", name = "Result $key $index", durChapterTime = index.toLong()) }
        books.forEach(appDb.bookDao::insert)
        try {
            ActivityScenario.launch<AppActivity>(AppActivity.startIntent(context, BookshelfSearchRoute)).use { scenario ->
                compose.waitUntil(10_000) { compose.onAllNodesWithTag("shelf-search-query").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("shelf-search-query").performTextInput("Result $key")
                compose.waitUntil(10_000) { compose.onAllNodesWithTag("shelf-search-results").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("shelf-search-results").performScrollToIndex(20)
                compose.onNodeWithText("Result $key 4").performClick()
                compose.waitUntil(10_000) { var detail = false; scenario.onActivity { detail = it.currentDestination is io.legado.app.ui.navigation.BookInfoRoute }; detail }
                scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
                compose.waitUntil(10_000) { compose.onAllNodesWithTag("shelf-search-results").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("Result $key 4").assertIsDisplayed()
            }
        } finally { books.forEach(appDb.bookDao::delete) }
    }

    @Test fun noResultsCanBeCleared() {
        ActivityScenario.launch<AppActivity>(AppActivity.startIntent(context, BookshelfSearchRoute)).use {
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("shelf-search-query").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("shelf-search-query").performTextInput("missing-$key")
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("shelf-search-empty").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("shelf-search-clear").performClick()
            compose.onNodeWithTag("shelf-search-empty").assertDoesNotExist()
        }
    }

    @Test fun discoveryProvidesOnlineSearch() {
        ActivityScenario.launch<AppActivity>(AppActivity.startIntent(context, io.legado.app.ui.navigation.BookshelfRoute)).use { scenario ->
            scenario.onActivity { it.navigateTo(io.legado.app.ui.navigation.ExploreRoute) }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("explore-search-books").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("explore-search-books").performClick()
            compose.waitUntil(10_000) { var opened = false; scenario.onActivity { opened = it.currentDestination is io.legado.app.ui.navigation.SearchRoute }; opened }
        }
    }

}
