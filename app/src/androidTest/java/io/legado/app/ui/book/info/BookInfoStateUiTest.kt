package io.legado.app.ui.book.info

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BookInfoStateUiTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Test
    fun addingBookUpdatesComposeFromViewModelState() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val book = Book(
            bookUrl = "book-info-state://${UUID.randomUUID()}",
            origin = "book-info-state",
            originName = "Book info state fixture",
            name = "Book info StateFlow fixture",
            author = "Test author",
            type = BookType.text or BookType.notShelf,
        )
        appDb.bookDao.insert(book)

        try {
            ActivityScenario.launch<BookInfoActivity>(
                Intent(context, BookInfoActivity::class.java).putExtra("bookUrl", book.bookUrl),
            ).use { scenario ->
                var addLabel = ""
                var removeLabel = ""
                var tocErrorLabel = ""
                scenario.onActivity { activity ->
                    addLabel = activity.getString(R.string.add_to_bookshelf)
                    removeLabel = activity.getString(R.string.remove_from_bookshelf)
                    tocErrorLabel = activity.getString(R.string.error_load_toc)
                }
                composeRule.onNodeWithText(book.name).assertIsDisplayed()
                composeRule.onNodeWithText(tocErrorLabel, substring = true).assertIsDisplayed()
                composeRule.onNodeWithText(addLabel)
                    .performClick()
                composeRule.waitUntil(10_000) {
                    composeRule.onAllNodesWithText(removeLabel)
                        .fetchSemanticsNodes().isNotEmpty()
                }
                composeRule.onNodeWithText(removeLabel)
                    .assertIsDisplayed()
            }
        } finally {
            appDb.bookDao.delete(book)
        }
    }

    @Test
    fun introExpansionIsDrivenByViewModelState() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val book = Book(
            bookUrl = "book-info-intro://${UUID.randomUUID()}",
            origin = "book-info-intro",
            originName = "Book info intro fixture",
            name = "Book info intro StateFlow fixture",
            author = "Test author",
            intro = (1..16).joinToString("\n") { "Introduction line $it with enough content to wrap across the screen." },
            type = BookType.text or BookType.notShelf,
        )
        appDb.bookDao.insert(book)

        try {
            var collapseLabel = ""
            var expandLabel = ""
            ActivityScenario.launch<BookInfoActivity>(
                Intent(context, BookInfoActivity::class.java).putExtra("bookUrl", book.bookUrl),
            ).use { scenario ->
                scenario.onActivity { activity ->
                    collapseLabel = activity.getString(R.string.book_intro_collapse)
                    expandLabel = activity.getString(R.string.book_intro_expand)
                }
                composeRule.waitUntil(10_000) {
                    composeRule.onAllNodesWithText(collapseLabel)
                        .fetchSemanticsNodes().isNotEmpty()
                }
                composeRule.onNodeWithText(collapseLabel).performScrollTo().assertIsDisplayed().performClick()
                composeRule.waitUntil(5_000) {
                    runCatching { composeRule.onNodeWithText(expandLabel).assertIsDisplayed(); true }.getOrDefault(false)
                }
                scenario.recreate()
                composeRule.waitUntil(10_000) { composeRule.onAllNodesWithText(expandLabel).fetchSemanticsNodes().isNotEmpty() }
                composeRule.onNodeWithText(expandLabel).performScrollTo().assertIsDisplayed().performClick()
                composeRule.onNodeWithText(collapseLabel).performScrollTo().assertIsDisplayed()
            }
        } finally {
            appDb.bookDao.delete(book)
        }
    }
}
