package io.legado.app.ui.book.read

import android.content.Intent
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
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real reader -> content search -> reader toolbar; no simulated result objects. */
@RunWith(AndroidJUnit4::class)
class SearchMenuReaderUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun contentSearchReturnsAndNavigatesBetweenActualResults() {
        val help = listOf("readHelpVersion", "readMenuHelpVersion").associateWith { LocalConfig.all[it] }
        val file = File.createTempFile("search-menu-owned-", ".txt", context.cacheDir)
        file.writeText((0 until 160).joinToString("\n") { "Line $it ${if (it == 10 || it == 90) "needle" else "ordinary"} content." })
        val book = Book(bookUrl = file.absolutePath, originName = file.name, name = file.name,
            charset = "UTF-8", type = BookType.local or BookType.text, totalChapterNum = 1)
        var scenario: ActivityScenario<ReadBookActivity>? = null
        try {
            LocalConfig.edit().putInt("readHelpVersion", 1).putInt("readMenuHelpVersion", 1).commit()
            appDb.bookDao.insert(book)
            appDb.bookChapterDao.insert(BookChapter(bookUrl = book.bookUrl, url = "search-menu-chapter",
                title = "Search fixture", start = 0L, end = file.length()))
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
            scenario.onActivity { it.openSearchActivity("needle") }
            compose.waitUntil(30_000) { compose.onAllNodesWithTag("content_search_hit_1").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("content_search_hit_0").performClick()
            compose.waitUntil(30_000) {
                var ready = false
                scenario.onActivity { activity -> ready = activity.findViewById<SearchMenu>(R.id.search_menu)?.selectedSearchResult != null }
                ready
            }
            scenario.onActivity { activity ->
                val menu = activity.findViewById<SearchMenu>(R.id.search_menu)
                val first = checkNotNull(menu.selectedSearchResult)
                assertTrue(menu.findViewById<android.view.View>(R.id.iv_search_content_down).isEnabled)
                menu.findViewById<android.view.View>(R.id.iv_search_content_down).performClick()
                val second = checkNotNull(menu.selectedSearchResult)
                assertNotEquals(first.queryIndexInChapter, second.queryIndexInChapter)
                menu.findViewById<android.view.View>(R.id.iv_search_content_up).performClick()
                assertEquals(first, menu.selectedSearchResult)
            }
        } finally {
            scenario?.close()
            appDb.bookChapterDao.delByBook(book.bookUrl)
            appDb.bookDao.delete(book)
            file.delete()
            TextFile.clear()
            LocalConfig.edit().apply { help.forEach { (key, value) -> if (value == null) remove(key) else putInt(key, value as Int) } }.commit()
        }
    }
}
