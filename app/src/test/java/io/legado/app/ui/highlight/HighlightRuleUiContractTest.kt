package io.legado.app.ui.highlight

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class HighlightRuleUiContractTest {

    @Test
    fun `rule manager is registered and reachable from reading`() {
        val manifest = projectFile("src/main/AndroidManifest.xml").readText()
        val activity = projectFile(
            "src/main/java/io/legado/app/ui/book/read/ReadBookActivity.kt"
        ).readText()

        assertTrue(manifest.contains(".ui.highlight.HighlightRuleActivity"))
        assertTrue(activity.contains("menu.add(Menu.NONE, R.id.menu_highlight_rule, Menu.NONE, R.string.highlight_rule)"))
        assertTrue(activity.contains("R.id.menu_highlight_rule -> startActivity<HighlightRuleActivity>()"))
    }

    @Test
    fun `reading actions use the themed popup menu`() {
        val activity = projectFile(
            "src/main/java/io/legado/app/ui/book/read/ReadBookActivity.kt"
        ).readText()

        assertTrue(activity.contains("ACTION_HIGHLIGHT_CREATE_RULE"))
        assertTrue(activity.contains("popupActionMenu(this)"))
        assertFalse(activity.contains("private var highlightActionMenu:"))
        assertFalse(activity.contains("HighlightRulePopup(this"))
    }

    @Test
    fun `rule editor reuses the current style host`() {
        val editor = projectFile(
            "src/main/java/io/legado/app/ui/highlight/edit/HighlightRuleEditDialog.kt"
        ).readText()

        assertTrue(editor.contains("HighlightStyleDialog.StyleHost"))
        assertTrue(editor.contains("setColorPickerDialogListener(this)"))
        assertTrue(editor.contains("applyToBodyState"))
        assertTrue(editor.contains("applyToTitleState"))
    }

    @Test
    fun `rule list has empty state and row actions`() {
        val activity = projectFile(
            "src/main/java/io/legado/app/ui/highlight/HighlightRuleActivity.kt"
        ).readText()

        assertTrue(activity.contains("R.string.highlight_rule_empty"))
        assertTrue(activity.contains("LegadoSelectActionBar"))
        assertTrue(activity.contains("displayNameWithGroup()"))
        assertTrue(activity.contains("rule.isEnabled"))
        assertTrue(activity.contains("edit(rule)"))
        assertTrue(activity.contains("R.string.import_local"))
        assertTrue(activity.contains("R.string.export_all"))
    }

    private fun parseXml(pathInApp: String): Element =
        DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }.newDocumentBuilder().parse(projectFile(pathInApp)).documentElement

    private fun Element.elementsWithAndroidId(id: String): Int {
        val elements = getElementsByTagName("*")
        return (0 until elements.length)
            .map { elements.item(it) as Element }
            .count { it.getAttributeNS(ANDROID_NAMESPACE, "id") == id }
    }

    private fun projectFile(pathInApp: String): File =
        sequenceOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull(File::isFile)
            ?: error("Missing project file: $pathInApp")

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
    }
}
