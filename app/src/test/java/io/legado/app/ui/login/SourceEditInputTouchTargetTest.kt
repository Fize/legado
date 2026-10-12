package io.legado.app.ui.login

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SourceEditInputTouchTargetTest {

    @Test
    fun `shared source editor input has an accessible touch target`() {
        val dialog = readProjectFile(
            "src/main/java/io/legado/app/ui/login/SourceLoginDialog.kt"
        )
        val v2 = readProjectFile(
            "src/main/java/io/legado/app/ui/login/SourceLoginV2Delegate.kt"
        )

        assertTrue(dialog.contains("heightIn(min = 48.dp)"))
        assertTrue(v2.contains("heightIn(min = 48.dp)"))
    }

    @Test
    fun `login form uses Compose OutlinedTextField input`() {
        val source = readProjectFile(
            "src/main/java/io/legado/app/ui/login/SourceLoginDialog.kt"
        )

        assertTrue(source.contains("OutlinedTextField("))
    }

    @Test
    fun `legacy and v2 password fields expose visibility toggles`() {
        val legacy = readProjectFile(
            "src/main/java/io/legado/app/ui/login/SourceLoginDialog.kt"
        )
        val v2 = readProjectFile(
            "src/main/java/io/legado/app/ui/login/SourceLoginV2Delegate.kt"
        )

        assertTrue(legacy.contains("PasswordVisualTransformation()"))
        assertTrue(legacy.contains("Icons.Default.VisibilityOff"))
        assertTrue(v2.contains("PasswordVisualTransformation()"))
        assertTrue(v2.contains("Icons.Default.VisibilityOff"))
    }

    private fun readProjectFile(pathInApp: String): String {
        val file = sequenceOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull(File::isFile)
        requireNotNull(file) { "Project file not found: $pathInApp" }
        return file.readText()
    }
}
