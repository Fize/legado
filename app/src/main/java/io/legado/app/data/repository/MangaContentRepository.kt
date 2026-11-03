package io.legado.app.data.repository

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.help.book.BookHelp
import io.legado.app.model.localBook.LocalBook
import io.legado.app.model.webBook.WebBook
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MangaContentRepository @Inject constructor() {

    fun hasReadableLocalFile(book: Book): Boolean = runCatching {
        LocalBook.getBookInputStream(book).close()
    }.isSuccess

    suspend fun loadBookInfo(source: BookSource, book: Book) {
        WebBook.getBookInfoAwait(source, book, canReName = false)
    }

    suspend fun loadChapterList(
        source: BookSource,
        book: Book,
        refresh: Boolean = true,
    ): Result<List<BookChapter>> = WebBook.getChapterListAwait(source, book, refresh)

    suspend fun findReadableSource(
        source: BookSource,
        name: String,
        author: String,
    ): Pair<Book, List<BookChapter>> {
        val book = WebBook.preciseSearchAwait(source, name, author).getOrThrow()
        if (book.tocUrl.isEmpty()) WebBook.getBookInfoAwait(source, book)
        val chapters = WebBook.getChapterListAwait(source, book).getOrThrow()
        val chapter = chapters.getOrElse(book.durChapterIndex) { chapters.last() }
        val nextChapter = chapters.getOrElse(chapter.index) { chapters.first() }
        WebBook.getContentAwait(
            bookSource = source,
            book = book,
            bookChapter = chapter,
            nextChapterUrl = nextChapter.url,
        )
        return book to chapters
    }

    fun deleteCachedChapter(book: Book, chapter: BookChapter) {
        BookHelp.delContent(book, chapter)
    }
}
