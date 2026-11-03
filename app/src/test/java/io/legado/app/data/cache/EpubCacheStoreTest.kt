package io.legado.app.data.cache

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

class EpubCacheStoreTest {
    private fun archive(body: String): ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(ZipEntry("chapter")); zip.write(body.toByteArray()); zip.closeEntry()
        }
    }.toByteArray()

    @Test fun openArchivePreventsCleanupUntilClosedAndCacheHitsDoNotOpenTheProvider() {
        val root = Files.createTempDirectory("epub-cache").toFile()
        val leases = CacheResourceLeases()
        try {
            val store = EpubCacheStore(leases)
            store.open(root, "book.epub", false) { ByteArrayInputStream(archive("ALPHA")) }.use { zip ->
                assertEquals("ALPHA", zip.getInputStream(zip.getEntry("chapter")).reader().readText())
                assertEquals(0, ReferenceAwareCachePruner(leases).prune(root, { epubCacheKey(it.name) }, { false }))
                store.open(root, "book.epub", false) { error("Cache hit must not read provider") }.close()
            }
            assertEquals(1, ReferenceAwareCachePruner(leases).prune(root, { epubCacheKey(it.name) }, { false }))
            assertEquals(0, leases.entryCount())
        } finally { root.deleteRecursively() }
    }

    @Test fun failedOrInvalidReplacementPreservesThePreviousArchiveAndReleasesLeases() {
        val root = Files.createTempDirectory("epub-cache").toFile()
        val leases = CacheResourceLeases()
        try {
            val store = EpubCacheStore(leases)
            store.open(root, "book.epub", false) { ByteArrayInputStream(archive("original")) }.close()
            listOf<() -> InputStream>(
                { object : InputStream() { override fun read(): Int = throw IOException("Interrupted provider") } },
                { ByteArrayInputStream("not a ZIP".toByteArray()) },
            ).forEach { input ->
                try { store.open(root, "book.epub", true, input).close(); fail("Must reject failed replacement") }
                catch (_: IOException) { }
                store.open(root, "book.epub", false) { error("Must retain previous file") }.use { zip ->
                    assertEquals("original", zip.getInputStream(zip.getEntry("chapter")).reader().readText())
                }
                assertEquals(listOf("book.epub"), root.listFiles()!!.map { it.name })
                assertEquals(0, leases.entryCount())
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun concurrentRefreshesUseSeparateStagingAndAlreadyOpenArchivesRemainReadable() {
        val root = Files.createTempDirectory("epub-cache").toFile()
        val leases = CacheResourceLeases()
        val executor = Executors.newSingleThreadExecutor()
        val started = CountDownLatch(1); val resume = CountDownLatch(1)
        try {
            val store = EpubCacheStore(leases)
            val pending = executor.submit<String> {
                store.open(root, "book.epub", true) {
                    started.countDown(); check(resume.await(5, TimeUnit.SECONDS))
                    ByteArrayInputStream(archive("first"))
                }.use { it.getInputStream(it.getEntry("chapter")).reader().readText() }
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            store.open(root, "book.epub", true) { ByteArrayInputStream(archive("second")) }.use { second ->
                resume.countDown()
                assertEquals("first", pending.get(5, TimeUnit.SECONDS))
                assertEquals("second", second.getInputStream(second.getEntry("chapter")).reader().readText())
            }
            assertEquals(listOf("book.epub"), root.listFiles()!!.map { it.name })
            assertEquals(0, leases.entryCount())
        } finally { resume.countDown(); executor.shutdownNow(); root.deleteRecursively() }
    }

    @Test fun parentTraversalIsRejectedBeforeReadingTheProvider() {
        val root = Files.createTempDirectory("epub-cache").toFile()
        val leases = CacheResourceLeases()
        try {
            try {
                EpubCacheStore(leases).open(root, "../outside.epub", true) { error("Must not read provider") }
                fail("Must reject traversal")
            } catch (_: IllegalArgumentException) { }
            assertEquals(0, leases.entryCount())
        } finally { root.deleteRecursively() }
    }
}
