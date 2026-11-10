package io.legado.app.ui.main

import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import fi.iki.elonen.NanoHTTPD
import io.legado.app.constant.PreferKey
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.rule.SearchRule
import io.legado.app.utils.defaultSharedPreferences
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.navigation3.runtime.NavEntry
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.ui.AppActivity
import io.legado.app.ui.main.bookshelf.BookshelfRootViewModel
import io.legado.app.ui.navigation.BookshelfRoute
import io.legado.app.ui.navigation.SearchRoute
import io.legado.app.ui.navigation.navigationViewModelStores
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RealSplitScreenUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }

    private fun split(scenario: ActivityScenario<AppActivity>, verify: () -> Unit) {
        var taskId = -1
        scenario.onActivity { taskId = it.taskId }
        try {
            shell("cmd statusbar wmshell-passthrough splitscreen moveToSideStage $taskId 1")
            compose.waitUntil(20_000) { var split = false; scenario.onActivity { split = it.isInMultiWindowMode }; split }
            val tasks = shell("dumpsys activity activities")
            assertTrue("Expected two visible split stages", tasks.contains("name=main") && tasks.contains("name=side") && tasks.contains("com.android.settings"))
            verify()
            shell("cmd statusbar wmshell-passthrough splitscreen switchSplitPosition")
            compose.waitForIdle()
            verify()
        } finally {
            shell("cmd statusbar wmshell-passthrough splitscreen exitSplitScreen $taskId")
        }
        compose.waitUntil(20_000) { var full = false; scenario.onActivity { full = !it.isInMultiWindowMode }; full }
        verify()
    }

    @Test fun selectedGroupAndBookSurviveSplitAndReturn() {
        val id = UUID.randomUUID().toString()
        val groupId = (0..60).map { 1L shl it }.first { appDb.bookGroupDao.getByID(it) == null }
        val group = BookGroup(groupId, "Split group $id")
        val book = Book(bookUrl = "split-test://$id", name = "Split book $id", group = groupId)
        appDb.bookGroupDao.insert(group)
        appDb.bookDao.insert(book)
        try {
            shell("am start -a android.settings.SETTINGS")
            ActivityScenario.launch<AppActivity>(AppActivity.startIntent(context, BookshelfRoute)).use { scenario ->
                scenario.onActivity { host ->
                    val owner = navigationViewModelStores(host, BookshelfRoute).getOrCreateOwner(NavEntry(BookshelfRoute) {}.contentKey)
                    ViewModelProvider(owner)[BookshelfRootViewModel::class.java].openGroup(group)
                }
                compose.waitUntil(20_000) { compose.onAllNodesWithText(book.name).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText(book.name).performTouchInput { longClick() }
                split(scenario) {
                    compose.onNodeWithText(book.name).assertIsDisplayed()
                    compose.onNodeWithTag("shelf-selection-add").assertIsEnabled()
                }
            }
        } finally {
            appDb.bookDao.delete(book)
            appDb.bookGroupDao.delete(group)
        }
    }

    @Test fun searchDraftSurvivesSplitAndReturn() {
        val scope = "split-search-${UUID.randomUUID()}"
        try {
            shell("am start -a android.settings.SETTINGS")
            ActivityScenario.launch<AppActivity>(AppActivity.startIntent(context, SearchRoute("", scope))).use { scenario ->
                compose.waitUntil(20_000) { runCatching { compose.onNodeWithTag("search_query").assertIsEnabled() }.isSuccess }
                compose.onNodeWithTag("search_query").performTextInput("split draft")
                split(scenario) {
                    compose.onNodeWithTag("search_query").assertIsDisplayed().assertTextContains("split draft")
                    compose.onNodeWithTag("search_menu").assertIsDisplayed()
                }
            }
        } finally {
            appDb.openHelper.writableDatabase.execSQL("DELETE FROM search_screen_drafts WHERE scope = ?", arrayOf(scope))
        }
    }
    @Test fun searchResultScrollPositionSurvivesRealSplit() {
        val id = UUID.randomUUID().toString()
        val query = "Split result $id"
        val scope = "split-result-$id"
        val prefs = context.defaultSharedPreferences
        val keys = listOf(PreferKey.cronet, PreferKey.autoBackup, PreferKey.precisionSearch, PreferKey.searchResultFilter)
        val saved = keys.associateWith { prefs.all[it] }
        var source: BookSource? = null
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val server = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response {
                calls.incrementAndGet()
                val body = if (session.parameters["page"]?.firstOrNull() == "1")
                    (0..71).joinToString("") { "<article><h2>$query book $it</h2><span>Author $it</span><a href='/book/$it'>Book</a></article>" }
                else "<main>No more</main>"
                return newFixedLengthResponse(body)
            }
        }
        try {
            prefs.edit().putBoolean(PreferKey.cronet, false).putBoolean(PreferKey.autoBackup, false)
                .putBoolean(PreferKey.precisionSearch, false).putString(PreferKey.searchResultFilter, "").commit()
            server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            val base = "http://127.0.0.1:${server.listeningPort}"
            source = BookSource(base, "Split source $id", bookSourceGroup = scope,
                searchUrl = "$base/search?page={{page}}&key={{key}}",
                ruleSearch = SearchRule(bookList = "article", name = "h2@text", author = "span@text", bookUrl = "a@href"))
            appDb.bookSourceDao.insert(source)
            shell("am start -a android.settings.SETTINGS")
            ActivityScenario.launch<AppActivity>(AppActivity.startIntent(context, SearchRoute(query, scope))).use { scenario ->
                compose.waitUntil(30_000) {
                    appDb.openHelper.readableDatabase.query("SELECT status,completedPage FROM search_sessions WHERE query = ?", arrayOf(query)).use {
                        it.moveToFirst() && it.getString(0) == "Ready" && it.getInt(1) >= 1
                    }
                }
                compose.onNodeWithTag("search_results").performScrollToIndex(48)
                compose.waitForIdle()
                val grid = compose.onNodeWithTag("search_results").fetchSemanticsNode()
                fun textIn(node: SemanticsNode): List<String> = node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + node.children.flatMap(::textIn)
                val anchor = grid.children.filter { it.boundsInRoot.top >= grid.boundsInRoot.top && it.boundsInRoot.bottom <= grid.boundsInRoot.bottom }
                    .flatMap(::textIn).first { it.startsWith(query) }
                compose.onNodeWithText(anchor).assertIsDisplayed()
                val before = calls.get()
                split(scenario) {
                    compose.onNodeWithTag("search_query").assertTextContains(query)
                    compose.onNodeWithText(anchor).assertIsDisplayed()
                    org.junit.Assert.assertEquals("Changing split should not start another search", before, calls.get())
                }
            }
        } finally {
            server.stop()
            source?.let { appDb.bookSourceDao.delete(it) }
            appDb.searchKeywordDao.get(query)?.let { appDb.searchKeywordDao.delete(it) }
            appDb.openHelper.writableDatabase.apply {
                execSQL("DELETE FROM search_screen_drafts WHERE scope = ?", arrayOf(scope))
                execSQL("DELETE FROM search_sessions WHERE scope = ?", arrayOf(scope))
            }
            prefs.edit().apply { saved.forEach { (key, value) -> when (value) {
                null -> remove(key); is Boolean -> putBoolean(key, value); is String -> putString(key, value)
            } } }.commit()
        }
    }

}
