package io.legado.app.ui.compat

import android.content.Intent
import android.content.pm.ActivityInfo
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.ui.browser.WebViewActivity
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class WebViewOrientationUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun findWebView(view: View): WebView? = when (view) {
        is WebView -> view
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { findWebView(view.getChildAt(it)) }
        else -> null
    }

    @Test fun webDocumentAndDraftRemainUsableWhenPortraitRequestIsIgnored() {
        OrientationWindowSession("web-document").use { windows ->
            val id = UUID.randomUUID().toString()
            val intent = Intent(instrumentation.targetContext, WebViewActivity::class.java)
                .putExtra("url", "https://orientation-$id.invalid/")
                .putExtra("title", "Orientation fixture")
                .putExtra("html", """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"></head>
                    <body><div style="position:sticky;top:0;background:#dce8ff;padding:8px">
                    <label for="draft">Preserved draft</label><textarea id="draft" style="width:90%;height:80px"></textarea></div>
                    ${(0..80).joinToString("") { "<p style='padding:12px;background:${if (it % 2 == 0) "#edf6f0" else "#f5eee6"}'>Scrollable fixture row $it</p>" }}
                    </body></html>""")
            ActivityScenario.launch<WebViewActivity>(intent).use { scenario ->
                lateinit var activity: WebViewActivity
                lateinit var web: WebView
                val deadline = android.os.SystemClock.uptimeMillis() + 20_000
                while (android.os.SystemClock.uptimeMillis() < deadline) {
                    var ready = false
                    scenario.onActivity { host ->
                        activity = host
                        findWebView(host.window.decorView)?.let { web = it; ready = it.progress == 100 }
                    }
                    if (ready && evaluate(web, "Boolean(document.getElementById('draft'))") == "true") break
                    android.os.SystemClock.sleep(100)
                }
                assertEquals("true", evaluate(web, "Boolean(document.getElementById('draft'))"))
                evaluate(web, "document.getElementById('draft').value='$id';window.compatibilityMarker='$id';window.scrollTo(0,500);true")
                scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
                for ((width, height) in listOf(1280 to 800, 800 to 1280, 900 to 850, 1280 to 800)) {
                    windows.resize(width, height, { activity }) {
                        val raw = evaluate(web, "JSON.stringify({draft:document.getElementById('draft').value,marker:window.compatibilityMarker,width:innerWidth,height:innerHeight,scroll:scrollY})")
                        val state = JSONObject(JSONTokener(raw).nextValue() as String)
                        assertEquals(id, state.getString("draft"))
                        assertEquals(id, state.getString("marker"))
                        assertTrue(state.getInt("width") > 0 && state.getInt("height") > 0)
                        assertEquals(width > height, state.getInt("width") > state.getInt("height"))
                        assertTrue("Document must remain scrolled", state.getInt("scroll") > 0)
                        state
                    }
                }
                scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
                instrumentation.waitForIdleSync()
                assertTrue(activity.isFinishing || activity.isDestroyed)
            }
        }
    }

    private fun evaluate(web: WebView, script: String): String {
        val result = arrayOfNulls<String>(1)
        val ready = CountDownLatch(1)
        instrumentation.runOnMainSync { web.evaluateJavascript(script) { result[0] = it; ready.countDown() } }
        assertTrue("WebView did not evaluate fixture script", ready.await(10, TimeUnit.SECONDS))
        return checkNotNull(result[0])
    }
}
