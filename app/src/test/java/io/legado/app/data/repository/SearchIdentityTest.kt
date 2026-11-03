package io.legado.app.data.repository

import org.junit.Assert.*
import org.junit.Test

class SearchIdentityTest {
    @Test fun compoundKeysAreUnambiguousAndStableForUnicode() {
        assertNotEquals(searchResultKey("ab", "c"), searchResultKey("a", "bc"))
        assertNotEquals(searchResultKey("书_作者", ""), searchResultKey("书", "作者_"))
        assertEquals(searchResultKey("漫画😀", "作者"), searchResultKey("漫画😀", "作者"))
        assertEquals(64, searchResultKey("", "").length)
    }
    @Test fun rankingPreservesExactKindContainsAndUnrelatedPriority() {
        assertEquals(0, searchResultRank("Book", "Author", null, "Book"))
        assertEquals(0, searchResultRank("Book", "Author", null, "Author"))
        assertEquals(1, searchResultRank("Book", "Author", "Fantasy", "Fantasy"))
        assertEquals(2, searchResultRank("Book title", "Author", null, "Book"))
        assertEquals(3, searchResultRank("Book", "Author", null, "Other"))
    }
    @Test fun sourceScopesPreserveUrlsAndGroupScopesRemoveDuplicates() {
        val source = SearchScopeSpec.source("Name::with:colon", "https://host:8080/path::part")
        assertTrue(source.isSource)
        assertEquals("Namewithcolon", source.sourceLabel)
        assertEquals("https://host:8080/path::part", source.sourceUrl)
        assertTrue(source.groups.isEmpty())
        assertEquals(listOf("one", "two"), SearchScopeSpec("one, two,one,, ").groups)
        assertEquals("one,two", SearchScopeSpec.groups(listOf("one", "two", "one")).raw)
        assertTrue(SearchScopeSpec("").isAll)
    }
}
