package io.legado.app.ui.book.toc

import android.app.Activity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookHighlight
import io.legado.app.data.entities.Bookmark
import io.legado.app.data.entities.TxtTocRule
import io.legado.app.ui.AppActivity
import io.legado.app.ui.navigation.BookTocRoute
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.io.File

@RunWith(AndroidJUnit4::class)
class BookTocRouteUiTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val book = Book(
        bookUrl = "toc-route://${UUID.randomUUID()}",
        origin = BookType.localTag,
        originName = "TOC route fixture.txt",
        name = "TOC fixture book",
        author = "Fixture author",
        totalChapterNum = 2,
        type = BookType.local or BookType.text,
    )
    private val chapters = listOf(
        BookChapter(bookUrl = book.bookUrl, url = "fixture-0", title = "Fixture chapter one", index = 0),
        BookChapter(bookUrl = book.bookUrl, url = "fixture-1", title = "Fixture chapter two", index = 1),
    )

    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Before
    fun insertBook() {
        appDb.bookDao.insert(book)
        appDb.bookChapterDao.insert(*chapters.toTypedArray())
    }

    @After
    fun removeBook() {
        appDb.bookmarkDao.getByBook(book.name, book.author).forEach(appDb.bookmarkDao::delete)
        appDb.bookHighlightDao.getByBook(book.bookUrl).forEach(appDb.bookHighlightDao::delete)
        appDb.bookChapterDao.delByBook(book.bookUrl)
        appDb.bookDao.delete(book)
    }

    @Test
    fun routeDisplaysChaptersAndReturnsTheSelectedReaderPosition() {
        val scenario = ActivityScenario.launchActivityForResult<AppActivity>(
            AppActivity.startIntent(context, BookTocRoute(book.bookUrl)),
        )

        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText(chapters[1].title).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(chapters[0].title).assertIsDisplayed()
        composeRule.onNodeWithText(chapters[1].title).performClick()

        assertEquals(Activity.RESULT_OK, scenario.result.resultCode)
        val data = requireNotNull(scenario.result.resultData)
        assertEquals(1, data.getIntExtra("index", -1))
        assertEquals(0, data.getIntExtra("chapterPos", -1))
        scenario.close()
    }

    @Test
    fun chapterMenuReversesAndPersistsDisplayOrder() {
        val scenario = ActivityScenario.launch<AppActivity>(
            AppActivity.startIntent(context, BookTocRoute(book.bookUrl)),
        )

        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText(chapters[1].title).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("legadoTopAppBarOverflow").performClick()
        composeRule.onNodeWithText(context.getString(io.legado.app.R.string.reverse_toc)).performClick()
        composeRule.waitUntil(10_000) { appDb.bookDao.getBook(book.bookUrl)?.getReverseTocDisplay() == true }

        val firstPosition = composeRule.onNodeWithText(chapters[0].title).fetchSemanticsNode().boundsInRoot.top
        val secondPosition = composeRule.onNodeWithText(chapters[1].title).fetchSemanticsNode().boundsInRoot.top
        assertEquals(true, secondPosition < firstPosition)
        scenario.close()
    }

    @Test
    fun bookmarkLongPressEditsAndPersistsTheNote() {
        val bookmark = Bookmark(
            bookName = book.name,
            bookAuthor = book.author,
            chapterName = chapters[1].title,
            chapterIndex = 1,
            chapterPos = 8,
            content = "Original note",
        )
        appDb.bookmarkDao.insert(bookmark)
        val scenario = ActivityScenario.launch<AppActivity>(
            AppActivity.startIntent(context, BookTocRoute(book.bookUrl)),
        )

        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText(chapters[1].title).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(context.getString(io.legado.app.R.string.bookmark)).performClick()
        composeRule.onNodeWithText(chapters[1].title).performTouchInput { longClick() }
        composeRule.onNodeWithTag("bookTocBookmarkNote")
            .performClick().performTextInput(" updated")
        composeRule.onNodeWithText(context.getString(io.legado.app.R.string.ok)).performClick()

        composeRule.waitUntil(10_000) {
            appDb.bookmarkDao.getByBook(book.name, book.author).singleOrNull()?.content == "Original note updated"
        }
        scenario.close()
    }

    @Test
    fun highlightLongPressEditsAndDeletesTheHighlight() {
        val highlight = BookHighlight(
            bookUrl = book.bookUrl,
            bookName = book.name,
            bookAuthor = book.author,
            chapterName = chapters[1].title,
            chapterIndex = 1,
            chapterPos = 10,
            chapterPosEnd = 14,
            bookText = "Text",
            note = "Original highlight note",
        )
        appDb.bookHighlightDao.insert(highlight)
        val scenario = ActivityScenario.launch<AppActivity>(
            AppActivity.startIntent(context, BookTocRoute(book.bookUrl)),
        )

        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText(chapters[1].title).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(context.getString(io.legado.app.R.string.highlight_tab)).performClick()
        composeRule.onNodeWithText(chapters[1].title).performTouchInput { longClick() }
        composeRule.onNodeWithTag("bookTocHighlightNote")
            .performClick().performTextReplacement("Updated highlight note")
        composeRule.onNodeWithText(context.getString(io.legado.app.R.string.ok)).performClick()
        composeRule.waitUntil(10_000) {
            appDb.bookHighlightDao.getByBook(book.bookUrl).singleOrNull()?.note == "Updated highlight note"
        }

        composeRule.onNodeWithText(chapters[1].title).performTouchInput { longClick() }
        composeRule.onNodeWithText(context.getString(io.legado.app.R.string.delete)).performClick()
        composeRule.waitUntil(10_000) { appDb.bookHighlightDao.getByBook(book.bookUrl).isEmpty() }
        scenario.close()
    }

    @Test
    fun txtTocRuleCanBeSelectedAndRebuildsLocalChapters() {
        val file = File.createTempFile("toc-route-", ".txt", context.cacheDir)
        file.writeText("Chapter One\nFirst body\nChapter Two\nSecond body\n")
        val localBook = Book(
            bookUrl = file.absolutePath,
            origin = BookType.localTag,
            originName = file.name,
            name = "TXT rule fixture ${UUID.randomUUID()}",
            author = "Fixture author",
            type = BookType.local or BookType.text,
        )
        val rule = TxtTocRule(
            id = System.currentTimeMillis(),
            name = "Route rule ${UUID.randomUUID()}",
            rule = "^Chapter .+$",
        )
        appDb.bookDao.insert(localBook)
        appDb.txtTocRuleDao.insert(rule)
        val scenario = ActivityScenario.launch<AppActivity>(
            AppActivity.startIntent(context, BookTocRoute(localBook.bookUrl)),
        )

        try {
            composeRule.waitUntil(10_000) {
                composeRule.onAllNodesWithText(context.getString(io.legado.app.R.string.chapter_list))
                    .fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithTag("legadoTopAppBarOverflow").performClick()
            composeRule.onNodeWithText(context.getString(io.legado.app.R.string.txt_toc_rule)).performClick()
            composeRule.onNodeWithText(rule.name).performClick()
            composeRule.onNodeWithText(context.getString(io.legado.app.R.string.ok)).performClick()

            composeRule.waitUntil(10_000) {
                appDb.bookChapterDao.getChapterList(localBook.bookUrl).map { it.title } ==
                    listOf("Chapter One", "Chapter Two")
            }
        } finally {
            scenario.close()
            appDb.bookChapterDao.delByBook(localBook.bookUrl)
            appDb.txtTocRuleDao.delete(rule)
            appDb.bookDao.delete(localBook)
            file.delete()
        }
    }

    @Test
    fun readerRuleEntryOpensComposeRulePickerImmediately() {
        val rule = TxtTocRule(
            id = System.currentTimeMillis(),
            name = "Auto-open rule ${UUID.randomUUID()}",
            rule = "^Fixture.*$",
        )
        appDb.txtTocRuleDao.insert(rule)
        val scenario = ActivityScenario.launch<AppActivity>(
            AppActivity.startIntent(context, BookTocRoute(book.bookUrl, openTxtTocRules = true)),
        )
        try {
            composeRule.waitUntil(10_000) {
                composeRule.onAllNodesWithText(rule.name).fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText(rule.name).assertIsDisplayed()
        } finally {
            scenario.close()
            appDb.txtTocRuleDao.delete(rule)
        }
    }

    @Test
    fun pdfWithoutOutlinesShowsDatabaseChapters() {
        val file = File.createTempFile("toc-fallback-", ".pdf", context.cacheDir)
        val localPdf = Book(
            bookUrl = file.absolutePath,
            origin = BookType.localTag,
            originName = file.name,
            name = "PDF fallback ${UUID.randomUUID()}",
            author = "Fixture author",
            type = BookType.local or BookType.text,
            totalChapterNum = 2,
        )
        val localChapters = listOf(
            BookChapter(bookUrl = localPdf.bookUrl, url = "pdf-fallback-0", title = "Fallback page group one", index = 0),
            BookChapter(bookUrl = localPdf.bookUrl, url = "pdf-fallback-1", title = "Fallback page group two", index = 1),
        )
        appDb.bookDao.insert(localPdf)
        appDb.bookChapterDao.insert(*localChapters.toTypedArray())
        val scenario = ActivityScenario.launch<AppActivity>(
            AppActivity.startIntent(context, BookTocRoute(localPdf.bookUrl)),
        )
        try {
            composeRule.waitUntil(10_000) {
                composeRule.onAllNodesWithText(localChapters[0].title).fetchSemanticsNodes().isNotEmpty() &&
                    composeRule.onAllNodesWithText(localChapters[1].title).fetchSemanticsNodes().isNotEmpty()
            }
        } finally {
            scenario.close()
            appDb.bookChapterDao.delByBook(localPdf.bookUrl)
            appDb.bookDao.delete(localPdf)
            file.delete()
        }
    }
}
