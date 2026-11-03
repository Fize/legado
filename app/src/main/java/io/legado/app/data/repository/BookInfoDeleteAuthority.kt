package io.legado.app.data.repository

import android.content.Context
import android.util.AtomicFile
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** System backup must never transfer authorization to execute a destructive command. */
@Singleton
open class BookInfoDeleteAuthority @Inject constructor(@ApplicationContext private val context: Context) {
    open fun id(): String = synchronized(lock) {
        val file = AtomicFile(File(context.noBackupFilesDir, "book-info-delete-installation"))
        val existing = runCatching { file.openRead().bufferedReader().use { it.readText() } }.getOrNull()
        if (existing != null && runCatching { UUID.fromString(existing) }.isSuccess) return@synchronized existing
        val value = UUID.randomUUID().toString()
        val output = file.startWrite()
        try { output.write(value.toByteArray(Charsets.UTF_8)); file.finishWrite(output) }
        catch (error: Throwable) { file.failWrite(output); throw error }
        value
    }
    private companion object { val lock = Any() }
}
