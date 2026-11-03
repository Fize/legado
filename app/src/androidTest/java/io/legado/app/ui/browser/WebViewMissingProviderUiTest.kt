package io.legado.app.ui.browser

import android.content.Intent
import android.webkit.WebView
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.help.source.SourceVerificationHelp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WebViewMissingProviderUiTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    @get:Rule val compose = createEmptyComposeRule()

    @Test @OptIn(ExperimentalTestApi::class)
    fun missingProviderShowsGuidanceAndCancelsVerificationOnReturn() {
        assertNull(WebView.getCurrentWebViewPackage())
        val key = SourceVerificationHelp.registerVerificationAttempt(Thread.currentThread())
        try {
            val intent = Intent(context, WebViewActivity::class.java)
                .putExtra("url", "https://example.invalid/verification")
                .putExtra("title", "Verification fixture")
                .putExtra("verificationResultKey", key)
                .putExtra("sourceVerificationEnable", true)
            ActivityScenario.launch<WebViewActivity>(intent).use { scenario ->
                repeat(2) {
                    compose.waitUntilAtLeastOneExists(hasText(context.getString(R.string.web_view_unavailable)), 15_000)
                    assertNull(SourceVerificationHelp.getResult(key))
                    if (it == 0) scenario.recreate()
                }
                scenario.onActivity { it.finish() }
                compose.waitUntil(5_000) { SourceVerificationHelp.getResult(key) != null }
                assertEquals("" to "", SourceVerificationHelp.getResult(key))
            }
        } finally {
            SourceVerificationHelp.clearResult(key)
        }
    }
}
