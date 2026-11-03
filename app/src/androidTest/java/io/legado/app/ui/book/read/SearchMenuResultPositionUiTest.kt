package io.legado.app.ui.book.read

import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.config.LocalConfig
import io.legado.app.model.ReadBook
import io.legado.app.ui.book.read.config.ClickActionConfigDialog
import io.legado.app.ui.book.read.page.ReadView
import io.legado.app.model.localBook.TextFile
import io.legado.app.model.localBook.LocalBook
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real reader -> content search -> reader toolbar; no simulated result objects. */
@RunWith(AndroidJUnit4::class)
class SearchMenuResultPositionUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command),
    ).bufferedReader().use { it.readText() }

    @Test fun laterSearchResultLandsOnItsOwnText() = checkPosition(false)
    @Test fun laterSearchResultAfterDisplayResizeLandsOnItsOwnText() = checkPosition(true)
    @Test fun laterSearchResultAfterReaderReconfigurationLandsOnItsOwnText() = checkPosition(true, true)
    @Test fun importedSearchResultAfterReaderReconfigurationLandsOnItsOwnText() = checkPosition(true, true, true)

    private fun checkPosition(resize: Boolean, reconfigureReader: Boolean = false, importFile: Boolean = false) {
        val help = listOf("readHelpVersion", "readMenuHelpVersion").associateWith { LocalConfig.all[it] }
        val file = File.createTempFile("search-menu-owned-", ".txt", context.cacheDir)
        file.writeText((0 until 60).joinToString("\n") { "Line %03d needle content for search navigation.".format(it) })
        var book = Book(bookUrl = file.absolutePath, originName = file.name, name = file.name,
            charset = "UTF-8", type = BookType.local or BookType.text, totalChapterNum = 1)
        var scenario: ActivityScenario<ReadBookActivity>? = null
        val originalSize = if (resize) shell("wm size") else ""
        val originalDensity = if (reconfigureReader) shell("wm density") else ""
        val originalFontScale = if (reconfigureReader) shell("settings get system font_scale").trim() else ""
        try {
            LocalConfig.edit().putInt("readHelpVersion", 1).putInt("readMenuHelpVersion", 1).commit()
            if (importFile) {
                book = LocalBook.importFile(Uri.fromFile(file))
            } else {
                appDb.bookDao.insert(book)
                appDb.bookChapterDao.insert(BookChapter(bookUrl = book.bookUrl, url = "search-menu-chapter",
                    title = "Search fixture", start = 0L, end = file.length()))
            }
            scenario = ActivityScenario.launch(Intent(context, ReadBookActivity::class.java).putExtra("bookUrl", book.bookUrl))
            scenario.onActivity { activity ->
                activity.supportFragmentManager.fragments.filterIsInstance<ClickActionConfigDialog>()
                    .forEach { it.dismissAllowingStateLoss() }
            }
            compose.waitUntil(30_000) {
                var ready = false
                scenario.onActivity { activity ->
                    ready = ReadBook.book?.bookUrl == book.bookUrl && ReadBook.curTextChapter?.isCompleted == true &&
                        !activity.findViewById<ReadView>(R.id.read_view).curPage.textPage.isMsgPage && activity.bottomDialog == 0
                }
                ready
            }
            if (reconfigureReader) {
                shell("wm size 2400x1200")
                shell("wm density 320")
                shell("settings put system font_scale 2.0")
                // Older Android versions may clamp the requested override to
                // a device-specific maximum; wait for the size actually used.
                val configuredWidth = Regex("(?:Override|Physical) size: (\\d+)x\\d+")
                    .findAll(shell("wm size")).last().groupValues[1].toInt()
                assertTrue("The configured display is not wide: $configuredWidth", configuredWidth >= 2000)
                compose.waitUntil(30_000) {
                    var ready = false
                    scenario.onActivity { activity ->
                        ready = activity.resources.configuration.fontScale >= 1.99f &&
                            activity.resources.displayMetrics.widthPixels == configuredWidth &&
                            ReadBook.curTextChapter?.isCompleted == true
                    }
                    ready
                }
                compose.waitForIdle()
            }
            scenario.onActivity { it.openSearchActivity("needle") }
            compose.waitUntil(30_000) { compose.onAllNodesWithTag("content_search_hit_0").fetchSemanticsNodes().isNotEmpty() }
            // Anchor the target at the top, rather than just exposing a sliver
            // that can legitimately disappear when the viewport becomes short.
            compose.onNodeWithTag("content_search_results").performScrollToIndex(20)
            compose.onNodeWithTag("content_search_hit_20").assertIsDisplayed()
            if (resize) {
                shell("wm size 2400x720")
                compose.waitForIdle()
                compose.onNodeWithTag("content_search_hit_20").assertIsDisplayed()
                shell("wm size 2400x1200")
                compose.waitForIdle()
                compose.onNodeWithTag("content_search_hit_20").assertIsDisplayed()
            }
            compose.onNodeWithTag("content_search_hit_20").assertIsDisplayed().performTouchInput { click() }
            compose.waitUntil(30_000) {
                var ready = false
                scenario.onActivity { activity -> ready = activity.findViewById<SearchMenu>(R.id.search_menu)?.selectedSearchResult != null }
                ready
            }
            scenario.onActivity { activity ->
                val menu = activity.findViewById<SearchMenu>(R.id.search_menu)
                val selected = checkNotNull(menu.selectedSearchResult)
                val view = activity.findViewById<ReadView>(R.id.read_view)
                val chapter = checkNotNull(ReadBook.curTextChapter)
                var textOffset = 0
                val pages = chapter.pages.mapIndexed { index, page ->
                    val description = "page=$index chapterPosition=${page.chapterPosition} textOffset=$textOffset " + page.text.take(500)
                    textOffset += page.text.length
                    description
                }
                val report = org.json.JSONObject().put("selected_ordinal", selected.resultCount)
                    .put("within_chapter", selected.resultCountWithinChapter).put("canonical_position", selected.queryIndexInChapter)
                    .put("reader_position", ReadBook.durChapterPos).put("reader_page_index", ReadBook.durPageIndex)
                    .put("double_page", view.curPage.textPage.doublePage)
                    .put("current_page", view.curPage.textPage.text).put("next_page", view.nextPage.textPage.text)
                    .put("page_diagnostics", org.json.JSONArray(pages))
                File(context.cacheDir, "search-result-position-diagnostic.json").writeText(report.toString(2))
                assertEquals(20, selected.resultCount)
                assertTrue("Selected Line 020 is absent from the visible page: ${view.curPage.textPage.text}",
                    view.curPage.textPage.text.contains("Line 020 needle"))
            }
            // The reader can re-layout after the search screen closes. Verify
            // the target remains visible after pending updates have settled.
            Thread.sleep(5_000)
            compose.waitForIdle()
            scenario.onActivity { activity ->
                val view = activity.findViewById<ReadView>(R.id.read_view)
                File(context.cacheDir, "search-result-position-settled.json").writeText(
                    org.json.JSONObject().put("reader_position", ReadBook.durChapterPos)
                        .put("reader_page_index", ReadBook.durPageIndex)
                        .put("current_page", view.curPage.textPage.text)
                        .put("next_page", view.nextPage.textPage.text)
                        .put("selected_text", view.getSelectText()).toString(2))
                assertTrue("Selected Line 020 disappeared after reader updates: ${view.curPage.textPage.text}",
                    view.curPage.textPage.text.contains("Line 020 needle"))
                assertEquals("needle", view.getSelectText())
            }

        } finally {
            scenario?.close()
            if (resize) {
                val override = Regex("Override size: (\\d+x\\d+)").find(originalSize)?.groupValues?.get(1)
                shell("wm size ${override ?: "reset"}")
            }
            if (reconfigureReader) {
                val override = Regex("Override density: (\\d+)").find(originalDensity)?.groupValues?.get(1)
                shell("wm density ${override ?: "reset"}")
                if (originalFontScale == "null") shell("settings delete system font_scale")
                else shell("settings put system font_scale $originalFontScale")
            }
            appDb.bookChapterDao.delByBook(book.bookUrl)
            appDb.bookDao.delete(book)
            file.delete()
            TextFile.clear()
            LocalConfig.edit().apply { help.forEach { (key, value) -> if (value == null) remove(key) else putInt(key, value as Int) } }.commit()
        }
    }
}
