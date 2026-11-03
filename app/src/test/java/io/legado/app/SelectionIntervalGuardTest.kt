package io.legado.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SelectionIntervalGuardTest {

    @Test
    fun `selection interval returns before reading empty bounds`() {
        sources.forEach { relativePath ->
            val source = File(sourceRoot, relativePath).readText()
            val methodName = if (relativePath.contains("BookshelfManage")) {
                "fun extendBookSelectionToInterval("
            } else {
                "fun checkSelectedInterval("
            }
            val methodStart = source.indexOf(methodName)
            val guard = source.indexOf("if (selectedPosition.isEmpty()) return", methodStart)
            val min = source.indexOf("Collections.min(selectedPosition)", methodStart)

            assertTrue("missing checkSelectedInterval in $relativePath", methodStart >= 0)
            assertTrue("missing empty selection guard in $relativePath", guard > methodStart)
            assertTrue("empty selection guard must precede Collections.min in $relativePath", guard < min)
        }
    }

    @Test
    fun `book selection uses stable book urls`() {
        val source = File(
            sourceRoot,
            "io/legado/app/ui/book/manage/BookshelfManageScreen.kt"
        ).readText()
        assertTrue(source.contains("Set<String>"))
        assertTrue(source.contains("book.bookUrl in selectedBookUrls"))
    }

    private val sourceRoot: File by lazy {
        sequenceOf(File("src/main/java"), File("app/src/main/java"))
            .first { it.isDirectory }
    }

    private companion object {
        val sources = listOf(
            "io/legado/app/ui/book/manage/BookshelfManageScreen.kt",
            "io/legado/app/ui/book/source/manage/BookSourceActivity.kt"
        )
    }
}
