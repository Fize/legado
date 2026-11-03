package io.legado.app.ui.config

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import io.legado.app.R
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.model.BookCover
import io.legado.app.ui.compose.component.LegadoTopAppBar
import io.legado.app.ui.compose.prefs.PreferenceCategoryHeader
import io.legado.app.ui.compose.prefs.PreferenceItem
import io.legado.app.ui.compose.prefs.SwitchPreferenceItem
import io.legado.app.ui.compose.prefs.rememberPrefBoolean
import io.legado.app.ui.compose.prefs.rememberPrefString
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.utils.FileUtils
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.externalFiles
import io.legado.app.utils.getPrefString
import io.legado.app.utils.inputStream
import io.legado.app.utils.postEvent
import io.legado.app.utils.putPrefString
import io.legado.app.utils.removePref
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileOutputStream

private val coverImageKeys = setOf(
    PreferKey.defaultCover,
    PreferKey.defaultCoverDark,
    PreferKey.readRecordCover,
    PreferKey.readRecordCoverDark,
)

@Composable
fun CoverSettingsScreen(
    onBack: () -> Unit,
    onOpenFontSettings: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedImageKey by remember { mutableStateOf<String?>(null) }
    var showImageActions by remember { mutableStateOf(false) }
    var showCoverRule by remember { mutableStateOf(false) }
    var savingImage by remember { mutableStateOf(false) }
    val coverShowName by rememberPrefBoolean(PreferKey.coverShowName, true)
    val coverShowNameNight by rememberPrefBoolean(PreferKey.coverShowNameN, true)
    val defaultCover by rememberPrefString(PreferKey.defaultCover, "")
    val defaultCoverNight by rememberPrefString(PreferKey.defaultCoverDark, "")
    val readRecordCover by rememberPrefString(PreferKey.readRecordCover, "")
    val readRecordCoverNight by rememberPrefString(PreferKey.readRecordCoverDark, "")
    val selectImage = stringResource(R.string.select_image)

    val imagePicker = rememberLauncherForActivityResult(HandleFileContract()) { result ->
        val key = result.value?.takeIf { it in coverImageKeys }
        val uri = result.uri
        if (key != null && uri != null) {
            scope.launch {
                savingImage = true
                runCatching { saveCoverImage(context, key, uri) }
                    .onSuccess {
                        BookCover.upDefaultCover()
                        postEvent(EventBus.BOOKSHELF_REFRESH, "")
                    }
                    .onFailure { context.toastOnUi(it.localizedMessage) }
                savingImage = false
            }
        }
    }

    DisposableEffect(context) {
        val preferences = context.defaultSharedPreferences
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key in setOf(
                    PreferKey.useDefaultCover,
                    PreferKey.coverShowName,
                    PreferKey.coverShowNameN,
                    PreferKey.coverShowAuthor,
                    PreferKey.coverShowAuthorN,
                    PreferKey.defaultCover,
                    PreferKey.defaultCoverDark,
                    PreferKey.readRecordCover,
                    PreferKey.readRecordCoverDark,
                )
            ) {
                BookCover.upDefaultCover()
                postEvent(EventBus.BOOKSHELF_REFRESH, "")
            }
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    Scaffold(
        topBar = {
            LegadoTopAppBar(
                title = stringResource(R.string.cover_config),
                onBack = onBack,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            item(key = PreferKey.loadCoverOnlyWifi) {
                SwitchPreferenceItem(
                    key = PreferKey.loadCoverOnlyWifi,
                    default = false,
                    title = stringResource(R.string.only_wifi),
                    summary = stringResource(R.string.only_wifi_summary),
                )
            }
            item(key = "coverRule") {
                PreferenceItem(
                    title = stringResource(R.string.cover_rule),
                    summary = stringResource(R.string.cover_rule_summary),
                    onClick = { showCoverRule = true },
                )
            }
            item(key = PreferKey.useDefaultCover) {
                SwitchPreferenceItem(
                    key = PreferKey.useDefaultCover,
                    default = false,
                    title = stringResource(R.string.use_default_cover),
                    summary = stringResource(R.string.use_default_cover_s),
                )
            }
            item(key = "coverFontSettings") {
                PreferenceItem(
                    title = stringResource(R.string.cover_font_config),
                    onClick = onOpenFontSettings,
                )
            }
            item(key = "dayThemeCategory") {
                PreferenceCategoryHeader(title = stringResource(R.string.day))
            }
            item(key = PreferKey.defaultCover) {
                CoverImagePreference(
                    summary = defaultCover.ifBlank { selectImage },
                    enabled = !savingImage,
                    onClick = {
                        openCoverImage(defaultCover, PreferKey.defaultCover, imagePicker) {
                            selectedImageKey = it
                            showImageActions = true
                        }
                    },
                )
            }
            item(key = PreferKey.coverShowName) {
                SwitchPreferenceItem(
                    key = PreferKey.coverShowName,
                    default = true,
                    title = stringResource(R.string.cover_show_name),
                    summary = stringResource(R.string.cover_show_name_summary),
                )
            }
            item(key = PreferKey.coverShowAuthor) {
                SwitchPreferenceItem(
                    key = PreferKey.coverShowAuthor,
                    default = true,
                    title = stringResource(R.string.cover_show_author),
                    summary = stringResource(R.string.cover_show_author_summary),
                    enabled = coverShowName,
                )
            }
            item(key = "nightThemeCategory") {
                PreferenceCategoryHeader(title = stringResource(R.string.night))
            }
            item(key = PreferKey.defaultCoverDark) {
                CoverImagePreference(
                    summary = defaultCoverNight.ifBlank { selectImage },
                    enabled = !savingImage,
                    onClick = {
                        openCoverImage(defaultCoverNight, PreferKey.defaultCoverDark, imagePicker) {
                            selectedImageKey = it
                            showImageActions = true
                        }
                    },
                )
            }
            item(key = PreferKey.coverShowNameN) {
                SwitchPreferenceItem(
                    key = PreferKey.coverShowNameN,
                    default = true,
                    title = stringResource(R.string.cover_show_name),
                    summary = stringResource(R.string.cover_show_name_summary),
                )
            }
            item(key = PreferKey.coverShowAuthorN) {
                SwitchPreferenceItem(
                    key = PreferKey.coverShowAuthorN,
                    default = true,
                    title = stringResource(R.string.cover_show_author),
                    summary = stringResource(R.string.cover_show_author_summary),
                    enabled = coverShowNameNight,
                )
            }
            item(key = "readRecordCoverCategory") {
                PreferenceCategoryHeader(title = stringResource(R.string.read_record_covers))
            }
            item(key = PreferKey.readRecordCover) {
                CoverImagePreference(
                    title = stringResource(R.string.read_record_cover_day),
                    summary = readRecordCover.ifBlank { selectImage },
                    enabled = !savingImage,
                    onClick = {
                        openCoverImage(readRecordCover, PreferKey.readRecordCover, imagePicker) {
                            selectedImageKey = it
                            showImageActions = true
                        }
                    },
                )
            }
            item(key = PreferKey.readRecordCoverDark) {
                CoverImagePreference(
                    title = stringResource(R.string.read_record_cover_night),
                    summary = readRecordCoverNight.ifBlank { selectImage },
                    enabled = !savingImage,
                    onClick = {
                        openCoverImage(readRecordCoverNight, PreferKey.readRecordCoverDark, imagePicker) {
                            selectedImageKey = it
                            showImageActions = true
                        }
                    },
                )
            }
        }
    }

    if (showImageActions) {
        val key = selectedImageKey
        AlertDialog(
            onDismissRequest = { showImageActions = false },
            title = { Text(stringResource(R.string.background_image)) },
            confirmButton = {
                TextButton(onClick = {
                    showImageActions = false
                    if (key != null) launchCoverImagePicker(imagePicker, key)
                }) { Text(stringResource(R.string.select_image)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showImageActions = false
                    if (key != null) {
                        context.removePref(key)
                        BookCover.upDefaultCover()
                        postEvent(EventBus.BOOKSHELF_REFRESH, "")
                    }
                }) { Text(stringResource(R.string.delete)) }
            },
        )
    }

    if (showCoverRule) {
        CoverRuleEditorDialog(
            onDismiss = { showCoverRule = false },
            onSave = { rule ->
                BookCover.saveCoverRule(rule)
                showCoverRule = false
            },
            onReset = {
                BookCover.delCoverRule()
                showCoverRule = false
            },
        )
    }
}

@Composable
private fun CoverImagePreference(
    summary: String,
    enabled: Boolean,
    title: String = stringResource(R.string.default_cover),
    onClick: () -> Unit,
) {
    PreferenceItem(title = title, summary = summary, enabled = enabled, onClick = onClick)
}

private fun openCoverImage(
    currentPath: String,
    key: String,
    launcher: androidx.activity.compose.ManagedActivityResultLauncher<(HandleFileContract.HandleFileParam.() -> Unit)?, HandleFileContract.Result>,
    onAskActions: (String) -> Unit,
) {
    if (currentPath.isBlank()) launchCoverImagePicker(launcher, key) else onAskActions(key)
}

private fun launchCoverImagePicker(
    launcher: androidx.activity.compose.ManagedActivityResultLauncher<(HandleFileContract.HandleFileParam.() -> Unit)?, HandleFileContract.Result>,
    key: String,
) {
    launcher.launch {
        value = key
        mode = HandleFileContract.IMAGE
    }
}

@Composable
private fun CoverRuleEditorDialog(
    onDismiss: () -> Unit,
    onSave: (BookCover.CoverRule) -> Unit,
    onReset: () -> Unit,
) {
    var rule by remember { mutableStateOf(BookCover.CoverRule(searchUrl = "", coverRule = "")) }
    var loaded by remember { mutableStateOf(false) }
    var validationError by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        rule = withContext(Dispatchers.IO) { BookCover.getCoverRule() }
        loaded = true
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.cover_config)) },
        text = {
            androidx.compose.foundation.layout.Column {
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { rule = rule.copy(enable = !rule.enable) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = rule.enable, onCheckedChange = { rule = rule.copy(enable = it) })
                    Text(stringResource(R.string.enable))
                }
                OutlinedTextField(
                    value = rule.searchUrl,
                    onValueChange = { rule = rule.copy(searchUrl = it); validationError = false },
                    label = { Text(stringResource(R.string.r_search_url)) },
                    enabled = loaded,
                )
                OutlinedTextField(
                    value = rule.coverRule,
                    onValueChange = { rule = rule.copy(coverRule = it); validationError = false },
                    label = { Text(stringResource(R.string.rule_cover_url)) },
                    enabled = loaded,
                )
                if (validationError) Text("搜索url和cover规则不能为空")
            }
        },
        confirmButton = {
            TextButton(enabled = loaded, onClick = {
                if (rule.searchUrl.isBlank() || rule.coverRule.isBlank()) validationError = true
                else onSave(rule)
            }) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = {
            androidx.compose.foundation.layout.Row {
                TextButton(onClick = onReset) { Text(stringResource(R.string.btn_default_s)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}

private suspend fun saveCoverImage(context: Context, key: String, uri: Uri) = withContext(Dispatchers.IO) {
    val displayName = DocumentFile.fromSingleUri(context, uri)?.name ?: uri.lastPathSegment ?: "cover.jpg"
    val suffix = if (displayName.contains(".9.png", true)) {
        ".9.png"
    } else {
        ".${displayName.substringAfterLast('.', "jpg")}"
    }
    val fileName = uri.inputStream(context).getOrThrow().use { MD5Utils.md5Encode(it) + suffix }
    val destination = FileUtils.createFileIfNotExist(context.externalFiles, "covers", fileName)
    uri.inputStream(context).getOrThrow().use { input -> FileOutputStream(destination).use(input::copyTo) }
    context.putPrefString(key, destination.absolutePath)
}
