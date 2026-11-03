package io.legado.app.data.cache

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import io.legado.app.data.entities.Book
import io.legado.app.utils.MD5Utils
import io.legado.app.help.book.isEpub
import java.io.Closeable
import splitties.init.appCtx

fun bookCacheKey(book: Book) = CacheResourceKey("book", MD5Utils.md5Encode16(book.bookUrl))
fun epubCacheKey(name: String) = CacheResourceKey("epub", name)

/** Adapter for parser/script APIs that have not yet become constructor-injected repositories. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface BookCacheEntryPoint {
    fun cacheChanges(): BookCacheChanges
    fun cacheResourceLeases(): CacheResourceLeases
    fun cacheMaintenance(): BookCacheMaintenanceRepository
    fun epubCacheStore(): EpubCacheStore
    fun textReaderImageStore(): TextReaderImageStore
}
internal fun cacheResourceLeases(): CacheResourceLeases =
    EntryPointAccessors.fromApplication(appCtx, BookCacheEntryPoint::class.java).cacheResourceLeases()
internal fun cacheMaintenance(): BookCacheMaintenanceRepository =
    EntryPointAccessors.fromApplication(appCtx, BookCacheEntryPoint::class.java).cacheMaintenance()

internal fun epubCacheStore(): EpubCacheStore =
    EntryPointAccessors.fromApplication(appCtx, BookCacheEntryPoint::class.java).epubCacheStore()
internal fun textReaderImageStore(): TextReaderImageStore =
    EntryPointAccessors.fromApplication(appCtx, BookCacheEntryPoint::class.java).textReaderImageStore()

fun CacheResourceLeases.acquireBook(book: Book): Closeable {
    val bookLease = acquire(bookCacheKey(book))
    val epubLease = try {
        if (book.isEpub) acquire(epubCacheKey(book.originName)) else null
    } catch (error: Throwable) {
        bookLease.close()
        throw error
    }
    return Closeable { try { epubLease?.close() } finally { bookLease.close() } }
}

internal fun bookCacheChanges(): BookCacheChanges =
    EntryPointAccessors.fromApplication(appCtx,BookCacheEntryPoint::class.java).cacheChanges()
