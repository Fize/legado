package io.legado.app.ui.menu

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Issue1044ReplaceMenuContractTest {

    @Test
    fun `replace rule menu puts manual replacement after imports`() {
        val screen = readProjectFile("src/main/java/io/legado/app/ui/replace/ReplaceRuleScreen.kt")
        val order = listOf(
            "R.string.add_replace_rule",
            "R.string.import_local",
            "R.string.import_on_line",
            "R.string.import_by_qr_code",
            "R.string.manual_replace_rule"
        ).map { id ->
            val index = screen.indexOf(id)
            assertTrue("$id should exist in ReplaceRuleScreen.kt", index >= 0)
            index
        }
        assertTrue("replace rule actions should follow the requested order", order.zipWithNext().all { (a, b) -> a < b })

        assertTrue(screen.contains("Modifier.testTag(\"replaceRuleManualMode\")"))

        val zhStrings = readProjectFile("src/main/res/values-zh/strings.xml")
        assertTrue(zhStrings.contains("<string name=\"manual_replace_rule\">手动替换</string>"))
    }

    private fun readProjectFile(pathInApp: String): String =
        sequenceOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull(File::isFile)
            ?.readText()
            .orEmpty()
}
