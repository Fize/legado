package io.legado.app.data.repository

import io.legado.app.constant.AppLog
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.*
import io.legado.app.help.book.getLocalUri
import io.legado.app.help.book.isLocal
import io.legado.app.help.config.LocalConfig
import io.legado.app.model.SourceCallBack
import io.legado.app.model.localBook.LocalBook
import io.legado.app.utils.GSON
import io.legado.app.utils.delete
import io.legado.app.utils.exists
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class BookInfoPendingDelete(val id: String, val bookUrl: String, val status: BookInfoDeleteStatus, val cancellable: Boolean)

/** Platform effects are separate from checkpoint/ownership transactions. */
@Singleton
open class BookInfoDeleteEffects @Inject constructor(private val database: AppDatabase) {
    open suspend fun removeRemote(command: BookInfoDeleteCommand, book: Book) {
        if (!command.deleteRemote) return
        if (!book.isLocal || command.target == null) throw BookInfoLibraryException(BookInfoLibraryError.Changed)
        try {
            if (!requireLibraryTarget(command.target).delete(book)) throw BookInfoLibraryException(BookInfoLibraryError.RemoteDelete)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: BookInfoLibraryException) { throw error }
        catch (_: Exception) { throw BookInfoLibraryException(BookInfoLibraryError.RemoteDelete) }
    }
    open suspend fun removeOriginal(command: BookInfoDeleteCommand, book: Book) {
        if (!command.deleteOriginal) return
        if (!book.isLocal) throw BookInfoLibraryException(BookInfoLibraryError.Changed)
        val file = originalLibraryFile(book)
        if (file != null && file.exists()) { file.delete(); check(!file.exists()) { "Unable to delete the local book file" } }
    }
    open suspend fun cleanup(command: BookInfoDeleteCommand, book: Book) {
        if (book.isLocal) {
            LocalConfig.deleteBookOriginal = command.deleteOriginal
            LocalBook.deleteBook(book, false)
        }
    }
    open suspend fun callback(book: Book) {
        val source = if (book.isLocal) null else database.bookSourceDao.getBookSource(book.origin)
        SourceCallBack.callBackBookAwait(SourceCallBack.DEL_BOOK_SHELF, source, book)
    }
}

@Singleton
class BookInfoDeleteRepository @Inject constructor(private val database: AppDatabase, private val effects: BookInfoDeleteEffects,
    private val authority: BookInfoDeleteAuthority) {
    constructor(database: AppDatabase) : this(database, BookInfoDeleteEffects(database), BookInfoDeleteAuthority(splitties.init.appCtx))
    constructor(database: AppDatabase, effects: BookInfoDeleteEffects) : this(database, effects, BookInfoDeleteAuthority(splitties.init.appCtx))
    private val dao get() = database.bookInfoDeleteDao
    fun observe(id: String) = dao.observe(id)
    fun observeBook(bookUrl: String) = dao.observeBook(bookUrl).map { command ->
        command?.takeIf { it.installationId == authority.id() }?.let {
            BookInfoPendingDelete(it.id, it.bookUrl, it.status, it.phase < BookInfoDeletePhase.DatabaseRemoved)
        }
    }.flowOn(IO)
    suspend fun get(id: String) = withContext(IO) { dao.get(id) }
    suspend fun pending() = withContext(IO) {
        val installation = authority.id()
        database.runInTransaction<List<BookInfoDeleteCommand>> {
            dao.minimizeTerminal()
            dao.pruneTerminal(now() - 7L * 24 * 60 * 60 * 1000)
            dao.pending().filter { command ->
                if (command.installationId == installation) command.status in setOf(BookInfoDeleteStatus.Queued, BookInfoDeleteStatus.Running) else {
                    check(dao.update(command.copy(status = BookInfoDeleteStatus.Cancelled, executionToken = "", snapshot = "", target = null,
                        error = BookInfoLibraryError.Interrupted.name, updatedAt = now())) == 1)
                    dao.release(command.id)
                    false
                }
            }
        }
    }
    private fun now() = System.currentTimeMillis()
    private fun snapshot(book: Book): String = GSON.toJson(book.copy(variable = GSON.toJson(book.variableMap)).apply {
        infoHtml = null; tocHtml = null; downloadUrls = null
    })
    private fun book(command: BookInfoDeleteCommand): Book = GSON.fromJson(command.snapshot, Book::class.java)
    private fun sameIdentity(current: Book, selected: Book): Boolean = current.bookUrl == selected.bookUrl &&
        current.name == selected.name && current.author == selected.author && current.origin == selected.origin &&
        current.originName == selected.originName && current.isLocal == selected.isLocal &&
        (!current.isLocal || current.getLocalUri() == selected.getLocalUri())
    private fun owner(owner: BookInfoRequestOwner) {
        val parent = database.bookInfoRequestDao.get(owner.id)
        if (parent == null || parent.ownerToken != owner.token || parent.revision != owner.revision ||
            parent.bookUrl != owner.bookUrl || parent.deleted || database.bookInfoRequestDao.activeChild(owner.id) != null ||
            !database.readerDetailDao.producerOwns(parent))
            throw BookInfoLibraryException(BookInfoLibraryError.Changed)
    }
    private fun instance(command: BookInfoDeleteCommand): Book {
        val current = database.bookDao.getBook(command.bookUrl)
        if (dao.lease(command.bookUrl)?.commandId != command.id || current == null || !sameIdentity(current, book(command)))
            throw BookInfoLibraryException(BookInfoLibraryError.Changed)
        return current
    }

    suspend fun accept(owner: BookInfoRequestOwner, id: String, selected: Book, original: Boolean,
        remote: Boolean, target: String?): BookInfoDeleteCommand = withContext(IO) {
        require(id.isNotBlank() && id.length <= 128 && selected.bookUrl == owner.bookUrl)
        database.runInTransaction<BookInfoDeleteCommand> {
            val installation = authority.id()
            val existing = dao.get(id)
            if (existing != null && existing.installationId != installation) throw BookInfoLibraryException(BookInfoLibraryError.Changed)
            if (existing?.status == BookInfoDeleteStatus.Completed && existing.requestId == owner.id && existing.bookUrl == owner.bookUrl)
                return@runInTransaction existing
            owner(owner)
            val current = database.bookDao.getBook(owner.bookUrl) ?: throw BookInfoLibraryException(BookInfoLibraryError.Changed)
            if (!sameIdentity(current, selected)) throw BookInfoLibraryException(BookInfoLibraryError.Changed)
            if ((original || remote) && !current.isLocal || remote && target == null)
                throw BookInfoLibraryException(BookInfoLibraryError.Changed)
            if (existing != null) {
                if (existing.requestId != owner.id || existing.bookUrl != owner.bookUrl || existing.status == BookInfoDeleteStatus.Cancelled)
                    throw BookInfoLibraryException(BookInfoLibraryError.Changed)
                instance(existing)
                if (remote && existing.target != target) throw BookInfoLibraryException(BookInfoLibraryError.Changed)
                if (existing.status == BookInfoDeleteStatus.Failed) {
                    val changed = existing.deleteOriginal != original || existing.deleteRemote != remote
                    existing.copy(status = BookInfoDeleteStatus.Queued, executionToken = "", error = null,
                        phase = if (changed) BookInfoDeletePhase.Accepted else existing.phase,
                        deleteOriginal = original, deleteRemote = remote, updatedAt = now()).also { check(dao.update(it) == 1) }
                } else {
                    if (existing.deleteOriginal != original || existing.deleteRemote != remote || remote && existing.target != target)
                        throw BookInfoLibraryException(BookInfoLibraryError.Changed)
                    existing
                }
            } else {
                dao.lease(owner.bookUrl)?.let { lease ->
                    val prior = dao.get(lease.commandId)
                    if (prior != null && prior.status != BookInfoDeleteStatus.Failed && prior.installationId == installation)
                        throw BookInfoLibraryException(BookInfoLibraryError.Changed)
                    if (prior != null) check(dao.update(prior.copy(status = BookInfoDeleteStatus.Cancelled,
                        executionToken = "", snapshot = "", target = null, updatedAt = now())) == 1)
                    dao.release(lease.commandId)
                }
                BookInfoDeleteCommand(id, owner.id, owner.bookUrl, snapshot(current), target, original, remote,
                    updatedAt = now(), installationId = installation).also { dao.insert(it); dao.insertLease(BookInfoDeletionLease(owner.bookUrl, id)) }
            }
        }
    }

    suspend fun cancel(owner: BookInfoRequestOwner, id: String): Boolean = withContext(IO) {
        database.runInTransaction<Boolean> {
            owner(owner)
            val command = dao.get(id)?.takeIf { it.bookUrl == owner.bookUrl && it.installationId == authority.id() &&
                it.status in setOf(BookInfoDeleteStatus.Queued, BookInfoDeleteStatus.Running, BookInfoDeleteStatus.Failed) &&
                it.phase < BookInfoDeletePhase.DatabaseRemoved } ?: return@runInTransaction false
            // Cancellation revokes effects; metadata edits after failure must not prevent it.
            // The row-bound lease still rejects a replacement book or a successor command.
            if (dao.lease(owner.bookUrl)?.commandId != id) return@runInTransaction false
            check(dao.update(command.copy(status = BookInfoDeleteStatus.Cancelled, executionToken = "", snapshot = "", target = null, updatedAt = now())) == 1)
            dao.release(id)
            true
        }
    }

    private fun execution(id: String, token: String): BookInfoDeleteCommand {
        return dao.get(id)?.takeIf { it.status == BookInfoDeleteStatus.Running && it.executionToken == token }
            ?: throw BookInfoLibraryException(BookInfoLibraryError.Changed)
    }
    private fun advance(id: String, token: String, phase: BookInfoDeletePhase) = database.runInTransaction<BookInfoDeleteCommand> {
        val command = execution(id, token)
        instance(command)
        command.copy(phase = phase, updatedAt = now()).also { check(dao.update(it) == 1) }
    }

    /** A fresh executor token fences late callbacks from a cancelled/replaced worker. */
    suspend fun execute(id: String, token: String = UUID.randomUUID().toString(),
        retryRemoteFailure: Boolean = false): BookInfoDeleteCommand? = withContext(IO) {
        try {
            var command = database.runInTransaction<BookInfoDeleteCommand?> {
                val current = dao.get(id) ?: return@runInTransaction null
                if (current.status in setOf(BookInfoDeleteStatus.Completed, BookInfoDeleteStatus.Cancelled)) {
                    return@runInTransaction current.copy(snapshot = "", target = null).also { check(dao.update(it) == 1) }
                }
                if (current.installationId != authority.id()) {
                    val cancelled = current.copy(status = BookInfoDeleteStatus.Cancelled, executionToken = "", snapshot = "", target = null,
                        error = BookInfoLibraryError.Interrupted.name, updatedAt = now())
                    check(dao.update(cancelled) == 1); dao.release(id)
                    return@runInTransaction cancelled
                }
                if (current.status !in setOf(BookInfoDeleteStatus.Queued, BookInfoDeleteStatus.Running)) return@runInTransaction current
                current.copy(status = BookInfoDeleteStatus.Running, executionToken = token, error = null,
                    updatedAt = now()).also { check(dao.update(it) == 1) }
            } ?: return@withContext null
            if (command.status != BookInfoDeleteStatus.Running || command.executionToken != token) return@withContext command
            if (command.phase < BookInfoDeletePhase.DatabaseRemoved) database.runInTransaction { instance(command) }
            if (command.phase == BookInfoDeletePhase.Accepted) {
                effects.removeRemote(command, book(command))
                command = advance(id, token, BookInfoDeletePhase.RemoteRemoved)
            }
            if (command.phase == BookInfoDeletePhase.RemoteRemoved) {
                effects.removeOriginal(command, book(command))
                command = advance(id, token, BookInfoDeletePhase.OriginalRemoved)
            }
            if (command.phase == BookInfoDeletePhase.OriginalRemoved) {
                effects.cleanup(command, book(command))
                command = database.runInTransaction<BookInfoDeleteCommand> {
                    val running = execution(id, token)
                    val current = instance(running)
                    current.saveReadRecordSnapshot(database)
                    database.bookDao.delete(current)
                    dao.publishDeleted(current.bookUrl, now())
                    database.readerDetailDao.publishDeleted(current.bookUrl, now())
                    running.copy(snapshot = snapshot(current), phase = BookInfoDeletePhase.DatabaseRemoved,
                        updatedAt = now()).also { check(dao.update(it) == 1) }
                }
            }
            if (command.phase == BookInfoDeletePhase.DatabaseRemoved) {
                val callback = database.runInTransaction<Boolean> {
                    val running = execution(id, token)
                    if (running.callbackClaimed) false else {
                        check(dao.update(running.copy(callbackClaimed = true, updatedAt = now())) == 1)
                        database.bookDao.getBook(running.bookUrl) == null
                    }
                }
                if (callback) {
                    try { effects.callback(book(command)) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { AppLog.put("书源移出书架回调失败", error) }
                }
                command = database.runInTransaction<BookInfoDeleteCommand> {
                    execution(id, token).copy(status = BookInfoDeleteStatus.Completed, phase = BookInfoDeletePhase.Completed,
                        snapshot = "", target = null, updatedAt = now()).also { check(dao.update(it) == 1) }
                }
            }
            command
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            database.runInTransaction<BookInfoDeleteCommand?> {
                val current = dao.get(id) ?: return@runInTransaction null
                // Validation can fail before the execution token was installed.
                if (current.status !in setOf(BookInfoDeleteStatus.Queued, BookInfoDeleteStatus.Running) ||
                    current.executionToken.isNotEmpty() && current.executionToken != token) return@runInTransaction current
                val reason = (error as? BookInfoLibraryException)?.reason ?: BookInfoLibraryError.Storage
                val retry = retryRemoteFailure && reason == BookInfoLibraryError.RemoteDelete
                current.copy(status = if (retry) BookInfoDeleteStatus.Queued else BookInfoDeleteStatus.Failed,
                    executionToken = if (retry) "" else current.executionToken, error = reason.name,
                    updatedAt = now()).also { check(dao.update(it) == 1) }
            }
        }
    }
}
