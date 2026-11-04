package io.legado.app.data.repository

import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.main.bookshelf.BookshelfGroupItem
import io.legado.app.ui.main.bookshelf.buildRootBookshelfGroupItems
import io.legado.app.utils.cnCompare
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Dispatchers
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

data class BookshelfContent(
    val books: List<Book>,
    val groups: List<BookGroup>,
    val groupItems: List<BookshelfGroupItem>,
)

data class BookshelfHeader(
    val bookCount: Int = 0,
    val readingCount: Int = 0,
    val continueBook: Book? = null,
)

@Singleton
class BookshelfRepository @Inject constructor() {

    fun observe(groupId: Long): Flow<BookshelfContent> {
        val groups = appDb.bookGroupDao.flowAll().map { list -> list.filter(BookGroup::show) }
        val books = appDb.bookDao.flowByGroup(groupId).map { sortBooks(it, groupId) }
        return if (groupId == BookGroup.IdRoot) {
            combine(books, groups, appDb.bookDao.flowBookshelfBooks()) { current, allGroups, allBooks ->
                val items = buildRootBookshelfGroupItems(allGroups, allBooks)
                BookshelfContent(current, allGroups, items)
            }
        } else {
            combine(books, groups) { current, allGroups ->
                BookshelfContent(current, allGroups, emptyList())
            }
        }
    }

    fun observeHeader(showRecentReading: Boolean, showStats: Boolean): Flow<BookshelfHeader> {
        if (!showRecentReading && !showStats) return flowOf(BookshelfHeader())
        return appDb.bookDao.flowShelfBookCount().map { bookCount ->
            BookshelfHeader(
                bookCount = bookCount,
                readingCount = if (showStats) appDb.bookDao.readingCount else 0,
                continueBook = if (showRecentReading) appDb.bookDao.lastReadBookOnShelf else null,
            )
        }.flowOn(Dispatchers.IO)
    }

    private fun sortBooks(books: List<Book>, groupId: Long): List<Book> =
        when (AppConfig.getBookSortByGroupId(groupId)) {
            1 -> books.sortedByDescending { it.latestChapterTime }
            2 -> books.sortedWith { first, second -> first.name.cnCompare(second.name) }
            3 -> books.sortedBy { it.order }
            4 -> books.sortedByDescending { max(it.latestChapterTime, it.durChapterTime) }
            else -> books.sortedByDescending { it.durChapterTime }
        }
}
