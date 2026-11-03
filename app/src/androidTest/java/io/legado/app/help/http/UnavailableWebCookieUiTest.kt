package io.legado.app.help.http

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.help.CacheManager
import io.legado.app.utils.NetworkUtils
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Run the appropriate case against devices with and without a WebView provider. */
@RunWith(AndroidJUnit4::class)
class UnavailableWebCookieUiTest {
    private val url = "https://login-${UUID.randomUUID()}.invalid"
    private val neighborUrl = "https://neighbor-${UUID.randomUUID()}.invalid"
    private val source = BookSource(bookSourceUrl = url)
    private val neighbor = BookSource(bookSourceUrl = neighborUrl)
    private val header = """{"Cookie":"session=original", "X-Test":"header"}"""

    private fun seed() {
        source.putLoginHeader(header)
        neighbor.putLoginHeader(header)
        CacheManager.putMemory("${NetworkUtils.getSubDomain(url)}_session_cookie", "volatile=original")
    }

    @Test fun noWebViewLeavesLoginHeaderAndCookiesIntact() {
        seed()
        try {
            source.removeLoginHeader()
            throw AssertionError("A missing WebView provider must report the failure")
        } catch (expected: WebCookieUnavailableException) {
            assertTrue(expected.cause is RuntimeException)
        }
        assertEquals(header, source.getLoginHeader())
        assertEquals(header, CacheManager.get("loginHeader_${source.getKey()}", true))
        assertEquals("session=original", CookieManager.getCookieNoSession(url))
        assertEquals("session=original", appDb.cookieDao.get(NetworkUtils.getSubDomain(url))?.cookie)
        assertEquals("volatile=original", CookieManager.getSessionCookie(NetworkUtils.getSubDomain(url)))
        assertEquals(header, neighbor.getLoginHeader())
        assertEquals("session=original", CookieManager.getCookieNoSession(neighborUrl))
    }

    @Test fun availableWebViewClearsOnlyTheRequestedLogin() {
        seed()
        val web = android.webkit.CookieManager.getInstance()
        web.setCookie(url, "web=original; Path=/")
        web.setCookie(neighborUrl, "web=neighbor; Path=/")
        web.flush()
        assertTrue(web.getCookie(url).orEmpty().contains("web=original"))
        source.removeLoginHeader()
        web.flush()
        assertNull(source.getLoginHeader())
        assertNull(CacheManager.get("loginHeader_${source.getKey()}", true))
        assertEquals("", CookieManager.getCookieNoSession(url))
        assertNull(appDb.cookieDao.get(NetworkUtils.getSubDomain(url)))
        assertNull(CookieManager.getSessionCookie(NetworkUtils.getSubDomain(url)))
        assertTrue(!web.getCookie(url).orEmpty().contains("web=original"))
        assertEquals(header, neighbor.getLoginHeader())
        assertEquals("session=original", CookieManager.getCookieNoSession(neighborUrl))
        assertTrue(web.getCookie(neighborUrl).orEmpty().contains("web=neighbor"))
        web.setCookie(neighborUrl, "web=; Path=/; Max-Age=0")
        web.flush()
    }

    @After fun cleanOwnedLocalState() {
        listOf(source, neighbor).forEach {
            val domain = NetworkUtils.getSubDomain(it.bookSourceUrl)
            appDb.cookieDao.delete(domain)
            CacheManager.deleteMemory("${domain}_cookie")
            CacheManager.deleteMemory("${domain}_session_cookie")
            CacheManager.delete("loginHeader_${it.getKey()}")
        }
    }
}
