package io.legado.app.ui.association

import io.legado.app.data.repository.*

import io.legado.app.ui.widget.dialog.resolveCodeDialogOriginal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ImportBookSourceStateTest {

    @Test
    fun `replace manager refresh keeps the editable source draft`() {
        assertEquals(
            "edited original",
            resolveCodeDialogOriginal(true, "edited original", "replacement preview"),
        )
        assertEquals(
            "visible edit",
            resolveCodeDialogOriginal(false, "older original", "visible edit"),
        )
    }

    @Test
    fun `classifies new updated and existing sources`() {
        assertEquals(
            ImportBookSourceStatus(isNew = true, isUpdate = false),
            resolveImportBookSourceStatus(importedLastUpdateTime = 100, localLastUpdateTime = null),
        )
        assertEquals(
            ImportBookSourceStatus(isNew = false, isUpdate = true),
            resolveImportBookSourceStatus(importedLastUpdateTime = 101, localLastUpdateTime = 100),
        )
        assertEquals(
            ImportBookSourceStatus(isNew = false, isUpdate = false),
            resolveImportBookSourceStatus(importedLastUpdateTime = 100, localLastUpdateTime = 100),
        )
        assertEquals(
            ImportBookSourceStatus(isNew = false, isUpdate = false),
            resolveImportBookSourceStatus(importedLastUpdateTime = 99, localLastUpdateTime = 100),
        )
    }

    @Test
    fun `default selection follows source status`() {
        val update = ImportBookSourceStatus(isNew = false, isUpdate = true)
        val existing = ImportBookSourceStatus(isNew = false, isUpdate = false)

        assertTrue(resolveImportSourceSelection(update, manualSelection = null))
        assertFalse(resolveImportSourceSelection(existing, manualSelection = null))
    }

    @Test
    fun `manual selection override survives repeated status changes`() {
        val newSource = ImportBookSourceStatus(isNew = true, isUpdate = false)
        val existing = ImportBookSourceStatus(isNew = false, isUpdate = false)

        assertFalse(resolveImportSourceSelection(newSource, manualSelection = false))
        assertFalse(resolveImportSourceSelection(existing, manualSelection = false))
        assertFalse(resolveImportSourceSelection(newSource, manualSelection = false))
        assertTrue(resolveImportSourceSelection(existing, manualSelection = true))
    }

    @Test
    fun `direct JS source import preserves coroutine cancellation`() {
        val source = readProjectFile(
            "src/main/java/io/legado/app/data/repository/BookSourceImportRepository.kt"
        )
        assertTrue(source.contains("private suspend fun extractJsSource(text: String)"))
        assertTrue(source.contains("JsSourceConfig.extract(text, coroutineContext)"))
        assertTrue(source.contains("runCatchingCancellable"))
    }

    @Test
    fun `import comment rows reset collapsed state when rebound`() {
        val components = readProjectFile(
            "src/main/java/io/legado/app/ui/association/AssociationComposeComponents.kt"
        )
        assertTrue(components.contains("var commentExpanded by remember(name, comment) { mutableStateOf(false) }"))
        assertTrue(components.contains("maxLines = if (commentExpanded) 39 else 3"))
    }

    @Test
    fun `association import status labels use localized resources`() {
        val importDialogs = listOf(
            "BookSourceImportScreen.kt",
            "HttpTtsImportScreen.kt",
        )

        importDialogs.forEach { fileName ->
            val source = readProjectFile(
                "src/main/java/io/legado/app/ui/association/$fileName"
            )
            assertTrue(source.contains("R.string.import_status_new"))
            assertTrue(source.contains("R.string.import_status_exist"))
            assertFalse(source.contains("\"新增\""))
            assertFalse(source.contains("\"更新\""))
            assertFalse(source.contains("\"已有\""))
        }

        importDialogs.forEach { fileName ->
                val source = readProjectFile(
                    "src/main/java/io/legado/app/ui/association/$fileName"
                )
                assertTrue(source.contains("R.string.import_status_update"))
            }
    }

    private fun readProjectFile(pathInApp: String): String {
        val file = sequenceOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull(File::isFile)
        requireNotNull(file) { "Project file not found: $pathInApp" }
        return file.readText()
    }
    @Test
    fun `reimport explicitly selects same timestamp source while allowing cancellation`() {
        val same = ImportBookSourceStatus(isNew = false, isUpdate = false)
        assertTrue(resolveImportSourceSelection(same, manualSelection = null, selectExisting = true))
        assertFalse(resolveImportSourceSelection(same, manualSelection = false, selectExisting = true))
    }

}
