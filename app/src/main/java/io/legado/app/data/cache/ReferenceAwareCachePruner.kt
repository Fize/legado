package io.legado.app.data.cache

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/** Files are candidates, never proof of abandonment. References are re-read at removal time. */
class ReferenceAwareCachePruner(private val leases: CacheResourceLeases) {
    fun prune(
        root: File,
        key: (File) -> CacheResourceKey,
        isReferenced: (File) -> Boolean,
        checkCancelled: () -> Unit = {},
    ): Int {
        val canonicalRoot = root.canonicalFile
        val candidates = root.listFiles().orEmpty()
        var removed = 0
        candidates.forEach { candidate ->
            checkCancelled()
            // Never traverse a symlink into a different resource or outside the managed root.
            if (Files.isSymbolicLink(candidate.toPath()) || candidate.canonicalFile.parentFile != canonicalRoot) return@forEach
            leases.plan(key(candidate)).use { plan ->
                // Database reads must stay outside resource locks: a library transaction may
                // publish cache files. The retained plan detects publications during this read.
                val referenced = isReferenced(candidate)
                if (!referenced) plan.removeIfUnchanged {
                    checkCancelled()
                    if (Files.exists(candidate.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                        deleteTree(candidate, checkCancelled)
                        removed++
                    }
                }
            }
        }
        return removed
    }
}

internal fun deleteTree(file: File, checkCancelled: () -> Unit = {}) {
    // walkFileTree does not follow symbolic links unless FOLLOW_LINKS is requested.
    Files.walkFileTree(file.toPath(), object : SimpleFileVisitor<Path>() {
        override fun visitFile(path: Path, attributes: BasicFileAttributes): FileVisitResult {
            checkCancelled(); Files.delete(path); return FileVisitResult.CONTINUE
        }
        override fun postVisitDirectory(path: Path, error: IOException?): FileVisitResult {
            if (error != null) throw error
            checkCancelled(); Files.delete(path); return FileVisitResult.CONTINUE
        }
    })
}
