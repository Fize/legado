package io.legado.app.ui.book.toc

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TocReverseNoDiffTest {

    @Test
    fun `full list replacement invalidates the Compose render cache`() {
        val fragment = source("app/src/main/java/io/legado/app/ui/book/toc/ChapterListFragment.kt")
        val block = fragment.substringAfter("private fun setChapterItemsNoDiff")
            .substringBefore("private suspend fun queryChapterList")
        assertTrue(block.contains("chapterItemsState = items"))
        assertTrue(block.contains("cacheRenderVersion++"))
        assertTrue(block.contains("onListChanged()"))
        assertTrue(block.contains("onItemsUpdated()"))
    }

    @Test
    fun `only explicit table reorder selects no diff path`() {
        val activity = source("app/src/main/java/io/legado/app/ui/book/toc/TocActivity.kt")
        val fragment = source("app/src/main/java/io/legado/app/ui/book/toc/ChapterListFragment.kt")
        val viewModel = source("app/src/main/java/io/legado/app/ui/book/toc/TocViewModel.kt")

        assertTrue(activity.contains("replaceAll = true"))
        assertTrue(fragment.contains("if (replaceAll)"))
        assertTrue(fragment.contains("setChapterItemsNoDiff(items)"))
        assertTrue(fragment.contains("setChapterItems(items)"))
        assertTrue(viewModel.contains("replaceAll: Boolean = false"))
    }

    private fun source(relativePath: String): String {
        return File(repositoryRoot(), relativePath).readText()
    }

    private fun repositoryRoot(): File {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        return generateSequence(File(userDir)) { it.parentFile }
            .first { File(it, "app/src/main").isDirectory }
    }
}
