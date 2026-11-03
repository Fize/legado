package io.legado.app.data.dao

import androidx.room.*
import io.legado.app.data.entities.*
import kotlinx.coroutines.flow.Flow

data class ReaderDetailApplied(val session: ReaderSessionState, val request: ReaderDetailRequest)

@Dao
abstract class ReaderDetailDao {
    @Query("SELECT * FROM reader_detail_requests WHERE id = :id")
    abstract fun get(id: String): ReaderDetailRequest?
    @Query("SELECT * FROM reader_detail_requests WHERE sessionId = :id AND phase IN ('Requested','Displayed','Returned') ORDER BY updatedAt DESC, id DESC LIMIT 1")
    abstract fun active(id: String): ReaderDetailRequest?
    @Query("SELECT * FROM reader_detail_requests WHERE sessionId = :id AND phase IN ('Requested','Displayed','Returned') ORDER BY updatedAt DESC, id DESC LIMIT 1")
    abstract fun observeActive(id: String): Flow<ReaderDetailRequest?>
    @Query("SELECT * FROM reader_detail_requests WHERE id = :id")
    abstract fun observe(id: String): Flow<ReaderDetailRequest?>
    @Query("SELECT * FROM reader_detail_book_leases WHERE requestId = :id")
    abstract fun lease(id: String): ReaderDetailBookLease?
    @Query("SELECT * FROM reader_sessions WHERE id = :id")
    protected abstract fun session(id: String): ReaderSessionState?
    @Query("SELECT * FROM book_info_requests WHERE id = :id")
    protected abstract fun detail(id: String): BookInfoRequest?
    @Query("SELECT * FROM books WHERE bookUrl = :bookUrl")
    protected abstract fun book(bookUrl: String): Book?
    @Query("SELECT EXISTS(SELECT 1 FROM reader_book_owners WHERE bookUrl = :bookUrl AND sessionId = :id AND ownerToken = :token)")
    protected abstract fun ownsBook(id: String, token: String, bookUrl: String): Boolean
    @Query("DELETE FROM reader_book_owners WHERE sessionId = :id AND ownerToken = :token")
    protected abstract fun releaseReader(id: String, token: String)
    @Query("DELETE FROM reader_detail_book_leases WHERE requestId = :id")
    protected abstract fun releaseDetail(id: String)
    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract fun insert(request: ReaderDetailRequest)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract fun setLease(lease: ReaderDetailBookLease)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract fun setReader(owner: ReaderBookOwner)
    @Update protected abstract fun update(request: ReaderDetailRequest): Int
    @Update protected abstract fun updateSession(session: ReaderSessionState): Int

    @Transaction
    open fun begin(id: String, token: String, revision: Long, requestId: String,
        chapter: Int, page: Int, now: Long): ReaderDetailRequest? {
        require(requestId.isNotBlank() && requestId.length <= 128 && chapter >= 0 && page >= 0)
        val state = session(id)?.takeIf { it.ownerToken == token && it.revision == revision && !it.deleted } ?: return null
        active(id)?.let { current ->
            return current.takeIf { it.phase == ReaderDetailPhase.Requested && it.revision == state.revision && it.bookUrl == state.bookUrl }
        }
        if (!ownsBook(id, token, state.bookUrl) || book(state.bookUrl) == null) return null
        val updated = state.copy(revision = state.revision + 1, chapterIndex = chapter, pageIndex = page,
            positionTime = 0, updatedAt = maxOf(state.updatedAt, now))
        check(updateSession(updated) == 1)
        releaseReader(id, token)
        return ReaderDetailRequest(requestId, id, state.bookUrl, updated.revision, updatedAt = now).also {
            insert(it); setLease(ReaderDetailBookLease(it.id, state.bookUrl))
        }
    }

    /** Producer replacement is atomic with the detail request's owner claim in the repository. */
    @Transaction
    open fun claim(id: String, bookUrl: String, token: String, now: Long): ReaderDetailRequest? {
        val request = get(id)?.takeIf { it.bookUrl == bookUrl && it.phase in setOf(ReaderDetailPhase.Requested, ReaderDetailPhase.Displayed) }
            ?: return null
        val reader = session(request.sessionId)?.takeIf { !it.deleted && it.bookUrl == request.bookUrl && it.revision == request.revision }
            ?: return null
        if (lease(request.id) == null) {
            check(update(request.copy(phase = ReaderDetailPhase.Returned, returnBookUrl = request.bookUrl,
                deleted = true, updatedAt = maxOf(request.updatedAt, now))) == 1)
            return null
        }
        return request.copy(phase = ReaderDetailPhase.Displayed, producerToken = token,
            updatedAt = maxOf(request.updatedAt, now)).also { check(update(it) == 1) }
    }

    open fun producerOwns(request: BookInfoRequest): Boolean {
        if (!request.returnToReader) return true
        val child = get(request.id) ?: return false
        val reader = session(child.sessionId) ?: return false
        return child.phase == ReaderDetailPhase.Displayed && child.producerToken == request.ownerToken &&
            child.revision == reader.revision && child.bookUrl == reader.bookUrl && !reader.deleted && lease(child.id) != null
    }

    /** Called after a validated source/preview/child-result transaction has saved its new row. */
    open fun bindBook(id: String, token: String, bookUrl: String): Boolean {
        val request = get(id)?.takeIf { it.phase == ReaderDetailPhase.Displayed && it.producerToken == token } ?: return false
        if (book(bookUrl) == null || detail(id)?.let { it.bookUrl == bookUrl && !it.deleted } != true) return false
        setLease(ReaderDetailBookLease(request.id, bookUrl))
        return true
    }

    @Transaction
    open fun finish(id: String, token: String, position: BookInfoNavigationPosition? = null, now: Long): Boolean {
        val parent = detail(id)?.takeIf { it.returnToReader && it.ownerToken == token } ?: return false
        val request = get(id)?.takeIf { it.phase == ReaderDetailPhase.Displayed && it.producerToken == token } ?: return false
        val reader = session(request.sessionId)?.takeIf { it.revision == request.revision && it.bookUrl == request.bookUrl && !it.deleted }
            ?: return false
        val deleted = parent.deleted || lease(id)?.bookUrl != parent.bookUrl || book(parent.bookUrl) == null
        require(position == null || position.isValid())
        check(update(request.copy(phase = ReaderDetailPhase.Returned, returnBookUrl = parent.bookUrl, deleted = deleted,
            position = position, updatedAt = maxOf(request.updatedAt, reader.updatedAt, now))) == 1)
        return true
    }

    @Query("""UPDATE reader_detail_requests SET phase = 'Returned', deleted = 1, updatedAt = MAX(updatedAt, :now)
        WHERE phase = 'Displayed' AND id IN (SELECT id FROM book_info_requests WHERE returnToReader = 1
        AND bookUrl = :bookUrl AND deleted = 1 AND ownerToken = reader_detail_requests.producerToken)""")
    abstract fun publishDeleted(bookUrl: String, now: Long): Int

    @Transaction
    open fun apply(id: String, token: String, now: Long): ReaderDetailApplied? {
        val state = session(id)?.takeIf { it.ownerToken == token } ?: return null
        val result = active(id) ?: return null
        if (state.revision != result.revision || state.bookUrl != result.bookUrl || state.deleted) {
            check(update(result.copy(phase = ReaderDetailPhase.Cancelled, updatedAt = maxOf(result.updatedAt, now))) == 1)
            releaseDetail(result.id)
            return null
        }
        if (result.phase != ReaderDetailPhase.Returned) return null
        val target = result.returnBookUrl?.let(::book)
        val deleted = result.deleted || target == null || lease(result.id)?.bookUrl != result.returnBookUrl
        val chapter = result.position?.chapterIndex ?: target?.durChapterIndex ?: state.chapterIndex
        val page = result.position?.let { it.pdfPageIndex?.rem(io.legado.app.model.localBook.PdfFile.PAGE_SIZE) ?: it.chapterPosition }
            ?: target?.durChapterPos ?: state.pageIndex
        val updated = state.copy(bookUrl = target?.bookUrl ?: state.bookUrl, inBookshelf = !deleted &&
            target!!.type and io.legado.app.constant.BookType.notShelf == 0, chapterIndex = chapter, pageIndex = page,
            chapterChanged = true,
            revision = state.revision + 1, positionTime = 0, deleted = deleted, updatedAt = maxOf(state.updatedAt, now))
        releaseReader(id, token)
        check(updateSession(updated) == 1)
        if (!deleted) setReader(ReaderBookOwner(updated.bookUrl, id, token))
        val consumed = result.copy(phase = ReaderDetailPhase.Consumed, updatedAt = maxOf(result.updatedAt, now))
        check(update(consumed) == 1)
        releaseDetail(result.id)
        return ReaderDetailApplied(updated, consumed)
    }

    @Transaction
    open fun cancelRequested(id: String, token: String, requestId: String, now: Long): Boolean {
        val state = session(id)?.takeIf { it.ownerToken == token } ?: return false
        val request = get(requestId)?.takeIf { it.sessionId == id && it.revision == state.revision && it.phase == ReaderDetailPhase.Requested }
            ?: return false
        check(update(request.copy(phase = ReaderDetailPhase.Cancelled, updatedAt = maxOf(request.updatedAt, now))) == 1)
        releaseDetail(requestId)
        return true
    }

    /** A resumed consumer can retire a missing producer without adopting an unproved row. */
    @Transaction
    open fun abandon(id: String, token: String, requestId: String, now: Long): Boolean {
        val reader = session(id)?.takeIf { it.ownerToken == token && !it.deleted } ?: return false
        val request = get(requestId)?.takeIf { it.sessionId == id && it.revision == reader.revision &&
            it.bookUrl == reader.bookUrl && it.phase == ReaderDetailPhase.Displayed } ?: return false
        val parent = detail(requestId)
        val target = lease(requestId)?.bookUrl
        check(update(request.copy(phase = ReaderDetailPhase.Returned,
            returnBookUrl = parent?.bookUrl ?: target ?: request.bookUrl,
            deleted = parent?.deleted == true || target == null || target != (parent?.bookUrl ?: target),
            updatedAt = maxOf(request.updatedAt, now))) == 1)
        return true
    }
}
