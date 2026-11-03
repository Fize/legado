package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import io.legado.app.constant.BookType
import io.legado.app.data.entities.*
import kotlinx.coroutines.flow.Flow

@Dao
abstract class BookInfoRequestDao {
    @Query("SELECT * FROM book_info_requests WHERE id = :id")
    abstract fun get(id: String): BookInfoRequest?

    @Query("SELECT * FROM book_info_requests WHERE id = :id")
    abstract fun observe(id: String): Flow<BookInfoRequest?>

    @Query("SELECT * FROM book_info_child_requests WHERE id = :id")
    abstract fun child(id: String): BookInfoChildRequest?

    @Query("SELECT * FROM book_info_child_requests WHERE id = :id")
    abstract fun observeChild(id: String): Flow<BookInfoChildRequest?>

    @Query("""SELECT c.* FROM book_info_child_requests c JOIN book_info_requests p ON p.id = c.requestId
        WHERE c.id = :id AND p.deleted = 0 AND c.revision = p.revision AND c.bookUrl = p.bookUrl""")
    abstract fun observeCurrentChild(id: String): Flow<BookInfoChildRequest?>

    @Query("""SELECT c.* FROM book_info_child_requests c JOIN book_info_requests p ON p.id = c.requestId
        WHERE p.id = :id AND p.deleted = 0 AND c.revision = p.revision AND c.bookUrl = p.bookUrl
        AND c.phase != 'Consumed' LIMIT 1""")
    abstract fun activeChild(id: String): BookInfoChildRequest?

    @Query("""SELECT c.* FROM book_info_child_requests c JOIN book_info_requests p ON p.id = c.requestId
        WHERE p.id = :id AND p.deleted = 0 AND c.revision = p.revision AND c.bookUrl = p.bookUrl
        AND c.phase != 'Consumed' LIMIT 1""")
    abstract fun observeActiveChild(id: String): Flow<BookInfoChildRequest?>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract fun insert(request: BookInfoRequest): Long
    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract fun insertChild(request: BookInfoChildRequest)
    @Update protected abstract fun update(request: BookInfoRequest): Int
    @Update protected abstract fun updateChild(request: BookInfoChildRequest): Int

    @Query("SELECT * FROM books WHERE bookUrl = :bookUrl")
    protected abstract fun book(bookUrl: String): Book?

    @Query("""SELECT EXISTS(SELECT 1 FROM reader_sessions WHERE id = :id AND ownerToken = :token
        AND initialBookUrl = :initialBookUrl AND bookUrl = :currentBookUrl)""")
    protected abstract fun matchesReader(id: String, token: String, initialBookUrl: String, currentBookUrl: String): Boolean
    @Query("SELECT deleted FROM reader_sessions WHERE id = :id AND ownerToken = :token")
    protected abstract fun readerWasDeleted(id: String, token: String): Boolean?

    @Query("""SELECT EXISTS(SELECT 1 FROM reader_detail_requests d JOIN reader_sessions s ON s.id = d.sessionId
        JOIN reader_detail_book_leases l ON l.requestId = d.id WHERE d.id = :id AND d.producerToken = :token
        AND d.phase = 'Displayed' AND d.revision = s.revision AND d.bookUrl = s.bookUrl AND s.deleted = 0)""")
    protected abstract fun readerOwnsDetail(id: String, token: String): Boolean

    @Query("DELETE FROM book_info_requests WHERE id = :id AND ownerToken = :token")
    abstract fun close(id: String, token: String): Int

    @Query("SELECT EXISTS(SELECT 1 FROM book_info_requests WHERE bookUrl = :bookUrl AND deleted = 0 AND id != :excludingId)")
    abstract fun anotherRequestUsesBook(bookUrl: String, excludingId: String): Boolean

    @Query("""SELECT EXISTS(SELECT 1 FROM book_info_requests p WHERE p.id = :id AND
        (p.initialBookUrl = :bookUrl OR EXISTS(SELECT 1 FROM book_info_child_requests c
            WHERE c.requestId = p.id AND c.bookUrl = :bookUrl)))
        AND NOT EXISTS(SELECT 1 FROM reader_sessions s WHERE s.bookUrl = :bookUrl AND NOT EXISTS(
            SELECT 1 FROM reader_detail_requests d JOIN book_info_requests p2 ON p2.id = d.id
            WHERE d.id = :id AND d.sessionId = s.id AND d.revision = s.revision AND d.phase = 'Displayed'
            AND d.producerToken = p2.ownerToken AND p2.returnToReader = 1))
        AND NOT EXISTS(SELECT 1 FROM book_info_requests WHERE id != :id AND bookUrl = :bookUrl AND deleted = 0)""")
    abstract fun canRetirePreview(id: String, bookUrl: String): Boolean

    private fun owned(id: String, token: String, revision: Long, bookUrl: String): BookInfoRequest? =
        get(id)?.takeIf { it.ownerToken == token && it.revision == revision && it.bookUrl == bookUrl && !it.deleted }

    private fun currentChild(id: String): BookInfoChildRequest? {
        val child = child(id) ?: return null
        val parent = get(child.requestId) ?: return null
        return child.takeIf { !parent.deleted && parent.revision == it.revision && parent.bookUrl == it.bookUrl &&
            (!parent.returnToReader || readerOwnsDetail(parent.id, parent.ownerToken)) }
    }

    private fun BookInfoChildRequest.isOpen() = phase == BookInfoChildPhase.Requested || phase == BookInfoChildPhase.Displayed

    @Transaction
    open fun claim(initial: BookInfoRequest, token: String): BookInfoRequest {
        require(initial.id.isNotBlank() && initial.id.length <= 128 && initial.initialBookUrl.isNotBlank() &&
            initial.bookUrl.isNotBlank() && token.isNotBlank() && token.length <= 128)
        require(initial.previewKey == null || (initial.previewKey.isNotBlank() && initial.previewKey.length <= 128))
        insert(initial.copy(ownerToken = token))
        val current = checkNotNull(get(initial.id))
        require(current.initialBookUrl == initial.initialBookUrl) { "Detail request belongs to another book" }
        require(current.returnToReader == initial.returnToReader) { "Detail request belongs to another navigation flow" }
        require(current.previewKey == null || current.previewKey == initial.previewKey) {
            "Detail request belongs to another preview"
        }
        val claimed = current.copy(ownerToken = token, previewKey = current.previewKey ?: initial.previewKey,
            updatedAt = maxOf(current.updatedAt, initial.updatedAt))
        check(update(claimed) == 1)
        return claimed
    }

    @Transaction
    open fun begin(id: String, token: String, revision: Long, bookUrl: String, childId: String,
        kind: BookInfoChildKind, position: BookInfoNavigationPosition?, now: Long): BookInfoChildRequest? {
        val parent = owned(id, token, revision, bookUrl) ?: return null
        if (book(bookUrl) == null || position?.isValid() == false) return null
        require(childId.isNotBlank())
        require(kind == BookInfoChildKind.Reader || position == null)
        val child = BookInfoChildRequest(childId, id, bookUrl, revision + 1, kind, position = position, updatedAt = now)
        insertChild(child)
        check(update(parent.copy(revision = child.revision, updatedAt = maxOf(parent.updatedAt, now))) == 1)
        return child
    }

    @Transaction
    open fun claimChild(id: String, bookUrl: String, kind: BookInfoChildKind, token: String, now: Long): BookInfoChildRequest? {
        require(token.isNotBlank())
        val child = currentChild(id)?.takeIf { it.bookUrl == bookUrl && it.kind == kind && it.isOpen() } ?: return null
        return child.copy(phase = BookInfoChildPhase.Displayed, producerToken = token, updatedAt = maxOf(child.updatedAt, now))
            .also { check(updateChild(it) == 1) }
    }

    @Transaction
    open fun deliverCatalog(id: String, token: String, bookUrl: String, position: BookInfoNavigationPosition, now: Long): Boolean {
        if (token.isBlank() || !position.isValid() || book(bookUrl) == null) return false
        val child = currentChild(id)?.takeIf { it.kind == BookInfoChildKind.Catalog && it.producerToken == token &&
            it.bookUrl == bookUrl && it.isOpen() } ?: return false
        return updateChild(child.copy(phase = BookInfoChildPhase.Returned, position = position,
            updatedAt = maxOf(child.updatedAt, now))) == 1
    }

    @Transaction
    open fun deliverEdit(id: String, token: String, bookUrl: String, now: Long): Boolean {
        if (token.isBlank() || book(bookUrl) == null) return false
        val child = currentChild(id)?.takeIf { it.kind == BookInfoChildKind.Edit && it.producerToken == token &&
            it.bookUrl == bookUrl && it.isOpen() } ?: return false
        return updateChild(child.copy(phase = BookInfoChildPhase.Returned, updatedAt = maxOf(child.updatedAt, now))) == 1
    }

    /** Deliver before disposing the reader row; its durable owner proves the canonical book key. */
    @Transaction
    open fun deliverReader(id: String, token: String, initialBookUrl: String, currentBookUrl: String, now: Long): Boolean {
        if (token.isBlank()) return false
        val child = currentChild(id)?.takeIf { it.kind == BookInfoChildKind.Reader && it.producerToken == token &&
            it.bookUrl == initialBookUrl && it.isOpen() } ?: return false
        require(currentBookUrl.isNotBlank())
        if (!matchesReader(id, token, initialBookUrl, currentBookUrl)) return false
        val returnedBook = book(currentBookUrl)
        val deleted = returnedBook == null || readerWasDeleted(id, token) == true
        return updateChild(child.copy(phase = BookInfoChildPhase.Returned, returnBookUrl = currentBookUrl,
            readerDeleted = deleted,
            readerInBookshelf = !deleted && returnedBook != null && returnedBook.type and BookType.notShelf == 0,
            updatedAt = maxOf(child.updatedAt, now))) == 1
    }

    /** Mature Activity hosts use the child itself as their durable return receipt. */
    @Transaction
    open fun claimPlatformReader(id: String, initialBookUrl: String, token: String, now: Long): BookInfoChildRequest? {
        // A restored Compose session must finish through its original ownership protocol.
        if (platformReaderHasSession(id)) return null
        val previous = currentChild(id)?.takeIf {
            it.kind == BookInfoChildKind.Reader && it.bookUrl == initialBookUrl && it.isOpen()
        } ?: return null
        val key = previous.returnBookUrl ?: initialBookUrl
        if (book(key) == null) return null
        return claimChild(id, initialBookUrl, BookInfoChildKind.Reader, token, now)?.copy(
            returnBookUrl = key,
            position = previous.position,
        )?.also { check(updateChild(it) == 1) }
    }

    @Query("SELECT EXISTS(SELECT 1 FROM reader_sessions WHERE id = :id)")
    protected abstract fun platformReaderHasSession(id: String): Boolean

    /** Move one live mature-player receipt without publishing a return between hosts. */
    @Transaction
    open fun transferPlatformReader(id: String, bookUrl: String, previousToken: String, nextToken: String, now: Long): BookInfoChildRequest? {
        require(previousToken.isNotBlank() && nextToken.isNotBlank() && previousToken != nextToken)
        val child = currentChild(id)?.takeIf { it.kind == BookInfoChildKind.Reader &&
            it.phase == BookInfoChildPhase.Displayed && it.producerToken == previousToken &&
            it.returnBookUrl == bookUrl && it.position == null && !platformReaderHasSession(id) } ?: return null
        return child.copy(producerToken = nextToken, updatedAt = maxOf(child.updatedAt, now))
            .also { check(updateChild(it) == 1) }
    }

    @Transaction
    open fun acknowledgePlatformPosition(id: String, token: String): Boolean {
        val child = currentChild(id)?.takeIf { it.kind == BookInfoChildKind.Reader &&
            it.phase == BookInfoChildPhase.Displayed && it.producerToken == token && !platformReaderHasSession(id) } ?: return false
        return updateChild(child.copy(position = null)) == 1
    }

    @Transaction
    open fun movePlatformReader(id: String, token: String, previousBookUrl: String, nextBookUrl: String, now: Long): Boolean {
        val child = currentChild(id)?.takeIf { it.kind == BookInfoChildKind.Reader &&
            it.phase == BookInfoChildPhase.Displayed && it.producerToken == token &&
            it.returnBookUrl == previousBookUrl && !platformReaderHasSession(id) } ?: return false
        if (book(nextBookUrl) == null) return false
        return updateChild(child.copy(returnBookUrl = nextBookUrl, updatedAt = maxOf(child.updatedAt, now))) == 1
    }

    @Transaction
    open fun finishPlatformReader(id: String, token: String, now: Long): Boolean {
        val child = currentChild(id)?.takeIf { it.kind == BookInfoChildKind.Reader &&
            it.phase == BookInfoChildPhase.Displayed && it.producerToken == token &&
            it.returnBookUrl != null && !platformReaderHasSession(id) } ?: return false
        val returnedBook = book(checkNotNull(child.returnBookUrl))
        return updateChild(child.copy(phase = BookInfoChildPhase.Returned,
            readerDeleted = returnedBook == null,
            readerInBookshelf = returnedBook != null && returnedBook.type and BookType.notShelf == 0,
            updatedAt = maxOf(child.updatedAt, now))) == 1
    }

    @Transaction
    open fun cancelChild(id: String, token: String, now: Long): Boolean {
        if (token.isBlank()) return false
        val child = currentChild(id)?.takeIf { it.producerToken == token && it.isOpen() } ?: return false
        return updateChild(child.copy(phase = BookInfoChildPhase.Cancelled, updatedAt = maxOf(child.updatedAt, now))) == 1
    }

    @Transaction
    open fun cancelRequested(id: String, token: String, revision: Long, bookUrl: String, childId: String, now: Long): Boolean {
        owned(id, token, revision, bookUrl) ?: return false
        val child = currentChild(childId)?.takeIf { it.requestId == id && it.phase == BookInfoChildPhase.Requested } ?: return false
        return updateChild(child.copy(phase = BookInfoChildPhase.Cancelled, updatedAt = maxOf(child.updatedAt, now))) == 1
    }

    @Transaction
    open fun abandonDisplayed(id: String, token: String, revision: Long, bookUrl: String, childId: String, now: Long): Boolean {
        owned(id, token, revision, bookUrl) ?: return false
        val child = currentChild(childId)?.takeIf { it.requestId == id && it.phase == BookInfoChildPhase.Displayed } ?: return false
        return updateChild(child.copy(phase = BookInfoChildPhase.Cancelled, updatedAt = maxOf(child.updatedAt, now))) == 1
    }

    @Transaction
    open fun requireChildOwner(id: String, token: String, bookUrl: String, kind: BookInfoChildKind) {
        check(token.isNotBlank() && currentChild(id)?.let {
            it.producerToken == token && it.bookUrl == bookUrl && it.kind == kind && it.isOpen()
        } == true) { "Detail child request expired" }
    }

    /** Consume a catalogue selection and durably queue its reading handoff in one transaction. */
    @Transaction
    open fun continueCatalog(id: String, token: String, revision: Long, bookUrl: String, childId: String,
        readerId: String, now: Long): BookInfoChildRequest? {
        val parent = owned(id, token, revision, bookUrl) ?: return null
        val child = currentChild(childId)?.takeIf { it.requestId == id && it.kind == BookInfoChildKind.Catalog &&
            it.phase == BookInfoChildPhase.Returned && it.position != null } ?: return null
        check(book(bookUrl) != null) { "Book was removed before opening the reader" }
        val reader = BookInfoChildRequest(readerId, id, bookUrl, revision + 1, BookInfoChildKind.Reader,
            position = child.position, updatedAt = now)
        insertChild(reader)
        check(updateChild(child.copy(phase = BookInfoChildPhase.Consumed, updatedAt = maxOf(child.updatedAt, now))) == 1)
        check(update(parent.copy(revision = reader.revision, updatedAt = maxOf(parent.updatedAt, now))) == 1)
        return reader
    }

    @Transaction
    open fun consume(id: String, token: String, revision: Long, bookUrl: String, childId: String, now: Long): BookInfoChildRequest? {
        val parent = owned(id, token, revision, bookUrl) ?: return null
        val child = currentChild(childId)?.takeIf { it.requestId == id &&
            (it.phase == BookInfoChildPhase.Cancelled || it.phase == BookInfoChildPhase.Returned) } ?: return null
        require(child.kind != BookInfoChildKind.Catalog || child.phase == BookInfoChildPhase.Cancelled) {
            "Catalogue results must atomically queue the reader"
        }
        val returnedUrl = child.returnBookUrl ?: bookUrl
        val deleted = child.kind == BookInfoChildKind.Reader && child.phase == BookInfoChildPhase.Returned &&
            (child.readerDeleted || book(returnedUrl) == null)
        check(updateChild(child.copy(phase = BookInfoChildPhase.Consumed, updatedAt = maxOf(child.updatedAt, now))) == 1)
        check(update(parent.copy(bookUrl = returnedUrl, deleted = deleted, revision = revision + 1,
            updatedAt = maxOf(parent.updatedAt, now))) == 1)
        return child
    }

    /** Source replacement and the request's new canonical key commit together. */
    @Transaction
    open fun replacePreview(id: String, token: String, revision: Long, oldBookUrl: String,
        newBookUrl: String, now: Long, replace: () -> Unit): BookInfoRequest {
        val parent = checkNotNull(owned(id, token, revision, oldBookUrl)) { "Detail request no longer owns this preview" }
        require(newBookUrl.isNotBlank())
        replace()
        return parent.copy(bookUrl = newBookUrl, revision = revision + 1, previewCommitRevision = revision + 1, updatedAt = maxOf(parent.updatedAt, now))
            .also { check(update(it) == 1) }
    }

    /** Source replacement and the request's new canonical key commit together. */
    @Transaction
    open fun replaceBook(id: String, token: String, revision: Long, oldBookUrl: String, newBookUrl: String,
        now: Long, replace: () -> Unit): BookInfoRequest {
        val parent = checkNotNull(owned(id, token, revision, oldBookUrl)) { "Detail request no longer owns this book" }
        require(newBookUrl.isNotBlank())
        replace()
        check(book(newBookUrl) != null) { "Replacement book was not saved" }
        return parent.copy(bookUrl = newBookUrl, revision = revision + 1, updatedAt = maxOf(parent.updatedAt, now))
            .also { check(update(it) == 1) }
    }
}
