package io.legado.app.ui.menu

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SourcePopupActionMigrationTest {

    @Test
    fun `source related row menus use the shared vertical builder or compose dropdown`() {
        sourceMenuFiles.forEach { path ->
            val source = readProjectFile(path)
            assertFalse("$path should not import platform PopupMenu", source.contains("import android.widget.PopupMenu"))
            assertFalse("$path should not import AppCompat PopupMenu", source.contains("import androidx.appcompat.widget.PopupMenu"))
            assertContains(path, source, "DropdownMenu(")
        }
        assertContains(BOOK_SOURCE, readProjectFile(BOOK_SOURCE), "color = MaterialTheme.colorScheme.error")
        assertContains(CHANGE_SOURCE_COMPOSE, readProjectFile(CHANGE_SOURCE_COMPOSE), "color = MaterialTheme.colorScheme.error")
        assertContains(EXPLORE, readProjectFile(EXPLORE), "color = MaterialTheme.colorScheme.error")
        legacyMenuFiles.forEach { path ->
            assertFalse(
                "$path should be removed",
                sequenceOf(File(path), File("app/$path")).any(File::isFile)
            )
        }
    }

    @Test
    fun `dynamic source menu entries keep their visibility and labels`() {
        val bookSource = readProjectFile(BOOK_SOURCE)
        listOf(
            "val defaultOrder = sort == BookSourceSort.Default",
            "if (source.hasLoginUrl)",
            "if (source.enabledExplore) R.string.disable_explore else R.string.enable_explore",
            "if (source.hasExploreUrl)"
        ).forEach { assertContains(BOOK_SOURCE, bookSource, it) }

        assertContains(
            EXPLORE,
            readProjectFile(EXPLORE),
            "if (menuSource.hasLoginUrl)"
        )
    }

    @Test
    fun `source menu labels keep their previous order`() {
        assertOrdered(
            BOOK_SOURCE,
            "R.string.to_top",
            "R.string.to_bottom",
            "R.string.login",
            "R.string.search",
            "R.string.delete",
            "R.string.disable_explore else R.string.enable_explore"
        )
        assertOrdered(
            CHANGE_SOURCE_COMPOSE,
            "R.string.to_top",
            "R.string.to_bottom",
            "R.string.edit_source",
            "R.string.disable_source",
            "R.string.delete_source"
        )
        assertOrdered(
            EXPLORE,
            "R.string.edit",
            "R.string.to_top",
            "R.string.login",
            "R.string.search",
            "R.string.refresh",
            "R.string.delete"
        )
    }

    @Test
    fun `source menu callbacks and delete side effects are preserved`() {
        assertActions(
            BOOK_SOURCE,
            "toTop(source)",
            "toBottom(source)",
            "SourceLoginRoute(",
            "searchBook(source)",
            "del(source)",
            "selectedUrlsState = selectedUrlsState - source.bookSourceUrl",
            "enableExplore(!source.enabledExplore, source)"
        )
        assertActions(
            CHANGE_SOURCE_COMPOSE,
            "onTopSource()",
            "onBottomSource()",
            "onEditSource()",
            "onDisableSource()",
            "showDeleteConfirm = true",
            "stringResource(R.string.draw)",
            "onDeleteSource()"
        )
        assertActions(
            CHANGE_CHAPTER,
            "topSource(item)",
            "bottomSource(item)",
            "editSource(item)",
            "disableSource(item)",
            "deleteSource(item)",
            "scoreRevision++"
        )
        assertActions(
            EXPLORE,
            "onEditSource(menuSource.bookSourceUrl)",
            "exploreViewModel.topSource(menuSource)",
            "onLoginSource(menuSource.bookSourceUrl)",
            "onSearchSource(menuSource)",
            "exploreViewModel.refreshSource(menuSource)",
            "exploreViewModel.deleteSource(source)"
        )
    }

    private fun assertActions(path: String, vararg expected: String) {
        val source = readProjectFile(path)
        expected.forEach { assertContains(path, source, it) }
    }

    private fun assertOrdered(path: String, vararg expected: String) {
        val source = readProjectFile(path)
        var previous = -1
        expected.forEach { snippet ->
            val current = source.indexOf(snippet, previous + 1)
            assertTrue("$path should contain $snippet after the previous item", current > previous)
            previous = current
        }
    }

    private fun assertContains(path: String, source: String, expected: String) {
        assertTrue("$path should contain $expected", source.contains(expected))
    }

    private fun readProjectFile(pathInApp: String): String =
        sequenceOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull(File::isFile)
            ?.readText()
            .orEmpty()

    private companion object {
        const val BOOK_SOURCE = "src/main/java/io/legado/app/ui/book/source/manage/BookSourceActivity.kt"
        const val CHANGE_SOURCE_COMPOSE = "src/main/java/io/legado/app/ui/book/changesource/ChangeSourceComposeComponents.kt"
        const val CHANGE_CHAPTER = "src/main/java/io/legado/app/ui/book/changesource/ChangeChapterSourceDialog.kt"
        const val EXPLORE = "src/main/java/io/legado/app/ui/main/explore/ExploreRootScreen.kt"
        val sourceMenuFiles = listOf(CHANGE_SOURCE_COMPOSE)
        val legacyMenuFiles = listOf(
            "src/main/res/menu/book_source_item.xml",
            "src/main/res/menu/change_source_item.xml",
            "src/main/res/menu/explore_item.xml"
        )
    }
}
