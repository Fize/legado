package io.legado.app.model.localBook

import org.junit.Assert.*
import org.junit.Test

class EpubResourceAddressTest {
    private val base = "OEBPS/Text/chapter.xhtml"
    private fun resolve(value: String) = EpubResourceAddress.resolve(base, value)
    @Test fun fragmentsRemainScopedToTheirOwningResource() {
        val first = resolve("#same")!!; val second = resolve("../Notes/notes.xhtml#same")!!
        assertEquals(base, first.path); assertEquals("same", first.fragment)
        assertEquals("OEBPS/Notes/notes.xhtml", second.path); assertEquals("same", second.fragment)
        assertNotEquals(first, second); assertNotEquals(first.href, second.href)
    }
    @Test fun relativeRootAndDotSegmentsResolveWithinTheContainer() {
        assertEquals("OEBPS/Notes/n.xhtml", resolve("../Notes/./n.xhtml")!!.path)
        assertEquals("OEBPS/n.xhtml", resolve("/OEBPS/n.xhtml")!!.path)
        assertEquals(base, resolve("?query=ignored#id")!!.path)
        assertEquals("id", resolve("?query=ignored#id")!!.fragment)
        assertNull(resolve("../../../outside.xhtml")); assertNull(resolve("/../outside.xhtml"))
    }
    @Test fun literalPlusUnicodeSpaceAndEncodedDelimitersRoundTripExactlyOnce() {
        val address = resolve("../Notes/中+文%20%2520%23.xhtml#注+%E9%87%8A%23")!!
        assertEquals("OEBPS/Notes/中+文 %20#.xhtml", address.path)
        assertEquals("注+释#", address.fragment)
        assertEquals(address, resolve(address.href))
        assertEquals(address, resolve("../Notes/中%2B文%20%2520%23.xhtml#注%2B%E9%87%8A%23"))
    }
    @Test fun malformedEscapesUtf8AndControlPathsAreRejected() {
        listOf("bad%", "bad%XX", "%FF.xhtml", "%E4%B8.xhtml", "%00.xhtml", "%5Cprivate.xhtml", "a\nb.xhtml",
            "#%FF", "#bad%", "%４１.xhtml", "%2E%2E/%2E%2E/%2E%2E/private").forEach { assertNull(it, resolve(it)) }
    }
    @Test fun deviceAndNetworkSchemesCannotBecomeContainerResources() {
        listOf("file:///private/a", "content://provider/a", "https://example.invalid/a", "javascript:alert(1)",
            "//example.invalid/a", "epub://other/a").forEach { assertNull(it, resolve(it)) }
    }
    @Test fun rawManifestPathsAreNotPercentDecodedAgain() {
        val raw = EpubResourceAddress.fromPath("OEBPS/literal%20+name.xhtml", "id")!!
        assertTrue(raw.href.contains("%2520+name"))
        assertEquals(raw, resolve(raw.href))
        assertNull(EpubResourceAddress.fromPath("/device/file")); assertNull(EpubResourceAddress.fromPath("../outside"))
    }
    @Test fun readingChaptersDefensivelyCopyResourceParts() {
        val source = mutableListOf(EpubReadingResource(resolve("#id")!!, "<p>Text</p>"))
        val chapter = EpubReadingChapter(source)
        source.clear(); assertEquals(1, chapter.parts.size)
        assertThrows(UnsupportedOperationException::class.java) { (chapter.parts as MutableList<*>).clear() }
    }
    @Test fun libraryIriEncodingPreservesEscapesButStillEncodesRawSpacesAndUnicode() {
        val encoded = me.ag2s.epublib.util.URLEncodeUtil.encodeHref("Notes/中+文 %20%2520.xhtml#注+1")
        assertEquals("Notes/%E4%B8%AD+%E6%96%87%20%20%2520.xhtml#%E6%B3%A8+1", encoded)
        assertEquals(encoded, me.ag2s.epublib.util.URLEncodeUtil.encodeHref(encoded))
        assertEquals("a%25wrong", me.ag2s.epublib.util.URLEncodeUtil.encodeHref("a%wrong"))
        assertEquals("a%25%EF%BC%94%EF%BC%91", me.ag2s.epublib.util.URLEncodeUtil.encodeHref("a%４１"))
    }
}
