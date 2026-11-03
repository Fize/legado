package io.legado.app.data.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.legado.app.data.entities.BookInfoDeleteStatus
import io.legado.app.work.BookInfoDeleteScheduler
import kotlinx.coroutines.flow.first
import splitties.init.appCtx
import android.net.Uri
import io.legado.app.exception.NoStackTraceException
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.isUri
import java.io.File
import java.nio.file.Files

import io.legado.app.constant.AppLog
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.saveReadRecordSnapshot
import io.legado.app.help.AppWebDav
import io.legado.app.help.book.getLocalUri
import io.legado.app.help.book.isLocal
import io.legado.app.help.config.LocalConfig
import io.legado.app.lib.webdav.WebDavException
import io.legado.app.lib.webdav.isWebDavOverwriteConflict
import io.legado.app.model.AudioPlay
import io.legado.app.model.ReadBook
import io.legado.app.model.SourceCallBack
import io.legado.app.model.localBook.LocalBook
import io.legado.app.model.remote.RemoteBookWebDav
import io.legado.app.utils.FileDoc
import io.legado.app.utils.delete
import io.legado.app.utils.exists
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

data class BookInfoLibraryOptions(val local: Boolean, val target: String?, val confirmDelete: Boolean, val deleteOriginal: Boolean)
enum class BookInfoLibraryError { NotConfigured, Changed, Conflict, RemoteDelete, RemoteUpload, Interrupted, Storage }
class BookInfoLibraryException(val reason: BookInfoLibraryError) : IllegalStateException(reason.name)

internal fun libraryTargetKey(client: RemoteBookWebDav): String = MessageDigest.getInstance("SHA-256")
    .digest("${client.rootBookUrl}\u0000${client.authorization.data}\u0000${client.serverID}".toByteArray())
    .joinToString("") { "%02x".format(it) }
internal fun requireLibraryTarget(expected: String): RemoteBookWebDav {
    val client = AppWebDav.defaultBookWebDav ?: throw BookInfoLibraryException(BookInfoLibraryError.NotConfigured)
    if (libraryTargetKey(client) != expected) throw BookInfoLibraryException(BookInfoLibraryError.Changed)
    return client
}
internal fun originalLibraryFile(book: Book): FileDoc? = try { FileDoc.fromUri(book.getLocalUri(), false) }
catch (missing: NoStackTraceException) {
    val uri = if (book.bookUrl.isUri()) Uri.parse(book.bookUrl) else Uri.fromFile(File(book.bookUrl))
    if (!uri.isContentScheme() && uri.path?.let { Files.notExists(File(it).toPath()) } == true) null else throw missing
}

@Singleton
class BookInfoLibraryRepository @Inject constructor(private val database: AppDatabase,
    private val deletions: BookInfoDeleteRepository, private val uploads: BookInfoUploadRepository,
    @ApplicationContext private val context: Context) {
    constructor(database: AppDatabase) : this(database, BookInfoDeleteRepository(database), BookInfoUploadRepository(database), appCtx)
    private fun book(key: String): Book = database.bookDao.getBook(key) ?: throw BookInfoLibraryException(BookInfoLibraryError.Changed)
    fun observeDelete(key: String) = deletions.observeBook(key)
    fun observeUpload(key: String) = uploads.observeBook(key)
    fun observeUploadCommand(id: String) = uploads.observe(id)
    suspend fun cancelUpload(owner: BookInfoRequestOwner, id: String): Boolean {
        val cancelled = uploads.cancel(owner, id)
        if (cancelled) io.legado.app.work.BookInfoUploadScheduler.cancel(context, id)
        return cancelled
    }
    suspend fun inspect(key: String): BookInfoLibraryOptions = withContext(IO) {
        val book = book(key)
        val client = if (book.isLocal) AppWebDav.defaultBookWebDav else null
        BookInfoLibraryOptions(book.isLocal, client?.let(::libraryTargetKey), LocalConfig.bookInfoDeleteAlert, LocalConfig.deleteBookOriginal)
    }
    suspend fun remoteExists(key: String, expected: String): Boolean = withContext(IO) {
        val book = book(key)
        if (!book.isLocal) throw BookInfoLibraryException(BookInfoLibraryError.Changed)
        requireLibraryTarget(expected).hasRemoteBook(book)
    }
    private fun requireOwner(owner: BookInfoRequestOwner, key: String) {
        val parent = database.bookInfoRequestDao.get(owner.id)
        if (parent == null || parent.ownerToken != owner.token || parent.revision != owner.revision ||
            parent.bookUrl != key || owner.bookUrl != key || parent.deleted ||
            database.bookInfoRequestDao.activeChild(owner.id) != null || database.bookInfoDeleteDao.blocksBook(key))
            throw BookInfoLibraryException(BookInfoLibraryError.Changed)
    }
    suspend fun upload(key: String, expected: String, overwrite: Boolean,
        owner: BookInfoRequestOwner? = null, selected: Book? = null, commandId: String? = null): Book = withContext(IO) {
        if (owner != null) {
            val id = commandId ?: java.util.UUID.randomUUID().toString()
            val previous = uploads.get(id)
            val command = uploads.accept(owner, id, checkNotNull(selected), expected, overwrite)
            if (command.status != io.legado.app.data.entities.BookInfoUploadStatus.Completed)
                io.legado.app.work.BookInfoUploadScheduler.enqueue(context, id,
                    replace = previous?.status == io.legado.app.data.entities.BookInfoUploadStatus.Failed)
            val result = uploads.observe(id).first { it?.status in setOf(io.legado.app.data.entities.BookInfoUploadStatus.Completed,
                io.legado.app.data.entities.BookInfoUploadStatus.Failed, io.legado.app.data.entities.BookInfoUploadStatus.Cancelled) }
            if (result?.status != io.legado.app.data.entities.BookInfoUploadStatus.Completed) throw BookInfoLibraryException(
                BookInfoLibraryError.entries.find { it.name == result?.error } ?: BookInfoLibraryError.Interrupted)
            return@withContext book(key)
        }
        val snapshot = database.runInTransaction<Book> {
            if (owner != null) requireOwner(owner, key)
            val current = book(key)
            if (owner != null && (selected == null || current.origin != selected.origin || current.originName != selected.originName ||
                    current.name != selected.name || current.author != selected.author)) throw BookInfoLibraryException(BookInfoLibraryError.Changed)
            current
        }
        if (!snapshot.isLocal) throw BookInfoLibraryException(BookInfoLibraryError.Changed)
        val origin = try { requireLibraryTarget(expected).uploadFile(snapshot, overwrite) }
        catch (error: WebDavException) {
            if (!overwrite && isWebDavOverwriteConflict(error.responseCode)) throw BookInfoLibraryException(BookInfoLibraryError.Conflict)
            throw error
        }
        val saved = database.runInTransaction<Book> {
            if (owner != null) requireOwner(owner, key)
            val current = book(key)
            if (!current.isLocal || current.originName != snapshot.originName || owner != null &&
                (current.origin != snapshot.origin || current.name != snapshot.name || current.author != snapshot.author)) throw BookInfoLibraryException(BookInfoLibraryError.Changed)
            current.origin = origin
            current.lastCheckTime = System.currentTimeMillis()
            database.bookDao.update(current)
            current
        }
        if (owner == null) ReadBook.book?.takeIf { it.bookUrl == key }?.let { it.origin = saved.origin; it.lastCheckTime = saved.lastCheckTime }
        if (owner == null) AudioPlay.book?.takeIf { it.bookUrl == key }?.let { it.origin = saved.origin; it.lastCheckTime = saved.lastCheckTime }
        saved
    }
    suspend fun remove(key: String, deleteOriginal: Boolean, deleteRemote: Boolean, expected: String?,
        owner: BookInfoRequestOwner? = null, commandId: String? = null, selected: Book? = null) = withContext(IO) {
        if (owner != null) {
            val id = checkNotNull(commandId)
            val previous = deletions.get(id)
            val command = deletions.accept(owner, id, checkNotNull(selected), deleteOriginal, deleteRemote, expected)
            if (command.status != BookInfoDeleteStatus.Completed) BookInfoDeleteScheduler.enqueue(context, id,
                replace = previous?.status == BookInfoDeleteStatus.Failed, remote = command.deleteRemote)
            val result = deletions.observe(id).first { it?.status in setOf(BookInfoDeleteStatus.Completed,
                BookInfoDeleteStatus.Failed, BookInfoDeleteStatus.Cancelled) }
            if (result?.status != BookInfoDeleteStatus.Completed) throw BookInfoLibraryException(
                BookInfoLibraryError.entries.find { it.name == result?.error } ?: BookInfoLibraryError.Interrupted)
            return@withContext
        }
        val snapshot = database.bookDao.getBook(key) ?: return@withContext
        if (deleteRemote) {
            if (!snapshot.isLocal || expected == null) throw BookInfoLibraryException(BookInfoLibraryError.Changed)
            if (!requireLibraryTarget(expected).delete(snapshot)) throw BookInfoLibraryException(BookInfoLibraryError.RemoteDelete)
        }
        if (snapshot.isLocal && deleteOriginal) {
            val file = originalLibraryFile(snapshot)
            if (file != null && file.exists()) { file.delete(); check(!file.exists()) { "Unable to delete the local book file." } }
        }
        snapshot.saveReadRecordSnapshot(database)
        val removed = database.runInTransaction<Boolean> {
            val current = database.bookDao.getBook(key) ?: return@runInTransaction false
            database.bookDao.delete(current)
            true
        }
        if (snapshot.isLocal) {
            LocalConfig.deleteBookOriginal = deleteOriginal
            LocalBook.deleteBook(snapshot, false)
        }
        if (ReadBook.book?.bookUrl == key) ReadBook.book = null
        if (removed) {
            try {
                val source = if (snapshot.isLocal) null else database.bookSourceDao.getBookSource(snapshot.origin)
                SourceCallBack.callBackBook(SourceCallBack.DEL_BOOK_SHELF, source, snapshot)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { AppLog.put("书源移出书架回调失败", error) }
        }
    }
    suspend fun cancelDelete(owner: BookInfoRequestOwner, id: String): Boolean {
        val cancelled = deletions.cancel(owner, id)
        if (cancelled) BookInfoDeleteScheduler.cancel(context, id)
        return cancelled
    }

}
