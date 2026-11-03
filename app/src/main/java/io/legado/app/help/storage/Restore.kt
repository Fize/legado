package io.legado.app.help.storage

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.google.gson.JsonElement
import dagger.hilt.android.EntryPointAccessors
import io.legado.app.BuildConfig
import io.legado.app.R
import io.legado.app.constant.AppConst.androidId
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.preferences.CacheExportOptions
import io.legado.app.data.preferences.CacheExportPreferencesEntryPoint
import io.legado.app.data.preferences.CACHE_EXPORT_KEYS
import io.legado.app.data.preferences.restoreLegacyPreferenceMap
import io.legado.app.data.repository.MangaReaderPreferencesEntryPoint
import io.legado.app.data.repository.MANGA_READER_LEGACY_KEYS
import io.legado.app.data.repository.restoreLegacyPreferenceMap
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.data.entities.BookHighlight
import io.legado.app.data.entities.BookMemo
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.Bookmark
import io.legado.app.data.entities.Cache
import io.legado.app.data.entities.Cookie
import io.legado.app.data.entities.DictRule
import io.legado.app.data.entities.HttpTTS
import io.legado.app.data.entities.HighlightRule
import io.legado.app.data.entities.KeyboardAssist
import io.legado.app.data.entities.ReadRecord
import io.legado.app.data.entities.mergeRestoredReadRecord
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.data.entities.RuleSub
import io.legado.app.data.entities.SearchKeyword
import io.legado.app.data.entities.Server
import io.legado.app.data.entities.TxtTocRule
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.AppCacheManager
import io.legado.app.help.CacheManager
import io.legado.app.help.HighlightStyle
import io.legado.app.help.LauncherIconHelp
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.normalizeLegacyPersistedCover
import io.legado.app.help.book.upType
import io.legado.app.help.config.BookshelfReadProgressMode
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.ReplacePreviewConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.help.http.CookieStore
import io.legado.app.lib.theme.WallpaperTheme
import io.legado.app.model.BookCover
import io.legado.app.model.localBook.LocalBook
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.GSONStrict
import io.legado.app.utils.LogUtils
import io.legado.app.utils.compress.ZipUtils
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.externalFiles
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.getPrefString
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.isJsonArray
import io.legado.app.utils.openInputStream
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.io.File
import java.io.FileInputStream
import java.util.UUID

internal fun parseCookieBackup(json: String): List<Cookie> {
    return GSONStrict.fromJsonArray<JsonElement>(json).getOrThrow().map { element ->
        require(element.isJsonObject)
        val url = element.asJsonObject.get("url")
        val cookie = element.asJsonObject.get("cookie")
        require(url != null && url.isJsonPrimitive && url.asJsonPrimitive.isString)
        require(cookie != null && cookie.isJsonPrimitive && cookie.asJsonPrimitive.isString)
        Cookie(url.asString.also { require(it.isNotBlank()) }, cookie.asString)
    }
}

private val runtimeSourceCachePrefixes = arrayOf(
    "v_",
    "userInfo_",
    "loginHeader_",
    "sourceVariable_",
    "infoMap_",
)

internal fun isRuntimeSourceCacheKey(key: String): Boolean {
    return runtimeSourceCachePrefixes.any { prefix ->
        key.startsWith(prefix) && key.length > prefix.length
    }
}

internal fun parseRuntimeSourceCacheBackup(json: String): List<Cache> {
    val latestByKey = linkedMapOf<String, Cache>()
    GSONStrict.fromJsonArray<JsonElement>(json).getOrThrow().forEach { element ->
        require(element.isJsonObject)
        val objectElement = element.asJsonObject
        val keyElement = objectElement.get("key")
        require(keyElement != null && keyElement.isJsonPrimitive && keyElement.asJsonPrimitive.isString)
        val key = keyElement.asString
        require(isRuntimeSourceCacheKey(key))

        val valueElement = objectElement.get("value")
        require(
            valueElement != null && (valueElement.isJsonNull ||
                (valueElement.isJsonPrimitive && valueElement.asJsonPrimitive.isString))
        )
        val deadlineElement = objectElement.get("deadline")
        require(
            deadlineElement != null && deadlineElement.isJsonPrimitive &&
                deadlineElement.asJsonPrimitive.isNumber
        )
        val deadline = deadlineElement.asJsonPrimitive.asString.toLongOrNull()
            ?: throw IllegalArgumentException("deadline must be an integer")
        require(deadline >= 0L)
        latestByKey[key] = Cache(
            key = key,
            value = if (valueElement.isJsonNull) null else valueElement.asString,
            deadline = deadline,
        )
    }
    return latestByKey.values.toList()
}

/**
 * 恢复
 */
object Restore {

    private const val TAG = "Restore"

    suspend fun restore(context: Context, uri: Uri): Unit = backupRestoreMutex.withLock {
        LogUtils.d(TAG, "开始恢复备份 uri:$uri")
        kotlin.runCatching {
            extractBackup(context, uri)
        }.onFailure {
            AppLog.put("复制解压文件出错\n${it.localizedMessage}", it)
            return
        }
        kotlin.runCatching {
            restoreUnpacked(Backup.backupPath)
        }.onFailure {
            RestoreRecoveryGate.recordRestoreFailure(context)
            appCtx.toastOnUi("恢复备份出错\n${it.localizedMessage}")
            AppLog.put("恢复备份出错\n${it.localizedMessage}", it)
        }
    }

    suspend fun restoreOrThrow(
        context: Context,
        uri: Uri,
        lanTransfer: Boolean = false,
    ) = backupRestoreMutex.withLock {
        LogUtils.d(TAG, "开始恢复备份 uri:$uri")
        val restorePath = if (lanTransfer) {
            File(context.cacheDir, "lan_backup/restore/${UUID.randomUUID()}").absolutePath
        } else {
            Backup.backupPath
        }
        try {
            extractBackup(context, uri, restorePath)
            if (lanTransfer) {
                LanBackupTransfer.requireRestoreMediaSpace(
                    context,
                    File(restorePath),
                    includeBackgrounds = !BackupConfig.ignoreReadConfig,
                )
            }
            restoreUnpacked(restorePath, lanTransfer)
        } catch (failure: Throwable) {
            RestoreRecoveryGate.recordRestoreFailure(context)
            throw failure
        } finally {
            if (lanTransfer) FileUtils.delete(restorePath)
        }
    }

    private fun extractBackup(
        context: Context,
        uri: Uri,
        targetPath: String = Backup.backupPath,
    ) {
        FileUtils.delete(targetPath)
        if (uri.isContentScheme()) {
            DocumentFile.fromSingleUri(context, uri)!!.openInputStream()!!.use {
                ZipUtils.unZipToPath(it, targetPath)
            }
        } else {
            ZipUtils.unZipToPath(File(uri.path!!), targetPath)
        }
    }

    suspend fun restoreLocked(path: String, lanTransfer: Boolean = false) {
        backupRestoreMutex.withLock {
            restoreUnpacked(path, lanTransfer)
        }
    }

    private suspend fun restoreUnpacked(path: String, lanTransfer: Boolean = false) {
        if (lanTransfer) {
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) {
                restore(path, lanTransfer = true)
            }
        } else {
            restore(path)
        }
    }

    private suspend fun restore(path: String, lanTransfer: Boolean = false) {
        val password = LocalConfig.password
        val aes = BackupAES(password)
        val backupRoot = File(path)
        validateRestorePayloads(backupRoot)
        val restoredPreferences = readPreferenceSnapshot(appCtx, path, "config")
        val effectivePreferences = restoredPreferences?.filterKeys { key ->
            BackupConfig.keyIsNotIgnore(key) && (!lanTransfer || key !in lanTransferIgnoredPrefKeys)
        }
        validateEagerRestorePreferenceTypes(effectivePreferences.orEmpty())
        // DataStore enforces these limits on update. Reject invalid backup values
        // before any business data or SharedPreferences have been written.
        effectivePreferences?.takeIf { values -> values.keys.any { it in CACHE_EXPORT_KEYS } }?.let { values ->
            try {
                CacheExportOptions().restoreLegacyPreferenceMap(values).validate()
            } catch (e: Exception) {
                throw IllegalArgumentException("config.xml 导出设置无效", e)
            }
        }
        val restoredVideoPreferences = readPreferenceSnapshot(appCtx, path, "videoConfig")
        val readerConfiguration = readRestoredReaderConfiguration(
            backupRoot, includeStyles = !BackupConfig.ignoreReadConfig,
        )
        val restoredCookies = if (!lanTransfer && !BackupConfig.ignoreCookies) {
            File(path, BackupConfig.cookieFileName).takeIf { it.exists() }?.let { file ->
                val raw = file.readText()
                val json = if (raw.isJsonArray()) {
                    raw
                } else {
                    if (password.isNullOrBlank()) {
                        throw NoStackTraceException(
                            appCtx.getString(R.string.cookie_backup_password_required)
                        )
                    }
                    aes.decryptStr(raw)
                }
                parseCookieBackup(json)
            }
        } else {
            null
        }
        val restoredRuntimeSourceCaches = if (!lanTransfer &&
            !BackupConfig.ignoreSourceVariables
        ) {
            File(path, BackupConfig.runtimeSourceCacheFileName).takeIf { it.exists() }?.let { file ->
                val raw = file.readText()
                val json = if (raw.isJsonArray()) {
                    raw
                } else {
                    if (password.isNullOrBlank()) {
                        throw NoStackTraceException(
                            appCtx.getString(R.string.source_variables_backup_password_required)
                        )
                    }
                    aes.decryptStr(raw)
                }
                parseRuntimeSourceCacheBackup(json)
            }
        } else {
            null
        }
        val restoredServers = File(path, "servers.json").takeIf {
            !lanTransfer && it.isFile
        }?.let { file ->
            try {
                val raw = file.readText()
                parseServerBackup(if (raw.isJsonArray()) raw else aes.decryptStr(raw))
            } catch (e: Exception) {
                throw IllegalArgumentException("servers.json 备份数据无法解析或口令错误", e)
            }
        }
        var restoredFontPath: String? = null
        val fontPreference = effectivePreferences?.get(PreferKey.coverFont) as? String
        val restoreFont = !fontPreference.isNullOrBlank() && File(backupRoot, BookCover.fontBackupFileName).isFile
        val restoreContext = currentCoroutineContext()
        restoreContext.ensureActive()
        // Preflight failures leave current work valid. Once mutation starts, old responses
        // must stay invalid even if this restore later rolls back.
        restoreWriteGeneration.advance()
        val preparedFiles = RestoreFileRecovery.prepare(appCtx, backupRoot, readerConfiguration,
            includeBackgrounds = !BackupConfig.ignoreReadConfig, restoreFont = restoreFont,
            onFontPrepared = { restoredFontPath = it }, checkActive = { restoreContext.ensureActive() })
        val committedCookieMemory = linkedMapOf<String, String>()
        var committedSamples: Pair<List<ReplaceRule>, List<Long>>? = null
        RestoreFileRecovery.withTransaction(appCtx, preparedFiles,
            includeDataStores = restoredPreferences != null,
            beforeCommit = { settings ->
                committedSamples?.let { (rules, ids) ->
                    ReplacePreviewConfig.saveImportedSamples(rules, ids, clearMissing = true,
                        editor = settings.editSamples())
                }
                restoredPreferences?.let { map ->
                    val edit = settings.editDefault()

                    map.forEach { (key, value) ->
                        if (BackupConfig.keyIsNotIgnore(key) &&
                            (!lanTransfer || key !in lanTransferIgnoredPrefKeys)
                        ) {
                            when (key) {
                                "readRecordSort" -> (value as? Int)?.let {
                                    settings.editLocal().putInt(key, it.coerceIn(0, 2)).apply()
                                }
                                PreferKey.readRecordCover,
                                PreferKey.readRecordCoverDark -> {
                                    val coverPath = (value as? String)?.let {
                                        remapRestoredCoverPath(it, File(path), appCtx.externalFiles)
                                    }
                                    if (coverPath == null) edit.remove(key) else edit.putString(key, coverPath)
                                }
                                PreferKey.coverFont -> {
                                    val fontPath = if (restoreFont) checkNotNull(restoredFontPath)
                                    else (value as? String).orEmpty().takeIf { File(it).isFile }.orEmpty()
                                    edit.putString(key, fontPath)
                                }
                                PreferKey.webDavPassword -> {
                                    val raw = value.toString()
                                    val decrypted = if (!password.isNullOrBlank()) {
                                        kotlin.runCatching { aes.decryptStr(raw) }.getOrNull()
                                    } else {
                                        null
                                    }
                                    if (decrypted != null) {
                                        edit.putString(key, decrypted)
                                    } else if (raw.isNotBlank() ||
                                        appCtx.getPrefString(PreferKey.webDavPassword).isNullOrBlank()
                                    ) {
                                        edit.putString(key, raw)
                                    }
                                }

                                PreferKey.doublePageHorizontal -> {
                                    val raw = value.toString()
                                    edit.putString(key, if (raw == "0") "2" else raw)
                                    edit.putBoolean("doublePageHorizontalMigrated_v310", true)
                                }

                                else -> when (value) {
                                    is Int -> edit.putInt(key, value)
                                    is Boolean -> edit.putBoolean(key, value)
                                    is Long -> edit.putLong(key, value)
                                    is Float -> edit.putFloat(key, value)
                                    is String -> edit.putString(key, value)
                                    is Set<*> -> {
                                        @Suppress("UNCHECKED_CAST")
                                        edit.putStringSet(key, value as Set<String>)
                                    }
                                }
                            }
                        }
                    }
                    if (PreferKey.bookshelfReadProgressMode !in map &&
                        PreferKey.showBookshelfReadProgress in map
                    ) {
                        val legacyEnabled = when (val value = map[PreferKey.showBookshelfReadProgress]) {
                            is Boolean -> value
                            is String -> value.toBooleanStrictOrNull()
                            else -> null
                        }
                        legacyEnabled?.let { enabled ->
                            edit.putInt(
                                PreferKey.bookshelfReadProgressMode,
                                if (enabled) {
                                    BookshelfReadProgressMode.STANDARD
                                } else {
                                    BookshelfReadProgressMode.HIDDEN
                                },
                            )
                        }
                    }
                    if (!BackupConfig.ignoreReadConfig &&
                        PreferKey.showReadTitleChapterNameOnly !in map
                    ) {
                        edit.putBoolean(PreferKey.showReadTitleChapterNameOnly, false)
                    }
                    if (PreferKey.showExploreCategories !in map) {
                        edit.putBoolean(PreferKey.showExploreCategories, false)
                    }
                    if (BackupConfig.keyIsNotIgnore(PreferKey.coverTitleAdaptive) &&
                        PreferKey.coverTitleAdaptive !in map
                    ) {
                        edit.putBoolean(PreferKey.coverTitleAdaptive, true)
                    }
                    if (BackupConfig.keyIsNotIgnore(PreferKey.coverCustomFontSize) &&
                        PreferKey.coverCustomFontSize !in map
                    ) {
                        edit.putBoolean(PreferKey.coverCustomFontSize, false)
                    }
                    if (BackupConfig.keyIsNotIgnore(PreferKey.coverFont) && PreferKey.coverFont !in map) {
                        edit.remove(PreferKey.coverFont)
                    }
                    if (PreferKey.myMoreItems !in map) edit.remove(PreferKey.myMoreItems)
                    if (PreferKey.autoBackup !in map) edit.putBoolean(PreferKey.autoBackup, true)
                    if (PreferKey.autoBackupWebDav !in map) edit.putBoolean(PreferKey.autoBackupWebDav, true)
                    if (PreferKey.autoBackupIntervalDays !in map) edit.putInt(PreferKey.autoBackupIntervalDays, 1)
                    if ("readRecordSimpleLayout" !in map) edit.putBoolean("readRecordSimpleLayout", true)
                    if ("readRecordUseDays" !in map) edit.putBoolean("readRecordUseDays", false)
                    if ("readRecordShowSeconds" !in map) edit.putBoolean("readRecordShowSeconds", true)
                    if ("readRecordFixedCard" !in map) edit.putBoolean("readRecordFixedCard", true)
                    for (key in listOf(PreferKey.readRecordCover, PreferKey.readRecordCoverDark)) {
                        if (key !in map && BackupConfig.keyIsNotIgnore(key)) edit.remove(key)
                    }
                    if (!BackupConfig.ignoreReadConfig && PreferKey.mangaRightToLeft !in map) {
                        edit.putBoolean(PreferKey.mangaRightToLeft, false)
                    }
                    edit.apply()
                    settings.markStoresTouched()
                    EntryPointAccessors.fromApplication(
                        appCtx,
                        MangaReaderPreferencesEntryPoint::class.java,
                    ).mangaReaderPreferencesStore().update { current ->
                        current.restoreLegacyPreferenceMap(effectivePreferences.orEmpty())
                    }
                    EntryPointAccessors.fromApplication(appCtx,CacheExportPreferencesEntryPoint::class.java).cacheExportPreferences()
                        .update { it.restoreLegacyPreferenceMap(effectivePreferences.orEmpty()) }
                    settings.editDefault().apply {
                        CACHE_EXPORT_KEYS.forEach(::remove)
                        MANGA_READER_LEGACY_KEYS.forEach(::remove)
                    }.apply()
                }
                restoredVideoPreferences?.let { map ->
                    settings.editVideo().apply {
                        map.forEach { (key, value) ->
                            when (value) {
                                is Int -> putInt(key, value)
                                is Boolean -> putBoolean(key, value)
                                is Long -> putLong(key, value)
                                is Float -> putFloat(key, value)
                                is String -> putString(key, value)
                                is Set<*> -> {
                                    @Suppress("UNCHECKED_CAST")
                                    putStringSet(key, value as Set<String>)
                                }
                            }
                        }
                        apply()
                    }
                }
                // These are the defaults published by the post-commit reader refresh.
                // Persist absent values before commit so a cold start observes the same state.
                val defaults = settings.editDefault()
                if (!appCtx.defaultSharedPreferences.contains(PreferKey.autoReadSpeed)) defaults.putInt(PreferKey.autoReadSpeed, 46)
                if (!appCtx.defaultSharedPreferences.contains(PreferKey.comicStyleSelect)) defaults.putInt(PreferKey.comicStyleSelect, 0)
                defaults.apply()
            },
        ) {
            currentCoroutineContext().ensureActive()
            retireBookTransfersForRestore(appDb)
            val restoredBookUrls = hashSetOf<String>()
            fileToListT<Book>(path, "bookshelf.json")?.let {
                it.forEach { book ->
                    if (lanTransfer) book.variable = null
                    book.upType()
                    book.normalizeLegacyPersistedCover()
                    book.customCoverUrl = book.customCoverUrl?.let { coverPath ->
                        remapRestoredCoverPath(coverPath, backupRoot, appCtx.externalFiles)
                    }
                    book.persistedCoverUrl = book.persistedCoverUrl?.let { coverPath ->
                        remapRestoredCoverPath(coverPath, backupRoot, appCtx.externalFiles)
                    }
                }
                it.filter { book -> book.isLocal }
                    .forEach { book ->
                        book.coverUrl = LocalBook.getCoverPath(book)
                    }
                val newBooks = arrayListOf<Book>()
                val ignoreLocalBook = BackupConfig.ignoreLocalBook
                it.forEach { book ->
                    if (ignoreLocalBook && book.isLocal) {
                        return@forEach
                    }
                    restoredBookUrls.add(book.bookUrl)
                    if (lanTransfer) {
                        appDb.bookDao.upsertPreservingVariable(book)
                        return@forEach
                    }
                    if (appDb.bookDao.has(book.bookUrl)) {
                        try {
                            appDb.bookDao.update(book)
                        } catch (_: SQLiteConstraintException) {
                            appDb.bookDao.insert(book)
                        }
                    } else {
                        newBooks.add(book)
                    }
                }
                appDb.bookDao.insert(*newBooks.toTypedArray())
            }
            fileToListT<BookMemo>(path, "bookMemo.json")?.let { memos ->
                appDb.bookMemoDao.restore(memos.filter { it.bookUrl in restoredBookUrls })
            }
            fileToListT<Bookmark>(path, "bookmark.json")?.let {
                appDb.bookmarkDao.insert(*it.toTypedArray())
            }
            fileToListT<BookHighlight>(path, "highlight.json")?.let { highlights ->
                try {
                    applyLegacyHighlightStyles(File(path, "highlight.json").readText(), highlights)
                    applyLegacyHighlightOwners(highlights)
                    appDb.bookHighlightDao.insert(*highlights.toTypedArray())
                } catch (e: Exception) {
                    throw IllegalArgumentException("highlight.json 恢复失败", e)
                }
            }
            fileToListT<HighlightRule>(path, "highlightRule.json")?.let { rules ->
                try {
                    appDb.highlightRuleDao.replaceAll(rules.map(HighlightRule::normalizeForRestore))
                } catch (e: Exception) {
                    throw IllegalArgumentException("highlightRule.json 恢复失败", e)
                }
            }
            fileToListT<BookGroup>(path, "bookGroup.json")?.let { groups ->
                groups.forEach { group ->
                    group.cover = group.cover?.let { coverPath ->
                        remapRestoredCoverPath(coverPath, backupRoot, appCtx.externalFiles)
                    }
                }
                appDb.bookGroupDao.insert(*groups.toTypedArray())
            }
            File(path, "bookSource.json").takeIf { it.isFile }?.let { file ->
                val batch = ArrayList<BookSource>(50)
                forEachRestoredBookSource(file) { source ->
                    batch.add(source)
                    if (batch.size == 50) {
                        appDb.bookSourceDao.insert(*batch.toTypedArray())
                        batch.clear()
                    }
                }
                if (batch.isNotEmpty()) appDb.bookSourceDao.insert(*batch.toTypedArray())
            }
            fileToListT<ReplaceRule>(path, "replaceRule.json")?.let {
                val insertedIds = appDb.replaceRuleDao.insert(*it.toTypedArray())
                committedSamples = it to insertedIds
            }
            fileToListT<SearchKeyword>(path, "searchHistory.json")?.let {
                appDb.searchKeywordDao.insert(*it.toTypedArray())
            }
            fileToListT<RuleSub>(path, "sourceSub.json")?.let {
                appDb.ruleSubDao.insert(*it.toTypedArray())
            }
            fileToListT<TxtTocRule>(path, "txtTocRule.json")?.let {
                appDb.txtTocRuleDao.insert(*it.toTypedArray())
            }
            fileToListT<HttpTTS>(path, "httpTTS.json")?.let {
                appDb.httpTTSDao.insert(*it.toTypedArray())
            }
            fileToListT<DictRule>(path, "dictRule.json")?.let {
                appDb.dictRuleDao.insert(*it.toTypedArray())
            }
            fileToListT<KeyboardAssist>(path, "keyboardAssists.json")?.let {
                appDb.keyboardAssistsDao.deleteAll() //先删除所有,保证和备份数据一样
                appDb.keyboardAssistsDao.insert(*it.toTypedArray())
            }
            fileToListT<ReadRecord>(path, "readRecord.json")?.let {
                it.forEach { readRecord ->
                    // Older backups omitted the device id; treat them as local records.
                    val normalizedRecord = if (readRecord.deviceId.isBlank()) {
                        readRecord.copy(deviceId = androidId)
                    } else {
                        readRecord
                    }
                    val restoredRecord = normalizedRecord.copy(coverUrl = remapReadRecordCover(
                        normalizedRecord.coverUrl?.let { remapRestoredCoverPath(it, File(path), appCtx.externalFiles) },
                        File(path), appCtx.externalFiles,
                    ))
                    appDb.runInTransaction {
                        val current = appDb.readRecordDao.getRecord(restoredRecord.deviceId, restoredRecord.bookName, restoredRecord.author)
                        appDb.readRecordDao.insert(mergeRestoredReadRecord(
                            current, restoredRecord, restoredRecord.deviceId == androidId,
                        ))
                    }
                }
            }
            restoredServers?.let { servers ->
                appDb.serverDao.insert(*servers.toTypedArray())
            }
            restoredCookies?.filter { cookie -> cookie.url.isNotBlank() }?.forEach { cookie ->
                if ('|' in cookie.url) {
                    appDb.cookieDao.insert(cookie)
                } else {
                    CookieStore.restoreCookie(cookie.url, cookie.cookie, publishMemory = false)?.let { saved ->
                        committedCookieMemory[saved.url] = saved.cookie
                    }
                }
            }
            restoredRuntimeSourceCaches?.takeIf { it.isNotEmpty() }?.let { caches ->
                // REPLACE updates matching keys while leaving local-only runtime entries intact.
                appDb.cacheDao.insert(*caches.toTypedArray())
            }
            readerConfiguration.coverRule?.let(BookCover::persistRestoredCoverRule)
            // Reject deferred violations inside the block: older framework drivers can
            // retain a native transaction when COMMIT itself fails on a deferred FK.
            appDb.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { violations ->
                if (violations.moveToFirst()) {
                    throw SQLiteConstraintException("关联数据不完整，已取消恢复以保留原数据。").apply {
                        initCause(IllegalStateException("foreign_key_check: ${violations.getString(0)}"))
                    }
                }
            }
            currentCoroutineContext().ensureActive()
        }
        // Publish non-database state only after the entire Room transaction commits.
        committedCookieMemory.forEach { (domain, cookie) ->
            CacheManager.putMemory("${domain}_cookie", cookie)
        }
        if (!restoredRuntimeSourceCaches.isNullOrEmpty()) AppCacheManager.clearSourceVariables()
        // DirectLinkUpload was removed; obsolete backup entries must not revive its cache.
        readerConfiguration.coverRule?.let { json ->
            BookCover.publishRestoredCoverRule(json)
        }
        if (readerConfiguration.styles != null) ReadBookConfig.initConfigs()
        if (readerConfiguration.shareStyle != null) ReadBookConfig.initShareConfig()
        //AppWebDav.downBgs()
        ReadBookConfig.apply {
            comicStyleSelect = appCtx.getPrefInt(PreferKey.comicStyleSelect)
            readStyleSelect = appCtx.getPrefInt(PreferKey.readStyleSelect)
            shareLayout = appCtx.getPrefBoolean(PreferKey.shareLayout)
            hideStatusBar = appCtx.getPrefBoolean(PreferKey.hideStatusBar)
            hideNavigationBar = appCtx.getPrefBoolean(PreferKey.hideNavigationBar)
            autoReadSpeed = appCtx.getPrefInt(PreferKey.autoReadSpeed, 46)
        }
        appCtx.toastOnUi(R.string.restore_success)
        withContext(Main) {
            delay(100)
            if (!BuildConfig.DEBUG) {
                LauncherIconHelp.changeIcon(appCtx.getPrefString(PreferKey.launcherIcon))
            }
            WallpaperTheme.syncWithPreferences(appCtx)
            ThemeConfig.applyDayNight(appCtx)
        }
    }

    private inline fun <reified T> fileToListT(path: String, fileName: String): List<T>? {
        try {
            val file = File(path, fileName)
            if (file.exists()) {
                LogUtils.d(TAG, "阅读恢复备份 $fileName 文件大小 ${file.length()}")
                FileInputStream(file).use {
                    return GSON.fromJsonArray<T>(it).getOrThrow().also { list ->
                        LogUtils.d(TAG, "阅读恢复备份 $fileName 列表大小 ${list.size}")
                    }
                }
            } else {
                LogUtils.d(TAG, "阅读恢复备份 $fileName 文件不存在")
            }
        } catch (e: Exception) {
            AppLog.put("$fileName\n读取解析出错\n${e.localizedMessage}", e)
            throw IllegalArgumentException("$fileName 备份数据无法解析", e)
        }
        return null
    }

    private fun applyLegacyHighlightOwners(highlights: List<BookHighlight>) {
        highlights.forEach { highlight ->
            val bookUrl = highlight.bookUrl.ifBlank {
                appDb.bookDao.getBook(highlight.bookName, highlight.bookAuthor)?.bookUrl.orEmpty()
            }
            val chapterUrl = highlight.chapterUrl.ifBlank {
                appDb.bookChapterDao.getChapter(bookUrl, highlight.chapterIndex)
                    ?.takeIf { it.title == highlight.chapterName }
                    ?.url
                    .orEmpty()
            }
            highlight.bindLegacyOwner(bookUrl, chapterUrl)
        }
    }

}

internal fun applyLegacyHighlightStyles(json: String, highlights: List<BookHighlight>) {
    val legacy = GSON.fromJsonObject<List<Map<String, Any?>>>(json).getOrNull()
    highlights.forEachIndexed { index, highlight ->
        if (highlight.style.isNullOrBlank()) {
            val raw = legacy?.getOrNull(index)
            val fill = (raw?.get("bgColor") as? Number)?.toInt() ?: 0
            val textColor = (raw?.get("textColor") as? Number)?.toInt() ?: 0
            if (fill != 0 || textColor != 0) {
                highlight.applyStyle(HighlightStyle(fill = fill, textColor = textColor))
            }
        }
    }
}
