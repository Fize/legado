package io.legado.app.ui.book.info

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.entities.BookSource
import io.legado.app.data.repository.BookIntroParser
import io.legado.app.help.config.LocalConfig
import io.legado.app.ui.AppActivity
import io.legado.app.ui.book.info.intro.BookIntroContent
import io.legado.app.ui.navigation.AboutRoute
import io.legado.app.utils.defaultSharedPreferences
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class BookIntroContentUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val state = mutableStateOf(BookInfoIntroUiState())
    private val clicked = AtomicReference<Pair<String, String>?>()
    private val linked = AtomicReference<String?>()
    private val overflow = AtomicReference<Pair<String?, Boolean>?>()
    private fun show(raw: String, source: BookSource? = null, body: (ActivityScenario<AppActivity>) -> Unit) {
        state.value = BookInfoIntroUiState(document = BookIntroParser.parse(raw, "https://example.com/book/1"), contentKey = "fixture", visible = true)
        val privacy = LocalConfig.all["privacyPolicyOk"]
        LocalConfig.edit().putBoolean("privacyPolicyOk", true).commit()
        try {
            ActivityScenario.launch<AppActivity>(AppActivity.startIntent(context, AboutRoute)).use { scenario ->
                scenario.onActivity { activity -> activity.setContent {
                    MaterialTheme {
                        Column(Modifier.width(280.dp).background(MaterialTheme.colorScheme.background).verticalScroll(rememberScrollState())) {
                            BookIntroContent(state.value, source, "https://example.com/book/1",
                                onOverflow = { key, value -> overflow.set(key to value) },
                                onToggle = { state.value = state.value.copy(expanded = !state.value.expanded) },
                                onRetry = {}, onButton = { name, script -> clicked.set(name to script) },
                                onImage = { linked.set(it) }, onLink = { linked.set(it) })
                        }
                    }
                } }
                body(scenario)
            }
        } finally {
            LocalConfig.edit().apply { if (privacy == null) remove("privacyPolicyOk") else putBoolean("privacyPolicyOk", privacy as Boolean) }.commit()
        }
    }
    @Test fun markdownTableHasFiniteWidthAndKeepsAllCells() = show("<md>| Name | Value |\n| --- | --- |\n| Alpha | **Beta** |</md>") {
        compose.onNodeWithTag("book_intro_table").assertIsDisplayed()
        compose.onNodeWithText("Alpha").assertIsDisplayed()
        compose.onNodeWithText("Beta").assertIsDisplayed()
        val bounds = compose.onNodeWithTag("book_intro_table").fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.width.isFinite() && bounds.width > 0)
        capture("intro-table.png")
    }
    @Test fun htmlButtonRetainsScriptAndCanBeClicked() = show("<usehtml><p><button>Run@onclick:book.setName('changed')</button></p></usehtml>") {
        compose.onNodeWithTag("intro_button_0").performClick()
        assertEquals("Run" to "book.setName('changed')", clicked.get())
    }
    @Test fun htmlLinksResolveAgainstBookAndRemainInteractive() = show("<usehtml><a href='../next'>Next</a></usehtml>") {
        compose.onNodeWithText("Next").performTouchInput { click(androidx.compose.ui.geometry.Offset(12f, centerY)) }
        assertEquals("https://example.com/next", linked.get())
    }
    @Test fun plainTextRetainsLiteralMarkupAndCollapsedBounds() = show((1..20).joinToString("\n") { "Line $it <b>literal</b>" }) {
        compose.waitUntil(5_000) { overflow.get()?.second == true }
        val full = compose.onNodeWithTag("book_intro").fetchSemanticsNode().boundsInRoot.height
        instrumentation.runOnMainSync { state.value = state.value.copy(expanded = false, canCollapse = true, toggleVisible = true) }
        compose.waitForIdle()
        val collapsed = compose.onNodeWithTag("book_intro").fetchSemanticsNode().boundsInRoot.height
        assertTrue("Collapsed intro must be shorter: $collapsed / $full", collapsed < full)
        compose.onNodeWithTag("book_intro_text").assertTextContains("Line 1 <b>literal</b>", substring = true)
        compose.onNodeWithTag("book_intro_toggle").performClick()
        assertTrue(state.value.expanded)
    }
    @Test fun explicitWebDocumentRendersJavascriptAndReleasesWhenReplaced() = show(
        "<useweb><html><body style='height:180px'><div id='ready'>Intro</div><script>window.bridgeAtLoad=[typeof source,typeof java,typeof cache].join(',');document.getElementById('ready').textContent='Script ready';</script></body></html></useweb>",
        BookSource("https://example.com/source", "Intro source"),
    ) { scenario ->
        var web: WebView? = null
        compose.waitUntil(10_000) {
            scenario.onActivity { web = findWeb(it.window.decorView) }
            web != null
        }
        val result = AtomicReference<String?>()
        compose.waitUntil(10_000) {
            scenario.onActivity { web!!.evaluateJavascript("document.getElementById('ready')?.textContent", result::set) }
            result.get() == "\"Script ready\""
        }
        scenario.onActivity { web!!.evaluateJavascript("window.bridgeAtLoad", result::set) }
        compose.waitUntil(5_000) { result.get() == "\"object,object,object\"" }
        compose.waitUntil(5_000) { runCatching { compose.onNodeWithTag("book_intro_web").assertIsDisplayed(); true }.getOrDefault(false) }
        compose.onNodeWithTag("book_intro_web").assertIsDisplayed()
        assertTrue("Web document needs nonzero layout height", compose.onNodeWithTag("book_intro_web").fetchSemanticsNode().boundsInRoot.height > 0)
        repeat(3) { index ->
            instrumentation.runOnMainSync {
                state.value = state.value.copy(contentKey = "web-reload-$index", document = BookIntroParser.parse(
                    "<useweb><div id='ready'>Pending</div><script>window.bridgeAtLoad=[typeof source,typeof java,typeof cache].join(',');document.getElementById('ready').textContent='Reload $index';</script></useweb>","https://example.com/book/1"))
            }
            compose.waitUntil(10_000) {
                scenario.onActivity { web=findWeb(it.window.decorView);web?.evaluateJavascript("document.getElementById('ready')?.textContent",result::set) }
                result.get()=="\"Reload $index\""
            }
            scenario.onActivity { web!!.evaluateJavascript("window.bridgeAtLoad",result::set) }
            compose.waitUntil(5_000) { result.get()=="\"object,object,object\"" }
        }
        instrumentation.runOnMainSync { state.value = state.value.copy(contentKey = "new", document = BookIntroParser.parse("Replacement", "")) }
        compose.onNodeWithText("Replacement").assertIsDisplayed()
        compose.onAllNodesWithTag("book_intro_web").assertCountEquals(0)
        scenario.onActivity { assertNull(findWeb(it.window.decorView)) }
    }
    @Test fun sourceAuthenticatedImageRetriesThenSupportsScriptAndLongPress() {
        val prefs = context.defaultSharedPreferences
        val cronet = prefs.all[io.legado.app.constant.PreferKey.cronet]
        prefs.edit().putBoolean(io.legado.app.constant.PreferKey.cronet, false).commit()
        val requests = java.util.concurrent.atomic.AtomicInteger()
        val failed = java.util.concurrent.atomic.AtomicBoolean(true)
        val token = java.util.UUID.randomUUID().toString()
        val bytes = java.io.ByteArrayOutputStream().also { output ->
            android.graphics.Bitmap.createBitmap(80, 40, android.graphics.Bitmap.Config.ARGB_8888).apply {
                eraseColor(android.graphics.Color.GREEN)
                compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)
                recycle()
            }
        }.toByteArray()
        val server = object : fi.iki.elonen.NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response {
                requests.incrementAndGet()
                if (session.headers["x-intro"] != token) return newFixedLengthResponse(Response.Status.UNAUTHORIZED, "text/plain", "Missing source headers")
                if (failed.get()) return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Retry fixture")
                return newFixedLengthResponse(Response.Status.OK, "image/png", bytes.inputStream(), bytes.size.toLong())
            }
        }.apply { start(fi.iki.elonen.NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
        val origin = "http://127.0.0.1:${server.listeningPort}/$token"
        val source = BookSource(origin, "Image source", header = "{\"X-Intro\":\"$token\"}")
        io.legado.app.data.appDb.bookSourceDao.insert(source)
        try {
            val url = "$origin/image.png"
            show("<usehtml><img src=\"$url\" alt=\"Loaded intro image\" onclick=\"book.setName('image')\"></usehtml>", source) { scenario ->
                var retry = ""
                scenario.onActivity { retry = it.getString(io.legado.app.R.string.retry) }
                compose.waitUntil(10_000) { compose.onAllNodesWithText(retry).fetchSemanticsNodes().isNotEmpty() }
                failed.set(false)
                compose.onNodeWithText(retry).performClick()
                compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Loaded intro image").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithContentDescription("Loaded intro image").assertIsDisplayed()
                assertTrue(requests.get() >= 2)
                compose.onNodeWithTag("intro_image_0").performClick()
                assertEquals("image" to "book.setName('image')", clicked.get())
                compose.onNodeWithTag("intro_image_0").performTouchInput { longClick() }
                assertEquals(url, linked.get())
                capture("intro-image.png")
            }
        } finally {
            server.stop()
            io.legado.app.data.appDb.bookSourceDao.delete(source)
            prefs.edit().apply { if (cronet == null) remove(io.legado.app.constant.PreferKey.cronet) else putBoolean(io.legado.app.constant.PreferKey.cronet, cronet as Boolean) }.commit()
        }
    }

    private fun capture(name: String) {
        val bitmap = compose.onNodeWithTag(if (name == "intro-table.png") "book_intro_table" else "book_intro").captureToImage().asAndroidBitmap()
        java.io.File(context.cacheDir, name).outputStream().use { output ->
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)
        }
    }
    private fun findWeb(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findWeb(view.getChildAt(i))?.let { return it }
        return null
    }
}
