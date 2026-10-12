package io.legado.app.ui.book.toc

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BookmarkItemLayoutTest {

    @Test
    fun `bookmark item keeps bindings and card presentation`() {
        val source = projectFile("src/main/java/io/legado/app/ui/book/toc/BookmarkFragment.kt").readText()

        assertTrue(source.contains("BookmarkScreen"))
        assertTrue(source.contains("item.chapterName"))
        assertTrue(source.contains("item.bookText"))
        assertTrue(source.contains("item.content"))
    }

    private fun projectFile(pathInApp: String): File =
        listOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull { it.isFile }
            ?: error("Missing project file: $pathInApp")
}
