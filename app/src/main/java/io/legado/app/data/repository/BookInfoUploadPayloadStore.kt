package io.legado.app.data.repository

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import dagger.hilt.android.qualifiers.ApplicationContext
import io.legado.app.utils.inputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

data class BookInfoUploadPayload(val key: String, val sha256: String, val size: Long)

/** Private immutable bytes; neither transient grants nor mutable originals are needed by workers. */
@Singleton
class BookInfoUploadPayloadStore private constructor(private val context: Context, private val namespace: String) {
    @Inject constructor(@ApplicationContext context: Context) : this(context, "")
    constructor(context: Context, namespace: UUID) : this(context, namespace.toString())
    private val preparing = ConcurrentHashMap.newKeySet<String>()
    private val directory get() = File(context.noBackupFilesDir, "book-info-uploads${if (namespace.isEmpty()) "" else "/$namespace"}")
        .apply { check(isDirectory || mkdirs()) }
    private fun file(key: String): File {
        require(UUID.fromString(key).toString() == key)
        return File(directory, "$key.data")
    }
    suspend fun capture(uri: Uri): BookInfoUploadPayload {
        val key = UUID.randomUUID().toString()
        val atomic = AtomicFile(file(key))
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        preparing += key
        val output = try { atomic.startWrite() } catch (error: Throwable) { preparing -= key; throw error }
        try {
            uri.inputStream(context).getOrThrow().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count); digest.update(buffer, 0, count); size += count
                }
            }
            atomic.finishWrite(output)
            return BookInfoUploadPayload(key, digest.digest().joinToString("") { "%02x".format(it) }, size)
        } catch (error: Throwable) { atomic.failWrite(output); atomic.delete(); preparing -= key; throw error }
    }
    suspend fun verified(key: String, sha256: String, size: Long): File {
        val file = file(key)
        check(file.isFile && file.length() == size) { "Upload snapshot is missing or changed" }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        check(digest.digest().joinToString("") { "%02x".format(it) } == sha256) { "Upload snapshot is changed" }
        return file
    }
    fun remove(key: String) { AtomicFile(file(key)).delete() }
    fun releasePreparation(key: String) { preparing -= key }
    fun pruneOrphans(keep: Set<String>, before: Long) {
        directory.listFiles().orEmpty().forEach { candidate ->
            val key = candidate.name.substringBefore(".data")
            if (key !in keep && key !in preparing && candidate.lastModified() < before &&
                candidate.name in setOf("$key.data", "$key.data.bak", "$key.data.new") &&
                runCatching { UUID.fromString(key).toString() == key }.getOrDefault(false)) remove(key)
        }
    }
}
