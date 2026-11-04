package io.legado.app.ui.main.my

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.ui.compose.component.LegadoDropdownMenuItem
import io.legado.app.ui.compose.component.LegadoMultiChoiceDialog
import io.legado.app.ui.compose.component.LegadoOverflowMenuButton
import io.legado.app.ui.compose.component.LegadoTopAppBar
import io.legado.app.ui.compose.prefs.ListPreferenceItem
import io.legado.app.ui.compose.prefs.PreferenceCategoryHeader
import io.legado.app.ui.compose.prefs.PreferenceItem
import io.legado.app.ui.compose.prefs.rememberPrefStringSet

enum class MyAction(val key: String) {
    About("about"), AllBookmarks("bookmark"), BackupSettings("web_dav_setting"),
    BookSourceManage("bookSourceManage"), DictionaryRuleManage("dictRuleManage"), Exit("exit"),
    FileManage("fileManage"), More("myMore"), OtherSettings("setting"),
    ReadRecord("readRecord"), ReplaceRuleManage("replaceManage"),
    RuleSubscription("ruleSubscription"), TextTocRuleManage("txtTocRuleManage"),
    ThemeSettings("theme_setting"),
}

private data class MyNavEntry(
    val action: MyAction,
    @StringRes val titleRes: Int,
    @StringRes val summaryRes: Int? = null,
    @DrawableRes val iconRes: Int,
)

private val topEntries = listOf(
    MyNavEntry(MyAction.BookSourceManage, R.string.book_source_manage, R.string.book_source_manage_desc, R.drawable.ic_cfg_source),
    MyNavEntry(MyAction.TextTocRuleManage, R.string.txt_toc_rule, R.string.config_txt_toc_rule, R.drawable.ic_cfg_source),
    MyNavEntry(MyAction.ReplaceRuleManage, R.string.replace_purify, R.string.replace_purify_desc, R.drawable.ic_cfg_replace),
    MyNavEntry(MyAction.DictionaryRuleManage, R.string.dict_rule, R.string.config_dict_rule, R.drawable.ic_translate),
    MyNavEntry(MyAction.RuleSubscription, R.string.rule_subscription, iconRes = R.drawable.ic_bottom_rss_feed_e),
)

private val settingEntries = listOf(
    MyNavEntry(MyAction.BackupSettings, R.string.backup_restore, R.string.web_dav_set_import_old, R.drawable.ic_cfg_backup),
    MyNavEntry(MyAction.ThemeSettings, R.string.theme_setting, R.string.theme_setting_s, R.drawable.ic_cfg_theme),
    MyNavEntry(MyAction.OtherSettings, R.string.other_setting, R.string.other_setting_s, R.drawable.ic_cfg_other),
)

private val aboutEntries = listOf(
    MyNavEntry(MyAction.AllBookmarks, R.string.bookmark, R.string.all_bookmark, R.drawable.ic_bookmark),
    MyNavEntry(MyAction.ReadRecord, R.string.read_record, R.string.read_record_summary, R.drawable.ic_history),
    MyNavEntry(MyAction.FileManage, R.string.file_manage, R.string.file_manage_summary, R.drawable.ic_folder_outline),
    MyNavEntry(MyAction.About, R.string.about, iconRes = R.drawable.ic_cfg_about),
)

@Composable
fun MyRootScreen(
    onAction: (MyAction) -> Unit,
    onThemeModeChanged: () -> Unit,
) {
    var showCustomizeDialog by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        topBar = {
            LegadoTopAppBar(
                title = stringResource(R.string.my),
                actions = {
                    LegadoOverflowMenuButton { dismiss ->
                        LegadoDropdownMenuItem(
                            text = stringResource(R.string.customize_my),
                            onClick = { dismiss(); showCustomizeDialog = true },
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        MyPreferenceScreen(
            isMore = false,
            showCustomizeDialog = showCustomizeDialog,
            onDismissCustomization = { showCustomizeDialog = false },
            onAction = onAction,
            onThemeModeChanged = onThemeModeChanged,
            modifier = Modifier.fillMaxSize().padding(innerPadding),
        )
    }
}

@Composable
fun MyPreferenceScreen(
    isMore: Boolean,
    showCustomizeDialog: Boolean,
    onDismissCustomization: () -> Unit,
    onAction: (MyAction) -> Unit,
    onThemeModeChanged: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var moreItems by rememberPrefStringSet(
        PreferKey.myMoreItems,
        emptySet(),
    )
    fun isVisible(key: String): Boolean = (key in moreItems) == isMore
    val visibleTop = topEntries.filter { isVisible(it.action.key) }
    val showThemeMode = isVisible(PreferKey.themeMode)
    val visibleSettings = settingEntries.filter { isVisible(it.action.key) }
    val visibleAbout = aboutEntries.filter { isVisible(it.action.key) }

    Box(modifier = modifier, contentAlignment = Alignment.TopCenter) {
        LazyColumn(modifier = Modifier.widthIn(max = 720.dp).fillMaxSize().testTag("my_preferences"), contentPadding = PaddingValues(vertical = 8.dp)) {
            visibleTop.forEach { entry -> item(key = entry.action.key) { MyEntry(entry, onAction) } }
            if (showThemeMode) {
                item(key = PreferKey.themeMode) {
                    ListPreferenceItem(
                        key = PreferKey.themeMode,
                        default = "0",
                        title = stringResource(R.string.theme_mode),
                        summaryOverride = stringResource(R.string.theme_mode_desc),
                        iconRes = R.drawable.ic_cfg_theme,
                        entries = stringArrayResource(R.array.theme_mode),
                        entryValues = stringArrayResource(R.array.theme_mode_v),
                        onValueChange = { onThemeModeChanged() },
                    )
                }
            }
            if (visibleSettings.isNotEmpty()) {
                item(key = "settingCategory") { PreferenceCategoryHeader(title = stringResource(R.string.setting)) }
                visibleSettings.forEach { entry -> item(key = entry.action.key) { MyEntry(entry, onAction) } }
            }
            if (visibleAbout.isNotEmpty() || !isMore) {
                item(key = "aboutCategory") { PreferenceCategoryHeader(title = stringResource(R.string.other)) }
                visibleAbout.forEach { entry -> item(key = entry.action.key) { MyEntry(entry, onAction) } }
                if (!isMore) {
                    item(key = MyAction.Exit.key) {
                        PreferenceItem(
                            title = stringResource(R.string.exit),
                            iconRes = R.drawable.ic_exit,
                            onClick = { onAction(MyAction.Exit) },
                        )
                    }
                }
            }
            if (!isMore) {
                item(key = MyAction.More.key) {
                    PreferenceItem(
                        title = stringResource(R.string.reader_menu_more),
                        iconRes = R.drawable.ic_more_vert,
                        onClick = { onAction(MyAction.More) },
                    )
                }
            }
        }
    }

    if (showCustomizeDialog) {
        val allCustomizable = buildList {
            addAll(topEntries.map { it.action.key to stringResource(it.titleRes) })
            add(PreferKey.themeMode to stringResource(R.string.theme_mode))
            addAll(settingEntries.map { it.action.key to stringResource(it.titleRes) })
            addAll(aboutEntries.map { it.action.key to stringResource(it.titleRes) })
        }
        LegadoMultiChoiceDialog(
            title = stringResource(R.string.my_more_items),
            entries = allCustomizable.map { it.second },
            entryValues = allCustomizable.map { it.first },
            selectedValues = moreItems,
            onDismiss = onDismissCustomization,
            onConfirm = { moreItems = it },
        )
    }
}

@Composable
private fun MyEntry(entry: MyNavEntry, onAction: (MyAction) -> Unit) {
    PreferenceItem(
        title = stringResource(entry.titleRes),
        summary = entry.summaryRes?.let { stringResource(it) },
        iconRes = entry.iconRes,
        onClick = { onAction(entry.action) },
    )
}
