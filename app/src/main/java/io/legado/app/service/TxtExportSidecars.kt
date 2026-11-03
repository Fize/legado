package io.legado.app.service

import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.Book
import io.legado.app.data.repository.BookExportJournal
import io.legado.app.help.storage.ExportRestoreBoundary
import io.legado.app.utils.*
import kotlinx.coroutines.ensureActive
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext

/** Publishes each TXT image through the existing per-file journal; this is not a group transaction. */
internal class TxtExportSidecars(
    private val db: AppDatabase,
    private val book: Book,
    private val root: FileDoc,
    private val journal: BookExportJournal,
    private val boundary: ExportRestoreBoundary,
    private val verifyOwner: () -> Unit,
    private val publication: ((() -> FileDoc) -> FileDoc),
    private val createStaging: ((FileDoc, String) -> FileDoc)? = null,
) : FileDocWriteInterceptor {
    private data class Pending(val folder: FileDoc, val target: String, val staging: FileDoc, val backup: String)
    private val pending = ConcurrentHashMap<String, Pending>()
    @Volatile private var catalog: String? = null

    private fun catalogKey() = GSON.toJson(db.bookChapterDao.getChapterList(book.bookUrl).map {
        listOf(it.url, it.index.toString(), it.title, it.isVolume.toString(), it.start?.toString(),
            it.end?.toString(), it.startFragmentId, it.endFragmentId, it.baseUrl)
    })

    // The retained engine verifies ownership inside the transaction that captures its chapters.
    // Never acquire the restore mutex here: its lock must precede the Room transaction.
    fun captureCatalog() {
        if (catalog == null && db.inTransaction()) catalog = catalogKey()
    }

    private fun verify(execution: CoroutineContext) {
        execution.ensureActive()
        boundary.checkCurrent()
        verifyOwner()
    }

    override fun create(parent: FileDoc, name: String, subDirs: Array<out String>, execution: CoroutineContext): FileDoc? {
        if (subDirs.size != 3 || subDirs[0] != "${book.name}_${book.author}" || subDirs[1] != "images") return null
        check(parent.uri == root.uri) { "TXT image directory changed" }
        check(name.matches(Regex("[0-9]+-[0-9a-fA-F]{16}\\.jpg"))) { "Invalid TXT image name" }
        root.asFile()?.let { directory ->
            val base = directory.canonicalFile
            val destination = subDirs.fold(base) { folder, child -> File(folder, child) }.resolve(name).canonicalFile
            check(destination.toPath().startsWith(base.toPath()) && destination != base) { "TXT image escapes export directory" }
        }
        return boundary.synchronous(execution) {
            verify(execution)
            checkNotNull(catalog) { "TXT image has no chapter snapshot" }
            val folder = parent.createFolderIfNotExist(*subDirs)
            journal.checkDestination(folder, name)
            val id = UUID.randomUUID().toString()
            val stagingName = ".$id.pending.$name"
            check(folder.find(stagingName) == null) { "TXT image staging file already exists" }
            val stage = createStaging?.invoke(folder, stagingName) ?: folder.createFileIfNotExist(stagingName)
            check(stage.name == stagingName && !stage.isDir) { "Provider changed the TXT image staging name" }
            check(pending.putIfAbsent(stage.uri.toString(), Pending(folder, name, stage, ".$id.backup.$name")) == null)
            stage
        }
    }

    override fun write(destination: FileDoc, source: File, execution: CoroutineContext): Boolean {
        val entry = pending.remove(destination.uri.toString()) ?: return false
        try {
            verify(execution)
            val expected = source.length()
            var copied = 0L
            destination.openOutputStream().getOrThrow().use { output ->
                source.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        execution.ensureActive(); boundary.checkCurrent()
                        val count = input.read(buffer)
                        if (count < 0) break
                        execution.ensureActive(); boundary.checkCurrent()
                        output.write(buffer, 0, count)
                        copied += count
                    }
                }
            }
            verify(execution)
            check(copied == expected && source.length() == expected) { "Cached TXT image changed during export" }
            journal.publish(entry.folder, destination, entry.target, entry.backup, { verify(execution) }) { action ->
                boundary.synchronous(execution) {
                    publication {
                        verify(execution)
                        check(catalogKey() == catalog) { "Book chapters changed" }
                        action()
                    }
                }
            }
            return true
        } finally { cleanup(entry) }
    }

    private fun cleanup(entry: Pending) {
        if (!journal.isRetained(entry.staging)) {
            // SAF rename may preserve a URI: resolve the staging NAME, never delete that stale URI.
            entry.folder.find(entry.staging.name)?.let { it.delete(); check(!it.exists()) }
        }
    }

    fun close() { pending.values.toList().forEach { cleanup(it); pending.remove(it.staging.uri.toString()) } }
}
