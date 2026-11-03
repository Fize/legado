package io.legado.app.ui.menu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class SelectActionBarPopupActionMigrationTest {

    @Test
    fun `legacy select action bar is replaced by Compose action bars`() {
        assertFalse(resolveFile(SELECT_ACTION_BAR).exists())
        expectedComposeMenuLabels.keys.forEach { path ->
            assertTrue(readProjectFile(path).contains("LegadoSelectActionBar("))
        }
    }

    @Test
    fun `themed popup preserves disabled menu items`() {
        val source = readProjectFile(POPUP_ACTION)

        listOf(
            "private var disabledActions by mutableStateOf",
            "fun setDisabledValues(values: Set<String>)",
            "item.enabled && item.value !in disabledActions",
            "MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)"
        ).forEach { expected ->
            assertTrue("PopupAction should contain $expected", source.contains(expected))
        }
    }

    @Test
    fun `selection menus retain their item order`() {
        expectedMenuIds.forEach { (path, expected) ->
            assertEquals(path, expected, readMenuItemIds(path))
        }
        expectedComposeMenuLabels.forEach { (path, expected) ->
            val source = readProjectFile(path)
                .substringAfter("LegadoSelectActionBar(")
            var previous = -1
            expected.forEach { snippet ->
                val current = source.indexOf(snippet, previous + 1)
                assertTrue("$path should contain $snippet in order", current > previous)
                previous = current
            }
        }
    }

    @Test
    fun `unused popup reflection helper is removed`() {
        val source = readProjectFile(VIEW_EXTENSIONS)

        assertFalse(source.contains("MenuPopupHelper"))
        assertFalse(source.contains("fun PopupMenu.show"))
        assertFalse(source.contains("java.lang.reflect.Field"))
    }

    private fun readMenuItemIds(path: String): List<String> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }
        val document = factory
            .newDocumentBuilder()
            .parse(resolveFile("src/main/res/menu/$path"))
        val items = document.getElementsByTagName("item")
        return (0 until items.length).map { index ->
            (items.item(index) as Element).getAttributeNS(ANDROID_NS, "id")
        }
    }

    private fun readProjectFile(path: String): String =
        resolveFile(path).takeIf(File::isFile)?.readText().orEmpty()

    private fun resolveFile(path: String): File =
        sequenceOf(File(path), File("app/$path"))
            .firstOrNull(File::isFile)
            ?: File("app/$path")

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
        const val SELECT_ACTION_BAR = "src/main/java/io/legado/app/ui/widget/SelectActionBar.kt"
        const val POPUP_ACTION = "src/main/java/io/legado/app/ui/widget/PopupAction.kt"
        const val VIEW_EXTENSIONS = "src/main/java/io/legado/app/utils/ViewExtensions.kt"

        val expectedMenuIds = emptyMap<String, List<String>>()

        val expectedComposeMenuLabels = mapOf(
            "src/main/java/io/legado/app/ui/book/source/manage/BookSourceActivity.kt" to listOf(
                "R.string.enable_selection",
                "R.string.disable_selection",
                "R.string.add_group",
                "R.string.remove_group",
                "R.string.enable_explore",
                "R.string.disable_explore",
                "R.string.selection_to_top",
                "R.string.selection_to_bottom",
                "R.string.export_selection",
                "R.string.share_selected_source",
                "R.string.check_select_source",
                "R.string.check_selected_interval"
            ),
            "src/main/java/io/legado/app/ui/book/localimport/LocalBookImportScreen.kt" to listOf(
                "R.string.delete"
            ),
            "src/main/java/io/legado/app/ui/book/manage/BookshelfManageScreen.kt" to listOf(
                "R.string.delete",
                "R.string.allow_update",
                "R.string.disable_update",
                "R.string.add_to_group",
                "R.string.remove_group",
                "R.string.change_source_batch",
                "R.string.clear_cache",
                "R.string.persist_network_covers",
                "R.string.restore_network_covers",
                "R.string.restore_source_covers",
                "R.string.check_selected_interval",
                "R.string.update_book_info_toc"
            ),
            "src/main/java/io/legado/app/ui/book/toc/rule/TextTocRuleScreen.kt" to listOf(
                "R.string.enable_selection",
                "R.string.disable_selection",
                "R.string.export_selection",
                "R.string.share_selected_source"
            ),
            "src/main/java/io/legado/app/ui/dict/rule/DictionaryRuleScreen.kt" to listOf(
                "R.string.enable_selection",
                "R.string.disable_selection",
                "R.string.export_selection",
                "R.string.share_selected_source"
            ),
            "src/main/java/io/legado/app/ui/replace/ReplaceRuleScreen.kt" to listOf(
                "R.string.enable_selection",
                "R.string.disable_selection",
                "R.string.add_group",
                "R.string.remove_group",
                "R.string.selection_to_top",
                "R.string.selection_to_bottom",
                "R.string.export_selection",
                "R.string.share_selected_source"
            ),
        )
    }
}
