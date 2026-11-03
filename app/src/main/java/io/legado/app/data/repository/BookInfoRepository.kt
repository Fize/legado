package io.legado.app.data.repository

import android.net.Uri
import io.legado.app.constant.AppPattern
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.AppWebDav
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.config.AppConfig
import io.legado.app.exception.NoBooksDirException
import io.legado.app.utils.isAbsUrl
import java.io.IOException
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.getRemoteUrl
import io.legado.app.model.BookCover
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.utils.UrlUtil
import io.legado.app.model.localBook.LocalBook
import io.legado.app.utils.ArchiveUtils
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.Book
import io.legado.app.constant.BookType
import io.legado.app.help.book.addType
import io.legado.app.help.book.updateTo
import io.legado.app.help.book.removeType
import io.legado.app.help.book.isSameNameAuthor
import io.legado.app.model.AudioPlay
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.SearchBook
import io.legado.app.data.entities.saveReadRecordSnapshot
import io.legado.app.data.entities.replaceBookAfterSourceChange
import io.legado.app.help.book.getLocalUri
import io.legado.app.help.book.isLocal
import io.legado.app.model.webBook.WebBook
import io.legado.app.model.ReadBook
import io.legado.app.utils.ConvertUtils
import io.legado.app.utils.FileDoc
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

sealed interface BookInfoEdit {
    data class Cover(val url: String, val source: String? = null) : BookInfoEdit
    data class Variable(val value: String?) : BookInfoEdit
    data class CanUpdate(val enabled: Boolean) : BookInfoEdit
    data class Group(val id: Long) : BookInfoEdit

    fun applyTo(book: Book) {
        when (this) {
            is Cover -> { book.customCoverUrl = url; book.persistedCoverUrl = null; book.customCoverSource = source?.let { io.legado.app.data.entities.CoverSourceRef(url, it) } }
            is Variable -> book.putCustomVariable(value)
            is CanUpdate -> {
                book.canUpdate = enabled
                if (!enabled) book.removeType(BookType.updateError)
            }
            is Group -> book.group = id
        }
    }
}

data class BookInfoFileCandidate(val url: String, val fileName: String?, val suffix: String?)
data class BookGroupAssignment(val mask: Long, val inBookshelf: Boolean)

/** Persistence boundary for the book information screen. */
@Singleton
class BookInfoRepository @Inject constructor(
    private val database: AppDatabase,
    val sourceVariables: SourceVariableRepository = SourceVariableRepository(),
) {
    fun findBook(name: String, author: String): Book? = database.bookDao.getBook(name, author)

    fun findBook(bookUrl: String): Book? = database.bookDao.getBook(bookUrl)

    fun findSearchBook(bookUrl: String): SearchBook? = database.searchBookDao.getSearchBook(bookUrl)

    fun findFirstSearchBook(name: String, author: String): SearchBook? =
        database.searchBookDao.getFirstByNameAuthor(name, author)

    fun findBookSource(sourceUrl: String): BookSource? =
        database.bookSourceDao.getBookSource(sourceUrl)

    fun hasBookSource(sourceUrl: String): Boolean =
        database.bookSourceDao.has(sourceUrl)

    fun chapters(bookUrl: String): List<BookChapter> =
        database.bookChapterDao.getChapterList(bookUrl)

    fun groupNames(groupId: Long): List<String> =
        database.bookGroupDao.getGroupNames(groupId)

    suspend fun kinds(book: Book): List<String> = withContext(IO) {
        val kinds = book.getKindList().toMutableList()
        if (book.isLocal) {
            val size = try {
                FileDoc.fromUri(book.getLocalUri(), false).size
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                0L
            }
            if (size > 0) kinds.add(ConvertUtils.formatFileSize(size))
        }
        kinds
    }

    suspend fun loadBookInfo(source: BookSource, book: Book, canRename: Boolean): Book = withContext(IO) {
        WebBook.getBookInfoAwait(source, book, canRename)
    }

    suspend fun loadChapterList(source: BookSource, book: Book, runPreUpdateJs: Boolean, fromBookInfo: Boolean): List<BookChapter> = withContext(IO) {
        WebBook.getChapterListAwait(source, book, runPreUpdateJs, fromBookInfo).getOrThrow()
    }

    suspend fun searchCover(book: Book): String? = withContext(IO) { BookCover.searchCover(book) }

    suspend fun downloadCandidates(book: Book, source: BookSource?): List<BookInfoFileCandidate> = withContext(IO) {
        book.downloadUrls.orEmpty().map { url ->
            val analyzed = AnalyzeUrl(url, source = source, coroutineContext = currentCoroutineContext())
            BookInfoFileCandidate(url, UrlUtil.getFileName(analyzed), analyzed.type)
        }
    }

    suspend fun refreshLocalFile(book: Book) = withContext(IO) {
        book.tocUrl = ""
        book.getRemoteUrl()?.let { url ->
            val webDav = AppWebDav.defaultBookWebDav ?: throw NoStackTraceException("webDav没有配置")
            val remote = webDav.getRemoteBook(url)
            if (remote == null) book.origin = BookType.localTag
            else if (remote.lastModify > book.lastCheckTime && LocalBook.downloadRemoteBook(book)) {
                book.lastCheckTime = remote.lastModify
            }
        }
    }

    suspend fun loadLocalBookInfo(book: Book) = withContext(IO) { LocalBook.upBookInfo(book) }

    suspend fun loadLocalChapters(book: Book): List<BookChapter> = withContext(IO) { LocalBook.getChapterList(book) }

    suspend fun findLibraryMatch(book: Book): Book? = withContext(IO) {
        findBook(book.name, book.author)?.takeIf { it.type and BookType.notShelf == 0 && it.origin == book.origin }
    }

    suspend fun commitLoadedChapters(oldBook: Book, book: Book, chapters: List<BookChapter>) = withContext(IO) {
        currentCoroutineContext().ensureActive()
        database.runInTransaction {
            database.bookDao.getBook(oldBook.bookUrl)?.let { stored ->
                book.durChapterIndex = stored.durChapterIndex
                book.durChapterPos = stored.durChapterPos
                book.durChapterTitle = stored.durChapterTitle
                book.durVolumeIndex = stored.durVolumeIndex
                book.chapterInVolumeIndex = stored.chapterInVolumeIndex
                book.durChapterTime = stored.durChapterTime
            }
            database.bookDao.replace(oldBook, book)
            replaceChapters(book.bookUrl, chapters)
            book.customCoverUrl = database.bookDao.getCustomCoverUrl(book.bookUrl)
            book.customCoverSource = database.bookDao.getCustomCoverSource(book.bookUrl)
            book.persistedCoverUrl = database.bookDao.getPersistedCoverUrl(book.bookUrl)
        }
        if (oldBook.bookUrl != book.bookUrl) BookHelp.updateCacheFolder(oldBook, book)
        ReadBook.onChapterListUpdated(book)
    }

    suspend fun changeSource(oldBook: Book?, book: Book, chapters: List<BookChapter>) = withContext(IO) {
        currentCoroutineContext().ensureActive()
        replaceBookAfterSourceChange(oldBook, book, chapters)
    }

    internal fun removeCompletedSource(source: SearchBook) {
        io.legado.app.help.source.SourceHelp.deleteBookSource(source.origin)
        database.searchBookDao.delete(source)
    }

    suspend fun saveSnapshot(
        book: Book, preserveCustomCoverUrl: Boolean,
        chapters: List<BookChapter>? = null, preserveReadingProgress: Boolean = true, owner: BookInfoRequestOwner? = null,
    ): Book = withContext(IO) {
        database.runInTransaction {
            if (owner != null) requireBookWriteOwner(owner, book.bookUrl)
            saveSnapshotInTransaction(book, preserveCustomCoverUrl, chapters, preserveReadingProgress, owner)
        }
        if (owner == null && ReadBook.book?.isSameNameAuthor(book) == true) ReadBook.book = book
        if (owner == null && AudioPlay.book?.isSameNameAuthor(book) == true) AudioPlay.book = book
        book
    }


    internal fun saveSnapshotInTransaction(book: Book, preserveCustomCoverUrl: Boolean,
        chapters: List<BookChapter>?, preserveReadingProgress: Boolean, owner: BookInfoRequestOwner? = null) {
        database.runInTransaction {
            if (owner != null) requireEntryOwner(owner)
            if (owner != null) {
                val collision = findBook(book.name, book.author)
                if (collision != null && collision.bookUrl != book.bookUrl) {
                    check(canRetirePreview(owner, collision)) { "Another book already owns this library identity" }
                    collision.saveReadRecordSnapshot(database)
                    database.bookDao.replace(collision, book)
                    database.bookDao.update(book)
                }
                findBook(book.bookUrl)?.let { stored ->
                    book.type = (book.type and BookType.notShelf.inv()) or (stored.type and BookType.notShelf)
                    // Ordinary snapshots cannot roll back a background upload's committed location.
                    if (book.isLocal && stored.isLocal && book.originName == stored.originName) {
                        book.origin = stored.origin
                        book.lastCheckTime = maxOf(book.lastCheckTime, stored.lastCheckTime)
                    }
                }
            }
            if (book.order == 0) book.order = minBookOrder() - 1
            (findBook(book.bookUrl) ?: findBook(book.name, book.author))?.takeIf { preserveReadingProgress }?.let { previous ->
                book.durChapterIndex = previous.durChapterIndex
                book.durChapterPos = previous.durChapterPos
                book.durChapterTitle = previous.durChapterTitle
                book.durVolumeIndex = previous.durVolumeIndex
                book.chapterInVolumeIndex = previous.chapterInVolumeIndex
                book.durChapterTime = previous.durChapterTime
            }
            saveBook(book, preserveCustomCoverUrl)
            if (chapters != null) replaceChapters(book.bookUrl, chapters)
            if (preserveCustomCoverUrl) {
                book.customCoverUrl = database.bookDao.getCustomCoverUrl(book.bookUrl)
                book.customCoverSource = database.bookDao.getCustomCoverSource(book.bookUrl)
                book.persistedCoverUrl = database.bookDao.getPersistedCoverUrl(book.bookUrl)
            }
            if (owner != null) database.readerDetailDao.bindBook(owner.id, owner.token, book.bookUrl)
        }
    }

    internal fun requireEntryOwner(owner: BookInfoRequestOwner) {
        check(!database.bookInfoDeleteDao.blocksBook(owner.bookUrl)) { "Book deletion is pending" }
        val current = database.bookInfoRequestDao.get(owner.id)
        check(current != null && current.ownerToken == owner.token && current.revision == owner.revision &&
            current.bookUrl == owner.bookUrl && !current.deleted) { "Detail request no longer owns this book" }
        check(database.readerDetailDao.producerOwns(current)) { "Detail no longer belongs to its reader" }
    }

    private fun requireBookWriteOwner(owner: BookInfoRequestOwner, bookUrl: String) {
        requireEntryOwner(owner)
        check(owner.bookUrl == bookUrl && database.bookInfoRequestDao.activeChild(owner.id) == null) {
            "Detail request is no longer ready to modify this book"
        }
    }

    private fun canRetirePreview(owner: BookInfoRequestOwner, book: Book): Boolean =
        book.type and BookType.notShelf != 0 && database.bookInfoRequestDao.canRetirePreview(owner.id, book.bookUrl) &&
            ReadBook.book?.bookUrl != book.bookUrl && AudioPlay.book?.bookUrl != book.bookUrl &&
            io.legado.app.model.VideoPlay.book?.bookUrl != book.bookUrl

    /** Called inside the navigation transaction; a preview never adopts an unrelated name match. */
    internal fun persistEntryRefresh(owner: BookInfoRequestOwner, preview: BookInfoPreview,
        replaceChapterList: Boolean): BookInfoPreview {
        requireBookWriteOwner(owner, owner.bookUrl)
        val current = findBook(owner.bookUrl)?.takeIf { it.type and BookType.notShelf == 0 }
        check(!preview.inBookshelf || current != null) {
            "The library book was removed while refreshing"
        }
        val refreshed = preview.book.copy(variable = io.legado.app.utils.GSON.toJson(preview.book.variableMap)).apply {
            removeType(BookType.updateError)
            infoHtml = preview.infoHtml
            tocHtml = preview.tocHtml
            downloadUrls = preview.downloadUrls?.toList()
        }
        val collision = findBook(refreshed.name, refreshed.author)
        check(current == null || collision == null || collision.bookUrl == owner.bookUrl) { "Another book already owns this library identity" }
        check(refreshed.bookUrl == owner.bookUrl || !database.bookDao.has(refreshed.bookUrl)) {
            "Another book already owns this canonical URL"
        }
        val chapters = if (replaceChapterList || current == null) preview.chapters else
            database.bookChapterDao.getChapterList(current.bookUrl).map { it.copy(bookUrl = refreshed.bookUrl) }
        current?.let {
            if (refreshed.bookUrl == it.bookUrl && refreshed.isLocal && it.isLocal && refreshed.originName == it.originName) {
                refreshed.origin = it.origin
                refreshed.lastCheckTime = maxOf(refreshed.lastCheckTime, it.lastCheckTime)
            }
            it.updateTo(refreshed)
            refreshed.removeType(BookType.updateError)
            if (it.bookUrl == refreshed.bookUrl) {
                database.bookDao.update(refreshed)
                if (replaceChapterList) replaceChapters(refreshed.bookUrl, chapters)
            } else {
                database.bookDao.replace(it, refreshed)
                replaceChapters(refreshed.bookUrl, chapters)
            }
        }
        return preview.copy(book = refreshed, chapters = chapters,
            inBookshelf = current != null && current.type and BookType.notShelf == 0)
    }

    internal fun replaceEntryBook(oldBook: Book, newBook: Book, chapters: List<BookChapter>) {
        val collision = findBook(newBook.name, newBook.author)
        check(collision == null || collision.bookUrl == oldBook.bookUrl || collision.bookUrl == newBook.bookUrl) {
            "Another book already owns this library identity"
        }
        oldBook.saveReadRecordSnapshot(database)
        database.bookDao.replace(oldBook, newBook)
        replaceChapters(newBook.bookUrl, chapters)
    }

    fun uploadImportedBookByDefault() = LocalConfig.uploadImportedBookToWebDav

    fun setUploadImportedBookByDefault(value: Boolean) { LocalConfig.uploadImportedBookToWebDav = value }

    suspend fun importOnlineFile(url: String, fileName: String, source: BookSource?): Book =
        withContext(IO) { previewImportedFile(downloadOnlineFile(url, fileName, source)) }

    suspend fun downloadFile(url: String, fileName: String, source: BookSource?): Uri =
        withContext(IO) { downloadOnlineFile(url, fileName, source) }

    private suspend fun downloadOnlineFile(url: String, fileName: String, source: BookSource?): Uri {
        AppConfig.defaultBookTreeUri ?: throw NoBooksDirException()
        if (!url.isAbsUrl()) return LocalBook.saveBookFile(url, fileName, source)
        return AnalyzeUrl(url, source = source, callTimeout = 0, coroutineContext = currentCoroutineContext())
            .getResponseAwait().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}: ${response.message}")
                LocalBook.saveBookFile(response.body.byteStream(), fileName)
            }
    }

    suspend fun archiveEntries(uri: Uri): List<String> = withContext(IO) {
        ArchiveUtils.getArchiveFilesName(uri) { AppPattern.bookFileRegex.matches(it) }
    }

    suspend fun importArchive(uri: Uri, fileName: String, entry: String): Book = withContext(IO) {
        val archive = FileDoc.fromUri(uri, false)
        val extracted = ArchiveUtils.deCompress(archive, filter = { it == entry }).single()
        val localUri = LocalBook.saveBookFile(extracted.inputStream(), fileName)
        previewImportedFile(localUri).apply {
            origin = "${BookType.localTag}::${archive.name}"
            addType(BookType.archive)
        }
    }

    suspend fun deleteLocalFile(book: Book, deleteOriginal: Boolean) = withContext(IO) {
        LocalBook.deleteBook(book, deleteOriginal)
    }

    suspend fun clearCache(book: Book) = withContext(IO) {
        BookHelp.clearCache(book)
        if (ReadBook.book?.bookUrl == book.bookUrl) ReadBook.clearTextChapter()
    }

    private fun previewImportedFile(uri: Uri): Book {
        val file = FileDoc.fromUri(uri, false)
        return LocalBook.withParserCacheInvalidated(uri, file.name) { LocalBook.previewImportFile(uri) }
    }

    suspend fun mergeLocalBook(localBook: Book, onlineBook: Book?, owner: BookInfoRequestOwner? = null): Book = withContext(IO) {
        var overwritten: Book? = null
        database.runInTransaction {
            if (owner != null) requireEntryOwner(owner)
            val existing = database.bookDao.getBook(localBook.bookUrl)
            overwritten = existing
            val fileTypes = BookType.allBookTypeLocal or BookType.archive
            val importedType = localBook.type and fileTypes
            (onlineBook ?: existing)?.updateTo(localBook)
            localBook.type = (localBook.type and fileTypes.inv()) or importedType
            onlineBook?.let {
                localBook.name = it.name.ifBlank { localBook.name }
                localBook.author = it.author.ifBlank { localBook.author }
                if (!it.coverUrl.isNullOrBlank()) localBook.coverUrl = it.coverUrl
                if (!it.intro.isNullOrBlank()) localBook.intro = it.intro
            }
            // Reimporting the same local file keeps its current reading position.
            existing?.let {
                localBook.order = it.order
                localBook.durChapterIndex = it.durChapterIndex
                localBook.durChapterPos = it.durChapterPos
                localBook.durChapterTitle = it.durChapterTitle
                localBook.durVolumeIndex = it.durVolumeIndex
                localBook.chapterInVolumeIndex = it.chapterInVolumeIndex
                localBook.durChapterTime = it.durChapterTime
            }
            if (localBook.order == 0) localBook.order = database.bookDao.minOrder - 1
            localBook.removeType(BookType.notShelf or BookType.updateError)
            val collision = database.bookDao.getBook(localBook.name, localBook.author)
            val retired = collision?.takeIf { owner != null && it.bookUrl != localBook.bookUrl && canRetirePreview(owner, it) }
            check(collision == null || collision.bookUrl == localBook.bookUrl || collision.bookUrl == onlineBook?.bookUrl || retired != null) {
                "A different book already uses this name and author."
            }
            val previous = onlineBook?.let { database.bookDao.getBook(it.bookUrl) } ?: retired
            if (previous != null && previous.bookUrl != localBook.bookUrl) {
                database.bookDao.replace(previous, localBook)
                // replace preserves stored fields; the current preview can have newer, unsaved edits.
                database.bookDao.update(localBook)
            } else database.bookDao.insert(localBook)
            database.bookChapterDao.delByBook(localBook.bookUrl)
            if (owner != null) database.bookInfoRequestDao.replaceBook(owner.id, owner.token,
                owner.revision, owner.bookUrl, localBook.bookUrl, System.currentTimeMillis()) { }
        }
        overwritten?.let { BookHelp.clearCache(it) }
        if (owner == null) syncActiveBook(localBook)
        localBook
    }

    fun minBookOrder(): Int = database.bookDao.minOrder

    suspend fun pin(bookUrl: String, owner: BookInfoRequestOwner? = null): Book = withContext(IO) {
        database.runInTransaction<Book> {
            if (owner != null) requireBookWriteOwner(owner, bookUrl)
            val book = checkNotNull(findBook(bookUrl)) { "书籍已移除，请重新打开详情" }
            book.order = minBookOrder() - 1
            book.durChapterTime = System.currentTimeMillis()
            database.bookDao.update(book)
            book
        }
    }

    /** Read and patch the current row so unrelated concurrent edits are retained. */
    suspend fun applyEdit(bookUrl: String, edit: BookInfoEdit, owner: BookInfoRequestOwner? = null): Book = withContext(IO) {
        val saved = database.runInTransaction<Book> {
            if (owner != null) requireBookWriteOwner(owner, bookUrl)
            val book = checkNotNull(database.bookDao.getBook(bookUrl)) { "书籍已移除，请重新打开详情" }
            edit.applyTo(book)
            database.bookDao.update(book)
            book
        }
        if (owner == null && ReadBook.book?.bookUrl == saved.bookUrl) ReadBook.book = saved
        if (owner == null && AudioPlay.book?.bookUrl == saved.bookUrl) AudioPlay.book = saved
        saved
    }

    fun saveBook(book: Book, preserveCustomCoverUrl: Boolean = true) {
        if (database.bookDao.has(book.bookUrl)) {
            if (preserveCustomCoverUrl) database.bookDao.updatePreservingCustomCoverUrl(book)
            else database.bookDao.update(book)
        } else {
            database.bookDao.insert(book)
        }
    }

    fun addToBookshelf(book: Book, chapters: List<BookChapter>, owner: BookInfoRequestOwner? = null) {
        database.runInTransaction { addShelfRows(book, chapters, owner) }
        if (owner == null) syncActiveBook(book)
    }

    private fun addShelfRows(book: Book, chapters: List<BookChapter>, owner: BookInfoRequestOwner? = null) {
        if (owner != null) {
            requireBookWriteOwner(owner, book.bookUrl)
            val collision = findBook(book.name, book.author)?.takeIf { it.bookUrl != book.bookUrl }
            if (collision != null) {
                check(canRetirePreview(owner, collision)) { "Another book already owns this library identity" }
                collision.saveReadRecordSnapshot(database)
                database.bookDao.replace(collision, book)
                database.bookDao.update(book)
            }
        }
        book.removeType(BookType.notShelf)
        if (book.order == 0) book.order = database.bookDao.minOrder - 1
        (if (owner == null) database.bookDao.getBook(book.name, book.author) else findBook(book.bookUrl))?.let { previous ->
            book.durChapterIndex = previous.durChapterIndex
            book.durChapterPos = previous.durChapterPos
            book.durChapterTitle = previous.durChapterTitle
            if (owner != null) {
                book.durVolumeIndex = previous.durVolumeIndex
                book.chapterInVolumeIndex = previous.chapterInVolumeIndex
                book.durChapterTime = previous.durChapterTime
            }
        }
        saveBook(book, preserveCustomCoverUrl = false)
        replaceChapters(book.bookUrl, chapters)
        if (owner != null) database.readerDetailDao.bindBook(owner.id, owner.token, book.bookUrl)
    }

    private fun syncActiveBook(book: Book, matchByName: Boolean = true) {
        fun matches(active: Book?) = if (matchByName) active?.isSameNameAuthor(book) == true else active?.bookUrl == book.bookUrl
        if (matches(ReadBook.book)) ReadBook.book = book
        if (matches(AudioPlay.book)) AudioPlay.book = book
    }

    /** Validate group membership in the same transaction as the book write. */
    suspend fun assignGroups(book: Book, chapters: List<BookChapter>, onShelf: Boolean, owners: Map<Long, String>? = null, entryOwner: BookInfoRequestOwner? = null): BookGroupAssignment = withContext(IO) {
        var activeBook: Book? = null
        var confirmedOnShelf = onShelf
        database.runInTransaction {
            if (entryOwner != null) {
                requireBookWriteOwner(entryOwner, book.bookUrl)
                if (findBook(book.bookUrl)?.type?.and(BookType.notShelf) == 0) confirmedOnShelf = true
            }
            val groups = database.bookGroupDao.all
            if (owners != null && owners.any { (id, generation) -> book.group and id != 0L && groups.none { it.groupId == id && it.generation == generation } }) {
                throw BookGroupException(BookGroupError.Changed)
            }
            val available = groups.filter { it.groupId > 0 || it.groupId == Long.MIN_VALUE }
                .fold(0L) { mask, group -> mask or group.groupId }
            val owned = owners?.keys?.fold(0L) { mask, id -> mask or id } ?: available
            if (owners != null && book.group and available and owned.inv() != 0L) throw BookGroupException(BookGroupError.Changed)
            book.group = book.group and available
            if (confirmedOnShelf) {
                val stored = checkNotNull(database.bookDao.getBook(book.bookUrl)) { "Book was removed." }
                stored.group = book.group
                database.bookDao.update(stored)
                activeBook = stored
            } else if (book.group != 0L) {
                addShelfRows(book, chapters, entryOwner)
                activeBook = book
            }
        }
        if (entryOwner == null) activeBook?.let { syncActiveBook(it, matchByName = !onShelf) }
        BookGroupAssignment(book.group, confirmedOnShelf || book.group != 0L)
    }

    fun deleteBook(book: Book) {
        if (ReadBook.book?.bookUrl == book.bookUrl) ReadBook.book = null
        book.saveReadRecordSnapshot()
        database.bookDao.delete(book)
    }

    private fun saveChapters(chapters: List<BookChapter>) {
        if (chapters.isNotEmpty()) database.bookChapterDao.insert(*chapters.toTypedArray())
    }

    private fun replaceChapters(bookUrl: String, chapters: List<BookChapter>) {
        database.bookChapterDao.delByBook(bookUrl)
        saveChapters(chapters)
    }
}
