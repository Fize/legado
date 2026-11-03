package io.legado.app.data.repository

import android.content.Context
import android.util.AtomicFile
import androidx.annotation.Keep
import dagger.hilt.android.qualifiers.ApplicationContext
import io.legado.app.constant.AppLog
import io.legado.app.utils.GSON
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Keep
data class BookInfoEditDraftSnapshot(val target: String, val revision: Long, val draft: BookInfoEditDraft, val dirty: Set<BookInfoEditField>)
@Keep
private data class EditDraftEnvelope(val epoch: Long = 0, val snapshot: BookInfoEditDraftSnapshot? = null)
data class EditDraftLease internal constructor(val key: String, internal val epoch: Long, val restored: BookInfoEditDraftSnapshot?)

/** User text is stored privately, outside the instance-state Binder payload and device backups. */
@Singleton
class BookInfoEditDraftStore @Inject constructor(@ApplicationContext private val context: Context) {
    private val locks = ConcurrentHashMap<String, Any>()
    private fun lock(key: String): Any { require(key.matches(Regex("[a-f0-9-]{36}"))); return locks.getOrPut(key) { Any() } }
    private fun file(key: String) = AtomicFile(File(File(context.noBackupFilesDir, "book-info-edit-drafts").apply { check(isDirectory || mkdirs()) }, "$key.json"))
    fun open(key: String): EditDraftLease = synchronized(lock(key)) {
        val atomic = file(key)
        val previous = read(atomic)
        val epoch = (previous?.epoch ?: 0) + 1
        write(atomic, EditDraftEnvelope(epoch, previous?.snapshot))
        EditDraftLease(key, epoch, previous?.snapshot)
    }
    fun save(lease: EditDraftLease, snapshot: BookInfoEditDraftSnapshot): Boolean = synchronized(lock(lease.key)) {
        val atomic = file(lease.key)
        val current = read(atomic) ?: return@synchronized false
        if (current.epoch != lease.epoch || snapshot.revision <= (current.snapshot?.revision ?: -1)) return@synchronized false
        write(atomic, EditDraftEnvelope(lease.epoch, snapshot)); true
    }
    fun discard(lease: EditDraftLease): Boolean = synchronized(lock(lease.key)) {
        val atomic = file(lease.key)
        if (read(atomic)?.epoch != lease.epoch) return@synchronized false
        // Keep an epoch tombstone so opening the same key cannot revive an old lease.
        write(atomic, EditDraftEnvelope(lease.epoch + 1)); true
    }
    private fun read(file: AtomicFile): EditDraftEnvelope? = try {
        file.openRead().bufferedReader().use { GSON.fromJson(it, EditDraftEnvelope::class.java) }
    } catch (_: FileNotFoundException) { null }
    catch (error: Exception) { AppLog.put("读取书籍编辑草稿失败", error); null }
    private fun write(file: AtomicFile, value: EditDraftEnvelope) {
        val output = file.startWrite()
        try { output.write(GSON.toJson(value).toByteArray()); file.finishWrite(output) }
        catch (error: Exception) { file.failWrite(output); throw error }
    }
}
