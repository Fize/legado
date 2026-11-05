package io.legado.app.ui.book.info

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import io.legado.app.ui.AppActivity
import io.legado.app.ui.navigation.AboutRoute
import org.junit.After
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.ui.theme.LegadoTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BookInfoScreenUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private var scenario: ActivityScenario<AppActivity>? = null
    @After fun close() { scenario?.close() }
    private val actions = mutableListOf<BookInfoUiAction>()
    private val book = Book(bookUrl = "https://book-info-screen.invalid/book", name = "Standalone details",
        author = "Details author", totalChapterNum = 2, originName = "Details source")
    private val state = BookInfoUiState(book = book, chapters = listOf(BookChapter(title = "First chapter")),
        source = BookSource("https://book-info-screen.invalid", "Details source"), kinds = listOf("Screen kind"))
    private fun show(state: BookInfoUiState = this.state) {
        scenario = ActivityScenario.launch(AppActivity.startIntent(context, AboutRoute))
        scenario!!.onActivity { activity -> activity.setContent {
            LegadoTheme { BookInfoScreen(state, false, actions::add) }
        } }
        compose.waitForIdle()
    }
    private fun text(id: Int): String {
        var value = context.getString(id)
        scenario?.onActivity { value = it.getString(id) }
        return value
    }

    @Test fun footerAndMetadataEmitTypedIntentWithoutAViewModel() {
        show()
        compose.onNodeWithText(text(R.string.add_to_bookshelf)).performClick()
        compose.onNodeWithText(text(R.string.start_read)).performClick()
        compose.onNodeWithText(text(R.string.view_toc)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.change_origin)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.change_group)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(BookInfoUiAction.Shelf, BookInfoUiAction.Read,
            BookInfoUiAction.Toc, BookInfoUiAction.ChangeSource, BookInfoUiAction.Group), actions) }
    }

    @Test fun titleAuthorAndKindKeepDistinctClickAndLongClickEvents() {
        show()
        compose.onNodeWithText(book.name).performClick()
        compose.onNodeWithText(book.name).performTouchInput { longClick() }
        val author = context.getString(R.string.author_show, book.author)
        compose.onNodeWithText(author).performClick()
        compose.onNodeWithText(author).performTouchInput { longClick() }
        compose.onNodeWithText("Screen kind").performScrollTo().performClick()
        compose.onNodeWithText("Screen kind").performTouchInput { longClick() }
        compose.runOnIdle { assertEquals(listOf(
            BookInfoUiAction.Callback(BookInfoCallbackEvent.Name), BookInfoUiAction.Callback(BookInfoCallbackEvent.LongName),
            BookInfoUiAction.Callback(BookInfoCallbackEvent.Author), BookInfoUiAction.Callback(BookInfoCallbackEvent.LongAuthor),
            BookInfoUiAction.Callback(BookInfoCallbackEvent.Kind, "Screen kind"),
            BookInfoUiAction.Callback(BookInfoCallbackEvent.LongKind, "Screen kind")), actions) }
    }

    @Test fun overflowClosesAfterEmittingTheCopyIntent() {
        show()
        compose.onNodeWithContentDescription(text(R.string.more_menu)).performClick()
        compose.onNodeWithText(text(R.string.copy_book_url)).performClick()
        compose.onNodeWithText(text(R.string.copy_book_url)).assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf(BookInfoUiAction.Callback(BookInfoCallbackEvent.CopyBookUrl)), actions) }
    }

    @Test fun localMetadataRemainsVisibleWithoutASource() {
        show(state.copy(source = null, kinds = listOf("42 MB")))
        compose.onNodeWithText("42 MB").performScrollTo().assertIsDisplayed()
    }

    @Test fun mutationDisablesBothFooterActions() {
        show(state.copy(shelfMutation = true))
        compose.onNodeWithText(text(R.string.add_to_bookshelf)).assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.start_read)).assertIsNotEnabled()
        compose.runOnIdle { assertEquals(emptyList<BookInfoUiAction>(), actions) }
    }

    @Test fun pendingDeletionEmitsCancellationForItsStableCommandId() {
        show(state.copy(pendingDelete = io.legado.app.data.repository.BookInfoPendingDelete("pending-delete", book.bookUrl,
            io.legado.app.data.entities.BookInfoDeleteStatus.Queued, true)))
        compose.onNodeWithTag("book_delete_pending").assertIsDisplayed()
        compose.onNodeWithTag("book_delete_cancel").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf(BookInfoUiAction.CancelDelete("pending-delete")), actions) }
    }

    @Test fun cancellationIsDisabledWhileSubmitting() {
        show(state.copy(cancellingDelete = true, pendingDelete = io.legado.app.data.repository.BookInfoPendingDelete("pending-delete", book.bookUrl,
            io.legado.app.data.entities.BookInfoDeleteStatus.Running, true)))
        compose.onNodeWithTag("book_delete_cancel").assertIsNotEnabled()
    }

    @Test fun cancellationIsUnavailableAfterDatabaseRemoval() {
        show(state.copy(pendingDelete = io.legado.app.data.repository.BookInfoPendingDelete("pending-delete", book.bookUrl,
            io.legado.app.data.entities.BookInfoDeleteStatus.Running, false)))
        compose.onNodeWithTag("book_delete_pending").assertIsDisplayed()
        compose.onNodeWithTag("book_delete_cancel").assertDoesNotExist()
    }

    @Test fun pendingUploadEmitsCancellationForTheCurrentCommandOnly() {
        show(state.copy(pendingUpload = io.legado.app.data.repository.BookInfoPendingUpload("upload-command", book.bookUrl,
            io.legado.app.data.entities.BookInfoUploadStatus.Queued)))
        compose.onNodeWithTag("book_upload_pending").assertIsDisplayed()
        compose.onNodeWithTag("book_upload_cancel").performClick()
        compose.runOnIdle { assertEquals(listOf(BookInfoUiAction.CancelUpload("upload-command")), actions) }
    }

    @Test fun uploadCancellationIsDisabledWhileSubmitting() {
        show(state.copy(cancellingUpload = true, pendingUpload = io.legado.app.data.repository.BookInfoPendingUpload("upload-command", book.bookUrl,
            io.legado.app.data.entities.BookInfoUploadStatus.Running)))
        compose.onNodeWithTag("book_upload_cancel").assertIsNotEnabled()
    }
    @Test fun primaryActionAddsBookWhenNotOnShelf() {
        show(state.copy(inBookshelf = false))
        compose.onNodeWithTag("book-info-primary").performClick()
        compose.runOnIdle { assertEquals(listOf(BookInfoUiAction.Shelf), actions) }
    }

    @Test fun primaryActionStartsUnreadBook() {
        show(state.copy(inBookshelf = true))
        compose.onNodeWithTag("book-info-primary").assertTextContains(text(R.string.start_read)).performClick()
        compose.runOnIdle { assertEquals(listOf(BookInfoUiAction.Read), actions) }
    }

    @Test fun primaryActionContinuesBookWithProgress() {
        show(state.copy(inBookshelf = true, book = book.copy(durChapterPos = 10)))
        compose.onNodeWithTag("book-info-primary").assertTextContains(text(R.string.continue_read)).performClick()
        compose.runOnIdle { assertEquals(listOf(BookInfoUiAction.Read), actions) }
    }

}
