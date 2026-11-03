package io.legado.app.ui.book.read

import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.ViewModelProvider
import io.legado.app.ui.book.manga.ReadMangaViewModel
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.Bookmark
import io.legado.app.model.ReadBook
import io.legado.app.model.localBook.PdfFile
import io.legado.app.model.localBook.PdfOutline
import io.legado.app.ui.navigation.MangaReaderRoute
import io.legado.app.ui.navigation.BookshelfRoute
import io.legado.app.ui.navigation.navigationViewModelStores
import androidx.navigation3.runtime.NavEntry
import androidx.compose.ui.test.onNodeWithContentDescription
import io.legado.app.ui.book.read.page.entities.column.ImageColumn
import io.legado.app.ui.book.read.config.ClickActionConfigDialog
import io.legado.app.ui.book.toc.PdfOutlineListState
import io.legado.app.ui.AppActivity
import io.legado.app.ui.navigation.BookTocRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PdfOutlineNavigationTest {
    @get:Rule val composeRule = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private lateinit var mangaModel: ReadMangaViewModel

    @Test
    fun outlineRetainsDatabasePositionsAndClickOpensActualTargetImage() {
        PDFBoxResourceLoader.init(context)
        val file = File.createTempFile("navigation-", ".pdf", context.cacheDir)
        PDDocument().use { document ->
            repeat(15) { index ->
                val page = PDPage(PDRectangle(600f, 900f))
                document.addPage(page)
                PDPageContentStream(document, page).use { content ->
                    content.beginText()
                    content.setFont(PDType1Font.HELVETICA, 28f)
                    content.newLineAtOffset(50f, 700f)
                    content.showText("PDF outline: page ${index + 1}")
                    content.endText()
                }
            }
            val outline = PDDocumentOutline()
            document.documentCatalog.documentOutline = outline
            val parent = PDOutlineItem().apply { title = "第一部分" }
            parent.addLast(PDOutlineItem().apply {
                title = "目标十三页"
                setDestination(document.getPage(12))
                addLast(PDOutlineItem().apply {
                    title = "同页小节"
                    setDestination(document.getPage(12))
                })
            })
            outline.addLast(parent)
            outline.addLast(PDOutlineItem().apply { title = "前言"; setDestination(document.getPage(0)) })
            document.save(file)
        }
        val book = Book(bookUrl = file.absolutePath, originName = file.name, type = BookType.local or BookType.text,
            name = "PDF test ${UUID.randomUUID()}", totalChapterNum = 2, durChapterPos = 3,
            durChapterTitle = "分段_0")
        book.setImageStyle("FULL")
        val chapters = (0..1).map { BookChapter(bookUrl = book.bookUrl, url = "pdf_$it", title = "分段_$it", index = it) }
        val bookmark = Bookmark(bookName = book.name, chapterIndex = 1, chapterPos = 7, content = "已有书签")
        appDb.bookDao.insert(book)
        appDb.bookChapterDao.insert(*chapters.toTypedArray())
        appDb.bookmarkDao.insert(bookmark)
        var reader: ActivityScenario<ReadBookActivity>? = null
        var manga: ActivityScenario<AppActivity>? = null
        try {
            ActivityScenario.launch<AppActivity>(AppActivity.startIntent(context, BookTocRoute(book.bookUrl))).use { tocScenario ->
                waitUntil { outlineRows() == listOf("第一部分", "目标十三页", "同页小节", "前言") }
                screenshot("pdf-outline-expanded")
                clickOutline("第一部分")
                waitUntil { outlineRows() == listOf("第一部分", "前言") }
                screenshot("pdf-outline-collapsed")
                composeRule.onNodeWithTag("legadoTopAppBarSearch").performClick()
                composeRule.onNodeWithText(context.getString(io.legado.app.R.string.search)).performTextInput("同页")
                waitUntil { outlineRows() == listOf("第一部分", "目标十三页", "同页小节") }
                screenshot("pdf-outline-search")
                composeRule.onNodeWithTag("legadoTopAppBarNavigation").performClick()
                waitUntil { outlineRows() == listOf("第一部分", "前言") }
                clickOutline("第一部分")
                composeRule.onNodeWithTag("legadoTopAppBarOverflow").performClick()
                composeRule.onNodeWithText(context.getString(io.legado.app.R.string.reverse_toc)).performClick()
                waitUntil { appDb.bookDao.getBook(book.bookUrl)?.getReverseToc() == true }
                val reversedOutline = PdfOutlineListState(PdfOutline.read(book), expanded = true)
                    .items(query = null, reverseOrder = true).map { it.node.title }
                assertEquals(listOf("前言", "第一部分", "目标十三页", "同页小节"), reversedOutline)
                screenshot("pdf-outline-reversed")
                assertEquals(3, appDb.bookDao.getBook(book.bookUrl)!!.durChapterPos)
                assertEquals(chapters, appDb.bookChapterDao.getChapterList(book.bookUrl))
                assertEquals(listOf(bookmark), appDb.bookmarkDao.getByBook(book.name, book.author))
            }
            reader = ActivityScenario.launch(Intent(context, ReadBookActivity::class.java)
                .putExtra("bookUrl", book.bookUrl))
            waitUntil { ReadBook.book?.bookUrl == book.bookUrl && ReadBook.curTextChapter?.pages?.isNotEmpty() == true }
            reader.onActivity { activity ->
                activity.supportFragmentManager.fragments.filterIsInstance<ClickActionConfigDialog>()
                    .forEach { it.dismissAllowingStateLoss() }
            }
            reader.onActivity { it.openChapterList() }
            waitUntil { outlineRows()?.contains("目标十三页") == true }
            clickOutline("目标十三页")
            waitUntil {
                ReadBook.durChapterIndex == 1 && ReadBook.curTextChapter
                    ?.getPageByReadPos(ReadBook.durChapterPos)?.lines
                    ?.flatMap { it.columns }?.filterIsInstance<ImageColumn>()?.any { it.src == "12" } == true
            }
            screenshot("pdf-reader-page-13")
            assertEquals(chapters, appDb.bookChapterDao.getChapterList(book.bookUrl))
            assertEquals(listOf(bookmark), appDb.bookmarkDao.getByBook(book.name, book.author))
            reader.close()
            reader = null
            val mangaRoute = MangaReaderRoute(book.bookUrl)
            val mangaScenario = ActivityScenario.launch<AppActivity>(AppActivity.startIntent(context, mangaRoute))
            manga = mangaScenario
            mangaScenario.onActivity { mangaModel = ViewModelProvider(navigationViewModelStores(it, BookshelfRoute).getOrCreateOwner(NavEntry(mangaRoute) {}.contentKey))[ReadMangaViewModel::class.java] }
            waitUntil {
                var ready = false
                mangaScenario.onActivity { activity ->
                    val snapshot = mangaModel.snapshot()
                    ready = snapshot.book?.bookUrl == book.bookUrl &&
                        !mangaModel.readerUiState.value.loadingVisible &&
                        visibleMangaPage()?.chapterIndex == snapshot.chapterIndex
                }
                ready
            }
            var catalogLabel = ""
            mangaScenario.onActivity { mangaModel.setMenuVisible(true); catalogLabel = it.getString(io.legado.app.R.string.chapter_list) }
            composeRule.onNodeWithContentDescription(catalogLabel).performClick()
            waitUntil { outlineRows()?.contains("目标十三页") == true }
            clickOutline("目标十三页")
            waitUntil {
                var targetVisible = false
                mangaScenario.onActivity { activity ->
                    targetVisible = visibleMangaPage()?.let {
                        it.chapterIndex == 1 && it.index == 2 && it.mImageUrl == "12"
                    } == true
                }
                val snapshot = mangaModel.snapshot()
                snapshot.chapterIndex == 1 && snapshot.chapterPosition == 2 && targetVisible
            }
            screenshot("pdf-manga-page-13")
            assertEquals(listOf(bookmark), appDb.bookmarkDao.getByBook(book.name, book.author))
            mangaScenario.close()
            manga = null
        } catch (error: Throwable) {
            manga?.let { scenario ->
                runCatching { recordMangaFailure(scenario) }.onFailure(error::addSuppressed)
            }
            throw error
        } finally {
            manga?.close()
            reader?.close()
            PdfFile.clear(book.bookUrl)
            appDb.bookmarkDao.delete(bookmark)
            appDb.bookDao.delete(book)
            file.delete()
        }
    }

    private fun visibleMangaPage(): io.legado.app.ui.book.manga.entities.MangaPage? {
        val state = mangaModel.readerUiState.value
        return state.items.getOrNull(state.anchorItemIndex) as? io.legado.app.ui.book.manga.entities.MangaPage
    }

    private fun recordMangaFailure(scenario: ActivityScenario<AppActivity>) {
        val state = StringBuilder()
        scenario.onActivity { activity ->
            val snapshot = mangaModel.snapshot()
            state.appendLine("chapter=${snapshot.chapterIndex}, position=${snapshot.chapterPosition}")
            state.appendLine("chapters=${listOf(snapshot.previousChapter, snapshot.currentChapter,
                snapshot.nextChapter).map { it?.chapter?.index }}")
            state.appendLine("loading=${mangaModel.readerUiState.value.loadingVisible}")
            state.appendLine("visible=${visibleMangaPage()}")
            state.appendLine("items=${mangaModel.readerUiState.value.items}")
        }
        File(context.getExternalFilesDir("ui-regression"), "pdf-manga-failure-state.txt").writeText(state.toString())
        screenshot("pdf-manga-failure")
    }

    private fun clickOutline(title: String) {
        waitUntil {
            val found = outlineRows()?.contains(title) == true
            if (found) composeRule.onNodeWithText(title).performClick()
            found
        }
    }

    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(500)
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(context.getExternalFilesDir("ui-regression"), "$name.png").outputStream()
                .use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }

    private fun outlineRows(): List<String>? {
        val candidates = listOf("第一部分", "目标十三页", "同页小节", "前言", "分段_1", "分段_0")
        return candidates.filter { title ->
            runCatching { composeRule.onNodeWithText(title).fetchSemanticsNode() }.isSuccess
        }
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 30_000_000_000L
        while (System.nanoTime() < deadline) {
            composeRule.waitForIdle()
            if (condition()) return
            Thread.sleep(100)
        }
        val chapter = ReadBook.curTextChapter
        val pageSources = chapter?.pages.orEmpty().flatMap { page ->
            page.lines.flatMap { line -> line.columns.filterIsInstance<ImageColumn>().map { it.src } }
        }
        val visiblePage = chapter?.getPageByReadPos(ReadBook.durChapterPos)
        val visiblePageSources = visiblePage?.lines.orEmpty().flatMap { line ->
            line.columns.filterIsInstance<ImageColumn>().map { it.src }
        }
        val state = "book=${ReadBook.book?.bookUrl}, chapter=${ReadBook.durChapterIndex}, " +
            "position=${ReadBook.durChapterPos}, currentChapter=${chapter?.chapter?.index}, " +
            "completed=${chapter?.isCompleted}, pageStarts=${chapter?.pages?.map { it.chapterPosition }}, " +
            "visiblePage=${visiblePage?.index}, visiblePageSources=$visiblePageSources, pageSources=$pageSources"
        assertTrue("PDF outline/navigation did not reach the expected state: $state", condition())
    }
}
