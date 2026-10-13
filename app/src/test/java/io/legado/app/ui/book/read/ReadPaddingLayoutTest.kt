package io.legado.app.ui.book.read

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReadPaddingLayoutTest {

    @Test
    fun `padding panel uses four shared controls`() {
        val source = readPaddingDialogSource()

        assertTrue(source.contains(".verticalScroll(rememberScrollState())"))
        val screenBody = source.substringAfter("private fun PaddingConfigScreen()")
            .substringBefore("private fun PaddingSeekBarControl(")
        val count = Regex("""PaddingSeekBarControl\(""").findAll(screenBody).count()
        assertEquals(4, count)
    }

    @Test
    fun `vertical padding supports the extended range`() {
        val source = readPaddingDialogSource()
        assertTrue(source.contains("titleRes = R.string.padding_top") && source.contains("max = 400"))
        assertTrue(source.contains("titleRes = R.string.padding_bottom") && source.contains("max = 400"))
    }

    @Test
    fun `horizontal padding keeps the compact range`() {
        val source = readPaddingDialogSource()
        assertTrue(source.contains("titleRes = R.string.padding_left") && source.contains("max = 100"))
        assertTrue(source.contains("titleRes = R.string.padding_right") && source.contains("max = 100"))
    }

    @Test
    fun `region controls are equal and touch sized`() {
        val source = readPaddingDialogSource()
        assertTrue(source.contains(".weight(1f)"))
        assertTrue(source.contains(".height(48.dp)"))
        assertTrue(source.contains(".size(48.dp)"))
        assertTrue(source.contains("stringResource(R.string.restore_default)"))
    }

    @Test
    fun `binary options use accessible switches`() {
        val source = readPaddingDialogSource()
        assertTrue(source.contains("stringResource(R.string.lock_left_right_padding)"))
        assertTrue(source.contains("stringResource(R.string.showLine)"))
        assertTrue(source.contains(".heightIn(min = 48.dp)"))
    }

    private fun readPaddingDialogSource(): String {
        val path = "src/main/java/io/legado/app/ui/book/read/config/PaddingConfigDialog.kt"
        val file = listOf(File(path), File("app/$path"))
            .firstOrNull { it.isFile }
            ?: error("Missing project file: $path")
        return file.readText()
    }
}
