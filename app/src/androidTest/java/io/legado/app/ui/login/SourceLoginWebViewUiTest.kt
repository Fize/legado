package io.legado.app.ui.login

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fi.iki.elonen.NanoHTTPD
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.help.CacheManager
import io.legado.app.help.http.CookieStore
import io.legado.app.ui.AppActivity
import io.legado.app.ui.navigation.SourceLoginRoute
import io.legado.app.ui.navigation.SourceLoginTarget
import io.legado.app.utils.NetworkUtils
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Exercises a traditional source login through the production WebView route and cookie store. */
@RunWith(AndroidJUnit4::class)
class SourceLoginWebViewUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val id = UUID.randomUUID().toString()
    private val requests = AtomicInteger()
    private val cookieObserved = AtomicBoolean()
    private val server = object : NanoHTTPD("127.0.0.1", 0) {
        override fun serve(session: IHTTPSession): Response {
            requests.incrementAndGet()
            if (session.parameters["login"]?.isNotEmpty() == true) {
                return newFixedLengthResponse(
                    Response.Status.OK,
                    "text/html",
                    page("Login accepted", completed = true),
                ).apply {
                    addHeader("Set-Cookie", "session=$id; Path=/; SameSite=Lax")
                }
            }
            session.headers.entries.firstOrNull {
                it.key.equals("cookie", ignoreCase = true)
            }?.value?.let { cookies ->
                if (cookies.contains("session=$id")) cookieObserved.set(true)
            }
            return newFixedLengthResponse(
                Response.Status.OK,
                "text/html",
                page("WebView login $id", completed = false),
            )
        }
    }.also(NanoHTTPD::start)
    private val source = BookSource(
        bookSourceUrl = "http://127.0.0.1:${server.listeningPort}",
        bookSourceName = "WebView login $id",
        loginUrl = "/login",
    )

    @get:Rule val compose = createEmptyComposeRule()

    @Test @OptIn(ExperimentalTestApi::class)
    fun realWebViewLoginPersistsCookieAcrossRecreationAndReturnsThroughProductionRoute() {
        assertNotNull("A WebView provider is required for this case", WebView.getCurrentWebViewPackage())
        appDb.bookSourceDao.insert(source)
        val route = SourceLoginRoute(SourceLoginTarget.BookSource, key = source.bookSourceUrl)
        ActivityScenario.launch<AppActivity>(AppActivity.startIntent(context, route)).use { scenario ->
            compose.waitUntilAtLeastOneExists(
                hasText(context.getString(R.string.login_source, source.bookSourceName)),
                15_000,
            )
            awaitPage(scenario, "WebView login $id")
            val submitted = evaluate(
                scenario,
                """
                    document.getElementById('account').value = '$id';
                    document.getElementById('submit').click();
                    true
                """.trimIndent(),
            )
            assertEquals("true", submitted)
            awaitPage(scenario, "Login accepted")
            val acceptedCookie = evaluate(scenario, "document.cookie")
            assertTrue("Login page did not set its session cookie: $acceptedCookie", acceptedCookie.contains("session=$id"))
            scenario.recreate()
            compose.waitUntilAtLeastOneExists(
                hasText(context.getString(R.string.login_source, source.bookSourceName)),
                15_000,
            )
            awaitPage(scenario, "WebView login $id")
            val restoredCookie = evaluate(scenario, "document.cookie")
            assertTrue("WebView lost the login cookie on recreation: $restoredCookie", restoredCookie.contains("session=$id"))
            compose.onNodeWithContentDescription(context.getString(R.string.ok)).performClick()
            compose.waitUntil(15_000) { requests.get() >= 4 }
            assertTrue("The WebView login request did not carry its new cookie", cookieObserved.get())
            compose.waitUntil(15_000) {
                CookieStore.getCookie(source.bookSourceUrl).contains("session=$id")
            }
            compose.waitUntil(15_000) {
                runCatching {
                    compose.onAllNodesWithText(
                        context.getString(R.string.login_source, source.bookSourceName),
                    ).fetchSemanticsNodes().isEmpty()
                }.getOrDefault(true)
            }
            assertTrue("The check action did not reload the real login page", requests.get() >= 4)
        }
        assertTrue(CookieStore.getCookie(source.bookSourceUrl).contains("session=$id"))
    }

    private fun page(title: String, completed: Boolean): String =
        """
            <!doctype html>
            <html><head><meta name="viewport" content="width=device-width,initial-scale=1"></head>
            <body>
              <script>
                ${if (completed) "document.cookie = 'session=$id; path=/';" else ""}
              </script>
              <h1 id="title">$title</h1>
              <input id="account" autocomplete="username" value="${if (completed) id else ""}">
              <button id="submit" type="button" onclick="location.href='/login?login=1'">Sign in</button>
              <p>${if (completed) "Cookie issued for $id" else "Real WebView login fixture"}</p>
            </body></html>
        """.trimIndent()

    private fun awaitPage(scenario: ActivityScenario<AppActivity>, expectedTitle: String) {
        val deadline = SystemClock.uptimeMillis() + 20_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (runCatching {
                    evaluate(
                        scenario,
                        "document.readyState === 'complete' && document.getElementById('title')?.textContent === '$expectedTitle'",
                    ) == "true"
                }.getOrDefault(false)
            ) {
                return
            }
            SystemClock.sleep(100)
        }
        throw AssertionError("WebView did not reach page $expectedTitle; requests=${requests.get()}")
    }

    private fun evaluate(scenario: ActivityScenario<AppActivity>, script: String): String {
        var webView: WebView? = null
        scenario.onActivity { webView = findWebView(it.window.decorView) }
        val target = checkNotNull(webView) { "Production login WebView is absent" }
        val result = arrayOfNulls<String>(1)
        val completed = CountDownLatch(1)
        instrumentation.runOnMainSync {
            target.evaluateJavascript(script) {
                result[0] = it
                completed.countDown()
            }
        }
        check(completed.await(10, TimeUnit.SECONDS)) { "WebView script timed out" }
        return checkNotNull(result[0])
    }

    private fun findWebView(view: View): WebView? = when (view) {
        is WebView -> view
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull {
            findWebView(view.getChildAt(it))
        }
        else -> null
    }

    @After
    fun cleanup() {
        runCatching { source.removeLoginHeader() }
        appDb.bookSourceDao.delete(source)
        val domain = NetworkUtils.getSubDomain(source.bookSourceUrl)
        appDb.cookieDao.delete(domain)
        CacheManager.deleteMemory("${domain}_cookie")
        CacheManager.deleteMemory("${domain}_session_cookie")
        CacheManager.delete("loginHeader_${source.getKey()}")
        CacheManager.delete("userInfo_${source.getKey()}")
        server.stop()
    }
}
