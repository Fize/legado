package io.legado.app.ui.config

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.text.format.Formatter
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog as ComposeAlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.AppWebDav
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.storage.Backup
import io.legado.app.help.storage.BackupConfig
import io.legado.app.help.storage.ImportOldData
import io.legado.app.help.storage.LanBackupSession
import io.legado.app.help.storage.LanBackupTransfer
import io.legado.app.help.storage.Restore
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.permission.Permissions
import io.legado.app.lib.permission.PermissionsCompat
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.compose.component.LegadoTextInputDialog
import io.legado.app.ui.compose.component.combinedClickableCompat
import io.legado.app.ui.compose.prefs.EditTextPreferenceItem
import io.legado.app.ui.compose.prefs.PreferenceCategoryHeader
import io.legado.app.ui.compose.prefs.PreferenceItem
import io.legado.app.ui.compose.prefs.SwitchPreferenceItem
import io.legado.app.ui.compose.prefs.rememberPrefBoolean
import io.legado.app.ui.compose.prefs.rememberPrefString
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.qrcode.QrCodeResult
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.dialog.WaitDialog
import io.legado.app.utils.FileDoc
import io.legado.app.utils.QRCodeUtils
import io.legado.app.utils.checkWrite
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.dpToPx
import io.legado.app.utils.externalFiles
import io.legado.app.utils.getPrefString
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.launch
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.init.appCtx

class BackupConfigFragment : Fragment(),
    SharedPreferences.OnSharedPreferenceChangeListener {

    private val viewModel by activityViewModels<ConfigViewModel>()
    private val waitDialog by lazy { WaitDialog(requireContext()) }
    private var refreshTick by mutableIntStateOf(0)
    private var showAutoBackupDialog by mutableStateOf(false)
    private var showLocalPasswordDialog by mutableStateOf(false)
    private var backupJob: Job? = null
    private var manualBackupUploadWebDav = true
    private var restoreJob: Job? = null
    private var lanBackupJob: Job? = null
    private var lanBackupSession: LanBackupSession? = null
    private var lanBackupDialog: AlertDialog? = null

    private val selectBackupPath = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            if (uri.isContentScheme()) {
                AppConfig.backupPath = uri.toString()
            } else {
                AppConfig.backupPath = uri.path
            }
            refreshTick++
        }
    }
    private val backupDir = registerForActivityResult(HandleFileContract()) { result ->
        result.uri?.let { uri ->
            if (uri.isContentScheme()) {
                AppConfig.backupPath = uri.toString()
                backup(uri.toString(), manualBackupUploadWebDav)
            } else {
                uri.path?.let { path ->
                    AppConfig.backupPath = path
                    backup(path, manualBackupUploadWebDav)
                }
            }
            refreshTick++
        }
    }
    private val restoreDoc = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            waitDialog.setText("恢复中…")
            waitDialog.show()
            val task = Coroutine.async {
                Restore.restore(appCtx, uri)
            }.onFinally {
                waitDialog.dismiss()
            }
            waitDialog.setOnCancelListener {
                task.cancel()
            }
        }
    }
    private val restoreOld = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            ImportOldData.importUri(appCtx, uri)
        }
    }
    private val lanBackupQr = registerForActivityResult(QrCodeResult()) {
        it?.let(::confirmLanBackupReceive)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        manualBackupUploadWebDav = savedInstanceState?.getBoolean("manualBackupUploadWebDav", true) ?: true
        requireContext().defaultSharedPreferences.registerOnSharedPreferenceChangeListener(this)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("manualBackupUploadWebDav", manualBackupUploadWebDav)
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
                    BackupConfigScreen()
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        activity?.setTitle(R.string.backup_restore)
    }

    fun launchImportOld() {
        restoreOld.launch()
    }

    fun showAppLog() {
        showDialogFragment<AppLogDialog>()
    }

    override fun onDestroy() {
        super.onDestroy()
        requireContext().defaultSharedPreferences.unregisterOnSharedPreferenceChangeListener(this)
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        refreshTick++
        when (key) {
            PreferKey.autoBackup, PreferKey.autoBackupWebDav, PreferKey.autoBackupIntervalDays -> updateAutoBackupSummary()
            PreferKey.backupPath -> upPreferenceSummary(key, getPrefString(key))
            PreferKey.webDavUrl,
            PreferKey.webDavAccount,
            PreferKey.webDavPassword,
            PreferKey.webDavDir -> {
                upPreferenceSummary(key, appCtx.getPrefString(key))
                viewModel.upWebDavConfig()
            }

            PreferKey.webDavDeviceName -> upPreferenceSummary(key, getPrefString(key))
        }
    }

    private fun upPreferenceSummary(preferenceKey: String, value: String?): String? {
        return when (preferenceKey) {
            PreferKey.backupPath ->
                value?.takeIf { it.isNotBlank() } ?: defaultBackupPathSummary()

            PreferKey.webDavUrl ->
                if (value.isNullOrBlank()) {
                    getString(R.string.web_dav_url_s)
                } else {
                    value
                }

            PreferKey.webDavAccount ->
                if (value.isNullOrBlank()) {
                    getString(R.string.web_dav_account_s)
                } else {
                    value
                }

            PreferKey.webDavPassword ->
                if (value.isNullOrEmpty()) {
                    getString(R.string.web_dav_pw_s)
                } else {
                    "*".repeat(value.length)
                }

            PreferKey.webDavDir -> when (value) {
                null -> "legado"
                else -> value
            }

            else -> value
        }
    }

    private data class PreferenceKeyRef(val key: String)

    private fun onPreferenceTreeClick(preference: PreferenceKeyRef): Boolean {
        when (preference.key) {
            PreferKey.backupPath -> showBackupPathSelector()
            PreferKey.backupContent -> backupContent()
            PreferKey.restoreIgnore -> backupIgnore()
            "web_dav_backup" -> backup()
            "localPassword" -> alertLocalPassword()
            PreferKey.autoBackup -> configureAutoBackup()
            "web_dav_restore" -> restore()
            "lan_backup_transfer" -> lanBackupTransfer()
        }
        return true
    }

    private fun defaultBackupPathSummary() =
        "${getString(R.string.default_path)}\n${requireContext().externalFiles.absolutePath}"

    private fun updateAutoBackupSummary(): String {
        return if (AppConfig.autoBackup) {
            getString(
                R.string.auto_backup_destination_summary,
                getString(if (AppConfig.autoBackupWebDav) R.string.backup_local_webdav else R.string.backup_local_only),
                AppConfig.autoBackupIntervalDays
            )
        } else getString(R.string.auto_backup_disabled)
    }

    private fun configureAutoBackup() {
        showAutoBackupDialog = true
    }

    @Composable
    private fun BackupConfigScreen() {
        val syncBookProgress by rememberPrefBoolean(PreferKey.syncBookProgress, true)
        val webDavUrl by rememberPrefString(PreferKey.webDavUrl, "")
        val webDavAccount by rememberPrefString(PreferKey.webDavAccount, "")
        val webDavPassword by rememberPrefString(PreferKey.webDavPassword, "")
        val webDavDir by rememberPrefString(PreferKey.webDavDir, AppConfig.webDavDir ?: "legado")
        val webDavDeviceName by rememberPrefString(
            PreferKey.webDavDeviceName,
            AppConfig.webDavDeviceName.orEmpty()
        )
        val backupPath by rememberPrefString(PreferKey.backupPath, "")
        val backupPathSummary = remember(refreshTick, backupPath) {
            upPreferenceSummary(PreferKey.backupPath, backupPath)
        }
        val autoBackupSummary = remember(refreshTick) {
            updateAutoBackupSummary()
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            item(key = "webDavCategory") {
                PreferenceCategoryHeader(title = stringResource(R.string.web_dav_set))
            }
            item(key = PreferKey.webDavUrl) {
                EditTextPreferenceItem(
                    key = PreferKey.webDavUrl,
                    title = stringResource(R.string.web_dav_url),
                    summary = upPreferenceSummary(PreferKey.webDavUrl, webDavUrl),
                )
            }
            item(key = PreferKey.webDavAccount) {
                EditTextPreferenceItem(
                    key = PreferKey.webDavAccount,
                    title = stringResource(R.string.web_dav_account),
                    summary = upPreferenceSummary(PreferKey.webDavAccount, webDavAccount),
                )
            }
            item(key = PreferKey.webDavPassword) {
                EditTextPreferenceItem(
                    key = PreferKey.webDavPassword,
                    title = stringResource(R.string.web_dav_pw),
                    summary = upPreferenceSummary(PreferKey.webDavPassword, webDavPassword),
                    isPassword = true,
                )
            }
            item(key = PreferKey.webDavDir) {
                EditTextPreferenceItem(
                    key = PreferKey.webDavDir,
                    default = AppConfig.webDavDir ?: "legado",
                    title = stringResource(R.string.sub_dir),
                    summary = upPreferenceSummary(PreferKey.webDavDir, webDavDir),
                )
            }
            item(key = PreferKey.webDavDeviceName) {
                EditTextPreferenceItem(
                    key = PreferKey.webDavDeviceName,
                    default = AppConfig.webDavDeviceName.orEmpty(),
                    title = stringResource(R.string.webdav_device_name),
                    summary = webDavDeviceName.ifBlank { AppConfig.webDavDeviceName.orEmpty() },
                )
            }
            item(key = PreferKey.webDavBookAutoRestore) {
                SwitchPreferenceItem(
                    key = PreferKey.webDavBookAutoRestore,
                    default = false,
                    title = stringResource(R.string.webdav_book_auto_restore_t),
                    summary = stringResource(R.string.webdav_book_auto_restore_s),
                )
            }
            item(key = PreferKey.syncBookProgress) {
                SwitchPreferenceItem(
                    key = PreferKey.syncBookProgress,
                    default = true,
                    title = stringResource(R.string.sync_book_progress_t),
                    summary = stringResource(R.string.sync_book_progress_s),
                )
            }
            item(key = PreferKey.syncBookProgressPlus) {
                SwitchPreferenceItem(
                    key = PreferKey.syncBookProgressPlus,
                    default = false,
                    title = stringResource(R.string.sync_book_progress_plus_t),
                    summary = stringResource(R.string.sync_book_progress_plus_s),
                    enabled = syncBookProgress,
                )
            }

            item(key = "backupRestoreCategory") {
                PreferenceCategoryHeader(title = stringResource(R.string.backup_restore))
            }
            item(key = "localPassword") {
                PreferenceItem(
                    title = stringResource(R.string.set_local_password),
                    summary = stringResource(R.string.set_local_password_summary),
                    onClick = { onPreferenceTreeClick(PreferenceKeyRef("localPassword")) },
                )
            }
            item(key = PreferKey.backupPath) {
                PreferenceItem(
                    title = stringResource(R.string.backup_path),
                    summary = backupPathSummary,
                    onClick = { onPreferenceTreeClick(PreferenceKeyRef(PreferKey.backupPath)) },
                )
            }
            item(key = PreferKey.backupContent) {
                PreferenceItem(
                    title = stringResource(R.string.backup_content),
                    summary = stringResource(R.string.backup_content_summary),
                    onClick = { onPreferenceTreeClick(PreferenceKeyRef(PreferKey.backupContent)) },
                )
            }
            item(key = "lan_backup_transfer") {
                PreferenceItem(
                    title = stringResource(R.string.lan_backup_transfer),
                    summary = stringResource(R.string.lan_backup_transfer_summary),
                    onClick = { onPreferenceTreeClick(PreferenceKeyRef("lan_backup_transfer")) },
                )
            }
            item(key = "web_dav_backup") {
                PreferenceItem(
                    title = stringResource(R.string.backup),
                    summary = stringResource(R.string.backup_destination_summary),
                    onClick = { onPreferenceTreeClick(PreferenceKeyRef("web_dav_backup")) },
                )
            }
            item(key = "web_dav_restore") {
                PreferenceItem(
                    title = stringResource(R.string.restore),
                    summary = stringResource(R.string.restore_summary),
                    onLongClick = { restoreFromLocal() },
                    onClick = { onPreferenceTreeClick(PreferenceKeyRef("web_dav_restore")) },
                )
            }
            item(key = PreferKey.restoreIgnore) {
                PreferenceItem(
                    title = stringResource(R.string.restore_ignore),
                    summary = stringResource(R.string.restore_ignore_summary),
                    onClick = { onPreferenceTreeClick(PreferenceKeyRef(PreferKey.restoreIgnore)) },
                )
            }
            item(key = PreferKey.onlyLatestBackup) {
                SwitchPreferenceItem(
                    key = PreferKey.onlyLatestBackup,
                    default = true,
                    title = stringResource(R.string.only_latest_backup_t),
                    summary = stringResource(R.string.only_latest_backup_s),
                )
            }
            item(key = PreferKey.autoBackup) {
                PreferenceItem(
                    title = stringResource(R.string.auto_backup_t),
                    summary = autoBackupSummary,
                    onClick = { onPreferenceTreeClick(PreferenceKeyRef(PreferKey.autoBackup)) },
                )
            }
            item(key = PreferKey.autoCheckNewBackup) {
                SwitchPreferenceItem(
                    key = PreferKey.autoCheckNewBackup,
                    default = true,
                    title = stringResource(R.string.auto_check_new_backup_t),
                    summary = stringResource(R.string.auto_check_new_backup_s),
                )
            }
        }

        if (showAutoBackupDialog) {
            var enabled by remember { mutableStateOf(AppConfig.autoBackup) }
            var uploadWebDav by remember { mutableStateOf(AppConfig.autoBackupWebDav) }
            var daysText by remember { mutableStateOf(AppConfig.autoBackupIntervalDays.toString()) }
            var hasError by remember { mutableStateOf(false) }

            ComposeAlertDialog(
                onDismissRequest = { showAutoBackupDialog = false },
                modifier = Modifier.widthIn(max = 480.dp),
                title = { Text(stringResource(R.string.auto_backup_t)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickableCompat(onClick = { enabled = !enabled })
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(R.string.auto_backup_t),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Switch(checked = enabled, onCheckedChange = { enabled = it })
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickableCompat(onClick = { uploadWebDav = false })
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = !uploadWebDav, onClick = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.backup_local_only))
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickableCompat(onClick = { uploadWebDav = true })
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = uploadWebDav, onClick = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.backup_local_webdav))
                        }

                        OutlinedTextField(
                            value = daysText,
                            onValueChange = {
                                daysText = it
                                hasError = false
                            },
                            label = { Text(stringResource(R.string.auto_backup_interval_days)) },
                            isError = hasError,
                            supportingText = if (hasError) {
                                { Text(stringResource(R.string.auto_backup_interval_invalid)) }
                            } else null,
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showAutoBackupDialog = false }) {
                        Text(stringResource(R.string.cancel))
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val days = daysText.toIntOrNull()
                        if (days == null || days < 1) {
                            hasError = true
                            return@TextButton
                        }
                        requireContext().defaultSharedPreferences.edit()
                            .putBoolean(PreferKey.autoBackup, enabled)
                            .putBoolean(PreferKey.autoBackupWebDav, uploadWebDav)
                            .putInt(PreferKey.autoBackupIntervalDays, days)
                            .apply()
                        refreshTick++
                        showAutoBackupDialog = false
                    }) {
                        Text(stringResource(R.string.ok))
                    }
                }
            )
        }

        if (showLocalPasswordDialog) {
            LegadoTextInputDialog(
                title = stringResource(R.string.set_local_password),
                message = stringResource(R.string.set_local_password_summary),
                initialValue = LocalConfig.password ?: "",
                hint = "password",
                isPassword = true,
                onDismiss = { showLocalPasswordDialog = false },
                onConfirm = { value ->
                    LocalConfig.password = value
                    showLocalPasswordDialog = false
                }
            )
        }
    }

    private fun alertLocalPassword() {
        showLocalPasswordDialog = true
    }

    private fun showBackupPathSelector() {
        requireContext().selector(
            titleSource = R.string.backup_path,
            items = listOf(
                getString(R.string.default_path),
                getString(R.string.select_folder),
            ),
        ) { _, index ->
            when (index) {
                0 -> AppConfig.backupPath = null
                1 -> selectBackupPath.launch()
            }
            refreshTick++
        }
    }

    private fun lanBackupTransfer() {
        requireContext().selector(
            titleSource = R.string.lan_backup_transfer,
            items = listOf(
                getString(R.string.lan_backup_send),
                getString(R.string.lan_backup_receive),
            ),
        ) { _, index ->
            when (index) {
                0 -> confirmLanBackupSend()
                1 -> lanBackupQr.launch()
            }
        }
    }

    private fun confirmLanBackupSend() {
        alert(
            titleResource = R.string.lan_backup_send,
            messageResource = R.string.lan_backup_send_confirm,
        ) {
            okButton { sendLanBackup() }
            cancelButton()
        }
    }

    private fun sendLanBackup() {
        lanBackupDialog?.dismiss()
        lanBackupSession?.close()
        lanBackupDialog = null
        lanBackupSession = null
        lanBackupJob?.cancel()
        waitDialog.setText(R.string.lan_backup_send).show()
        lanBackupJob = viewLifecycleOwner.lifecycleScope.launch {
            var pendingSession: LanBackupSession? = null
            try {
                val prepared = withContext(IO) {
                    val backupFile = Backup.backupForLanTransferLocked(appCtx)
                    val deviceName = AppConfig.webDavDeviceName
                        ?.takeIf { it.isNotBlank() }
                        ?: "${Build.MANUFACTURER} ${Build.MODEL}".trim()
                    val session = LanBackupTransfer.prepare(backupFile, deviceName)
                    pendingSession = session
                    val qrCode = QRCodeUtils.createQRCode(session.qrText)
                        ?: throw NoStackTraceException("生成二维码失败")
                    session to qrCode
                }
                pendingSession = prepared.first
                ensureActive()
                showLanBackupQr(prepared.first, prepared.second)
                pendingSession = null
            } catch (error: Throwable) {
                ensureActive()
                AppLog.put("局域网备份发送失败\n${error.localizedMessage}", error)
                appCtx.toastOnUi(error.localizedMessage ?: getString(R.string.backup_fail, ""))
            } finally {
                pendingSession?.close()
                waitDialog.dismiss()
            }
        }
        waitDialog.setOnCancelListener { lanBackupJob?.cancel() }
    }

    private fun showLanBackupQr(
        session: LanBackupSession,
        qrCode: android.graphics.Bitmap,
    ) {
        lanBackupDialog?.dismiss()
        lanBackupSession?.close()
        lanBackupSession = session
        val ctx = requireContext()
        val rootLayout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(24.dpToPx(), 16.dpToPx(), 24.dpToPx(), 16.dpToPx())
            addView(
                ImageView(ctx).apply {
                    contentDescription = getString(R.string.lan_backup_waiting)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setImageBitmap(qrCode)
                },
                LinearLayout.LayoutParams(240.dpToPx(), 240.dpToPx())
            )
            addView(
                TextView(ctx).apply {
                    gravity = Gravity.CENTER
                    text = getString(R.string.lan_backup_waiting)
                    textSize = 14f
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 12.dpToPx() }
            )
        }
        lateinit var dialog: AlertDialog
        val expireTask = Runnable {
            if (lanBackupSession === session) dialog.dismiss()
        }
        dialog = alert(R.string.lan_backup_send) {
            customView { rootLayout }
            cancelButton()
            onDismiss {
                rootLayout.removeCallbacks(expireTask)
                if (lanBackupSession === session) {
                    lanBackupSession = null
                    session.close()
                }
                if (lanBackupDialog === dialog) {
                    lanBackupDialog = null
                }
            }
        }
        lanBackupDialog = dialog
        rootLayout.postDelayed(
            expireTask,
            (session.descriptor.expiresAt - System.currentTimeMillis()).coerceAtLeast(0L),
        )
    }

    private fun confirmLanBackupReceive(qrText: String) {
        val descriptor = LanBackupTransfer.decodeDescriptor(qrText).getOrElse { error ->
            appCtx.toastOnUi(error.localizedMessage ?: getString(R.string.lan_backup_receive))
            return
        }
        alert(
            title = getString(R.string.lan_backup_receive),
            message = getString(
                R.string.lan_backup_receive_confirm,
                Formatter.formatFileSize(requireContext(), descriptor.size),
                descriptor.deviceName.ifBlank { descriptor.hosts.first() },
            ),
        ) {
            okButton { receiveLanBackup(qrText) }
            cancelButton()
        }
    }

    private fun receiveLanBackup(qrText: String) {
        lanBackupJob?.cancel()
        waitDialog.setText(R.string.lan_backup_receive).show()
        lanBackupJob = viewLifecycleOwner.lifecycleScope.launch {
            var receivedFile: java.io.File? = null
            try {
                val received = LanBackupTransfer.receive(appCtx, qrText)
                receivedFile = received.file
                waitDialog.setText(R.string.backup)
                withContext(IO) {
                    Backup.backupBeforeLanRestoreLocked(appCtx)
                    LanBackupTransfer.requireRestoreSpace(appCtx, received.uncompressedBytes)
                }
                ensureActive()
                waitDialog.setText(R.string.restore)
                withContext(IO) {
                    Restore.restoreOrThrow(
                        appCtx,
                        received.file.toUri(),
                        lanTransfer = true,
                    )
                }
            } catch (error: Throwable) {
                ensureActive()
                AppLog.put("局域网备份接收失败\n${error.localizedMessage}", error)
                appCtx.toastOnUi(error.localizedMessage ?: getString(R.string.lan_backup_receive))
            } finally {
                receivedFile?.parentFile?.deleteRecursively()
                waitDialog.dismiss()
            }
        }
        waitDialog.setOnCancelListener { lanBackupJob?.cancel() }
    }

    /**
     * 备份忽略设置
     */
    private fun backupIgnore() {
        val checkedItems = BooleanArray(BackupConfig.ignoreKeys.size) {
            BackupConfig.ignoreConfig[BackupConfig.ignoreKeys[it]] ?: false
        }
        alert(R.string.restore_ignore) {
            multiChoiceItems(BackupConfig.ignoreTitle, checkedItems) { _, which, isChecked ->
                BackupConfig.ignoreConfig[BackupConfig.ignoreKeys[which]] = isChecked
            }
            onDismiss {
                BackupConfig.saveIgnoreConfig()
            }
        }
    }

    private fun backupContent() {
        val checkedItems = BooleanArray(BackupConfig.contentKeys.size) {
            BackupConfig.contentIsEnabled(BackupConfig.contentKeys[it])
        }
        alert(R.string.backup_content) {
            multiChoiceItems(BackupConfig.contentTitles, checkedItems) { _, which, isChecked ->
                BackupConfig.ignoreConfig[BackupConfig.contentKeys[which]] = !isChecked
            }
            onDismiss {
                BackupConfig.saveIgnoreConfig()
            }
        }
    }

    fun backup() {
        requireContext().selector(R.string.backup, listOf(
            getString(R.string.backup_local_only), getString(R.string.backup_local_webdav),
        )) { _, index ->
            manualBackupUploadWebDav = index == 1
            startBackup(manualBackupUploadWebDav)
        }
    }

    private fun startBackup(uploadWebDav: Boolean) {
        val backupPath = AppConfig.backupPath
        if (backupPath.isNullOrEmpty()) {
            backup(null, uploadWebDav)
        } else {
            if (backupPath.isContentScheme()) {
                lifecycleScope.launch {
                    val canWrite = withContext(IO) {
                        FileDoc.fromDir(backupPath).checkWrite()
                    }
                    if (canWrite) {
                        backup(backupPath, uploadWebDav)
                    } else {
                        backupDir.launch()
                    }
                }
            } else {
                backupUsePermission(backupPath, uploadWebDav)
            }
        }
    }

    private fun backup(backupPath: String?, uploadWebDav: Boolean) {
        waitDialog.setText("备份中…")
        waitDialog.setOnCancelListener {
            backupJob?.cancel()
        }
        waitDialog.show()
        backupJob?.cancel()
        backupJob = lifecycleScope.launch {
            try {
                Backup.backupLocked(requireContext(), backupPath, uploadWebDav)
                appCtx.toastOnUi(R.string.backup_success)
            } catch (e: Throwable) {
                ensureActive()
                AppLog.put("备份出错\n${e.localizedMessage}", e)
                appCtx.toastOnUi(
                    appCtx.getString(
                        R.string.backup_fail,
                        e.localizedMessage
                    )
                )
            } finally {
                ensureActive()
                waitDialog.dismiss()
            }
        }
    }

    private fun backupUsePermission(path: String, uploadWebDav: Boolean) {
        PermissionsCompat.Builder()
            .addPermissions(*Permissions.Group.STORAGE)
            .rationale(R.string.tip_perm_request_storage)
            .onGranted {
                backup(path, uploadWebDav)
            }
            .request()
    }

    fun restore() {
        waitDialog.setText(R.string.loading)
        waitDialog.setOnCancelListener {
            restoreJob?.cancel()
        }
        waitDialog.show()
        Coroutine.async {
            restoreJob = coroutineContext[Job]
            showRestoreDialog(requireContext())
        }.onError {
            AppLog.put("恢复备份出错WebDavError\n${it.localizedMessage}", it)
            if (context == null) {
                return@onError
            }
            alert {
                setTitle(R.string.restore)
                setMessage("WebDavError\n${it.localizedMessage}\n将从本地备份恢复。")
                okButton {
                    restoreFromLocal()
                }
                cancelButton()
            }
        }.onFinally {
            waitDialog.dismiss()
        }
    }

    private suspend fun showRestoreDialog(context: Context) {
        val names = withContext(IO) { AppWebDav.getBackupNames() }
        if (AppWebDav.isJianGuoYun && names.size > 700) {
            context.toastOnUi("由于坚果云限制列出文件数量，部分备份可能未显示，请及时清理旧备份")
        }
        if (names.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            withContext(Main) {
                context.selector(
                    title = context.getString(R.string.select_restore_file),
                    items = names
                ) { _, index ->
                    if (index in 0 until names.size) {
                        restoreWebDav(names[index])
                    }
                }
            }
        } else {
            throw NoStackTraceException("Web dav no back up file")
        }
    }

    private fun restoreWebDav(name: String) {
        waitDialog.setText("恢复中…")
        waitDialog.show()
        val task = Coroutine.async {
            AppWebDav.restoreWebDav(name)
        }.onError {
            AppLog.put("WebDav恢复出错\n${it.localizedMessage}", it)
            appCtx.toastOnUi("WebDav恢复出错\n${it.localizedMessage}")
        }.onFinally {
            waitDialog.dismiss()
        }
        waitDialog.setOnCancelListener {
            task.cancel()
        }
    }

    private fun restoreFromLocal() {
        restoreDoc.launch {
            title = getString(R.string.select_restore_file)
            mode = HandleFileContract.FILE
            allowExtensions = arrayOf("zip")
        }
    }

    override fun onDestroyView() {
        lanBackupJob?.cancel()
        lanBackupDialog?.dismiss()
        lanBackupSession?.close()
        lanBackupDialog = null
        lanBackupSession = null
        super.onDestroyView()
        waitDialog.dismiss()
    }

    override fun onStop() {
        lanBackupJob?.cancel()
        lanBackupDialog?.dismiss()
        lanBackupSession?.close()
        lanBackupDialog = null
        lanBackupSession = null
        super.onStop()
    }
}
