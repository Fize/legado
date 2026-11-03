package io.legado.app.service

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.os.Process
import android.provider.DocumentsContract
import android.util.AtomicFile
import androidx.annotation.Keep
import io.legado.app.constant.AppLog
import io.legado.app.data.repository.BookExportJournal
import io.legado.app.data.repository.BookTransferProcess
import io.legado.app.utils.*
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

@Keep internal data class ExportStagingRecord(
    val id: String, val rootKey: String, val directory: String, val name: String,
    val process: String, val pid: Int, val session: String, val stagingUri: String? = null,
    val version: Int = 1,
)

/** Ownership of unpublished scratch files, independent of the retained publication journal. */
internal class ExportStagingFiles(
    private val context: Context,
    private val output: FileDoc,
    private val journal: BookExportJournal,
    private val metadataRoot: File = File(context.noBackupFilesDir, "book-export-staging-v1"),
) : Closeable {
    companion object {
        private val metadata = ReentrantLock()
        private val active = ConcurrentHashMap.newKeySet<String>()
        fun isEngineStagingName(name: String): Boolean {
            if (name.length > 4096 || '/' in name || '\\' in name || !name.startsWith('.')) return false
            val prefix = name.substringBefore(".pending.").removePrefix(".")
            val uuid = runCatching { UUID.fromString(prefix).toString() == prefix }.getOrDefault(false)
            return uuid && name.substringAfter(".pending.", "").isNotBlank() ||
                name.matches(Regex("\\..+\\.[0-9a-f]+\\.pending\\.pdf"))
        }
    }
    private val rootKey = key(output)
    private val session = UUID.randomUUID().toString().also(active::add)
    private val created = linkedSetOf<String>()
    private var closed = false
    private fun key(folder: FileDoc) = folder.asFile()?.canonicalPath ?: "${folder.uri.authority}:${DocumentsContract.getDocumentId(folder.uri)}"
    private fun atomic(id: String): AtomicFile {
        require(UUID.fromString(id).toString() == id)
        return AtomicFile(File(metadataRoot, "$id.json"))
    }
    private fun validate(record: ExportStagingRecord, id: String = record.id) {
        require(record.version == 1 && record.id == id && record.pid > 0)
        listOf(id, record.process, record.session).forEach { require(UUID.fromString(it).toString() == it) }
        require(record.rootKey.length <= 32768 && record.directory.length <= 32768)
        require(record.directory.startsWith("content://") || File(record.directory).isAbsolute)
        require(isEngineStagingName(record.name))
        record.stagingUri?.let { require(it.length <= 32768 && Uri.parse(it).scheme in setOf("file", "content")) }
    }
    private fun write(record: ExportStagingRecord) {
        validate(record); check(metadataRoot.isDirectory || metadataRoot.mkdirs())
        val file = atomic(record.id); val stream = file.startWrite()
        try { val writer = stream.writer(Charsets.UTF_8); GSON.toJson(record, writer); writer.flush(); file.finishWrite(stream) }
        catch (error: Throwable) { file.failWrite(stream); throw error }
    }
    private fun read(id: String): ExportStagingRecord = atomic(id).openRead().use {
        check(it.channel.size() <= 131072)
        checkNotNull(GSON.fromJson(it.reader(Charsets.UTF_8), ExportStagingRecord::class.java)).also { record -> validate(record, id) }
    }
    private fun ids() = metadataRoot.listFiles().orEmpty().mapNotNull { file ->
        val id = file.name.substringBefore('.')
        id.takeIf { runCatching { UUID.fromString(it).toString() == it }.getOrDefault(false) &&
            file.name.removePrefix(it) in setOf(".json", ".json.bak", ".json.new") }
    }.distinct()
    private fun remove(id: String) {
        atomic(id).delete()
        check(listOf(".json", ".json.bak", ".json.new").none { File(metadataRoot, id + it).exists() })
    }
    private fun checkedFolder(record: ExportStagingRecord): FileDoc {
        check(record.rootKey == rootKey)
        val folder = FileDoc.fromDir(record.directory)
        output.asFile()?.let { root ->
            check(folder.asFile()!!.canonicalFile.toPath().startsWith(root.canonicalFile.toPath())) { "Export staging directory moved outside its root" }
        } ?: check(folder.isContentScheme && folder.uri.authority == output.uri.authority &&
            DocumentsContract.getTreeDocumentId(folder.uri) == DocumentsContract.getTreeDocumentId(output.uri))
        return folder
    }
    /** Null cursor/denied access must not be mistaken for an empty directory. */
    private fun find(folder: FileDoc, name: String): FileDoc? {
        folder.asFile()?.let { directory ->
            return try { Files.newDirectoryStream(directory.toPath()).use { entries ->
                entries.firstOrNull { it.fileName.toString() == name }?.toFile()?.let(FileDoc::fromFile)
            } } catch (_: NoSuchFileException) { null }
        }
        val childUri = DocumentsContract.buildChildDocumentsUriUsingTree(folder.uri, DocumentsContract.getDocumentId(folder.uri))
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED)
        val cursor = context.contentResolver.query(childUri, columns, null, null, null) ?: throw IOException("Cannot enumerate export staging directory")
        return cursor.use {
            val idColumn = it.getColumnIndexOrThrow(columns[0]); val nameColumn = it.getColumnIndexOrThrow(columns[1])
            val typeColumn = it.getColumnIndexOrThrow(columns[2]); val sizeColumn = it.getColumnIndexOrThrow(columns[3])
            val modifiedColumn = it.getColumnIndexOrThrow(columns[4])
            var found: FileDoc? = null
            while (it.moveToNext()) if (it.getString(nameColumn) == name) {
                check(found == null) { "Ambiguous export staging name" }
                found = FileDoc(name, it.getString(typeColumn) == DocumentsContract.Document.MIME_TYPE_DIR, it.getLong(sizeColumn), it.getLong(modifiedColumn),
                    DocumentsContract.buildDocumentUriUsingTree(folder.uri, it.getString(idColumn)))
            }
            found
        }
    }
    /** Called under the restore boundary before the retained engine starts rendering. */
    fun recoverAbandoned() {
        val alive = context.getSystemService(ActivityManager::class.java).runningAppProcesses
            ?.filter { it.uid == Process.myUid() }?.map { it.pid }?.toSet()
        metadata.withLock {
            ids().forEach { id ->
                val record = runCatching { read(id) }.getOrElse {
                    AppLog.put("Export staging metadata was preserved because it could not be read", it, true); return@forEach
                }
                if (record.rootKey != rootKey) return@forEach
                val abandoned = if (record.process == BookTransferProcess.id) record.session !in active
                    else alive != null && record.pid !in alive
                if (abandoned) cleanup(record)
            }
        }
    }
    /** Only the scoped engine adapter and the TXT image adapter create these files. */
    fun create(folder: FileDoc, name: String): FileDoc = metadata.withLock {
        check(!closed && isEngineStagingName(name))
        folder.asFile()?.let { check(it.isDirectory || it.mkdirs()) }
        check(find(folder, name) == null) { "Export staging name already exists" }
        val record = ExportStagingRecord(UUID.randomUUID().toString(), rootKey,
            folder.asFile()?.canonicalPath ?: folder.toString(), name, BookTransferProcess.id, Process.myPid(), session)
        checkedFolder(record)
        write(record); created.add(record.id) // Durable intent precedes any external creation.
        val stage = folder.asFile()?.let {
            File(it, name).also { child -> check(child.createNewFile()) }.let(FileDoc::fromFile)
        } ?: FileDoc.fromDocumentFile(checkNotNull(folder.asDocumentFile()!!.createFile("", name)))
        check(stage.name == name && !stage.isDir) { "Provider changed the export staging name" }
        write(record.copy(stagingUri = stage.uri.toString()))
        stage
    }
    private fun cleanup(record: ExportStagingRecord) {
        record.stagingUri?.let { uri ->
            if (journal.isRetained(FileDoc(record.name, false, 0, 0, Uri.parse(uri)))) return
        }
        val folder = checkedFolder(record)
        val stage = find(folder, record.name)
        // A caller may die while a separate provider still owns its create request.
        // Keep an unbound intent even when the file is not visible yet, so a late
        // creation remains owned and can be collected on a subsequent execution.
        if (stage == null && record.stagingUri == null) return
        if (stage != null) {
            check(!stage.isDir) { "Export staging file became a directory" }
            check(record.stagingUri == null || sameDocument(record.stagingUri, stage)) { "Export staging document identity changed" }
            if (journal.isRetained(stage)) return
            if (stage.isContentScheme) check(DocumentsContract.deleteDocument(context.contentResolver, stage.uri))
            else Files.delete(stage.asFile()!!.toPath())
            check(find(folder, record.name) == null) { "Cannot remove owned export staging file" }
        }
        // Resolve by staging name: a renamed URI may now identify the published canonical file.
        remove(record.id)
    }
    private fun sameDocument(expected: String, document: FileDoc): Boolean {
        val uri = Uri.parse(expected)
        if (document.isContentScheme) return uri.scheme == "content" && uri.authority == document.uri.authority &&
            DocumentsContract.getDocumentId(uri) == DocumentsContract.getDocumentId(document.uri)
        val path = uri.path ?: return false
        return uri.scheme == "file" && File(path).canonicalFile == document.asFile()!!.canonicalFile
    }
    override fun close() {
        metadata.withLock {
            if (closed) return
            try {
                created.forEach { id -> runCatching { read(id) }.getOrNull()?.takeIf {
                    it.session == session && it.process == BookTransferProcess.id && it.pid == Process.myPid()
                }?.let { record -> runCatching { cleanup(record) }.onFailure { error ->
                    AppLog.put("Export staging cleanup retained its ownership record", error, true)
                } } }
            } finally { closed = true; active.remove(session) }
        }
    }
}
