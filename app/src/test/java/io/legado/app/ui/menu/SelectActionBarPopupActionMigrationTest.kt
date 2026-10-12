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
    fun `select action bar uses the themed popup and preserves listener behavior`() {
        val source = readProjectFile(SELECT_ACTION_BAR)

        listOf(
            "private var selMenu: Menu? = null",
            "private var menuItemClickListener: PopupMenu.OnMenuItemClickListener? = null",
            "MenuBuilder(context)",
            "SupportMenuInflater(context).inflate(resId, this)",
            "private fun showMoreMenu()",
            "setVertical(true)",
            "setDangerValues(",
            "setDisabledValues(",
            "if (item.isVisible)",
            "it.isEnabled && it.itemId.toString() == action",
            "menuItemClickListener?.onMenuItemClick(menuItem)",
            "R.id.menu_del"
        ).forEach { expected ->
            assertTrue("SelectActionBar should contain $expected", source.contains(expected))
        }

        assertFalse(source.contains("selMenu?.show()"))
        assertFalse(source.contains("PopupMenu(context"))
        assertFalse(source.contains("selMenu?.setOnMenuItemClickListener"))
        assertFalse(source.contains("import android.view.MenuInflater"))
    }

    @Test
    fun `themed popup preserves disabled menu items`() {
        val source = readProjectFile(POPUP_ACTION)

        listOf(
            "private var disabledValues: Set<String> = emptySet()",
            "fun setDisabledValues(values: Set<String>)",
            "item.enabled && item.value !in disabledValues",
            "context.secondaryDisabledTextColor"
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

        val expectedMenuIds = mapOf(
            "book_source_sel.xml" to listOf(
                "@+id/menu_enable_selection",
                "@+id/menu_disable_selection",
                "@+id/menu_add_group",
                "@+id/menu_remove_group",
                "@+id/menu_enable_explore",
                "@+id/menu_disable_explore",
                "@+id/menu_top_sel",
                "@+id/menu_bottom_sel",
                "@+id/menu_export_selection",
                "@+id/menu_share_source",
                "@+id/menu_check_source",
                "@+id/menu_check_selected_interval"
            ),
        )

        val expectedComposeMenuLabels = mapOf(
            "src/main/java/io/legado/app/ui/book/import/local/ImportBookActivity.kt" to listOf(
                "R.string.delete"
            ),
            "src/main/java/io/legado/app/ui/book/manage/BookshelfManageActivity.kt" to listOf(
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
            "src/main/java/io/legado/app/ui/book/toc/rule/TxtTocRuleActivity.kt" to listOf(
                "R.string.enable_selection",
                "R.string.disable_selection",
                "R.string.export_selection",
                "R.string.share_selected_source"
            ),
            "src/main/java/io/legado/app/ui/dict/rule/DictRuleActivity.kt" to listOf(
                "R.string.enable_selection",
                "R.string.disable_selection",
                "R.string.export_selection",
                "R.string.share_selected_source"
            ),
            "src/main/java/io/legado/app/ui/replace/ReplaceRuleActivity.kt" to listOf(
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
