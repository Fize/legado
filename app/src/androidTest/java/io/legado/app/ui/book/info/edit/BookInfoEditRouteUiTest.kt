package io.legado.app.ui.book.info.edit

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.ui.AppActivity
import io.legado.app.ui.navigation.EditBookInfoRoute
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertEquals
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BookInfoEditRouteUiTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Test
    fun routeShowsEditorInsideAppActivity() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val route = EditBookInfoRoute("emulator://missing-book")

        ActivityScenario.launch<AppActivity>(AppActivity.startIntent(context, route)).use {
            composeRule.onNodeWithText(context.getString(R.string.book_info_edit))
                .assertIsDisplayed()
            composeRule.onNodeWithText(context.getString(R.string.book_name))
                .assertIsDisplayed()
            composeRule.onNodeWithText(context.getString(R.string.cover_path))
                .assertIsDisplayed()
            composeRule.onNodeWithText(context.getString(R.string.select_local_image))
                .assertIsDisplayed()
        }
    }

    @Test
    fun hiltRepositoryLoadsAndSavesBookEdits() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val book = Book(
            bookUrl = "edit-route://${UUID.randomUUID()}",
            origin = "edit-route",
            originName = "Edit route fixture",
            name = "Before edit",
            author = "Test author",
            type = BookType.text,
        )
        appDb.bookDao.insert(book)
        val scenario = ActivityScenario.launchActivityForResult<AppActivity>(
            AppActivity.startIntent(context, EditBookInfoRoute(book.bookUrl)),
        )
        try {
            composeRule.waitUntil(10_000) {
                composeRule.onAllNodesWithText("Before edit").fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText(context.getString(R.string.book_name))
                .performTextReplacement("After edit")
            composeRule.onNodeWithContentDescription(context.getString(R.string.action_save)).performClick()
            composeRule.waitUntil(10_000) { appDb.bookDao.getBook(book.bookUrl)?.name == "After edit" }
            assertEquals("After edit", appDb.bookDao.getBook(book.bookUrl)?.name)
        } finally {
            scenario.close()
            appDb.bookDao.delete(book)
        }
    }

}
