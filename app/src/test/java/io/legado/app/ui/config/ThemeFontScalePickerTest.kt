package io.legado.app.ui.config

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ThemeFontScalePickerTest {

    @Test
    fun `compose font scale dialog keeps the effective configured or system value`() {
        val source = readProjectFile("src/main/java/io/legado/app/ui/config/ThemeSettingsScreen.kt")

        assertTrue(source.contains("currentScale = AppContextWrapper.getFontScale(context)"))
        assertTrue(source.contains("currentScale.coerceIn(0.8f, 1.6f)"))
        assertTrue(source.contains(".roundToInt()"))
        assertTrue(source.contains("putPrefInt(PreferKey.fontScale, 0)"))
        assertFalse(source.contains("NumberPickerDialog"))
    }

    private fun readProjectFile(pathInApp: String): String {
        return sequenceOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull(File::isFile)
            ?.readText()
            .orEmpty()
    }
}
