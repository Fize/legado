package io.legado.app.ui.config

import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.data.repository.BackupSelectionOption
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.compose.component.LegadoDropdownMenuItem
import io.legado.app.ui.compose.component.LegadoOverflowMenuButton
import io.legado.app.ui.compose.component.LegadoTextInputDialog
import io.legado.app.ui.compose.component.LegadoTopAppBar
import io.legado.app.ui.compose.prefs.EditTextPreferenceItem
import io.legado.app.ui.compose.prefs.PreferenceCategoryHeader
import io.legado.app.ui.compose.prefs.PreferenceItem
import io.legado.app.ui.compose.prefs.SwitchPreferenceItem
import io.legado.app.ui.compose.prefs.rememberPrefInt
import io.legado.app.ui.compose.prefs.rememberPrefBoolean
import io.legado.app.ui.compose.prefs.rememberPrefString
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.qrcode.QrCodeResult
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.launch
import io.legado.app.utils.putPrefString
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.core.content.edit

@Composable
fun BackupSettingsScreen(
    onBack: () -> Unit,
    onOpenLog: () -> Unit,
    viewModel: BackupSettingsViewModel = viewModel(),
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showPathDialog by remember { mutableStateOf(false) }
    var showBackupTargetDialog by remember { mutableStateOf(false) }
    var showLanTransferDialog by remember { mutableStateOf(false) }
    var showAutoBackupDialog by remember { mutableStateOf(false) }
    var showPasswordDialog by remember { mutableStateOf(false) }
    var selectionDialog by remember { mutableStateOf<BackupSelectionDialog?>(null) }
    var selectedOptions by remember { mutableStateOf<List<BackupSelectionOption>>(emptyList()) }
    var pendingBackupUploadWebDav by remember { mutableStateOf(false) }

    val selectBackupPath = rememberLauncherForActivityResult(HandleFileContract()) { result ->
        result.uri?.let { uri ->
            context.putPrefString(PreferKey.backupPath, if (uri.scheme == "content") uri.toString() else uri.path.orEmpty())
        }
    }
    val reselectBackupPath = rememberLauncherForActivityResult(HandleFileContract()) { result ->
        result.uri?.let { uri ->
            val path = if (uri.scheme == "content") uri.toString() else uri.path.orEmpty()
            context.putPrefString(PreferKey.backupPath, path)
            viewModel.backup(path, pendingBackupUploadWebDav)
        }
    }
    val restoreDocument = rememberLauncherForActivityResult(HandleFileContract()) { result ->
        result.uri?.let(viewModel::restoreLocal)
    }
    val importOldData = rememberLauncherForActivityResult(HandleFileContract()) { result ->
        result.uri?.let(viewModel::importLegacy)
    }
    val lanQr = rememberLauncherForActivityResult(QrCodeResult()) { result ->
        result?.let(viewModel::previewLanReceive)
    }

    val autoBackup by rememberPrefBoolean(PreferKey.autoBackup, true)
    val syncBookProgress by rememberPrefBoolean(PreferKey.syncBookProgress, true)
    val webDavUrl by rememberPrefString(PreferKey.webDavUrl, "")
    val webDavAccount by rememberPrefString(PreferKey.webDavAccount, "")
    val webDavPassword by rememberPrefString(PreferKey.webDavPassword, "")
    val webDavDir by rememberPrefString(PreferKey.webDavDir, AppConfig.webDavDir ?: "legado")
    val webDavDeviceName by rememberPrefString(PreferKey.webDavDeviceName, AppConfig.webDavDeviceName.orEmpty())
    val backupPath by rememberPrefString(PreferKey.backupPath, "")
    val autoBackupWebDav by rememberPrefBoolean(PreferKey.autoBackupWebDav, true)
    val autoBackupIntervalDays by rememberPrefInt(PreferKey.autoBackupIntervalDays, 1)
    val autoBackupSummary = if (autoBackup) stringResource(R.string.auto_backup_destination_summary,
        stringResource(if (autoBackupWebDav) R.string.backup_local_webdav else R.string.backup_local_only),
        autoBackupIntervalDays.coerceAtLeast(1)) else stringResource(R.string.auto_backup_disabled)
    val backupPathSummary = backupPathSummary(backupPath, viewModel.defaultBackupPath, stringResource(R.string.default_path))

    fun startBackup(uploadWebDav: Boolean) {
        val path = backupPath.takeIf(String::isNotBlank)
        if (path == null) {
            viewModel.backup(null, uploadWebDav)
            return
        }
        scope.launch {
            val canWrite = withContext(Dispatchers.IO) { viewModel.canWriteBackupPath(path) }
            if (canWrite) {
                viewModel.backup(path, uploadWebDav)
            } else {
                pendingBackupUploadWebDav = uploadWebDav
                reselectBackupPath.launch {
                    title = resources.getString(R.string.select_folder)
                    mode = HandleFileContract.DIR_SYS
                }
            }
        }
    }

    DisposableEffect(context) {
        val preferences = context.defaultSharedPreferences
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key in setOf(PreferKey.webDavUrl, PreferKey.webDavAccount, PreferKey.webDavPassword, PreferKey.webDavDir)) {
                viewModel.updateWebDavConfig()
            }
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    val noticeText = state.notice?.let { it.text ?: stringResource(it.resourceId ?: R.string.error) }
    LaunchedEffect(state.notice, noticeText) {
        noticeText?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeNotice()
        }
    }
    LaunchedEffect(state.lanQr?.session) {
        val session = state.lanQr?.session ?: return@LaunchedEffect
        delay((session.descriptor.expiresAt - System.currentTimeMillis()).coerceAtLeast(0L))
        viewModel.closeLanQr()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            LegadoTopAppBar(
                title = stringResource(R.string.backup_restore),
                onBack = onBack,
                actions = {
                    LegadoOverflowMenuButton { dismiss ->
                        LegadoDropdownMenuItem(
                            text = stringResource(R.string.menu_import_old_version),
                            onClick = { dismiss(); importOldData.launch { mode = HandleFileContract.DIR } },
                        )
                        LegadoDropdownMenuItem(
                            text = stringResource(R.string.log),
                            onClick = { dismiss(); onOpenLog() },
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        BackupSettingsContent(
            modifier = Modifier.fillMaxSize().padding(padding),
            syncBookProgress = syncBookProgress,
            webDavUrl = webDavUrl,
            webDavAccount = webDavAccount,
            webDavPassword = webDavPassword,
            webDavDir = webDavDir,
            webDavDeviceName = webDavDeviceName,
            backupPathSummary = backupPathSummary,
            autoBackupSummary = autoBackupSummary,
            onSelectPath = { showPathDialog = true },
            onSelectContent = {
                selectedOptions = viewModel.contentOptions()
                selectionDialog = BackupSelectionDialog.Content
            },
            onSelectIgnore = {
                selectedOptions = viewModel.ignoreOptions()
                selectionDialog = BackupSelectionDialog.Ignore
            },
            onBackup = { showBackupTargetDialog = true },
            onRestore = { viewModel.loadRemoteBackups() },
            onRestoreLocal = {
                restoreDocument.launch {
                    title = resources.getString(R.string.select_restore_file)
                    mode = HandleFileContract.FILE
                    allowExtensions = arrayOf("zip")
                }
            },
            onLanTransfer = { showLanTransferDialog = true },
            onAutoBackup = { showAutoBackupDialog = true },
            onPassword = { showPasswordDialog = true },
        )
    }

    if (showPathDialog) {
        AlertDialog(
            onDismissRequest = { showPathDialog = false },
            title = { Text(stringResource(R.string.backup_path)) },
            text = { Text(backupPathSummary) },
            confirmButton = {
                TextButton(onClick = {
                    showPathDialog = false
                    selectBackupPath.launch { title = resources.getString(R.string.select_folder); mode = HandleFileContract.DIR_SYS }
                }) { Text(stringResource(R.string.select_folder)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showPathDialog = false
                    context.defaultSharedPreferences.edit().remove(PreferKey.backupPath).apply()
                }) { Text(stringResource(R.string.default_path)) }
            },
        )
    }

    if (showBackupTargetDialog) {
        AlertDialog(
            onDismissRequest = { showBackupTargetDialog = false },
            title = { Text(stringResource(R.string.backup)) },
            text = { Text(stringResource(R.string.backup_destination_summary)) },
            confirmButton = {
                TextButton(onClick = { showBackupTargetDialog = false; startBackup(true) }) {
                    Text(stringResource(R.string.backup_local_webdav))
                }
            },
            dismissButton = {
                TextButton(onClick = { showBackupTargetDialog = false; startBackup(false) }) {
                    Text(stringResource(R.string.backup_local_only))
                }
            },
        )
    }

    if (showLanTransferDialog) {
        AlertDialog(
            onDismissRequest = { showLanTransferDialog = false },
            title = { Text(stringResource(R.string.lan_backup_transfer)) },
            text = { Text(stringResource(R.string.lan_backup_transfer_summary)) },
            confirmButton = {
                TextButton(onClick = { showLanTransferDialog = false; viewModel.prepareLanSend() }) {
                    Text(stringResource(R.string.lan_backup_send))
                }
            },
            dismissButton = {
                TextButton(onClick = { showLanTransferDialog = false; lanQr.launch(Unit) }) {
                    Text(stringResource(R.string.lan_backup_receive))
                }
            },
        )
    }

    if (showAutoBackupDialog) {
        AutoBackupDialog(onDismiss = { showAutoBackupDialog = false })
    }
    if (showPasswordDialog) {
        LegadoTextInputDialog(
            title = stringResource(R.string.set_local_password),
            message = stringResource(R.string.set_local_password_summary),
            initialValue = viewModel.localPassword(),
            hint = "password",
            isPassword = true,
            onDismiss = { showPasswordDialog = false },
            onConfirm = { viewModel.saveLocalPassword(it); showPasswordDialog = false },
        )
    }
    selectionDialog?.let { type ->
        SelectionDialog(
            title = stringResource(if (type == BackupSelectionDialog.Content) R.string.backup_content else R.string.restore_ignore),
            options = selectedOptions,
            onToggle = { key -> selectedOptions = selectedOptions.map { if (it.key == key) it.copy(enabled = !it.enabled) else it } },
            onDismiss = { selectionDialog = null },
            onConfirm = {
                if (type == BackupSelectionDialog.Content) viewModel.saveContentOptions(selectedOptions)
                else viewModel.saveIgnoreOptions(selectedOptions)
                selectionDialog = null
            },
        )
    }
    state.remoteBackupNames?.let { names ->
        AlertDialog(
            onDismissRequest = viewModel::closeRemoteBackupDialog,
            title = { Text(stringResource(R.string.select_restore_file)) },
            text = {
                LazyColumn(modifier = Modifier.widthIn(max = 520.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
                    items(names) { name ->
                        Text(name, modifier = Modifier.fillMaxWidth().clickable { viewModel.restoreRemote(name) }.padding(12.dp))
                    }
                    if (names.isEmpty()) item { Text(stringResource(R.string.empty)) }
                }
            },
            confirmButton = { TextButton(onClick = viewModel::closeRemoteBackupDialog) { Text(stringResource(R.string.cancel)) } },
        )
    }
    state.receivePreview?.let { preview ->
        AlertDialog(
            onDismissRequest = viewModel::closeLanReceiveDialog,
            title = { Text(stringResource(R.string.lan_backup_receive)) },
            text = {
                Text(resources.getString(
                    R.string.lan_backup_receive_confirm,
                    Formatter.formatFileSize(context, preview.size),
                    preview.deviceName.ifBlank { stringResource(R.string.lan_backup_receive) },
                ))
            },
            confirmButton = { TextButton(onClick = viewModel::confirmLanReceive) { Text(stringResource(R.string.ok)) } },
            dismissButton = { TextButton(onClick = viewModel::closeLanReceiveDialog) { Text(stringResource(R.string.cancel)) } },
        )
    }
    state.lanQr?.let { qr ->
        AlertDialog(
            onDismissRequest = viewModel::closeLanQr,
            title = { Text(stringResource(R.string.lan_backup_send)) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    androidx.compose.foundation.Image(
                        bitmap = qr.bitmap.asImageBitmap(),
                        contentDescription = stringResource(R.string.lan_backup_waiting),
                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                    )
                    Text(stringResource(R.string.lan_backup_waiting))
                }
            },
            confirmButton = { TextButton(onClick = viewModel::closeLanQr) { Text(stringResource(R.string.cancel)) } },
        )
    }
    state.busyMessage?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::cancelOperation,
            title = { Text(stringResource(message)) },
            text = { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                CircularProgressIndicator()
                Text(stringResource(R.string.loading))
            } },
            confirmButton = { TextButton(onClick = viewModel::cancelOperation) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun BackupSettingsContent(
    modifier: Modifier,
    syncBookProgress: Boolean,
    webDavUrl: String,
    webDavAccount: String,
    webDavPassword: String,
    webDavDir: String,
    webDavDeviceName: String,
    backupPathSummary: String,
    autoBackupSummary: String,
    onSelectPath: () -> Unit,
    onSelectContent: () -> Unit,
    onSelectIgnore: () -> Unit,
    onBackup: () -> Unit,
    onRestore: () -> Unit,
    onRestoreLocal: () -> Unit,
    onLanTransfer: () -> Unit,
    onAutoBackup: () -> Unit,
    onPassword: () -> Unit,
) {
    LazyColumn(modifier = modifier, contentPadding = PaddingValues(vertical = 8.dp)) {
        item("webDavCategory") { PreferenceCategoryHeader(title = stringResource(R.string.web_dav_set)) }
        item(PreferKey.webDavUrl) { EditTextPreferenceItem(PreferKey.webDavUrl, title = stringResource(R.string.web_dav_url), summary = webDavUrl.ifBlank { stringResource(R.string.web_dav_url_s) }) }
        item(PreferKey.webDavAccount) { EditTextPreferenceItem(PreferKey.webDavAccount, title = stringResource(R.string.web_dav_account), summary = webDavAccount.ifBlank { stringResource(R.string.web_dav_account_s) }) }
        item(PreferKey.webDavPassword) { EditTextPreferenceItem(PreferKey.webDavPassword, title = stringResource(R.string.web_dav_pw), summary = if (webDavPassword.isBlank()) stringResource(R.string.web_dav_pw_s) else "*".repeat(webDavPassword.length), isPassword = true) }
        item(PreferKey.webDavDir) { EditTextPreferenceItem(PreferKey.webDavDir, default = "legado", title = stringResource(R.string.sub_dir), summary = webDavDir) }
        item(PreferKey.webDavDeviceName) { EditTextPreferenceItem(PreferKey.webDavDeviceName, title = stringResource(R.string.webdav_device_name), summary = webDavDeviceName) }
        item(PreferKey.webDavBookAutoRestore) { SwitchPreferenceItem(PreferKey.webDavBookAutoRestore, false, title = stringResource(R.string.webdav_book_auto_restore_t), summary = stringResource(R.string.webdav_book_auto_restore_s)) }
        item(PreferKey.syncBookProgress) { SwitchPreferenceItem(PreferKey.syncBookProgress, true, title = stringResource(R.string.sync_book_progress_t), summary = stringResource(R.string.sync_book_progress_s)) }
        item(PreferKey.syncBookProgressPlus) { SwitchPreferenceItem(PreferKey.syncBookProgressPlus, false, title = stringResource(R.string.sync_book_progress_plus_t), summary = stringResource(R.string.sync_book_progress_plus_s), enabled = syncBookProgress) }
        item("backupRestoreCategory") { PreferenceCategoryHeader(title = stringResource(R.string.backup_restore)) }
        item("localPassword") { PreferenceItem(title = stringResource(R.string.set_local_password), summary = stringResource(R.string.set_local_password_summary), onClick = onPassword) }
        item(PreferKey.backupPath) { PreferenceItem(title = stringResource(R.string.backup_path), summary = backupPathSummary, onClick = onSelectPath) }
        item(PreferKey.backupContent) { PreferenceItem(title = stringResource(R.string.backup_content), summary = stringResource(R.string.backup_content_summary), onClick = onSelectContent) }
        item("lan_backup_transfer") { PreferenceItem(title = stringResource(R.string.lan_backup_transfer), summary = stringResource(R.string.lan_backup_transfer_summary), onClick = onLanTransfer) }
        item("web_dav_backup") { PreferenceItem(title = stringResource(R.string.backup), summary = stringResource(R.string.backup_destination_summary), onClick = onBackup) }
        item("web_dav_restore") { PreferenceItem(title = stringResource(R.string.restore), summary = stringResource(R.string.restore_summary), onLongClick = onRestoreLocal, onClick = onRestore) }
        item(PreferKey.restoreIgnore) { PreferenceItem(title = stringResource(R.string.restore_ignore), summary = stringResource(R.string.restore_ignore_summary), onClick = onSelectIgnore) }
        item(PreferKey.onlyLatestBackup) { SwitchPreferenceItem(PreferKey.onlyLatestBackup, true, title = stringResource(R.string.only_latest_backup_t), summary = stringResource(R.string.only_latest_backup_s)) }
        item(PreferKey.autoBackup) { PreferenceItem(title = stringResource(R.string.auto_backup_t), summary = autoBackupSummary, onClick = onAutoBackup) }
        item(PreferKey.autoCheckNewBackup) { SwitchPreferenceItem(PreferKey.autoCheckNewBackup, true, title = stringResource(R.string.auto_check_new_backup_t), summary = stringResource(R.string.auto_check_new_backup_s)) }
    }
}

@Composable
private fun AutoBackupDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(AppConfig.autoBackup) }
    var uploadWebDav by remember { mutableStateOf(AppConfig.autoBackupWebDav) }
    var daysText by remember { mutableStateOf(AppConfig.autoBackupIntervalDays.toString()) }
    var hasError by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.auto_backup_t)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth().clickable { enabled = !enabled }, horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.auto_backup_t)); Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
                Row(Modifier.fillMaxWidth().clickable { uploadWebDav = false }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = !uploadWebDav, onClick = { uploadWebDav = false }); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.backup_local_only))
                }
                Row(Modifier.fillMaxWidth().clickable { uploadWebDav = true }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = uploadWebDav, onClick = { uploadWebDav = true }); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.backup_local_webdav))
                }
                OutlinedTextField(
                    value = daysText,
                    onValueChange = { daysText = it.filter(Char::isDigit).take(4); hasError = false },
                    label = { Text(stringResource(R.string.auto_backup_interval_days)) },
                    isError = hasError,
                    supportingText = if (hasError) ({ Text(stringResource(R.string.auto_backup_interval_invalid)) }) else null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val days = daysText.toIntOrNull()
                if (days == null || days < 1) { hasError = true; return@TextButton }
                context.defaultSharedPreferences.edit()
                    .putBoolean(PreferKey.autoBackup, enabled)
                    .putBoolean(PreferKey.autoBackupWebDav, uploadWebDav)
                    .putInt(PreferKey.autoBackupIntervalDays, days)
                    .apply()
                onDismiss()
            }) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun SelectionDialog(
    title: String,
    options: List<BackupSelectionOption>,
    onToggle: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val labels = options.associate { it.key to stringResource(it.titleId) }
    val confirmLabel = stringResource(R.string.ok)
    val cancelLabel = stringResource(R.string.cancel)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(modifier = Modifier.widthIn(max = 520.dp)) {
                items(options, key = { it.key }) { option ->
                    Row(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = option.enabled, role = Role.Checkbox, onValueChange = { onToggle(option.key) }).padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = option.enabled, onCheckedChange = null)
                        Spacer(Modifier.width(8.dp)); Text(labels.getValue(option.key))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(cancelLabel) } },
    )
}

private enum class BackupSelectionDialog { Content, Ignore }
