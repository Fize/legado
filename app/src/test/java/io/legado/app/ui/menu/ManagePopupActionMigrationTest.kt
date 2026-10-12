package io.legado.app.ui.menu

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ManagePopupActionMigrationTest {

    @Test
    fun `shared popup adds vertical danger styling without losing existing behavior`() {
        val popup = readProjectFile("src/main/java/io/legado/app/ui/widget/PopupAction.kt")
        val builder = readProjectFile("src/main/java/io/legado/app/ui/widget/PopupActionMenu.kt")
        val row = readProjectFile("src/main/res/layout/item_popup_action.xml")

        listOf(
            "applyMd3PopupStyle()",
            "resolveDropDownYOffset(",
            "LinearLayoutManager(context)",
            "private var actionItems: List<PopupActionItem> = emptyList()",
            "items.any { it.icon != null }",
            "items.any { it.checkable }",
            "textView.measuredWidth",
            "textView.minHeight = 48.dpToPx()",
            "textView.setPadding(5.dpToPx(), 5.dpToPx(), 5.dpToPx(), 5.dpToPx())",
            "val enabled = isItemEnabled(item)",
            "item.enabled && item.value !in disabledValues",
            "context.secondaryDisabledTextColor",
            "info.isCheckable = item.checkable",
            "AccessibilityNodeInfoCompat.CHECKED_STATE_TRUE",
            "AccessibilityNodeInfoCompat.CHECKED_STATE_FALSE",
            "imageView.visibility = View.INVISIBLE",
            "takeIf(::isItemEnabled)"
        ).forEach { expected -> assertContains("PopupAction.kt", popup, expected) }
        listOf(
            "@+id/iv_icon",
            "@+id/text_view",
            "@+id/iv_check_end",
            "android:duplicateParentState=\"true\""
        ).forEach { expected -> assertContains("item_popup_action.xml", row, expected) }
        assertFalse(row.contains("app:tint="))

        listOf(
            "setVertical(true)",
            "setDangerValues(dangerValues)",
            "dismiss()",
            "showAsDropDown(anchor, 0, 4.dpToPx())"
        ).forEach { expected -> assertContains("PopupActionMenu.kt", builder, expected) }
    }

    @Test
    fun `toolbar overflow uses exact items and keeps native fallbacks`() {
        val bridge = readProjectFile(
            "src/main/java/io/legado/app/utils/ToolbarOverflowMenuExtensions.kt"
        )

        listOf(
            "getTag(R.id.toolbar_overflow_menu_state) as? OverflowMenuState",
            "setTag(R.id.toolbar_overflow_menu_state, newState)",
            "addOnLayoutChangeListener",
            "onPrepareMenu(menu)",
            "onOpenCustomMenu(menu)",
            "actionItems.hasUnsupportedItems()",
            "item.subMenu != null || item.actionView != null",
            "showOverflowMenu()",
            "actionItems.mapIndexed { index, item ->",
            "value = index.toString()",
            "icon = if (state.showIcons)",
            "enabled = item.isEnabled",
            "checkable = item.isCheckable",
            "checked = item.isChecked",
            "action.toIntOrNull()",
            "actionItems::getOrNull",
            "performItemAction(menuItem, 0)",
            "params.isOverflowButton",
            "abc_action_menu_overflow_description"
        ).forEach { expected ->
            assertContains("ToolbarOverflowMenuExtensions.kt", bridge, expected)
        }
        assertFalse(bridge.contains("WeakHashMap"))
        assertFalse(bridge.contains("setOnHierarchyChangeListener"))
        assertFalse(bridge.contains("performIdentifierAction"))
    }

    @Test
    fun `base activity and fragments install the toolbar overflow bridge`() {
        val activity = readProjectFile("src/main/java/io/legado/app/base/BaseActivity.kt")
        val fragment = readProjectFile("src/main/java/io/legado/app/base/BaseFragment.kt")

        listOf(
            "if (view is Toolbar) view.installMd3OverflowMenu()",
            "?.installActivityOverflowMenu()",
            "showIcons = showOpenMenuIcon",
            "onPrepareOptionsMenu(toolbarMenu)",
            "onMenuOpened(Window.FEATURE_OPTIONS_PANEL, toolbarMenu)"
        ).forEach { expected -> assertContains("BaseActivity.kt", activity, expected) }
        assertFalse(activity.contains("if (view is Toolbar) view.installActivityOverflowMenu()"))
        assertContains("BaseFragment.kt", fragment, "it.installMd3OverflowMenu()")
    }

    @Test
    fun `dialog overflow menus use the shared vertical bridge or Compose TopAppBar`() {
        val sourceLogin = readProjectFile("src/main/java/io/legado/app/ui/login/SourceLoginDialog.kt")
        assertContains("SourceLoginDialog.kt", sourceLogin, "installMd3OverflowMenu(")
        assertContains("SourceLoginDialog.kt", sourceLogin, "showIcons = true")
        assertContains(
            "SourceLoginDialog.kt",
            sourceLogin,
            "onOpenCustomMenu = { it.applyOpenTint(requireContext()) }"
        )

        val appLog = readProjectFile("src/main/java/io/legado/app/ui/about/AppLogDialog.kt")
        assertContains("AppLogDialog.kt", appLog, "LegadoTopAppBar(")
        assertContains("AppLogDialog.kt", appLog, "iconRes = R.drawable.ic_clear_all")
        assertContains("AppLogDialog.kt", appLog, "iconRes = R.drawable.ic_export")

        val crashLog = readProjectFile("src/main/java/io/legado/app/ui/about/CrashLogsDialog.kt")
        assertContains("CrashLogsDialog.kt", crashLog, "LegadoTopAppBar(")
        assertContains("CrashLogsDialog.kt", crashLog, "iconRes = R.drawable.ic_clear_all")
    }

    @Test
    fun `dialog menu actions provide icons for the vertical menu`() {
        val menus = mapOf(
            "source_login.xml" to listOf(
                "menu_ok",
                "menu_show_login_header",
                "menu_del_login_header",
                "menu_clear_login_info",
                "menu_log"
            )
        )
        menus.forEach { (file, ids) ->
            val source = readProjectFile("src/main/res/menu/$file")
            ids.forEach { id ->
                val item = source.substringAfter("android:id=\"@+id/$id\"")
                    .substringBefore("/>")
                assertTrue(
                    "$file/$id should declare an icon",
                    item.contains("android:icon=\"@drawable/")
                )
            }
        }
    }

    @Test
    fun `management screens use Compose DropdownMenu`() {
        listOf(RULE_SUB_ACTIVITY, REPLACE_RULE_ACTIVITY, TXT_TOC_RULE_ACTIVITY).forEach { path ->
            val source = readProjectFile(path)
            assertFalse("$path should not import PopupMenu", source.contains("import android.widget.PopupMenu"))
            assertFalse("$path should not import AppCompat PopupMenu", source.contains("import androidx.appcompat.widget.PopupMenu"))
            assertContains(path, source, "DropdownMenu(")
            assertContains(path, source, "color = MaterialTheme.colorScheme.error")
        }
    }

    @Test
    fun `management menu actions keep their current callbacks and side effects`() {
        assertActions(RULE_SUB_ACTIVITY, "delSubscription(item)")
        assertActions(
            REPLACE_RULE_ACTIVITY,
            "toTop(item)",
            "toBottom(item)",
            "delete(item)",
            "selectedIdsState = selectedIdsState - item.id"
        )
        assertActions(
            TXT_TOC_RULE_ACTIVITY,
            "toTop(source)",
            "toBottom(source)",
            "del(source)",
            "selectedIdsState = selectedIdsState - source.id"
        )
    }

    @Test
    fun `management menu labels keep their previous order`() {
        listOf(REPLACE_RULE_ACTIVITY, TXT_TOC_RULE_ACTIVITY).forEach { path ->
            assertOrdered(
                path,
                "stringResource(R.string.to_top)",
                "stringResource(R.string.to_bottom)",
                "stringResource(R.string.delete)"
            )
        }
        assertOrdered(
            RULE_SUB_ACTIVITY,
            "stringResource(R.string.delete)"
        )
    }

    private fun assertActions(path: String, vararg expected: String) {
        val source = readProjectFile(path)
        expected.forEach { assertContains(path, source, it) }
    }

    private fun assertContains(path: String, source: String, expected: String) {
        assertTrue("$path should contain $expected", source.contains(expected))
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

    private fun readProjectFile(pathInApp: String): String =
        sequenceOf(File(pathInApp), File("app/$pathInApp"))
            .firstOrNull(File::isFile)
            ?.readText()
            .orEmpty()

    private companion object {
        const val RULE_SUB_ACTIVITY = "src/main/java/io/legado/app/ui/rulesub/RuleSubActivity.kt"
        const val REPLACE_RULE_ACTIVITY = "src/main/java/io/legado/app/ui/replace/ReplaceRuleActivity.kt"
        const val TXT_TOC_RULE_ACTIVITY = "src/main/java/io/legado/app/ui/book/toc/rule/TxtTocRuleActivity.kt"
    }
}
