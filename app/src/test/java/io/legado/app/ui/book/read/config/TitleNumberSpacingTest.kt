package io.legado.app.ui.book.read.config

import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class TitleNumberSpacingTest {

    @Test
    fun `spacing maps to seek bar progress`() {
        assertEquals(0, titleNumberSpacingToProgress(-50))
        assertEquals(50, titleNumberSpacingToProgress(0))
        assertEquals(150, titleNumberSpacingToProgress(100))
    }

    @Test
    fun `seek bar progress maps to spacing`() {
        assertEquals(-50, titleNumberSpacingFromProgress(0))
        assertEquals(0, titleNumberSpacingFromProgress(50))
        assertEquals(100, titleNumberSpacingFromProgress(150))
    }

    @Test
    fun `out of range values are clamped`() {
        assertEquals(0, titleNumberSpacingToProgress(-51))
        assertEquals(150, titleNumberSpacingToProgress(101))
        assertEquals(-50, titleNumberSpacingFromProgress(-1))
        assertEquals(100, titleNumberSpacingFromProgress(151))
    }

    @Test
    fun `spacing seek bar exposes the mapped range`() {
        val source = projectFile("src/main/java/io/legado/app/ui/book/read/config/TipConfigDialog.kt").readText()
        val spacingSection = source.substringAfter("titleRes = R.string.title_number_spacing")
            .substringBefore("titleRes =")

        org.junit.Assert.assertTrue(spacingSection.contains("max = 150"))
    }

    private fun projectFile(pathInApp: String): File {
        return listOf(File(pathInApp), File("app/$pathInApp"))
            .first { it.isFile }
    }
}
