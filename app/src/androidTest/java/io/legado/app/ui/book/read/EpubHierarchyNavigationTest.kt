package io.legado.app.ui.book.read

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.constant.BookType
import io.legado.app.constant.PageAnim
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.Bookmark
import io.legado.app.help.book.BookHelp
import io.legado.app.model.localBook.EpubFile
import io.legado.app.ui.book.toc.TocListState
import io.legado.app.ui.book.toc.TocListItem
import io.legado.app.utils.HtmlFormatter
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class EpubHierarchyNavigationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val titles = listOf("第一卷", "第一章", "第一节", "第二卷", "第一章", "番外 一", "第三卷", "第一章", "番外 二", "番外 三")
    private val depths = listOf(0, 1, 2, 0, 1, 0, 0, 1, 0, 0)

    @Test
    fun reporterEpubRetainsAllTenNavigationNodesAndRealContentUrls() {
        fixture("issue1074-reporter.epub").use { fixture ->
            val toc = EpubFile.getToc(fixture.book)
            assertEquals(titles, toc.map { it.title })
            assertEquals(depths, toc.map { it.depth })
            assertEquals(listOf(null, 0, 1, null, 3, null, null, 6, null, null), toc.map { it.parentId })
            val urls = (1..10).map { "OEBPS/Text/${it.toString().padStart(2, '0')}.html" }
            assertEquals(urls, toc.map { it.href })
            assertEquals(urls, fixture.chapters.map { it.url })
            fixture.chapters.forEachIndexed { index, chapter ->
                assertEquals(index, chapter.index)
                assertEquals(urls.getOrNull(index + 1).orEmpty(), chapter.getVariable("nextUrl"))
                assertTrue(EpubFile.getContent(fixture.book, chapter).orEmpty().contains("正文"))
            }
        }
    }

    @Test
    fun readerResourcesKeepChapterFragmentsAndEachPhysicalFileScope() {
        for (asset in listOf("issue1074-containers-fragments.epub", "issue1074-nav3-containers.epub")) {
            fixture(asset).use { fixture ->
                val identities = identities(fixture.book)
                val first = EpubFile.getReaderChapter(fixture.book, fixture.chapters[0])!!
                val second = EpubFile.getReaderChapter(fixture.book, fixture.chapters[1])!!
                val next = EpubFile.getReaderChapter(fixture.book, fixture.chapters[2])!!
                assertEquals(listOf("OEBPS/a.xhtml"), first.parts.map { it.address.path })
                assertEquals(listOf("OEBPS/a.xhtml", "OEBPS/b.xhtml"), second.parts.map { it.address.path })
                assertEquals(listOf("OEBPS/b.xhtml"), next.parts.map { it.address.path })
                val firstText = first.parts.joinToString { org.jsoup.Jsoup.parse(it.html).text() }
                val secondText = second.parts.joinToString { org.jsoup.Jsoup.parse(it.html).text() }
                val nextText = next.parts.joinToString { org.jsoup.Jsoup.parse(it.html).text() }
                assertTrue(firstText.contains("ALPHA_ONLY")); assertFalse(firstText.contains("BEFORE_START") || firstText.contains("SECOND_ONLY"))
                assertTrue(secondText.contains("SECOND_ONLY") && secondText.contains("TAIL_AFTER_SHARED_ID"))
                assertFalse(secondText.contains("ALPHA_ONLY") || secondText.contains("NEXT_RESOURCE_ONLY"))
                assertTrue(nextText.contains("NEXT_RESOURCE_ONLY")); assertFalse(nextText.contains("BEFORE_NEXT"))
                assertEquals(identities, identities(fixture.book))
            }
        }
    }

    @Test
    fun scopedLinksResolveToTheClippedChapterInsteadOfTheOtherFilesIdenticalId() = kotlinx.coroutines.runBlocking {
        for (asset in listOf("issue1074-containers-fragments.epub","issue1074-nav3-containers.epub")) {
            fixture(asset).use { fixture ->
                val id=UUID.randomUUID().toString()
                val owner=io.legado.app.data.repository.ReaderProgressOwner(id,"link-owner",0)
                appDb.readerSessionDao.claim(io.legado.app.data.entities.ReaderSessionState(id,fixture.book.bookUrl,
                    fixture.book.bookUrl,true,false,updatedAt=System.currentTimeMillis()),owner.token)
                try {
                    val repository=io.legado.app.data.repository.TextReaderEpubRepository(appDb,io.legado.app.data.cache.CacheResourceLeases())
                    val key=io.legado.app.model.TextReaderChapterKey(owner,fixture.book.bookUrl,fixture.book.origin,0,1)
                    for ((path,fragment,index) in listOf(Triple("OEBPS/a.xhtml","cut",1),
                        Triple("OEBPS/a.xhtml","collision",1),Triple("OEBPS/b.xhtml","collision",2))) {
                        val address=io.legado.app.model.localBook.EpubResourceAddress.fromPath(path,fragment)!!
                        assertEquals(index,repository.targetChapter(key,address)!!.index)
                    }
                    assertFalse(EpubFile.getReaderChapter(fixture.book,fixture.chapters[0])!!.parts.single().startsAtRoot)
                    assertTrue(EpubFile.getReaderChapter(fixture.book,fixture.chapters[1])!!.parts.last().startsAtRoot)
                } finally { appDb.readerSessionDao.close(id,owner.token) }
            }
        }
    }

    @Test
    fun ncxAndEpub3ContainersAliasesAndCrossResourceFragmentsKeepExactBoundaries() {
        for (asset in listOf("issue1074-containers-fragments.epub", "issue1074-nav3-containers.epub")) {
            fixture(asset).use { fixture ->
                val toc = EpubFile.getToc(fixture.book)
                assertEquals(asset, listOf("Container", "First", "Alias", "Second", "Next"), toc.map { it.title })
                assertEquals(listOf(0, 1, 2, 2, 0), toc.map { it.depth })
                assertNull(toc.first().href)
                assertEquals(listOf("OEBPS/a.xhtml#start", "OEBPS/a.xhtml#cut", "OEBPS/b.xhtml#collision"), fixture.chapters.map { it.url })
                assertEquals("OEBPS/a.xhtml#cut", fixture.chapters[0].getVariable("nextUrl"))
                assertEquals("cut", fixture.chapters[0].endFragmentId)
                assertEquals("OEBPS/b.xhtml#collision", fixture.chapters[1].getVariable("nextUrl"))
                val first = EpubFile.getContent(fixture.book, fixture.chapters[0]).orEmpty()
                val second = EpubFile.getContent(fixture.book, fixture.chapters[1]).orEmpty()
                val next = EpubFile.getContent(fixture.book, fixture.chapters[2]).orEmpty()
                assertTrue(first.contains("ALPHA_ONLY"))
                assertFalse(first.contains("BEFORE_START") || first.contains("SECOND_ONLY"))
                assertTrue(second.contains("SECOND_ONLY") && second.contains("TAIL_AFTER_SHARED_ID"))
                assertFalse(second.contains("ALPHA_ONLY") || second.contains("NEXT_RESOURCE_ONLY"))
                assertTrue(next.contains("NEXT_RESOURCE_ONLY"))
                assertFalse(next.contains("BEFORE_NEXT"))
            }
        }
    }

    @Test
    fun oldFragmentMetadataAndGeneratedCachesRepairWithoutChangingEditsOrChapterIdentity() {
        fixture("issue1074-containers-fragments.epub").use { fixture ->
            val first = fixture.chapters[0].copy().apply {
                // Old imports linked the first alias back to the same href before deduplication.
                putVariable("nextUrl", url)
                endFragmentId = startFragmentId
            }
            appDb.bookChapterDao.update(first)
            val identities = identities(fixture.book)
            EpubFile.clear(fixture.book.bookUrl)
            val corrected = EpubFile.getContent(fixture.book, first).orEmpty()
            assertTrue(corrected.contains("ALPHA_ONLY"))
            assertFalse(corrected.contains("SECOND_ONLY") || corrected.contains("BEFORE_START"))
            val legacy = HtmlFormatter.formatKeepImg("<p>ALPHA_ONLY</p><p>SECOND_ONLY</p><p>TAIL_AFTER_SHARED_ID</p>")
            BookHelp.saveText(fixture.book, first, legacy)
            assertEquals(corrected, BookHelp.getContent(fixture.book, first))
            EpubFile.clear(fixture.book.bookUrl)
            assertEquals(corrected, BookHelp.getContent(fixture.book, first))

            val second = fixture.chapters[1]
            val correctedSecond = EpubFile.getContent(fixture.book, second).orEmpty()
            // The old reader cut this resource at the next resource's colliding fragment ID.
            BookHelp.saveText(fixture.book, second,
                HtmlFormatter.formatKeepImg("<p>SECOND_ONLY</p><p>BEFORE_NEXT</p>"))
            assertEquals(correctedSecond, BookHelp.getContent(fixture.book, second))
            assertTrue(correctedSecond.contains("TAIL_AFTER_SHARED_ID"))

            BookHelp.saveText(fixture.book, first, "My preserved chapter edit")
            EpubFile.clear(fixture.book.bookUrl)
            assertEquals("My preserved chapter edit", BookHelp.getContent(fixture.book, first))
            assertEquals(identities, identities(fixture.book))
            EpubFile.clear(fixture.book.bookUrl)
            assertTrue(fixture.file.delete())
            assertEquals("My preserved chapter edit", BookHelp.getContent(fixture.book, first))
        }
    }

    @Test
    fun oldImportedDirectoryHierarchyKeepsProgressBookmarksAndReaderTargets() {
        fixture("issue1074-reporter.epub").use { fixture ->
            val book = fixture.book.apply {
                durChapterIndex = 2; durChapterPos = 1; durChapterTitle = titles[2]
            }
            appDb.bookDao.update(book)
            val bookmark = Bookmark(bookName = book.name, chapterIndex = 7, chapterPos = 9, chapterName = titles[7])
            appDb.bookmarkDao.insert(bookmark)
            val identities = identities(book)
            val state = TocListState().apply {
                setFullChapters(fixture.chapters, reverseOrder = false, currentChapterIndex = 2,
                    epubToc = EpubFile.getToc(book))
            }
            try {
                val expanded = state.showNormal(2)
                assertEquals(titles, expanded.map { it.chapter.title })
                assertEquals(depths, expanded.map { it.depth })
                assertTrue(state.toggleVolume(-2))
                assertTrue(state.toggleVolume(-1))
                val collapsed = state.showNormal(2)
                assertEquals(8, collapsed.size)
                assertTrue((collapsed.first() as TocListItem.Volume).containsCurrentChapter)
                assertTrue(collapsed.filter { it.chapter.title.startsWith("番外") }.all { it.depth == 0 })
                val search = state.showSearch(state.searchIndexes("第一节"), 2)
                assertEquals(titles.take(3), search.map { it.chapter.title })
                assertTrue(state.expandVolumeContainingChapter(2))
                assertEquals(titles, state.showNormal(2).map { it.chapter.title })
                state.setFullChapters(fixture.chapters, reverseOrder = true, epubToc = EpubFile.getToc(book))
                assertEquals(listOf(9, 8, 6, 7, 5, 3, 4, 0, 1, 2).map(titles::get),
                    state.showNormal(2).map { it.chapter.title })
                assertEquals(2, appDb.bookDao.getBook(book.bookUrl)!!.durChapterIndex)
                assertEquals(1, appDb.bookDao.getBook(book.bookUrl)!!.durChapterPos)
                assertEquals(identities, identities(book))
                assertEquals(listOf(bookmark), appDb.bookmarkDao.getByBook(book.name, book.author))
                val extra = fixture.chapters.single { it.url == "OEBPS/Text/09.html" }
                assertEquals(8, extra.index)
                assertTrue(EpubFile.getContent(book, extra).orEmpty().contains("正文"))
            } finally { appDb.bookmarkDao.delete(bookmark) }
        }
    }

    @Test
    fun oldPhysicallyReversedCacheKeepsIndexesAndContainerContentBoundaries() {
        fixture("issue1074-containers-fragments.epub").use { fixture ->
            val book = fixture.book.apply {
                setReverseToc(true); durChapterIndex = 1; durChapterPos = 7; durChapterTitle = "Second"
            }
            val reversed = fixture.chapters.reversed().mapIndexed { index, chapter -> chapter.copy(index = index) }
            appDb.bookChapterDao.delByBook(book.bookUrl)
            appDb.bookChapterDao.insert(*reversed.toTypedArray())
            appDb.bookDao.update(book)
            val identities = identities(book)
            val epubToc = EpubFile.getToc(book)
            val state = TocListState().apply { setFullChapters(reversed, reverseOrder = true, epubToc = epubToc) }
            val rows = state.showNormal(1)
            assertEquals(listOf("Next", "Container", "First", "Second", "Alias"), rows.map { it.chapter.title })
            assertNull(rows[1].readingChapter)
            assertEquals(1, rows[3].readingChapter!!.index)
            assertTrue(state.toggleVolume(rows[1].chapter.index))
            assertEquals(listOf("Next", "Container"), state.showNormal(1).map { it.chapter.title })
            assertTrue(state.toggleVolume(rows[1].chapter.index))
            state.setFullChapters(reversed, reverseOrder = false, epubToc = epubToc)
            assertEquals(listOf("Container", "First", "Alias", "Second", "Next"),
                state.showNormal(1).map { it.chapter.title })
            assertEquals(identities, identities(book))
            assertEquals(7, appDb.bookDao.getBook(book.bookUrl)!!.durChapterPos)
            val chapter = fixture.chapters.single { it.url == "OEBPS/a.xhtml#cut" }
            val content = EpubFile.getContent(book, chapter).orEmpty()
            assertTrue(content.contains("SECOND_ONLY") && content.contains("TAIL_AFTER_SHARED_ID"))
            assertFalse(content.contains("ALPHA_ONLY") || content.contains("NEXT_RESOURCE_ONLY"))
        }
    }

    private fun fixture(asset: String): Fixture {
        val file = File.createTempFile("epub-hierarchy-", ".epub", context.cacheDir)
        instrumentation.context.assets.open(asset).use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
        val book = Book(bookUrl = file.absolutePath, originName = file.name,
            name = "EPUB ${UUID.randomUUID()}", type = BookType.local or BookType.text, charset = "UTF-8")
        book.setPageAnim(PageAnim.noAnim)
        book.setTocExpanded(true)
        val chapters = EpubFile.getChapterList(book)
        book.totalChapterNum = chapters.size
        book.latestChapterTime = System.currentTimeMillis()
        book.durChapterTitle = chapters.first().title
        appDb.bookDao.insert(book)
        appDb.bookChapterDao.insert(*chapters.toTypedArray())
        return Fixture(file, book, chapters)
    }

    private class Fixture(val file: File, val book: Book, val chapters: List<BookChapter>) : AutoCloseable {
        override fun close() {
            BookHelp.clearCache(book)
            EpubFile.clear(book.bookUrl)
            appDb.bookDao.delete(book)
            book.coverUrl?.let { File(it).delete() }
            file.delete()
        }
    }

    private fun identities(book: Book) = appDb.bookChapterDao.getChapterList(book.bookUrl).map {
        listOf(it.index, it.url, it.title, it.startFragmentId, it.endFragmentId, it.getVariable("nextUrl"))
    }
}
