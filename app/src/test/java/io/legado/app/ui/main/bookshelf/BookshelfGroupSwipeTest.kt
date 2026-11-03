package io.legado.app.ui.main.bookshelf

import io.legado.app.data.entities.BookGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BookshelfGroupSwipeTest {

    private val groups = listOf(
        BookGroup(groupId = 1, groupName = "First"),
        BookGroup(groupId = 2, groupName = "Second"),
        BookGroup(groupId = 4, groupName = "Third"),
    )

    @Test
    fun `swiping left selects the next visible group`() {
        assertEquals(4L, adjacentBookshelfGroupId(groups, 2, 1))
    }

    @Test
    fun `swiping right selects the previous visible group`() {
        assertEquals(1L, adjacentBookshelfGroupId(groups, 2, -1))
    }

    @Test
    fun `root missing and edge groups leave the gesture to the parent pager`() {
        assertNull(adjacentBookshelfGroupId(groups, BookGroup.IdRoot, 1))
        assertNull(adjacentBookshelfGroupId(groups, 1, -1))
        assertNull(adjacentBookshelfGroupId(groups, 4, 1))
    }

    @Test
    fun `group swipe uses compose drag gestures and commits after threshold`() {
        val source = projectFile(
            "src/main/java/io/legado/app/ui/main/bookshelf/BookshelfRootScreen.kt",
        ).readText().replace("\r\n", "\n")
        assertTrue(source.contains("detectHorizontalDragGestures"))
        assertTrue(source.contains("totalHorizontalDrag > 96f"))
        assertTrue(source.contains("bookshelfViewModel.switchGroup(-1)"))
        assertTrue(source.contains("bookshelfViewModel.switchGroup(1)"))
        assertTrue(source.contains("onDragCancel = { totalHorizontalDrag = 0f }"))
    }

    @Test
    fun `folder group exposes toolbar back navigation`() {
        val root = projectFile(
            "src/main/java/io/legado/app/ui/main/bookshelf/BookshelfRootScreen.kt",
        ).readText().replace("\r\n", "\n")

        assertTrue(root.contains("BookshelfTopBar("))
        assertTrue(root.contains("onBackClick = if (uiState.groupId == BookGroup.IdRoot) null else"))
        assertTrue(root.contains("bookshelfViewModel.goBack()"))
    }

    private fun projectFile(pathInApp: String): File {
        return sequenceOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull(File::isFile)
            ?: error("Project file not found: $pathInApp")
    }
}
