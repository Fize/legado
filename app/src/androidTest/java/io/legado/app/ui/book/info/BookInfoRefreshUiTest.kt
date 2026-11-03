package io.legado.app.ui.book.info

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelProvider
import androidx.navigation3.runtime.NavEntry
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import fi.iki.elonen.NanoHTTPD
import io.legado.app.R
import io.legado.app.constant.BookType
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.cache.bookCacheKey
import io.legado.app.data.cache.cacheResourceLeases
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.rule.BookInfoRule
import io.legado.app.data.entities.rule.TocRule
import io.legado.app.data.repository.BookInfoPreviewStore
import io.legado.app.help.book.BookHelp
import io.legado.app.help.globalExecutor
import io.legado.app.ui.AppActivity
import io.legado.app.ui.navigation.BookInfoRoute
import io.legado.app.ui.navigation.BookTocRoute
import io.legado.app.ui.navigation.BookshelfRoute
import io.legado.app.ui.navigation.navigationViewModelStores
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
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class BookInfoRefreshUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val id = UUID.randomUUID().toString()
    private val prefs = context.defaultSharedPreferences
    private val previous = listOf(PreferKey.cronet, PreferKey.autoBackup).associateWith { prefs.all[it] }
    private lateinit var server: NanoHTTPD
    private lateinit var source: BookSource
    private lateinit var book: Book
    private lateinit var route: BookInfoRoute
    private var scenario: ActivityScenario<AppActivity>? = null
    private var holdResponse: CountDownLatch? = null
    private val responseStarted = CountDownLatch(1)
    private var cacheRelease: CountDownLatch? = null
    private var cacheThread: Thread? = null
    private val cacheFailure = AtomicReference<Throwable?>()
    private val extras = mutableListOf<Book>()
    private val updatedName get() = "Refreshed detail $id"
    private val base get() = "http://127.0.0.1:${server.listeningPort}/$id"

    @Before fun setup() {
        prefs.edit().putBoolean(PreferKey.cronet, false).putBoolean(PreferKey.autoBackup, false).commit()
        server = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response = when (session.uri.removePrefix("/$id")) {
                "/info" -> {
                    responseStarted.countDown()
                    holdResponse?.await(20, TimeUnit.SECONDS)
                    newFixedLengthResponse("<h1 class='name'>$updatedName</h1><p class='author'>Author $id</p><a class='toc' href='toc'>TOC</a>")
                }
                "/toc" -> newFixedLengthResponse("<a class='chapter' href='c1'>Updated chapter 1</a><a class='chapter' href='c2'>Updated chapter 2</a>")
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Missing")
            }
        }.apply { start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
        source = BookSource(base, "Refresh source $id", ruleBookInfo = BookInfoRule(name = ".name@text",
            author = ".author@text", tocUrl = ".toc@href", canReName = "true"),
            ruleToc = TocRule(chapterList = "a.chapter", chapterName = "text", chapterUrl = "href"))
        book = Book(bookUrl = "$base/info", origin = base, originName = source.bookSourceName,
            name = "Original refresh $id", author = "Author $id", tocUrl = "$base/toc", totalChapterNum = 1,
            customCoverUrl = "android.resource://${context.packageName}/${R.mipmap.ic_launcher}")
        route = BookInfoRoute(book.bookUrl)
        appDb.bookSourceDao.insert(source)
        appDb.bookDao.insert(book)
        appDb.bookChapterDao.insert(BookChapter(bookUrl = book.bookUrl, url = "$base/old", title = "Original chapter"))
    }

    @After fun cleanup() {
        holdResponse?.countDown()
        cacheRelease?.countDown()
        cacheThread?.join(10_000)
        scenario?.close()
        globalExecutor.submit {}.get(10, TimeUnit.SECONDS)
        (extras + listOfNotNull(appDb.bookDao.getBook(book.bookUrl), book)).forEach {
            BookHelp.clearCache(it)
            appDb.bookDao.delete(it)
        }
        appDb.bookSourceDao.delete(source)
        server.stop()
        prefs.edit().apply { previous.forEach { (key, value) -> when (value) {
            null -> remove(key)
            is Boolean -> putBoolean(key, value)
        } } }.commit()
    }

    private fun model(activity: AppActivity) = ViewModelProvider(navigationViewModelStores(activity, BookshelfRoute)
        .getOrCreateOwner(NavEntry(route) {}.contentKey))[BookInfoViewModel::class.java]
    private fun launch() {
        scenario = ActivityScenario.launch(AppActivity.startIntent(context, route))
        compose.waitUntil(10_000) { var ready = false; scenario!!.onActivity {
            ready = model(it).uiState.value.recoveryReady && model(it).uiState.value.book?.bookUrl == book.bookUrl
        }; ready }
    }
    private fun refresh() {
        scenario!!.onActivity { model(it).loadBookInfo(checkNotNull(model(it).getBook(false))) }
    }
    private fun refreshFinished(): Boolean {
        var done = false
        scenario!!.onActivity { done = !model(it).uiState.value.loading && model(it).uiState.value.recoveryReady }
        return done
    }

    @Test fun actualNetworkRefreshCommitsCurrentProgressAndPreviewTogether() {
        holdResponse = CountDownLatch(1)
        launch(); refresh()
        assertTrue(responseStarted.await(10, TimeUnit.SECONDS))
        val current = checkNotNull(appDb.bookDao.getBook(book.bookUrl)).apply {
            durChapterIndex = 1; durChapterPos = 7; durVolumeIndex = 2; chapterInVolumeIndex = 1
            customIntro = "Concurrent custom intro"; canUpdate = false
        }
        appDb.bookDao.update(current)
        holdResponse!!.countDown()
        compose.waitUntil(15_000) { refreshFinished() && appDb.bookDao.getBook(book.bookUrl)?.name == updatedName }
        compose.onNodeWithText(updatedName).assertIsDisplayed()
        val stored = checkNotNull(appDb.bookDao.getBook(book.bookUrl))
        assertEquals(1, stored.durChapterIndex); assertEquals(7, stored.durChapterPos)
        assertEquals(2, stored.durVolumeIndex); assertEquals(1, stored.chapterInVolumeIndex)
        assertEquals("Concurrent custom intro", stored.customIntro); assertFalse(stored.canUpdate)
        val preview = checkNotNull(BookInfoPreviewStore(context).peek(route.previewKey))
        assertEquals(updatedName, preview.book.name); assertEquals(7, preview.book.durChapterPos)
        assertEquals(2, preview.chapters.size)
        assertTrue(appDb.bookInfoRequestDao.get(route.requestId)!!.revision > 0)
        scenario!!.recreate()
        compose.waitUntil(10_000) { refreshFinished() }
        compose.onNodeWithText(updatedName).assertIsDisplayed()
    }

    @Test fun anUnshelvedNetworkRefreshDoesNotAdoptAnotherLibraryBook() {
        book.type = BookType.text or BookType.notShelf
        appDb.bookDao.update(book)
        val library = book.copy(bookUrl = "library://$id", name = updatedName, type = BookType.text, origin = "other-source")
        extras += library
        appDb.bookDao.insert(library)
        launch(); refresh()
        compose.waitUntil(15_000) { refreshFinished() && BookInfoPreviewStore(context).peek(route.previewKey)?.book?.name == updatedName }
        compose.onNodeWithText(updatedName).assertIsDisplayed()
        assertEquals(book.name, appDb.bookDao.getBook(book.bookUrl)!!.name)
        assertEquals(library.origin, appDb.bookDao.getBook(library.bookUrl)!!.origin)
        assertFalse(BookInfoPreviewStore(context).peek(route.previewKey)!!.inBookshelf)
        assertEquals(book.bookUrl, appDb.bookInfoRequestDao.get(route.requestId)!!.bookUrl)
    }

    @Test fun aLateNetworkRefreshCannotResurrectARemovedShelfBook() {
        holdResponse = CountDownLatch(1)
        launch(); refresh()
        assertTrue(responseStarted.await(10, TimeUnit.SECONDS))
        appDb.bookDao.delete(book)
        holdResponse!!.countDown()
        compose.waitUntil(15_000) { refreshFinished() }
        assertFalse(appDb.bookDao.has(book.bookUrl))
        assertEquals(0L, appDb.bookInfoRequestDao.get(route.requestId)!!.revision)
        assertEquals(book.name, BookInfoPreviewStore(context).peek(route.previewKey)!!.book.name)
    }
    @Test fun openingCatalogAfterCommitWaitsForTheCancelledWriterAndSurvivesRecreation() {
        launch()
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1).also { cacheRelease = it }
        cacheThread = Thread({
            runCatching {
                cacheResourceLeases().plan(bookCacheKey(book)).use { plan ->
                    check(plan.removeIfUnchanged {
                        locked.countDown()
                        check(release.await(60, TimeUnit.SECONDS))
                    })
                }
            }.onFailure(cacheFailure::set)
        }, "detail-refresh-cache-barrier").apply { start() }
        assertTrue(locked.await(10, TimeUnit.SECONDS))
        refresh()
        compose.waitUntil(15_000) { appDb.bookInfoRequestDao.get(route.requestId)?.previewCommitRevision != null &&
            appDb.bookDao.getBook(book.bookUrl)?.name == updatedName }
        lateinit var first: BookInfoViewModel
        lateinit var toc: String
        scenario!!.onActivity {
            first = model(it)
            assertEquals(book.name, first.uiState.value.book!!.name)
            toc = it.getString(R.string.view_toc)
        }
        compose.onNodeWithText(toc).performScrollTo().performClick()
        compose.waitUntil(10_000) { first.uiState.value.childPreparation != null }
        assertNull(appDb.bookInfoRequestDao.activeChild(route.requestId))
        scenario!!.recreate()
        compose.waitUntil(10_000) { var ready = false; scenario!!.onActivity {
            ready = it.currentDestination == route
        }; ready }
        scenario!!.onActivity {
            assertSame(first, model(it))
            assertNotNull(model(it).uiState.value.childPreparation)
            assertEquals(route, it.currentDestination)
        }
        release.countDown()
        cacheThread!!.join(10_000)
        assertNull(cacheFailure.get())
        compose.waitUntil(15_000) { var opened = false; scenario!!.onActivity {
            opened = it.currentDestination is BookTocRoute
        }; opened }
        compose.onNodeWithText("Updated chapter 1").assertIsDisplayed()
        assertEquals(updatedName, appDb.bookDao.getBook(book.bookUrl)!!.name)
        assertEquals(updatedName, BookInfoPreviewStore(context).peek(route.previewKey)!!.book.name)
        assertEquals(1L, appDb.bookInfoRequestDao.get(route.requestId)!!.previewCommitRevision)
        Espresso.pressBack()
        compose.waitUntil(15_000) { var returned = false; scenario!!.onActivity {
            returned = it.currentDestination == route && model(it).uiState.value.recoveryReady &&
                appDb.bookInfoRequestDao.activeChild(route.requestId) == null
        }; returned }
        compose.onNodeWithText(updatedName).assertIsDisplayed()
    }

    @Test fun openingCatalogCannotReinsertAShelfBookDeletedByAnotherOperation() {
        launch()
        appDb.bookDao.delete(book)
        lateinit var toc: String
        scenario!!.onActivity { toc = it.getString(R.string.view_toc) }
        compose.onNodeWithText(toc).performScrollTo().performClick()
        compose.waitUntil(10_000) { var failed = false; scenario!!.onActivity {
            failed = model(it).uiState.value.childPreparation?.failed == true
        }; failed }
        assertFalse(appDb.bookDao.has(book.bookUrl))
        assertNull(appDb.bookInfoRequestDao.activeChild(route.requestId))
        assertEquals(0L, appDb.bookInfoRequestDao.get(route.requestId)!!.revision)
    }

}
