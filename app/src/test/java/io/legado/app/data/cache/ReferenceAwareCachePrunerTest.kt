package io.legado.app.data.cache

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class ReferenceAwareCachePrunerTest {
    @Test fun freshReferencesProtectAFolderPublishedAfterAnEarlierSnapshot() {
        val root = Files.createTempDirectory("legado-prune").toFile()
        try {
            val leases = CacheResourceLeases(); val folder = root.resolve("new").apply { mkdir() }
            folder.resolve("chapter").writeText("ALPHA")
            val oldSnapshot = emptySet<String>(); val current = mutableSetOf("new")
            assertFalse("new" in oldSnapshot)
            assertEquals(0, ReferenceAwareCachePruner(leases).prune(root, { CacheResourceKey("book", it.name) }, { it.name in current }))
            assertEquals("ALPHA", folder.resolve("chapter").readText())
            current.clear()
            assertEquals(1, ReferenceAwareCachePruner(leases).prune(root, { CacheResourceKey("book", it.name) }, { it.name in current }))
        } finally { root.deleteRecursively() }
    }
    @Test fun activeUnreferencedResourcesAreRetainedUntilTheirLeaseCloses() {
        val root = Files.createTempDirectory("legado-prune").toFile()
        try {
            val leases = CacheResourceLeases(); val folder = root.resolve("active").apply { mkdir() }
            val key = CacheResourceKey("book", "active")
            leases.acquire(key).use {
                assertEquals(0, ReferenceAwareCachePruner(leases).prune(root, { key }, { false }))
                assertTrue(folder.exists())
            }
            assertEquals(1, ReferenceAwareCachePruner(leases).prune(root, { key }, { false }))
        } finally { root.deleteRecursively() }
    }
    @Test fun symbolicLinksCannotRemoveFilesOutsideTheManagedDirectory() {
        val root = Files.createTempDirectory("legado-prune").toFile()
        val outside = Files.createTempDirectory("legado-protected").toFile()
        try {
            val original = outside.resolve("original").apply { writeText("keep") }
            Files.createSymbolicLink(root.resolve("link").toPath(), outside.toPath())
            root.resolve("orphan").apply { mkdir(); Files.createSymbolicLink(resolve("nested").toPath(), outside.toPath()) }
            assertEquals(1, ReferenceAwareCachePruner(CacheResourceLeases()).prune(root, { CacheResourceKey("book", it.name) }, { false }))
            assertEquals("keep", original.readText()); assertTrue(root.resolve("link").exists())
        } finally { root.resolve("link").delete(); root.deleteRecursively(); outside.deleteRecursively() }
    }

    @Test fun publicationDuringReferenceValidationCannotBeDeletedByTheOldPlan() {
        val root = Files.createTempDirectory("legado-prune").toFile()
        try {
            val leases = CacheResourceLeases(); val folder = root.resolve("new").apply { mkdir() }
            val key = CacheResourceKey("book", "new")
            val deleted = ReferenceAwareCachePruner(leases).prune(root, { key }, {
                // The reference query began before the new writer committed its library row.
                leases.use(key, mutating = true) { folder.resolve("chapter").writeText("ALPHA") }
                false
            })
            assertEquals(0, deleted)
            assertEquals("ALPHA", folder.resolve("chapter").readText())
        } finally { root.deleteRecursively() }
    }

}
