package io.legado.app.data.repository

import android.net.Uri
import androidx.core.net.toUri

import io.legado.app.constant.AppConst
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookProgress
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.ReadRecord
import io.legado.app.data.entities.saveWithCover
import io.legado.app.help.AppWebDav
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.book.update
import io.legado.app.help.config.AppConfig
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.ACache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Semaphore
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MangaReaderDataRepository @Inject constructor() {

    fun chapterCount(bookUrl: String): Int = appDb.bookChapterDao.getChapterCount(bookUrl)

    fun source(origin: String): BookSource? = appDb.bookSourceDao.getBookSource(origin)

    fun chapter(bookUrl: String, index: Int): BookChapter? =
        appDb.bookChapterDao.getChapter(bookUrl, index)

    fun readRecord(book: Book): ReadRecord =
        appDb.readRecordDao.getRecord(AppConst.androidId, book.name, book.author)
            ?: ReadRecord(deviceId = AppConst.androidId, bookName = book.name, author = book.author)

    fun saveReadRecord(record: ReadRecord, book: Book, elapsed: Long) {
        if (AppConfig.enableReadRecord) record.saveWithCover(book, elapsed)
    }

    fun cachedContent(book: Book, chapter: BookChapter): String? =
        BookHelp.getContent(book, chapter)

    fun hasCachedContent(book: Book, chapter: BookChapter): Boolean =
        BookHelp.hasContent(book, chapter)

    fun imageUrls(chapter: BookChapter, content: String): Flow<String> =
        BookHelp.flowImages(chapter, content)

    suspend fun cachedImageDimensions(path: String): Pair<Int, Int>? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        if (!java.io.File(path).isFile) return@withContext null
        val size = io.legado.app.utils.BitmapUtils.getImageSize(path) ?: io.legado.app.utils.SvgUtils.getSize(path)
        size?.let { it.width to it.height }
    }

    fun imageCachePath(book: Book, sourceUrl: String): String =
        BookHelp.getImage(book, sourceUrl).absolutePath

    val preDownloadCount: Int
        get() = AppConfig.preDownloadNum

    val progressSyncEnabled: Boolean
        get() = AppConfig.syncBookProgress

    val enhancedProgressSyncEnabled: Boolean
        get() = AppConfig.syncBookProgressPlus

    val automaticSourceChangeEnabled: Boolean
        get() = AppConfig.autoChangeSource

    val sourceSearchConcurrency: Int
        get() = AppConfig.threadCount

    val titleBarAdditionVisible: Boolean
        get() = AppConfig.showReadTitleBarAddition

    val globalEInkModeEnabled: Boolean
        get() = AppConfig.isEInkMode

    var openReaderLinksInBrowser: Boolean
        get() = AppConfig.readUrlInBrowser
        set(value) {
            AppConfig.readUrlInBrowser = value
        }

    val addToShelfConfirmationEnabled: Boolean
        get() = AppConfig.showAddToShelfAlert

    fun savedImageDirectory(): Uri? = ACache.get()
        .getAsString(AppConst.imagePathKey)
        ?.takeIf(String::isNotEmpty)
        ?.toUri()

    fun rememberImageDirectory(uri: Uri) {
        ACache.get().put(AppConst.imagePathKey, uri.toString())
    }

    fun saveProgress(book: Book, chapterIndex: Int, chapterPosition: Int, readAt: Long, owner: ReaderProgressOwner? = null) {
        val title = chapter(book.bookUrl, chapterIndex)?.getDisplayTitle(
                ContentProcessor.get(book.name, book.origin).getTitleReplaceRules(),
                book.getUseReplaceRule(), replaceBook = book.toReplaceBook(),
            )
        if (owner == null) {
            appDb.bookDao.updateReaderProgress(book.bookUrl, book.origin, chapterIndex, chapterPosition, title, readAt)
        } else {
            appDb.readerSessionDao.saveProgress(owner.sessionId, owner.token, owner.revision,
                book.bookUrl, book.origin, chapterIndex, chapterPosition, title, readAt)
        }
    }

    fun downloadContent(
        source: BookSource,
        scope: CoroutineScope,
        chapter: BookChapter,
        book: Book,
        semaphore: Semaphore?,
        success: suspend (String) -> Unit,
        error: suspend () -> Unit,
        cancel: suspend () -> Unit,
    ) {
        WebBook.getContent(
            scope,
            source,
            book,
            chapter,
            start = CoroutineStart.LAZY,
            executeContext = IO,
            semaphore = semaphore,
        ).onSuccess { success(it) }
            .onError { error() }
            .onCancel { cancel() }
            .start()
    }

    fun replaceChapterList(oldBook: Book, book: Book, chapters: List<BookChapter>) {
        if (oldBook.bookUrl == book.bookUrl) {
            book.update()
        } else {
            appDb.bookDao.replace(oldBook, book)
            BookHelp.updateCacheFolder(oldBook, book)
        }
        appDb.bookChapterDao.delByBook(oldBook.bookUrl)
        appDb.bookChapterDao.insert(*chapters.toTypedArray())
    }

    suspend fun loadChapterList(source: BookSource, book: Book): Result<List<BookChapter>> =
        WebBook.getChapterListAwait(source, book)

    suspend fun cloudProgress(book: Book): BookProgress? = AppWebDav.getBookProgress(book)

    suspend fun uploadProgress(book: Book, success: (() -> Unit)? = null) {
        AppWebDav.uploadBookProgress(book, onSuccess = {
            appDb.bookDao.markReaderProgressSynced(book.bookUrl, book.origin, book.name, book.author,
                book.durChapterIndex, book.durChapterPos, book.durChapterTime, System.currentTimeMillis())
            success?.invoke()
        })
    }

    suspend fun uploadProgress(progress: BookProgress, success: (() -> Unit)? = null) {
        AppWebDav.uploadBookProgress(progress, success)
    }
}
