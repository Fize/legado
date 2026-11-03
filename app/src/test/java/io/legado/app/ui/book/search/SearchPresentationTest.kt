package io.legado.app.ui.book.search

import io.legado.app.data.entities.*
import io.legado.app.data.repository.*
import io.legado.app.help.book.ReadRecordIndex
import org.junit.Assert.*
import org.junit.Test

class SearchPresentationTest {
    private val row = SearchSessionResult("session", "key", 0, "url", "source", "Source", 0, "Book", "Author", "Fantasy", null, null, null,
        null, "", null, null, -1, -1, 0, 0)
    private val session = SearchSession("session", "Book", "", false, 0, 0, 1, 1, SearchSessionStatus.Ready, false, 1, 1, 0, null, 0)
    private val frame = SearchSessionFrame(session, listOf(SearchResultSnapshot(row, listOf(row, row.copy(origin = "other")))))
    private val emptyLibrary = SearchLibraryIndex(emptySet(), emptySet(), emptySet())
    private val preferences = SearchPreferences("", false, true, "", 4)
    private val records = ReadRecordIndex.of(listOf(ReadRecordBook("Book", "Author")))

    @Test fun indicatorMatchesAuthorAndReactsToTheTypedPreference() {
        assertTrue(presentSearchResults(frame, emptyLibrary, records, preferences).single().hasReadRecord)
        assertFalse(presentSearchResults(frame, emptyLibrary, records, preferences.copy(showReadRecord = false)).single().hasReadRecord)
        val other = ReadRecordIndex.of(listOf(ReadRecordBook("Book", "Different")))
        assertFalse(presentSearchResults(frame, emptyLibrary, other, preferences).single().hasReadRecord)
    }
    @Test fun libraryIdentitySuppressesReadRecordAndCountsDistinctOrigins() {
        val library = SearchLibraryIndex(emptySet(), setOf(SearchBookIdentity("Book", "Author")), setOf("Book"))
        val result = presentSearchResults(frame, library, records, preferences).single()
        assertTrue(result.inBookshelf); assertFalse(result.hasReadRecord); assertEquals(2, result.originCount)
        assertFalse(presentSearchResults(frame, library.copy(identities = emptySet()), records, preferences).single().inBookshelf)
    }
    @Test fun unknownAuthorsUseNameFallbackWhileKnownAuthorsRemainSeparate() {
        val library = SearchLibraryIndex(emptySet(), setOf(SearchBookIdentity("Book", "Other")), setOf("Book"))
        assertFalse(library.contains(row)); assertTrue(library.contains(row.copy(author = "")))
        assertTrue(library.copy(urls = setOf("url")).contains(row))
    }
    @Test fun filtersApplyToNameAuthorAndKindWithoutChangingPersistedResults() {
        listOf(" book ", "AUTHOR", "Fantasy\n\nFantasy").forEach { filter ->
            assertTrue(presentSearchResults(frame, emptyLibrary, records, preferences.copy(resultFilter = filter)).isEmpty())
        }
        assertEquals(1, presentSearchResults(frame, emptyLibrary, records, preferences.copy(resultFilter = "\n \t")).size)
        assertEquals(1, frame.results.size)
        assertEquals(1, presentSearchResults(frame, emptyLibrary, records, preferences.copy(resultFilter = "other")).size)
    }
}
