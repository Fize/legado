package io.legado.app.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import com.bumptech.glide.Glide
import dagger.hilt.android.qualifiers.ApplicationContext
import io.legado.app.utils.SvgUtils
import io.legado.app.utils.externalFiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CoverFileRepository @Inject constructor(@ApplicationContext private val context: Context) {
    suspend fun importCover(uri: Uri): String = withContext(IO) {
        val directory = File(context.externalFiles, "covers").apply { check(isDirectory || mkdirs()) }
        val temporary = File.createTempFile("import-", ".tmp", directory)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            checkNotNull(context.contentResolver.openInputStream(uri)).use { input ->
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count); digest.update(buffer, 0, count)
                    }
                }
            }
            check(temporary.length() > 0) { "Empty image" }
            val decoded = checkNotNull(coverBitmap(temporary.absolutePath)) { "Unable to decode image" }
            decoded.recycle()
            val displayName = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
            val suffix = if (displayName?.endsWith(".9.png", true) == true) ".9.png" else ".image"
            val file = File(directory, "group-" + digest.digest().joinToString("") { "%02x".format(it) } + suffix)
            if (!file.exists()) check(temporary.renameTo(file))
            file.absolutePath
        } finally { temporary.delete() }
    }

    suspend fun coverBitmap(path: String?): Bitmap? = withContext(IO) {
        if (path.isNullOrBlank()) return@withContext null
        val request = Glide.with(context).asBitmap().load(path).submit(216, 300)
        try {
            runInterruptible { request.get() }.copy(Bitmap.Config.ARGB_8888, false)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            if (File(path).isFile) SvgUtils.createBitmap(path, 216, 300) else null
        }
        finally { Glide.with(context).clear(request) }
    }
}
