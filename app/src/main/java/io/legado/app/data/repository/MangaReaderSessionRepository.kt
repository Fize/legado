package io.legado.app.data.repository

import android.net.Uri
import io.legado.app.constant.AppConst
import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookProgress
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.replaceBookAfterSourceChange
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.AppWebDav
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.removeType
import io.legado.app.help.book.update
import io.legado.app.model.MangaReaderSession
import io.legado.app.ui.book.manga.entities.MangaChapter
import io.legado.app.ui.book.manga.entities.MangaContent
import io.legado.app.utils.ACache
import io.legado.app.utils.FileDoc
import io.legado.app.utils.createFileIfNotExist
import io.legado.app.utils.writeFile
import javax.inject.Inject
import dagger.hilt.android.scopes.ViewModelScoped
import kotlinx.coroutines.CoroutineScope

data class MangaReaderSnapshot(
    val book: Book?,
    val source: BookSource?,
    val currentChapter: MangaChapter?,
    val previousChapter: MangaChapter?,
    val nextChapter: MangaChapter?,
    val content: MangaContent,
    val chapterIndex: Int,
    val chapterPosition: Int,
    val chapterCount: Int,
    val hasNextChapter: Boolean,
    val inBookshelf: Boolean,
)

interface MangaReaderSessionListener {
    fun onContentChanged()
    fun onLoadFailed(message: String, retry: Boolean)
    fun onCloudProgressFound(progress: BookProgress)
    fun onLoadingStarted()
    fun onNextChapterStarted()
}

/**
 * Repository boundary for the active manga reader session.
 *
 * Keeping callbacks and persistence access here prevents engine details and the database
 * singleton from leaking into the Compose state layer.
 */
@ViewModelScoped
class MangaReaderSessionRepository @Inject constructor(
    private val session: MangaReaderSession,
) {
    private var listener: MangaReaderSessionListener? = null
    private val callback = object : MangaReaderSession.Callback {
        override fun upContent() = listener?.onContentChanged() ?: Unit
        override fun loadFail(msg: String, retry: Boolean) =
            listener?.onLoadFailed(msg, retry) ?: Unit
        override fun sureNewProgress(progress: BookProgress) =
            listener?.onCloudProgressFound(progress) ?: Unit
        override fun showLoading() = listener?.onLoadingStarted() ?: Unit
        override fun startLoad() = listener?.onNextChapterStarted() ?: Unit
    }

    fun attach(listener: MangaReaderSessionListener, scope: CoroutineScope) {
        session.bind(scope)
        this.listener = listener
        session.register(callback)
    }

    fun detach(listener: MangaReaderSessionListener) {
        if (this.listener === listener) {
            this.listener = null
            session.unregister(callback)
        }
    }

    fun configureLaunch(inBookshelf: Boolean, chapterChanged: Boolean) {
        session.inBookshelf = inBookshelf
        session.chapterChanged = chapterChanged
    }

    fun setTitleHidden(hidden: Boolean) {
        session.hideTitle = hidden
    }

    suspend fun findBook(bookUrl: String): Book? = appDb.bookDao.getBook(bookUrl)

    fun bindProgressOwner(owner: ReaderProgressOwner) { session.progressOwner = owner }
    fun progressOwner(): ReaderProgressOwner? = session.progressOwner

    fun beginRequest() {
        session.pauseReading()
        session.saveRead()
        session.clearMangaChapter()
        session.book = null
        session.progressOwner = null
    }

    fun initializeBook(book: Book): Boolean {
        val sameBook = session.book?.bookUrl == book.bookUrl
        if (sameBook) session.upData(book) else session.resetData(book)
        return sameBook
    }

    fun snapshot(): MangaReaderSnapshot = MangaReaderSnapshot(
        book = session.book,
        source = session.bookSource,
        currentChapter = session.curMangaChapter,
        previousChapter = session.prevMangaChapter,
        nextChapter = session.nextMangaChapter,
        content = session.mangaContents,
        chapterIndex = session.durChapterIndex,
        chapterPosition = session.durChapterPos,
        chapterCount = session.chapterSize,
        hasNextChapter = session.hasNextChapter,
        inBookshelf = session.inBookshelf,
    )

    suspend fun enabledSources() = appDb.bookSourceDao.allTextEnabledPart

    suspend fun cloudProgress(book: Book): BookProgress? = AppWebDav.getBookProgress(book)

    fun loadContent(sameBook: Boolean) {
        if (sameBook) session.loadOrUpContent() else session.loadContent()
    }

    fun refreshContent() = session.loadOrUpContent()

    fun clearLoadedContent() = session.clearMangaChapter()

    fun awaitBackgroundTasks() {
        session.executor.submit {}.get()
    }

    suspend fun refreshBookFromDatabase() {
        val bookUrl = session.book?.bookUrl ?: return
        val refreshed = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { appDb.bookDao.getBook(bookUrl) }
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
            if (session.book?.bookUrl == bookUrl) refreshed?.let(session::upData)
        }
    }

    fun reloadContent() = session.loadContent()

    fun moveToPreviousChapter(force: Boolean = false) = session.moveToPrevChapter(force)

    fun moveToNextChapter(force: Boolean = false) = session.moveToNextChapter(force)

    fun updateVisiblePage(chapterIndex: Int, pageIndex: Int) {
        session.durChapterPos = pageIndex
        when {
            session.durChapterIndex < chapterIndex -> session.moveToNextChapter()
            session.durChapterIndex > chapterIndex -> session.moveToPrevChapter()
            else -> {
                session.curPageChanged()
                session.saveRead()
            }
        }
    }

    fun beginReading() = session.beginReading()

    fun pauseReading() = session.pauseReading()

    fun consumeChapterChanged(): Boolean = session.chapterChanged.also {
        session.chapterChanged = false
    }

    fun saveRead() = session.saveRead()

    fun syncProgress(onOlderCloudProgress: (BookProgress) -> Unit = {}) =
        session.syncProgress(onOlderCloudProgress)

    fun uploadProgress() = session.uploadProgress()

    fun cancelPreDownload() = session.cancelPreDownloadTask()

    fun markCurrentBookInBookshelf() {
        session.inBookshelf = true
    }

    fun addCurrentBookToBookshelf() {
        session.book?.removeType(BookType.notShelf)
        session.book?.save()
        session.inBookshelf = true
    }

    fun setCurrentPage(position: Int) {
        session.durChapterPos = position
    }

    fun setProgress(progress: BookProgress) = session.setProgress(progress)

    fun onChapterListUpdated(book: Book) = session.onChapterListUpdated(book)

    suspend fun replaceChapterList(
        oldBook: Book,
        book: Book,
        chapters: List<BookChapter>,
    ) {
        if (oldBook.bookUrl == book.bookUrl) {
            book.update()
        } else {
            appDb.bookDao.replace(oldBook, book)
            BookHelp.updateCacheFolder(oldBook, book)
        }
        appDb.bookChapterDao.delByBook(oldBook.bookUrl)
        appDb.bookChapterDao.insert(*chapters.toTypedArray())
        onChapterListUpdated(book)
    }

    fun applySourceChange(book: Book, chapters: List<BookChapter>) {
        val currentBook = session.book
        val owner = session.progressOwner
        book.type = if (session.inBookshelf) book.type and BookType.notShelf.inv() else book.type or BookType.notShelf
        currentBook?.migrateTo(book, chapters)
        val replace = { replaceBookAfterSourceChange(currentBook, book, chapters) }
        if (owner == null) replace() else {
            val updated = appDb.readerSessionDao.replaceOwnedBook(owner.sessionId, owner.token,
                owner.revision, checkNotNull(currentBook).bookUrl, book.bookUrl,
                book.durChapterIndex, book.durChapterPos, System.currentTimeMillis(), replace)
            session.progressOwner = updated.progressOwner()
        }
        session.resetData(book)
        session.loadContent()
    }

    fun upReadTime() = session.upReadTime()

    fun openChapter(index: Int, position: Int = 0) {
        if (index >= session.chapterSize) return
        session.showLoading()
        session.durChapterIndex = index
        session.durChapterPos = position
        session.saveRead()
        session.loadContent()
    }

    suspend fun currentChapter(bookUrl: String): BookChapter? =
        appDb.bookChapterDao.getChapter(bookUrl, session.durChapterIndex)

    fun removeCurrentBookFromBookshelf() {
        session.book?.delete()
    }

    suspend fun saveImage(sourceUrl: String, destination: Uri) {
        val book = session.book ?: return
        BookHelp.saveImage(session.bookSource, book, sourceUrl)
        val image = BookHelp.getImage(book, sourceUrl)
        if (!image.isFile) throw NoStackTraceException("图片下载失败")
        try {
            FileDoc.fromDir(destination).createFileIfNotExist(image.name).writeFile(image)
        } catch (error: Exception) {
            ACache.get().remove(AppConst.imagePathKey)
            throw error
        }
    }
}
