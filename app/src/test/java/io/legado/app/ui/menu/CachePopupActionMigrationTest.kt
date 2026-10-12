package io.legado.app.ui.menu

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CachePopupActionMigrationTest {

    @Test
    fun `cache long press uses Compose DropdownMenu`() {
        val source = readProjectFile(CACHE_ACTIVITY)

        assertFalse(source.contains("import androidx.appcompat.widget.PopupMenu"))
        assertFalse(source.contains("PopupMenu.OnMenuItemClickListener"))
        assertFalse(source.contains("PopupMenu(this, it)"))
        assertFalse(source.contains("applyOpenTint"))
        assertFalse(source.contains("inflate(R.menu.book_cache_download)"))
        assertFalse(
            "Legacy cache menu resource should be removed",
            sequenceOf(File(LEGACY_MENU), File("app/$LEGACY_MENU")).any(File::isFile)
        )
        assertContains(source, "combinedClickable")
        assertContains(source, "DropdownMenu")
        assertOrdered(
            source,
            "R.string.menu_download_after",
            "startDownloadAfterCurrent()",
            "R.string.menu_download_all",
            "startDownloadAll()"
        )
    }

    @Test
    fun `cache download ranges and stop behavior remain unchanged`() {
        val source = readProjectFile(CACHE_ACTIVITY)
        val afterCurrent = section(
            source,
            "private fun startDownloadAfterCurrent()",
            "private fun startDownloadAll()"
        )
        val all = section(
            source,
            "private fun startDownloadAll()",
            "private fun initBookData()"
        )

        assertContains(afterCurrent, "sureCacheBook")
        assertContains(afterCurrent, "booksState.forEach")
        assertContains(afterCurrent, "book.durChapterIndex")
        assertContains(afterCurrent, "book.lastChapterIndex")
        assertContains(afterCurrent, "CacheBook.stop(this@CacheActivity)")
        assertContains(all, "sureCacheBook")
        assertContains(all, "booksState.forEach")
        assertTrue(Regex("""book,\s+0,\s+book\.lastChapterIndex""").containsMatchIn(all))
        assertContains(all, "CacheBook.stop(this@CacheActivity)")
    }

    @Test
    fun `cache toolbar download state updates the action view accessibility label`() {
        val source = readProjectFile(CACHE_ACTIVITY)
        val state = section(
            source,
            "private fun updateDownloadMenuState()",
            "override fun observeLiveBus"
        )
        val observer = section(
            source,
            "observeEvent<String>(EventBus.UP_DOWNLOAD_STATE)",
            "observeEvent<Pair<Book, BookChapter>>(EventBus.SAVE_CONTENT)"
        )

        assertContains(observer, "updateDownloadMenuState()")
        assertContains(state, "isCacheRunningState = CacheBook.isRun")
        assertContains(source, "if (isCacheRunningState) R.string.stop else R.string.download_start")
        assertContains(source, "R.drawable.ic_stop_black_24dp")
        assertContains(source, "R.drawable.ic_play_24dp")
        assertContains(source, "contentDescription = downloadTitle")
    }

    private fun section(source: String, start: String, end: String): String {
        val startIndex = source.indexOf(start)
        val endIndex = source.indexOf(end, startIndex + start.length)
        assertTrue("CacheActivity.kt should contain $start before $end", startIndex >= 0 && endIndex > startIndex)
        return source.substring(startIndex, endIndex)
    }

    private fun assertOrdered(source: String, vararg snippets: String) {
        var previous = -1
        snippets.forEach { snippet ->
            val current = source.indexOf(snippet, previous + 1)
            assertTrue("CacheActivity.kt should contain $snippet after the previous action", current > previous)
            previous = current
        }
    }

    private fun assertContains(source: String, expected: String) {
        assertTrue("CacheActivity.kt should contain $expected", source.contains(expected))
    }

    private fun readProjectFile(pathInApp: String): String =
        sequenceOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull(File::isFile)
            ?.readText()
            ?.replace("\r\n", "\n")
            .orEmpty()

    private companion object {
        const val CACHE_ACTIVITY = "src/main/java/io/legado/app/ui/book/cache/CacheActivity.kt"
        const val LEGACY_MENU = "src/main/res/menu/book_cache_download.xml"
    }
}
