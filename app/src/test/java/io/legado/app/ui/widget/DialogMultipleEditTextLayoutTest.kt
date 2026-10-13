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
        val bindingSource = readProjectFile(
            "src/main/java/io/legado/app/databinding/ProgrammaticBindings.kt"
        )
        assertTrue(bindingSource.contains("class DialogEditTextBinding"))
        assertTrue(bindingSource.contains("val editView = AutoCompleteTextView(context)"))
        assertTrue(bindingSource.contains("val textInputLayout = TextInputLayout(context)"))
        assertTrue(bindingSource.contains("minHeight = 48.dpToPx()"))
        assertTrue(bindingSource.contains("setPaddingRelative(12.dpToPx(), 6.dpToPx(), 12.dpToPx(), 6.dpToPx())"))
        assertFalse(bindingSource.contains("DialogEditTextBinding(context: Context) : ViewBinding {\n    val editView = AutoCompleteTextView(context).apply {\n        isSingleLine = true"))

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
