package io.legado.app.ui.widget.dialog

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UrlOptionDialogContractTest {

    private val source by lazy {
        projectFile("src/main/java/io/legado/app/ui/widget/dialog/UrlOptionDialog.kt")
            .readText()
            .replace("\r\n", "\n")
    }

    @Test
    fun `request and response scripts use their matching setters`() {
        assertTrue(source.contains("urlOption.setJs(jsText)"))
        assertTrue(source.contains("urlOption.setBodyJs(bodyJsText)"))
        assertFalse(source.contains("urlOption.setJs(bodyJsText)"))
    }

    private fun projectFile(pathInApp: String): File {
        return listOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull { it.isFile }
            ?: error("Missing project file: $pathInApp")
    }
}
