package io.legado.app.ui.widget.dialog

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VariableDialogLayoutTest {

    @Test
    fun variableInputUsesFloatingLabelAndCommentAreaKeepsCardInsets() {
        val source = projectFile("src/main/java/io/legado/app/ui/widget/dialog/VariableDialog.kt")
            .readText()

        assertTrue(source.contains("OutlinedTextField("))
        assertTrue(source.contains("label = { Text(\"variable\") }"))
        assertTrue(source.contains(".padding(horizontal = 16.dp)"))
        assertTrue(source.contains(".verticalScroll(rememberScrollState())"))
        assertTrue(source.contains(".padding(top = 6.dp, bottom = 12.dp)"))
        assertTrue(source.contains(".padding(bottom = 3.dp)"))
    }

    private fun projectFile(pathInApp: String): File {
        return listOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull { it.isFile }
            ?: error("Missing project file: $pathInApp")
    }
}
