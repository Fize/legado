package io.legado.app.ui.book.info

import io.legado.app.data.repository.infoSnapshot

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.rule.BookInfoRule
import io.legado.app.utils.GSON
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class BookInfoLoadingIndicatorTest {
    @Test fun `details have one injected state owner`() {
        val model = projectFile("src/main/java/io/legado/app/ui/book/info/BookInfoViewModel.kt").readText()
        val activity = projectFile("src/main/java/io/legado/app/ui/book/info/BookInfoActivity.kt").readText()
        assertTrue(model.contains("@HiltViewModel"))
        assertTrue(model.contains("private val repository: BookInfoRepository"))
        assertTrue(model.contains("private val savedState: SavedStateHandle"))
        assertEquals(1, Regex("MutableStateFlow\\(").findAll(model).count())
        assertTrue(activity.contains("viewModel.uiState.collectAsStateWithLifecycle()"))
        assertFalse(model.contains("appDb."))
        assertFalse(model.contains("MutableLiveData"))
        assertFalse(model.contains("android.content.Intent"))
        assertFalse(model.contains("AppCompatActivity"))
        assertFalse(model.contains("SourceLoginJsExtensions"))
    }

    @Test fun `read progress appears only for read multi chapter books`() {
        assertNull(resolveBookInfoReadProgress(Book(totalChapterNum = 20)))
        assertNull(resolveBookInfoReadProgress(Book(totalChapterNum = 1, durChapterPos = 1)))
        assertEquals(50, resolveBookInfoReadProgress(Book(totalChapterNum = 11, durChapterIndex = 5)))
    }

    @Test fun `empty stored toc title follows the current chapter index`() {
        val chapters = listOf(BookChapter(title = "卷一", isVolume = true), BookChapter(title = "第一章"), BookChapter(title = "第二章"))
        assertEquals("已保存章节", resolveBookInfoTocTitle("已保存章节", 2, chapters))
        assertEquals("卷一", resolveBookInfoTocTitle(null, 0, chapters))
        assertEquals("第二章", resolveBookInfoTocTitle(null, 99, chapters))
    }

    @Test fun `overlapping network loads stay active until the last one finishes`() {
        val changes = mutableListOf<Boolean>()
        val counter = BookInfoNetworkLoadingCounter(changes::add)
        counter.begin(); counter.begin()
        assertEquals(listOf(true), changes)
        counter.end()
        assertEquals(listOf(true), changes)
        counter.end()
        assertEquals(listOf(true, false), changes)
    }

    @Test(expected = IllegalStateException::class)
    fun `network loading cannot finish without an active request`() {
        BookInfoNetworkLoadingCounter {}.end()
    }

    @Test fun `book snapshots retain transient parser inputs and detach mutable configuration`() {
        val book = Book(bookUrl = "book", name = "Before", variable = "{\"one\":\"before\"}",
            readConfig = Book.ReadConfig(manualReplaceRuleIds = mutableListOf(1L)))
        book.infoHtml = "<h1>Cached info</h1>"
        book.tocHtml = "<a>Cached TOC</a>"
        book.downloadUrls = mutableListOf("https://example.com/book.epub")
        val snapshot = book.infoSnapshot()
        book.name = "After"
        book.readConfig!!.splitLongChapter = false
        book.variableMap["one"] = "after"
        (book.downloadUrls as MutableList<String>).clear()
        assertEquals("Before", snapshot.name)
        assertTrue(snapshot.readConfig!!.splitLongChapter)
        assertEquals(listOf(1L), snapshot.readConfig!!.manualReplaceRuleIds)
        assertEquals("before", snapshot.variableMap["one"])
        assertEquals("<h1>Cached info</h1>", snapshot.infoHtml)
        assertEquals("<a>Cached TOC</a>", snapshot.tocHtml)
        assertEquals(listOf("https://example.com/book.epub"), snapshot.downloadUrls)
    }

    @Test fun `chapter snapshots detach script variables and metadata`() {
        val chapter = BookChapter(url = "one", title = "Before", variable = "{\"one\":\"before\"}").apply { titleMD5 = "cached" }
        val snapshot = chapter.infoSnapshot()
        chapter.title = "After"
        chapter.variableMap["one"] = "after"
        assertEquals("Before", snapshot.title)
        assertEquals("before", snapshot.variableMap["one"])
        assertEquals("cached", snapshot.titleMD5)
    }

    @Test fun `source snapshots detach mutable nested rules`() {
        val source = BookSource("https://example.com", "Before", ruleBookInfo = BookInfoRule(name = "h1@text"))
        val snapshot = source.infoSnapshot()
        source.bookSourceName = "After"
        source.ruleBookInfo!!.name = "h2@text"
        assertEquals("Before", snapshot.bookSourceName)
        assertEquals("h1@text", snapshot.ruleBookInfo!!.name)
    }

    @Test fun `legacy and null rule selections normalize to an empty detached list`() {
        for (json in listOf("{}", "{\"manualReplaceRuleIds\":null}")) {
            val config = GSON.fromJson(json, Book.ReadConfig::class.java)
            val snapshot = Book(readConfig = config).infoSnapshot()
            assertTrue(snapshot.readConfig!!.manualReplaceRuleIds.isEmpty())
        }
    }

    private fun projectFile(path: String): File = sequenceOf(File(path), File("app/$path"))
        .firstOrNull(File::isFile) ?: error("Missing project file: $path")
}
