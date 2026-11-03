package io.legado.app.lib.cronet

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/** Existing downloads also need this: target 37 rejects writable System.load files. */
internal fun prepareVerifiedCronetLibrary(file: File, expectedMd5: String): File {
    if (!Regex("[0-9a-fA-F]{32}").matches(expectedMd5) || !file.isFile ||
        !cronetLibraryMd5(file).equals(expectedMd5, ignoreCase = true)) {
        throw IOException("Cronet native library checksum mismatch")
    }
    if (!file.setReadOnly() || file.canWrite()) {
        throw IOException("Cannot make Cronet native library read-only")
    }
    return file
}

/** Never truncate a previously installed library, including an existing read-only copy. */
internal fun installVerifiedCronetLibrary(source: File, target: File, expectedMd5: String) {
    if (source.canonicalFile == target.canonicalFile) {
        prepareVerifiedCronetLibrary(target, expectedMd5)
        return
    }
    if (target.isDirectory) throw IOException("Cronet library target is a directory")
    val parent = target.parentFile ?: throw IOException("Cronet library directory is missing")
    if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Cannot create Cronet library directory")
    val pending = File.createTempFile(".cronet-", ".so.tmp", parent)
    try {
        FileOutputStream(pending).use { output ->
            // The open descriptor can write; the path is read-only for its entire published lifetime.
            if (!pending.setReadOnly() || pending.canWrite()) throw IOException("Cannot protect pending Cronet library")
            source.inputStream().use { input -> input.copyTo(output, 512 * 1024) }
            output.flush()
            output.fd.sync()
        }
        prepareVerifiedCronetLibrary(pending, expectedMd5)
        if (!pending.renameTo(target)) throw IOException("Cannot install verified Cronet native library")
    } finally {
        pending.delete()
    }
}

private fun cronetLibraryMd5(file: File): String {
    val digest = MessageDigest.getInstance("MD5")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
