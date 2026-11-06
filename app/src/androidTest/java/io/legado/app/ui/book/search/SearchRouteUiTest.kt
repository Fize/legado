package io.legado.app.ui.book.search

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fi.iki.elonen.NanoHTTPD
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.rule.BookInfoRule
import io.legado.app.data.entities.rule.SearchRule
import io.legado.app.data.entities.rule.TocRule
import io.legado.app.help.config.LocalConfig
import io.legado.app.ui.AppActivity
import io.legado.app.ui.navigation.BookInfoRoute
import io.legado.app.ui.navigation.SearchRoute
import io.legado.app.ui.navigation.AppRoute
import io.legado.app.ui.navigation.openBookSearch
import kotlinx.serialization.json.Json
import io.legado.app.utils.defaultSharedPreferences
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class SearchRouteUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val id = UUID.randomUUID().toString()
    private val query = "Search $id"
    private val group = "search-group-$id"
    private val prefs = context.defaultSharedPreferences
    private val saved = listOf(PreferKey.cronet, PreferKey.searchResultFilter, PreferKey.precisionSearch, PreferKey.autoBackup, "searchScope").associateWith { prefs.all[it] }
    private val localKeys = listOf("privacyPolicyOk", "readHelpVersion", "readMenuHelpVersion")
    private val savedLocal = localKeys.associateWith { LocalConfig.all[it] }
    private val sources = arrayListOf<BookSource>()
    private lateinit var server: NanoHTTPD
    private val calls = AtomicInteger()
    private val repeatFirstPage = java.util.concurrent.atomic.AtomicBoolean(false)
    private val multiPage = java.util.concurrent.atomic.AtomicBoolean(false)
    private val detailHeader = AtomicReference<String?>()
    private var holdA: CountDownLatch? = null
    private val base get() = "http://127.0.0.1:${server.listeningPort}"
    private val bookUrl get() = "$base/info"

    @Before fun setup() {
        prefs.edit().putBoolean(PreferKey.cronet, false).putString(PreferKey.searchResultFilter, "").putBoolean(PreferKey.precisionSearch, false).putBoolean(PreferKey.autoBackup, false).commit()
        LocalConfig.edit().putBoolean("privacyPolicyOk", true).putInt("readHelpVersion", 1).putInt("readMenuHelpVersion", 1).commit()
        server = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response = when (session.uri) {
                "/search/a", "/search/b" -> {
                    calls.incrementAndGet()
                    if (session.uri.endsWith("/a")) holdA?.await(15, TimeUnit.SECONDS)
                    if (multiPage.get()) {
                        val page = session.parameters["page"]?.firstOrNull()?.toIntOrNull() ?: 0
                        val html = if (page in 1..2) (0 until 12).joinToString("") { index ->
                            "<article><h2>$query $page-$index</h2><span class='author'>Author</span><a href='/info/$page/$index'>Book</a></article>"
                        } else "<main>No more</main>"
                        newFixedLengthResponse(html)
                    } else if (repeatFirstPage.get() || session.parameters["page"]?.firstOrNull() == "1") newFixedLengthResponse("<article><h2>$query</h2><span class='author'>Author</span><a href='/info'>Book</a></article>")
                    else newFixedLengthResponse("<main>No more</main>")
                }
                "/explore" -> if (session.parameters["page"]?.firstOrNull() == "1") newFixedLengthResponse("<article><h2>$query</h2><span class='author'>Author</span><a href='/info'>Book</a></article>") else newFixedLengthResponse("<main>No more</main>")
                "/info" -> { detailHeader.set(session.headers["x-search-source"]); newFixedLengthResponse("<h1>$query</h1><p class='author'>Author</p><div class='intro'>Pinned source detail $id</div><a class='toc' href='/toc'>TOC</a>") }
                "/toc" -> newFixedLengthResponse("<a class='chapter' href='/c1'>Chapter</a>")
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Missing")
            }
        }.apply { start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
        addSource("b")
    }
    private fun addSource(label: String): BookSource = BookSource("$base/$label", "Source $label $id",
        bookSourceGroup = group, header = "{\"X-Search-Source\":\"$label\"}",
        searchUrl = "$base/search/$label?page={{page}}&key={{key}}",
        ruleExplore = io.legado.app.data.entities.rule.ExploreRule(bookList = "article", name = "h2@text", author = ".author@text", bookUrl = "a@href"),
        ruleSearch = SearchRule(bookList = "article", name = "h2@text", author = ".author@text", bookUrl = "a@href"),
        ruleBookInfo = BookInfoRule(name = "h1@text", author = ".author@text", intro = ".intro@text", tocUrl = ".toc@href"),
        ruleToc = TocRule(chapterList = "a.chapter", chapterName = "text", chapterUrl = "href"),
    ).also { sources += it; appDb.bookSourceDao.insert(it) }

    @After fun cleanup() {
        holdA?.countDown(); server.stop()
        sources.forEach { appDb.bookSourceDao.delete(it) }
        appDb.searchKeywordDao.get(query)?.let { appDb.searchKeywordDao.delete(it) }
        val db = appDb.openHelper.writableDatabase
        db.execSQL("DELETE FROM search_screen_drafts WHERE sessionId IN (SELECT id FROM search_sessions WHERE query = ?)", arrayOf(query))
        db.execSQL("DELETE FROM search_sessions WHERE query = ?", arrayOf(query))
        prefs.edit().apply { saved.forEach { (key, value) -> when (value) {
            null -> remove(key); is Boolean -> putBoolean(key, value); is String -> putString(key, value)
        } } }.commit()
        LocalConfig.edit().apply { savedLocal.forEach { (key, value) -> when (value) {
            null -> remove(key); is Boolean -> putBoolean(key, value); is Int -> putInt(key, value)
        } } }.commit()
    }
    private fun launch(query: String = this.query) = ActivityScenario.launch<AppActivity>(AppActivity.startIntent(context, SearchRoute(query, group)))
    private fun await(condition: () -> Boolean) {
        try { compose.waitUntil(20_000, condition) }
        catch (error: Throwable) {
            val status = appDb.openHelper.readableDatabase.query("SELECT status,done,total,completedPage,requestedPage,hasMore,error FROM search_sessions WHERE query = ?", arrayOf(query)).use { cursor ->
                buildList { while (cursor.moveToNext()) add((0 until cursor.columnCount).map { cursor.getString(it) }) }
            }
            throw AssertionError("Fixture search calls=${calls.get()} state=$status UI=${compose.onRoot().printToString()}", error)
        }
    }
    private fun ready() = compose.waitUntil(20_000) {
        runCatching { compose.onNodeWithTag("search_query").assertIsEnabled() }.isSuccess
    }
    private fun uiText(id: Int): String {
        var result = context.getString(id)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
                .filterIsInstance<AppActivity>().firstOrNull()?.let { result = it.getString(id) }
        }
        return result
    }
    private fun menu(label: Int) {
        compose.onNodeWithTag("search_menu").assertIsEnabled().performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithText(uiText(label)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(uiText(label)).performClick()
    }
    private fun completed(): Boolean = appDb.openHelper.readableDatabase.query("SELECT hasMore,status FROM search_sessions WHERE query = ? ORDER BY updatedAt DESC LIMIT 1", arrayOf(query)).use {
        it.moveToFirst() && it.getInt(0) == 0 && it.getString(1) == "Ready"
    }

    @Test fun realSearchPaginatesAndRecreationRestoresResultsWithoutAnotherRequest() {
        launch().use { scenario ->
            ready(); await(::completed)
            compose.onNodeWithTag("search_result:${io.legado.app.data.repository.searchResultKey(query, "Author")}").assertExists()
            compose.waitForIdle()
            compose.onNodeWithText(context.getString(R.string.search_session_expand_scope)).assertDoesNotExist()
            compose.onNodeWithText(context.getString(R.string.search_session_relax_precision)).assertDoesNotExist()
            scenario.onActivity { assertEquals(androidx.lifecycle.Lifecycle.State.RESUMED, it.lifecycle.currentState) }
            val windows = android.os.ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand("dumpsys window windows"),
            ).bufferedReader().use { it.readText() }
            assertTrue("Search Activity window must be attached", windows.contains("${context.packageName}/io.legado.app.ui.AppActivity"))
            assertFalse("System ANR dialog must not cover the search screenshot", windows.contains("Application Not Responding:"))
            instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
                java.io.File(context.cacheDir, "search-route-fixture.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            val before = calls.get(); assertEquals(2, before)
            scenario.recreate(); ready()
            compose.onNodeWithTag("search_query").assertTextContains(query)
            compose.onNodeWithTag("search_results").assertExists()
            compose.waitForIdle(); assertEquals(before, calls.get())
        }
    }
    @Test fun repeatedPageDoesNotCauseUnboundedAutomaticRequestsAndManualNextStillWorks() {
        repeatFirstPage.set(true)
        launch().use {
            ready()
            await { calls.get() >= 2 }
            SystemClock.sleep(2_000)
            assertEquals("One automatic page is allowed while the list remains at the bottom", 2, calls.get())
            compose.onNodeWithText(context.getString(R.string.next_page)).assertIsDisplayed().performClick()
            await { calls.get() >= 3 }
            SystemClock.sleep(1_000)
            assertEquals("Manual next should load exactly one page", 3, calls.get())
        }
    }
    @Test fun scrollingToTheBottomAgainLoadsTheFollowingPage() {
        multiPage.set(true)
        launch().use {
            ready()
            await { calls.get() == 1 && appDb.openHelper.readableDatabase.query(
                "SELECT COUNT(*) FROM search_session_results WHERE sessionId IN (SELECT id FROM search_sessions WHERE query = ?)", arrayOf(query)
            ).use { cursor -> cursor.moveToFirst() && cursor.getInt(0) == 12 } }
            compose.onNodeWithTag("search_results").performScrollToIndex(11)
            await { calls.get() == 2 && appDb.openHelper.readableDatabase.query(
                "SELECT COUNT(*) FROM search_session_results WHERE sessionId IN (SELECT id FROM search_sessions WHERE query = ?)", arrayOf(query)
            ).use { cursor -> cursor.moveToFirst() && cursor.getInt(0) == 24 } }
            compose.onNodeWithTag("search_results").performScrollToIndex(23)
            await(::completed)
            assertEquals(3, calls.get())
        }
    }
    @Test fun resultFilterReactsImmediatelyAndSurvivesRecreation() {
        launch().use { scenario ->
            ready(); await(::completed)
            menu(R.string.search_result_filter)
            compose.onNodeWithTag("search_filter").performTextReplacement(query)
            compose.onNodeWithText(uiText(R.string.ok)).performClick()
            compose.waitUntil(20_000) { compose.onAllNodesWithTag("search_empty").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("search_empty").assertIsDisplayed()
            scenario.recreate(); ready()
            compose.waitUntil(20_000) { compose.onAllNodesWithTag("search_empty").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("search_empty").assertIsDisplayed()
            assertEquals(query, prefs.getString(PreferKey.searchResultFilter, ""))
        }
    }
    @Test fun sourceScopeDraftAndSearchTextSurviveActivityRecreation() {
        launch("").use { scenario ->
            ready(); menu(R.string.search_scope)
            compose.onNodeWithText(uiText(R.string.book_source), useUnmergedTree = true).performClick()
            compose.onNodeWithTag("search_scope_query").performTextReplacement(id)
            compose.onNodeWithText(sources.single().bookSourceName).performClick()
            scenario.recreate(); ready()
            compose.onNodeWithTag("search_scope_query").assertTextContains(id)
            compose.onNodeWithText(sources.single().bookSourceName).assertExists()
            compose.onNodeWithText(uiText(R.string.ok)).performClick()
            assertEquals(0, calls.get())
        }
    }
    @Test fun applicationContextEntryCarriesTypedArgumentsAndTheRequiredTaskFlag() {
        val captured = AtomicReference<Intent?>()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.component?.className != AppActivity::class.java.name) return null
                captured.set(Intent(intent))
                return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            instrumentation.runOnMainSync { context.openBookSearch(query, group) }
            val intent = checkNotNull(captured.get())
            assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
            assertEquals(SearchRoute(query, group), Json.decodeFromString<AppRoute>(checkNotNull(intent.getStringExtra("initialDestination"))))
        } finally { instrumentation.removeMonitor(monitor) }
    }

    @Test fun processTextOpensTheTypedSearchDestinationInsideTheSameHost() {
        prefs.edit().putString("searchScope", group).commit()
        val intent = Intent(context, AppActivity::class.java).setAction(Intent.ACTION_PROCESS_TEXT).setType("text/plain")
            .putExtra(Intent.EXTRA_PROCESS_TEXT, query)
        ActivityScenario.launch<AppActivity>(intent).use { scenario ->
            ready(); await(::completed)
            scenario.onActivity { activity ->
                assertEquals(SearchRoute(query), activity.currentDestination)
            }
            compose.onNodeWithTag("search_query").assertTextContains(query)
        }
    }

    @Test fun selectingAndDeletingHistoryUseThePersistedKeywordIdentity() {
        appDb.searchKeywordDao.insert(io.legado.app.data.entities.SearchKeyword(query))
        launch("").use {
            ready()
            compose.waitUntil(20_000) { compose.onAllNodesWithText(query).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(query).performTouchInput { longClick() }
            await { appDb.searchKeywordDao.get(query) == null }
        }
        appDb.searchKeywordDao.insert(io.legado.app.data.entities.SearchKeyword(query))
        launch("").use {
            ready()
            compose.waitUntil(20_000) { compose.onAllNodesWithText(query).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(query).performClick()
            await(::completed)
            compose.onNodeWithTag("search_query").assertTextContains(query)
            assertNotNull(appDb.searchKeywordDao.get(query))
        }
    }

    @Test fun selectedSourceCredentialsReachRealBookDetailsEvenWhenSameUrlCacheWasOverwritten() {
        val a = addSource("a"); holdA = CountDownLatch(1)
        val oldHostLaunches = AtomicInteger()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.component?.className == "io.legado.app.ui.book.info.BookInfoActivity") oldHostLaunches.incrementAndGet()
                return null
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            launch().use { scenario ->
                ready()
                await { appDb.searchBookDao.getSearchBook(bookUrl)?.origin == sources.first().bookSourceUrl }
                holdA!!.countDown()
                await(::completed)
                await { appDb.searchBookDao.getSearchBook(bookUrl)?.origin == a.bookSourceUrl }
                compose.onNodeWithTag("search_result:${io.legado.app.data.repository.searchResultKey(query, "Author")}").performClick()
                var detail: BookInfoRoute? = null
                compose.waitUntil(20_000) { scenario.onActivity { detail = it.currentDestination as? BookInfoRoute }; detail != null && detailHeader.get() != null }
                val opened = detail!!
                assertEquals(bookUrl, opened.bookUrl)
                assertEquals(opened.requestId, opened.previewKey)
                assertEquals("b", detailHeader.get())
                assertEquals(0, oldHostLaunches.get())
                compose.waitUntil(20_000) { compose.onAllNodesWithText("Pinned source detail $id").fetchSemanticsNodes().isNotEmpty() }
                scenario.recreate()
                compose.waitUntil(20_000) { var restored = false; scenario.onActivity { restored = it.currentDestination == opened }; restored }
                scenario.onActivity { assertEquals(opened, it.currentDestination) }
                compose.waitUntil(20_000) { compose.onAllNodesWithText("Pinned source detail $id").fetchSemanticsNodes().isNotEmpty() }
                instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                compose.waitUntil(20_000) { var back = false; scenario.onActivity { back = it.currentDestination is SearchRoute }; back }
                ready()
                compose.waitForIdle()
                scenario.onActivity { assertTrue(it.currentDestination is SearchRoute) }
                assertEquals(0, oldHostLaunches.get())
            }
        } finally { instrumentation.removeMonitor(monitor) }
    }
    @Test fun exploreSelectionKeepsItsSourceAfterTheSharedCacheWasOverwritten() {
        val source = sources.first()
        val route = io.legado.app.ui.navigation.ExploreBooksRoute(source.bookSourceUrl, "Explore fixture", "$base/explore?page={{page}}")
        ActivityScenario.launch<AppActivity>(AppActivity.startIntent(context, route)).use { scenario ->
            compose.waitUntil(20_000) { compose.onAllNodesWithText(query).fetchSemanticsNodes().isNotEmpty() }
            val other = addSource("a")
            appDb.searchBookDao.insert(io.legado.app.data.entities.SearchBook(bookUrl = bookUrl, name = query, author = "Author",
                origin = other.bookSourceUrl, originName = other.bookSourceName))
            compose.onNodeWithText(query).performClick()
            compose.waitUntil(20_000) { var opened = false; scenario.onActivity { opened = it.currentDestination is BookInfoRoute }; opened && detailHeader.get() != null }
            assertEquals("b", detailHeader.get())
            compose.waitUntil(20_000) { compose.onAllNodesWithText("Pinned source detail $id").fetchSemanticsNodes().isNotEmpty() }
            scenario.onActivity { val detail = it.currentDestination as BookInfoRoute; assertEquals(detail.requestId, detail.previewKey) }
        }
    }

    @Test fun externalBookResolutionReplacesItsDialogWithDetailsAndDoesNotReopenItOnBack() {
        val source = sources.first().apply { bookUrlPattern = Regex.escape(bookUrl) }
        appDb.bookSourceDao.insert(source)
        val route = io.legado.app.ui.navigation.AddToBookshelfRoute(bookUrl)
        ActivityScenario.launch<AppActivity>(AppActivity.startIntent(context, route)).use { scenario ->
            compose.waitUntil(20_000) { var opened = false; scenario.onActivity { opened = it.currentDestination is BookInfoRoute }; opened }
            compose.waitUntil(20_000) { compose.onAllNodesWithText("Pinned source detail $id").fetchSemanticsNodes().isNotEmpty() }
            scenario.onActivity { val detail = it.currentDestination as BookInfoRoute; assertEquals(bookUrl, detail.bookUrl); assertEquals(detail.requestId, detail.previewKey) }
            instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            compose.waitUntil(20_000) { scenario.state == androidx.lifecycle.Lifecycle.State.DESTROYED }
            org.junit.Assert.assertEquals(androidx.lifecycle.Lifecycle.State.DESTROYED, scenario.state)
        }
    }

}
