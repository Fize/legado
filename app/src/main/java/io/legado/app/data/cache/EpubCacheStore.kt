package io.legado.app.data.cache

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.UUID
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton

/** Each producer stages independently; an open archive retains its resource until close. */
@Singleton
class EpubCacheStore @Inject constructor(private val leases: CacheResourceLeases) {
    fun open(root: File, name: String, refresh: Boolean, input: () -> InputStream): ZipFile {
        val file = File(root, name)
        require(file.canonicalFile.parentFile == root.canonicalFile) { "Invalid EPUB cache name" }
        val resource = leases.acquire(epubCacheKey(name), mutating = true)
        try {
            if (!file.exists() || refresh) {
                val stagingName = ".epub-${UUID.randomUUID()}.stage"
                leases.acquire(epubCacheKey(stagingName), mutating = true).use {
                    val staging = File(root, stagingName)
                    try {
                        input().use { source -> staging.outputStream().use { output -> source.copyTo(output); output.fd.sync() } }
                        ZipFile(staging).use { /* Validate before replacing a previously usable archive. */ }
                        // Same-directory rename publishes complete bytes. Concurrent producers never
                        // share a staging file, and open ZipFiles keep their original file descriptor.
                        Files.move(staging.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
                    } finally {
                        staging.delete()
                    }
                }
            }
            return object : ZipFile(file) {
                override fun close() { try { super.close() } finally { resource.close() } }
            }
        } catch (error: Throwable) {
            resource.close()
            throw error
        }
    }
}
