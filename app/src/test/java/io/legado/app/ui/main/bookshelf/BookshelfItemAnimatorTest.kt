package io.legado.app.ui.main.bookshelf

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BookshelfItemAnimatorTest {

    @Test
    fun `bookshelf variants use stable keys for frequent item updates`() {
        val bookshelfSources = listOf(
            "src/main/java/io/legado/app/ui/main/bookshelf/style1/books/BooksFragment.kt",
            "src/main/java/io/legado/app/ui/main/bookshelf/style2/BookshelfFragment2.kt"
        )

        bookshelfSources.forEach { path ->
            val source = readProjectFile(path)
            assertTrue(
                "$path should provide stable keys in LazyColumn/LazyVerticalGrid",
                source.contains("key =")
            )
        }
    }

    private fun readProjectFile(pathInApp: String): String {
        val file = sequenceOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull(File::isFile)
        requireNotNull(file) { "Project file not found: $pathInApp" }
        return file.readText()
    }
}
