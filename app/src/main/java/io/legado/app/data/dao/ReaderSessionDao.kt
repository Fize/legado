package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import io.legado.app.data.entities.ReaderSessionState
import io.legado.app.data.entities.ReaderBookOwner
import io.legado.app.data.entities.ReaderCatalogRequest
import kotlinx.coroutines.flow.Flow

@Dao
abstract class ReaderSessionDao {
    @Query("DELETE FROM reader_sessions WHERE id = :id AND ownerToken = :token")
    abstract fun close(id: String, token: String): Int

    @Query("SELECT * FROM reader_sessions WHERE id = :id")
    abstract fun get(id: String): ReaderSessionState?

    @Query("SELECT * FROM reader_sessions WHERE id = :id")
    abstract fun observe(id: String): Flow<ReaderSessionState?>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract fun insert(state: ReaderSessionState): Long

    @Query("UPDATE reader_sessions SET ownerToken = :token, updatedAt = :now WHERE id = :id")
    protected abstract fun setOwner(id: String, token: String, now: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract fun setBookOwner(owner: ReaderBookOwner)

    @Query("DELETE FROM reader_book_owners WHERE sessionId = :id AND ownerToken = :token")
    protected abstract fun releaseBookOwners(id: String, token: String)

    @Query("SELECT EXISTS(SELECT 1 FROM reader_detail_requests WHERE sessionId = :id AND phase IN ('Requested','Displayed','Returned'))")
    protected abstract fun detailIsActive(id: String): Boolean

    @Transaction
    open fun claim(initial: ReaderSessionState, token: String): ReaderSessionState {
        insert(initial)
        val existing = checkNotNull(get(initial.id))
        require(existing.initialBookUrl == initial.initialBookUrl) { "Reader identity belongs to another book" }
        setOwner(initial.id, token, initial.updatedAt)
        activate(initial.id, token)
        return checkNotNull(get(initial.id))
    }

    @Transaction
    open fun activate(id: String, token: String): Boolean {
        val state = get(id)?.takeIf { it.ownerToken == token } ?: return false
        if (state.deleted || detailIsActive(id) || !bookExists(state.bookUrl)) return false
        setBookOwner(ReaderBookOwner(state.bookUrl, id, token))
        return true
    }

    @Query("SELECT EXISTS(SELECT 1 FROM reader_sessions s JOIN reader_book_owners o ON o.bookUrl = s.bookUrl WHERE s.id = :id AND s.ownerToken = :token AND s.revision = :revision AND s.bookUrl = :bookUrl AND s.deleted = 0 AND o.sessionId = s.id AND o.ownerToken = s.ownerToken AND NOT EXISTS(SELECT 1 FROM reader_detail_requests WHERE sessionId = s.id AND phase IN ('Requested','Displayed','Returned')))")
    abstract fun ownsBook(id: String, token: String, revision: Long, bookUrl: String): Boolean

    @Query("UPDATE reader_sessions SET chapterIndex = :chapter, pageIndex = :page, positionTime = :now, updatedAt = MAX(updatedAt, :now) WHERE id = :id AND ownerToken = :token AND revision = :revision AND bookUrl = :bookUrl AND deleted = 0 AND positionTime <= :now AND NOT EXISTS(SELECT 1 FROM reader_detail_requests WHERE sessionId = :id AND phase IN ('Requested','Displayed','Returned'))")
    protected abstract fun savePosition(id: String, token: String, revision: Long, bookUrl: String,
        chapter: Int, page: Int, now: Long): Int

    @Query("""UPDATE books SET lastCheckCount = 0, durChapterTime = MAX(durChapterTime, :readAt),
        durChapterIndex = :chapter, durChapterPos = :page, durChapterTitle = COALESCE(:title, durChapterTitle)
        WHERE bookUrl = :bookUrl AND origin = :origin
        AND EXISTS(SELECT 1 FROM reader_book_owners WHERE bookUrl = :bookUrl AND sessionId = :id AND ownerToken = :token)""")
    protected abstract fun saveBookProgress(id: String, token: String, bookUrl: String, origin: String,
        chapter: Int, page: Int, title: String?, readAt: Long): Int

    @Query("SELECT EXISTS(SELECT 1 FROM books WHERE bookUrl = :bookUrl)")
    protected abstract fun bookExists(bookUrl: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM books WHERE bookUrl = :bookUrl AND origin = :origin)")
    protected abstract fun bookMatches(bookUrl: String, origin: String): Boolean

    @Transaction
    open fun saveProgress(id: String, token: String, revision: Long, bookUrl: String, origin: String,
        chapter: Int, page: Int, title: String?, readAt: Long): Int {
        if (chapter < 0 || page < 0 || !bookMatches(bookUrl, origin) || savePosition(id, token, revision, bookUrl, chapter, page, readAt) == 0) return 0
        return saveBookProgress(id, token, bookUrl, origin, chapter, page, title, readAt)
    }

    @Query("UPDATE reader_sessions SET bookUrl = :bookUrl, chapterIndex = :chapter, pageIndex = :page, revision = revision + 1, positionTime = 0, updatedAt = MAX(updatedAt, :now) WHERE id = :id AND ownerToken = :token")
    protected abstract fun move(id: String, token: String, bookUrl: String, chapter: Int, page: Int, now: Long): Int

    @Transaction
    open fun moveBook(id: String, token: String, bookUrl: String, chapter: Int, page: Int, now: Long): ReaderSessionState? {
        val current = get(id) ?: return null
        if (chapter < 0 || page < 0 || !bookExists(bookUrl) || !ownsBook(id, token, current.revision, current.bookUrl)) return null
        if (move(id, token, bookUrl, chapter, page, now) == 0) return null
        releaseBookOwners(id, token)
        setBookOwner(ReaderBookOwner(bookUrl, id, token))
        return get(id)
    }

    /** Validate the old owner before deletion cascades revoke it; publish the new key in the same transaction. */
    @Transaction
    open fun replaceOwnedBook(id: String, token: String, revision: Long, oldBookUrl: String,
        newBookUrl: String, chapter: Int, page: Int, now: Long, replace: () -> Unit): ReaderSessionState {
        check(ownsBook(id, token, revision, oldBookUrl)) { "Reader no longer owns this book" }
        replace()
        check(move(id, token, newBookUrl, chapter, page, now) == 1)
        releaseBookOwners(id, token)
        setBookOwner(ReaderBookOwner(newBookUrl, id, token))
        return checkNotNull(get(id))
    }

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract fun insertCatalog(request: ReaderCatalogRequest)

    @Query("SELECT * FROM reader_catalog_requests WHERE id = :id")
    abstract fun catalog(id: String): ReaderCatalogRequest?

    @Query("SELECT * FROM reader_catalog_requests WHERE id = :id")
    abstract fun observeCatalogRequest(id: String): Flow<ReaderCatalogRequest?>

    @Query("SELECT * FROM reader_catalog_requests WHERE sessionId = :id AND consumed = 0 AND chapterIndex IS NOT NULL ORDER BY rowid DESC LIMIT 1")
    abstract fun pendingCatalog(id: String): ReaderCatalogRequest?

    @Query("SELECT * FROM reader_catalog_requests WHERE sessionId = :id AND consumed = 0 AND chapterIndex IS NOT NULL ORDER BY rowid DESC LIMIT 1")
    abstract fun observeCatalog(id: String): Flow<ReaderCatalogRequest?>

    @Transaction
    open fun beginCatalog(id: String, token: String, requestId: String, chapter: Int, page: Int, now: Long): ReaderCatalogRequest? {
        val current = get(id)?.takeIf { it.ownerToken == token } ?: return null
        val state = moveBook(id, token, current.bookUrl, chapter, page, now) ?: return null
        return ReaderCatalogRequest(requestId, id, state.bookUrl, state.revision).also(::insertCatalog)
    }

    @Query("UPDATE reader_catalog_requests SET chapterIndex = :chapter, pageIndex = :page WHERE id = :id AND consumed = 0 AND chapterIndex IS NULL")
    protected abstract fun resolveCatalog(id: String, chapter: Int, page: Int): Int

    @Transaction
    open fun deliverCatalog(id: String, bookUrl: String, chapter: Int, page: Int): Boolean {
        if (chapter < 0 || page < 0 || !bookExists(bookUrl)) return false
        val request = catalog(id)?.takeIf { it.bookUrl == bookUrl && !it.consumed } ?: return false
        val session = get(request.sessionId) ?: return false
        if (session.bookUrl != bookUrl || session.revision != request.revision) return false
        return resolveCatalog(id, chapter, page) == 1
    }

    @Query("UPDATE reader_catalog_requests SET consumed = 1 WHERE id = :id")
    protected abstract fun markConsumed(id: String)

    @Transaction
    open fun applyCatalog(id: String, token: String, now: Long): ReaderSessionState? {
        val state = get(id)?.takeIf { it.ownerToken == token } ?: return null
        val result = pendingCatalog(id) ?: return null
        if (result.revision != state.revision || result.bookUrl != state.bookUrl) {
            markConsumed(result.id)
            return null
        }
        val updated = moveBook(id, token, state.bookUrl, checkNotNull(result.chapterIndex), checkNotNull(result.pageIndex), now)
        if (updated != null) markConsumed(result.id)
        return updated
    }
}
