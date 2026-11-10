package io.legado.app.ui.book.read

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import fi.iki.elonen.NanoHTTPD
import io.legado.app.R
import io.legado.app.constant.BookType
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.rule.BookInfoRule
import io.legado.app.data.entities.rule.ExploreRule
import io.legado.app.data.entities.rule.SearchRule
import io.legado.app.data.entities.rule.TocRule
import io.legado.app.data.repository.searchResultKey
import io.legado.app.help.book.BookHelp
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.globalExecutor
import io.legado.app.model.ReadBook
import io.legado.app.ui.AppActivity
import io.legado.app.ui.navigation.BookInfoRoute
import io.legado.app.ui.navigation.BookshelfRoute
import io.legado.app.ui.navigation.ExploreBooksRoute
import io.legado.app.ui.navigation.SearchRoute
import io.legado.app.utils.defaultSharedPreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * One acceptance matrix for every application-owned text entry that addresses an existing book.
 * A successful open must use ReadBookActivity and retain the same chapter-space anchor.
 */
@RunWith(AndroidJUnit4::class)
class ReaderEntryConsistencyUiTest {
    @get:org.junit.Rule(order = 0) val acceptanceTheme = io.legado.app.ui.test.AcceptanceThemeRule()

    @get:Rule val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val preferences = context.defaultSharedPreferences
    private val id = UUID.randomUUID().toString()
    private val group = "reader-entry-$id"
    private val query = "Reader entry $id"
    private val savedRecentReading = preferences.all[PreferKey.showBookshelfRecentReading]
    private val savedLocal = listOf("privacyPolicyOk", "readHelpVersion", "readMenuHelpVersion")
        .associateWith { LocalConfig.all[it] }
    private lateinit var server: NanoHTTPD
    private lateinit var source: BookSource
    private lateinit var book: Book
    private val chapters = arrayListOf<BookChapter>()
    private var app: ActivityScenario<AppActivity>? = null
    private var opened: ReadBookActivity? = null
    private var firstAnchor: Pair<Int, Int>? = null

    private val base get() = "http://127.0.0.1:${server.listeningPort}"

    @Before
    fun setup() {
        preferences.edit()
            .putBoolean(PreferKey.cronet, false)
            .putBoolean(PreferKey.autoBackup, false)
            .putBoolean(PreferKey.showBookshelfRecentReading, true)
            .commit()
        LocalConfig.edit()
            .putBoolean("privacyPolicyOk", true)
            .putInt("readHelpVersion", 1)
            .putInt("readMenuHelpVersion", 1)
            .commit()
        server = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response {
                val result = when (session.uri) {
                    "/search", "/explore" -> if (session.parameters["page"]?.firstOrNull() == "1") {
                        "<article><h2>$query</h2><span class='author'>Author</span><a href='/info'>Book</a></article>"
                    } else {
                        "<main>No more</main>"
                    }
                    "/info" -> "<h1>$query</h1><p class='author'>Author</p><div class='intro'>Reader entry fixture</div><a class='toc' href='/toc'>TOC</a>"
                    "/toc" -> chapters.joinToString("") { "<a class='chapter' href='/${it.url}'>${it.title}</a>" }
                    else -> if (session.uri.removePrefix("/") in chapters.map(BookChapter::url)) {
                        "<main>${chapters.first { it.url == session.uri.removePrefix("/") }.title}</main>"
                    } else "Missing"
                }
                return newFixedLengthResponse(result)
            }
        }.apply { start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
        source = BookSource(
            bookSourceUrl = "$base/source",
            bookSourceName = "Reader entry source $id",
            bookSourceGroup = group,
            searchUrl = "$base/search?page={{page}}&key={{key}}",
            ruleExplore = ExploreRule(
                bookList = "article",
                name = "h2@text",
                author = ".author@text",
                bookUrl = "a@href",
            ),
            ruleSearch = SearchRule(
                bookList = "article",
                name = "h2@text",
                author = ".author@text",
                bookUrl = "a@href",
            ),
            ruleBookInfo = BookInfoRule(
                name = "h1@text",
                author = ".author@text",
                intro = ".intro@text",
                tocUrl = ".toc@href",
            ),
            ruleToc = TocRule(
                chapterList = "a.chapter",
                chapterName = "text",
                chapterUrl = "href",
            ),
        )
        appDb.bookSourceDao.insert(source)
        chapters += (0..2).map { index ->
            BookChapter(
                bookUrl = "$base/info",
                url = "chapter-$index",
                title = "Chapter $index",
                index = index,
            )
        }
        book = Book(
            bookUrl = "$base/info",
            origin = source.bookSourceUrl,
            originName = source.bookSourceName,
            name = query,
            author = "Author",
            type = BookType.text,
            totalChapterNum = chapters.size,
            durChapterIndex = 1,
            durChapterTitle = chapters[1].title,
            durChapterPos = 1800,
            durChapterTime = System.currentTimeMillis(),
        )
        appDb.bookDao.insert(book)
        chapters.forEach { chapter ->
            appDb.bookChapterDao.insert(chapter)
            assertTrue(
                BookHelp.saveContent(
                    source,
                    book,
                    chapter,
                    (0..100).joinToString("\n") {
                        "Reader entry paragraph $it for ${chapter.title}, with a stable anchor."
                    },
                ),
            )
        }
    }

    @After
    fun cleanup() {
        runCatching { closeReader() }
        app?.close()
        globalExecutor.submit {}.get(10, TimeUnit.SECONDS)
        appDb.bookChapterDao.delByBook(book.bookUrl)
        appDb.bookDao.delete(book)
        appDb.bookSourceDao.delete(source)
        BookHelp.clearCache(book)
        appDb.searchKeywordDao.get(query)?.let { appDb.searchKeywordDao.delete(it) }
        appDb.openHelper.writableDatabase.execSQL(
            "DELETE FROM search_screen_drafts WHERE sessionId IN (SELECT id FROM search_sessions WHERE query = ?)",
            arrayOf(query),
        )
        appDb.openHelper.writableDatabase.execSQL(
            "DELETE FROM search_sessions WHERE query = ?",
            arrayOf(query),
        )
        server.stop()
        preferences.edit().apply {
            if (savedRecentReading is Boolean) {
                putBoolean(PreferKey.showBookshelfRecentReading, savedRecentReading)
            } else {
                remove(PreferKey.showBookshelfRecentReading)
            }
        }.commit()
        LocalConfig.edit().apply {
            savedLocal.forEach { (key, value) ->
                when (value) {
                    null -> remove(key)
                    is Boolean -> putBoolean(key, value)
                    is Int -> putInt(key, value)
                }
            }
        }.commit()
    }

    @Test(timeout = 240_000)
    fun shelfDetailSearchDiscoverAndDeepLinkUseTheSameMatureReaderAnchor() {
        openFromBookshelf()
        firstAnchor = assertMatureReader(null)

        openFromDetail()
        assertMatureReader(checkNotNull(firstAnchor))

        openFromSearch()
        assertMatureReader(checkNotNull(firstAnchor))

        openFromExplore()
        assertMatureReader(checkNotNull(firstAnchor))

        openFromDeepLink()
        assertMatureReader(checkNotNull(firstAnchor))
    }

    private fun openFromBookshelf() {
        resetApplication()
        app = ActivityScenario.launch(AppActivity.startIntent(context, BookshelfRoute))
        compose.waitUntil(20_000) {
            compose.onAllNodesWithTag("bookshelf_continue_reading").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("bookshelf_continue_reading").assertIsDisplayed().performClick()
    }

    private fun openFromDetail() {
        resetApplication()
        val route = BookInfoRoute(book.bookUrl)
        app = ActivityScenario.launch(AppActivity.startIntent(context, route))
        var reading = ""
        app!!.onActivity { reading = it.getString(R.string.continue_read) }
        compose.waitUntil(20_000) {
            compose.onAllNodesWithText(reading).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(reading).performClick()
    }

    private fun openFromSearch() {
        resetApplication()
        app = ActivityScenario.launch(AppActivity.startIntent(context, SearchRoute(query, group)))
        waitForSearchResultAndOpenDetails()
    }

    private fun openFromExplore() {
        resetApplication()
        val route = ExploreBooksRoute(source.bookSourceUrl, "Reader entry", "$base/explore?page={{page}}")
        app = ActivityScenario.launch(AppActivity.startIntent(context, route))
        compose.waitUntil(20_000) {
            compose.onAllNodesWithText(query).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(query).performClick()
        waitForDetailsAndRead()
    }

    private fun openFromDeepLink() {
        resetApplication()
        val deepLink = Uri.Builder()
            .scheme("legado")
            .authority("import")
            .appendPath("addToBookshelf")
            .appendQueryParameter("src", book.bookUrl)
            .build()
        val intent = Intent(Intent.ACTION_VIEW, deepLink)
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .setPackage(context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        assertEquals(
            AppActivity::class.java.name,
            context.packageManager.resolveActivity(intent, 0)?.activityInfo?.name,
        )
        app = ActivityScenario.launch(intent)
        waitForDetailsAndRead()
    }

    private fun waitForSearchResultAndOpenDetails() {
        compose.waitUntil(30_000) {
            runCatching { compose.onNodeWithTag("search_query").assertIsDisplayed() }.isSuccess
        }
        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag("search_result:${searchResultKey(query, "Author")}")
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("search_result:${searchResultKey(query, "Author")}").performClick()
        waitForDetailsAndRead()
    }

    private fun waitForDetailsAndRead() {
        compose.waitUntil(20_000) {
            var ready = false
            app!!.onActivity { ready = it.currentDestination is BookInfoRoute }
            ready
        }
        var reading = ""
        app!!.onActivity { reading = it.getString(R.string.continue_read) }
        compose.waitUntil(20_000) {
            compose.onAllNodesWithText(reading).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(reading).performClick()
    }

    private fun assertMatureReader(expected: Pair<Int, Int>?): Pair<Int, Int> {
        compose.waitUntil(30_000) {
            opened = reader()
            opened != null &&
                ReadBook.book?.bookUrl == book.bookUrl &&
                ReadBook.curTextChapter?.chapter?.url == chapters[1].url &&
                ReadBook.curTextChapter?.isCompleted == true &&
                ReadBook.curTextChapter?.pages?.isNotEmpty() == true
        }
        val actual = ReadBook.durChapterIndex to ReadBook.durChapterPos
        if (expected == null) {
            assertEquals(book.durChapterIndex, actual.first)
            assertTrue("A saved non-zero anchor must be restored", actual.second > 0)
        } else {
            assertEquals("Entry path must restore the same canonical reader anchor", expected, actual)
        }
        assertNotNull(opened?.findViewById<android.view.View>(R.id.read_view))
        assertFalse(opened!!.isDestroyed)
        closeReader()
        return actual
    }

    private fun reader(): ReadBookActivity? {
        var result: ReadBookActivity? = null
        instrumentation.runOnMainSync {
            result = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<ReadBookActivity>()
                .firstOrNull()
        }
        return result
    }

    private fun closeReader() {
        val current = opened ?: reader() ?: return
        instrumentation.runOnMainSync { if (!current.isDestroyed) current.finish() }
        compose.waitUntil(20_000) { reader() == null }
        opened = null
    }

    private fun resetApplication() {
        closeReader()
        app?.close()
        app = null
    }
}
