package io.legado.app.ui.navigation

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class BookInfoResultRouteTest {
    @Test fun everyReadingKindKeepsItsOwnedDetailReturnKey() {
        val routes = listOf<AppRoute>(PlatformTextReaderRoute("book", "child"),
            TextReaderRoute("book", requestId = "child", returnToDetails = true),
            AudioPlayerRoute("book", "child", true), VideoPlayerRoute("book", requestId = "child", returnToDetails = true))
        routes.forEach { assertEquals(it, Json.decodeFromString<AppRoute>(Json.encodeToString<AppRoute>(it))) }
    }
    @Test fun detailRoundTripKeepsItsEntryIdentityAndIndependentSearchPreviewKey() {
        val route = BookInfoRoute("book", requestId = "detail-request", previewKey = "search-preview")
        assertEquals(route, Json.decodeFromString<AppRoute>(Json.encodeToString<AppRoute>(route)))
        assertNotEquals(route.requestId, route.previewKey)
    }
    @Test fun aNewDetailEntryUsesItsRequestIdentityAsItsDefaultPreviewKey() {
        val first = BookInfoRoute("book")
        val second = BookInfoRoute("book")
        assertEquals(first.requestId, first.previewKey)
        assertNotEquals(first.requestId, second.requestId)
        assertEquals(first, Json.decodeFromString<AppRoute>(Json.encodeToString<AppRoute>(first)))
    }
    @Test fun catalogRoundTripRetainsOnlyItsSmallReturnKey() {
        val route = BookTocRoute("book", bookInfoChildRequestId = "catalog-request")
        assertEquals(route, Json.decodeFromString<AppRoute>(Json.encodeToString<AppRoute>(route)))
        assertNull(route.readerCatalogRequestId)
    }
    @Test(expected = IllegalArgumentException::class)
    fun aCatalogCannotReturnToTwoDifferentOwners() {
        BookTocRoute("book", readerCatalogRequestId = "reader-result", bookInfoChildRequestId = "detail-result")
    }
    @Test fun editorRoundTripRetainsItsExactReturnOwner() {
        val route = EditBookInfoRoute("book", bookInfoChildRequestId = "edit-request")
        assertEquals(route, Json.decodeFromString<AppRoute>(Json.encodeToString<AppRoute>(route)))
    }
}
