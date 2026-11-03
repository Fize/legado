package io.legado.app.data.cache

import android.content.Context
import android.util.AtomicFile
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** Bounded, private decoded-byte cache. Clear revokes in-flight publications for that book. */
@Singleton
class TextReaderImageStore internal constructor(private val root: File, private val limit: Long, private val maxEntries: Int = 512) {
    @Inject constructor(@ApplicationContext context: Context) : this(File(context.cacheDir, "text-reader-images"), 32L * 1024 * 1024)
    init { require(limit > 0 && maxEntries > 0) }
    internal class Entry { var users = 0; var revision = 0L }
    private val entries = hashMapOf<String, Entry>()

    inner class Lease internal constructor(internal val book: String, internal val entry: Entry,
        internal val revision: Long) : Closeable {
        internal var closed = false
        fun isCurrent(): Boolean = current(this)
        override fun close() = release(this)
    }

    @Synchronized fun open(bookUrl: String): Lease {
        val book = digest(bookUrl)
        val entry = entries.getOrPut(book) { Entry() }
        entry.users++
        return Lease(book, entry, entry.revision)
    }
    @Synchronized private fun current(lease: Lease) = !lease.closed && lease.entry.revision == lease.revision
    @Synchronized private fun release(lease: Lease) {
        if (lease.closed) return
        lease.closed = true
        if (--lease.entry.users == 0) {
            entries.remove(lease.book)
            if (!Files.isSymbolicLink(root.toPath())) {
                val directory = File(root, lease.book)
                if (!Files.isSymbolicLink(directory.toPath()) && directory.isDirectory && directory.listFiles().isNullOrEmpty()) directory.delete()
            }
        }
    }

    @Synchronized fun read(lease: Lease, identity: String): ByteArray? {
        if (!current(lease)) return null
        val file = file(lease.book, identity)
        if (!file.exists() && !File(file.path + ".bak").exists()) return null
        return AtomicFile(file).readFully().also { file.setLastModified(System.currentTimeMillis()) }
    }

    @Synchronized fun write(lease: Lease, identity: String, bytes: ByteArray): Boolean {
        if (!current(lease) || bytes.size > limit) return false
        val file = file(lease.book, identity)
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try { output.write(bytes); atomic.finishWrite(output) }
        catch (failure: Throwable) { atomic.failWrite(output); throw failure }
        trim()
        return true
    }

    @Synchronized fun clearBook(bookUrl: String) {
        val book = digest(bookUrl)
        entries[book]?.let { it.revision++ }
        if (Files.isSymbolicLink(root.toPath())) { root.delete(); return }
        erase(File(root, book))
    }

    @Synchronized fun clearAll() {
        entries.values.forEach { it.revision++ }
        erase(root)
    }

    private fun file(book: String, identity: String): File {
        directory(root)
        val parent = File(root, book)
        directory(parent)
        return File(parent, digest(identity) + ".img").also { file ->
            listOf(file, File(file.path + ".bak"), File(file.path + ".new")).forEach {
                if (Files.isSymbolicLink(it.toPath())) throw IOException("Image cache path is not a regular file")
            }
        }
    }
    private fun directory(file: File) {
        if (Files.isSymbolicLink(file.toPath()) || (!file.isDirectory && !file.mkdirs())) throw IOException("Image cache directory is unavailable")
    }
    private fun erase(file: File) {
        if (!Files.isSymbolicLink(file.toPath()) && file.isDirectory) file.listFiles()?.forEach(::erase)
        if (file.exists() || Files.isSymbolicLink(file.toPath())) file.delete()
    }
    private fun trim() {
        val files = root.listFiles().orEmpty().filter { !Files.isSymbolicLink(it.toPath()) && it.isDirectory }
            .flatMap { it.listFiles().orEmpty().filter { child -> !Files.isSymbolicLink(child.toPath()) && child.isFile } }
        var bytes = files.sumOf { it.length() }
        var count = files.size
        files.sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.path }).forEach {
            if (bytes > limit || count > maxEntries) { val size = it.length(); if (it.delete()) { bytes -= size; count-- } }
        }
        root.listFiles().orEmpty().forEach {
            if (!Files.isSymbolicLink(it.toPath()) && it.isDirectory && it.listFiles().isNullOrEmpty() && entries[it.name] == null) it.delete()
        }
    }
    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
}
