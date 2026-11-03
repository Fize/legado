package io.legado.app.data.cache

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class TextReaderImageStoreTest {
    private val parent = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
        "reader-image-store-${UUID.randomUUID()}").apply { mkdirs() }
    private val root = File(parent, "images")
    @After fun cleanup() { parent.deleteRecursively() }

    @Test fun atomicBytesSurviveANewStoreInstanceWithoutSharingMutableArrays() {
        val store = TextReaderImageStore(root, 100)
        val data = byteArrayOf(1, 2, 3)
        store.open("book").use { assertTrue(store.write(it, "image", data)) }
        data[0] = 9
        val restored = TextReaderImageStore(root, 100)
        restored.open("book").use {
            val first = restored.read(it, "image")!!
            assertArrayEquals(byteArrayOf(1, 2, 3), first)
            first[0] = 9
            assertArrayEquals(byteArrayOf(1, 2, 3), restored.read(it, "image"))
        }
    }
    @Test fun clearingABookRevokesLateWritesButDoesNotAffectAnotherBook() {
        val store = TextReaderImageStore(root, 100)
        store.open("first").use { first -> store.open("second").use { second ->
            store.write(first, "image", byteArrayOf(1)); store.write(second, "image", byteArrayOf(2))
            store.clearBook("first")
            assertFalse(first.isCurrent()); assertFalse(store.write(first, "late", byteArrayOf(3)))
            assertNull(store.read(first, "image")); assertArrayEquals(byteArrayOf(2), store.read(second, "image"))
            store.open("first").use { fresh ->
                assertNull(store.read(fresh, "image")); assertTrue(store.write(fresh, "new", byteArrayOf(4)))
            }
        } }
    }
    @Test fun clearingAllRevokesEveryOutstandingLeaseAndAllowsFreshRequests() {
        val store = TextReaderImageStore(root, 100)
        store.open("first").use { first -> store.open("second").use { second ->
            store.write(first, "image", byteArrayOf(1)); store.clearAll()
            assertFalse(first.isCurrent()); assertFalse(second.isCurrent())
            assertFalse(store.write(second, "image", byteArrayOf(2)))
            store.open("first").use { assertTrue(store.write(it, "image", byteArrayOf(3))) }
        } }
    }
    @Test fun closingALeaseIsIdempotentAndCannotPublishAfterwards() {
        val store = TextReaderImageStore(root, 100)
        val lease = store.open("book")
        lease.close(); lease.close()
        assertFalse(store.write(lease, "image", byteArrayOf(1)))
        assertFalse(lease.isCurrent())
    }
    @Test fun byteAndEntryQuotasEvictOldFilesAcrossBooks() {
        val store = TextReaderImageStore(root, 5, maxEntries = 2)
        store.open("first").use { store.write(it, "a", byteArrayOf(1, 1)) }
        root.walkTopDown().filter { it.isFile }.forEach { assertTrue(it.setLastModified(1)) }
        store.open("second").use { store.write(it, "b", byteArrayOf(2, 2)) }
        store.open("third").use { store.write(it, "c", byteArrayOf(3, 3)) }
        assertTrue(root.walkTopDown().filter { it.isFile }.sumOf { it.length() } <= 5)
        assertTrue(root.walkTopDown().count { it.isFile } <= 2)
        store.open("first").use { assertNull(store.read(it, "a")) }
        store.open("third").use { assertArrayEquals(byteArrayOf(3, 3), store.read(it, "c")) }
    }
    @Test fun anOversizedImageIsNotCachedOrAllowedToEvictTheEntireCache() {
        val store = TextReaderImageStore(root, 2)
        store.open("book").use {
            assertTrue(store.write(it, "small", byteArrayOf(1)))
            assertFalse(store.write(it, "large", byteArrayOf(1, 2, 3)))
            assertArrayEquals(byteArrayOf(1), store.read(it, "small"))
        }
    }
    @Test fun theEntryCountIsBoundedEvenWhenImagesAreVerySmall() {
        val store = TextReaderImageStore(root, 100, maxEntries = 2)
        store.open("book").use { lease ->
            store.write(lease, "first", byteArrayOf(1))
            root.walkTopDown().filter { it.isFile }.forEach { it.setLastModified(1) }
            store.write(lease, "second", byteArrayOf(2)); store.write(lease, "third", byteArrayOf(3))
            assertEquals(2, root.walkTopDown().count { it.isFile })
            assertNull(store.read(lease, "first"))
        }
    }
    @Test fun untrustedKeysCannotEscapeThePrivateDirectory() {
        val store = TextReaderImageStore(root, 100)
        store.open("../../outside").use { store.write(it, "../../image", byteArrayOf(1)) }
        assertFalse(File(parent, "outside").exists())
        assertEquals(1, root.walkTopDown().count { it.isFile && it.extension == "img" })
    }
    @Test fun aSymlinkRootIsRejectedAndClearingItDoesNotFollowItsTarget() {
        val outside = File(parent, "outside").apply { mkdirs() }
        val marker = File(outside, "keep").apply { writeText("owned fixture") }
        Files.createSymbolicLink(root.toPath(), outside.toPath())
        val store = TextReaderImageStore(root, 100)
        store.open("book").use { lease -> assertThrows(IOException::class.java) { store.write(lease, "image", byteArrayOf(1)) } }
        store.clearAll()
        assertTrue(marker.exists()); assertEquals("owned fixture", marker.readText())
    }
}
