package io.legado.app.ui.main.my

import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentContainerView
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.ThemeConfig
import io.legado.app.ui.about.AboutActivity
import io.legado.app.ui.about.ReadRecordActivity
import io.legado.app.ui.about.checkAppUpdate
import io.legado.app.ui.book.bookmark.AllBookmarkActivity
import io.legado.app.ui.book.source.manage.BookSourceActivity
import io.legado.app.ui.book.toc.rule.TxtTocRuleActivity
import io.legado.app.ui.compose.component.LegadoDropdownMenuItem
import io.legado.app.ui.compose.component.LegadoMultiChoiceDialog
import io.legado.app.ui.compose.component.LegadoOverflowMenuButton
import io.legado.app.ui.compose.component.LegadoTopAppBar
import io.legado.app.ui.compose.prefs.ListPreferenceItem
import io.legado.app.ui.compose.prefs.PreferenceCategoryHeader
import io.legado.app.ui.compose.prefs.PreferenceItem
import io.legado.app.ui.compose.prefs.rememberPrefStringSet
import io.legado.app.ui.config.BackupConfigFragment
import io.legado.app.ui.config.ConfigActivity
import io.legado.app.ui.config.ConfigTag
import io.legado.app.ui.config.OtherConfigFragment
import io.legado.app.ui.config.ThemeConfigFragment
import io.legado.app.ui.dict.rule.DictRuleActivity
import io.legado.app.ui.file.FileManageActivity
import io.legado.app.ui.main.MainFragmentInterface
import io.legado.app.ui.replace.ReplaceRuleActivity
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.utils.LogUtils
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.startActivity
import io.legado.app.utils.useTwoPaneLayout

class MyFragment() : Fragment(), MainFragmentInterface {

    constructor(position: Int) : this() {
        val bundle = Bundle()
        bundle.putInt("position", position)
        arguments = bundle
    }

    override val position: Int? get() = arguments?.getInt("position")

    private var currentDetailTag by mutableStateOf(ConfigTag.OTHER_CONFIG)
    private var detailContainerId: Int = View.NO_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        currentDetailTag = savedInstanceState?.getString("currentDetailTag") ?: ConfigTag.OTHER_CONFIG
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("currentDetailTag", currentDetailTag)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LegadoTheme {
                    MyRootScreen()
                }
            }
        }
    }

    @Composable
    private fun MyRootScreen() {
        val configuration = LocalConfiguration.current
        val context = LocalContext.current
        val twoPane = remember(configuration) { context.useTwoPaneLayout }
        val leftId = remember { View.generateViewId() }
        val rightId = remember {
            View.generateViewId().also { detailContainerId = it }
        }
        val detailTitleRes = when (currentDetailTag) {
            ConfigTag.BACKUP_CONFIG -> R.string.backup_restore
            ConfigTag.THEME_CONFIG -> R.string.theme_setting
            ConfigTag.MY_MORE -> R.string.reader_menu_more
            else -> R.string.other_setting
        }

        Scaffold(
            topBar = {
                LegadoTopAppBar(
                    title = stringResource(R.string.my),
                    actions = {
                        LegadoOverflowMenuButton { dismiss ->
                            LegadoDropdownMenuItem(
                                text = stringResource(R.string.customize_my),
                                onClick = {
                                    dismiss()
                                    (childFragmentManager.findFragmentByTag("prefFragment")
                                        as? MyPreferenceFragment)?.showCustomization()
                                }
                            )
                        }
                    }
                )
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                Box(
                    modifier = if (twoPane) {
                        Modifier
                            .width(360.dp)
                            .fillMaxHeight()
                    } else {
                        Modifier.fillMaxSize()
                    }
                ) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            FragmentContainerView(ctx).apply {
                                id = leftId
                                val fragmentTag = "prefFragment"
                                val preferenceFragment =
                                    childFragmentManager.findFragmentByTag(fragmentTag)
                                        ?: MyPreferenceFragment()
                                childFragmentManager.beginTransaction()
                                    .replace(leftId, preferenceFragment, fragmentTag)
                                    .commitAllowingStateLoss()
                            }
                        }
                    )
                }

                if (twoPane) {
                    VerticalDivider()
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(detailTitleRes),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            when (currentDetailTag) {
                                ConfigTag.THEME_CONFIG -> {
                                    TextButton(
                                        onClick = {
                                            (childFragmentManager.findFragmentByTag("detail:${ConfigTag.THEME_CONFIG}")
                                                as? ThemeConfigFragment)?.toggleThemeMode()
                                        }
                                    ) {
                                        Text(stringResource(R.string.theme_mode))
                                    }
                                }
                                ConfigTag.BACKUP_CONFIG -> {
                                    LegadoOverflowMenuButton { dismiss ->
                                        LegadoDropdownMenuItem(
                                            text = stringResource(R.string.menu_import_old_version),
                                            onClick = {
                                                dismiss()
                                                (childFragmentManager.findFragmentByTag("detail:${ConfigTag.BACKUP_CONFIG}")
                                                    as? BackupConfigFragment)?.launchImportOld()
                                            }
                                        )
                                        LegadoDropdownMenuItem(
                                            text = stringResource(R.string.log),
                                            onClick = {
                                                dismiss()
                                                (childFragmentManager.findFragmentByTag("detail:${ConfigTag.BACKUP_CONFIG}")
                                                    as? BackupConfigFragment)?.showAppLog()
                                            }
                                        )
                                    }
                                }
                            }
                        }
                        HorizontalDivider()
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                        ) {
                            AndroidView(
                                modifier = Modifier.fillMaxSize(),
                                factory = { ctx ->
                                    FragmentContainerView(ctx).apply {
                                        id = rightId
                                        bindDetailFragment(rightId, currentDetailTag)
                                    }
                                },
                                update = {
                                    bindDetailFragment(rightId, currentDetailTag)
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    private fun bindDetailFragment(containerId: Int, configTag: String) {
        val tag = "detail:$configTag"
        val currentInContainer = childFragmentManager.findFragmentById(containerId)
        if (currentInContainer?.tag == tag) return
        val existing = childFragmentManager.findFragmentByTag(tag)
        val detailFragment: Fragment = existing ?: when (configTag) {
            ConfigTag.BACKUP_CONFIG -> BackupConfigFragment()
            ConfigTag.THEME_CONFIG -> ThemeConfigFragment()
            ConfigTag.MY_MORE -> MyPreferenceFragment().apply {
                arguments = Bundle().apply {
                    putString("configTag", ConfigTag.MY_MORE)
                }
            }
            else -> OtherConfigFragment()
        }
        childFragmentManager.beginTransaction()
            .replace(containerId, detailFragment, tag)
            .commitAllowingStateLoss()
    }

    fun openConfigDetail(configTag: String): Boolean {
        if (!requireContext().useTwoPaneLayout) return false
        currentDetailTag = configTag
        if (detailContainerId != View.NO_ID) {
            bindDetailFragment(detailContainerId, configTag)
        }
        return true
    }

    /**
     * 配置（纯 Jetpack Compose 实现）
     */
    class MyPreferenceFragment : Fragment(),
        SharedPreferences.OnSharedPreferenceChangeListener {

        private val isMore: Boolean
            get() = arguments?.getString("configTag") == ConfigTag.MY_MORE ||
                activity?.intent?.getStringExtra("configTag") == ConfigTag.MY_MORE

        private var showCustomizeDialog by mutableStateOf(false)

        fun showCustomization() {
            showCustomizeDialog = true
        }

        private data class MyNavEntry(
            val key: String,
            @StringRes val titleRes: Int,
            @StringRes val summaryRes: Int? = null,
            @DrawableRes val iconRes: Int,
            val category: String = "",
        )

        private val topEntries = listOf(
            MyNavEntry("bookSourceManage", R.string.book_source_manage, R.string.book_source_manage_desc, R.drawable.ic_cfg_source),
            MyNavEntry("txtTocRuleManage", R.string.txt_toc_rule, R.string.config_txt_toc_rule, R.drawable.ic_cfg_source),
            MyNavEntry("replaceManage", R.string.replace_purify, R.string.replace_purify_desc, R.drawable.ic_cfg_replace),
            MyNavEntry("dictRuleManage", R.string.dict_rule, R.string.config_dict_rule, R.drawable.ic_translate),
        )

        private val settingEntries = listOf(
            MyNavEntry("web_dav_setting", R.string.backup_restore, R.string.web_dav_set_import_old, R.drawable.ic_cfg_backup, "setting"),
            MyNavEntry("theme_setting", R.string.theme_setting, R.string.theme_setting_s, R.drawable.ic_cfg_theme, "setting"),
            MyNavEntry("setting", R.string.other_setting, R.string.other_setting_s, R.drawable.ic_cfg_other, "setting"),
        )

        private val aboutEntries = listOf(
            MyNavEntry("bookmark", R.string.bookmark, R.string.all_bookmark, R.drawable.ic_bookmark, "about"),
            MyNavEntry("readRecord", R.string.read_record, R.string.read_record_summary, R.drawable.ic_history, "about"),
            MyNavEntry("fileManage", R.string.file_manage, R.string.file_manage_summary, R.drawable.ic_folder_outline, "about"),
            MyNavEntry("about", R.string.about, null, R.drawable.ic_cfg_about, "about"),
            MyNavEntry("check_update", R.string.check_update, null, R.drawable.ic_download, "about"),
            MyNavEntry("check_beta_update", R.string.check_beta_update, null, R.drawable.ic_download, "about"),
        )

        override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?,
        ): View {
            if (isMore && activity is ConfigActivity) {
                activity?.setTitle(R.string.reader_menu_more)
            }
            return ComposeView(requireContext()).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                setContent {
                    LegadoTheme {
                        MyPreferenceScreen()
                    }
                }
            }
        }

        override fun onResume() {
            super.onResume()
            requireContext().defaultSharedPreferences.registerOnSharedPreferenceChangeListener(this)
        }

        override fun onPause() {
            requireContext().defaultSharedPreferences.unregisterOnSharedPreferenceChangeListener(this)
            super.onPause()
        }

        override fun onSharedPreferenceChanged(
            sharedPreferences: SharedPreferences?,
            key: String?
        ) {
            when (key) {
                "recordLog" -> LogUtils.upLevel()
            }
        }

        @Composable
        private fun MyPreferenceScreen() {
            var moreItems by rememberPrefStringSet(
                PreferKey.myMoreItems,
                setOf("check_update", "check_beta_update")
            )

            fun isVisible(key: String): Boolean {
                return when (key) {
                    "myMore", "exit" -> !isMore
                    else -> (key in moreItems) == isMore
                }
            }

            val visibleTop = topEntries.filter { isVisible(it.key) }
            val showThemeMode = isVisible(PreferKey.themeMode)
            val visibleSettings = settingEntries.filter { isVisible(it.key) }
            val visibleAbout = aboutEntries.filter { isVisible(it.key) }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                visibleTop.forEach { entry ->
                    item(key = entry.key) {
                        PreferenceItem(
                            title = stringResource(entry.titleRes),
                            summary = entry.summaryRes?.let { stringResource(it) },
                            iconRes = entry.iconRes,
                            onClick = { onItemClick(entry.key) },
                        )
                    }
                }

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
                            onValueChange = {
                                view?.post { ThemeConfig.applyDayNight(requireContext()) }
                            }
                        )
                    }
                }

                if (visibleSettings.isNotEmpty()) {
                    item(key = "settingCategory") {
                        PreferenceCategoryHeader(title = stringResource(R.string.setting))
                    }
                    visibleSettings.forEach { entry ->
                        item(key = entry.key) {
                            PreferenceItem(
                                title = stringResource(entry.titleRes),
                                summary = entry.summaryRes?.let { stringResource(it) },
                                iconRes = entry.iconRes,
                                onClick = { onItemClick(entry.key) },
                            )
                        }
                    }
                }

                if (visibleAbout.isNotEmpty() || !isMore) {
                    item(key = "aboutCategory") {
                        PreferenceCategoryHeader(title = stringResource(R.string.other))
                    }
                    visibleAbout.forEach { entry ->
                        item(key = entry.key) {
                            PreferenceItem(
                                title = stringResource(entry.titleRes),
                                summary = entry.summaryRes?.let { stringResource(it) },
                                iconRes = entry.iconRes,
                                onClick = { onItemClick(entry.key) },
                            )
                        }
                    }
                    if (!isMore) {
                        item(key = "exit") {
                            PreferenceItem(
                                title = stringResource(R.string.exit),
                                iconRes = R.drawable.ic_exit,
                                onClick = { onItemClick("exit") },
                            )
                        }
                    }
                }

                if (!isMore) {
                    item(key = "myMore") {
                        PreferenceItem(
                            title = stringResource(R.string.reader_menu_more),
                            iconRes = R.drawable.ic_more_vert,
                            onClick = { onItemClick("myMore") },
                        )
                    }
                }
            }

            if (showCustomizeDialog) {
                val allCustomizable = buildList {
                    addAll(topEntries.map { it.key to stringResource(it.titleRes) })
                    add(PreferKey.themeMode to stringResource(R.string.theme_mode))
                    addAll(settingEntries.map { it.key to stringResource(it.titleRes) })
                    addAll(aboutEntries.map { it.key to stringResource(it.titleRes) })
                }
                LegadoMultiChoiceDialog(
                    title = stringResource(R.string.my_more_items),
                    entries = allCustomizable.map { it.second },
                    entryValues = allCustomizable.map { it.first },
                    selectedValues = moreItems,
                    onDismiss = { showCustomizeDialog = false },
                    onConfirm = { newSet ->
                        moreItems = newSet
                    }
                )
            }
        }

        private fun onItemClick(key: String) {
            when (key) {
                "myMore" -> {
                    if ((parentFragment as? MyFragment)?.openConfigDetail(ConfigTag.MY_MORE) != true) {
                        startActivity<ConfigActivity> { putExtra("configTag", ConfigTag.MY_MORE) }
                    }
                }
                "check_update" -> checkAppUpdate()
                "check_beta_update" -> checkAppUpdate(beta = true)
                "bookSourceManage" -> startActivity<BookSourceActivity>()
                "replaceManage" -> startActivity<ReplaceRuleActivity>()
                "dictRuleManage" -> startActivity<DictRuleActivity>()
                "txtTocRuleManage" -> startActivity<TxtTocRuleActivity>()
                "bookmark" -> startActivity<AllBookmarkActivity>()
                "setting" -> {
                    if ((parentFragment as? MyFragment)?.openConfigDetail(ConfigTag.OTHER_CONFIG) != true) {
                        startActivity<ConfigActivity> {
                            putExtra("configTag", ConfigTag.OTHER_CONFIG)
                        }
                    }
                }

                "web_dav_setting" -> {
                    if ((parentFragment as? MyFragment)?.openConfigDetail(ConfigTag.BACKUP_CONFIG) != true) {
                        startActivity<ConfigActivity> {
                            putExtra("configTag", ConfigTag.BACKUP_CONFIG)
                        }
                    }
                }

                "theme_setting" -> {
                    if ((parentFragment as? MyFragment)?.openConfigDetail(ConfigTag.THEME_CONFIG) != true) {
                        startActivity<ConfigActivity> {
                            putExtra("configTag", ConfigTag.THEME_CONFIG)
                        }
                    }
                }

                "fileManage" -> startActivity<FileManageActivity>()
                "readRecord" -> startActivity<ReadRecordActivity>()
                "about" -> startActivity<AboutActivity>()
                "exit" -> activity?.finish()
            }
        }
    }
}
