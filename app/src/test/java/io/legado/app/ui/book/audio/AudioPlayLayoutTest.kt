package io.legado.app.ui.book.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class AudioPlayLayoutTest {

    @Test
    fun playButtonsUseCompatTintForTheirWhiteIcons() {
        val activity = projectFile("src/main/java/io/legado/app/ui/book/audio/AudioPlayActivity.kt")
            .readText()

        assertTrue(activity.contains("FloatingActionButton("))
        assertTrue(activity.contains("containerColor = Color.White"))
        assertTrue(activity.contains("contentColor = Color.Black"))
        assertTrue(activity.contains("tint = Color.Black"))

        assertEquals("#FFFFFFFF", vectorFillColor("ic_play_24dp.xml"))
        assertEquals("#FFFFFFFF", vectorFillColor("ic_pause_24dp.xml"))
    }

    @Test
    fun chapterInfoIsBoundedAndPresentInBothOrientations() {
        val activity = projectFile("src/main/java/io/legado/app/ui/book/audio/AudioPlayActivity.kt")
            .readText()
        val subtitleSection = activity.substringAfter("// Subtitle & Chapter Index area")
            .substringBefore("// Progress bar row")
        val portraitBranch = subtitleSection.substringAfter("if (!isLandscape) {")
            .substringBefore("} else {")
        val landscapeBranch = subtitleSection.substringAfter("} else {")

        listOf(portraitBranch, landscapeBranch).forEach { branch ->
            assertTrue(branch.contains("text = subTitleState"))
            assertTrue(branch.contains("fontWeight = FontWeight.Bold"))
            assertTrue(branch.contains("maxLines = 2"))
            assertTrue(branch.contains("overflow = TextOverflow.Ellipsis"))
            assertTrue(branch.contains("if (chapterIndexTextState.isNotEmpty())"))
            assertTrue(branch.contains("text = chapterIndexTextState"))
            assertTrue(branch.contains("color = colorResource(R.color.md_dark_secondary)"))
            assertTrue(branch.contains("fontSize = 12.sp"))
        }
    }

    private fun vectorFillColor(fileName: String): String {
        val document = parseProjectXml("src/main/res/drawable/$fileName")
        val path = document.getElementsByTagName("path").item(0) as Element
        return path.getAttributeNS(androidNamespace, "fillColor")
    }

    private fun parseProjectXml(pathInApp: String): Document {
        val file = projectFile(pathInApp)
        return DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }.newDocumentBuilder().parse(file)
    }

    private fun projectFile(pathInApp: String): File {
        return listOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull { it.isFile }
            ?: error("Missing project file: $pathInApp")
    }

    private companion object {
        const val androidNamespace = "http://schemas.android.com/apk/res/android"
    }
}
