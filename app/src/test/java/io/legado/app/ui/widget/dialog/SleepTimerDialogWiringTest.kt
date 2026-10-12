package io.legado.app.ui.widget.dialog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class SleepTimerDialogWiringTest {

    @Test
    fun presetsAndCustomInputKeepMainlineTimerContracts() {
        val source = projectFile(
            "src/main/java/io/legado/app/ui/widget/dialog/SleepTimerDialog.kt"
        ).readText()
        val preferKey = projectFile(
            "src/main/java/io/legado/app/constant/PreferKey.kt"
        ).readText()

        assertTrue(source.contains("TIME_PRESETS = intArrayOf(15, 30, 45, 60)"))
        assertTrue(source.contains("CHAPTER_PRESETS = intArrayOf(1, 2, 3, 5)"))
        assertTrue(source.contains("value !in 1..max"))
        assertTrue(source.contains("MAX_CHAPTER_STOP_COUNT"))
        assertTrue(source.contains("private const val MAX_MINUTES = 180"))
        assertTrue(source.contains("if (chapter > 0) AppConfig.sleepTimerPreferChapter = true"))
        assertTrue(source.contains("if (minute > 0) AppConfig.sleepTimerPreferChapter = false"))
        assertTrue(source.contains("PreferKey.lastSleepTimer"))
        assertTrue(source.contains("PreferKey.lastSleepChapter"))
        assertTrue(source.contains("customInputVisible && customChapterMode == chapterMode"))
        assertTrue(source.contains("arguments?.getInt(ARG_MINUTE)"))
        assertTrue(source.contains("arguments?.getInt(ARG_CHAPTER)"))
        assertTrue(source.contains("arguments?.getBoolean(ARG_EPISODES)"))
        assertFalse(source.contains("AudioPlayService"))
        assertFalse(source.contains("BaseReadAloudService"))
        assertTrue(preferKey.contains("const val lastSleepTimer = \"lastSleepTimer\""))
        assertTrue(preferKey.contains("const val lastSleepChapter = \"lastSleepChapter\""))
    }

    @Test
    fun layoutProvidesEqualAccessibleOptionsAndOneCustomInput() {
        val source = projectFile(
            "src/main/java/io/legado/app/ui/widget/dialog/SleepTimerDialog.kt"
        ).readText()

        assertTrue(source.contains("SleepTimerOptionButton("))
        assertTrue(source.contains("modifier = Modifier.weight(1f)"))
        assertTrue(source.contains(".heightIn(min = 48.dp)"))
        assertTrue(source.contains("stringResource(R.string.sleep_timer_custom)"))
        assertTrue(source.contains("if (customInputVisible)"))
        assertTrue(source.contains("KeyboardType.Number"))
        assertTrue(source.contains("next.length <= 3"))
        assertTrue(source.contains("contentDescription = stringResource(R.string.sleep_timer_off)"))
    }

    @Test
    fun chineseResourcesKeepChapterAndEpisodeUnitsSeparate() {
        assertEquals("%d章", chineseString("sleep_timer_chapter_short"))
        assertEquals("%d集", chineseString("sleep_timer_episode_short"))
    }

    private fun chineseString(name: String): String {
        val document = parseProjectXml("src/main/res/values-zh/strings.xml")
        return document.getElementsByTagName("string").let { nodes ->
            (0 until nodes.length)
                .map { nodes.item(it) as Element }
                .single { it.getAttribute("name") == name }
                .textContent
        }
    }

    private fun parseProjectXml(pathInApp: String): Document {
        return DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }.newDocumentBuilder().parse(projectFile(pathInApp))
    }

    private fun projectFile(pathInApp: String): File {
        return listOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull { it.isFile }
            ?: error("Missing project file: $pathInApp")
    }
}
