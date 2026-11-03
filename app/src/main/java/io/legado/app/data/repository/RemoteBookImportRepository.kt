package io.legado.app.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import io.legado.app.R
import io.legado.app.constant.AppConst.DEFAULT_WEBDAV_ID
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern.archiveFileRegex
import io.legado.app.constant.AppPattern.bookFileRegex
import io.legado.app.constant.BookType
import io.legado.app.constant.PreferKey
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.Server
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.webdav.Authorization
import io.legado.app.lib.webdav.WebDav
import io.legado.app.model.analyzeRule.CustomUrl
import io.legado.app.model.localBook.LocalBook
import io.legado.app.utils.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import javax.inject.Inject

@Serializable
data class RemoteImportDirectory(val path: String, val name: String)

data class RemoteImportEntry(
    val path: String,
    val filename: String,
    val isDir: Boolean,
    val size: Long,
    val lastModify: Long,
    val isOnBookShelf: Boolean,
) {
    val contentType get() = filename.substringAfterLast('.')
}

class RemoteImportConnection(
    val root: String,
    val serverId: Long?,
    val authorization: Authorization,
) {
    val isDefault get() = serverId == null
}

data class RemoteImportBatchResult(val completePaths: Set<String>, val message: String, val storageDenied: Boolean)
data class RemoteImportServer(val id: Long, val name: String)

@Serializable
data class RemoteServerDraft(
    val id: Long? = null,
    val name: String = "",
    val url: String = "",
    val username: String = "",
    val password: String = "",
) {
    override fun toString() = "RemoteServerDraft(id=$id, name=$name, credentials=redacted)"
}

class RemoteBookImportRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: AppDatabase,
    private val local: LocalBookImportRepository,
) {
    private val preferences get() = context.defaultSharedPreferences
    private val configKeys = setOf(PreferKey.remoteServerId, PreferKey.webDavUrl, PreferKey.webDavAccount, PreferKey.webDavPassword, PreferKey.webDavDir)
    private fun preferenceChanges() = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> if (key in configKeys) trySend(Unit) }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        trySend(Unit)
        awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    // Only the selected server affects the current directory; editing another profile does not reset it.
    fun configurationChanges() = combine(preferenceChanges(), database.serverDao.observeAll()) { _, servers ->
        val id = AppConfig.remoteServerId
        val server = servers.firstOrNull { it.id == id }
        listOf(id.toString(), server?.config) + if (server == null)
            listOf(PreferKey.webDavUrl, PreferKey.webDavAccount, PreferKey.webDavPassword, PreferKey.webDavDir).map { preferences.getString(it, null) }
        else emptyList()
    }.distinctUntilChanged().map { Unit }.flowOn(Dispatchers.IO)

    suspend fun connection(): RemoteImportConnection = withContext(Dispatchers.IO) {
        val server = database.serverDao.get(AppConfig.remoteServerId)
        val profile = server?.let { requireNotNull(it.getWebDavConfig()) { context.getString(R.string.wrong_format) } }
        if (profile != null) {
            RemoteImportConnection(normalizeUrl(profile.url), server.id, Authorization(profile))
        } else {
            val account = preferences.getString(PreferKey.webDavAccount, null)
            val password = preferences.getString(PreferKey.webDavPassword, null)
            check(!account.isNullOrBlank() && !password.isNullOrBlank()) { context.getString(R.string.webdav_not_configured) }
            val root = normalizeUrl(preferences.getString(PreferKey.webDavUrl, null)?.trim()?.takeIf(String::isNotEmpty)
                ?: "https://dav.jianguoyun.com/dav/").toHttpUrlOrNull()!!.newBuilder()
            AppConfig.webDavDir?.trim()?.trim('/')?.takeIf(String::isNotBlank)?.let { root.addPathSegments("$it/") }
            RemoteImportConnection(root.addPathSegment("books").addPathSegment("").build().toString(), null, Authorization(account, password))
        }
    }

    private fun normalizeUrl(value: String): String {
        val url = requireNotNull(value.trim().toHttpUrlOrNull()) { context.getString(R.string.remote_import_invalid_url) }
        return if (url.encodedPath.endsWith('/')) url.toString() else url.newBuilder().addPathSegment("").build().toString()
    }
    private fun requireOrigin(connection: RemoteImportConnection, path: String): String {
        val root = requireNotNull(connection.root.toHttpUrlOrNull())
        val target = requireNotNull(path.toHttpUrlOrNull())
        require(root.scheme == target.scheme && root.host == target.host && root.port == target.port) {
            context.getString(R.string.remote_import_invalid_url)
        }
        return path
    }
    private fun sameOrigin(connection: RemoteImportConnection, path: String): Boolean =
        runCatching { requireOrigin(connection, path) }.isSuccess

    suspend fun prepare(connection: RemoteImportConnection) = withContext(Dispatchers.IO) {
        WebDav(connection.root, connection.authorization).makeAsDir()
        Unit
    }
    suspend fun list(connection: RemoteImportConnection, directory: String?): List<RemoteImportEntry> = withContext(Dispatchers.IO) {
        val shelf = ImportBookShelfFiles(database.bookDao.localBookFileNames,
            database.bookDao.localBookAlternateOrigins.map { origin ->
                if (origin.startsWith(BookType.webDavTag)) CustomUrl(origin.removePrefix(BookType.webDavTag)).getUrl() else origin
            })
        WebDav(requireOrigin(connection, directory ?: connection.root), connection.authorization).listFiles().mapNotNull { file ->
            if (!sameOrigin(connection, file.path) || (!file.isDir && !file.displayName.matches(bookFileRegex) && !file.displayName.matches(archiveFileRegex))) null
            else RemoteImportEntry(file.path, file.displayName, file.isDir, file.size, file.lastModify,
                !file.isDir && file.displayName in shelf)
        }
    }
    suspend fun importBooks(connection: RemoteImportConnection, entries: List<RemoteImportEntry>): RemoteImportBatchResult = withContext(Dispatchers.IO) {
        val completed = linkedSetOf<String>()
        var products = 0
        var denied = false
        val errors = arrayListOf<Throwable>()
        entries.forEach { entry ->
            currentCoroutineContext().ensureActive()
            try {
                val uri = WebDav(requireOrigin(connection, entry.path), connection.authorization).downloadInputStream().let { LocalBook.saveBookFile(it, entry.filename) }
                val (completeUris, books) = LocalBook.importFiles(listOf(uri))
                books.forEach { book -> saveOrigin(book, connection, entry.path) }
                products += books.size
                if (uri in completeUris) completed.add(entry.path)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                errors.add(error)
                denied = denied || error is SecurityException
                AppLog.put("导入远程书籍失败: ${entry.filename}", error)
            }
        }
        val message = when {
            products == 0 && errors.isNotEmpty() -> errorMessage(errors.first())
            completed.size == entries.size -> context.getString(R.string.nb_file_add_succeed, products)
            else -> context.getString(R.string.local_import_partial, products, entries.size - completed.size)
        }
        RemoteImportBatchResult(completed, message, denied)
    }
    private fun saveOrigin(book: Book, connection: RemoteImportConnection, path: String) {
        book.origin = BookType.webDavTag + CustomUrl(path).putAttribute("serverID", connection.serverId).toString()
        book.save()
    }

    suspend fun existingBook(entry: RemoteImportEntry): Book? = withContext(Dispatchers.IO) { database.bookDao.getBookByFileName(entry.filename) }
    suspend fun downloadedArchive(entry: RemoteImportEntry): LocalImportEntry? = withContext(Dispatchers.IO) {
        val value = AppConfig.defaultBookTreeUri ?: return@withContext null
        val directory = local.resolveDirectory(value) ?: return@withContext null
        FileDoc(directory.name, true, 0, 0, Uri.parse(directory.uri)).find(entry.filename)?.let {
            LocalImportEntry(it.uri.toString(), it.name, it.isDir, it.size, it.lastModified, true)
        }
    }
    suspend fun archiveNames(entry: LocalImportEntry) = local.archiveNames(entry)
    suspend fun existingArchiveBook(name: String) = local.existingArchiveBook(name)
    suspend fun importArchive(connection: RemoteImportConnection, remote: RemoteImportEntry, archive: LocalImportEntry, name: String): Book? =
        withContext(Dispatchers.IO) { local.importArchive(archive, name)?.also { saveOrigin(it, connection, remote.path) } }

    suspend fun storageConfiguration() = local.configuration()
    suspend fun saveStorageDirectory(value: String) = local.saveDirectory(value)
    fun privateStorageDirectory() = local.privateDirectory()
    fun errorMessage(error: Throwable): String = local.errorMessage(error)
    fun emptyArchiveMessage() = local.emptyArchiveMessage()

    fun servers() = database.serverDao.observeAll().map { list -> list.map { RemoteImportServer(it.id, it.name) } }.flowOn(Dispatchers.IO)
    suspend fun selectedServer(): Long = withContext(Dispatchers.IO) { AppConfig.remoteServerId }
    suspend fun selectServer(id: Long) = withContext(Dispatchers.IO) { AppConfig.remoteServerId = id }
    suspend fun deleteServer(id: Long) = withContext(Dispatchers.IO) {
        database.runInTransaction { database.serverDao.delete(id) }
        if (AppConfig.remoteServerId == id) AppConfig.remoteServerId = DEFAULT_WEBDAV_ID
    }
    suspend fun serverDraft(id: Long?): RemoteServerDraft = withContext(Dispatchers.IO) {
        if (id == null) return@withContext RemoteServerDraft()
        val server = requireNotNull(database.serverDao.get(id)) { context.getString(R.string.remote_import_server_missing) }
        val config = server.getWebDavConfig()
        RemoteServerDraft(server.id, server.name, config?.url.orEmpty(), config?.username.orEmpty(), config?.password.orEmpty())
    }
    suspend fun saveServer(draft: RemoteServerDraft): Long = withContext(Dispatchers.IO) {
        val config = Server.WebDavConfig(normalizeUrl(draft.url), draft.username, draft.password)
        var savedId = draft.id ?: System.currentTimeMillis()
        database.runInTransaction {
            val existing = draft.id?.let { requireNotNull(database.serverDao.get(it)) { context.getString(R.string.remote_import_server_missing) } }
            if (draft.id == null) while (database.serverDao.get(savedId) != null) savedId++
            database.serverDao.insert(Server(savedId, draft.name, config = GSON.toJson(config), sortNumber = existing?.sortNumber ?: 0))
        }
        savedId
    }
}
