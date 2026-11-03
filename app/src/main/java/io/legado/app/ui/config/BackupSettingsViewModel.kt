package io.legado.app.ui.config

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.data.repository.BackupSettingsRepository
import io.legado.app.data.repository.BackupSelectionOption
import io.legado.app.data.repository.LanBackupQr
import io.legado.app.data.repository.LanBackupReceivePreview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class BackupSettingsUiState(
    val busyMessage: Int? = null,
    val notice: BackupNotice? = null,
    val remoteBackupNames: List<String>? = null,
    val lanQr: LanBackupQr? = null,
    val receivePreview: LanBackupReceivePreview? = null,
)

data class BackupNotice(val text: String? = null, val resourceId: Int? = null)

@HiltViewModel
class BackupSettingsViewModel @Inject constructor(
    private val repository: BackupSettingsRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(BackupSettingsUiState())
    val uiState: StateFlow<BackupSettingsUiState> = _uiState.asStateFlow()
    private var operation: Job? = null

    fun updateWebDavConfig() = viewModelScope.launch {
        runCatching { repository.updateWebDavConfig() }
            .onFailure { report(it, "更新 WebDAV 配置失败") }
    }

    fun backup(path: String?, uploadWebDav: Boolean) = runOperation(R.string.backup) {
        repository.backup(path, uploadWebDav)
        _uiState.update { it.copy(notice = BackupNotice(resourceId = R.string.backup_success)) }
    }

    suspend fun canWriteBackupPath(path: String): Boolean = repository.canWriteBackupPath(path)

    fun restoreLocal(uri: Uri) = runOperation(R.string.restore) {
        repository.restoreLocal(uri)
        _uiState.update { it.copy(notice = BackupNotice(resourceId = R.string.success)) }
    }

    fun importLegacy(uri: Uri) = runOperation(R.string.menu_import_old_version) {
        repository.importLegacy(uri)
    }

    fun loadRemoteBackups() = runOperation(R.string.loading) {
        val names = repository.webDavBackupNames()
        _uiState.update { it.copy(remoteBackupNames = names) }
    }

    fun restoreRemote(name: String) = runOperation(R.string.restore) {
        repository.restoreWebDav(name)
        _uiState.update { it.copy(remoteBackupNames = null, notice = BackupNotice(resourceId = R.string.success)) }
    }

    fun prepareLanSend() = runOperation(R.string.lan_backup_send) {
        closeLanQr()
        val qr = repository.createLanBackup()
        _uiState.update { it.copy(lanQr = qr) }
    }

    fun previewLanReceive(qrText: String) {
        runCatching { repository.previewLanRestore(qrText) }
            .onSuccess { preview -> _uiState.update { it.copy(receivePreview = preview) } }
            .onFailure { report(it, "局域网备份二维码无效") }
    }

    fun confirmLanReceive() {
        val preview = _uiState.value.receivePreview ?: return
        runOperation(R.string.lan_backup_receive) {
            repository.receiveLanBackup(preview.qrText)
            _uiState.update { it.copy(receivePreview = null, notice = BackupNotice(resourceId = R.string.success)) }
        }
    }

    fun closeLanQr() {
        _uiState.value.lanQr?.let { repository.closeLanBackup(it.session) }
        _uiState.update { it.copy(lanQr = null) }
    }

    fun closeRemoteBackupDialog() = _uiState.update { it.copy(remoteBackupNames = null) }
    fun closeLanReceiveDialog() = _uiState.update { it.copy(receivePreview = null) }
    fun consumeNotice() = _uiState.update { it.copy(notice = null) }

    fun cancelOperation() {
        operation?.cancel()
        _uiState.update { it.copy(busyMessage = null) }
    }

    fun contentOptions(): List<BackupSelectionOption> = repository.contentOptions()
    fun ignoreOptions(): List<BackupSelectionOption> = repository.ignoreOptions()
    fun saveContentOptions(options: List<BackupSelectionOption>) = repository.saveContentOptions(options)
    fun saveIgnoreOptions(options: List<BackupSelectionOption>) = repository.saveIgnoreOptions(options)
    fun localPassword(): String = repository.localPassword()
    fun saveLocalPassword(value: String) = repository.saveLocalPassword(value)

    val defaultBackupPath: String get() = repository.defaultBackupPath

    override fun onCleared() {
        closeLanQr()
        operation?.cancel()
        super.onCleared()
    }

    private fun runOperation(message: Int, block: suspend () -> Unit) {
        operation?.cancel()
        operation = viewModelScope.launch {
            _uiState.update { it.copy(busyMessage = message, notice = null) }
            try {
                block()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                report(error, "备份/恢复操作失败")
            } finally {
                _uiState.update { it.copy(busyMessage = null) }
            }
        }
    }

    private fun report(error: Throwable, title: String) {
        AppLog.put("$title\n${error.localizedMessage}", error)
        _uiState.update { it.copy(notice = BackupNotice(text = error.localizedMessage ?: title)) }
    }
}
