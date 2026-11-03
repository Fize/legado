package io.legado.app.data.repository

import android.content.Context
import android.content.Intent
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import io.legado.app.utils.FileDoc
import io.legado.app.utils.checkWrite
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CacheFolderAccess @Inject constructor(@ApplicationContext private val context: Context) {
    suspend fun accept(value: String): String=withContext(Dispatchers.IO) {
        require(value.length<=32_768)
        val uri=Uri.parse(value); require(uri.scheme=="content" && !uri.authority.isNullOrBlank())
        context.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        check(FileDoc.fromDir(uri).checkWrite()) { "Folder is not writable" }
        value
    }
    suspend fun writable(value: String): Boolean=withContext(Dispatchers.IO) {
        value.isNotBlank() && runCatching { FileDoc.fromDir(value).checkWrite() }.getOrDefault(false)
    }
}
