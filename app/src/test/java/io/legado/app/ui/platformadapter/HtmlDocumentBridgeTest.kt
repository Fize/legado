package io.legado.app.ui.platformadapter

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class HtmlDocumentBridgeTest {
    @Test fun bridgeComesBeforeSourceScriptsAndKeepsDoctypeAndRawScriptData() {
        val raw="if (1 < 2) window.value = '<unchanged>';"
        val result=htmlWithDocumentBridge("<!doctype html><html><head><script>$raw</script></head><body>Body</body></html>","window.bridge=true;")
        val parsed=Jsoup.parse(result)
        assertTrue(result.startsWith("<!doctype html>",true))
        assertEquals("window.bridge=true;",parsed.select("script")[0].data())
        assertEquals(raw,parsed.select("script")[1].data())
        assertEquals("Body",parsed.body().text())
    }
    @Test fun htmlFragmentsAndEmptyDocumentsHaveAnInitialBridge() {
        listOf("", "<p>Fragment</p><script>useBridge();</script>").forEach { html ->
            val result=Jsoup.parse(htmlWithDocumentBridge(html,"var bridge = nativeBridge;"))
            assertEquals("var bridge = nativeBridge;",result.head().selectFirst("script")!!.data())
            assertTrue(result.selectFirst("script")!!.parent()===result.head())
        }
    }
}
