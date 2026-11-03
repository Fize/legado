package io.legado.app.ui.navigation

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class MangaReaderRouteTest {
    @Test fun aRestoredReaderRetainsItsRequestIdentityAndExplicitLaunchPosition() {
        val route = MangaReaderRoute("book-key", 2, 3, inBookshelf = false, chapterChanged = true, returnToDetails = true)
        val encoded = Json.encodeToString<AppRoute>(route)
        assertTrue(encoded.contains(route.requestId))
        assertEquals(route, Json.decodeFromString<AppRoute>(encoded))
        assertNotEquals(route.requestId, MangaReaderRoute("book-key").requestId)
    }
    @Test fun aRestoredCatalogKeepsTheExactResultRequestKey() {
        val route = BookTocRoute("changed-source-book", readerCatalogRequestId = "result-key")
        assertEquals(route, Json.decodeFromString<AppRoute>(Json.encodeToString<AppRoute>(route)))
    }
}
