package io.legado.app.ui.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class DialogMultipleEditTextLayoutTest {

    @Test
    fun keyboardAssistsEditDialogUsesComposeOutlinedTextFields() {
        val source = readProjectFile(
            "src/main/java/io/legado/app/ui/widget/keyboard/KeyboardAssistsConfig.kt"
        )
        assertTrue(source.contains("OutlinedTextField("))
        assertTrue(source.contains("label = { Text(\"key\") }"))
        assertTrue(source.contains("label = { Text(\"value\") }"))
        assertFalse(source.contains("singleLine = true"))
    }

    @Test
    fun singleFieldDialogUsesFloatingLabelForRuntimeHints() {
        val document = parseProjectXml("src/main/res/layout/dialog_edit_text.xml")
        val field = document.findElementById("@+id/edit_view")
        val inputLayout = field.parentNode as Element

        assertEquals("io.legado.app.ui.widget.text.TextInputLayout", inputLayout.tagName)
        assertEquals("48dp", field.androidAttribute("minHeight"))
        assertEquals("12dp", field.androidAttribute("paddingStart"))
        assertEquals("6dp", field.androidAttribute("paddingTop"))
        assertEquals("12dp", field.androidAttribute("paddingEnd"))
        assertEquals("6dp", field.androidAttribute("paddingBottom"))
        assertFalse(field.hasAttributeNS(androidNamespace, "singleLine"))

        val source = readProjectFile(
            "src/main/java/io/legado/app/ui/widget/text/AutoCompleteTextView.kt"
        )
        assertTrue(source.contains("override fun onAttachedToWindow()"))
        assertTrue(source.contains("ancestor.hint = currentHint"))
        assertTrue(source.contains("hint = null"))
    }

    private fun parseProjectXml(pathInApp: String): Document {
        return DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }.newDocumentBuilder().parse(projectFile(pathInApp))
    }

    private fun readProjectFile(pathInApp: String): String = projectFile(pathInApp).readText()

    private fun projectFile(pathInApp: String): File =
        listOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull { it.isFile }
            ?: error("Missing project file: $pathInApp")

    private fun Document.findElementById(id: String): Element {
        return getElementsByTagName("*").let { nodes ->
            (0 until nodes.length)
                .map { nodes.item(it) as Element }
                .single { it.androidAttribute("id") == id }
        }
    }

    private fun Element.androidAttribute(name: String): String =
        getAttributeNS(androidNamespace, name)

    private companion object {
        const val androidNamespace = "http://schemas.android.com/apk/res/android"
    }
}
