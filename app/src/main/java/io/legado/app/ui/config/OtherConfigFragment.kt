package io.legado.app.ui.config

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.jeremyliao.liveeventbus.LiveEventBus
import io.legado.app.R
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.databinding.DialogEditCodeBinding
import io.legado.app.databinding.DialogEditTextBinding
import io.legado.app.help.AppFreezeMonitor
import io.legado.app.help.DispatchersMonitor
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.normalizeJsSourceApiToken
import io.legado.app.help.http.Cronet
import io.legado.app.lib.dialogs.alert
import io.legado.app.model.CheckSource
import io.legado.app.model.ImageProvider
import io.legado.app.receiver.SharedReceiverActivity
import io.legado.app.ui.compose.component.LegadoTextInputDialog
import io.legado.app.ui.compose.prefs.ListPreferenceItem
import io.legado.app.ui.compose.prefs.PreferenceCategoryHeader
import io.legado.app.ui.compose.prefs.PreferenceItem
import io.legado.app.ui.compose.prefs.SwitchPreferenceItem
import io.legado.app.ui.compose.prefs.rememberPrefBoolean
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.video.config.SettingsDialog
import io.legado.app.ui.widget.code.addJsonPattern
import io.legado.app.ui.widget.number.NumberPickerDialog
import io.legado.app.utils.LogUtils
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.isJsonObject
import io.legado.app.utils.postEvent
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.putPrefString
import io.legado.app.utils.removePref
import io.legado.app.utils.restart
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.supportsPromotedNotifications
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import splitties.init.appCtx

/**
 * 其它设置
 */
class OtherConfigFragment : Fragment(),
    SharedPreferences.OnSharedPreferenceChangeListener {

    private val viewModel by activityViewModels<ConfigViewModel>()
    private val packageManager = appCtx.packageManager
    private val componentName = ComponentName(
        appCtx,
        SharedReceiverActivity::class.java.name
    )
    private var refreshTick by mutableIntStateOf(0)

    private val localBookTreeSelect = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { treeUri ->
            AppConfig.defaultBookTreeUri = treeUri.toString()
            refreshTick++
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        putPrefBoolean(PreferKey.processText, isProcessTextEnabled())
        requireContext().defaultSharedPreferences.registerOnSharedPreferenceChangeListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        requireContext().defaultSharedPreferences.unregisterOnSharedPreferenceChangeListener(this)
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
                    OtherConfigScreen()
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        activity?.setTitle(R.string.other_setting)
    }

    private fun onOtherPreferenceClick(key: String) {
        when (key) {
            PreferKey.userAgent -> showUserAgentDialog()
            PreferKey.customHosts -> showCustomHostsDialog()
            PreferKey.videoSetting -> showDialogFragment(SettingsDialog(requireActivity()))
            PreferKey.defaultBookTreeUri -> localBookTreeSelect.launch {
                title = getString(R.string.select_book_folder)
                mode = HandleFileContract.DIR_SYS
            }

            PreferKey.preDownloadNum -> NumberPickerDialog(requireContext())
                .setTitle(getString(R.string.pre_download))
                .setMaxValue(9999)
                .setMinValue(0)
                .setValue(AppConfig.preDownloadNum)
                .show {
                    AppConfig.preDownloadNum = it
                    refreshTick++
                }

            PreferKey.threadCount -> NumberPickerDialog(requireContext())
                .setTitle(getString(R.string.threads_num_title))
                .setMaxValue(999)
                .setMinValue(1)
                .setValue(AppConfig.threadCount)
                .show {
                    AppConfig.threadCount = it
                    refreshTick++
                }

            PreferKey.cleanCache -> clearCache()
            PreferKey.checkSource -> showDialogFragment<CheckSourceConfig>()
            PreferKey.bitmapCacheSize -> {
                NumberPickerDialog(requireContext())
                    .setTitle(getString(R.string.bitmap_cache_size))
                    .setMaxValue(1024)
                    .setMinValue(1)
                    .setValue(AppConfig.bitmapCacheSize)
                    .show {
                        AppConfig.bitmapCacheSize = it
                        ImageProvider.bitmapLruCache.resize(ImageProvider.cacheSize)
                        refreshTick++
                    }
            }
            PreferKey.imageRetainNum -> NumberPickerDialog(requireContext())
                .setTitle(getString(R.string.image_retain_number))
                .setMaxValue(999)
                .setMinValue(0)
                .setValue(AppConfig.imageRetainNum)
                .show {
                    AppConfig.imageRetainNum = it
                    refreshTick++
                }

            PreferKey.sourceEditMaxLine -> {
                NumberPickerDialog(requireContext())
                    .setTitle(getString(R.string.source_edit_text_max_line))
                    .setMaxValue(Int.MAX_VALUE)
                    .setMinValue(10)
                    .setValue(AppConfig.sourceEditMaxLine)
                    .show {
                        AppConfig.sourceEditMaxLine = it
                        refreshTick++
                    }
            }

            PreferKey.clearWebViewData -> clearWebViewData()
            PreferKey.shrinkDatabase -> shrinkDatabase()
        }
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        refreshTick++
        when (key) {
            PreferKey.threadCount -> {
                postEvent(PreferKey.threadCount, "")
            }

            PreferKey.recordLog -> {
                AppConfig.recordLog = appCtx.getPrefBoolean(PreferKey.recordLog)
                LogUtils.upLevel()
                LogUtils.logDeviceInfo()
                LiveEventBus.config().enableLogger(AppConfig.recordLog)
                AppFreezeMonitor.init(appCtx)
                DispatchersMonitor.init()
            }

            PreferKey.cronet -> if (appCtx.getPrefBoolean(PreferKey.cronet)) {
                Cronet.preDownload()
            }

            PreferKey.processText -> sharedPreferences?.let {
                setProcessTextEnable(it.getBoolean(key, true))
            }

            PreferKey.showDiscovery -> postEvent(EventBus.NOTIFY_MAIN, true)
            PreferKey.language -> viewLifecycleOwner.lifecycleScope.launch {
                delay(1000)
                appCtx.restart()
            }

            PreferKey.liveUpdateNotifications -> {
                if (
                    supportsPromotedNotifications() &&
                    sharedPreferences?.getBoolean(key, false) == true &&
                    !NotificationManagerCompat.from(requireContext())
                        .canPostPromotedNotifications()
                ) {
                    val intent = promotedNotificationSettingsIntent()
                    if (intent.resolveActivity(requireContext().packageManager) != null) {
                        startActivity(intent)
                    } else {
                        putPrefBoolean(PreferKey.liveUpdateNotifications, false)
                        toastOnUi(R.string.tip_cannot_jump_setting_page)
                    }
                }
            }
        }
    }

    private fun canConfigurePromotedNotifications(): Boolean {
        if (!supportsPromotedNotifications()) return false
        val context = requireContext()
        return NotificationManagerCompat.from(context).canPostPromotedNotifications() ||
            promotedNotificationSettingsIntent().resolveActivity(context.packageManager) != null
    }

    private fun promotedNotificationSettingsIntent() =
        Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, requireContext().packageName)
        }

    @Composable
    private fun OtherConfigScreen() {
        val autoRefresh by rememberPrefBoolean(PreferKey.autoRefresh, false)
        var showTokenDialog by remember { mutableStateOf(false) }

        val userAgentSummary = remember(refreshTick) { AppConfig.userAgent }
        val defaultBookTreeSummary = remember(refreshTick) {
            AppConfig.defaultBookTreeUri ?: getString(R.string.book_tree_uri_s)
        }
        val sourceEditMaxLineSummary = remember(refreshTick) {
            getString(R.string.source_edit_max_line_summary, AppConfig.sourceEditMaxLine.toString())
        }
        val checkSourceSummary = remember(refreshTick) { CheckSource.summary }
        val bitmapCacheSummary = remember(refreshTick) {
            getString(R.string.bitmap_cache_size_summary, AppConfig.bitmapCacheSize.toString())
        }
        val imageRetainSummary = remember(refreshTick) {
            getString(R.string.image_retain_number_summary, AppConfig.imageRetainNum.toString())
        }
        val preDownloadSummary = remember(refreshTick) {
            getString(R.string.pre_download_s, AppConfig.preDownloadNum.toString())
        }
        val threadCountSummary = remember(refreshTick) {
            getString(R.string.threads_num, AppConfig.threadCount.toString())
        }
        val tokenValue = remember(refreshTick) { AppConfig.jsSourceApiToken.orEmpty() }
        val tokenSummary = remember(refreshTick, tokenValue) {
            if (tokenValue.isBlank()) {
                getString(R.string.js_source_api_token_summary)
            } else {
                getString(R.string.js_source_api_token_configured)
            }
        }
        val showLiveUpdate = remember { canConfigurePromotedNotifications() }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            item(key = PreferKey.language) {
                ListPreferenceItem(
                    key = PreferKey.language,
                    default = "auto",
                    title = stringResource(R.string.language),
                    entries = stringArrayResource(R.array.language),
                    entryValues = stringArrayResource(R.array.language_value),
                )
            }

            item(key = "mainActivityCategory") {
                PreferenceCategoryHeader(title = stringResource(R.string.main_activity))
            }
            item(key = PreferKey.autoRefresh) {
                SwitchPreferenceItem(
                    key = PreferKey.autoRefresh,
                    default = false,
                    title = stringResource(R.string.pt_auto_refresh),
                    summary = stringResource(R.string.ps_auto_refresh),
                )
            }
            if (autoRefresh) {
                item(key = PreferKey.onlyUpdateRead) {
                    SwitchPreferenceItem(
                        key = PreferKey.onlyUpdateRead,
                        default = false,
                        title = stringResource(R.string.only_update_read),
                        summary = stringResource(R.string.ps_only_update_read),
                    )
                }
            }
            item(key = PreferKey.defaultToRead) {
                SwitchPreferenceItem(
                    key = PreferKey.defaultToRead,
                    default = false,
                    title = stringResource(R.string.pt_default_read),
                    summary = stringResource(R.string.ps_default_read),
                )
            }
            item(key = PreferKey.showDiscovery) {
                SwitchPreferenceItem(
                    key = PreferKey.showDiscovery,
                    default = true,
                    title = stringResource(R.string.show_discovery),
                )
            }
            item(key = PreferKey.showDiscoveryFastScroller) {
                SwitchPreferenceItem(
                    key = PreferKey.showDiscoveryFastScroller,
                    default = false,
                    title = stringResource(R.string.show_discovery_fast_scroller),
                )
            }
            item(key = PreferKey.defaultHomePage) {
                ListPreferenceItem(
                    key = PreferKey.defaultHomePage,
                    default = "bookshelf",
                    title = stringResource(R.string.default_home_page),
                    entries = stringArrayResource(R.array.default_home_page),
                    entryValues = stringArrayResource(R.array.default_home_page_value),
                )
            }

            item(key = "otherSettingCategory") {
                PreferenceCategoryHeader(title = stringResource(R.string.other_setting))
            }
            item(key = PreferKey.userAgent) {
                PreferenceItem(
                    title = stringResource(R.string.user_agent),
                    summary = userAgentSummary,
                    onClick = { onOtherPreferenceClick(PreferKey.userAgent) },
                )
            }
            item(key = PreferKey.customHosts) {
                PreferenceItem(
                    title = stringResource(R.string.custom_hosts),
                    summary = stringResource(R.string.custom_hosts_summary),
                    onClick = { onOtherPreferenceClick(PreferKey.customHosts) },
                )
            }
            item(key = PreferKey.defaultBookTreeUri) {
                PreferenceItem(
                    title = stringResource(R.string.book_tree_uri_t),
                    summary = defaultBookTreeSummary,
                    onClick = { onOtherPreferenceClick(PreferKey.defaultBookTreeUri) },
                )
            }
            item(key = PreferKey.sourceEditMaxLine) {
                PreferenceItem(
                    title = stringResource(R.string.source_edit_text_max_line),
                    summary = sourceEditMaxLineSummary,
                    onClick = { onOtherPreferenceClick(PreferKey.sourceEditMaxLine) },
                )
            }
            item(key = PreferKey.checkSource) {
                PreferenceItem(
                    title = stringResource(R.string.check_source_config),
                    summary = checkSourceSummary,
                    onClick = { onOtherPreferenceClick(PreferKey.checkSource) },
                )
            }
            item(key = "uploadRule") {
                PreferenceItem(
                    title = stringResource(R.string.direct_link_upload_rule),
                    summary = stringResource(R.string.direct_link_upload_rule_summary),
                    onClick = { onOtherPreferenceClick("uploadRule") },
                )
            }
            item(key = PreferKey.cronet) {
                SwitchPreferenceItem(
                    key = PreferKey.cronet,
                    default = false,
                    title = "Cronet",
                    summary = stringResource(R.string.pref_cronet_summary),
                )
            }
            item(key = PreferKey.antiAlias) {
                SwitchPreferenceItem(
                    key = PreferKey.antiAlias,
                    default = false,
                    title = stringResource(R.string.anti_alias),
                    summary = stringResource(R.string.pref_anti_alias_summary),
                )
            }
            item(key = PreferKey.bitmapCacheSize) {
                PreferenceItem(
                    title = stringResource(R.string.bitmap_cache_size),
                    summary = bitmapCacheSummary,
                    onClick = { onOtherPreferenceClick(PreferKey.bitmapCacheSize) },
                )
            }
            item(key = PreferKey.imageRetainNum) {
                PreferenceItem(
                    title = stringResource(R.string.image_retain_number),
                    summary = imageRetainSummary,
                    onClick = { onOtherPreferenceClick(PreferKey.imageRetainNum) },
                )
            }
            item(key = PreferKey.preDownloadNum) {
                PreferenceItem(
                    title = stringResource(R.string.pre_download),
                    summary = preDownloadSummary,
                    onClick = { onOtherPreferenceClick(PreferKey.preDownloadNum) },
                )
            }
            item(key = PreferKey.replaceEnableDefault) {
                SwitchPreferenceItem(
                    key = PreferKey.replaceEnableDefault,
                    default = true,
                    title = stringResource(R.string.replace_enable_default_t),
                    summary = stringResource(R.string.replace_enable_default_s),
                )
            }
            item(key = "mediaButtonOnExit") {
                SwitchPreferenceItem(
                    key = "mediaButtonOnExit",
                    default = true,
                    title = stringResource(R.string.media_button_on_exit_title),
                    summary = stringResource(R.string.media_button_on_exit_summary),
                )
            }
            item(key = PreferKey.readAloudByMediaButton) {
                SwitchPreferenceItem(
                    key = PreferKey.readAloudByMediaButton,
                    default = false,
                    title = stringResource(R.string.read_aloud_by_media_button_title),
                    summary = stringResource(R.string.read_aloud_by_media_button_summary),
                )
            }
            item(key = PreferKey.ignoreAudioFocus) {
                SwitchPreferenceItem(
                    key = PreferKey.ignoreAudioFocus,
                    default = false,
                    title = stringResource(R.string.ignore_audio_focus_title),
                    summary = stringResource(R.string.ignore_audio_focus_summary),
                )
            }
            item(key = PreferKey.autoClearExpired) {
                SwitchPreferenceItem(
                    key = PreferKey.autoClearExpired,
                    default = true,
                    title = stringResource(R.string.auto_clear_expired),
                    summary = stringResource(R.string.auto_clear_expired_summary),
                )
            }
            item(key = PreferKey.showAddToShelfAlert) {
                SwitchPreferenceItem(
                    key = PreferKey.showAddToShelfAlert,
                    default = true,
                    title = stringResource(R.string.show_add_to_shelf_alert_title),
                    summary = stringResource(R.string.show_add_to_shelf_alert_summary),
                )
            }
            item(key = "autoUpdateVariant") {
                SwitchPreferenceItem(
                    key = "autoUpdateVariant",
                    default = true,
                    title = stringResource(R.string.auto_update),
                    summary = stringResource(R.string.auto_update_summary),
                )
            }
            if (showLiveUpdate) {
                item(key = PreferKey.liveUpdateNotifications) {
                    SwitchPreferenceItem(
                        key = PreferKey.liveUpdateNotifications,
                        default = false,
                        title = stringResource(R.string.live_update_notifications),
                        summary = stringResource(R.string.live_update_notifications_summary),
                    )
                }
            }
            item(key = PreferKey.showMangaUi) {
                SwitchPreferenceItem(
                    key = PreferKey.showMangaUi,
                    default = true,
                    title = stringResource(R.string.show_manga_ui),
                )
            }
            item(key = PreferKey.videoSetting) {
                PreferenceItem(
                    title = stringResource(R.string.video_setting),
                    summary = stringResource(R.string.video_setting_summary),
                    onClick = { onOtherPreferenceClick(PreferKey.videoSetting) },
                )
            }
            item(key = PreferKey.jsSourceApiTokenRequired) {
                SwitchPreferenceItem(
                    key = PreferKey.jsSourceApiTokenRequired,
                    default = true,
                    title = stringResource(R.string.js_source_api_token_required_title),
                    summary = stringResource(R.string.js_source_api_token_required_summary),
                )
            }
            item(key = PreferKey.jsSourceApiToken) {
                PreferenceItem(
                    title = stringResource(R.string.js_source_api_token_title),
                    summary = tokenSummary,
                    onClick = { showTokenDialog = true },
                )
            }
            item(key = PreferKey.cleanCache) {
                PreferenceItem(
                    title = stringResource(R.string.clear_cache),
                    summary = stringResource(R.string.clear_cache_summary),
                    onClick = { onOtherPreferenceClick(PreferKey.cleanCache) },
                )
            }
            item(key = PreferKey.clearWebViewData) {
                PreferenceItem(
                    title = stringResource(R.string.clear_webview_data),
                    summary = stringResource(R.string.clear_webview_data_summary),
                    onClick = { onOtherPreferenceClick(PreferKey.clearWebViewData) },
                )
            }
            item(key = PreferKey.shrinkDatabase) {
                PreferenceItem(
                    title = stringResource(R.string.shrink_database),
                    summary = stringResource(R.string.shrink_database_summary),
                    onClick = { onOtherPreferenceClick(PreferKey.shrinkDatabase) },
                )
            }
            item(key = PreferKey.threadCount) {
                PreferenceItem(
                    title = stringResource(R.string.threads_num_title),
                    summary = threadCountSummary,
                    onClick = { onOtherPreferenceClick(PreferKey.threadCount) },
                )
            }
            item(key = PreferKey.processText) {
                SwitchPreferenceItem(
                    key = PreferKey.processText,
                    default = true,
                    title = stringResource(R.string.add_to_text_context_menu_t),
                    summary = stringResource(R.string.add_to_text_context_menu_s),
                )
            }
            item(key = PreferKey.recordLog) {
                SwitchPreferenceItem(
                    key = PreferKey.recordLog,
                    default = false,
                    title = stringResource(R.string.record_log),
                    summary = stringResource(R.string.record_debug_log),
                )
            }
            item(key = PreferKey.recordHttpLog) {
                SwitchPreferenceItem(
                    key = PreferKey.recordHttpLog,
                    default = false,
                    title = stringResource(R.string.record_http_log),
                    summary = stringResource(R.string.record_http_log_summary),
                )
            }
            item(key = PreferKey.recordHeapDump) {
                SwitchPreferenceItem(
                    key = PreferKey.recordHeapDump,
                    default = false,
                    title = stringResource(R.string.record_heap_dump_t),
                    summary = stringResource(R.string.record_heap_dump_s),
                )
            }
        }

        if (showTokenDialog) {
            LegadoTextInputDialog(
                title = stringResource(R.string.js_source_api_token_title),
                initialValue = tokenValue,
                isPassword = true,
                onDismiss = { showTokenDialog = false },
                onConfirm = { newValue ->
                    val token = normalizeJsSourceApiToken(newValue)
                    AppConfig.jsSourceApiToken = token
                    refreshTick++
                }
            )
        }
    }

    @SuppressLint("InflateParams")
    private fun showUserAgentDialog() {
        alert(getString(R.string.user_agent)) {
            val alertBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
                editView.hint = getString(R.string.user_agent)
                editView.setText(AppConfig.userAgent)
            }
            customView { alertBinding.root }
            okButton {
                val userAgent = alertBinding.editView.text?.toString()
                if (userAgent.isNullOrBlank()) {
                    removePref(PreferKey.userAgent)
                } else {
                    putPrefString(PreferKey.userAgent, userAgent)
                }
                refreshTick++
            }
            cancelButton()
        }
    }

    @SuppressLint("InflateParams")
    private fun showCustomHostsDialog() {
        alert(getString(R.string.custom_hosts)) {
            val alertBinding = DialogEditCodeBinding.inflate(layoutInflater).apply {
                editViewC.hint = getString(R.string.json_format)
                editView.addJsonPattern()
                editView.setText(AppConfig.customHosts)
            }
            customView { alertBinding.root }
            okButton {
                val customHosts = alertBinding.editView.text?.toString()
                if (customHosts.isJsonObject()) {
                    putPrefString(PreferKey.customHosts, customHosts!!)
                } else {
                    removePref(PreferKey.customHosts)
                }
                refreshTick++
            }
            cancelButton()
        }
    }

    private fun clearCache() {
        requireContext().alert(
            titleResource = R.string.clear_cache,
            messageResource = R.string.sure_del
        ) {
            okButton {
                viewModel.clearCache()
            }
            noButton()
        }
    }

    private fun shrinkDatabase() {
        alert(R.string.sure, R.string.shrink_database) {
            okButton {
                viewModel.shrinkDatabase()
            }
            noButton()
        }
    }

    private fun clearWebViewData() {
        alert(R.string.clear_webview_data, R.string.sure_del) {
            okButton {
                viewModel.clearWebViewData()
            }
            noButton()
        }
    }

    private fun isProcessTextEnabled(): Boolean {
        return packageManager.getComponentEnabledSetting(componentName) != PackageManager.COMPONENT_ENABLED_STATE_DISABLED
    }

    private fun setProcessTextEnable(enable: Boolean) {
        if (enable) {
            packageManager.setComponentEnabledSetting(
                componentName,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP
            )
        } else {
            packageManager.setComponentEnabledSetting(
                componentName,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP
            )
        }
    }
}
