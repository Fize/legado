package io.legado.app.data.repository

import android.content.Context
import android.net.Uri
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import dagger.hilt.android.qualifiers.ApplicationContext
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.constant.AppPattern.bookFileRegex
import io.legado.app.constant.AppPattern.jsFileRegex
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.HighlightRuleFile
import io.legado.app.help.storage.Restore
import io.legado.app.help.config.AppConfig
import io.legado.app.help.storage.selectedBackupFileNames
import io.legado.app.model.jsSource.JsSourceConfig
import io.legado.app.model.localBook.LocalBook
import io.legado.app.ui.book.import.local.ImportBook
import io.legado.app.ui.main.bookshelf.importBookshelfJson
import io.legado.app.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import kotlinx.serialization.json.Json

sealed interface FileAssociationResult {
    data class Data(val type: String, val source: String) : FileAssociationResult
    data class Online(val uri: String) : FileAssociationResult
    data class Preview(val items: List<ImportBook>, val stagingPath: String, val openSingle: Boolean) : FileAssociationResult
    data class Unsupported(val uri: Uri, val name: String) : FileAssociationResult
    data class OpenBook(val book: Book) : FileAssociationResult
    data object MixedTypes : FileAssociationResult
}

/** File/provider access and durable import operations for external content. */
class FileAssociationRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: AppDatabase,
) {
    suspend fun loadRequest(id: String, initial: FileAssociationRequest?): FileAssociationRequest = withContext(Dispatchers.IO) {
        require(id.matches(Regex("[a-zA-Z0-9-]{1,80}")))
        val directory = File(context.cacheDir, "association-requests").apply { mkdirs() }
        val file = File(directory, "$id.json")
        if (file.isFile) return@withContext Json.decodeFromString<FileAssociationRequest>(file.readText())
        val input = requireNotNull(initial) { errorMessage() }
        val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        directory.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
        val temporary = File.createTempFile("request-", ".tmp", directory)
        try {
            temporary.writeText(Json.encodeToString(FileAssociationRequest.serializer(), input))
            check(temporary.renameTo(file))
        } finally { temporary.delete() }
        input
    }

    suspend fun requiresStoragePermission(request: FileAssociationRequest): Boolean = withContext(Dispatchers.IO) {
        if (request.action != FileAssociationAction.View) return@withContext false
        val uri = request.sourceUri?.let(Uri::parse) ?: return@withContext false
        if (uri.isContentScheme()) return@withContext false
        if (uri.scheme == "http" || uri.scheme == "https") return@withContext false
        if (uri.scheme != null && uri.scheme != "file") return@withContext true
        val file = File(requireNotNull(uri.path)).canonicalFile
        val ownedDirectories = listOf(context.filesDir, context.cacheDir) +
            context.getExternalFilesDirs(null).filterNotNull() + context.externalCacheDirs.filterNotNull()
        ownedDirectories.none { root ->
            val directory = root.canonicalFile
            file == directory || file.path.startsWith(directory.path + File.separator)
        }
    }

    fun configuredBookDirectory(): Uri? = AppConfig.defaultBookTreeUri?.takeIf { it.isNotBlank() }?.let(Uri::parse)
    fun privateBookDirectory(): Uri = Uri.fromFile(File(context.filesDir, "books"))
    fun bookDirectoryDescription(): String = AppConfig.defaultBookTreeUri ?: privateBookDirectory().path.orEmpty()
    fun saveBookDirectory(directory: Uri) { AppConfig.defaultBookTreeUri = directory.toString() }

    fun errorMessage(error: Throwable? = null): String =
        error?.localizedMessage ?: context.getString(R.string.wrong_format)

    suspend fun resolveUri(uri: Uri, shared: Boolean): FileAssociationResult = withContext(Dispatchers.IO) {
        if (shared) {
            require(uri.isContentScheme())
            uri.inputStream(context).getOrThrow().use { }
        }
        if (uri.isContentScheme() || uri.isFileScheme()) dispatchFile(FileDoc.fromUri(uri, false), shared)
        else FileAssociationResult.Online(uri.toString())
    }

    suspend fun resolveUris(uris: List<Uri>): FileAssociationResult = withContext(Dispatchers.IO) {
        require(uris.isNotEmpty() && uris.all { it.isContentScheme() })
        prepareLocalBooks(uris, false)
    }

    suspend fun resolveText(text: String): FileAssociationResult = withContext(Dispatchers.IO) {
        extractSharedImportUrl(text)?.let { url ->
            return@withContext FileAssociationResult.Online(
                Uri.Builder().scheme("legado").authority("import").appendPath("auto")
                    .appendQueryParameter("src", url).build().toString()
            )
        }
        val file = transferFile().apply { writeText(text) }
        readJson(Uri.fromFile(file))
    }

    private fun transferFile(): File {
        val directory = File(context.cacheDir, "association-transfers").apply { mkdirs() }
        // A destination reads after this entry has gone away. Keep transfers for a day;
        // remove expired files on the next transfer rather than racing the next reader.
        val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        directory.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
        return File.createTempFile("import-", ".json", directory)
    }

    private fun readJson(uri: Uri): FileAssociationResult.Data {
        val map = uri.inputStream(context).getOrThrow().use {
            jsonPath.parse(it).read<Map<String, *>>("$[0]")
        } ?: uri.inputStream(context).getOrThrow().use { jsonPath.parse(it).read("$") }
        return FileAssociationResult.Data(requireNotNull(jsonImportType(map)) { errorMessage() }, uri.toString())
    }

    private suspend fun dispatchFile(file: FileDoc, shared: Boolean): FileAssociationResult {
        if (file.name.matches(AppPattern.archiveFileRegex)) {
            val backupNames = selectedBackupFileNames { true }.toSet()
            val entries = if (file.name.endsWith(".zip", true)) {
                ArchiveUtils.getArchiveFilesName(file) { it in backupNames || it.matches(bookFileRegex) }
            } else emptyList()
            return if (entries.any { it in backupNames } && entries.none { it.matches(bookFileRegex) }) {
                FileAssociationResult.Data("backup", file.uri.toString())
            } else prepareLocalBooks(listOf(file.uri), false)
        }
        runCatchingCancellable {
            if (file.openInputStream().getOrNull().looksLikeJson()) return readJson(file.uri)
        }.onFailure { AppLog.put("尝试导入为JSON文件失败", it) }
        return when {
            file.name.matches(jsFileRegex) -> FileAssociationResult.Data("bookSource", file.uri.toString())
            file.name.matches(bookFileRegex) -> prepareLocalBooks(listOf(file.uri), !shared)
            else -> FileAssociationResult.Unsupported(file.uri, file.name)
        }
    }

    suspend fun importData(type: String, source: String) = withContext(Dispatchers.IO) {
        when (type) {
            "bookshelf" -> importBookshelfJson(Uri.parse(source).readText(context), 0)
            "backup" -> Restore.restoreOrThrow(context, Uri.parse(source))
            else -> error("Unsupported import")
        }
    }

    suspend fun importBook(uri: Uri): Book = withContext(Dispatchers.IO) { LocalBook.importFile(uri) }

    suspend fun restorePreview(path: String, openSingle: Boolean): FileAssociationResult.Preview = withContext(Dispatchers.IO) {
        val staging = File(path)
        require(staging.parentFile?.canonicalFile == File(context.cacheDir, "shared-books").canonicalFile)
        val books = GSON.fromJsonArray<Book>(File(staging, "preview.json").readText()).getOrThrow()
        FileAssociationResult.Preview(books.map { ImportBook(FileDoc.fromFile(File(it.bookUrl)), false, it) }, path, openSingle)
    }

    private suspend fun prepareLocalBooks(uris: List<Uri>, openSingle: Boolean): FileAssociationResult {
        val staging = File(context.cacheDir, "shared-books/${UUID.randomUUID()}")
        var retainStaging = false
        try {
            val files = collectSharedImportFiles(uris, staging)
            val dataFiles = files.mapNotNull { file ->
                val type = if (file.name.matches(jsFileRegex)) "bookSource"
                else runCatchingCancellable {
                    if (!file.inputStream().looksLikeJson()) return@runCatchingCancellable null
                    val map = file.inputStream().use { jsonPath.parse(it).read<Map<String, *>>("$[0]") }
                        ?: file.inputStream().use { jsonPath.parse(it).read("$") }
                    jsonImportType(map)
                }.getOrNull()
                type?.let { it to file }
            }
            val bookFiles = files.filter { file ->
                file.name.matches(bookFileRegex) && dataFiles.none { it.second == file }
            }
            if (dataFiles.isNotEmpty()) {
                if (bookFiles.isNotEmpty() || dataFiles.map { it.first }.distinct().size != 1) {
                    staging.deleteRecursively()
                    return FileAssociationResult.MixedTypes
                }
                val type = dataFiles.first().first
                val file = if (dataFiles.size == 1) dataFiles.single().second else {
                    val merged = JsonArray()
                    dataFiles.forEach { (_, source) ->
                        val json = if (source.name.matches(jsFileRegex)) {
                            GSON.toJsonTree(JsSourceConfig.extract(source.readText(), currentCoroutineContext()))
                        } else source.reader().use { GSON.fromJson(it, JsonElement::class.java) }
                        val records = if (type == "highlightRule" && json.isJsonObject &&
                            json.asJsonObject.get("type")?.asString == HighlightRuleFile.TYPE) {
                            json.asJsonObject.getAsJsonArray("rules")
                        } else json
                        if (records.isJsonArray) records.asJsonArray.forEach(merged::add)
                        else merged.add(records)
                    }
                    File(staging, "import-data.json").apply { writeText(merged.toString()) }
                }
                val transfer = transferFile().also { file.copyTo(it, overwrite = true) }
                staging.deleteRecursively()
                return FileAssociationResult.Data(type, Uri.fromFile(transfer).toString())
            }
            val items = previewSharedLocalBooks(bookFiles, database, context)
            File(staging, "preview.json").writeText(GSON.toJson(items.map { it.preview }))
            retainStaging = true
            return FileAssociationResult.Preview(items, staging.path, openSingle)
        } finally {
            if (!retainStaging) staging.deleteRecursively()
        }
    }

    suspend fun copyLocalBooks(items: List<ImportBook>, directory: Uri): List<Book> = withContext(Dispatchers.IO) {
        val copies = linkedMapOf<Uri, Book>()
        items.forEach { item ->
            runCatchingCancellable {
                val uri = copySharedLocalBook(item.file, directory, database, context)
                val preview = checkNotNull(item.preview)
                    .copy(bookUrl = FileDoc.fromUri(uri, false).toString())
                item.preview.coverUrl?.let { coverPath ->
                    val cover = File(coverPath)
                    if (coverPath == LocalBook.getCoverPath(item.preview) && cover.isFile) {
                        val destination = File(LocalBook.getCoverPath(preview))
                        cover.copyTo(destination, overwrite = true)
                        preview.coverUrl = destination.path
                    }
                }
                copies[uri] = preview
            }.onFailure { AppLog.put("复制分享书籍失败\n${it.localizedMessage}", it) }
        }
        val (_, books) = LocalBook.importFiles(copies.keys.toList(), copies)
        books
    }

    suspend fun releasePreview(items: List<ImportBook>, stagingPath: String?) = withContext(Dispatchers.IO) {
        items.distinctBy { it.file.uri }.forEach {
            LocalBook.withParserCacheInvalidated(it.file.uri, it.file.name) { }
            it.preview?.let { preview ->
                if (preview.coverUrl == LocalBook.getCoverPath(preview)) File(preview.coverUrl!!).delete()
            }
        }
        stagingPath?.let { File(it).deleteRecursively() }
    }
}
