package io.legado.app.ui.main.bookshelf

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BookshelfRootContractTest {

    @Test
    fun `root screen collects immutable state and renders existing compose items`() {
        val screen = projectFile("src/main/java/io/legado/app/ui/main/bookshelf/BookshelfRootScreen.kt")
        val viewModel = projectFile("src/main/java/io/legado/app/ui/main/bookshelf/BookshelfRootViewModel.kt")

        assertTrue(screen.contains("collectAsStateWithLifecycle()"))
        assertTrue(screen.contains("BookshelfBookGridItem("))
        assertTrue(screen.contains("BookshelfGroupGridItem("))
        assertTrue(viewModel.contains("StateFlow<BookshelfRootUiState>"))
        assertTrue(viewModel.contains("repository.observe(selectedGroup)"))
    }

    private fun projectFile(pathInApp: String): String =
        listOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("Missing project file: $pathInApp")
}
