package io.legado.app.ui.book.info

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BookInfoScreenBoundaryTest {
    private fun source(name: String) = sequenceOf(File("src/main/java/io/legado/app/ui/book/info/$name.kt"),
        File("app/src/main/java/io/legado/app/ui/book/info/$name.kt")).first(File::isFile).readText()

    @Test fun `screen has no state owner or platform operations`() {
        val screen = source("BookInfoScreen")
        for (forbidden in listOf("viewModel", "Activity", "LocalContext", "SourceCallBack", "startActivity",
            "AppConfig", "LocalConfig", "showDialogFragment", "appDb", "bookReaderIntent")) {
            assertFalse("Screen contains $forbidden", screen.contains(forbidden))
        }
        assertTrue(screen.contains("state: BookInfoUiState"))
        assertTrue(screen.contains("onAction: (BookInfoUiAction) -> Unit"))
        assertTrue(screen.contains("LocalWindowInfo.current.containerSize"))
    }

    @Test fun `intro reports carry the content identity`() {
        val screen = source("BookInfoScreen")
        for (action in listOf("IntroButton", "IntroImage", "IntroLink")) {
            assertTrue(screen.contains("BookInfoUiAction.$action(state.intro.contentKey"))
        }
    }
}
