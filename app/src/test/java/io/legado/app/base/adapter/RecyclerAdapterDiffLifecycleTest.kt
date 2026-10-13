package io.legado.app.base.adapter

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RecyclerAdapterDiffLifecycleTest {

    @Test
    fun `legacy diff adapters are removed after Compose migration`() {
        assertTrue(!projectPath("src/main/java/io/legado/app/base/adapter/RecyclerAdapter.kt").exists())
        assertTrue(!projectPath("src/main/java/io/legado/app/base/adapter/DiffRecyclerAdapter.kt").exists())
        assertTrue(!projectPath("src/main/java/io/legado/app/ui/book/manga/recyclerview/MangaAdapter.kt").exists())
        val manga = projectFile("src/main/java/io/legado/app/ui/book/manga/MangaReaderState.kt")
            .readText()
        assertTrue(manga.contains("data class MangaReaderUiState"))
        assertTrue(manga.contains("fun buildMangaSpreads"))
    }

    private fun projectFile(pathInApp: String): File {
        return listOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull { it.isFile }
            ?: error("Missing project file: $pathInApp")
    }

    private fun projectPath(pathInApp: String): File =
        if (File("app").isDirectory) File("app/$pathInApp") else File(pathInApp)
}
