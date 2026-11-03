package io.legado.app.help.storage

import android.database.sqlite.SQLiteDatabase
import android.content.SharedPreferences
import dagger.hilt.android.EntryPointAccessors
import io.legado.app.constant.PreferKey
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.data.preferences.CacheExportPreferencesEntryPoint
import io.legado.app.data.preferences.options
import io.legado.app.data.preferences.proto.CacheExportSettingsProto.CacheExportSettings
import io.legado.app.data.repository.MangaReaderPreferences
import io.legado.app.data.repository.MangaReaderPreferencesEntryPoint
import io.legado.app.model.VideoPlay.VIDEO_PREF_NAME
import io.legado.app.utils.defaultSharedPreferences
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.model.BookCover
import io.legado.app.utils.GSON
import io.legado.app.utils.externalFiles
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

/** Opt-in host probe. It stops the real restore without production hooks. */
@OptIn(ExperimentalSerializationApi::class)
@RunWith(AndroidJUnit4::class)
class RestoreFileDeathProbe {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val args get() = InstrumentationRegistry.getArguments()
    private fun root(): File {
        assumeTrue("Use the host file death controller", args.getString("fileRestoreDeath") == "yes")
        val id = requireNotNull(args.getString("fileRestoreId"))
        require(UUID.fromString(id).toString() == id)
        require(args.getString("fileRestoreCheckpoint") in listOf("before", "after"))
        return File(context.cacheDir, "compat-file-restore/$id")
    }
    private fun book(root: File) = Book(bookUrl = "https://files-death-${root.name}.invalid/book", name = "Files ${root.name}", durChapterIndex = 7)
    private fun trigger(root: File) = "restore_file_death_${root.name.replace("-", "")}"
    private fun files() = listOf(File(ReadBookConfig.configFilePath), File(ReadBookConfig.shareConfigFilePath))
    private fun settingsOnly() = args.getString("fileRestoreSettingsOnly") == "yes"
    private fun media(root: File) = (if (settingsOnly()) emptyList() else listOf("covers", readRecordCoverDirectory)).map { File(context.externalFiles, "$it/death-${root.name}.bin") }
    private fun fontDirectory() = File(context.externalFiles, "font")
    private fun save(file: File, bytes: ByteArray) {
        FileOutputStream(file).use { it.write(bytes); it.fd.sync() }
    }
    private fun json(file: File, data: JSONObject) = save(file, data.toString().toByteArray())
    private fun hash(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    private fun committed(id: String): Boolean = SQLiteDatabase.openDatabase(context.getDatabasePath("legado.db").path,
        null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { database ->
        database.rawQuery("SELECT value FROM caches WHERE key = ?", arrayOf(RestoreFileRecovery.COMMIT_KEY))
            .use { it.moveToFirst() && it.getString(0) == id }
    }

    private fun exports() = EntryPointAccessors.fromApplication(context, CacheExportPreferencesEntryPoint::class.java).cacheExportPreferences()
    private fun mangas() = EntryPointAccessors.fromApplication(context, MangaReaderPreferencesEntryPoint::class.java).mangaReaderPreferencesStore()
    private fun settingsKey(root: File) = "restore_file_death_${root.name}"
    private fun video() = context.getSharedPreferences(VIDEO_PREF_NAME, 0)
    private fun samples() = context.getSharedPreferences("replace_preview", 0)
    private fun rule(before: JSONObject) = ReplaceRule(id = before.getLong("ruleId"), name = "Old ${before.getString("id")}", isEnabled = false)
    private fun prepareFont(root: File, input: File, before: JSONObject) {
        val directory = fontDirectory()
        before.put("fontDirectoryExisted", directory.exists())
        directory.listFiles().orEmpty().filter(File::isFile).forEach {
            before.put("fontOriginal:${it.path}", hash(it))
        }
        val candidates = checkNotNull(File("/system/fonts").listFiles()).asSequence()
            .filter { it.isFile && it.extension.equals("ttf", ignoreCase = true) && it.length() > 0L && it.canRead() }
            .distinctBy(::hash)
            .take(2)
            .toList()
        require(candidates.size == 2) { "Two distinct readable system fonts are required" }
        val old = File(directory, "death-old-${root.name}.ttf")
        check(directory.mkdirs() || directory.isDirectory)
        candidates[0].copyTo(old, overwrite = true)
        val replacement = File(input, BookCover.fontBackupFileName)
        candidates[1].copyTo(replacement, overwrite = true)
        check(hash(old) != hash(replacement))
        check(context.defaultSharedPreferences.edit().putString(PreferKey.coverFont, old.path).commit())
        before.put("fontOldPath", old.path).put("fontOldHash", hash(old)).put("fontNewHash", hash(replacement))
    }
    private fun put(editor: SharedPreferences.Editor, values: Map<String, *>) {
        values.forEach { (key, value) -> when (value) {
            is String -> editor.putString(key, value); is Int -> editor.putInt(key, value); is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value); is Boolean -> editor.putBoolean(key, value)
            is Set<*> -> { @Suppress("UNCHECKED_CAST") editor.putStringSet(key, value as Set<String>) }
        } }
    }
    private suspend fun prepareSettings(root: File, input: File, before: JSONObject) {
        if (!settingsOnly()) prepareFont(root, input, before)
        save(File(root, "original-export.pb"), exports().snapshotForRestore())
        save(File(root, "original-manga.pb"), ProtoBuf.encodeToByteArray(MangaReaderPreferences.serializer(), mangas().requireCurrent()))
        listOf(context.defaultSharedPreferences, video(), samples()).forEachIndexed { index, preferences ->
            writePreferenceSnapshot(context, root.path, "original-prefs-$index") { put(this, preferences.all) }
        }
        before.put("ruleId", UUID.randomUUID().mostSignificantBits.and(Long.MAX_VALUE).coerceAtLeast(1))
            .put("exportFormat", exports().current().format).put("mangaCount", mangas().requireCurrent().preDownloadCount)
        json(File(root, "baseline.json"), before)
        val rule = rule(before)
        appDb.replaceRuleDao.insert(rule)
        check(context.defaultSharedPreferences.edit().putString(settingsKey(root), "old")
            .putString(PreferKey.language, "zh").putInt(PreferKey.fontScale, 12).commit())
        check(video().edit().putString(settingsKey(root), "old-video").commit())
        check(samples().edit().putString("sample_${rule.id}", "old-sample").commit())
        writePreferenceSnapshot(context, input.path, "config") {
            putString(settingsKey(root), "new")
            putString(PreferKey.language, "en"); putInt(PreferKey.fontScale, 16)
            putInt(PreferKey.exportType, (before.getInt("exportFormat") + 1) % 3)
            putInt(PreferKey.mangaPreDownloadNum, if (before.getInt("mangaCount") == 23) 22 else 23)
            putString(PreferKey.coverFont, "restore-fixture-font")
        }
        writePreferenceSnapshot(context, input.path, "videoConfig") { putString(settingsKey(root), "new-video") }
        val replacement = rule.copy(name = "New ${root.name}").apply { previewText = "new-sample" }
        File(input, "replaceRule.json").writeText(GSON.toJson(listOf(replacement)))
    }
    private suspend fun settingsActive(root: File, before: JSONObject) =
        context.defaultSharedPreferences.getString(settingsKey(root), null) == "new" &&
            video().getString(settingsKey(root), null) == "new-video" &&
            samples().getString("sample_${before.getLong("ruleId")}", null) == "new-sample" &&
            exports().current().format == (before.getInt("exportFormat") + 1) % 3 &&
            mangas().requireCurrent().preDownloadCount == if (before.getInt("mangaCount") == 23) 22 else 23

    private fun fontActive(before: JSONObject): Boolean {
        if (settingsOnly()) return true
        val path = context.defaultSharedPreferences.getString(PreferKey.coverFont, null) ?: return false
        val installed = File(path)
        return installed.isFile && hash(installed) == before.getString("fontNewHash")
    }

    @Test fun holdRealRestoreAtCheckpoint() = runBlocking {
        val root = root().apply { check(mkdirs()) }
        val input = File(root, "input").apply { check(mkdir()) }
        val ignored = HashMap(BackupConfig.ignoreConfig)
        val before = JSONObject().put("id", root.name).put("pid", Process.myPid()).put("uid", Process.myUid())
            .put("lastBackup", LocalConfig.lastBackup)
        assertTrue(Process.myUid() >= 10000)
        assertFalse(RestoreFileRecovery.hasPending(context))
        files().forEachIndexed { index, file ->
            before.put("existed$index", file.isFile)
            if (file.isFile) save(File(root, "original$index"), file.readBytes())
        }
        media(root).forEachIndexed { index, file ->
            check(!file.exists()); before.put("directory$index", file.parentFile!!.exists())
        }
        // Metadata is durable before fixture-owned live data is installed.
        json(File(root, "baseline.json"), before)
        try {
            prepareSettings(root, input, before)
            if (!settingsOnly()) {
            val styles = GSON.toJsonTree(ReadBookConfig.configList).asJsonArray
            val share = GSON.toJsonTree(ReadBookConfig.shareConfig).asJsonObject
            styles.first().asJsonObject.addProperty("restoreFileProbe", "old-${root.name}")
            share.addProperty("restoreFileProbe", "old-${root.name}")
            files().forEachIndexed { index, file ->
                val bytes = (if (index == 0) styles.toString() else share.toString()).toByteArray()
                save(File(root, "old$index"), bytes); save(file, bytes)
            }
            styles.first().asJsonObject.addProperty("restoreFileProbe", "new-${root.name}")
            share.addProperty("restoreFileProbe", "new-${root.name}")
            save(File(input, ReadBookConfig.configFileName), styles.toString().toByteArray())
            save(File(input, ReadBookConfig.shareConfigFileName), share.toString().toByteArray())
            }
            media(root).forEach { file ->
                check(file.parentFile!!.mkdirs() || file.parentFile!!.isDirectory); file.writeText("old-${root.name}")
                File(input, "${file.parentFile!!.name}/${file.name}").apply { parentFile!!.mkdirs(); writeText("new-${root.name}") }
            }
            appDb.bookDao.insert(book(root))
            File(input, "bookshelf.json").writeText(GSON.toJson(listOf(book(root).copy(durChapterIndex = 29))))
            BackupConfig.ignoreConfig["readConfig"] = false
            LocalConfig.lastBackup = 1234L
            val checkpoint = args.getString("fileRestoreCheckpoint")!!
            if (checkpoint == "before") {
                // Marker insert follows all file activations but precedes commit.
                appDb.openHelper.writableDatabase.execSQL("""CREATE TRIGGER ${trigger(root)} BEFORE INSERT ON caches
                    WHEN NEW.key = '${RestoreFileRecovery.COMMIT_KEY}' BEGIN
                    SELECT sum(n) FROM (WITH RECURSIVE seq(n) AS
                      (SELECT 1 UNION ALL SELECT n+1 FROM seq WHERE n<1000000000) SELECT n FROM seq); END""")
            }
            json(File(root, "ready.json"), JSONObject().put("id", root.name).put("pid", Process.myPid())
                .put("uid", Process.myUid()).put("checkpoint", checkpoint).put("settingsOnly", settingsOnly()))
            // run-as does not share the application's emulated-storage mount namespace.
            // Observe owned media from this normal app UID, publish only fixture hashes privately.
            Thread {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45)
                var previous = ""
                while (System.nanoTime() < deadline) {
                    val mediaActive = runCatching {
                        media(root).all { it.readText() == "new-${root.name}" }
                    }.getOrDefault(false)
                    val styleActive = runCatching {
                        settingsOnly() || files().all { it.readBytes().contentEquals(File(input, it.name).readBytes()) }
                    }.getOrDefault(false)
                    val font = runCatching { fontActive(before) }.getOrDefault(false)
                    val settings = runCatching { runBlocking { settingsActive(root, before) } }.getOrDefault(false)
                    val diagnostic = JSONObject().put("media", mediaActive).put("styles", styleActive)
                        .put("font", font).put("settings", settings)
                        .put("defaultSetting", context.defaultSharedPreferences.getString(settingsKey(root), null))
                        .put("videoSetting", video().getString(settingsKey(root), null))
                        .put("sampleSetting", samples().getString("sample_${before.getLong("ruleId")}", null))
                        .put("coverFont", context.defaultSharedPreferences.getString(PreferKey.coverFont, null))
                        .put("fontExpected", before.optString("fontNewHash"))
                        .put("exportFormat", runCatching { runBlocking { exports().current().format } }.getOrNull())
                        .put("exportExpected", (before.getInt("exportFormat") + 1) % 3)
                        .put("mangaCount", runCatching { runBlocking { mangas().requireCurrent().preDownloadCount } }.getOrNull())
                        .put("mangaExpected", if (before.getInt("mangaCount") == 23) 22 else 23)
                    val encoded = diagnostic.toString()
                    if (encoded != previous) {
                        save(File(root, "barrier-diag.json"), encoded.toByteArray())
                        previous = encoded
                    }
                    val active = runCatching {
                        mediaActive && styleActive && font && settings
                    }.getOrDefault(false)
                    if (active) {
                        json(File(root, "activated.json"), JSONObject().put("id", root.name)
                            .put("normal_uid", Process.myUid()).put("media_files", media(root).size)
                            .put("style_files", if (settingsOnly()) 0 else 2).put("font_installed", !settingsOnly())
                            .put("settings_observed", true))
                        break
                    }
                    Thread.sleep(10)
                }
            }.apply { isDaemon = true; start() }
            if (checkpoint == "before") Restore.restoreLocked(input.path)
            else {
                val executor = Executors.newSingleThreadExecutor()
                val dispatcher = object : CoroutineDispatcher() {
                    override fun dispatch(context: CoroutineContext, block: Runnable) {
                        executor.execute {
                            val transaction = RestoreFileTransaction.read(File(this@RestoreFileDeathProbe.context.filesDir, "restore-files-v1.bin"), this@RestoreFileDeathProbe.context.externalFiles)
                            val transactionId = transaction?.id ?: RestoreSettingsTransaction.read(this@RestoreFileDeathProbe.context)?.id
                            if (transactionId != null && committed(transactionId)) {
                                json(File(root, "committed.json"), JSONObject().put("id", root.name).put("transaction", transactionId))
                                check(CountDownLatch(1).await(60, TimeUnit.SECONDS)) { "Host did not kill at committed barrier" }
                            }
                            block.run()
                        }
                    }
                }
                try { withContext(dispatcher) { Restore.restoreLocked(input.path) } }
                finally { executor.shutdownNow() }
            }
            fail("Host must kill the actual restore at the requested checkpoint")
        } finally {
            // SIGKILL skips this; ordinary failures still remove fixture changes.
            cleanup(root)
            BackupConfig.ignoreConfig.clear(); BackupConfig.ignoreConfig.putAll(ignored)
        }
    }

    @Test fun verifyAutomaticStartupRecovery() = runBlocking {
        val root = root()
        val before = JSONObject(File(root, "baseline.json").readText())
        val chapter = if (args.getString("fileRestoreCheckpoint") == "after") 29 else 7
        val changed = chapter == 29
        // No explicit recovery here: Application.onCreate must have resolved it.
        withTimeout(15_000) { while (RestoreFileRecovery.hasPending(context)) delay(20) }
        backupRestoreMutex.withLock {
            try {
                assertNotEquals(before.getInt("pid"), Process.myPid())
                assertEquals(chapter, appDb.bookDao.getBook(book(root).bookUrl)?.durChapterIndex)
                media(root).forEach { assertEquals("${if (chapter == 29) "new" else "old"}-${root.name}", it.readText()) }
                if (!settingsOnly()) files().forEachIndexed { index, file ->
                    val expected = if (chapter == 29) File(root, "input/${file.name}") else File(root, "old$index")
                    assertArrayEquals(expected.readBytes(), file.readBytes())
                }
                if (!settingsOnly()) {
                    val oldPath = before.getString("fontOldPath")
                    val restoredPath = context.defaultSharedPreferences.getString(PreferKey.coverFont, null)
                    if (changed) {
                        assertNotEquals(oldPath, restoredPath)
                        assertEquals(before.getString("fontNewHash"), hash(File(checkNotNull(restoredPath))))
                    } else {
                        assertEquals(oldPath, restoredPath)
                        assertEquals(before.getString("fontOldHash"), hash(File(checkNotNull(restoredPath))))
                    }
                }
                assertNull(appDb.cacheDao.get(RestoreFileRecovery.COMMIT_KEY))
                assertEquals(if (changed) "new" else "old", context.defaultSharedPreferences.getString(settingsKey(root), null))
                assertEquals(if (changed) "new-video" else "old-video", video().getString(settingsKey(root), null))
                assertEquals(if (changed) "new-sample" else "old-sample", samples().getString("sample_${before.getLong("ruleId")}", null))
                val originalExport = CacheExportSettings.parseFrom(File(root, "original-export.pb").readBytes()).options()
                val originalManga = ProtoBuf.decodeFromByteArray(MangaReaderPreferences.serializer(), File(root, "original-manga.pb").readBytes())
                assertEquals(if (changed) originalExport.copy(format = (originalExport.format + 1) % 3) else originalExport, exports().current())
                assertEquals(if (changed) originalManga.copy(preDownloadCount = if (originalManga.preDownloadCount == 23) 22 else 23) else originalManga, mangas().requireCurrent())
                assertEquals("${if (changed) "New" else "Old"} ${root.name}", appDb.replaceRuleDao.all.first { it.id == before.getLong("ruleId") }.name)
                if (changed) assertTrue(LocalConfig.lastBackup > 1234L) else assertEquals(1234L, LocalConfig.lastBackup)
                val configuration = context.applicationContext.resources.configuration
                assertEquals(if (changed) "en" else "zh", configuration.locales[0].language)
                assertEquals(if (changed) 1.6f else 1.2f, configuration.fontScale, 0.01f)
                json(File(root, "recovered.json"), JSONObject().put("id", root.name).put("pid", Process.myPid())
                    .put("bookChapter", chapter).put("automatic_startup", true).put("lastBackup", LocalConfig.lastBackup)
                    .put("private_files", if (settingsOnly()) 0 else files().size).put("media_directories", media(root).size)
                    .put("preferences_checked", true).put("data_stores", 2).put("preview_samples", 1)
                    .put("font_checked", !settingsOnly())
                    .put("font_hash", if (settingsOnly()) "" else hash(File(checkNotNull(
                        context.defaultSharedPreferences.getString(PreferKey.coverFont, null)))))
                    .put("font_expected_hash", if (settingsOnly()) "" else
                        before.getString(if (changed) "fontNewHash" else "fontOldHash"))
                    .put("locale_and_font_scale_verified", true))
            } finally { cleanup(root) }
        }
    }

    private fun cleanup(root: File) {
        val original = File(root, "baseline.json")
        if (!original.isFile) return
        val before = JSONObject(original.readText())
        appDb.openHelper.writableDatabase.execSQL("DROP TRIGGER IF EXISTS ${trigger(root)}")
        RestoreFileRecovery.recover(context, cleanup = true)
        appDb.bookDao.delete(book(root))
        if (before.has("ruleId")) appDb.replaceRuleDao.delete(rule(before))
        runBlocking {
            File(root, "original-export.pb").takeIf { it.isFile }?.let { exports().restoreSnapshot(it.readBytes()) }
            File(root, "original-manga.pb").takeIf { it.isFile }?.let {
                val originalManga = ProtoBuf.decodeFromByteArray(MangaReaderPreferences.serializer(), it.readBytes())
                mangas().update { originalManga }
            }
        }
        val preferences = listOf(context.defaultSharedPreferences, video(), samples())
        val originalPreferences = preferences.mapIndexed { index, target ->
            readPreferenceSnapshot(context, root.path, "original-prefs-$index")?.also { map ->
                target.edit().clear().apply { put(this, map); check(commit()) }
            }
        }
        files().forEachIndexed { index, file ->
            if (before.getBoolean("existed$index")) save(file, File(root, "original$index").readBytes())
            else check(!file.exists() || file.delete())
        }
        ReadBookConfig.initConfigs(); ReadBookConfig.initShareConfig()
        media(root).forEachIndexed { index, file ->
            check(!file.exists() || file.delete())
            if (!before.getBoolean("directory$index") && file.parentFile!!.list()?.isEmpty() == true) file.parentFile!!.delete()
        }
        val directory = fontDirectory()
        val originalFonts = before.keys().asSequence().filter { it.startsWith("fontOriginal:") }
            .map { it.removePrefix("fontOriginal:") }.toSet()
        directory.listFiles().orEmpty().filter { it.isFile && it.path !in originalFonts }.forEach {
            check(it.delete())
        }
        if (before.has("fontDirectoryExisted") && !before.getBoolean("fontDirectoryExisted") &&
            directory.list()?.isEmpty() == true) check(directory.delete())
        ReadBookConfig.apply {
            readStyleSelect = context.defaultSharedPreferences.getInt(PreferKey.readStyleSelect, 0)
            comicStyleSelect = context.defaultSharedPreferences.getInt(PreferKey.comicStyleSelect, 0)
            autoReadSpeed = context.defaultSharedPreferences.getInt(PreferKey.autoReadSpeed, 46)
        }
        originalPreferences[0]?.let { map -> context.defaultSharedPreferences.edit().clear().apply { put(this, map); check(commit()) } }
        LocalConfig.lastBackup = before.getLong("lastBackup")
    }
}
