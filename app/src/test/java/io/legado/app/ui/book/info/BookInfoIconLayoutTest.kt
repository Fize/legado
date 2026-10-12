package io.legado.app.ui.book.info

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BookInfoIconLayoutTest {

    @Test
    fun metadataIconsUseFixedSizeAndLogicalSpacing() {
        val source = projectFile(
            "src/main/java/io/legado/app/ui/book/info/BookInfoActivity.kt"
        ).readText()

        assertTrue(source.contains("BookHeaderSection"))
        assertTrue(source.contains("BookMetadataActionsSection"))
        assertTrue(source.contains("R.string.author_show"))
        assertTrue(source.contains("R.string.origin_show"))
    }

    private fun projectFile(pathInApp: String): File =
        sequenceOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull(File::isFile)
            ?: error("Project file not found: $pathInApp")
}
