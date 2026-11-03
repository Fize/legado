package io.legado.app.data.repository

import io.legado.app.data.AppDatabase
import io.legado.app.data.cache.CacheResourceLeases
import io.legado.app.data.cache.acquireBook
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.getLocalUri
import io.legado.app.help.book.isEpub
import io.legado.app.model.TextReaderChapterKey
import io.legado.app.model.localBook.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

internal interface EpubReadingSource {
    fun chapter(book: Book, chapter: BookChapter): EpubReadingChapter?
    fun resource(book: Book, address: EpubResourceAddress): EpubReadingResource?
    fun resourceByKey(book: Book, key: String): EpubReadingResource? = null
}

private object FileEpubReadingSource : EpubReadingSource {
    override fun chapter(book: Book, chapter: BookChapter) = EpubFile.getReaderChapter(book, chapter)
    override fun resource(book: Book, address: EpubResourceAddress) = EpubFile.getReaderResource(book, address)
    override fun resourceByKey(book: Book, key: String) = EpubFile.getReaderResourceByKey(book, key)
}

data class EpubChapterTarget(val index: Int, val url: String)

/** Every raw chapter or cross-file note is read under the exact reader owner and book resource lease. */
@Singleton
class TextReaderEpubRepository internal constructor(private val database: AppDatabase,
    private val resources: CacheResourceLeases, private val source: EpubReadingSource) {
    @Inject constructor(database: AppDatabase, resources: CacheResourceLeases) : this(database, resources, FileEpubReadingSource)

    suspend fun chapter(key: TextReaderChapterKey): EpubReadingChapter = read(key) { book, chapter ->
        checkNotNull(source.chapter(book, chapter)) { "EPUB chapter is unavailable" }
    }

    suspend fun resource(key: TextReaderChapterKey, address: EpubResourceAddress): EpubReadingResource? =
        read(key) { book, _ -> source.resource(book, address) }
    suspend fun resourceByKey(key: TextReaderChapterKey, resourceKey: String): EpubReadingResource? =
        read(key) { book, _ -> source.resourceByKey(book, resourceKey) }

    /** Resolve against the actual clipped reading chapters, including several chapters in one file. */
    suspend fun targetChapter(key: TextReaderChapterKey, address: EpubResourceAddress): EpubChapterTarget? {
        val context = currentCoroutineContext()
        return read(key) { book, _ ->
            fun hasAnchor(html: String, fragment: String) = org.jsoup.Jsoup.parse(html).getAllElements().any {
                it.id() == fragment || it.attr("xml:id") == fragment || it.normalName() == "a" && it.attr("name") == fragment
            }
            val fragment = address.fragment
            // An explicit id="top" wins over the conventional missing-#top fallback.
            val root = fragment.isNullOrEmpty() || fragment.equals("top",true) &&
                source.resource(book,address)?.let { !hasAnchor(it.html,checkNotNull(fragment)) } != false
            val chapters = database.bookChapterDao.getChapterList(key.bookUrl).map { it.infoSnapshot() }
            val ordered = chapters.sortedWith(compareBy<BookChapter> {
                when {
                    it.url.substringBeforeLast("#") == address.path && it.startFragmentId == address.fragment -> 0
                    it.url.substringBeforeLast("#") == address.path -> 1
                    else -> 2
                }
            })
            val found = ordered.firstOrNull { chapter ->
                // Cooperative cancellation also bounds a missing target in a large table of contents.
                context.ensureActive()
                requireOwner(key)
                val parts = source.chapter(book, chapter)?.parts.orEmpty()
                parts.any { part ->
                    if (part.address.path != address.path) false
                    else if (root) part.startsAtRoot
                    else hasAnchor(part.html,checkNotNull(fragment))
                }
            } ?: return@read null
            check(database.bookChapterDao.getChapter(key.bookUrl,found.index)?.url == found.url) { "Reader chapter changed" }
            EpubChapterTarget(found.index, found.url)
        }
    }

    private suspend fun <T> read(key: TextReaderChapterKey, load: (Book, BookChapter) -> T): T = withContext(Dispatchers.IO) {
        val (book, chapter) = database.runInTransaction<Pair<Book, BookChapter>> {
            requireOwner(key)
            val book = checkNotNull(database.bookDao.getBook(key.bookUrl)) { "Reader book was removed" }
            check(book.origin == key.origin && book.isEpub) { "Reader EPUB changed" }
            val chapter = checkNotNull(database.bookChapterDao.getChapter(key.bookUrl, key.chapterIndex)) { "Reader chapter was removed" }
            book.infoSnapshot() to chapter.infoSnapshot()
        }
        resources.acquireBook(book).use {
            val location = book.getLocalUri().toString()
            currentCoroutineContext().ensureActive()
            val result = load(book, chapter)
            currentCoroutineContext().ensureActive()
            database.runInTransaction {
                requireOwner(key)
                val current = checkNotNull(database.bookDao.getBook(key.bookUrl)) { "Reader book was removed" }
                check(current.origin == book.origin && current.isEpub && current.originName == book.originName &&
                    current.charset == book.charset && current.getLocalUri().toString() == location) { "Reader EPUB changed" }
                check(database.bookChapterDao.getChapter(key.bookUrl, key.chapterIndex)?.url == chapter.url) { "Reader chapter changed" }
            }
            result
        }
    }

    private fun requireOwner(key: TextReaderChapterKey) {
        check(database.readerSessionDao.ownsBook(key.owner.sessionId, key.owner.token, key.owner.revision, key.bookUrl)) {
            "Reader no longer owns this book"
        }
    }
}
