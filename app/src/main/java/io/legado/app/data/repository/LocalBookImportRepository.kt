package io.legado.app.data.repository

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern.archiveFileRegex
import io.legado.app.constant.AppPattern.bookFileRegex
import io.legado.app.constant.PreferKey
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.help.book.cacheLocalUri
import io.legado.app.help.book.removeLocalUriCache
import io.legado.app.help.config.AppConfig
import io.legado.app.model.localBook.LocalBook
import io.legado.app.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import javax.inject.Inject

@Serializable
data class LocalImportDirectory(val uri: String, val name: String)

data class LocalImportEntry(
    val uri: String,
    val name: String,
    val isDir: Boolean,
    val size: Long,
    val lastModified: Long,
    val isOnBookShelf: Boolean,
)

data class LocalImportConfiguration(
    val directory: String?,
    val saveDirectory: String,
    val needsStorageSetup: Boolean,
    val sort: Int,
    val fileNameScript: String,
    val storageHelp: String,
)

data class LocalImportResult(val completeUris: Set<String>, val message: String)

class ImportBookShelfFiles(fileNames: Iterable<String>, alternateOrigins: Iterable<String>) {
    private val fileNames = fileNames.toHashSet()
    private val alternateOrigins = alternateOrigins.toHashSet()
    operator fun contains(fileName: String): Boolean =
        fileName in fileNames || alternateOrigins.any { it.endsWith(fileName, ignoreCase = true) }
}

class LocalBookImportRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: AppDatabase,
) {
    suspend fun configuration() = withContext(Dispatchers.IO) {
        LocalImportConfiguration(
            AppConfig.importBookPath?.takeIf(String::isNotBlank) ?: AppConfig.defaultBookTreeUri,
            AppConfig.defaultBookTreeUri ?: privateDirectory(),
            AppConfig.defaultBookTreeUri.isNullOrBlank(),
            context.getPrefInt(PreferKey.localBookImportSort),
            AppConfig.bookImportFileName.orEmpty(),
            context.assets.open("storageHelp.md").bufferedReader().use { it.readText() },
        )
    }

    fun privateDirectory(): String = Uri.fromFile(File(context.filesDir, "books")).toString()
    fun errorMessage(error: Throwable): String = error.localizedMessage ?: context.getString(R.string.unknown_error)
    fun emptyArchiveMessage(): String = context.getString(R.string.unsupport_archivefile_entry)

    suspend fun saveDirectory(uri: String) = withContext(Dispatchers.IO) {
        if (uri == privateDirectory()) File(context.filesDir, "books").mkdirs()
        AppConfig.defaultBookTreeUri = uri
    }
    suspend fun saveRoot(uri: String) = withContext(Dispatchers.IO) { AppConfig.importBookPath = uri }
    suspend fun saveSort(sort: Int) = withContext(Dispatchers.IO) { context.putPrefInt(PreferKey.localBookImportSort, sort) }
    suspend fun saveFileNameScript(script: String) = withContext(Dispatchers.IO) { AppConfig.bookImportFileName = script }

    suspend fun resolveDirectory(value: String): LocalImportDirectory? = withContext(Dispatchers.IO) {
        val uri = Uri.parse(value).let { if (it.scheme == null) Uri.fromFile(File(value)) else it }
        val doc = if (uri.scheme == "content") {
            val file = if (DocumentsContract.isDocumentUri(context, uri)) DocumentFile.fromSingleUri(context, uri)
                else DocumentFile.fromTreeUri(context, uri)
            file?.takeIf { it.isDirectory && it.canRead() }?.let(FileDoc::fromDocumentFile)
        } else {
            File(requireNotNull(uri.path)).takeIf { it.isDirectory && it.canRead() }?.let(FileDoc::fromFile)
        }
        doc?.let { LocalImportDirectory(it.uri.toString(), it.name) }
    }

    private fun LocalImportDirectory.doc() = FileDoc(name, true, 0L, 0L, Uri.parse(uri))
    private fun LocalImportEntry.doc() = FileDoc(name, isDir, size, lastModified, Uri.parse(uri))
    private fun shelfFiles() = ImportBookShelfFiles(database.bookDao.localBookFileNames, database.bookDao.localBookAlternateOrigins)
    private fun FileDoc.entry(shelf: ImportBookShelfFiles) = LocalImportEntry(
        uri.toString(), name, isDir, size, lastModified, !isDir && name in shelf,
    )
    private fun supported(doc: FileDoc) = doc.name.matches(bookFileRegex) || doc.name.matches(archiveFileRegex)

    suspend fun list(directory: LocalImportDirectory): List<LocalImportEntry> = withContext(Dispatchers.IO) {
        val shelf = shelfFiles()
        requireNotNull(directory.doc().list { !it.name.startsWith('.') && (it.isDir || supported(it)) })
            .map { it.entry(shelf) }
    }

    fun scan(directory: LocalImportDirectory) = flow {
        val shelf = shelfFiles()
        val pending = ArrayDeque<FileDoc>().apply { add(directory.doc()) }
        val visited = hashSetOf<String>()
        val files = linkedMapOf<String, LocalImportEntry>()
        while (pending.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val dir = pending.removeFirst()
            // Canonical paths also stop filesystem symlinks leading back to an ancestor.
            val identity = if (dir.uri.scheme == "file") File(requireNotNull(dir.uri.path)).canonicalPath else dir.uri.toString()
            if (!visited.add(identity)) continue
            requireNotNull(dir.list()).forEach { doc ->
                if (doc.isDir) pending.add(doc)
                else if (supported(doc)) files[doc.uri.toString()] = doc.entry(shelf)
            }
            emit(files.values.toList())
        }
    }.flowOn(Dispatchers.IO)

    suspend fun canAddGroup(): Boolean = withContext(Dispatchers.IO) { database.bookGroupDao.canAddGroup }

    suspend fun importBooks(entries: List<LocalImportEntry>, groupName: String?): LocalImportResult = withContext(Dispatchers.IO) {
        if (groupName != null) check(database.bookGroupDao.canAddGroup) { context.getString(R.string.book_group_limit) }
        val (importedUris, importedBooks) = LocalBook.importFiles(entries.map { Uri.parse(it.uri) })
        val groupError = groupName?.let { name ->
            runCatching {
                database.runInTransaction {
                    val groupDao = database.bookGroupDao
                    check(groupDao.canAddGroup) { context.getString(R.string.book_group_limit) }
                    val groupId = groupDao.getUnusedId()
                    groupDao.getByID(groupId) ?: database.bookDao.removeGroup(groupId)
                    groupDao.insert(BookGroup(groupId = groupId, groupName = name, order = groupDao.maxOrder + 1))
                    importedBooks.map { it.bookUrl }.chunked(900).forEach { database.bookDao.addGroup(it, groupId) }
                }
            }.exceptionOrNull()
        }
        val message = when {
            groupError != null -> {
                AppLog.put("创建本地书籍目录分组失败", groupError)
                context.getString(R.string.import_directory_group_failed, errorMessage(groupError))
            }
            importedUris.size == entries.size -> context.getString(R.string.nb_file_add_succeed, importedBooks.size)
            else -> context.getString(R.string.local_import_partial, importedBooks.size, entries.size - importedUris.size)
        }
        LocalImportResult(importedUris.mapTo(linkedSetOf()) { it.toString() }, message)
    }

    suspend fun delete(entries: List<LocalImportEntry>) = withContext(Dispatchers.IO) {
        entries.forEach { currentCoroutineContext().ensureActive(); val doc = it.doc(); doc.delete(); check(!doc.exists()) { it.name } }
    }

    fun isArchive(entry: LocalImportEntry) = ArchiveUtils.isArchive(entry.name)
    suspend fun archiveNames(entry: LocalImportEntry): List<String> = withContext(Dispatchers.IO) {
        ArchiveUtils.getArchiveFilesName(entry.doc()) { it.matches(bookFileRegex) }
    }
    suspend fun existingArchiveBook(name: String): Book? = withContext(Dispatchers.IO) { database.bookDao.getBookByFileName(name) }
    suspend fun importArchive(entry: LocalImportEntry, name: String): Book? = withContext(Dispatchers.IO) {
        LocalBook.importArchiveFile(Uri.parse(entry.uri), name) { it == name }.firstOrNull()
    }
    suspend fun rebind(entry: LocalImportEntry): Book? = withContext(Dispatchers.IO) {
        val fileDoc = entry.doc()
        val book = database.bookDao.getBook(fileDoc.toString()) ?: database.bookDao.getBookByFileName(fileDoc.name)
            ?: return@withContext null
        LocalBook.withParserCacheInvalidated(book.bookUrl, book.originName) {
            book.removeLocalUriCache()
            book.cacheLocalUri(fileDoc.uri)
        }
        book
    }
}
