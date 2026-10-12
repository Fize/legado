package io.legado.app.ui.book.changesource

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ChangeSourceToolbarLayoutTest {

    @Test
    fun `change source top bar reserves end padding for expanded search field`() {
        val source = projectFile(
            "src/main/java/io/legado/app/ui/book/changesource/ChangeSourceComposeComponents.kt"
        ).readText()
        assertTrue(
            "ChangeSourceTopBar must reserve end padding for expanded search actions",
            source.contains(".padding(end = 16.dp)")
        )
    }

    private fun projectFile(pathInApp: String): File {
        return sequenceOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull(File::isFile)
            ?: error("Project file not found: $pathInApp")
    }
}
