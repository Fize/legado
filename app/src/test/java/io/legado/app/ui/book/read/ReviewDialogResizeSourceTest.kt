package io.legado.app.ui.book.read

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReviewDialogResizeSourceTest {

    @Test
    fun `review dialog exposes an isolated bounded resize handle`() {
        val source = projectFile(
            "src/main/java/io/legado/app/ui/book/read/ReviewDetailDialog.kt"
        ).readText().normalizeLines()

        assertTrue(source.contains("id = R.id.drag_handle"))
        assertTrue(source.contains("R.string.review_resize_handle"))
        assertTrue(source.contains("setupHeightDrag(this)"))
        assertTrue(source.contains("dragHandle.setOnClickListener"))
        assertTrue(source.contains("dragHandle.setOnTouchListener"))
        assertTrue(source.contains("MotionEvent.ACTION_MOVE"))
        assertTrue(source.contains("heightPx.coerceIn(minHeight, maxHeight)"))
        assertTrue(source.contains("setLayout(1f, clampedHeight)"))
        assertTrue(source.contains("if (!hasDraggedHeight) handle.performClick()"))
        assertTrue(source.contains("lastHeightRatio = clampedHeight.toFloat() / windowHeight"))
    }

    private fun String.normalizeLines(): String = replace("\r\n", "\n")

    private fun projectFile(pathInApp: String): File {
        return listOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull { it.isFile }
            ?: error("Missing project file: $pathInApp")
    }
}
