package io.legado.app.data.repository

import io.legado.app.constant.AppLog
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.*
import io.legado.app.help.book.*
import io.legado.app.lib.webdav.WebDavContentMatch
import io.legado.app.lib.webdav.WebDavException
import io.legado.app.lib.webdav.isWebDavOverwriteConflict
import io.legado.app.model.remote.remoteBookUploadFileName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class BookInfoPendingUpload(val id: String, val bookUrl: String, val status: BookInfoUploadStatus)

@Singleton
open class BookInfoUploadEffects @Inject constructor() {
    open suspend fun transfer(command: BookInfoUploadCommand, file: File, recovering: Boolean): String {
        val client = requireLibraryTarget(command.target)
        try {
            var overwrite = command.overwrite
            if (recovering) when (client.inspectSnapshot(command.fileName, command.sha256, command.size)) {
                WebDavContentMatch.Matches -> return client.snapshotOrigin(command.fileName)
                WebDavContentMatch.Missing -> overwrite = false
                // A previous request may have succeeded. Never overwrite another writer on replay.
                WebDavContentMatch.Different -> throw BookInfoLibraryException(BookInfoLibraryError.Conflict)
            }
            return try { client.uploadSnapshot(command.fileName, file, overwrite) }
            catch (error: WebDavException) {
                if (!isWebDavOverwriteConflict(error.responseCode)) throw error
                if (client.inspectSnapshot(command.fileName, command.sha256, command.size) == WebDavContentMatch.Matches)
                    client.snapshotOrigin(command.fileName)
                else throw BookInfoLibraryException(BookInfoLibraryError.Conflict)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: BookInfoLibraryException) { throw error }
        catch (_: Exception) { throw BookInfoLibraryException(BookInfoLibraryError.RemoteUpload) }
    }
}

/** Room is the upload outbox; WorkManager is only its executor. */
@Singleton
class BookInfoUploadRepository @Inject constructor(
    private val database: AppDatabase,
    private val payloads: BookInfoUploadPayloadStore,
    private val effects: BookInfoUploadEffects,
    private val authority: BookInfoDeleteAuthority,
) {
    constructor(database: AppDatabase) : this(database, BookInfoUploadPayloadStore(splitties.init.appCtx),
        BookInfoUploadEffects(), BookInfoDeleteAuthority(splitties.init.appCtx))
    private val dao get() = database.bookInfoUploadDao
    private fun now() = System.currentTimeMillis()
    suspend fun get(id: String) = withContext(IO) { dao.get(id) }
    fun observe(id: String) = dao.observe(id)
    fun observeBook(bookUrl: String) = dao.observeBook(bookUrl).map { command ->
        command?.takeIf { it.installationId == authority.id() }?.let { BookInfoPendingUpload(it.id, it.bookUrl, it.status) }
    }.flowOn(IO)
    private fun source(book: Book) = if (book.isArchive) checkNotNull(book.getArchiveUri()) else book.getLocalUri()
    private fun owner(owner: BookInfoRequestOwner) {
        val parent = database.bookInfoRequestDao.get(owner.id)
        if (parent == null || parent.ownerToken != owner.token || parent.revision != owner.revision ||
            parent.bookUrl != owner.bookUrl || parent.deleted || database.bookInfoRequestDao.activeChild(owner.id) != null ||
            !database.readerDetailDao.producerOwns(parent))
            throw BookInfoLibraryException(BookInfoLibraryError.Changed)
    }
    private fun selected(owner: BookInfoRequestOwner, selected: Book): Book {
        owner(owner)
        if (database.bookInfoDeleteDao.blocksBook(owner.bookUrl)) throw BookInfoLibraryException(BookInfoLibraryError.Changed)
        val current = database.bookDao.getBook(owner.bookUrl)
            ?: throw BookInfoLibraryException(BookInfoLibraryError.Changed)
        if (!current.isLocal || current.bookUrl != selected.bookUrl || current.name != selected.name ||
            current.author != selected.author || current.origin != selected.origin || current.originName != selected.originName ||
            source(current) != source(selected)) throw BookInfoLibraryException(BookInfoLibraryError.Changed)
        return current
    }
    private fun instance(command: BookInfoUploadCommand): Book {
        val current = database.bookDao.getBook(command.bookUrl)
        if (dao.lease(command.bookUrl)?.commandId != command.id || current == null || !current.isLocal ||
            current.originName != command.originName ||
            current.origin != command.originalOrigin && current.origin != command.resultOrigin)
            throw BookInfoLibraryException(BookInfoLibraryError.Changed)
        return current
    }
    private fun scrub(command: BookInfoUploadCommand, status: BookInfoUploadStatus, error: String? = command.error) =
        command.copy(sourceUri = "", originalOrigin = "", originName = "", fileName = "", sha256 = "", size = 0,
            target = "", resultOrigin = null, status = status, error = error, updatedAt = now())
    private fun removePayload(key: String) {
        runCatching { payloads.remove(key) }.onFailure { AppLog.put("清理上传快照失败", it) }
    }

    suspend fun accept(owner: BookInfoRequestOwner, id: String, book: Book, target: String,
        overwrite: Boolean): BookInfoUploadCommand = withContext(IO) {
        require(id.isNotBlank() && id.length <= 128 && target.isNotBlank())
        val installation = authority.id()
        var reused: BookInfoUploadCommand? = null
        val captured = database.runInTransaction<Book> {
            val existing = dao.get(id)
            if (existing?.status == BookInfoUploadStatus.Completed && existing.installationId == installation &&
                existing.requestId == owner.id && existing.bookUrl == owner.bookUrl) {
                reused = existing
                return@runInTransaction book
            }
            val current = selected(owner, book)
            if (existing != null) {
                if (existing.installationId != installation || existing.requestId != owner.id ||
                    existing.bookUrl != owner.bookUrl || existing.target != target || existing.status == BookInfoUploadStatus.Cancelled)
                    throw BookInfoLibraryException(BookInfoLibraryError.Changed)
                instance(existing)
                reused = if (existing.status == BookInfoUploadStatus.Failed) {
                    // Retry/overwrite is a fresh explicit confirmation; immutable bytes stay the same.
                    existing.copy(status = BookInfoUploadStatus.Queued, overwrite = overwrite, executionToken = "", error = null,
                        phase = if (existing.phase == BookInfoUploadPhase.Transferred) existing.phase else BookInfoUploadPhase.Prepared,
                        updatedAt = now()).also { check(dao.update(it) == 1) }
                } else {
                    if (existing.overwrite != overwrite) throw BookInfoLibraryException(BookInfoLibraryError.Changed)
                    existing
                }
            }
            current
        }
        reused?.let { return@withContext it }
        val uri = source(captured)
        val name = remoteBookUploadFileName(captured)
        require(name.isNotBlank() && '/' !in name && '\\' !in name && name !in setOf(".", ".."))
        val payload = payloads.capture(uri)
        var retired: String? = null
        try {
            val command = database.runInTransaction<BookInfoUploadCommand> {
                val current = selected(owner, book)
                if (authority.id() != installation || source(current) != uri || current.origin != captured.origin ||
                    current.originName != captured.originName || dao.get(id) != null) throw BookInfoLibraryException(BookInfoLibraryError.Changed)
                dao.lease(owner.bookUrl)?.let { lease ->
                    val prior = checkNotNull(dao.get(lease.commandId))
                    if (prior.installationId == installation && prior.status != BookInfoUploadStatus.Failed)
                        throw BookInfoLibraryException(BookInfoLibraryError.Changed)
                    check(dao.update(scrub(prior, BookInfoUploadStatus.Cancelled)) == 1)
                    dao.release(prior.id); retired = prior.payloadKey
                }
                BookInfoUploadCommand(id, owner.id, owner.bookUrl, uri.toString(), current.origin, current.originName,
                    name, payload.key, payload.sha256, payload.size, target, overwrite, installation, updatedAt = now())
                    .also { dao.insert(it); dao.insertLease(BookInfoUploadLease(owner.bookUrl, id)) }
            }
            retired?.let(::removePayload)
            command
        } catch (error: Throwable) { removePayload(payload.key); throw error }
        finally { payloads.releasePreparation(payload.key) }
    }

    suspend fun cancel(owner: BookInfoRequestOwner, id: String): Boolean = withContext(IO) {
        var payload: String? = null
        val cancelled = database.runInTransaction<Boolean> {
            owner(owner)
            val command = dao.get(id)?.takeIf { it.bookUrl == owner.bookUrl && it.installationId == authority.id() &&
                it.status in setOf(BookInfoUploadStatus.Queued, BookInfoUploadStatus.Running, BookInfoUploadStatus.Failed) }
                ?: return@runInTransaction false
            if (dao.lease(owner.bookUrl)?.commandId != id) return@runInTransaction false
            check(dao.update(scrub(command, BookInfoUploadStatus.Cancelled).copy(executionToken = "")) == 1)
            dao.release(id); payload = command.payloadKey
            true
        }
        payload?.let(::removePayload)
        cancelled
    }

    suspend fun pending(): List<BookInfoUploadCommand> = withContext(IO) {
        val installation = authority.id()
        val retired = mutableListOf<String>()
        val pending = database.runInTransaction<List<BookInfoUploadCommand>> {
            dao.terminal().forEach { command ->
                retired += command.payloadKey
                if (command.target.isNotEmpty() || command.sourceUri.isNotEmpty() || command.resultOrigin != null) {
                    check(dao.update(scrub(command, command.status).copy(updatedAt = command.updatedAt)) == 1)
                }
                dao.release(command.id)
            }
            dao.pruneTerminal(now() - 7L * 24 * 60 * 60 * 1000)
            dao.pending().filter { command ->
                val authorized = command.installationId == installation
                val ownsRow = dao.lease(command.bookUrl)?.commandId == command.id
                if (authorized && ownsRow) command.status in setOf(BookInfoUploadStatus.Queued, BookInfoUploadStatus.Running)
                else {
                    check(dao.update(scrub(command, BookInfoUploadStatus.Cancelled,
                        if (authorized) BookInfoLibraryError.Changed.name else BookInfoLibraryError.Interrupted.name).copy(executionToken = "")) == 1)
                    dao.release(command.id); retired += command.payloadKey; false
                }
            }
        }
        retired.forEach(::removePayload)
        val keep = database.runInTransaction<List<BookInfoUploadCommand>> { dao.pending() }.mapTo(mutableSetOf()) { it.payloadKey }
        payloads.pruneOrphans(keep, now() - 24L * 60 * 60 * 1000)
        pending
    }
    private fun execution(id: String, token: String) = dao.get(id)?.takeIf {
        it.status == BookInfoUploadStatus.Running && it.executionToken == token
    } ?: throw BookInfoLibraryException(BookInfoLibraryError.Changed)

    suspend fun execute(id: String, token: String = UUID.randomUUID().toString(), retryNetworkFailure: Boolean = false): BookInfoUploadCommand? = withContext(IO) {
        try {
            var command = database.runInTransaction<BookInfoUploadCommand?> {
                val current = dao.get(id) ?: return@runInTransaction null
                if (current.status !in setOf(BookInfoUploadStatus.Queued, BookInfoUploadStatus.Running)) return@runInTransaction current
                val authorized = current.installationId == authority.id()
                if (!authorized || dao.lease(current.bookUrl)?.commandId != current.id) {
                    return@runInTransaction scrub(current, BookInfoUploadStatus.Cancelled,
                        if (authorized) BookInfoLibraryError.Changed.name else BookInfoLibraryError.Interrupted.name)
                        .also { check(dao.update(it) == 1); dao.release(id) }
                }
                current.copy(status = BookInfoUploadStatus.Running, executionToken = token, error = null, updatedAt = now())
                    .also { check(dao.update(it) == 1) }
            } ?: return@withContext null
            if (command.status in setOf(BookInfoUploadStatus.Completed, BookInfoUploadStatus.Cancelled)) {
                removePayload(command.payloadKey); return@withContext command
            }
            if (command.status != BookInfoUploadStatus.Running || command.executionToken != token) return@withContext command
            database.runInTransaction { instance(command) }
            if (command.phase < BookInfoUploadPhase.Transferred) {
                val file = payloads.verified(command.payloadKey, command.sha256, command.size)
                val recovering = command.phase == BookInfoUploadPhase.Transferring
                command = database.runInTransaction<BookInfoUploadCommand> {
                    val running = execution(id, token); instance(running)
                    running.copy(phase = BookInfoUploadPhase.Transferring, updatedAt = now()).also { check(dao.update(it) == 1) }
                }
                val origin = effects.transfer(command, file, recovering)
                command = database.runInTransaction<BookInfoUploadCommand> {
                    val running = execution(id, token); instance(running)
                    running.copy(phase = BookInfoUploadPhase.Transferred, resultOrigin = origin, updatedAt = now())
                        .also { check(dao.update(it) == 1) }
                }
            }
            if (command.phase == BookInfoUploadPhase.Transferred) {
                command = database.runInTransaction<BookInfoUploadCommand> {
                    val running = execution(id, token)
                    val current = instance(running)
                    current.origin = checkNotNull(running.resultOrigin)
                    current.lastCheckTime = now()
                    database.bookDao.update(current)
                    scrub(running, BookInfoUploadStatus.Completed, null).copy(phase = BookInfoUploadPhase.Completed)
                        .also { check(dao.update(it) == 1); dao.release(id) }
                }
                removePayload(command.payloadKey)
            }
            command
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            val outcome = database.runInTransaction<BookInfoUploadCommand?> {
                val current = dao.get(id) ?: return@runInTransaction null
                if (current.status !in setOf(BookInfoUploadStatus.Queued, BookInfoUploadStatus.Running) ||
                    current.executionToken.isNotEmpty() && current.executionToken != token) return@runInTransaction current
                val reason = (error as? BookInfoLibraryException)?.reason ?: BookInfoLibraryError.Storage
                if (dao.lease(current.bookUrl)?.commandId != current.id) {
                    return@runInTransaction scrub(current, BookInfoUploadStatus.Cancelled, BookInfoLibraryError.Changed.name)
                        .also { check(dao.update(it) == 1); dao.release(id) }
                }
                val retry = retryNetworkFailure && reason == BookInfoLibraryError.RemoteUpload
                current.copy(status = if (retry) BookInfoUploadStatus.Queued else BookInfoUploadStatus.Failed,
                    executionToken = if (retry) "" else current.executionToken, error = reason.name, updatedAt = now())
                    .also { check(dao.update(it) == 1) }
            }
            if (outcome?.status == BookInfoUploadStatus.Cancelled) removePayload(outcome.payloadKey)
            outcome
        }
    }
}
