package io.legado.app.ui.menu

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FileManagePopupActionMigrationTest {

    @Test
    fun `file deletion uses the Compose dropdown menu with error styling`() {
        val source = readProjectFile(FILE_MANAGE)

        assertFalse(source.contains("import android.widget.PopupMenu"))
        assertFalse(source.contains("PopupMenu(context, view)"))
        assertContains(source, "DropdownMenu(")
        assertContains(source, "stringResource(R.string.delete)")
        assertContains(source, "color = MaterialTheme.colorScheme.error")
        assertContains(source, "viewModel.delete(file)")
    }

    @Test
    fun `parent navigation is modeled by breadcrumbs instead of a deletable row`() {
        val source = readProjectFile(FILE_MANAGE)
        val viewModel = readProjectFile(FILE_MANAGE_VIEW_MODEL)

        assertContains(source, "state.breadcrumbs")
        assertContains(source, "if (!viewModel.goBack()) onExit()")
        assertFalse(source.contains("dirParent"))
        assertTrue(viewModel.contains("breadcrumbs.dropLast(1)"))
    }

    @Test
    fun `legacy file deletion menu resource is removed`() {
        assertFalse(projectFileExists("src/main/res/menu/file_long_click.xml"))
    }

    private fun assertContains(source: String, expected: String) {
        assertTrue("FileManagerScreen.kt should contain $expected", source.contains(expected))
    }

    private fun readProjectFile(pathInApp: String): String =
        projectFile(pathInApp)?.readText().orEmpty()

    private fun projectFileExists(pathInApp: String): Boolean =
        projectFile(pathInApp) != null

    private fun projectFile(pathInApp: String): File? =
        sequenceOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull(File::isFile)

    private companion object {
        const val FILE_MANAGE = "src/main/java/io/legado/app/ui/file/FileManagerScreen.kt"
        const val FILE_MANAGE_VIEW_MODEL = "src/main/java/io/legado/app/ui/file/FileManageViewModel.kt"
    }
}
