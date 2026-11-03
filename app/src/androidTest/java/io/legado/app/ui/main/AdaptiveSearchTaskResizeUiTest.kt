package io.legado.app.ui.main

import android.content.pm.PackageManager
import android.content.Intent
import android.os.ParcelFileDescriptor
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import fi.iki.elonen.NanoHTTPD
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.rule.SearchRule
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.AppActivity
import io.legado.app.ui.navigation.BookshelfRoute
import io.legado.app.ui.navigation.ExploreRoute
import io.legado.app.ui.navigation.MyRoute
import io.legado.app.ui.navigation.SearchRoute
import io.legado.app.utils.defaultSharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/** Keeps the production LegadoApp, adaptive navigation, NavDisplay and SearchScreen intact. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 30)
class AdaptiveSearchTaskResizeUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val id = UUID.randomUUID().toString()
    private val query = "Window $id"
    private val group = "window-search-$id"
    private val shelfPrefix = "Rail boundary $id"
    private val preferences = context.defaultSharedPreferences
    private val old = listOf(PreferKey.cronet, PreferKey.autoBackup, PreferKey.searchResultFilter, PreferKey.precisionSearch)
        .associateWith { preferences.all[it] }
    private val oldLayout = AppConfig.bookshelfLayout
    private val oldMargin = AppConfig.bookshelfMargin
    private val oldShowBookName = AppConfig.showBookname
    private lateinit var server: NanoHTTPD
    private lateinit var source: BookSource
    private var shelfGroup: BookGroup? = null
    private var shelfBooks = emptyList<Book>()
    private var activity: AppActivity? = null
    private var caseName = "not-opened"
    private val calls = AtomicInteger()
    private val trace = JSONArray()
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
    private fun resumed(): AppActivity? {
        var result: AppActivity? = null
        instrumentation.runOnMainSync {
            result = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<AppActivity>().firstOrNull()
        }
        return result
    }

    @Before fun setup() {
        Assume.assumeTrue("Requires a freeform-capable device", context.packageManager
            .hasSystemFeature(PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT))
        preferences.edit().putBoolean(PreferKey.cronet, false).putBoolean(PreferKey.autoBackup, false)
            .putBoolean(PreferKey.precisionSearch, false).putString(PreferKey.searchResultFilter, "").commit()
        server = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response {
                calls.incrementAndGet()
                val html = if (session.parameters["page"]?.firstOrNull() == "1") (0..71).joinToString("") {
                    "<article><h2>$query book $it</h2><span>Author $it</span><a href='/book/$it'>Book</a></article>"
                } else "<main>No more</main>"
                return newFixedLengthResponse(html)
            }
        }.apply { start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
        val base = "http://127.0.0.1:${server.listeningPort}"
        source = BookSource(base, "Window search $id", bookSourceGroup = group,
            searchUrl = "$base/search?page={{page}}&key={{key}}",
            exploreUrl = """<js>JSON.stringify([{title:'Rail category',url:'https://rail-boundary.invalid/category'}]);</js>""",
            ruleSearch = SearchRule(bookList = "article", name = "h2@text", author = "span@text", bookUrl = "a@href"))
        appDb.bookSourceDao.insert(source)
    }

    @After fun cleanup() {
        instrumentation.runOnMainSync { activity?.takeUnless { it.isDestroyed }?.finish() }
        if (::server.isInitialized) server.stop()
        shelfBooks.forEach { appDb.bookDao.delete(it) }
        shelfGroup?.let { appDb.bookGroupDao.delete(it) }
        if (::source.isInitialized) appDb.bookSourceDao.delete(source)
        appDb.searchKeywordDao.get(query)?.let { appDb.searchKeywordDao.delete(it) }
        appDb.openHelper.writableDatabase.apply {
            execSQL("DELETE FROM search_screen_drafts WHERE scope = ?", arrayOf(group))
            execSQL("DELETE FROM search_sessions WHERE scope = ?", arrayOf(group))
        }
        preferences.edit().apply { old.forEach { (key, value) -> when (value) {
            null -> remove(key); is Boolean -> putBoolean(key, value); is String -> putString(key, value)
        } } }.commit()
        AppConfig.bookshelfLayout = oldLayout
        AppConfig.bookshelfMargin = oldMargin
        AppConfig.showBookname = oldShowBookName
        File(context.cacheDir, "adaptive-search-task-resize-$caseName.json").writeText(trace.toString(2))
    }

    private fun open(text: String) {
        caseName = if (text.isEmpty()) "draft" else "results"
        // UiAutomation executes an argv command, not a shell that strips JSON quotes.
        val route = SearchRoute(text, group)
        val uri = AppActivity.startIntent(context, route).toUri(Intent.URI_INTENT_SCHEME)
        check(uri.none(Char::isWhitespace))
        val result = shell("am start --windowingMode 5 $uri")
        check(!result.contains("Error")) { result }
        compose.waitUntil(20_000) { activity = resumed(); activity?.isInMultiWindowMode == true }
        assertEquals("Typed launch extra: ${activity!!.intent.getStringExtra("initialDestination")}", route, activity!!.initialDestination)
        compose.waitUntil(20_000) { runCatching { compose.onNodeWithTag("search_query").assertIsEnabled() }.isSuccess }
    }

    private fun openBookshelfRoot() {
        val previous = checkNotNull(activity)
        instrumentation.runOnMainSync { previous.finish() }
        compose.waitUntil(10_000) { previous.isDestroyed || activity == null }
        val uri = AppActivity.startIntent(context, BookshelfRoute).toUri(Intent.URI_INTENT_SCHEME)
        check(uri.none(Char::isWhitespace))
        check(!shell("am start --windowingMode 5 $uri").contains("Error"))
        compose.waitUntil(20_000) {
            activity = resumed()
            activity != null && activity !== previous && activity?.isInMultiWindowMode == true
        }
        compose.waitUntil(10_000) { activity?.currentDestination == BookshelfRoute }
        compose.waitUntil(10_000) {
            runCatching {
                compose.onNodeWithTag("bookshelf_items").assertExists()
                compose.onAllNodesWithText(shelfPrefix, substring = true).fetchSemanticsNodes().isNotEmpty()
            }.isSuccess
        }
        compose.onAllNodesWithText(shelfPrefix, substring = true).onFirst().performClick()
        compose.waitUntil(10_000) {
            runCatching { compose.onNodeWithText("$shelfPrefix 0").assertExists() }.isSuccess
        }
    }

    private fun nav(label: Int) = compose.onNode(isSelectable() and hasText(checkNotNull(activity).getString(label)))
    private fun texts(node: SemanticsNode): List<String> = node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + node.children.flatMap(::texts)
    private fun grid() = compose.onNodeWithTag("search_results").fetchSemanticsNode()
    private fun navigationMode(): String {
        val buttons = listOf(R.string.bookshelf, R.string.discovery, R.string.my).map { label ->
            nav(label).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        }
        return if (abs(buttons.last().center.x - buttons.first().center.x) >
            abs(buttons.last().center.y - buttons.first().center.y)) "bar" else "rail"
    }
    private fun assertContentOutsideRail(tag: String) {
        val content = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        if (navigationMode() == "rail") {
            val railButtons = listOf(R.string.bookshelf, R.string.discovery, R.string.my).map { label ->
                nav(label).fetchSemanticsNode().boundsInRoot
            }
            assertTrue(
                "Content $tag starts before the side navigation: content=$content buttons=$railButtons",
                content.left >= railButtons.maxOf { it.right } - 1f,
            )
        }
    }
    private fun resize(widthDp: Int, heightDp: Int = 600): String {
        val host = checkNotNull(activity)
        val density = host.resources.displayMetrics.density
        val width = (widthDp * density).toInt()
        val height = (heightDp * density).toInt()
        val result = shell("am task resize ${host.taskId} 50 50 ${50 + width} ${50 + height}")
        check(!result.contains("Error") && !result.contains("Exception")) { result }
        compose.waitUntil(10_000) {
            var measured = 0; var measuredHeight = 0
            instrumentation.runOnMainSync { measured = host.window.decorView.width; measuredHeight = host.window.decorView.height }
            abs(measured - width) <= 2 * density && abs(measuredHeight - height) <= 2 * density && host.resources.configuration.screenWidthDp == widthDp
        }
        compose.waitForIdle()
        assertSame("Window resize recreated the host", host, resumed())
        val buttons = listOf(R.string.bookshelf, R.string.discovery, R.string.my).map { label ->
            nav(label).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        }
        buttons.forEach { assertTrue(it.left >= 0 && it.right <= width + 1) }
        val mode = if (abs(buttons.last().center.x - buttons.first().center.x) > abs(buttons.last().center.y - buttons.first().center.y)) "bar" else "rail"
        assertEquals("Wrong adaptive navigation for $widthDp x $heightDp", if (widthDp < 600 || heightDp < 480) "bar" else "rail", mode)
        trace.put(JSONObject().put("requested_width_dp", widthDp).put("requested_height_dp", heightDp)
            .put("configuration_width_dp", host.resources.configuration.screenWidthDp).put("actual_width_px", host.window.decorView.width)
            .put("configuration_height_dp", host.resources.configuration.screenHeightDp).put("actual_height_px", host.window.decorView.height)
            .put("activity_identity", System.identityHashCode(host)).put("task_id", host.taskId)
            .put("navigation", mode).put("requests", calls.get()).put("route", host.currentDestination.toString()))
        return mode
    }

    private fun insertShelfBooks() {
        val groupId = (0..60).map { 1L shl it }.first { appDb.bookGroupDao.getByID(it) == null }
        shelfGroup = BookGroup(groupId, shelfPrefix)
        shelfBooks = (0..47).map {
            Book(bookUrl = "rail-boundary-$id-$it", name = "$shelfPrefix $it", group = groupId, order = it)
        }
        appDb.bookGroupDao.insert(checkNotNull(shelfGroup))
        shelfBooks.forEach { appDb.bookDao.insert(it) }
        AppConfig.bookshelfLayout = 3
        AppConfig.bookshelfMargin = 16
        AppConfig.showBookname = 0
    }

    private fun textsIn(node: SemanticsNode): List<String> =
        node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + node.children.flatMap(::textsIn)

    private fun assertSearchCards(widthDp: Int, expectedAnchor: String) {
        resize(widthDp)
        assertContentOutsideRail("search_screen")
        compose.onNodeWithTag("search_query").assertTextContains(query)
        compose.onNodeWithText(expectedAnchor).assertIsDisplayed()
        val list = grid()
        val cells = list.children.filter { it.boundsInRoot.width > 0 && it.boundsInRoot.height > 0 }
        assertTrue("Search results are missing at $widthDp dp", cells.isNotEmpty())
        val density = checkNotNull(activity).resources.displayMetrics.density
        cells.forEach {
            assertTrue("Search card narrower than 280dp at $widthDp dp", it.boundsInRoot.width >= 280 * density - 2)
            assertTrue(it.boundsInRoot.left >= list.boundsInRoot.left - 1)
            assertTrue(it.boundsInRoot.right <= list.boundsInRoot.right + 1)
        }
        val content = compose.onNodeWithTag("search_screen").fetchSemanticsNode().boundsInRoot
        traceRoute(
            widthDp = widthDp,
            heightDp = 600,
            surface = "search",
            content = content,
            itemCount = cells.size,
            minimumItemWidthDp = cells.minOf { it.boundsInRoot.width / density },
            stateAnchor = expectedAnchor,
        )
        captureEvidence("search", widthDp)
    }
    private fun traceRoute(
        widthDp: Int,
        heightDp: Int,
        surface: String,
        content: androidx.compose.ui.geometry.Rect,
        itemCount: Int,
        minimumItemWidthDp: Float,
        stateAnchor: String,
    ) {
        val record = trace.getJSONObject(trace.length() - 1)
        val surfaces = record.optJSONArray("surfaces") ?: JSONArray().also { record.put("surfaces", it) }
        surfaces.put(JSONObject()
            .put("surface", surface)
            .put("requested_width_dp", widthDp)
            .put("requested_height_dp", heightDp)
            .put("navigation", navigationMode())
            .put("content_left_px", content.left)
            .put("content_right_px", content.right)
            .put("content_width_px", content.width)
            .put("item_count", itemCount)
            .put("minimum_item_width_dp", minimumItemWidthDp)
            .put("state_anchor", stateAnchor))
    }
    private fun captureEvidence(surface: String, widthDp: Int) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(context.cacheDir, "side-navigation-$surface-$widthDp.png").outputStream().use {
            assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    @Test fun rootNavigationAndSearchResultsKeepTheirStateAcrossActualTaskBounds() {
        open(query); resize(900)
        compose.waitUntil(20_000) {
            appDb.openHelper.readableDatabase.query("SELECT status,completedPage FROM search_sessions WHERE query = ?", arrayOf(query)).use {
                it.moveToFirst() && it.getString(0) == "Ready" && it.getInt(1) == 1
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("search_results").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("search_results").performScrollToIndex(48)
        compose.waitForIdle()
        val before = calls.get()
        val root = grid()
        val anchor = root.children.filter { it.boundsInRoot.top >= root.boundsInRoot.top && it.boundsInRoot.bottom <= root.boundsInRoot.bottom }
            .flatMap(::texts).first { it.startsWith(query) }
        val columns = mutableMapOf<Int, Int>()
        for (width in listOf(900, 860, 840, 839, 760, 640, 600, 599, 520, 420, 360, 420, 520, 599, 600, 760, 839, 840, 900)) {
            resize(width)
            compose.onNodeWithTag("search_query").assertTextContains(query)
            compose.onNodeWithText(anchor).assertIsDisplayed()
            nav(R.string.bookshelf).assertIsSelected()
            assertEquals(SearchRoute(query, group), activity!!.currentDestination)
            val list = grid(); val cells = list.children.filter { it.boundsInRoot.width > 0 && it.boundsInRoot.height > 0 }
            assertTrue(cells.isNotEmpty())
            cells.forEach {
                assertTrue("Search card narrower than 280dp", it.boundsInRoot.width >= 280 * activity!!.resources.displayMetrics.density - 2)
                assertTrue(it.boundsInRoot.left >= list.boundsInRoot.left - 1 && it.boundsInRoot.right <= list.boundsInRoot.right + 1)
            }
            val count = cells.map { (it.boundsInRoot.center.x / 2).toInt() }.distinct().size
            columns[width] = count
            trace.getJSONObject(trace.length() - 1).put("grid_width_px", list.boundsInRoot.width).put("columns", count).put("anchor", anchor)
            if (width in listOf(360, 600, 900)) {
                val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
                File(context.cacheDir, "adaptive-search-window-$width.png").outputStream().use {
                    assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
                }
            }
            assertEquals("Resize submitted another search", before, calls.get())
        }
        val expectedWideColumns = if (activity!!.resources.configuration.fontScale >= 1.8f) 1 else 2
        assertEquals(expectedWideColumns, columns[900])
        assertEquals(1, columns[600]); assertEquals(1, columns[360])
        resize(840, 360)
        compose.onNodeWithText(anchor).assertIsDisplayed()
        compose.onNodeWithTag("search_query").assertTextContains(query)
        nav(R.string.my).performClick()
        compose.waitUntil(10_000) { activity?.currentDestination == MyRoute }
        resize(599); resize(600)
        nav(R.string.bookshelf).performClick()
        compose.waitUntil(10_000) { activity?.currentDestination is SearchRoute }
        compose.onNodeWithText(anchor).assertIsDisplayed()
        compose.onNodeWithTag("search_query").assertTextContains(query)
        assertEquals(before, calls.get())
    }

    @Test fun unsubmittedQueryAndFilterDialogDraftSurviveWindowChanges() {
        open(""); resize(360)
        val draft = "Draft $id"
        compose.onNodeWithTag("search_query").performClick().assertIsFocused().performTextInput(draft)
        for (width in listOf(599, 600, 839, 840, 900, 360)) {
            resize(width); compose.onNodeWithTag("search_query").assertTextContains(draft)
        }
        compose.onNodeWithTag("search_menu").performClick()
        compose.onNodeWithText(activity!!.getString(R.string.search_result_filter)).performClick()
        compose.onNodeWithTag("search_filter").performClick().assertIsFocused().performTextInput("Filter $id")
        for (width in listOf(600, 840, 900, 360)) {
            resize(width)
            val observed = compose.onNodeWithTag("search_filter").fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text
            val stored = appDb.openHelper.readableDatabase.query("SELECT filterDraft,revision FROM search_screen_drafts WHERE scope = ?", arrayOf(group)).use { cursor ->
                JSONArray().apply { while (cursor.moveToNext()) put(JSONObject().put("filter", cursor.getString(0)).put("revision", cursor.getLong(1))) }
            }
            trace.getJSONObject(trace.length() - 1).put("observed_filter", observed).put("stored_drafts", stored)
            assertEquals("Filter draft after resize to $width", "Filter $id", observed)
            compose.onNodeWithText(activity!!.getString(R.string.ok)).assertIsDisplayed()
        }
        resize(840, 360)
        compose.onNodeWithTag("search_filter").assertTextContains("Filter $id")
        compose.onNodeWithText(activity!!.getString(R.string.ok)).assertIsDisplayed()
        compose.onNodeWithText(activity!!.getString(R.string.ok)).performClick()
        compose.onNodeWithTag("search_filter").assertDoesNotExist()
        compose.onNodeWithTag("search_query").assertTextContains(draft)
        assertEquals(0, calls.get())
    }

    @Test fun sideNavigationBoundariesKeepCardsAndTopLevelState() {
        insertShelfBooks()
        open(query)
        caseName = "side-navigation-boundaries"
        resize(900)
        compose.waitUntil(20_000) {
            appDb.openHelper.readableDatabase.query(
                "SELECT status,completedPage FROM search_sessions WHERE query = ?",
                arrayOf(query),
            ).use { it.moveToFirst() && it.getString(0) == "Ready" && it.getInt(1) == 1 }
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("search_results").fetchSemanticsNodes().isNotEmpty()
        }
        resize(599)
        compose.onNodeWithTag("search_results").performScrollToIndex(32)
        compose.waitForIdle()
        val resultAnchor = grid().children
            .filter {
                it.boundsInRoot.top >= grid().boundsInRoot.top &&
                    it.boundsInRoot.bottom <= grid().boundsInRoot.bottom
            }
            .flatMap(::textsIn)
            .first { it.startsWith(query) }
        val expectedRequests = calls.get()

        for (width in listOf(599, 600, 839, 840)) {
            assertSearchCards(width, resultAnchor)
            assertEquals("Side-navigation boundary resubmitted search", expectedRequests, calls.get())
            assertEquals(SearchRoute(query, group), activity!!.currentDestination)
        }

        openBookshelfRoot()

        var shelfAnchor: String? = null
        var exploreExpanded = false
        var myScrollAnchor = false
        for (width in listOf(599, 600, 839, 840)) {
            resize(width)
            if (activity!!.currentDestination != BookshelfRoute) {
                nav(R.string.bookshelf).performClick()
                compose.waitUntil(10_000) { activity?.currentDestination == BookshelfRoute }
            }
            assertContentOutsideRail("bookshelf_items")
            if (shelfAnchor == null) {
                compose.onNodeWithTag("bookshelf_items").performScrollToIndex(32)
                compose.waitForIdle()
                val shelf = compose.onNodeWithTag("bookshelf_items").fetchSemanticsNode()
                shelfAnchor = shelf.children
                    .filter {
                        it.boundsInRoot.top >= shelf.boundsInRoot.top &&
                            it.boundsInRoot.bottom <= shelf.boundsInRoot.bottom
                    }
                    .flatMap(::textsIn)
                    .first { it.startsWith(shelfPrefix) }
            }
            compose.onNodeWithText(checkNotNull(shelfAnchor)).assertIsDisplayed()
            val shelf = compose.onNodeWithTag("bookshelf_items").fetchSemanticsNode()
            val density = activity!!.resources.displayMetrics.density
            val shelfCards = shelf.children.filter { it.boundsInRoot.width > 0 && it.boundsInRoot.height > 0 }
            assertTrue("Bookshelf cards are missing at $width dp", shelfCards.isNotEmpty())
            shelfCards.forEach {
                assertTrue("Bookshelf card narrower than 96dp at $width dp", it.boundsInRoot.width >= 96 * density - 2)
                assertTrue(it.boundsInRoot.left >= shelf.boundsInRoot.left - 1)
                assertTrue(it.boundsInRoot.right <= shelf.boundsInRoot.right + 1)
            }
            traceRoute(
                widthDp = width,
                heightDp = 600,
                surface = "bookshelf",
                content = shelf.boundsInRoot,
                itemCount = shelfCards.size,
                minimumItemWidthDp = shelfCards.minOf { it.boundsInRoot.width / density },
                stateAnchor = checkNotNull(shelfAnchor),
            )
            captureEvidence("bookshelf", width)

            nav(R.string.discovery).performClick()
            compose.waitUntil(10_000) { activity?.currentDestination == ExploreRoute }
            compose.waitUntil(20_000) {
                runCatching { compose.onNodeWithText(source.bookSourceName).assertIsDisplayed() }.isSuccess
            }
            assertContentOutsideRail("explore_content")
            val explore = compose.onNodeWithTag("explore_sources").fetchSemanticsNode()
            val exploreContent = compose.onNodeWithTag("explore_content").fetchSemanticsNode()
            assertTrue("Explore source row narrower than content at $width dp", explore.boundsInRoot.width >= minOf(
                exploreContent.boundsInRoot.width,
                720 * density,
            ) - 2)
            if (!exploreExpanded) {
                compose.onNodeWithText(source.bookSourceName).performClick()
                compose.waitUntil(20_000) {
                    runCatching { compose.onNodeWithText("Rail category").assertIsDisplayed() }.isSuccess
                }
                exploreExpanded = true
            } else {
                compose.onNodeWithText("Rail category").assertIsDisplayed()
            }
            traceRoute(
                widthDp = width,
                heightDp = 600,
                surface = "explore",
                content = exploreContent.boundsInRoot,
                itemCount = explore.children.count { it.boundsInRoot.width > 0 && it.boundsInRoot.height > 0 },
                minimumItemWidthDp = explore.boundsInRoot.width / density,
                stateAnchor = "Rail category",
            )
            captureEvidence("explore", width)

            nav(R.string.my).performClick()
            compose.waitUntil(10_000) { activity?.currentDestination == MyRoute }
            compose.waitUntil(10_000) {
                runCatching {
                    compose.onNodeWithTag("my_preferences").assertIsDisplayed()
                }.isSuccess
            }
            assertContentOutsideRail("my_preferences")
            val my = compose.onNodeWithTag("my_preferences").fetchSemanticsNode()
            assertTrue(my.boundsInRoot.width <= 720 * density + 2)
            assertTrue(my.boundsInRoot.left >= 0 && my.boundsInRoot.right <= activity!!.window.decorView.width + 1)
            if (!myScrollAnchor) {
                val about = activity!!.getString(R.string.about)
                compose.onNodeWithTag("my_preferences").performScrollToNode(hasText(about))
                compose.onNodeWithText(about).assertIsDisplayed()
                myScrollAnchor = true
            } else {
                compose.onNodeWithText(activity!!.getString(R.string.about)).assertIsDisplayed()
            }
            traceRoute(
                widthDp = width,
                heightDp = 600,
                surface = "my",
                content = my.boundsInRoot,
                itemCount = my.children.count { it.boundsInRoot.width > 0 && it.boundsInRoot.height > 0 },
                minimumItemWidthDp = my.boundsInRoot.width / density,
                stateAnchor = activity!!.getString(R.string.about),
            )
            captureEvidence("my", width)
        }
        caseName = "side-navigation-boundaries"
    }
}
