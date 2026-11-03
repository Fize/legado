package io.legado.app.data.repository

import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class BookInfoRequestOwner(val id: String, val token: String, val revision: Long, val bookUrl: String)
fun BookInfoRequest.navigationOwner() = BookInfoRequestOwner(id, ownerToken, revision, bookUrl)
data class BookInfoConsumedResult(val request: BookInfoRequest, val result: BookInfoChildRequest)
data class BookInfoEntrySnapshot(val request: BookInfoRequest?, val child: BookInfoChildRequest?)
private data class BookInfoClaimResult(val request: BookInfoRequest?)

/** Durable navigation state. Only the entry owns when to present a platform UI. */
@Singleton
class BookInfoNavigationRepository @Inject constructor(private val database: AppDatabase,
    private val books: dagger.Lazy<BookInfoRepository>) {
    constructor(database: AppDatabase) : this(database, dagger.Lazy { BookInfoRepository(database) })
    private val dao get() = database.bookInfoRequestDao

    suspend fun claim(id: String, initialBookUrl: String, currentBookUrl: String, token: String = UUID.randomUUID().toString()): BookInfoRequest =
        withContext(Dispatchers.IO) { dao.claim(BookInfoRequest(id, initialBookUrl, currentBookUrl, token,
            updatedAt = System.currentTimeMillis()), token) }

    suspend fun claimPreview(id: String, initialBookUrl: String, lease: BookInfoPreviewLease,
        previews: BookInfoPreviewStore, token: String, returnToReader: Boolean = false): BookInfoRequest? = withContext(Dispatchers.IO) {
        previews.withCurrentLease(lease) {
            database.runInTransaction<BookInfoClaimResult> {
                if (returnToReader && (lease.key != id || database.readerDetailDao.claim(id, initialBookUrl, token,
                        System.currentTimeMillis()) == null)) return@runInTransaction BookInfoClaimResult(null)
                require(returnToReader || database.readerDetailDao.get(id) == null) { "Request belongs to a reader" }
                BookInfoClaimResult(dao.claim(BookInfoRequest(id, initialBookUrl, initialBookUrl,
                    token, updatedAt = System.currentTimeMillis(), previewKey = lease.key, returnToReader = returnToReader), token))
            }
        }?.request
    }

    fun dispose(owner: BookInfoRequestOwner, lease: BookInfoPreviewLease, previews: BookInfoPreviewStore) {
        io.legado.app.help.globalExecutor.execute {
            runCatching {
                previews.withCurrentLease(lease) {
                    val closed = database.runInTransaction<Boolean> {
                        database.readerDetailDao.finish(owner.id, owner.token, now = System.currentTimeMillis())
                        dao.close(owner.id, owner.token) == 1 || dao.get(owner.id) == null
                    }
                    if (closed) previews.discard(lease)
                }
            }.onFailure { io.legado.app.constant.AppLog.put("清理详情请求失败", it) }
        }
    }

    suspend fun get(id: String): BookInfoRequest? = withContext(Dispatchers.IO) { dao.get(id) }
    fun observe(id: String) = dao.observe(id)
    fun observeChild(id: String) = dao.observeCurrentChild(id)
    fun observeActiveChild(id: String) = dao.observeActiveChild(id)
    fun observeEntry(id: String) = combine(dao.observe(id), dao.observeActiveChild(id), database.readerDetailDao.observe(id)) { _, _, _ -> Unit }
        .map { entry(id) }.distinctUntilChanged()
    suspend fun entry(id: String): BookInfoEntrySnapshot = withContext(Dispatchers.IO) {
        database.runInTransaction<BookInfoEntrySnapshot> {
            val parent = dao.get(id)
            val reader = database.readerDetailDao.get(id)
            val valid = parent?.takeUnless { it.returnToReader && (reader == null || reader.producerToken != it.ownerToken ||
                reader.phase in setOf(ReaderDetailPhase.Cancelled, ReaderDetailPhase.Consumed)) }
            BookInfoEntrySnapshot(valid, if (valid == null) null else dao.activeChild(id))
        }
    }
    suspend fun bookExists(bookUrl: String): Boolean = withContext(Dispatchers.IO) { database.bookDao.has(bookUrl) }

    suspend fun prepareAndBegin(owner: BookInfoRequestOwner, kind: BookInfoChildKind,
        book: Book, chapters: List<BookChapter>, childId: String = UUID.randomUUID().toString()): BookInfoChildRequest? = withContext(Dispatchers.IO) {
        require(book.bookUrl == owner.bookUrl)
        val snapshot = book.copy(variable = io.legado.app.utils.GSON.toJson(book.variableMap))
        database.runInTransaction<BookInfoChildRequest?> {
            check(dao.activeChild(owner.id) == null) { "Detail already has a pending child request" }
            check(kind != BookInfoChildKind.Reader || dao.get(owner.id)?.returnToReader != true) {
                "This detail entry must return to its existing reader"
            }
            if (snapshot.type and io.legado.app.constant.BookType.notShelf == 0) {
                check(books.get().findBook(owner.bookUrl)?.type?.and(io.legado.app.constant.BookType.notShelf) == 0) {
                    "The library book was removed before opening the child page"
                }
            }
            books.get().saveSnapshotInTransaction(snapshot, true, chapters.takeIf { it.isNotEmpty() }, true, owner)
            checkNotNull(dao.begin(owner.id, owner.token, owner.revision, owner.bookUrl, childId,
                kind, null, System.currentTimeMillis())) { "Detail request no longer owns this book" }
        }
    }

    suspend fun replacePreview(owner: BookInfoRequestOwner, lease: BookInfoPreviewLease,
        previews: BookInfoPreviewStore, previewRevision: Long, preview: BookInfoPreview,
        previous: Book, expectedCommitRevision: Long? = lease.commitRevision): BookInfoRequest = withContext(Dispatchers.IO) {
        checkNotNull(previews.withCurrentLease(lease) {
            val updated = database.runInTransaction<BookInfoRequest> {
                books.get().requireEntryOwner(owner)
                check(previews.stage(lease, previewRevision, owner.id, owner.revision + 1, preview, expectedCommitRevision))
                dao.replacePreview(owner.id, owner.token, owner.revision, owner.bookUrl,
                    preview.book.bookUrl, System.currentTimeMillis()) {
                    if (preview.inBookshelf) books.get().replaceEntryBook(previous, preview.book, preview.chapters)
                }.also { database.readerDetailDao.bindBook(owner.id, owner.token, it.bookUrl) }
            }
            runCatching { previews.resolve(lease, updated.id, updated.revision, updated.bookUrl, updated.previewCommitRevision) }
                .onFailure { io.legado.app.constant.AppLog.put("完成详情预览提交失败", it) }
            updated
        }) { "Detail preview ownership expired" }
    }

    /** Journal, current library metadata, chapters and canonical key share one ownership boundary. */
    suspend fun refreshPreview(owner: BookInfoRequestOwner, lease: BookInfoPreviewLease,
        previews: BookInfoPreviewStore, previewRevision: Long, preview: BookInfoPreview,
        previous: Book, replaceChapterList: Boolean = true,
        expectedCommitRevision: Long? = lease.commitRevision): BookInfoPreview = withContext(Dispatchers.IO) {
        require(previous.bookUrl == owner.bookUrl)
        val saved = checkNotNull(previews.withCurrentLease(lease) {
            val committed = database.runInTransaction<BookInfoPreview> {
                val prepared = books.get().persistEntryRefresh(owner, preview, replaceChapterList)
                check(previews.stage(lease, previewRevision, owner.id, owner.revision + 1, prepared, expectedCommitRevision))
                dao.replacePreview(owner.id, owner.token, owner.revision, owner.bookUrl,
                    prepared.book.bookUrl, System.currentTimeMillis()) { }
                database.readerDetailDao.bindBook(owner.id, owner.token, prepared.book.bookUrl)
                prepared
            }
            runCatching { previews.resolve(lease, owner.id, owner.revision + 1, committed.book.bookUrl, owner.revision + 1) }
                .onFailure { io.legado.app.constant.AppLog.put("完成详情刷新预览失败", it) }
            committed
        }) { "Detail preview ownership expired" }
        if (saved.inBookshelf) {
            runCatching { io.legado.app.help.book.BookHelp.updateCacheFolder(previous, saved.book) }
                .onFailure { io.legado.app.constant.AppLog.put("迁移详情刷新缓存失败", it) }
        }
        saved
    }

    suspend fun begin(owner: BookInfoRequestOwner, kind: BookInfoChildKind,
        position: BookInfoNavigationPosition? = null, childId: String = UUID.randomUUID().toString()): BookInfoChildRequest? =
        withContext(Dispatchers.IO) { dao.begin(owner.id, owner.token, owner.revision, owner.bookUrl,
            childId, kind, position, System.currentTimeMillis()) }

    suspend fun claimChild(id: String, bookUrl: String, kind: BookInfoChildKind,
        token: String = UUID.randomUUID().toString()): BookInfoChildRequest? = withContext(Dispatchers.IO) {
        dao.claimChild(id, bookUrl, kind, token, System.currentTimeMillis())
    }

    suspend fun deliverCatalog(child: BookInfoChildRequest, position: BookInfoNavigationPosition): Boolean =
        withContext(Dispatchers.IO) { dao.deliverCatalog(child.id, child.producerToken, child.bookUrl, position, System.currentTimeMillis()) }

    suspend fun deliverEdit(child: BookInfoChildRequest): Boolean = withContext(Dispatchers.IO) {
        dao.deliverEdit(child.id, child.producerToken, child.bookUrl, System.currentTimeMillis())
    }
    suspend fun editWasReturned(child: BookInfoChildRequest): Boolean = withContext(Dispatchers.IO) {
        dao.child(child.id)?.let { it.kind == BookInfoChildKind.Edit && it.producerToken == child.producerToken &&
            it.phase in setOf(BookInfoChildPhase.Returned, BookInfoChildPhase.Consumed) } == true
    }

    suspend fun deliverReader(child: BookInfoChildRequest, canonicalBookUrl: String): Boolean = withContext(Dispatchers.IO) {
        dao.deliverReader(child.id, child.producerToken, child.bookUrl, canonicalBookUrl, System.currentTimeMillis())
    }

    suspend fun cancel(child: BookInfoChildRequest): Boolean = withContext(Dispatchers.IO) {
        dao.cancelChild(child.id, child.producerToken, System.currentTimeMillis())
    }

    suspend fun cancelRequested(owner: BookInfoRequestOwner, childId: String): Boolean = withContext(Dispatchers.IO) {
        dao.cancelRequested(owner.id, owner.token, owner.revision, owner.bookUrl, childId, System.currentTimeMillis())
    }
    suspend fun abandonDisplayed(owner: BookInfoRequestOwner, childId: String): Boolean = kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
        io.legado.app.help.globalExecutor.execute {
            val result = runCatching {
                database.runInTransaction<Boolean> {
                    books.get().requireEntryOwner(owner)
                    val child = dao.activeChild(owner.id)?.takeIf { it.id == childId && it.phase == BookInfoChildPhase.Displayed }
                        ?: return@runInTransaction false
                    val reader = database.readerSessionDao.get(childId)?.takeIf {
                        child.kind == BookInfoChildKind.Reader && it.ownerToken == child.producerToken && it.initialBookUrl == child.bookUrl
                    }
                    if (reader != null) {
                        check(dao.deliverReader(child.id, child.producerToken, child.bookUrl, reader.bookUrl, System.currentTimeMillis()))
                        database.readerSessionDao.close(reader.id, reader.ownerToken)
                        true
                    } else dao.abandonDisplayed(owner.id, owner.token, owner.revision, owner.bookUrl, childId, System.currentTimeMillis())
                }
            }
            if (continuation.isActive) continuation.resumeWith(result)
        }
    }

    suspend fun consume(owner: BookInfoRequestOwner, childId: String): BookInfoConsumedResult? = withContext(Dispatchers.IO) {
        database.runInTransaction<BookInfoConsumedResult?> {
            val result = dao.consume(owner.id, owner.token, owner.revision, owner.bookUrl, childId, System.currentTimeMillis())
                ?: return@runInTransaction null
            val current = checkNotNull(dao.get(owner.id))
            database.readerDetailDao.bindBook(owner.id, owner.token, current.bookUrl)
            BookInfoConsumedResult(current, result)
        }
    }

    suspend fun continueCatalog(owner: BookInfoRequestOwner, childId: String,
        readerId: String = UUID.randomUUID().toString()): BookInfoChildRequest? = withContext(Dispatchers.IO) {
        dao.continueCatalog(owner.id, owner.token, owner.revision, owner.bookUrl, childId, readerId, System.currentTimeMillis())
    }

    suspend fun replaceBook(owner: BookInfoRequestOwner, newBookUrl: String, replace: () -> Unit): BookInfoRequest =
        withContext(Dispatchers.IO) {
            database.runInTransaction<BookInfoRequest> {
                books.get().requireEntryOwner(owner)
                dao.replaceBook(owner.id, owner.token, owner.revision, owner.bookUrl,
                    newBookUrl, System.currentTimeMillis(), replace).also {
                    database.readerDetailDao.bindBook(owner.id, owner.token, it.bookUrl)
                }
            }
        }

    suspend fun close(owner: BookInfoRequestOwner): Boolean = withContext(Dispatchers.IO) {
        database.runInTransaction<Boolean> {
            val selection = dao.activeChild(owner.id)?.takeIf {
                it.kind == BookInfoChildKind.Catalog && it.phase == BookInfoChildPhase.Returned
            }?.position
            database.readerDetailDao.finish(owner.id, owner.token, selection, System.currentTimeMillis())
            dao.close(owner.id, owner.token) == 1
        }
    }

    suspend fun cancelUnopenedReaderDetail(id: String, bookUrl: String) = withContext(Dispatchers.IO) {
        database.runInTransaction {
            val request = database.readerDetailDao.get(id)?.takeIf { it.bookUrl == bookUrl } ?: return@runInTransaction
            val reader = database.readerSessionDao.get(request.sessionId) ?: return@runInTransaction
            if (request.phase == ReaderDetailPhase.Requested) database.readerDetailDao.cancelRequested(reader.id, reader.ownerToken, id, System.currentTimeMillis())
            else if (request.phase == ReaderDetailPhase.Displayed) database.readerDetailDao.abandon(reader.id, reader.ownerToken, id, System.currentTimeMillis())
        }
    }

    /** Flush an unshelved private preview before its existing reader receives the canonical key. */
    suspend fun prepareReaderReturn(owner: BookInfoRequestOwner, book: Book, chapters: List<BookChapter>) = withContext(Dispatchers.IO) {
        database.runInTransaction {
            val parent = checkNotNull(dao.get(owner.id)?.takeIf { it.ownerToken == owner.token &&
                it.revision == owner.revision && it.bookUrl == owner.bookUrl && it.returnToReader })
            if (parent.deleted || !database.readerDetailDao.producerOwns(parent)) return@runInTransaction
            val stored = database.bookDao.getBook(parent.bookUrl)
            if (stored?.type?.and(io.legado.app.constant.BookType.notShelf) != 0) {
                check(book.bookUrl == parent.bookUrl)
                val snapshot = book.copy(variable = io.legado.app.utils.GSON.toJson(book.variableMap)).apply {
                    type = type or io.legado.app.constant.BookType.notShelf
                }
                books.get().saveSnapshotInTransaction(snapshot, true, chapters.takeIf { it.isNotEmpty() || stored == null }, true, owner)
            }
            check(database.readerDetailDao.bindBook(owner.id, owner.token, parent.bookUrl))
        }
    }

    /** Bounded database cleanup survives disposal of an entry; the queued task captures only keys. */
    fun disposeChild(id: String, token: String) {
        io.legado.app.help.globalExecutor.execute {
            runCatching { dao.cancelChild(id, token, System.currentTimeMillis()) }
                .onFailure { io.legado.app.constant.AppLog.put("清理详情子页面请求失败", it) }
        }
    }
}
