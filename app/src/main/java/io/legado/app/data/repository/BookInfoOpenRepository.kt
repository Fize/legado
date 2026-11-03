package io.legado.app.data.repository

import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.Book
import io.legado.app.help.book.isNotShelf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class BookInfoOpenRequest(val id: String, val bookUrl: String, val previewKey: String)
internal data class BookInfoOpenSelection(val request: BookInfoOpenRequest?, val lease: BookInfoPreviewLease)

/** Captures a selected result before another list can overwrite the shared search cache. */
@Singleton
class BookInfoOpenRepository @Inject constructor(
    private val database: AppDatabase,
    private val previews: BookInfoPreviewStore,
) {
    suspend fun shelf(bookUrl: String, id: String): BookInfoOpenRequest = withContext(Dispatchers.IO) {
        val preview = database.runInTransaction<BookInfoPreview> {
            val book = checkNotNull(database.bookDao.getBook(bookUrl)) { "Book is no longer available" }
            BookInfoPreview(book, database.bookChapterDao.getChapterList(bookUrl), book.origin, !book.isNotShelf)
        }
        preparePreview(preview, id)
    }

    suspend fun selected(book: Book, id: String): BookInfoOpenRequest = withContext(Dispatchers.IO) {
        val preview = database.runInTransaction<BookInfoPreview> {
            val shelf = database.bookDao.getBook(book.bookUrl)?.takeUnless { it.isNotShelf }
                ?: database.bookDao.getBook(book.name, book.author)?.takeUnless { it.isNotShelf }
            val selected = shelf ?: book
            BookInfoPreview(selected, if (shelf == null) emptyList() else database.bookChapterDao.getChapterList(selected.bookUrl),
                selected.origin, shelf != null)
        }
        preparePreview(preview, id)
    }

    internal fun preparePreview(preview: BookInfoPreview, id: String = UUID.randomUUID().toString()): BookInfoOpenRequest {
        val lease = previews.open(id)
        check(previews.save(lease, 1, preview)) { "Cannot save the selected book preview" }
        return BookInfoOpenRequest(id, preview.book.bookUrl, id)
    }

    suspend fun pending(id: String): BookInfoOpenRequest? = withContext(Dispatchers.IO) {
        previews.peek(id)?.let { BookInfoOpenRequest(id, it.book.bookUrl, id) }
    }

    internal suspend fun retain(id: String): BookInfoOpenSelection = withContext(Dispatchers.IO) {
        val lease = previews.open(id)
        BookInfoOpenSelection(lease.restored?.let { BookInfoOpenRequest(id, it.book.bookUrl, id) }, lease)
    }

    internal suspend fun release(lease: BookInfoPreviewLease) = withContext(Dispatchers.IO) {
        if (database.bookInfoRequestDao.get(lease.key) == null) previews.discard(lease)
    }

    internal fun abandon(lease: BookInfoPreviewLease) {
        io.legado.app.help.globalExecutor.execute {
            runCatching {
                if (database.bookInfoRequestDao.get(lease.key) == null) previews.discard(lease)
            }.onFailure { io.legado.app.constant.AppLog.put("Discard selected book preview failed", it) }
        }
    }

    fun snapshot(book: Book): Book = book.infoSnapshot()
}
