package io.legado.app.data.repository

import android.content.Context
import android.util.AtomicFile
import androidx.annotation.Keep
import androidx.annotation.WorkerThread
import dagger.hilt.android.qualifiers.ApplicationContext
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.utils.GSON
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Keep
data class BookInfoPreview(
    val book: Book,
    val chapters: List<BookChapter>,
    val sourceKey: String?,
    val inBookshelf: Boolean,
    val infoHtml: String? = null,
    val tocHtml: String? = null,
    val downloadUrls: List<String>? = null,
)

@Keep
private data class BookInfoPreviewEnvelope(
    val format: Int = 1,
    val epoch: Long = 0,
    val revision: Long = 0,
    val preview: BookInfoPreview? = null,
    val pending: BookInfoPendingPreview? = null,
    val commitRevision: Long? = null,
)

@Keep
private data class BookInfoPendingPreview(val requestId: String, val requestRevision: Long, val preview: BookInfoPreview,
    val requiresCommitReceipt: Boolean = false)

data class BookInfoPreviewLease internal constructor(val key: String, internal val epoch: Long, val restored: BookInfoPreview?,
    val commitRevision: Long? = null)
data class BookInfoPreviewSnapshot(val preview: BookInfoPreview?, val commitRevision: Long?)

/** Private screen state, excluded from backups and independent of the library's books table. */
@Singleton
class BookInfoPreviewStore @Inject constructor(@ApplicationContext private val context: Context) {
    private val locks = ConcurrentHashMap<String, Any>()
    private val keyPattern = Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
    private fun lock(key: String): Any {
        require(key.matches(keyPattern))
        return locks.getOrPut(key) { Any() }
    }
    private fun directory() = File(context.noBackupFilesDir, "book-info-previews").apply { check(isDirectory || mkdirs()) }
    private fun file(key: String) = AtomicFile(File(directory(), "$key.json"))

    @WorkerThread
    fun peek(key: String): BookInfoPreview? = synchronized(lock(key)) { read(file(key))?.preview }

    @WorkerThread
    fun open(key: String): BookInfoPreviewLease = synchronized(lock(key)) {
        val atomic = file(key)
        val previous = read(atomic)
        val epoch = (previous?.epoch ?: 0) + 1
        write(atomic, BookInfoPreviewEnvelope(epoch = epoch, preview = previous?.preview, pending = previous?.pending,
            commitRevision = previous?.commitRevision))
        cleanOldEntries(key)
        BookInfoPreviewLease(key, epoch, previous?.preview, previous?.commitRevision)
    }

    /** Keep a durable request claim ordered with preview ownership handoffs. */
    @WorkerThread
    fun <T : Any> withCurrentLease(lease: BookInfoPreviewLease, action: () -> T): T? = synchronized(lock(lease.key)) {
        if (read(file(lease.key))?.epoch != lease.epoch) return@synchronized null
        action()
    }

    @WorkerThread
    fun stage(lease: BookInfoPreviewLease, revision: Long, requestId: String, requestRevision: Long,
        preview: BookInfoPreview, expectedCommitRevision: Long? = lease.commitRevision): Boolean = synchronized(lock(lease.key)) {
        val atomic = file(lease.key)
        val current = read(atomic) ?: return@synchronized false
        if (current.epoch != lease.epoch || current.commitRevision != expectedCommitRevision || revision <= current.revision) return@synchronized false
        require(preview.book.bookUrl.isNotBlank() && preview.chapters.all { it.bookUrl == preview.book.bookUrl })
        write(atomic, current.copy(revision = revision,
            pending = BookInfoPendingPreview(requestId, requestRevision, diskSnapshot(preview), requiresCommitReceipt = true)))
        true
    }

    /** Promote only the preview whose canonical key was committed by this durable request. */
    @WorkerThread
    fun resolve(lease: BookInfoPreviewLease, requestId: String, requestRevision: Long,
        bookUrl: String, previewCommitRevision: Long? = null): BookInfoPreview? =
        resolveSnapshot(lease, requestId, requestRevision, bookUrl, previewCommitRevision)?.preview

    @WorkerThread
    fun resolveSnapshot(lease: BookInfoPreviewLease, requestId: String, requestRevision: Long,
        bookUrl: String, previewCommitRevision: Long? = null): BookInfoPreviewSnapshot? = synchronized(lock(lease.key)) {
        val atomic = file(lease.key)
        val current = read(atomic) ?: return@synchronized null
        if (current.epoch != lease.epoch) return@synchronized null
        val pending = current.pending ?: return@synchronized BookInfoPreviewSnapshot(current.preview, current.commitRevision)
        val committed = previewCommitRevision == pending.requestRevision ||
            (!pending.requiresCommitReceipt && current.preview != null &&
                current.preview.book.bookUrl != pending.preview.book.bookUrl && requestRevision >= pending.requestRevision)
        val promoted = pending.requestId == requestId && committed && pending.preview.book.bookUrl == bookUrl
        val preview = if (promoted) pending.preview else current.preview
        val receipt = if (promoted) previewCommitRevision else current.commitRevision
        write(atomic, current.copy(preview = preview, pending = null, commitRevision = receipt))
        BookInfoPreviewSnapshot(preview, receipt)
    }

    @WorkerThread
    fun save(lease: BookInfoPreviewLease, revision: Long, preview: BookInfoPreview,
        expectedCommitRevision: Long? = lease.commitRevision): Boolean = synchronized(lock(lease.key)) {
        val atomic = file(lease.key)
        val current = read(atomic) ?: return@synchronized false
        if (current.epoch != lease.epoch || current.commitRevision != expectedCommitRevision || revision <= current.revision) return@synchronized false
        require(preview.book.bookUrl.isNotBlank())
        require(preview.chapters.all { it.bookUrl == preview.book.bookUrl })
        write(atomic, BookInfoPreviewEnvelope(epoch = lease.epoch, revision = revision, preview = diskSnapshot(preview),
            commitRevision = current.commitRevision))
        true
    }

    @WorkerThread
    fun discard(lease: BookInfoPreviewLease): Boolean = synchronized(lock(lease.key)) {
        val atomic = file(lease.key)
        if (read(atomic)?.epoch != lease.epoch) return@synchronized false
        atomic.delete()
        true
    }

    private fun diskSnapshot(preview: BookInfoPreview): BookInfoPreview = preview.copy(
        book = preview.book.copy(variable = GSON.toJson(preview.book.variableMap)),
        chapters = preview.chapters.map { it.copy(variable = GSON.toJson(it.variableMap)) },
        infoHtml = preview.infoHtml ?: preview.book.infoHtml,
        tocHtml = preview.tocHtml ?: preview.book.tocHtml,
        downloadUrls = preview.downloadUrls ?: preview.book.downloadUrls,
    )

    private fun read(atomic: AtomicFile): BookInfoPreviewEnvelope? = try {
        atomic.openRead().bufferedReader().use { reader ->
            GSON.fromJson(reader, BookInfoPreviewEnvelope::class.java)?.takeIf { it.format == 1 }
        }
    } catch (_: FileNotFoundException) { null }
    catch (error: Exception) { AppLog.put("读取详情预览恢复状态失败", error); null }

    private fun write(atomic: AtomicFile, value: BookInfoPreviewEnvelope) {
        val output = atomic.startWrite()
        try {
            output.write(GSON.toJson(value).toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
        } catch (error: Throwable) {
            atomic.failWrite(output)
            throw error
        }
    }

    private fun cleanOldEntries(requested: String) {
        val cutoff = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
        directory().listFiles()?.filter { file ->
            val key = file.name.removeSuffix(".bak").removeSuffix(".new").removeSuffix(".json")
            key != requested && key.matches(keyPattern) && file.lastModified() < cutoff
        }?.forEach { file -> runCatching { file.delete() } }
    }
}
