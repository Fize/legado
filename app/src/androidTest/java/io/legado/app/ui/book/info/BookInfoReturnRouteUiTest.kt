package io.legado.app.ui.book.info

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.*
import io.legado.app.ui.AppActivity
import io.legado.app.ui.navigation.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BookInfoReturnRouteUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val id = UUID.randomUUID().toString()
    private val parentId = UUID.randomUUID().toString()
    private val childId = UUID.randomUUID().toString()
    private val book = Book(bookUrl = "detail-return://$id", name = "Return fixture $id", author = "Author $id",
        totalChapterNum = 3, type = BookType.text)
    private val dao get() = appDb.bookInfoRequestDao
    private var scenario: ActivityScenario<AppActivity>? = null
    @Before fun setup() {
        appDb.bookDao.insert(book)
        appDb.bookChapterDao.insert(*(0..2).map { BookChapter(bookUrl = book.bookUrl, url = "${book.bookUrl}/$it", title = "Return chapter ${it + 1}", index = it) }.toTypedArray())
        dao.claim(BookInfoRequest(parentId, book.bookUrl, book.bookUrl, "parent", updatedAt = 1), "parent")
    }
    @After fun cleanup() {
        scenario?.close()
        dao.close(parentId, "parent")
        appDb.readerSessionDao.get("other-$id")?.let { appDb.readerSessionDao.close(it.id, it.ownerToken) }
        appDb.bookDao.delete(book)
        appDb.bookDao.getBook("${book.bookUrl}/changed")?.let(appDb.bookDao::delete)
    }
    private fun begin(kind: BookInfoChildKind) = checkNotNull(dao.begin(parentId, "parent", 0, book.bookUrl, childId, kind, null, 2))
    private fun launch(route: AppRoute) {
        scenario = ActivityScenario.launch(AppActivity.startIntent(context, route))
    }
    private fun catalogReady() = compose.waitUntil(10_000) {
        compose.onAllNodesWithText("Return chapter 3").fetchSemanticsNodes().isNotEmpty()
    }

    @Test fun realCatalogSelectionSurvivesRecreationAndQueuesOneReader() {
        begin(BookInfoChildKind.Catalog)
        launch(BookTocRoute(book.bookUrl, bookInfoChildRequestId = childId))
        catalogReady(); scenario!!.recreate(); catalogReady()
        compose.onNodeWithText("Return chapter 3").performClick()
        compose.waitUntil(10_000) { dao.child(childId)?.phase == BookInfoChildPhase.Returned }
        assertEquals(2, dao.child(childId)!!.position!!.chapterIndex)
        val reader = checkNotNull(dao.continueCatalog(parentId, "parent", 1, book.bookUrl, childId, "reader-$id", 10))
        assertEquals(BookInfoChildKind.Reader, reader.kind)
        assertEquals(2, reader.position!!.chapterIndex)
        assertEquals(BookInfoChildPhase.Consumed, dao.child(childId)!!.phase)
    }

    @Test fun cancelDoesNotDeleteAPreviewUsedByAnotherReader() {
        appDb.bookDao.update(book.copy(type = BookType.text or BookType.notShelf))
        appDb.readerSessionDao.claim(ReaderSessionState("other-$id", book.bookUrl, book.bookUrl, false, false, updatedAt = 1), "other")
        begin(BookInfoChildKind.Catalog)
        launch(BookTocRoute(book.bookUrl, bookInfoChildRequestId = childId)); catalogReady()
        Espresso.pressBackUnconditionally()
        compose.waitUntil(10_000) { dao.child(childId)?.phase == BookInfoChildPhase.Cancelled }
        assertNotNull(appDb.bookDao.getBook(book.bookUrl))
        assertTrue(appDb.readerSessionDao.ownsBook("other-$id", "other", 0, book.bookUrl))
    }

    @Test fun editorSaveWritesTheBookAndItsDurableReturn() {
        begin(BookInfoChildKind.Edit)
        launch(EditBookInfoRoute(book.bookUrl, bookInfoChildRequestId = childId))
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("book_edit_name").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("book_edit_name").performTextReplacement("Updated $id")
        Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("book_edit_save").performClick()
        compose.waitUntil(10_000) { dao.child(childId)?.phase == BookInfoChildPhase.Returned }
        assertEquals("Updated $id", appDb.bookDao.getBook(book.bookUrl)!!.name)
        assertNotNull(dao.consume(parentId, "parent", 1, book.bookUrl, childId, 10))
        assertNull(dao.consume(parentId, "parent", 1, book.bookUrl, childId, 11))
    }

    @Test fun editorBackCancelsWithoutSavingItsDraft() {
        begin(BookInfoChildKind.Edit)
        launch(EditBookInfoRoute(book.bookUrl, bookInfoChildRequestId = childId))
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("book_edit_name").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("book_edit_name").performTextReplacement("Cancelled $id")
        Espresso.closeSoftKeyboard(); Espresso.pressBackUnconditionally()
        compose.waitUntil(10_000) { dao.child(childId)?.phase == BookInfoChildPhase.Cancelled }
        assertEquals(book.name, appDb.bookDao.getBook(book.bookUrl)!!.name)
    }

    @Test fun anObsoleteCatalogRouteNeverReopensItsOldBook() {
        begin(BookInfoChildKind.Catalog)
        val changed = book.copy(bookUrl = "${book.bookUrl}/changed", name = "Changed $id")
        dao.replaceBook(parentId, "parent", 1, book.bookUrl, changed.bookUrl, 3) { appDb.bookDao.insert(changed) }
        launch(BookTocRoute(book.bookUrl, bookInfoChildRequestId = childId))
        compose.waitUntil(10_000) { scenario!!.state == Lifecycle.State.DESTROYED }
        assertEquals("", dao.child(childId)!!.producerToken)
        assertEquals(changed.bookUrl, dao.get(parentId)!!.bookUrl)
    }
}
