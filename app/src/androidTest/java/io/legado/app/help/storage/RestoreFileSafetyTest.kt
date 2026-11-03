package io.legado.app.help.storage

import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.preferences.CacheExportPreferencesEntryPoint
import io.legado.app.data.repository.MangaReaderPreferencesEntryPoint
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.model.BookCover
import io.legado.app.utils.GSON
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.externalFiles
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RestoreFileSafetyTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val id = UUID.randomUUID().toString()
    private val root = File(context.cacheDir, "restore-files-fixture-$id")
    private val book = Book(bookUrl = "https://restore-files-$id.invalid/book", name = "Restore files $id", durChapterIndex = 7)
    private val preferences = context.defaultSharedPreferences
    private val originalPreferences = HashMap(preferences.all)
    private val originalIgnore = HashMap(BackupConfig.ignoreConfig)
    private val originalLastBackup = LocalConfig.lastBackup
    private val originalReader = listOf(ReadBookConfig.readStyleSelect, ReadBookConfig.comicStyleSelect, ReadBookConfig.autoReadSpeed)
    private val originalBooleans = listOf(ReadBookConfig.shareLayout, ReadBookConfig.hideStatusBar, ReadBookConfig.hideNavigationBar)
    private val configurationFiles = listOf(File(ReadBookConfig.configFilePath), File(ReadBookConfig.shareConfigFilePath))
    private val originalFiles = configurationFiles.associateWith { if (it.isFile) it.readBytes() else null }
    private val originalStyles = ReadBookConfig.configList.toList()
    private val originalShareStyle = ReadBookConfig.shareConfig
    private val fontDirectory = File(context.externalFiles, "font")
    private val mediaDirectories = listOf("covers", readRecordCoverDirectory, "font").map { File(context.externalFiles, it) }
    private val directoriesExisted = mediaDirectories.associateWith { it.exists() }
    private val originalMedia = mediaHashes()
    private val exports = EntryPointAccessors.fromApplication(context, CacheExportPreferencesEntryPoint::class.java).cacheExportPreferences()
    private val manga = EntryPointAccessors.fromApplication(context, MangaReaderPreferencesEntryPoint::class.java).mangaReaderPreferencesStore()
    private val savedExport = runBlocking { exports.current() }
    private val savedManga = runBlocking { manga.current() }
    private val ownedMedia = mutableListOf<File>()
    private var unreadable: File? = null
    private var newFontHash: String? = null
    private val trigger = "restore_files_${id.replace("-", "")}"
    private val cacheKey = "v_restore_files_$id"

    @Before fun setup() {
        assertTrue(Process.myUid() >= 10000)
        assertFalse(RestoreFileRecovery.hasPending(context))
        check(root.mkdirs())
        File(root, "bookshelf.json").writeText(GSON.toJson(listOf(book.copy(durChapterIndex = 29))))
        appDb.bookDao.insert(book)
        LocalConfig.lastBackup = 1234
        BackupConfig.ignoreConfig["readConfig"] = false
        BackupConfig.ignoreConfig["coverConfig"] = false
        BackupConfig.ignoreConfig["ignoreSourceVariables"] = false
    }

    @After fun cleanup() = runBlocking {
        unreadable?.setReadable(true, false)
        backupRestoreMutex.withLock {
            appDb.openHelper.writableDatabase.execSQL("DROP TRIGGER IF EXISTS $trigger")
            RestoreFileRecovery.recover(context, cleanup = true)
            appDb.bookDao.delete(book); appDb.cacheDao.delete(cacheKey)
            exports.update { savedExport }; manga.update { savedManga }
            originalFiles.forEach { (file, bytes) ->
                if (bytes == null) file.delete()
                else if (!file.isFile || !file.readBytes().contentEquals(bytes)) writeRestoredConfiguration(file) { it.write(bytes) }
            }
            ReadBookConfig.configList.clear(); ReadBookConfig.configList.addAll(originalStyles)
            ReadBookConfig.shareConfig = originalShareStyle
            preferences.edit().remove(PreferKey.readStyleSelect).remove(PreferKey.comicStyleSelect).remove(PreferKey.autoReadSpeed).commit()
            ReadBookConfig.readStyleSelect = originalReader[0]; ReadBookConfig.comicStyleSelect = originalReader[1]; ReadBookConfig.autoReadSpeed = originalReader[2]
            ReadBookConfig.shareLayout = originalBooleans[0]; ReadBookConfig.hideStatusBar = originalBooleans[1]; ReadBookConfig.hideNavigationBar = originalBooleans[2]
            preferences.edit().clear().apply {
                originalPreferences.forEach { (key, value) -> when (value) {
                    is String -> putString(key, value); is Int -> putInt(key, value); is Long -> putLong(key, value)
                    is Boolean -> putBoolean(key, value); is Float -> putFloat(key, value)
                    is Set<*> -> { @Suppress("UNCHECKED_CAST") putStringSet(key, value as Set<String>) }
                } }
                check(commit())
            }
            LocalConfig.lastBackup = originalLastBackup
            BackupConfig.ignoreConfig.clear(); BackupConfig.ignoreConfig.putAll(originalIgnore)
            ownedMedia.forEach { check(!it.exists() || it.delete()) }
            fontDirectory.listFiles()?.filter { it.isFile && it.path !in originalMedia && digest(it) == newFontHash }
                ?.forEach { check(it.delete()) }
            root.deleteRecursively()
            directoriesExisted.forEach { (directory, existed) ->
                if (!existed && directory.list()?.isEmpty() == true) check(directory.delete())
            }
            assertFalse(RestoreFileRecovery.hasPending(context))
            assertNull(appDb.cacheDao.get(RestoreFileRecovery.COMMIT_KEY))
            assertEquals(originalPreferences, preferences.all)
            assertEquals(originalMedia, mediaHashes())
        }
    }

    @Test fun unreadableMediaCannotFailAfterCommittingTheBook() = runBlocking {
        unreadable = File(root, "covers/restore-$id.bin").apply { parentFile!!.mkdirs(); writeText("fixture") }
        assertTrue(unreadable!!.setReadable(false, false)); assertFalse(unreadable!!.canRead())
        rejected { Restore.restoreLocked(root.path) }
    }

    @Test fun unreadableFontCannotFailAfterCommittingTheBook() = runBlocking {
        unreadable = File(root, BookCover.fontBackupFileName).apply { writeText("unreadable-font-$id") }
        assertTrue(unreadable!!.setReadable(false, false)); assertFalse(unreadable!!.canRead())
        writePreferenceSnapshot(context, root.path, "config") { putString(PreferKey.coverFont, "restore-fixture-font") }
        rejected { Restore.restoreLocked(root.path) }
    }

    @Test fun markerFailureRollsBackAlreadyActivatedImagesAndStyles() = runBlocking {
        val styles = bundle()
        appDb.openHelper.writableDatabase.execSQL("""CREATE TRIGGER $trigger BEFORE INSERT ON caches
            WHEN NEW.key = '${RestoreFileRecovery.COMMIT_KEY}' BEGIN SELECT RAISE(ABORT, 'file marker failure'); END""")
        rejected { Restore.restoreLocked(root.path) }
        assertOldBundle(styles)
    }

    @Test fun earlierDatabaseFailureOnlyDiscardsPreparedFiles() = runBlocking {
        val styles = bundle()
        File(root, "runtimeSourceCache.json").writeText("""[{"key":"$cacheKey","value":"fixture","deadline":0}]""")
        appDb.openHelper.writableDatabase.execSQL("""CREATE TRIGGER $trigger BEFORE INSERT ON caches
            WHEN NEW.key = '$cacheKey' BEGIN SELECT RAISE(ABORT, 'before file activation'); END""")
        rejected { Restore.restoreLocked(root.path) }
        assertOldBundle(styles)
    }

    @Test fun successPublishesImagesStylesAndAValidatedFontWithTheBook() = runBlocking {
        val styles = bundle()
        val systemFont = checkNotNull(File("/system/fonts").listFiles()?.firstOrNull { it.isFile && it.extension == "ttf" && it.canRead() })
        val fontInput = File(root, BookCover.fontBackupFileName)
        systemFont.copyTo(fontInput); newFontHash = digest(fontInput)
        writePreferenceSnapshot(context, root.path, "config") { putString(PreferKey.coverFont, "restore-fixture-font") }
        Restore.restoreLocked(root.path)
        assertEquals(29, appDb.bookDao.getBook(book.bookUrl)!!.durChapterIndex)
        assertEquals("new", ownedMedia[0].readText()); assertEquals("added", ownedMedia[1].readText())
        assertEquals("new", ownedMedia[2].readText()); assertEquals("added", ownedMedia[3].readText())
        assertArrayEquals(styles.first, configurationFiles[0].readBytes()); assertArrayEquals(styles.second, configurationFiles[1].readBytes())
        assertEquals(newFontHash, digest(File(preferences.getString(PreferKey.coverFont, null)!!)))
        assertTrue(LocalConfig.lastBackup > 1234)
        assertFalse(RestoreFileRecovery.hasPending(context)); assertNull(appDb.cacheDao.get(RestoreFileRecovery.COMMIT_KEY))
    }

    @Test fun ignoredReadAssetsAreNotOpenedOrPublished() = runBlocking {
        BackupConfig.ignoreConfig["readConfig"] = true
        unreadable = File(root, "bg/ignored-$id.bin").apply { parentFile!!.mkdirs(); writeText("ignored") }
        assertTrue(unreadable!!.setReadable(false, false))
        File(root, ReadBookConfig.configFileName).writeText("[{")
        Restore.restoreLocked(root.path)
        assertEquals(29, appDb.bookDao.getBook(book.bookUrl)!!.durChapterIndex)
        originalFiles.forEach { (file, bytes) -> assertArrayEquals(bytes, file.takeIf { it.isFile }?.readBytes()) }
        assertFalse(File(context.externalFiles, "bg/${unreadable!!.name}").exists())
    }

    @Test fun cancellingAnObservedPartialCopyKeepsTheBookAndLiveFiles() = runBlocking {
        val source = File(root, "covers/cancel-$id.bin").apply { parentFile!!.mkdirs() }
        RandomAccessFile(source, "rw").use { it.setLength(64L * 1024 * 1024) }
        val restore = async(Dispatchers.IO) { Restore.restoreLocked(root.path) }
        try {
            withTimeout(15_000) {
                while (true) {
                    val transaction = RestoreFileTransaction.read(File(context.filesDir, "restore-files-v1.bin"), context.externalFiles)
                    val staged = transaction?.let { File(context.externalFiles, ".restore-files-${it.id}/new/covers/${source.name}") }
                    if (staged != null && staged.length() in 1 until source.length()) break
                    check(!restore.isCompleted) { "Copy finished before cancellation could be observed" }
                    delay(5)
                }
            }
        } finally { restore.cancelAndJoin() }
        assertTrue(restore.isCancelled)
        assertEquals(7, appDb.bookDao.getBook(book.bookUrl)!!.durChapterIndex)
        assertEquals(1234L, LocalConfig.lastBackup)
        assertFalse(File(context.externalFiles, "covers/${source.name}").exists())
        assertFalse(RestoreFileRecovery.hasPending(context))
    }

    private suspend fun rejected(action: suspend () -> Unit) {
        val before = HashMap(preferences.all)
        assertTrue("File/database failure must propagate", runCatching { action() }.isFailure)
        assertEquals("Failure committed the book", 7, appDb.bookDao.getBook(book.bookUrl)!!.durChapterIndex)
        assertEquals(before, preferences.all); assertEquals(1234L, LocalConfig.lastBackup)
        assertEquals(savedExport, exports.current()); assertEquals(savedManga, manga.current())
        assertFalse(RestoreFileRecovery.hasPending(context)); assertNull(appDb.cacheDao.get(RestoreFileRecovery.COMMIT_KEY))
    }

    private fun bundle(): Pair<ByteArray, ByteArray> {
        listOf("covers", readRecordCoverDirectory).forEach { name ->
            val shared = File(context.externalFiles, "$name/shared-$id.bin").apply { parentFile!!.mkdirs(); writeText("old") }
            val added = File(context.externalFiles, "$name/added-$id.bin")
            check(!added.exists()); ownedMedia += shared; ownedMedia += added
            File(root, "$name/${shared.name}").apply { parentFile!!.mkdirs(); writeText("new") }
            File(root, "$name/${added.name}").writeText("added")
        }
        val configs = GSON.toJsonTree(originalStyles).asJsonArray.apply { first().asJsonObject.addProperty("restoreFixtureTag", id) }.toString().toByteArray()
        val share = GSON.toJsonTree(originalShareStyle).asJsonObject.apply { addProperty("restoreFixtureTag", id) }.toString().toByteArray()
        File(root, ReadBookConfig.configFileName).writeBytes(configs); File(root, ReadBookConfig.shareConfigFileName).writeBytes(share)
        return configs to share
    }

    private fun assertOldBundle(styles: Pair<ByteArray, ByteArray>) {
        assertEquals("old", ownedMedia[0].readText()); assertFalse(ownedMedia[1].exists())
        assertEquals("old", ownedMedia[2].readText()); assertFalse(ownedMedia[3].exists())
        originalFiles.forEach { (file, bytes) -> assertArrayEquals(bytes, file.takeIf { it.isFile }?.readBytes()) }
        assertFalse(configurationFiles[0].takeIf { it.isFile }?.readBytes()?.contentEquals(styles.first) == true)
    }

    private fun mediaHashes(): Map<String, String> = mediaDirectories.flatMap { directory ->
        if (!directory.isDirectory) emptyList() else directory.walkTopDown().filter(File::isFile).map { it.path to digest(it) }.toList()
    }.toMap()
    private fun digest(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
}
