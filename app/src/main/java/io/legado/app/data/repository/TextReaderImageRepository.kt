package io.legado.app.data.repository

import io.legado.app.data.AppDatabase
import io.legado.app.data.cache.CacheResourceLeases
import io.legado.app.data.cache.acquireBook
import io.legado.app.data.cache.TextReaderImageStore
import io.legado.app.data.entities.Book
import io.legado.app.help.book.*
import io.legado.app.help.glide.imageSourceCacheIdentity
import io.legado.app.help.http.CookieStore
import io.legado.app.utils.GSON
import io.legado.app.model.TextReaderChapterKey
import io.legado.app.model.TextReaderImage
import io.legado.app.model.localBook.EpubFile
import io.legado.app.model.localBook.MobiFile
import io.legado.app.model.localBook.PdfFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import java.io.IOException

/** Only visible images are requested. Decoding receives owned bytes, never a shared recycled bitmap. */
@Singleton
class TextReaderImageRepository @Inject constructor(
    private val database: AppDatabase, private val resources: CacheResourceLeases, private val cache: TextReaderImageStore,
) {
    private val loads = Semaphore(4)
    suspend fun load(key: TextReaderChapterKey, image: TextReaderImage, force: Boolean = false): ByteArray = loads.withPermit {
        withContext(Dispatchers.IO) {
            require(image.source.isNotBlank()) { "Image address is empty" }
            val (book, source) = database.runInTransaction<Pair<Book, io.legado.app.data.entities.BookSource?>> {
                requireOwner(key)
                val book = checkNotNull(database.bookDao.getBook(key.bookUrl))
                check(book.origin == key.origin) { "Reader source changed" }
                book.infoSnapshot() to database.bookSourceDao.getBookSource(key.origin)?.infoSnapshot()
            }
            resources.acquireBook(book).use {
                cache.open(book.bookUrl).use { lease ->
                    val version = BookHelp.imageSaveVersion(book, image.source)
                    val sourceIdentity = GSON.toJson(source)
                    val login = source?.getLoginHeader()
                    val cookie = if (book.isLocal) "" else CookieStore.getCookie(image.source)
                    val identity = imageSourceCacheIdentity(image.source,
                        mapOf("cookie" to cookie, "login" to login.orEmpty(), "version" to version.toString()),
                        sourceIdentity, GSON.toJson(listOf(book.bookUrl, book.name, book.author, book.variableMap.toSortedMap())), false)
                    val stored = if (force) null else try { cache.read(lease, identity)?.takeIf { it.isNotEmpty() } }
                        catch (_: IOException) { null }
                    val bytes = stored ?: (if (force) null else cached(book, image.source, version)?.takeIf { it.isNotEmpty() }) ?: when {
                        book.isEpub -> EpubFile.getImage(book, image.source)?.use { it.readBytes() }
                        book.isMobi -> MobiFile.getImage(book, image.source)?.use { it.readBytes() }
                        book.isPdf -> PdfFile.getImage(book, image.source)?.use { it.readBytes() }
                        else -> BookHelp.fetchImage(source, book, image.source)
                    }
                    currentCoroutineContext().ensureActive()
                    check(BookHelp.imageSaveVersion(book, image.source) == version) { "Reader image changed" }
                    database.runInTransaction {
                        requireOwner(key)
                        check(database.bookDao.getBook(key.bookUrl)?.origin == key.origin) { "Reader source changed" }
                        check(GSON.toJson(database.bookSourceDao.getBookSource(key.origin)) == sourceIdentity) { "Reader source changed" }
                    }
                    check(lease.isCurrent()) { "Reader image changed" }
                    checkNotNull(bytes) { "Image could not be loaded" }.also {
                        check(it.isNotEmpty()) { "Image is empty" }
                        if (stored == null && source?.getLoginHeader() == login &&
                            (book.isLocal || CookieStore.getCookie(image.source) == cookie)) {
                            try { cache.write(lease, identity, it) } catch (_: IOException) { /* Cache availability does not prevent drawing owned bytes. */ }
                        }
                        check(lease.isCurrent()) { "Reader image changed" }
                    }
                }
            }
        }
    }

    private fun cached(book: Book, source: String, version: Long): ByteArray? = synchronized(BookHelp) {
        check(BookHelp.imageSaveVersion(book, source) == version) { "Reader image changed" }
        BookHelp.getImage(book, source).takeIf { it.isFile }?.readBytes()
    }

    private fun requireOwner(key: TextReaderChapterKey) {
        check(database.readerSessionDao.ownsBook(key.owner.sessionId, key.owner.token,
            key.owner.revision, key.bookUrl)) { "Reader no longer owns this book" }
    }
}
