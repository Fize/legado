package io.legado.app.ui.login

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.TextRange
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.os.SystemClock
import android.os.ParcelFileDescriptor
import android.view.accessibility.AccessibilityNodeInfo
import android.view.KeyEvent
import android.webkit.WebView
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.help.CacheManager
import io.legado.app.help.http.CookieManager
import io.legado.app.ui.AppActivity
import io.legado.app.ui.navigation.SourceLoginRoute
import io.legado.app.ui.navigation.SourceLoginTarget
import io.legado.app.utils.NetworkUtils
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import org.json.JSONObject

/** Exercises real login actions on a device without an available WebView provider. */
@RunWith(AndroidJUnit4::class)
class SourceLoginMissingWebViewUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val url = "https://login-ui-${UUID.randomUUID()}.invalid"
    private val source = BookSource(bookSourceUrl = url, bookSourceName = "Login fixture", loginUi = """[{"name":"username","type":"text"}]""")
    private val header = """{"Cookie":"session=original", "X-Test":"header"}"""
    @get:Rule val compose = createEmptyComposeRule()

    @Test @OptIn(ExperimentalTestApi::class)
    fun webLoginWithoutProviderShowsRecoveryAndKeepsSourceData() {
        assertNull(WebView.getCurrentWebViewPackage())
        val webSource = source.copy(loginUi = null, loginUrl = "https://example.invalid/login")
        appDb.bookSourceDao.insert(webSource)
        webSource.putLoginHeader(header)
        assertTrue(webSource.putLoginInfo("""{"username":"original"}"""))
        val savedInfo = webSource.getLoginInfo()
        val route = SourceLoginRoute(SourceLoginTarget.BookSource, key = url)
        ActivityScenario.launch<AppActivity>(AppActivity.startIntent(context, route)).use { scenario ->
            repeat(2) {
                compose.waitUntilAtLeastOneExists(hasText(context.getString(R.string.web_login_unavailable)), 15_000)
                assertEquals(header, webSource.getLoginHeader())
                assertEquals(savedInfo, webSource.getLoginInfo())
                scenario.onActivity { assertTrue(!it.isFinishing) }
                if (it == 0) scenario.recreate()
            }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        }
        assertEquals(header, webSource.getLoginHeader())
        assertEquals(savedInfo, webSource.getLoginInfo())
    }

    @Test @OptIn(ExperimentalTestApi::class)
    fun clearingLoginKeepsDataAndShowsRetryMessage() {
        appDb.bookSourceDao.insert(source)
        source.putLoginHeader(header)
        assertTrue(source.putLoginInfo("""{"username":"original"}"""))
        val savedInfo = source.getLoginInfo()
        val route = SourceLoginRoute(SourceLoginTarget.BookSource, key = url)
        val automation = instrumentation.uiAutomation
        ActivityScenario.launch<AppActivity>(AppActivity.startIntent(context, route)).use { scenario ->
            try {
                compose.waitUntilAtLeastOneExists(hasText(context.getString(R.string.login_source, source.bookSourceName)), 15_000)
            } catch (failure: Throwable) {
                scenario.onActivity { host ->
                    android.util.Log.e("LoginMissingWebView", "fragments=" + host.supportFragmentManager.fragments)
                    android.util.Log.e("LoginMissingWebView", "activitySource=" + androidx.lifecycle.ViewModelProvider(host)[SourceLoginViewModel::class.java].source?.getKey())
                }
                throw failure
            }
            fun openMenu() = compose.onNodeWithContentDescription(context.getString(R.string.more_menu)).performClick()
            compose.onNode(hasSetTextAction()).performTextReplacement("pending-draft")
            compose.onNode(hasSetTextAction()).performTextInputSelection(TextRange(13))
            compose.onNode(hasSetTextAction()).assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(13)))
            repeat(2) {
                scenario.recreate()
                compose.waitUntilAtLeastOneExists(hasText(context.getString(R.string.login_source, source.bookSourceName)), 15_000)
                compose.onNode(hasSetTextAction()).assert(SemanticsMatcher("exact restored draft") { node ->
                    node.config.getOrNull(SemanticsProperties.EditableText)?.text == "pending-draft" &&
                        node.config.getOrNull(SemanticsProperties.InputText)?.text == "pending-draft"
                })
                compose.onNode(hasSetTextAction()).assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(13)))
            }
            compose.onNode(hasSetTextAction()).performTextInput("-after")
            compose.onNode(hasSetTextAction()).assert(SemanticsMatcher("exact continued draft") { node ->
                node.config.getOrNull(SemanticsProperties.EditableText)?.text == "pending-draft-after"
            })
            compose.onNode(hasSetTextAction()).performTextInputSelection(TextRange(2, 7))
            scenario.recreate()
            compose.waitUntilAtLeastOneExists(hasText(context.getString(R.string.login_source, source.bookSourceName)), 15_000)
            compose.onNode(hasSetTextAction()).assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(2, 7)))
            compose.onNode(hasSetTextAction()).performTextInput("X")
            compose.onNode(hasSetTextAction()).assert(SemanticsMatcher("exact selection replacement") { node ->
                node.config.getOrNull(SemanticsProperties.EditableText)?.text == "peX-draft-after"
            })
            fun checkState() {
                assertEquals(header, source.getLoginHeader())
                assertEquals(savedInfo, source.getLoginInfo())
                assertEquals("session=original", CookieManager.getCookieNoSession(url))
            }
            fun expectRetryMessage(action: () -> Unit) {
                val expected = context.getString(R.string.web_cookie_unavailable)
                val event = automation.executeAndWaitForEvent(
                    { action() },
                    { it.text.any { text -> text.toString() == expected } },
                    5_000,
                )
                event.recycle()
            }
            openMenu()
            expectRetryMessage { compose.onNodeWithText(context.getString(R.string.del_login_header)).performClick() }
            checkState()
            openMenu()
            compose.onNodeWithText(context.getString(R.string.clear_login_info)).performClick()
            var yes: AccessibilityNodeInfo? = null
            val deadline = SystemClock.uptimeMillis() + 5_000
            while (yes == null && SystemClock.uptimeMillis() < deadline) {
                yes = automation.rootInActiveWindow?.findAccessibilityNodeInfosByText(context.getString(R.string.yes))
                    ?.firstOrNull { it.isClickable }
                if (yes == null) SystemClock.sleep(50)
            }
            val yesButton = checkNotNull(yes)
            expectRetryMessage { assertTrue(yesButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)) }
            checkState()
            compose.onNodeWithContentDescription(context.getString(R.string.more_menu)).assertExists()
        }
    }

    @Test @OptIn(ExperimentalTestApi::class)
    fun closingAfterRecreationSavesTheEditedLogin() {
        appDb.bookSourceDao.insert(source)
        assertTrue(source.putLoginInfo("""{"username":"original"}"""))
        val route = SourceLoginRoute(SourceLoginTarget.BookSource, key = url)
        ActivityScenario.launch<AppActivity>(AppActivity.startIntent(context, route)).use { scenario ->
            compose.waitUntilAtLeastOneExists(hasText(context.getString(R.string.login_source, source.bookSourceName)), 15_000)
            compose.onNode(hasSetTextAction()).performTextReplacement("saved-after-recreate")
            assertEquals("original", JSONObject(source.getLoginInfo().orEmpty()).getString("username"))
            scenario.recreate()
            compose.waitUntilAtLeastOneExists(hasText(context.getString(R.string.login_source, source.bookSourceName)), 15_000)
            compose.onNode(hasSetTextAction()).assert(SemanticsMatcher("exact draft after recreation") { node ->
                node.config.getOrNull(SemanticsProperties.EditableText)?.text == "saved-after-recreate"
            })
            assertEquals("original", JSONObject(source.getLoginInfo().orEmpty()).getString("username"))
            compose.waitForIdle()
            fun imeShown(): Boolean = ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand("dumpsys input_method"),
            ).bufferedReader().use { it.readText().contains("mInputShown=true") }
            var hiddenSince = 0L
            var lastBack = 0L
            compose.waitUntil(10_000) {
                val now = SystemClock.uptimeMillis()
                if (imeShown()) {
                    hiddenSince = 0L
                    if (lastBack == 0L || now - lastBack >= 2_000) {
                        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                        lastBack = now
                    }
                    false
                } else {
                    if (hiddenSince == 0L) hiddenSince = now
                    now - hiddenSince >= 750
                }
            }
            assertTrue(compose.onAllNodesWithContentDescription(context.getString(R.string.more_menu)).fetchSemanticsNodes().isNotEmpty())
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            compose.waitUntil(10_000) {
                runCatching { JSONObject(source.getLoginInfo().orEmpty()).getString("username") == "saved-after-recreate" }
                    .getOrDefault(false)
            }
        }
        assertEquals("saved-after-recreate", JSONObject(source.getLoginInfo().orEmpty()).getString("username"))
    }

    @After fun cleanOwnedState() {
        appDb.bookSourceDao.delete(source)
        val domain = NetworkUtils.getSubDomain(url)
        appDb.cookieDao.delete(domain)
        CacheManager.deleteMemory("${domain}_cookie")
        CacheManager.deleteMemory("${domain}_session_cookie")
        CacheManager.delete("loginHeader_${source.getKey()}")
        CacheManager.delete("userInfo_${source.getKey()}")
    }
}
