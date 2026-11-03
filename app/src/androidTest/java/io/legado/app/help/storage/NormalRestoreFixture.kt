package io.legado.app.help.storage

import android.content.Context
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.utils.defaultSharedPreferences
import java.io.File
import java.util.UUID

/** A real restore of explicitly supplied, fixture-owned payloads; never replaces a whole database. */
internal class NormalRestoreFixture(
    private val context: Context,
    owner: String,
    private val allowedTasks: () -> Set<String> = { emptySet() },
    private val allowedBatches: () -> Set<String> = { emptySet() },
) {
    private val root = File(context.cacheDir, "normal-restore-$owner")
    private val prefs = context.defaultSharedPreferences
    private val keys = listOf(PreferKey.autoReadSpeed, PreferKey.comicStyleSelect, PreferKey.readStyleSelect,
        PreferKey.shareLayout, PreferKey.hideStatusBar, PreferKey.hideNavigationBar)
    private val before = keys.associateWith { prefs.all[it] }
    private val readerInts = listOf(ReadBookConfig.autoReadSpeed, ReadBookConfig.comicStyleSelect, ReadBookConfig.readStyleSelect)
    private val readerFlags = listOf(ReadBookConfig.shareLayout, ReadBookConfig.hideStatusBar, ReadBookConfig.hideNavigationBar)
    private val lastBackup = LocalConfig.all["lastBackup"]
    private val readerFiles = listOf(File(ReadBookConfig.configFilePath), File(ReadBookConfig.shareConfigFilePath))
        .associateWith { if (it.isFile) it.readBytes().toList() else null }
    private var used = false
    private var ownsRoot = false

    suspend fun restore(entries: Map<String, String>) {
        check((appDb.bookTransferDao.active("download") + appDb.bookTransferDao.active("export")).all { it.id in allowedTasks() })
        check(appDb.bookTransferDao.unfinishedTransferBatches().all { it.id in allowedBatches() })
        if (!ownsRoot) { check(!root.exists()); check(root.mkdirs()); ownsRoot = true }
        val payload = File(root, UUID.randomUUID().toString()).apply { check(mkdir()) }
        entries.forEach { (name, value) -> check('/' !in name && '\\' !in name); File(payload, name).writeText(value) }
        used = true
        Restore.restoreLocked(payload.path)
        check(!RestoreRecoveryGate.isQuarantined)
        check(!RestoreFileRecovery.hasPending(context))
    }

    fun cleanup() {
        if (used) {
            check(!RestoreFileRecovery.hasPending(context))
            readerFiles.forEach { (file, bytes) -> check(bytes == file.takeIf { it.isFile }?.readBytes()?.toList()) }
            ReadBookConfig.autoReadSpeed = readerInts[0]; ReadBookConfig.comicStyleSelect = readerInts[1]
            ReadBookConfig.readStyleSelect = readerInts[2]; ReadBookConfig.shareLayout = readerFlags[0]
            ReadBookConfig.hideStatusBar = readerFlags[1]; ReadBookConfig.hideNavigationBar = readerFlags[2]
            check(prefs.edit().apply { before.forEach { (key, value) -> when (value) {
                null -> remove(key); is Int -> putInt(key, value); is Boolean -> putBoolean(key, value)
                is String -> putString(key, value); is Long -> putLong(key, value); is Float -> putFloat(key, value)
                else -> error("Unexpected reader preference type")
            } } }.commit())
            check(LocalConfig.edit().apply {
                if (lastBackup == null) remove("lastBackup") else putLong("lastBackup", lastBackup as Long)
            }.commit())
        }
        if (ownsRoot) check(root.deleteRecursively() || !root.exists())
    }
}
