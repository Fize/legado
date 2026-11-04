package io.legado.app.ui.config

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import io.legado.app.model.CheckSource
import com.jeremyliao.liveeventbus.LiveEventBus
import io.legado.app.R
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.help.AppFreezeMonitor
import io.legado.app.help.DispatchersMonitor
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.normalizeJsSourceApiToken
import io.legado.app.help.http.Cronet
import io.legado.app.model.ImageProvider
import io.legado.app.model.VideoPlay
import io.legado.app.ui.compose.component.LegadoCodeInputDialog
import io.legado.app.ui.compose.component.LegadoTextInputDialog
import io.legado.app.ui.compose.component.LegadoTopAppBar
import io.legado.app.ui.compose.prefs.ListPreferenceItem
import io.legado.app.ui.compose.prefs.PreferenceCategoryHeader
import io.legado.app.ui.compose.prefs.PreferenceItem
import io.legado.app.ui.compose.prefs.SwitchPreferenceItem
import io.legado.app.ui.compose.prefs.rememberPrefBoolean
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.utils.LogUtils
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.isJsonObject
import io.legado.app.utils.postEvent
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.putPrefString
import io.legado.app.utils.removePref
import io.legado.app.utils.restart
import io.legado.app.utils.supportsPromotedNotifications
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun OtherSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val viewModel: ConfigViewModel = viewModel()
    val packageManager = context.packageManager
    val componentName = remember(context) {
        ComponentName(context.packageName, "${context.packageName}.receiver.SharedReceiverActivity")
    }
    var refreshTick by remember { mutableIntStateOf(0) }
    var showUserAgentDialog by rememberSaveable { mutableStateOf(false) }
    var showCustomHostsDialog by rememberSaveable { mutableStateOf(false) }
    var showVideoSettingsDialog by remember { mutableStateOf(false) }
    var showCheckSourceDialog by remember { mutableStateOf(false) }
    var pendingNumber by remember { mutableStateOf<NumberSetting?>(null) }
    var pendingConfirmation by remember { mutableStateOf<String?>(null) }

    val localBookTreeSelect = rememberLauncherForActivityResult(HandleFileContract()) { result ->
        result.uri?.let { AppConfig.defaultBookTreeUri = it.toString(); refreshTick++ }
    }

    fun setProcessTextEnable(enable: Boolean) {
        packageManager.setComponentEnabledSetting(
            componentName,
            if (enable) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
    }

    fun openNumber(key: String, title: Int, min: Int, max: Int, value: Int) {
        pendingNumber = NumberSetting(key, title, min, max, value.coerceIn(min, max))
    }

    fun onOtherPreferenceClick(key: String) {
        when (key) {
            PreferKey.userAgent -> showUserAgentDialog = true
            PreferKey.customHosts -> showCustomHostsDialog = true
            PreferKey.videoSetting -> showVideoSettingsDialog = true
            PreferKey.defaultBookTreeUri -> localBookTreeSelect.launch {
                title = resources.getString(R.string.select_book_folder)
                mode = HandleFileContract.DIR_SYS
            }
            PreferKey.preDownloadNum -> openNumber(key, R.string.pre_download, 0, 9999, AppConfig.preDownloadNum)
            PreferKey.threadCount -> openNumber(key, R.string.threads_num_title, 1, 999, AppConfig.threadCount)
            PreferKey.cleanCache, PreferKey.clearWebViewData, PreferKey.shrinkDatabase -> pendingConfirmation = key
            PreferKey.checkSource -> showCheckSourceDialog = true
            PreferKey.bitmapCacheSize -> openNumber(key, R.string.bitmap_cache_size, 1, 1024, AppConfig.bitmapCacheSize)
            PreferKey.imageRetainNum -> openNumber(key, R.string.image_retain_number, 0, 999, AppConfig.imageRetainNum)
            PreferKey.sourceEditMaxLine -> openNumber(key, R.string.source_edit_text_max_line, 10, Int.MAX_VALUE, AppConfig.sourceEditMaxLine)
        }
    }

    fun onPreferenceChanged(preferences: SharedPreferences, key: String?) {
        refreshTick++
        when (key) {
            PreferKey.threadCount -> postEvent(PreferKey.threadCount, "")
            PreferKey.recordLog -> {
                AppConfig.recordLog = context.getPrefBoolean(PreferKey.recordLog)
                LogUtils.upLevel()
                LogUtils.logDeviceInfo()
                LiveEventBus.config().enableLogger(AppConfig.recordLog)
                AppFreezeMonitor.init(context)
                DispatchersMonitor.init()
            }
            PreferKey.cronet -> if (context.getPrefBoolean(PreferKey.cronet)) Cronet.preDownload()
            PreferKey.processText -> setProcessTextEnable(preferences.getBoolean(key, true))
            PreferKey.showDiscovery -> postEvent(EventBus.NOTIFY_MAIN, true)
            PreferKey.language -> scope.launch { delay(1000); context.restart() }
            PreferKey.liveUpdateNotifications -> {
                if (supportsPromotedNotifications() && preferences.getBoolean(key, false) &&
                    !NotificationManagerCompat.from(context).canPostPromotedNotifications()
                ) {
                    val intent = promotedNotificationSettingsIntent(context)
                    if (intent.resolveActivity(packageManager) != null) context.startActivity(intent)
                    else {
                        context.putPrefBoolean(PreferKey.liveUpdateNotifications, false)
                        context.toastOnUi(R.string.tip_cannot_jump_setting_page)
                    }
                }
            }
        }
    }

    DisposableEffect(context, componentName) {
        context.putPrefBoolean(
            PreferKey.processText,
            packageManager.getComponentEnabledSetting(componentName) != PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
        )
        val preferences = context.defaultSharedPreferences
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
            if (sharedPreferences != null) onPreferenceChanged(sharedPreferences, key)
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    val showLiveUpdate = remember(context) {
        supportsPromotedNotifications() && (
            NotificationManagerCompat.from(context).canPostPromotedNotifications() ||
                promotedNotificationSettingsIntent(context).resolveActivity(packageManager) != null
            )
    }

    Scaffold(
        topBar = {
            LegadoTopAppBar(title = stringResource(R.string.other_setting), onBack = onBack)
        },
    ) { padding ->
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().padding(padding)) {
            OtherConfigContent(
                context = context,
                refreshTick = refreshTick,
                onOtherPreferenceClick = ::onOtherPreferenceClick,
                onRefresh = { refreshTick++ },
                showLiveUpdate = showLiveUpdate,
            )
        }
    }

    pendingNumber?.let { setting ->
        NumberSettingDialog(
            setting = setting,
            onDismiss = { pendingNumber = null },
            onConfirm = { value ->
                when (setting.key) {
                    PreferKey.preDownloadNum -> AppConfig.preDownloadNum = value
                    PreferKey.threadCount -> AppConfig.threadCount = value
                    PreferKey.bitmapCacheSize -> { AppConfig.bitmapCacheSize = value; ImageProvider.bitmapLruCache.resize(ImageProvider.cacheSize) }
                    PreferKey.imageRetainNum -> AppConfig.imageRetainNum = value
                    PreferKey.sourceEditMaxLine -> AppConfig.sourceEditMaxLine = value
                    VIDEO_PRESS_SPEED_KEY -> VideoPlay.longPressSpeed = value
                }
                pendingNumber = null
                refreshTick++
                if (setting.key == PreferKey.threadCount) postEvent(PreferKey.threadCount, "")
            },
        )
    }
    pendingConfirmation?.let { key ->
        val title = when (key) {
            PreferKey.cleanCache -> R.string.clear_cache
            PreferKey.clearWebViewData -> R.string.clear_webview_data
            else -> R.string.shrink_database
        }
        AlertDialog(
            onDismissRequest = { pendingConfirmation = null },
            title = { Text(stringResource(title)) },
            text = { Text(stringResource(R.string.sure_del)) },
            confirmButton = {
                TextButton(onClick = {
                    when (key) {
                        PreferKey.cleanCache -> viewModel.clearCache()
                        PreferKey.clearWebViewData -> viewModel.clearWebViewData()
                        else -> viewModel.shrinkDatabase()
                    }
                    pendingConfirmation = null
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { pendingConfirmation = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (showUserAgentDialog) {
        LegadoTextInputDialog(
            title = stringResource(R.string.user_agent), initialValue = AppConfig.userAgent.orEmpty(),
            hint = stringResource(R.string.user_agent), onDismiss = { showUserAgentDialog = false },
            onConfirm = { value -> if (value.isBlank()) context.removePref(PreferKey.userAgent) else context.putPrefString(PreferKey.userAgent, value); showUserAgentDialog = false; refreshTick++ },
        )
    }
    if (showCustomHostsDialog) {
        LegadoCodeInputDialog(
            title = stringResource(R.string.custom_hosts), initialValue = AppConfig.customHosts.orEmpty(),
            hint = stringResource(R.string.json_format), jsonSyntax = true,
            onDismiss = { showCustomHostsDialog = false },
            onConfirm = { value -> if (value.isJsonObject()) context.putPrefString(PreferKey.customHosts, value) else context.removePref(PreferKey.customHosts); showCustomHostsDialog = false; refreshTick++ },
        )
    }
    if (showVideoSettingsDialog) {
        VideoSettingsDialog(
            onDismiss = { showVideoSettingsDialog = false },
            onPressSpeedClick = {
                pendingNumber = NumberSetting(
                    VIDEO_PRESS_SPEED_KEY,
                    R.string.press_speed,
                    5,
                    60,
                    VideoPlay.longPressSpeed,
                )
            },
        )
    }
    if (showCheckSourceDialog) {
        CheckSourceSettingsDialog(
            onDismiss = { showCheckSourceDialog = false },
            onSaved = { refreshTick++ },
        )
    }
}

private data class NumberSetting(val key: String, val title: Int, val min: Int, val max: Int, val value: Int)

private fun promotedNotificationSettingsIntent(context: Context) =
    Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS).apply {
        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    }

private const val VIDEO_PRESS_SPEED_KEY = "videoPressSpeed"

@Composable
private fun VideoSettingsDialog(onDismiss: () -> Unit, onPressSpeedClick: () -> Unit) {
    var autoPlay by remember { mutableStateOf(VideoPlay.autoPlay) }
    var defaultFloatWindow by remember { mutableStateOf(VideoPlay.defaultFloatWindow) }
    var startFull by remember { mutableStateOf(VideoPlay.startFull) }
    var fullBottomProgress by remember { mutableStateOf(VideoPlay.fullBottomProgressBar) }
    val context = LocalContext.current
    val resources = LocalResources.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.config_settings)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ComposeCheckRow(stringResource(R.string.auto_play), autoPlay) {
                    autoPlay = it
                    VideoPlay.autoPlay = it
                }
                ComposeCheckRow(stringResource(R.string.default_float_window), defaultFloatWindow) {
                    defaultFloatWindow = it
                    VideoPlay.defaultFloatWindow = it
                }
                if (autoPlay) {
                    ComposeCheckRow(stringResource(R.string.start_full), startFull) {
                        startFull = it
                        VideoPlay.startFull = it
                    }
                }
                ComposeCheckRow(stringResource(R.string.full_bottom_progress), fullBottomProgress) {
                    fullBottomProgress = it
                    VideoPlay.fullBottomProgressBar = it
                }
                Text(
                    text = resources.getString(R.string.press_speed_summary, VideoPlay.longPressSpeed / 10.0f),
                    modifier = Modifier.fillMaxWidth().clickable(onClick = onPressSpeedClick).padding(vertical = 12.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) } },
    )
}

@Composable
private fun ComposeCheckRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Checkbox(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun CheckSourceSettingsDialog(onDismiss: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    var timeoutText by remember { mutableStateOf((CheckSource.timeout / 1000).toString()) }
    var checkDetail by remember { mutableStateOf(CheckSource.wSourceComment) }
    var checkDomain by remember { mutableStateOf(CheckSource.checkDomain) }
    var checkSearch by remember { mutableStateOf(CheckSource.checkSearch) }
    var checkDiscovery by remember { mutableStateOf(CheckSource.checkDiscovery) }
    var checkInfo by remember { mutableStateOf(CheckSource.checkInfo) }
    var checkCategory by remember { mutableStateOf(CheckSource.checkCategory) }
    var checkContent by remember { mutableStateOf(CheckSource.checkContent) }
    val infoEnabled = checkSearch || checkDiscovery
    val categoryEnabled = infoEnabled && checkInfo
    val contentEnabled = categoryEnabled && checkCategory

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.check_source_config)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                OutlinedTextField(
                    value = timeoutText,
                    onValueChange = { timeoutText = it.filter(Char::isDigit).take(9) },
                    label = { Text(stringResource(R.string.check_source_timeout)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )
                CheckSourceCheckRow(stringResource(R.string.source_check_detail), checkDetail, true) { checkDetail = it }
                Text(stringResource(R.string.check_source_item), modifier = Modifier.padding(top = 8.dp))
                CheckSourceCheckRow(stringResource(R.string.domain), checkDomain, true) { next ->
                    checkDomain = next
                    if (!checkSearch && !checkDiscovery && !next) checkSearch = true
                }
                CheckSourceCheckRow(stringResource(R.string.search), checkSearch, true) { next ->
                    checkSearch = next
                    if (!checkSearch && !checkDiscovery) {
                        checkInfo = false
                        checkCategory = false
                        checkContent = false
                        if (!checkDomain) checkDiscovery = true
                    }
                }
                CheckSourceCheckRow(stringResource(R.string.discovery), checkDiscovery, true) { next ->
                    checkDiscovery = next
                    if (!checkSearch && !checkDiscovery) {
                        checkInfo = false
                        checkCategory = false
                        checkContent = false
                        if (!checkDomain) checkSearch = true
                    }
                }
                CheckSourceCheckRow(stringResource(R.string.source_tab_info), checkInfo, infoEnabled) { next ->
                    checkInfo = next
                    if (!next) { checkCategory = false; checkContent = false }
                }
                CheckSourceCheckRow(stringResource(R.string.chapter_list), checkCategory, categoryEnabled) { next ->
                    checkCategory = next
                    if (!next) checkContent = false
                }
                CheckSourceCheckRow(stringResource(R.string.main_body), checkContent, contentEnabled) { checkContent = it }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val timeout = timeoutText.toLongOrNull()
                if (timeoutText.isBlank()) {
                    context.toastOnUi("${resources.getString(R.string.timeout)}${resources.getString(R.string.cannot_empty)}")
                    return@TextButton
                }
                if (timeout == null || timeout <= 0L) {
                    context.toastOnUi("${resources.getString(R.string.timeout)}${resources.getString(R.string.less_than)}0${resources.getString(R.string.seconds)}")
                    return@TextButton
                }
                CheckSource.timeout = timeout * 1000
                CheckSource.wSourceComment = checkDetail
                CheckSource.checkDomain = checkDomain
                CheckSource.checkSearch = checkSearch
                CheckSource.checkDiscovery = checkDiscovery
                CheckSource.checkInfo = checkInfo
                CheckSource.checkCategory = checkCategory
                CheckSource.checkContent = checkContent
                CheckSource.putConfig()
                context.putPrefString(PreferKey.checkSource, CheckSource.summary)
                onSaved()
                onDismiss()
            }) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun CheckSourceCheckRow(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, enabled = enabled, onCheckedChange = onChange)
        Spacer(Modifier.width(4.dp))
        Text(label)
    }
}

@Composable
private fun NumberSettingDialog(
    setting: NumberSetting,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var text by remember(setting) { mutableStateOf(setting.value.toString()) }
    val value = text.toLongOrNull()?.takeIf { it in setting.min.toLong()..setting.max.toLong() }?.toInt()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(setting.title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.filter(Char::isDigit).take(10) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                isError = value == null,
            )
        },
        confirmButton = {
            TextButton(enabled = value != null, onClick = { value?.let(onConfirm) }) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun OtherConfigContent(
    context: android.content.Context,
    refreshTick: Int,
    onOtherPreferenceClick: (String) -> Unit,
    onRefresh: () -> Unit,
    showLiveUpdate: Boolean,
) {
        val resources = LocalResources.current
        val autoRefresh by rememberPrefBoolean(PreferKey.autoRefresh, false)
        var showTokenDialog by remember { mutableStateOf(false) }

        val userAgentSummary = remember(refreshTick) { AppConfig.userAgent }
        val defaultBookTree = remember(refreshTick) { AppConfig.defaultBookTreeUri }
        val defaultBookTreeSummary = defaultBookTree ?: stringResource(R.string.book_tree_uri_s)
        val sourceEditMaxLineSummary = stringResource(R.string.source_edit_max_line_summary, AppConfig.sourceEditMaxLine.toString())
        val checkItemIds = listOfNotNull(
            R.string.domain.takeIf { CheckSource.checkDomain },
            R.string.search.takeIf { CheckSource.checkSearch },
            R.string.discovery.takeIf { CheckSource.checkDiscovery },
            R.string.source_tab_info.takeIf { CheckSource.checkInfo },
            R.string.chapter_list.takeIf { CheckSource.checkCategory },
            R.string.main_body.takeIf { CheckSource.checkContent },
        )
        val checkItems = checkItemIds.joinToString(separator = "", transform = { " " + resources.getString(it) })
        val checkSourceSummary = stringResource(R.string.check_source_config_summary, (CheckSource.timeout / 1000).toString(), checkItems)
        val bitmapCacheSummary = stringResource(R.string.bitmap_cache_size_summary, AppConfig.bitmapCacheSize.toString())
        val imageRetainSummary = stringResource(R.string.image_retain_number_summary, AppConfig.imageRetainNum.toString())
        val preDownloadSummary = stringResource(R.string.pre_download_s, AppConfig.preDownloadNum.toString())
        val threadCountSummary = stringResource(R.string.threads_num, AppConfig.threadCount.toString())
        val tokenValue = remember(refreshTick) { AppConfig.jsSourceApiToken.orEmpty() }
        val tokenSummary = stringResource(if (tokenValue.isBlank()) R.string.js_source_api_token_summary else R.string.js_source_api_token_configured)

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
                    onRefresh()
                }
            )
        }
    }
