package io.legado.app.data.cache

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.utils.externalFiles
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EpubCacheStoreDeviceTest {
    @Test fun externalStoragePublishesAtomicallyAndAnOpenArchiveRetainsItsOriginalBytes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.externalFiles, "epub-store-test-${UUID.randomUUID()}").apply { mkdirs() }
        val leases = CacheResourceLeases()
        val store = EpubCacheStore(leases)
        fun archive(body: String): ByteArray = ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { zip ->
                zip.putNextEntry(ZipEntry("chapter")); zip.write(body.toByteArray()); zip.closeEntry()
            }
        }.toByteArray()
        try {
            store.open(root, "book.epub", false) { ByteArrayInputStream(archive("original")) }.use { original ->
                store.open(root, "book.epub", true) { ByteArrayInputStream(archive("replacement")) }.use { replacement ->
                    assertEquals("replacement", replacement.getInputStream(replacement.getEntry("chapter")).reader().readText())
                }
                assertEquals("original", original.getInputStream(original.getEntry("chapter")).reader().readText())
                assertEquals(0, ReferenceAwareCachePruner(leases).prune(root, { epubCacheKey(it.name) }, { false }))
                try {
                    store.open(root, "book.epub", true) { ByteArrayInputStream("invalid".toByteArray()) }.close()
                    fail("Invalid provider bytes must not replace a usable ZIP")
                } catch (_: IOException) { }
                store.open(root, "book.epub", false) { error("Must use cached replacement") }.use { zip ->
                    assertEquals("replacement", zip.getInputStream(zip.getEntry("chapter")).reader().readText())
                }
                assertEquals(listOf("book.epub"), root.listFiles()!!.map { it.name })
            }
            assertEquals(1, ReferenceAwareCachePruner(leases).prune(root, { epubCacheKey(it.name) }, { false }))
        } finally { root.deleteRecursively() }
    }
}
