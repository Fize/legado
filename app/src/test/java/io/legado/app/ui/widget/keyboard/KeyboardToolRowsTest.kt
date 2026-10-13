package io.legado.app.ui.widget.keyboard

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class KeyboardToolRowsTest {

    @Test
    fun `row count changes update Compose state and relayout`() {
        val source = projectFile(
            "src/main/java/io/legado/app/ui/widget/keyboard/KeyboardToolPop.kt"
        ).readText()
        val observer = source
            .substringAfter("observeEvent<Int>(PreferKey.showBoardLine)")
            .substringBefore("contentView.measure(")
        assertTrue(source.contains("private var rowCount by mutableIntStateOf"))
        assertTrue(observer.contains("rowCount = rows.coerceIn(1, 5)"))
        assertTrue(observer.contains("contentView.requestLayout()"))
        assertTrue(source.contains("actions.chunked(rowCount)"))
        assertTrue(source.contains(".height((rowCount * 42 + 10).dp)"))
    }

    private fun projectFile(pathInApp: String): File =
        listOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull { it.isFile }
            ?: error("Missing project file: $pathInApp")
}
