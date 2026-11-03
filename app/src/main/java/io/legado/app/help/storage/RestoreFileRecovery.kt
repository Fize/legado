package io.legado.app.help.storage

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.Cache
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.model.BookCover
import io.legado.app.ui.font.installFontFile
import io.legado.app.utils.externalFiles
import android.graphics.Typeface
import java.io.File
import java.util.UUID

/** Called under backupRestoreMutex, except the single-threaded early startup resolve. */
internal object RestoreFileRecovery {
    const val COMMIT_KEY = "legado.restore.files.v1.commit"
    private fun journal(context: Context) = File(context.filesDir, "restore-files-v1.bin")

    fun prepare(
        context: Context,
        backupRoot: File,
        reader: RestoredReaderConfiguration,
        includeBackgrounds: Boolean,
        restoreFont: Boolean,
        onFontPrepared: (String) -> Unit,
        checkActive: () -> Unit,
    ): RestoreFileTransaction? {
        recover(context, cleanup = true)
        val names = backupMediaDirectoryNames.filter { includeBackgrounds || it != "bg" }
        // Preserve the previous implementation's recovery of old, pre-journal swaps.
        val empty = File(context.cacheDir, ".restore-empty-${UUID.randomUUID()}")
        names.filter { File(context.externalFiles, ".$it.previous").exists() }.forEach {
            restoreBackupMediaDirectory(empty, context.externalFiles, it).getOrThrow()
        }
        val replacements = buildMap {
            reader.styles?.let { put(ReadBookConfig.configFileName, it) }
            reader.shareStyle?.let { put(ReadBookConfig.shareConfigFileName, it) }
        }
        val additional = if (restoreFont) mapOf<String, (File) -> Unit>("font" to { directory ->
            val file = File(backupRoot, BookCover.fontBackupFileName).inputStream().use { input ->
                installFontFile(input, BookCover.fontBackupFileName, directory) {
                    runCatching { Typeface.createFromFile(it) }.isSuccess
                }
            }
            onFontPrepared(File(context.externalFiles, "font/${file.name}").absolutePath)
        }) else emptyMap()
        return RestoreFileTransaction.prepare(backupRoot, context.externalFiles, journal(context), names,
            replacements, additional, checkActive)
    }

    suspend fun <T> withTransaction(
        context: Context,
        files: RestoreFileTransaction?,
        includeDataStores: Boolean,
        beforeCommit: suspend (RestoreSettingsTransaction) -> Unit,
        block: suspend () -> T,
    ): T {
        val id = files?.id ?: UUID.randomUUID().toString()
        val result = try {
            val settings = RestoreSettingsTransaction.prepare(context, id, includeDataStores)
            appDb.withTransaction {
                val result = block()
                val coroutine = currentCoroutineContext()
                files?.activate { coroutine.ensureActive() }
                beforeCommit(settings)
                check(settings.editLocal().putLong("lastBackup", System.currentTimeMillis()).commit())
                appDb.cacheDao.insert(Cache(COMMIT_KEY, id))
                appDb.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { rows ->
                    if (rows.moveToFirst()) throw SQLiteConstraintException("关联数据不完整，已取消恢复以保留原数据。")
                }
                coroutine.ensureActive()
                result
            }
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                try { recover(context, cleanup = true) }
                catch (recovery: Throwable) { failure.addSuppressed(recovery) }
            }
            throw failure
        }
        // A cancellation while Room returns cannot reverse a committed group.
        runCatching { recover(context, cleanup = true) }
            .onFailure { AppLog.put("恢复已提交，临时文件清理待重试", it) }
        return result
    }

    fun hasPending(context: Context): Boolean = journal(context).isFile || RestoreSettingsTransaction.hasPending(context)

    fun pendingIsResolved(context: Context): Boolean {
        val pending = journal(context)
        val files = if (pending.isFile) checkNotNull(RestoreFileTransaction.read(pending, context.externalFiles)) else null
        val settings = RestoreSettingsTransaction.read(context)
        check(files == null || settings == null || files.id == settings.id)
        return (files == null || files.isResolved) && (settings == null || settings.isResolved)
    }

    fun recover(context: Context, cleanup: Boolean): Boolean {
        val pending = journal(context)
        if (!hasPending(context)) return false
        val files = if (pending.isFile) checkNotNull(RestoreFileTransaction.read(pending, context.externalFiles)) else null
        val settings = RestoreSettingsTransaction.read(context)
        check(files == null || settings == null || files.id == settings.id) { "恢复日志身份不一致" }
        val id = checkNotNull(files?.id ?: settings?.id)
        val committed = databaseCommitted(context, id)
        files?.resolve(committed)
        // Only pending recovery enters this path; a normal startup does no DB/DataStore work.
        runBlocking(Dispatchers.IO) { settings?.resolve(committed) }
        if (cleanup) {
            files?.cleanup()
            settings?.cleanup()
            if (appDb.cacheDao.get(COMMIT_KEY)?.value == id) appDb.cacheDao.delete(COMMIT_KEY)
        }
        return true
    }

    private fun databaseCommitted(context: Context, id: String): Boolean {
        val database = context.getDatabasePath(AppDatabase.DATABASE_NAME)
        if (!database.isFile) return false
        // A fresh connection observes committed WAL, never a failed transaction's
        // uncommitted cache marker retained by an older framework connection.
        return SQLiteDatabase.openDatabase(database.path, null,
            SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { db ->
            val exists = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='caches'", null)
                .use { it.moveToFirst() }
            exists && db.rawQuery("SELECT value FROM caches WHERE key = ?", arrayOf(COMMIT_KEY))
                .use { it.moveToFirst() && it.getString(0) == id }
        }
    }
}
