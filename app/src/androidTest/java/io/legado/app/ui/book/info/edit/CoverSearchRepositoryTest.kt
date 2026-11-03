package io.legado.app.ui.book.info.edit

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fi.iki.elonen.NanoHTTPD
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.rule.SearchRule
import io.legado.app.data.repository.CoverSearchRepository
import io.legado.app.utils.defaultSharedPreferences
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class CoverSearchRepositoryTest {
    private val prefs = InstrumentationRegistry.getInstrumentation().targetContext.defaultSharedPreferences
    private val oldCronet = prefs.all[PreferKey.cronet]
    private val id = UUID.randomUUID().toString()
    private val title = "Cover search $id"
    private val repository = CoverSearchRepository(appDb)
    private lateinit var source: BookSource
    private lateinit var server: NanoHTTPD
    private var author = "Author"
    private var gate: CountDownLatch? = null
    private val requests = AtomicInteger()
    @Before fun setup() {
        prefs.edit().putBoolean(PreferKey.cronet, false).commit()
        server = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response {
                requests.incrementAndGet(); gate?.await(10, TimeUnit.SECONDS)
                return newFixedLengthResponse("<article><h2>$title</h2><span class='author'>$author</span><a href='/book'>Book</a><img src='/cover.png'></article>")
            }
        }.apply { start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
        val base = "http://127.0.0.1:${server.listeningPort}"
        source = BookSource(base, "Search fixture", searchUrl = "$base/search?key={{key}}", ruleSearch = SearchRule(bookList = "article", name = "h2@text", author = ".author@text", bookUrl = "a@href", coverUrl = "img@src"))
        appDb.bookSourceDao.insert(source)
    }
    @After fun cleanup() {
        gate?.countDown(); server.stop(); appDb.bookSourceDao.delete(source)
        prefs.edit().apply { if (oldCronet is Boolean) putBoolean(PreferKey.cronet, oldCronet) else remove(PreferKey.cronet) }.commit()
    }
    @Test fun parsesExactMatchAndCachesTheCoverWithItsSource() = runBlocking {
        val result = checkNotNull(repository.search(source.bookSourceUrl, title, "Author"))
        assertEquals("${source.bookSourceUrl}/cover.png", result.url)
        assertEquals(source.bookSourceUrl, result.source)
        assertEquals(listOf(result), repository.cached(title, "Author"))
        assertEquals(1, requests.get())
    }
    @Test fun mismatchedAuthorDoesNotEnterTheCache() = runBlocking {
        author = "Other author"
        assertNull(repository.search(source.bookSourceUrl, title, "Author"))
        assertTrue(repository.cached(title, "Author").isEmpty())
    }
    @Test fun disablingSourceWhileItsRequestRunsDiscardsTheResult() = runBlocking {
        gate = CountDownLatch(1)
        val request = async(Dispatchers.IO) { repository.search(source.bookSourceUrl, title, "Author") }
        withTimeout(10000) { while (requests.get() == 0) delay(20) }
        appDb.bookSourceDao.enable(source.bookSourceUrl, false)
        gate!!.countDown()
        assertNull(request.await())
        appDb.bookSourceDao.enable(source.bookSourceUrl, true)
        assertTrue(repository.cached(title, "Author").isEmpty())
    }
    @Test fun cancellationDoesNotPublishOrCacheItsResponse() = runBlocking {
        gate = CountDownLatch(1)
        val request = launch(Dispatchers.IO) { repository.search(source.bookSourceUrl, title, "Author") }
        withTimeout(10000) { while (requests.get() == 0) delay(20) }
        request.cancel(); gate!!.countDown(); request.join()
        assertTrue(repository.cached(title, "Author").isEmpty())
    }
}
