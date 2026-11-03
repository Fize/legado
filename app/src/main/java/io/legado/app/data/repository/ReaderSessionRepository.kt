package io.legado.app.data.repository

import io.legado.app.data.appDb
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.BookInfoChildKind
import io.legado.app.data.entities.ReaderSessionState
import io.legado.app.data.entities.ReaderCatalogRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class ReaderOpenRequest(
    val id: String,
    val bookUrl: String,
    val inBookshelf: Boolean = true,
    val chapterChanged: Boolean = false,
    val chapterIndex: Int? = null,
    val pageIndex: Int = 0,
    val returnToDetails: Boolean = false,
)

data class ReaderProgressOwner(val sessionId: String, val token: String, val revision: Long)
class ReaderReturnRequestExpiredException : IllegalStateException("Detail reading request expired")

internal fun ReaderSessionState.progressOwner() = ReaderProgressOwner(id, ownerToken, revision)

@Singleton
class ReaderSessionRepository @Inject constructor(
    private val database: dagger.Lazy<AppDatabase>,
    private val navigation: dagger.Lazy<BookInfoNavigationRepository> = dagger.Lazy { BookInfoNavigationRepository(database.get()) },
    private val previews: dagger.Lazy<BookInfoPreviewStore> = dagger.Lazy { BookInfoPreviewStore(splitties.init.appCtx) },
) {
    constructor() : this(dagger.Lazy { appDb })
    private val db get() = database.get()
    private val previewStore get() = previews.get()
    suspend fun claim(request: ReaderOpenRequest, token: String): ReaderSessionState {
        require(request.id.isNotBlank() && request.id.length <= 128 && request.bookUrl.isNotBlank())
        require(request.chapterIndex == null || request.chapterIndex >= 0)
        require(request.pageIndex >= 0)
        // Ownership handoff follows all already-published positions on the same queue.
        // A new entry cannot invalidate an older writer before its last visible page has been saved.
        return kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            val future = io.legado.app.help.globalExecutor.submit {
                if (!continuation.isActive) return@submit
                continuation.resumeWith(runCatching {
                    db.runInTransaction<ReaderSessionState> {
                        val detail = if (request.returnToDetails) db.bookInfoRequestDao.claimChild(
                            request.id, request.bookUrl, BookInfoChildKind.Reader, token, System.currentTimeMillis())
                            ?: throw ReaderReturnRequestExpiredException() else null
                        val position = detail?.position
                        val chapter = if (request.returnToDetails) position?.chapterIndex else request.chapterIndex
                        val page = if (request.returnToDetails) position?.let {
                            it.pdfPageIndex?.rem(io.legado.app.model.localBook.PdfFile.PAGE_SIZE) ?: it.chapterPosition
                        } ?: 0 else request.pageIndex
                        val changed = if (request.returnToDetails) position?.chapterChanged == true
                            else request.chapterChanged || chapter != null
                        val saved = db.readerSessionDao.claim(ReaderSessionState(request.id, request.bookUrl, request.bookUrl,
                            request.inBookshelf, changed, chapter ?: -1, page,
                            updatedAt = System.currentTimeMillis()), token)
                        val current = db.readerDetailDao.apply(saved.id, token, System.currentTimeMillis())?.session ?: saved
                        if (db.readerDetailDao.active(saved.id) == null) db.readerSessionDao.activate(saved.id, token)
                        current
                    }
                })
            }
            continuation.invokeOnCancellation {
                future.cancel(false)
                close(ReaderProgressOwner(request.id, token, 0))
            }
        }
    }

    /** The shared persistence queue orders disposal after the reader's final progress snapshot. */
    fun close(owner: ReaderProgressOwner) {
        io.legado.app.help.globalExecutor.execute {
            runCatching {
                closeOwned(owner)
            }
                .onFailure { io.legado.app.constant.AppLog.put("清理阅读请求失败\n$it", it) }
        }
    }


    private fun closeOwned(owner: ReaderProgressOwner) {
        db.runInTransaction {
            val current = db.readerSessionDao.get(owner.sessionId)?.takeIf { it.ownerToken == owner.token }
                ?: return@runInTransaction
            // Publish before deleting the row that proves the reader's identity.
            db.bookInfoRequestDao.deliverReader(current.id, current.ownerToken, current.initialBookUrl,
                current.bookUrl, System.currentTimeMillis())
            db.readerSessionDao.close(owner.sessionId, owner.token)
        }
    }

    suspend fun closeAndAwait(owner: ReaderProgressOwner): Unit = kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
        io.legado.app.help.globalExecutor.execute {
            val result = runCatching { closeOwned(owner) }
            if (continuation.isActive) continuation.resumeWith(result)
        }
    }

    suspend fun activate(owner: ReaderProgressOwner): Boolean = kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
        val future = io.legado.app.help.globalExecutor.submit {
            if (!continuation.isActive) return@submit
            continuation.resumeWith(runCatching { db.readerSessionDao.activate(owner.sessionId, owner.token) })
        }
        continuation.invokeOnCancellation { future.cancel(false) }
    }

    fun observeCatalogRequest(id: String) = db.readerSessionDao.observeCatalogRequest(id)

    fun observeCatalog(sessionId: String) = db.readerSessionDao.observeCatalog(sessionId)

    fun observeDetail(sessionId: String) = db.readerDetailDao.observeActive(sessionId)
    suspend fun detail(sessionId: String) = withContext(Dispatchers.IO) { db.readerDetailDao.active(sessionId) }
    suspend fun current(owner: ReaderProgressOwner) = withContext(Dispatchers.IO) {
        db.readerSessionDao.get(owner.sessionId)?.takeIf { it.ownerToken == owner.token }
    }

    /** Run after the last visible progress write and revoke its old generation before navigation. */
    suspend fun beginDetail(owner: ReaderProgressOwner, chapter: Int, page: Int,
        id: String = UUID.randomUUID().toString()): io.legado.app.data.entities.ReaderDetailRequest? =
        kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            io.legado.app.help.globalExecutor.execute {
                val result = runCatching { db.readerDetailDao.begin(owner.sessionId, owner.token, owner.revision,
                    id, chapter, page, System.currentTimeMillis()) }
                if (continuation.isActive) continuation.resumeWith(result)
            }
        }

    suspend fun applyDetail(owner: ReaderProgressOwner): io.legado.app.data.dao.ReaderDetailApplied? =
        kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            io.legado.app.help.globalExecutor.execute {
                val result = runCatching { db.readerDetailDao.apply(owner.sessionId, owner.token, System.currentTimeMillis()) }
                if (continuation.isActive) continuation.resumeWith(result)
            }
        }

    suspend fun cancelDetail(owner: ReaderProgressOwner, id: String): Boolean = withContext(Dispatchers.IO) {
        db.readerDetailDao.cancelRequested(owner.sessionId, owner.token, id, System.currentTimeMillis())
    }

    /** Recover a displayed detail that is absent from this reader's restored navigation stack. */
    suspend fun recoverDetail(owner: ReaderProgressOwner, id: String) = withContext(Dispatchers.IO) {
        val request = db.readerDetailDao.get(id)?.takeIf { it.sessionId == owner.sessionId && it.phase == io.legado.app.data.entities.ReaderDetailPhase.Displayed }
            ?: return@withContext
        val reader = db.readerSessionDao.get(owner.sessionId)
        check(reader?.ownerToken == owner.token && reader.revision == request.revision && reader.bookUrl == request.bookUrl)
        val parent = db.bookInfoRequestDao.get(id)
        if (parent == null || db.readerDetailDao.lease(id) == null) {
            db.readerDetailDao.abandon(owner.sessionId, owner.token, id, System.currentTimeMillis())
            return@withContext
        }
        val lease = previewStore.open(checkNotNull(parent.previewKey))
        val token = UUID.randomUUID().toString()
        val claimed = navigation.get().claimPreview(id, request.bookUrl, lease, previewStore, token, true)
        if (claimed == null) {
            previewStore.discard(lease)
            check(db.readerDetailDao.get(id)?.phase != io.legado.app.data.entities.ReaderDetailPhase.Displayed)
            return@withContext
        }
        val snapshot = previewStore.resolveSnapshot(lease, claimed.id, claimed.revision, claimed.bookUrl, claimed.previewCommitRevision)?.preview
        val stored = db.bookDao.getBook(claimed.bookUrl)
        val book = snapshot?.book?.takeIf { it.bookUrl == claimed.bookUrl } ?: checkNotNull(stored) { "Detail preview is unavailable" }
        navigation.get().prepareReaderReturn(claimed.navigationOwner(), book,
            snapshot?.chapters ?: db.bookChapterDao.getChapterList(book.bookUrl))
        check(navigation.get().close(claimed.navigationOwner()))
        previewStore.discard(lease)
    }

    suspend fun applyCatalog(owner: ReaderProgressOwner): ReaderSessionState? = withContext(Dispatchers.IO) {
        db.readerSessionDao.applyCatalog(owner.sessionId, owner.token, System.currentTimeMillis())
    }

    suspend fun beginCatalog(owner: ReaderProgressOwner, chapter: Int, page: Int): ReaderCatalogRequest? = withContext(Dispatchers.IO) {
        db.readerSessionDao.beginCatalog(owner.sessionId, owner.token, UUID.randomUUID().toString(),
            chapter, page, System.currentTimeMillis())
    }

    suspend fun navigate(owner: ReaderProgressOwner, bookUrl: String, chapter: Int, page: Int): ReaderSessionState? = withContext(Dispatchers.IO) {
        db.readerSessionDao.moveBook(owner.sessionId, owner.token, bookUrl, chapter, page, System.currentTimeMillis())
    }

    /** A prepared text destination may commit only against the exact source and target chapter identities. */
    suspend fun navigateChapter(owner: ReaderProgressOwner, bookUrl: String, origin: String,
        chapter: Int, chapterUrl: String, position: Int): ReaderSessionState? = withContext(Dispatchers.IO) {
        db.runInTransaction<ReaderSessionState?> {
            if (!db.readerSessionDao.ownsBook(owner.sessionId,owner.token,owner.revision,bookUrl) ||
                db.bookDao.getBook(bookUrl)?.origin != origin ||
                db.bookChapterDao.getChapter(bookUrl,chapter)?.url != chapterUrl ||
                db.readerDetailDao.active(owner.sessionId) != null) null
            else db.readerSessionDao.moveBook(owner.sessionId,owner.token,bookUrl,chapter,position,System.currentTimeMillis())
        }
    }

    suspend fun deliverCatalog(requestId: String, bookUrl: String, chapter: Int, page: Int): Boolean = withContext(Dispatchers.IO) {
        db.readerSessionDao.deliverCatalog(requestId, bookUrl, chapter, page)
    }
}
