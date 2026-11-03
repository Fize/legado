package io.legado.app.help.glide

import org.junit.Assert.*
import org.junit.Test

class ImageSourceCacheIdentityTest {
    private fun key(source: String? = null, book: String? = null, manga: Boolean = false,
        headers: Map<String,String> = emptyMap()) = imageSourceCacheIdentity("https://example.com/image", headers, source, book, manga)
    @Test fun credentialsAndSourceIdentityArePartOfTheDiskKey() {
        assertNotEquals(key("first"), key("second"))
        assertNotEquals(key(headers=mapOf("Authorization" to "first")), key(headers=mapOf("Authorization" to "second")))
    }
    @Test fun bookAndReaderModeDoNotShareSourceData() {
        assertNotEquals(key("source", "first"), key("source", "second"))
        assertNotEquals(key("source", "book", true), key("source", "book", false))
    }
    @Test fun headerOrderingDoesNotChangeTheKeyAndSecretsAreNotIncludedInPlainText() {
        val first=key(headers=linkedMapOf("B" to "secret-b", "A" to "secret-a"))
        assertEquals(first,key(headers=linkedMapOf("A" to "secret-a", "B" to "secret-b")))
        assertEquals(64,first.length);assertFalse(first.contains("secret"))
    }
    @Test fun nullEmptyAndAmbiguousDelimitersRemainDistinct() {
        assertNotEquals(key(null),key(""))
        assertNotEquals(key("a,b", "c"),key("a", "b,c"))
        assertNotEquals(key("汉字", "😀"),key("汉", "字😀"))
    }
}
