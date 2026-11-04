package io.legado.app.ui.main.bookshelf

import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.data.entities.BookshelfBook
import io.legado.app.utils.cnCompare
import kotlin.math.max

data class BookshelfGroupItem(
    val group: BookGroup,
    val previewBooks: List<BookshelfBook>,
    val latestReadTime: Long = Long.MIN_VALUE,
) {
    val coverSignature: List<List<String?>> = previewBooks.map { book ->
        listOf(
            book.bookUrl,
            book.displayCover,
            book.name,
            book.author,
            book.coverSourceOrigin,
        )
    }
}

internal fun buildBookshelfGroupItems(
    groups: List<BookGroup>,
    books: List<BookshelfBook>,
): List<BookshelfGroupItem> {
    return groups.map { group ->
        val memberBooks = books.filter { it.belongsToGroup(group.groupId) }
        val previewBooks = if (group.cover.isNullOrBlank()) {
            sortBookshelfBooks(
                memberBooks,
                group.getRealBookSort(),
            ).take(4)
        } else {
            emptyList()
        }
        BookshelfGroupItem(
            group = group,
            previewBooks = previewBooks,
            latestReadTime = memberBooks.maxOfOrNull { it.durChapterTime } ?: Long.MIN_VALUE,
        )
    }
}

private data class BookshelfSortEntry(
    val item: Any,
    val readTime: Long,
    val isGroup: Boolean,
    val tieOrder: Int,
    val index: Int,
)

internal fun orderBookshelfItems(
    groupItems: List<BookshelfGroupItem>,
    books: List<Book>,
): List<Any> {
    val entries = groupItems.mapIndexed { index, item ->
        BookshelfSortEntry(item, item.latestReadTime, true, item.group.order, index)
    } + books.mapIndexed { index, book ->
        BookshelfSortEntry(book, book.durChapterTime, false, book.order, index)
    }
    return entries.sortedWith(
        compareByDescending<BookshelfSortEntry> { it.readTime }
            .thenByDescending { it.isGroup }
            .thenBy { it.tieOrder }
            .thenBy { it.index },
    ).map { it.item }
}

internal fun buildRootBookshelfGroupItems(
    groups: List<BookGroup>,
    books: List<BookshelfBook>,
): List<BookshelfGroupItem> {
    return buildBookshelfGroupItems(groups, books)
        .filterNot { it.group.isRootHiddenSystemGroup }
        .filter { it.previewBooks.isNotEmpty() }
}

internal val BookGroup.isRootHiddenSystemGroup: Boolean
    get() = groupId == BookGroup.IdAll ||
        groupId == BookGroup.IdNetNone ||
        groupId == BookGroup.IdLocalNone

internal fun sortBookshelfBooks(books: List<BookshelfBook>, sort: Int): List<BookshelfBook> {
    return when (sort) {
        1 -> books.sortedByDescending { it.latestChapterTime }
        2 -> books.sortedWith { first, second -> first.name.cnCompare(second.name) }
        3 -> books.sortedBy { it.order }
        4 -> books.sortedByDescending { max(it.latestChapterTime, it.durChapterTime) }
        else -> books.sortedByDescending { it.durChapterTime }
    }
}

private fun BookshelfBook.belongsToGroup(groupId: Long): Boolean {
    val isAudio = type and BookType.audio > 0
    val isLocal = type and BookType.local > 0
    val isVideo = type and BookType.video > 0
    return when (groupId) {
        BookGroup.IdAll -> true
        BookGroup.IdLocal -> isLocal
        BookGroup.IdAudio -> isAudio
        BookGroup.IdNetNone -> !isLocal && !isAudio && !isVideo && !hasUserGroup
        BookGroup.IdLocalNone -> isLocal && !hasUserGroup
        BookGroup.IdVideo -> isVideo
        BookGroup.IdError -> type and BookType.updateError > 0
        else -> (groupId > 0 || groupId == Long.MIN_VALUE) && (group and groupId) != 0L
    }
}
