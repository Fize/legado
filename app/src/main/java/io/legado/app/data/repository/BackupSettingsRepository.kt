package io.legado.app.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import io.legado.app.R
import io.legado.app.help.AppWebDav
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.storage.Backup
import io.legado.app.help.storage.BackupConfig
import io.legado.app.help.storage.ImportOldData
import io.legado.app.help.storage.LanBackupSession
import io.legado.app.help.storage.LanBackupTransfer
import io.legado.app.help.storage.Restore
import io.legado.app.utils.QRCodeUtils
import io.legado.app.utils.FileDoc
import io.legado.app.utils.checkWrite
import io.legado.app.utils.externalFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

data class BackupSelectionOption(val key: String, @androidx.annotation.StringRes val titleId: Int, val enabled: Boolean)

data class LanBackupQr(val bitmap: Bitmap, val session: LanBackupSession)

data class LanBackupReceivePreview(
    val qrText: String,
    val size: Long,
    val deviceName: String,
)

@Singleton
class BackupSettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val defaultBackupPath: String get() = context.externalFiles.absolutePath

    suspend fun backup(path: String?, uploadWebDav: Boolean) = Backup.backupLocked(context, path, uploadWebDav)

    suspend fun canWriteBackupPath(path: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { FileDoc.fromDir(path).checkWrite() }.getOrDefault(false)
    }

    suspend fun restoreLocal(uri: Uri) = Restore.restoreOrThrow(context, uri)

    suspend fun updateWebDavConfig() = AppWebDav.upConfig()

    suspend fun webDavBackupNames(): List<String> = AppWebDav.getBackupNames()

    suspend fun restoreWebDav(name: String) = AppWebDav.restoreWebDav(name)

    suspend fun importLegacy(uri: Uri) = withContext(Dispatchers.IO) {
        ImportOldData.importUri(context, uri)
    }

    suspend fun createLanBackup(): LanBackupQr {
        val file = Backup.backupForLanTransferLocked(context)
        val deviceName = AppConfig.webDavDeviceName?.takeIf(String::isNotBlank)
            ?: "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim()
        val session = LanBackupTransfer.prepare(file, deviceName)
        return try {
            val bitmap = QRCodeUtils.createQRCode(session.qrText)
                ?: error(context.getString(R.string.backup_fail, "QR code generation failed"))
            LanBackupQr(bitmap, session)
        } catch (error: Throwable) {
            session.close()
            throw error
        }
    }

    fun closeLanBackup(session: LanBackupSession) = session.close()

    fun previewLanRestore(qrText: String): LanBackupReceivePreview {
        val descriptor = LanBackupTransfer.decodeDescriptor(qrText).getOrThrow()
        return LanBackupReceivePreview(qrText, descriptor.size, descriptor.deviceName)
    }

    suspend fun receiveLanBackup(qrText: String) {
        val received = LanBackupTransfer.receive(context, qrText)
        try {
            withContext(Dispatchers.IO) {
                Backup.backupBeforeLanRestoreLocked(context)
                LanBackupTransfer.requireRestoreSpace(context, received.uncompressedBytes)
            }
            withContext(Dispatchers.IO) {
                Restore.restoreOrThrow(context, received.file.toUri(), lanTransfer = true)
            }
        } finally {
            received.file.parentFile?.deleteRecursively()
        }
    }

    fun contentOptions(): List<BackupSelectionOption> = BackupConfig.contentKeys.mapIndexed { index, key ->
        BackupSelectionOption(key, BackupConfig.contentTitleIds[index], BackupConfig.contentIsEnabled(key))
    }

    fun ignoreOptions(): List<BackupSelectionOption> = BackupConfig.ignoreKeys.mapIndexed { index, key ->
        BackupSelectionOption(key, BackupConfig.ignoreTitleIds[index], BackupConfig.ignoreConfig[key] == true)
    }

    fun saveContentOptions(options: List<BackupSelectionOption>) {
        options.forEach { BackupConfig.ignoreConfig[it.key] = !it.enabled }
        BackupConfig.saveIgnoreConfig()
    }

    fun saveIgnoreOptions(options: List<BackupSelectionOption>) {
        options.forEach { BackupConfig.ignoreConfig[it.key] = it.enabled }
        BackupConfig.saveIgnoreConfig()
    }

    fun localPassword(): String = LocalConfig.password.orEmpty()

    fun saveLocalPassword(value: String) {
        LocalConfig.password = value
    }
}
