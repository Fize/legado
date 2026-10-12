package io.legado.app.ui.code

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CodeEditorSelectionContractTest {

    @Test
    fun `overflow select all uses the editor document instead of ime extracted text`() {
        val activity = projectFile(
            "app/src/main/java/io/legado/app/ui/code/CodeEditActivity.kt"
        ).readText()

        assertTrue(activity.contains("stringResource(R.string.select_all)"))
        assertTrue(activity.contains("if (!useSafeEditor) editor.selectAll()"))
        assertTrue(activity.contains("props.maxIPCTextLength = 64 * 1024"))
    }

    private fun projectFile(path: String): File {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        return generateSequence(File(userDir)) { it.parentFile }
            .map { File(it, path) }
            .first { it.exists() }
    }
}
