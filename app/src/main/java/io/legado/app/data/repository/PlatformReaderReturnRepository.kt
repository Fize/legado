package io.legado.app.data.repository

import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.BookInfoChildRequest
import io.legado.app.data.entities.Book
import io.legado.app.constant.BookType
import io.legado.app.help.storage.RestoreRecoveryGate
import io.legado.app.help.globalExecutor
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import javax.inject.Inject
import javax.inject.Singleton

/** Small adapter for mature readers; no content or mutable Book crosses the Activity boundary. */
@Singleton
class PlatformReaderReturnRepository @Inject constructor(private val database: AppDatabase) {
    data class MediaPreparation(val owner: BookInfoChildRequest, val book: Book, val positionApplied: Boolean)

    /** Commit a media seek and consume its instruction together, before the player starts. */
    fun prepareMedia(id: String, bookUrl: String, token: String, expectedType: Int, requireCurrent: () -> Unit = {}): MediaPreparation =
        run {
            reconcilePendingReturns(id)
            check(!reconcilePendingStop(id)) { "Playback handoff was stopped" }
            database.runInTransaction<MediaPreparation> {
                requireCurrent()
                require(expectedType == BookType.audio || expectedType == BookType.video)
                val owner = claim(id, bookUrl, token)
                val book = checkNotNull(database.bookDao.getBook(checkNotNull(owner.returnBookUrl)))
                check(book.type and expectedType != 0) { "The book no longer belongs to this media player" }
                val position = owner.position
                if (position != null) {
                    applyMediaPosition(book, position.chapterIndex, position.chapterPosition, expectedType)
                    check(acknowledgePosition(owner))
                }
                MediaPreparation(owner.copy(position = null), book, position != null).also { requireCurrent() }
            }
        }

    /** A handoff consumes the old capability and never reapplies an already acknowledged seek. */
    fun transferMedia(id: String, bookUrl: String, previousToken: String, token: String, expectedType: Int, requireCurrent: () -> Unit = {}): MediaPreparation? =
        run {
            reconcilePendingReturns(id)
            check(!reconcilePendingStop(id)) { "Playback handoff was stopped" }
            database.runInTransaction<MediaPreparation?> {
                requireCurrent()
                require(expectedType == BookType.audio || expectedType == BookType.video)
                val previous = database.bookInfoRequestDao.child(id)
                // Explicitly closing the underlying detail bridge revokes its return receipt, not playback.
                if (previous == null || previous.phase in setOf(io.legado.app.data.entities.BookInfoChildPhase.Returned,
                        io.legado.app.data.entities.BookInfoChildPhase.Cancelled, io.legado.app.data.entities.BookInfoChildPhase.Consumed)) return@runInTransaction null
                val book = checkNotNull(database.bookDao.getBook(bookUrl))
                check(book.type and expectedType != 0)
                val owner = checkNotNull(database.bookInfoRequestDao.transferPlatformReader(id, bookUrl, previousToken, token, System.currentTimeMillis())) {
                    "Playback handoff ownership expired"
                }
                MediaPreparation(owner, book, false).also { requireCurrent() }
            }
        }

    fun seekMedia(bookUrl: String, chapterIndex: Int, expectedType: Int, requireCurrent: () -> Unit = {}): Book = database.runInTransaction<Book> {
        requireCurrent()
        require(expectedType == BookType.audio || expectedType == BookType.video)
        val book = checkNotNull(database.bookDao.getBook(bookUrl))
        check(book.type and expectedType != 0)
        applyMediaPosition(book, chapterIndex, 0, expectedType)
        requireCurrent()
        book
    }

    private fun applyMediaPosition(book: Book, chapterIndex: Int, chapterPosition: Int, expectedType: Int) {
        val chapters = database.bookChapterDao.getChapterList(book.bookUrl)
        val target = checkNotNull(chapters.firstOrNull { it.index == chapterIndex }) { "Media chapter was removed" }
        book.durChapterIndex = target.index
        book.durChapterPos = chapterPosition
        book.durChapterTime = maxOf(book.durChapterTime, System.currentTimeMillis())
        if (expectedType == BookType.video) {
            val volumes = chapters.filter { it.isVolume }
            if (volumes.isEmpty()) {
                book.durVolumeIndex = 0
                book.chapterInVolumeIndex = chapters.indexOf(target)
            } else {
                val volumeIndex = volumes.indexOfLast { it.index <= target.index }.coerceAtLeast(0)
                val volume = volumes[volumeIndex]
                val next = volumes.getOrNull(volumeIndex + 1)?.index ?: Int.MAX_VALUE
                val episodes = chapters.filter { it.index > volume.index && it.index < next }
                val episode = if (target.isVolume) episodes.firstOrNull() else target
                book.durVolumeIndex = volumeIndex
                book.chapterInVolumeIndex = if (episode == null) 0 else episodes.indexOf(episode).also {
                    check(it >= 0) { "Media chapter no longer belongs to its volume" }
                }
                book.durChapterIndex = episode?.index ?: volume.index
            }
        }
        book.durChapterTitle = chapters.firstOrNull { it.index == book.durChapterIndex }?.title
        database.bookDao.update(book)
    }

    fun claim(id: String, bookUrl: String, token: String): BookInfoChildRequest = database.runInTransaction<BookInfoChildRequest> {
        checkNotNull(database.bookInfoRequestDao.claimPlatformReader(id, bookUrl, token, System.currentTimeMillis())) {
            "Detail reading request expired"
        }
    }

    fun replaceSource(owner: BookInfoChildRequest, oldBookUrl: String, nextBookUrl: String, replace: () -> Unit) {
        database.runInTransaction {
            val current = database.bookInfoRequestDao.child(owner.id)
            check(current?.producerToken == owner.producerToken && current.returnBookUrl == oldBookUrl &&
                current.phase == io.legado.app.data.entities.BookInfoChildPhase.Displayed) { "Reader return ownership expired" }
            replace()
            check(database.bookInfoRequestDao.movePlatformReader(owner.id, owner.producerToken,
                oldBookUrl, nextBookUrl, System.currentTimeMillis())) { "Reader return ownership expired" }
        }
    }

    fun finish(owner: BookInfoChildRequest): Boolean =
        database.bookInfoRequestDao.finishPlatformReader(owner.id, owner.producerToken, System.currentTimeMillis())

    fun acknowledgePosition(owner: BookInfoChildRequest): Boolean =
        database.bookInfoRequestDao.acknowledgePlatformPosition(owner.id, owner.producerToken)

    /** A cold receiver may close before transfer. Only the captured old producer can finish. */
    internal fun finishUnclaimedHandoff(id: String, bookUrl: String, previousToken: String): Boolean =
        database.runInTransaction<Boolean> {
            RestoreRecoveryGate.requireBusinessReady()
            val child = database.bookInfoRequestDao.child(id)?.takeIf {
                it.kind == io.legado.app.data.entities.BookInfoChildKind.Reader &&
                    it.phase == io.legado.app.data.entities.BookInfoChildPhase.Displayed &&
                    it.producerToken == previousToken && it.returnBookUrl == bookUrl
            } ?: return@runInTransaction false
            finish(child)
        }

    /** Pending records are invisible in their live process until the final-save queue slot. */
    internal fun reconcilePendingReturns(id: String, returns: MediaHandoffReturns = MediaHandoffReturns.default,
        tickets: MediaHandoffTickets = MediaHandoffTickets.default) {
        RestoreRecoveryGate.requireBusinessReady()
        for (record in returns.ready(id)) {
            // Also covers a kill after the durable record, but before ticket retirement.
            tickets.retire(record.ticket)
            record.parent?.let(tickets::retire)
            database.runInTransaction {
                RestoreRecoveryGate.requireBusinessReady()
                val child = database.bookInfoRequestDao.child(id)
                if (child?.kind == io.legado.app.data.entities.BookInfoChildKind.Reader &&
                    child.phase == io.legado.app.data.entities.BookInfoChildPhase.Displayed &&
                    child.returnBookUrl == record.bookUrl &&
                    child.producerToken in setOf(record.previousToken, record.receiverToken)) {
                    finish(child)
                }
            }
            // A stale producer or an already consumed receipt is also a completed reconciliation.
            returns.clear(record)
        }
    }

    internal fun reconcilePendingStop(id: String): Boolean {
        RestoreRecoveryGate.requireBusinessReady()
        val record = MediaHandoffStops.read(id) ?: return false
        val matched = database.runInTransaction<Boolean> {
            val dao = database.bookInfoRequestDao
            val child = dao.child(id)?.takeIf(record::matches) ?: return@runInTransaction false
            when (child.phase) {
                io.legado.app.data.entities.BookInfoChildPhase.Displayed ->
                    dao.finishPlatformReader(id, child.producerToken, System.currentTimeMillis())
                io.legado.app.data.entities.BookInfoChildPhase.Requested -> {
                    val token = java.util.UUID.randomUUID().toString()
                    dao.claimChild(id, child.bookUrl, child.kind, token, System.currentTimeMillis())?.let {
                        check(dao.cancelChild(id, token, System.currentTimeMillis()))
                    }
                }
                else -> Unit
            }
            true
        }
        // Clear only after commit, even if the process died after Room finished the old stop.
        MediaHandoffStops.clear(record)
        return matched
    }

    fun observe(id: String) = flow {
        // Final playback saves and stop reconciliation share the same FIFO persistence queue.
        withContext(globalExecutor.asCoroutineDispatcher()) {
            reconcilePendingReturns(id)
            reconcilePendingStop(id)
        }
        emitAll(database.bookInfoRequestDao.observeCurrentChild(id))
    }.flowOn(Dispatchers.IO)

    fun closeBridge(id: String, bookUrl: String) {
        val closed = database.runInTransaction<Boolean> {
            val child = database.bookInfoRequestDao.child(id)?.takeIf { it.bookUrl == bookUrl } ?: return@runInTransaction false
            when (child.phase) {
                io.legado.app.data.entities.BookInfoChildPhase.Requested -> {
                    val token = java.util.UUID.randomUUID().toString()
                    val owner = database.bookInfoRequestDao.claimChild(id, bookUrl,
                        io.legado.app.data.entities.BookInfoChildKind.Reader, token, System.currentTimeMillis())
                    if (owner != null) check(database.bookInfoRequestDao.cancelChild(id, token, System.currentTimeMillis()))
                }
                io.legado.app.data.entities.BookInfoChildPhase.Displayed -> check(finish(child))
                else -> Unit
            }
            true
        }
        if (closed) MediaHandoffStops.discard(id)
    }

    companion object { const val REQUEST_ID = "platformReaderRequestId" }
}
