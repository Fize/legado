package io.legado.app.model

import io.legado.app.constant.AppLog
import io.legado.app.data.repository.MangaReaderDataRepository
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookProgress
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.ReadRecord
import io.legado.app.data.entities.updateSnapshot
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isSameNameAuthor
import io.legado.app.help.book.readSimulating
import io.legado.app.help.book.simulatedTotalChapterNum
import io.legado.app.help.globalExecutor
import io.legado.app.ui.book.manga.entities.BaseMangaPage
import io.legado.app.ui.book.manga.entities.MangaChapter
import io.legado.app.ui.book.manga.entities.MangaContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlin.math.min
import javax.inject.Inject
import dagger.hilt.android.scopes.ViewModelScoped
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext

@ViewModelScoped
@Suppress("MemberVisibilityCanBePrivate")
class MangaReaderSession @Inject constructor(
    private val dataRepository: MangaReaderDataRepository,
    private val decoder: MangaChapterDecoder = MangaChapterDecoder(dataRepository),
) : CoroutineScope {
    private var ownerJob: Job? = null
    private var ownerScope: CoroutineScope? = null
    private var contentScope: CoroutineScope? = null
    private val contentGeneration = AtomicLong()
    override val coroutineContext: CoroutineContext
        get() = checkNotNull(ownerScope) { "Manga session has no ViewModel owner" }.coroutineContext

    fun bind(scope: CoroutineScope) {
        check(ownerJob == null) { "Manga session already has an owner" }
        val job = SupervisorJob(scope.coroutineContext[Job])
        ownerJob = job
        ownerScope = CoroutineScope(scope.coroutineContext + job)
        contentScope = CoroutineScope(scope.coroutineContext + SupervisorJob(job) + IO)
    }

    private fun currentRequest(bookUrl: String, generation: Long): Boolean =
        contentGeneration.get() == generation && book?.bookUrl == bookUrl


    @Volatile var progressOwner: io.legado.app.data.repository.ReaderProgressOwner? = null
    var hideTitle: Boolean = false
    var inBookshelf = false
    @Volatile var book: Book? = null
    val executor = globalExecutor
    @Volatile var durChapterIndex = 0 //章节位置
    var chapterSize = 0//总章节
    @Volatile var durChapterPos = 0
    var chapterChanged = false
    var prevMangaChapter: MangaChapter? = null
    var curMangaChapter: MangaChapter? = null
    var nextMangaChapter: MangaChapter? = null
    var bookSource: BookSource? = null
    var readStartTime: Long = System.currentTimeMillis()
    private val readRecordLock = Any()
    private var readingActive = false
    private var readRecord = ReadRecord()
    private val loadingChapters = arrayListOf<Int>()
    var simulatedChapterSize = 0
    var mCallback: Callback? = null
    var preDownloadTask: Job? = null
    val downloadedChapters = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
    val downloadFailChapters = java.util.concurrent.ConcurrentHashMap<Int, Int>()
    private val downloadScope get() = checkNotNull(contentScope) { "Manga session has no ViewModel owner" }
    val preDownloadSemaphore = Semaphore(2)
    val mangaContents get() = buildMangaContent()
    val hasNextChapter get() = durChapterIndex < simulatedChapterSize - 1

    fun resetData(book: Book) {
        synchronized(readRecordLock) {
            this.book = book
            resetReadRecord(book)
        }
        chapterSize = dataRepository.chapterCount(book.bookUrl)
        simulatedChapterSize = if (book.readSimulating()) {
            book.simulatedTotalChapterNum()
        } else {
            chapterSize
        }
        durChapterIndex = book.durChapterIndex
        durChapterPos = book.durChapterPos
        clearMangaChapter()
        updateSource(book)
        synchronized(this) {
            loadingChapters.clear()
            downloadedChapters.clear()
            downloadFailChapters.clear()
        }
    }

    fun upData(book: Book) {
        synchronized(readRecordLock) {
            if (readRecord.bookName != book.name || readRecord.author != book.author) {
                upReadTime()
                resetReadRecord(book)
            }
            this.book = book
        }
        chapterSize = dataRepository.chapterCount(book.bookUrl)
        simulatedChapterSize = if (book.readSimulating()) {
            book.simulatedTotalChapterNum()
        } else {
            chapterSize
        }

        if (durChapterIndex != book.durChapterIndex) {
            durChapterIndex = book.durChapterIndex
            durChapterPos = book.durChapterPos
            clearMangaChapter()
        }
        updateSource(book)
        synchronized(this) {
            loadingChapters.clear()
            downloadedChapters.clear()
            downloadFailChapters.clear()
        }
    }

    fun updateSource(book: Book) {
        dataRepository.source(book.origin)?.let {
            bookSource = it
        } ?: let {
            bookSource = null
        }
    }

    fun clearMangaChapter() {
        contentGeneration.incrementAndGet()
        preDownloadTask?.cancel()
        preDownloadTask = null
        contentScope?.coroutineContext?.cancelChildren()
        synchronized(this) { loadingChapters.clear() }
        prevMangaChapter = null
        curMangaChapter = null
        nextMangaChapter = null
    }

    private fun resetReadRecord(book: Book) {
        readRecord = dataRepository.readRecord(book)
    }

    fun beginReading() = synchronized(readRecordLock) {
        if (!readingActive) {
            readStartTime = System.currentTimeMillis()
            readingActive = true
        }
    }

    fun pauseReading() = synchronized(readRecordLock) {
        if (readingActive) {
            upReadTime()
            readingActive = false
        }
    }

    //每次切换章节更新阅读记录
    fun upReadTime() {
        val (record, snapshotBook, elapsed) = synchronized(readRecordLock) {
            if (!readingActive) return
            val currentBook = book?.copy() ?: return
            if (readRecord.bookName != currentBook.name || readRecord.author != currentBook.author) {
                resetReadRecord(currentBook)
            }
            val now = System.currentTimeMillis()
            val elapsed = (now - readStartTime).coerceAtLeast(0)
            readStartTime = now
            readRecord.readTime += elapsed
            readRecord.lastRead = now
            readRecord.updateSnapshot(currentBook, durChapterIndex, durChapterPos)
            Triple(readRecord.copy(), currentBook, elapsed)
        }
        executor.execute {
            dataRepository.saveReadRecord(record, snapshotBook, elapsed)
        }
    }

    @Synchronized
    private fun addLoading(index: Int): Boolean {
        if (loadingChapters.contains(index)) return false
        loadingChapters.add(index)
        return true
    }

    @Synchronized
    fun removeLoading(index: Int) {
        loadingChapters.remove(index)
    }

    fun loadContent() {
        clearMangaChapter()
        loadContent(durChapterIndex)
        loadContent(durChapterIndex + 1)
        loadContent(durChapterIndex - 1)
    }

    fun loadOrUpContent() {
        if (curMangaChapter == null) {
            loadContent(durChapterIndex)
        } else {
            mCallback?.upContent()
        }
        if (nextMangaChapter == null) {
            loadContent(durChapterIndex + 1)
        }
        if (prevMangaChapter == null) {
            loadContent(durChapterIndex - 1)
        }
    }

    private fun loadContent(index: Int) {
        val owner = book?.copy() ?: return
        val generation = contentGeneration.get()
        downloadScope.launch {
            try {
                val chapter = dataRepository.chapter(owner.bookUrl, index) ?: return@launch
                if (!currentRequest(owner.bookUrl, generation) || !addLoading(index)) return@launch
                dataRepository.cachedContent(owner, chapter)?.let {
                    contentLoadFinish(chapter, it, generation = generation)
                } ?: download(downloadScope, chapter, generation = generation)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (currentRequest(owner.bookUrl, generation)) {
                    removeLoading(index)
                    withContext(Main.immediate) {
                        if (currentRequest(owner.bookUrl, generation)) mCallback?.loadFail("加载正文出错\n${error.localizedMessage}")
                    }
                }
                AppLog.put("加载正文出错\n${error.localizedMessage}", error)
            }
        }
    }

    /**
     * 内容加载完成
     */
    suspend fun contentLoadFinish(
        chapter: BookChapter,
        content: String?,
        errorMsg: String = "加载内容失败",
        canceled: Boolean = false,
        generation: Long = contentGeneration.get(),
    ) {
        val owner = book?.takeIf { currentRequest(chapter.bookUrl, generation) }?.copy() ?: return
        if (canceled || chapter.index !in durChapterIndex - 1..durChapterIndex + 1) {
            if (currentRequest(chapter.bookUrl, generation)) removeLoading(chapter.index)
            return
        }
        // Parsing can suspend. Resolve the chapter slot only after it finishes: the reader
        // may have moved to an adjacent chapter while image dimensions were being read.
        val parsed = content?.takeIf { it.isNotEmpty() || chapter.isVolume }
            ?.let { decoder.decode(chapter, it, owner, hideTitle, chapterSize) }
        withContext(Main.immediate) {
            if (!currentRequest(owner.bookUrl, generation)) return@withContext
            removeLoading(chapter.index)
            val offset = chapter.index - durChapterIndex
            if (offset !in -1..1) return@withContext
            val failure = when {
                content == null -> errorMsg
                content.isEmpty() && !chapter.isVolume -> "正文内容为空"
                parsed?.imageCount == 0 && !chapter.isVolume -> "正文没有图片"
                else -> null
            }
            if (failure != null) {
                if (offset == 0) mCallback?.loadFail(failure)
                return@withContext
            }
            when (offset) {
                0 -> {
                    curMangaChapter = parsed
                    durChapterPos = durChapterPos.coerceIn(0, ((parsed?.imageCount ?: 0) - 1).coerceAtLeast(0))
                }
                -1 -> prevMangaChapter = parsed
                1 -> nextMangaChapter = parsed
            }
            mCallback?.upContent()
        }
    }

    private fun buildMangaContent(): MangaContent {
        val items = arrayListOf<BaseMangaPage>()
        var pos = 0
        var curFinish = false
        var nextFinish = false
        prevMangaChapter?.let {
            pos += it.pages.size
            items.addAll(it.pages)
        }
        curMangaChapter?.let {
            curFinish = true
            items.addAll(it.pages)
            val currentPosition = if (it.imageCount > 0) {
                durChapterPos.coerceIn(0, it.imageCount - 1)
            } else {
                0
            }
            pos += currentPosition
            if (!hideTitle && it.imageCount > 0) {
                pos++
            }
        }
        nextMangaChapter?.let {
            nextFinish = true
            items.addAll(it.pages)
        }
        return MangaContent(pos, items, curFinish, nextFinish)
    }

    /**
     * 加载下一章
     */
    fun moveToNextChapter(toFirst: Boolean = false): Boolean {
        if (durChapterIndex < simulatedChapterSize - 1) {
            if (toFirst) {
                mCallback?.showLoading()
                durChapterPos = 0
            }
            durChapterIndex++
            prevMangaChapter = curMangaChapter
            curMangaChapter = nextMangaChapter
            nextMangaChapter = null
            if (curMangaChapter == null) {
                mCallback?.startLoad()
                loadContent(durChapterIndex)
            } else {
                mCallback?.upContent()
            }
            loadContent(durChapterIndex + 1)
            saveRead()
            AppLog.putDebug("moveToNextChapter-curPageChanged()")
            curPageChanged()
            return true
        } else {
            AppLog.putDebug("跳转下一章失败,没有下一章")
            return false
        }
    }

    fun moveToPrevChapter(toFirst: Boolean = false): Boolean {
        if (durChapterIndex > 0) {
            if (toFirst) {
                mCallback?.showLoading()
                durChapterPos = 0
            }
            durChapterIndex--
            nextMangaChapter = curMangaChapter
            curMangaChapter = prevMangaChapter
            prevMangaChapter = null
            if (curMangaChapter == null) {
                loadContent(durChapterIndex)
            } else {
                mCallback?.upContent()
            }
            loadContent(durChapterIndex - 1)
            saveRead()
            return true
        }
        return false
    }

    fun curPageChanged() {
        upReadTime()
        preDownload()
    }

    fun saveRead() {
        val capturedOwner = progressOwner
        val currentBook = book ?: return
        val chapterIndex = durChapterIndex
        val chapterPosition = durChapterPos
        val readAt = maxOf(System.currentTimeMillis(), currentBook.durChapterTime)
        val snapshotBook = currentBook.copy(durChapterIndex = chapterIndex, durChapterPos = chapterPosition,
            durChapterTime = readAt, lastCheckCount = 0)
        book = snapshotBook
        executor.execute {
            kotlin.runCatching {
                dataRepository.saveProgress(snapshotBook, chapterIndex, chapterPosition, readAt, capturedOwner)
            }.onFailure {
                AppLog.put("保存漫画阅读进度信息出错\n$it", it)
            }
        }
    }

    private fun downloadNetworkContent(
        bookSource: BookSource,
        scope: CoroutineScope,
        chapter: BookChapter,
        book: Book,
        semaphore: Semaphore?,
        success: suspend (String) -> Unit = {},
        error: suspend () -> Unit = {},
        cancel: suspend () -> Unit = {},
    ) {
        dataRepository.downloadContent(
            bookSource,
            scope,
            chapter,
            book,
            semaphore,
            success,
            error,
            cancel,
        )
    }

    private fun preDownload() {
        val owner = book?.copy()?.takeUnless { it.isLocal } ?: return
        val generation = contentGeneration.get()
        executor.execute {
            if (!currentRequest(owner.bookUrl, generation)) return@execute
            if (dataRepository.preDownloadCount < 2) {
                upToc()
                return@execute
            }
            preDownloadTask?.cancel()
            preDownloadTask = launch(IO) {
                //预下载
                launch {
                    val maxChapterIndex =
                        min(durChapterIndex + dataRepository.preDownloadCount, chapterSize)
                    for (i in durChapterIndex.plus(2)..maxChapterIndex) {
                        if (downloadedChapters.contains(i)) continue
                        if ((downloadFailChapters[i] ?: 0) >= 3) continue
                        downloadIndex(i, owner.bookUrl, generation)
                    }
                }
                launch {
                    val minChapterIndex = durChapterIndex - min(5, dataRepository.preDownloadCount)
                    for (i in durChapterIndex.minus(2) downTo minChapterIndex) {
                        if (downloadedChapters.contains(i)) continue
                        if ((downloadFailChapters[i] ?: 0) >= 3) continue
                        downloadIndex(i, owner.bookUrl, generation)
                    }
                }
            }
        }
    }

    fun cancelPreDownloadTask() {
        if (curMangaChapter != null && nextMangaChapter != null) {
            preDownloadTask?.cancel()
            downloadScope.coroutineContext.cancelChildren()
        }
    }

    private suspend fun downloadIndex(index: Int, ownerUrl: String, generation: Long) {
        if (!currentRequest(ownerUrl, generation)) return
        if (index < 0) return
        if (index > chapterSize - 1) {
            upToc()
            return
        }
        val book = book ?: return
        val chapter = dataRepository.chapter(book.bookUrl, index) ?: return
        if (dataRepository.hasCachedContent(book, chapter)) {
            downloadedChapters.add(chapter.index)
        } else {
            delay(1000)
            if (currentRequest(ownerUrl, generation) && addLoading(index)) {
                download(downloadScope, chapter, preDownloadSemaphore, generation)
            }
        }
    }

    /**
     * 获取正文
     */
    private suspend fun download(
        scope: CoroutineScope,
        chapter: BookChapter,
        semaphore: Semaphore? = null,
        generation: Long = contentGeneration.get(),
    ) {
        val book = book?.copy() ?: return
        if (chapter.bookUrl != book.bookUrl) return
        val bookSource = bookSource
        if (bookSource != null) {
            downloadNetworkContent(bookSource, scope, chapter, book, semaphore, success = success@{
                if (!currentRequest(book.bookUrl, generation)) return@success
                downloadedChapters.add(chapter.index)
                downloadFailChapters.remove(chapter.index)
                contentLoadFinish(chapter, it, generation = generation)
            }, error = error@{
                if (!currentRequest(book.bookUrl, generation)) return@error
                downloadFailChapters[chapter.index] =
                    (downloadFailChapters[chapter.index] ?: 0) + 1
                contentLoadFinish(chapter, null, generation = generation)
            }, cancel = {
                contentLoadFinish(chapter, null, canceled = true, generation = generation)
            })
        } else {
            contentLoadFinish(chapter, null, "加载内容失败 没有书源", generation = generation)
        }
    }

    @Synchronized
    fun upToc() {
        val bookSource = bookSource ?: return
        val book = book ?: return
        if (!book.canUpdate) return
        if (chapterSize - durChapterIndex - 1 >= 3) return
        if (System.currentTimeMillis() - book.lastCheckTime < 600000) return
        book.lastCheckTime = System.currentTimeMillis()
        val oldBook = book.copy()
        val generation = contentGeneration.get()
        val refreshedBook = oldBook.copy()
        launch(IO) {
            dataRepository.loadChapterList(bookSource, refreshedBook).onSuccess { cList ->
                ensureActive()
                if (currentRequest(oldBook.bookUrl, generation) && cList.size > chapterSize) {
                    dataRepository.replaceChapterList(oldBook, refreshedBook, cList)
                    withContext(Main.immediate) {
                        if (!currentRequest(oldBook.bookUrl, generation)) return@withContext
                        onChapterListUpdated(refreshedBook, false)
                        nextMangaChapter ?: loadContent(durChapterIndex + 1)
                    }
                }
            }
        }
    }

    fun uploadProgress(successAction: (() -> Unit)? = null) {
        val current = book?.copy() ?: return
        val captured = current.copy(durChapterIndex = durChapterIndex, durChapterPos = durChapterPos)
        launch(IO) {
            dataRepository.uploadProgress(captured, successAction)
            ensureActive()
        }
    }

    /**
     * 同步阅读进度
     * 如果当前进度快于服务器进度或者没有进度进行上传，如果慢与服务器进度则执行传入动作
     */
    fun syncProgress(
        newProgressAction: ((progress: BookProgress) -> Unit)? = null,
        uploadSuccessAction: (() -> Unit)? = null,
        syncSuccessAction: (() -> Unit)? = null,
    ) {
        if (!dataRepository.progressSyncEnabled) return
        val book = book ?: return
        val generation = contentGeneration.get()
        launch(IO) {
            try {
                val progress = dataRepository.cloudProgress(book.copy())
                withContext(Main.immediate) {
                    if (!currentRequest(book.bookUrl, generation)) return@withContext
                    if (progress == null || progress.durChapterIndex < durChapterIndex ||
                        (progress.durChapterIndex == durChapterIndex && progress.durChapterPos < durChapterPos)) {
                        val captured = BookProgress(book.copy().apply {
                            durChapterIndex = this@MangaReaderSession.durChapterIndex
                            durChapterPos = this@MangaReaderSession.durChapterPos
                        })
                        launch(IO) { dataRepository.uploadProgress(captured, uploadSuccessAction) }
                    } else if (progress.durChapterIndex > durChapterIndex || progress.durChapterPos > durChapterPos) {
                        newProgressAction?.invoke(progress)
                    } else syncSuccessAction?.invoke()
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { AppLog.put("拉取阅读进度失败", error) }
        }
    }

    fun setProgress(progress: BookProgress) {
        if (progress.durChapterIndex < chapterSize &&
            (durChapterIndex != progress.durChapterIndex
                    || durChapterPos != progress.durChapterPos)
        ) {
            mCallback?.showLoading()
            if (progress.durChapterIndex == durChapterIndex) {
                durChapterPos = progress.durChapterPos
                mCallback?.upContent()
            } else {
                durChapterIndex = progress.durChapterIndex
                durChapterPos = progress.durChapterPos
                loadContent()
            }
            saveRead()
        }
    }

    fun showLoading() {
        mCallback?.showLoading()
    }

    fun loadFail(msg: String, retry: Boolean = true) {
        mCallback?.loadFail(msg, retry)
    }

    fun onChapterListUpdated(newBook: Book, loadContent: Boolean = true) {
        if (newBook.isSameNameAuthor(book)) {
            book = newBook
            chapterSize = newBook.totalChapterNum
            simulatedChapterSize = newBook.simulatedTotalChapterNum()
            if (simulatedChapterSize > 0 && durChapterIndex > simulatedChapterSize - 1) {
                durChapterIndex = simulatedChapterSize - 1
            }
            if (mCallback == null) {
                clearMangaChapter()
            } else if (loadContent) {
                loadContent()
            }
        }
    }

    /**
     * 注册回调
     */
    fun register(cb: Callback) {
        mCallback = cb
    }

    /**
     * 取消注册回调
     */
    fun unregister(cb: Callback) {
        pauseReading()
        if (mCallback === cb) {
            mCallback = null
        }
        preDownloadTask?.cancel()
        preDownloadTask = null
        contentGeneration.incrementAndGet()
        ownerJob?.cancel()
    }

    interface Callback {
        fun upContent()
        fun loadFail(msg: String, retry: Boolean = true)
        fun sureNewProgress(progress: BookProgress)
        fun showLoading()
        fun startLoad()
    }
}
