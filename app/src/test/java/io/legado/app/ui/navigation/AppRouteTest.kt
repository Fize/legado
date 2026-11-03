package io.legado.app.ui.navigation

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppRouteTest {

    private val json = Json {
        encodeDefaults = true
        classDiscriminator = "route"
    }

    @Test
    fun `all top-level destinations are stable route keys`() {
        assertEquals(setOf(BookshelfRoute, ExploreRoute, MyRoute), TopLevelRoutes)
        assertTrue(TopLevelRoutes.all { it is TopLevelRoute })
    }

    @Test
    fun `reader route survives serialization`() {
        val route: AppRoute = MangaReaderRoute(
            bookUrl = "https://example.test/book/1",
            chapterIndex = 12,
        )

        val restored = json.decodeFromString<AppRoute>(json.encodeToString(route))

        assertEquals(route, restored)
    }

    @Test
    fun `book toc route survives serialization`() {
        val route: AppRoute = BookTocRoute("local://book/42")

        val restored = json.decodeFromString<AppRoute>(json.encodeToString(route))

        assertEquals(route, restored)
    }

    @Test
    fun `external import route survives serialization`() {
        val route: AppRoute = ImportRoute(
            payload = "legado://import/source?id=1",
            kind = ImportKind.BookSource,
        )

        val restored = json.decodeFromString<AppRoute>(json.encodeToString(route))

        assertEquals(route, restored)
    }

    @Test
    fun `online import URI route survives serialization`() {
        val route: AppRoute = OnlineImportRoute(
            "legado://import/bookSource?src=https%3A%2F%2Fexample.test%2Fsource.json"
        )

        val restored = json.decodeFromString<AppRoute>(json.encodeToString(route))

        assertEquals(route, restored)
    }

    @Test
    fun `external URL confirmation route survives serialization`() {
        val route: AppRoute = OpenUrlConfirmRoute(
            uri = "https://example.test/path",
            mimeType = "text/html",
            sourceOrigin = "https://example.test/source",
            sourceName = "Example",
            sourceType = 1,
        )

        val restored = json.decodeFromString<AppRoute>(json.encodeToString(route))

        assertEquals(route, restored)
    }

    @Test
    fun `verification code route survives serialization`() {
        val route: AppRoute = VerificationCodeRoute(
            imageUrl = "https://example.test/captcha.png",
            sourceOrigin = "https://example.test/source",
            sourceName = "Example",
            sourceType = 1,
            verificationResultKey = "request-42",
        )

        val restored = json.decodeFromString<AppRoute>(json.encodeToString(route))

        assertEquals(route, restored)
    }

    @Test
    fun `book information edit route survives serialization`() {
        val route: AppRoute = EditBookInfoRoute("https://example.test/book/42")

        val restored = json.decodeFromString<AppRoute>(json.encodeToString(route))

        assertEquals(route, restored)
    }

    @Test
    fun `bookshelf management route preserves selected group`() {
        val route: AppRoute = BookshelfManageRoute(groupId = 4096L)

        val restored = json.decodeFromString<AppRoute>(json.encodeToString(route))

        assertEquals(route, restored)
    }

    @Test
    fun `content search route preserves its result handoff key`() {
        val route: AppRoute = SearchContentRoute(
            bookUrl = "https://example.test/book/42",
            query = "needle",
            searchResultKey = "search-results-42",
            initialIndex = 3,
        )

        val restored = json.decodeFromString<AppRoute>(json.encodeToString(route))

        assertEquals(route, restored)
    }

    @Test
    fun `JavaScript source edit route survives serialization`() {
        val route: AppRoute = JavaScriptSourceEditRoute("https://example.test/source")

        val restored = json.decodeFromString<AppRoute>(json.encodeToString(route))

        assertEquals(route, restored)
    }

    @Test
    fun `QR scanner route survives serialization`() {
        val restored = json.decodeFromString<AppRoute>(json.encodeToString<AppRoute>(QrCodeRoute))

        assertEquals(QrCodeRoute, restored)
    }

    @Test
    fun `source login route preserves its typed target`() {
        val route: AppRoute = SourceLoginRoute(
            target = SourceLoginTarget.HttpTts,
            key = "12",
            bookUrl = "https://example.test/book",
        )

        val restored = json.decodeFromString<AppRoute>(json.encodeToString(route))

        assertEquals(route, restored)
    }

    @Test
    fun `about destination survives serialization for external app entry`() {
        val restored = json.decodeFromString<AppRoute>(json.encodeToString<AppRoute>(AboutRoute))

        assertEquals(AboutRoute, restored)
    }

    @Test
    fun `theme settings destination survives serialization`() {
        val route: AppRoute = SettingsRoute(SettingsSection.Theme)

        val restored = json.decodeFromString<AppRoute>(json.encodeToString(route))

        assertEquals(route, restored)
    }

    @Test
    fun `backup settings and app logs destinations survive serialization`() {
        val routes = listOf<AppRoute>(SettingsRoute(SettingsSection.Backup), AppLogRoute)

        routes.forEach { route ->
            assertEquals(route, json.decodeFromString<AppRoute>(json.encodeToString(route)))
        }
    }
}
