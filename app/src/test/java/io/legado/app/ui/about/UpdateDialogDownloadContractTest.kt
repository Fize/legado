package io.legado.app.ui.about

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UpdateDialogDownloadContractTest {

    @Test
    fun `beta update uses the system download flow`() {
        val source = projectFile(
            "src/main/java/io/legado/app/ui/about/UpdateDialog.kt"
        ).readText()
        val clickHandler = source.substringAfter(
            "// btnBetaUpdate"
        ).substringBefore("private fun formatUpdateMetadata")

        assertTrue(clickHandler.contains("startDownload(arguments?.getString(\"url\"))"))
        assertFalse(clickHandler.contains("openUrl"))
    }

    @Test
    fun `beta update exposes browser fallback in toolbar`() {
        val source = projectFile("src/main/java/io/legado/app/ui/about/UpdateDialog.kt").readText()
        assertTrue(source.contains("val showOpenInBrowser = true"))
        assertTrue(source.contains("R.string.open_in_browser"))
        assertTrue(source.contains("arguments?.getString(\"url\").orEmpty()"))
    }

    private fun projectFile(pathInApp: String): File {
        return sequenceOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull(File::isFile)
            ?: error("Project file not found: $pathInApp")
    }
}
