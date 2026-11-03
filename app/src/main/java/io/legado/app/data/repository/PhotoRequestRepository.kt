package io.legado.app.data.repository

import android.content.Context
import android.util.AtomicFile
import androidx.annotation.Keep
import dagger.hilt.android.qualifiers.ApplicationContext
import io.legado.app.data.entities.Book
import io.legado.app.help.book.BookHelp
import io.legado.app.model.ImageProvider
import io.legado.app.utils.GSON
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Keep
data class PhotoRequest(val url: String, val sourceOrigin: String? = null, val localPath: String? = null)

/** Durable route inputs, including inline images, never cross the instance-state Binder payload. */
@Singleton
class PhotoRequestRepository @Inject constructor(@ApplicationContext private val context: Context) {
    private fun directory() = File(context.noBackupFilesDir, "photo-requests").apply { check(isDirectory || mkdirs()) }
    private fun file(key: String): AtomicFile {
        require(key.matches(Regex("[a-f0-9-]{36}")))
        return AtomicFile(File(directory(), "$key.json"))
    }
    suspend fun create(url: String, sourceOrigin: String? = null, book: Book? = null): String = withContext(IO) {
        require(url.isNotBlank())
        val key = UUID.randomUUID().toString()
        var image: File? = null
        try {
            // Snapshot transient captcha/rendered images before their producer evicts the bitmap.
            val bitmap = runCatching { ImageProvider.get(url)?.takeUnless { it.isRecycled }?.copy(android.graphics.Bitmap.Config.ARGB_8888, false) }.getOrNull()
            if (bitmap != null) try {
                image = File(directory(), "$key.png")
                image!!.outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
            } finally { bitmap.recycle() }
            if (image == null) book?.let { BookHelp.getImage(it, url) }?.takeIf { it.isFile }?.let { cached ->
                image = File(directory(), "$key.image")
                cached.inputStream().use { input -> copy(input, image!!) }
            }
            if (image == null && (url.startsWith("content://") || url.startsWith("file://"))) {
                image = File(directory(), "$key.image")
                checkNotNull(context.contentResolver.openInputStream(android.net.Uri.parse(url))).use { copy(it, image!!) }
            }
            val local = image?.path
            val atomic = file(key)
            val output = atomic.startWrite()
            try { output.write(GSON.toJson(PhotoRequest(url, sourceOrigin, local)).toByteArray()); atomic.finishWrite(output) }
            catch (error: Exception) { atomic.failWrite(output); throw error }
            key
        } catch (error: Exception) { image?.delete(); throw error }
    }
    private suspend fun copy(input: java.io.InputStream, file: File) {
        file.outputStream().use { output ->
            val buffer = ByteArray(8192)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count)
            }
        }
    }
    suspend fun delete(key: String) = withContext(IO) {
        file(key).delete()
        File(directory(), "$key.png").delete()
        File(directory(), "$key.image").delete()
        Unit
    }
    suspend fun read(key: String): PhotoRequest = withContext(IO) {
        val request = file(key).openRead().bufferedReader().use { checkNotNull(GSON.fromJson(it, PhotoRequest::class.java)) }
        require(request.url.isNotBlank()) { "Invalid photo request" }
        request.copy(localPath = request.localPath?.takeIf { File(it).isFile })
    }
}
