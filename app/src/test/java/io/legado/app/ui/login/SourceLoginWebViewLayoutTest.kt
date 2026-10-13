package io.legado.app.ui.login

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SourceLoginWebViewLayoutTest {

    @Test
    fun `web login root paints the runtime theme background`() {
        val activity = readProjectFile(
            "src/main/java/io/legado/app/ui/login/SourceLoginActivity.kt"
        )

        assertTrue(
            "Web login must paint an opaque runtime theme background",
            activity.contains("setBackgroundColor(backgroundColor)")
        )
    }

    @Test
    fun `web view roots stay above system bars and keyboard`() {
        val login = readProjectFile("src/main/java/io/legado/app/ui/login/SourceLoginActivity.kt")
        assertTrue(login.contains(".imePadding()"))
        assertTrue(login.contains("Scaffold("))
        val browser = readProjectFile("src/main/java/io/legado/app/ui/browser/WebViewActivity.kt")
        assertTrue(browser.contains("setOnApplyWindowInsetsListenerCompat"))
        assertTrue(browser.contains("WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()"))
        assertTrue(browser.contains("bottomPadding = windowInsets.getInsets(typeMask).bottom"))
    }

    private fun readProjectFile(pathInApp: String): String {
        return sequenceOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull(File::isFile)
            ?.readText()
            .orEmpty()
    }
}
