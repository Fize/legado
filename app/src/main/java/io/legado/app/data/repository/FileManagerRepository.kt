package io.legado.app.data.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FileManagerRepository @Inject constructor(
    @ApplicationContext context: Context,
) {
    val root: File? = context.getExternalFilesDir(null)?.parentFile

    suspend fun list(directory: File?): Result<List<File>> = withContext(Dispatchers.IO) {
        runCatching {
            directory?.listFiles()?.sortedWith(compareBy({ it.isFile }, { it.name.lowercase() }))
                ?: emptyList()
        }
    }

    suspend fun delete(file: File): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            check(file.delete())
        }
    }
}
