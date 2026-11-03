package io.legado.app.ui.book.read

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.constant.BookType
import io.legado.app.constant.PageAnim
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.Bookmark
import io.legado.app.model.localBook.TextFile
import io.legado.app.ui.book.toc.TocListItem
import io.legado.app.ui.book.toc.TocListState
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class TocReverseNavigationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun visibleTitlesReverseWithoutChangingParserOrderOrChapterIdentity() {
        fixture(listOf("Chapter 1", "Chapter 2", "Chapter 3", "Chapter 4", "Chapter 5")).use { fixture ->
            val chapters = appDb.bookChapterDao.getChapterList(fixture.book.bookUrl)
            val state = TocListState()
            state.setFullChapters(chapters, reverseOrder = false)
            assertEquals(fixture.titles, state.showNormal(1).map { it.chapter.title })
            state.setFullChapters(chapters, reverseOrder = false, reverseDisplay = true)
            assertEquals(fixture.titles.reversed(), state.showNormal(1).map { it.chapter.title })
            assertEquals(chapters.map { it.index to it.url },
                appDb.bookChapterDao.getChapterList(fixture.book.bookUrl).map { it.index to it.url })
            assertFalse("The source parser order must not change", appDb.bookDao.getBook(fixture.book.bookUrl)!!.getReverseToc())
            assertEquals(fixture.book.durChapterIndex, appDb.bookDao.getBook(fixture.book.bookUrl)!!.durChapterIndex)
        }
    }

    @Test fun reverseRetainsVolumeMembershipProloguesAndCurrentVolume() {
        val titles = listOf("Prologue 1", "Prologue 2", "Prologue 3", "Volume A", "A1", "A2", "Volume B", "B1", "B2")
        fixture(titles, setOf(3, 6), current = 7, expanded = false).use { fixture ->
            val chapters = appDb.bookChapterDao.getChapterList(fixture.book.bookUrl)
            val state = TocListState()
            state.setFullChapters(chapters, reverseOrder = false, defaultExpanded = false,
                currentChapterIndex = 7)
            val normal = state.showNormal(7)
            assertEquals(listOf("Prologue 1", "Prologue 2", "Prologue 3", "Volume A", "Volume B", "B1", "B2"),
                normal.map { it.chapter.title })
            state.setFullChapters(chapters, reverseOrder = false, defaultExpanded = false,
                currentChapterIndex = 7, reverseDisplay = true)
            val reversed = state.showNormal(7)
            val expected = listOf("Volume B", "B2", "B1", "Volume A", "Prologue 3", "Prologue 2", "Prologue 1")
            assertEquals(expected, reversed.map { it.chapter.title })
            val volumeB = reversed.filterIsInstance<TocListItem.Volume>().single { it.chapter.title == "Volume B" }
            assertTrue(volumeB.containsCurrentChapter)
            assertFalse(volumeB.collapsed)
            reversed.filterIsInstance<TocListItem.Chapter>().forEach { item ->
                assertEquals(item.chapter.title,
                    if (item.chapter.title.startsWith("Prologue")) null else volumeB.chapter.index,
                    item.parentVolumeIndex)
            }
            val current = appDb.bookDao.getBook(fixture.book.bookUrl)!!
            assertEquals("Current chapter identity must survive reversal", "B1",
                appDb.bookChapterDao.getChapter(current.bookUrl, current.durChapterIndex)!!.title)
            assertEquals(147, current.durChapterPos)
            assertEquals("B1", appDb.bookmarkDao.getByBook(current.name, current.author).single().chapterName)
        }
    }

    @Test fun returningToReaderKeepsActualChapterAndPositionAndBookmarkTarget() {
        fixture((1..5).map { "Chapter $it" }, current = 3).use { fixture ->
            fixture.book.durChapterPos = 1470
            appDb.bookDao.update(fixture.book)
            val book = appDb.bookDao.getBook(fixture.book.bookUrl)!!
            val original = appDb.bookChapterDao.getChapter(book.bookUrl, book.durChapterIndex)!!
            val position = book.durChapterPos
            val bookmark = appDb.bookmarkDao.getByBook(book.name, book.author).single()
            val state = TocListState().apply {
                setFullChapters(appDb.bookChapterDao.getChapterList(book.bookUrl), reverseOrder = false, reverseDisplay = true)
            }
            assertEquals(fixture.titles.reversed(), state.showNormal(book.durChapterIndex).map { it.chapter.title })
            assertEquals(original.url, appDb.bookChapterDao.getChapter(book.bookUrl, book.durChapterIndex)!!.url)
            assertEquals(position, appDb.bookDao.getBook(book.bookUrl)!!.durChapterPos)
            assertEquals("Chapter 4", appDb.bookChapterDao.getChapter(book.bookUrl, bookmark.chapterIndex)!!.title)
        }
    }

    @Test fun legacyStoredReverseOrderKeepsItsChapterIndexesAndParserPreference() {
        fixture((1..5).map { "Chapter $it" }).use { fixture ->
            val book = fixture.book
            val reversed = appDb.bookChapterDao.getChapterList(book.bookUrl).reversed()
                .mapIndexed { index, chapter -> chapter.copy(index = index) }
            appDb.bookChapterDao.delByBook(book.bookUrl)
            appDb.bookChapterDao.insert(*reversed.toTypedArray())
            book.setReverseToc(true)
            book.durChapterIndex = 3
            appDb.bookDao.update(book)
            fun identities() = appDb.bookChapterDao.getChapterList(book.bookUrl).map { listOf(it.index, it.url, it.title) }
            val original = identities()
            val chapters = appDb.bookChapterDao.getChapterList(book.bookUrl)
            val state = TocListState()
            state.setFullChapters(chapters, reverseOrder = true)
            assertEquals(fixture.titles.reversed(), state.showNormal(3).map { it.chapter.title })
            state.setFullChapters(chapters, reverseOrder = true, reverseDisplay = true)
            assertEquals(fixture.titles, state.showNormal(3).map { it.chapter.title })
            assertEquals(original, appDb.bookChapterDao.getChapterList(book.bookUrl).map { listOf(it.index, it.url, it.title) })
            assertTrue(appDb.bookDao.getBook(book.bookUrl)!!.getReverseToc())
            assertEquals(3, appDb.bookDao.getBook(book.bookUrl)!!.durChapterIndex)
        }
    }

    private fun fixture(titles: List<String>, volumes: Set<Int> = emptySet(), current: Int = 1,
                        expanded: Boolean = true): Fixture {
        val file = File.createTempFile("toc-reverse-", ".txt", context.cacheDir)
        val book = Book(bookUrl = file.absolutePath, originName = file.name, name = file.name,
            charset = "UTF-8", type = BookType.local or BookType.text, totalChapterNum = titles.size,
            durChapterIndex = current, durChapterPos = 147, durChapterTitle = titles[current]).apply {
            setPageAnim(PageAnim.noAnim)
            setTocExpanded(expanded)
        }
        var offset = 0L
        val chapters = titles.mapIndexed { index, title ->
            val text = (1..40).joinToString("\n", postfix = "\n") { "$title content line $it preserves its original chapter identity." }
            val bytes = text.toByteArray(Charsets.UTF_8)
            file.appendBytes(bytes)
            BookChapter(bookUrl = book.bookUrl, url = "chapter-$index", index = index, title = title,
                isVolume = index in volumes, start = offset, end = offset + bytes.size).also { offset += bytes.size }
        }
        // These chapters describe the completed file; opening it must not trigger a fresh TXT parse.
        book.latestChapterTime = file.lastModified()
        appDb.bookDao.insert(book)
        appDb.bookChapterDao.insert(*chapters.toTypedArray())
        val bookmark = Bookmark(bookName = book.name, bookAuthor = book.author,
            chapterIndex = current, chapterPos = 147, chapterName = titles[current], bookText = "original location")
        appDb.bookmarkDao.insert(bookmark)
        return Fixture(file, book, bookmark, titles)
    }

    private class Fixture(val file: File, val book: Book, val bookmark: Bookmark, val titles: List<String>) : AutoCloseable {
        override fun close() {
            appDb.bookmarkDao.delete(bookmark)
            appDb.bookDao.delete(book)
            TextFile.clear()
            file.delete()
        }
    }

}
