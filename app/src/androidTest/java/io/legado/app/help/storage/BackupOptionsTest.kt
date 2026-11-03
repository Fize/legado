package io.legado.app.help.storage

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.core.net.toUri
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import io.legado.app.data.preferences.CacheExportOptions
import io.legado.app.data.preferences.CacheExportPreferencesEntryPoint
import io.legado.app.data.preferences.CACHE_EXPORT_KEYS
import io.legado.app.data.preferences.toLegacyPreferenceMap
import fi.iki.elonen.NanoHTTPD
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.help.AppWebDav
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.ui.AppActivity
import io.legado.app.ui.navigation.SettingsRoute
import io.legado.app.ui.navigation.SettingsSection
import io.legado.app.utils.GSON
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.externalFiles
import io.legado.app.utils.fromJsonArray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

@RunWith(AndroidJUnit4::class)
class BackupOptionsTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext.applicationContext
    private val preferences = context.defaultSharedPreferences
    private val savedPreferences = HashMap(preferences.all)
    private val savedLocal = HashMap(LocalConfig.all)
    private val savedIgnore = HashMap(BackupConfig.ignoreConfig)
    private val cachePreferences=EntryPointAccessors.fromApplication(context,CacheExportPreferencesEntryPoint::class.java).cacheExportPreferences()
    private val savedCachePreferences=runBlocking(Dispatchers.IO) { cachePreferences.current() }
    private val directory = File(context.cacheDir, "backup-options-${UUID.randomUUID()}")
    private val book = Book(bookUrl = "backup-options:${UUID.randomUUID()}", name = "Backup options", author = "Test", durChapterIndex = 7)
    private val requests = CopyOnWriteArrayList<String>()
    @Volatile private var upload: ByteArray? = null
    @Volatile private var failUpload = false
    @Volatile private var failAuthorization = false
    private var scenario: ActivityScenario<AppActivity>? = null
    private val defaultArchive = File(context.externalFiles, "backup.zip")
    private var savedDefaultArchive: ByteArray? = null
    private val server = object : NanoHTTPD("127.0.0.1", 0) {
        override fun serve(session: IHTTPSession): Response {
            requests += "${session.method} ${session.uri}"
            if (failAuthorization) return newFixedLengthResponse(Response.Status.UNAUTHORIZED, "text/plain", "invalid credentials")
            val files = HashMap<String, String>()
            session.parseBody(files)
            if (session.method == Method.PUT) {
                upload = files["content"]?.let { File(it).readBytes() }
                return newFixedLengthResponse(if (failUpload) Response.Status.INTERNAL_ERROR else Response.Status.CREATED,
                    "text/plain", "upload")
            }
            return newFixedLengthResponse(Response.Status.OK, "application/xml",
                """<?xml version="1.0"?><d:multistatus xmlns:d="DAV:"/>""")
        }
    }

    @Before fun setUp() {
        directory.mkdirs()
        savedDefaultArchive = defaultArchive.takeIf { it.exists() }?.readBytes()
        server.start()
        preferences.edit().putString(PreferKey.backupPath, directory.path)
            .putBoolean(PreferKey.autoBackup, true).putBoolean(PreferKey.autoBackupWebDav, false)
            .putInt(PreferKey.autoBackupIntervalDays, 7).putBoolean(PreferKey.onlyLatestBackup, true)
            .putString(PreferKey.webDavUrl, "http://127.0.0.1:${server.listeningPort}/")
            .putString(PreferKey.webDavDir, "").putString(PreferKey.webDavAccount, "test")
            .putString(PreferKey.webDavPassword, "test").commit()
        LocalConfig.edit().putInt("backupHelpVersion", 1).putLong("lastBackup", 0).commit()
        BackupConfig.contentKeys.forEach { BackupConfig.ignoreConfig[it] = true }
        listOf(BackupConfig.bookshelfContentKey, BackupConfig.settingContentKey, BackupConfig.backgroundContentKey)
            .forEach { BackupConfig.ignoreConfig[it] = false }
        appDb.bookDao.insert(book)
        runBlocking(Dispatchers.IO) { AppWebDav.upConfig() }
        assertTrue("The fake WebDav must be configured before checking for zero requests", AppWebDav.isOk)
        requests.clear()
    }

    @After fun tearDown() {
        scenario?.close()
        runBlocking(Dispatchers.IO) { cachePreferences.update { savedCachePreferences } }
        appDb.bookDao.delete(book)
        preferences.edit().clear().apply { savedPreferences.forEach { (key, value) -> putValue(key, value) } }.commit()
        LocalConfig.edit().clear().apply { savedLocal.forEach { (key, value) -> putValue(key, value) } }.commit()
        BackupConfig.ignoreConfig.clear()
        BackupConfig.ignoreConfig.putAll(savedIgnore)
        runBlocking(Dispatchers.IO) { AppWebDav.upConfig() }
        server.stop()
        directory.deleteRecursively()
        savedDefaultArchive?.let { defaultArchive.writeBytes(it) } ?: defaultArchive.delete()
    }

    @Test fun manualChoiceConfirmsBeforeCreatingLocalAndWebDavArchives() {
        defaultArchive.delete()
        preferences.edit().remove(PreferKey.backupPath).commit()
        launchSettings()
        clickPreference("web_dav_backup")
        node(R.string.backup_local_only).assertIsDisplayed()
        node(R.string.backup_local_webdav).assertIsDisplayed()
        screenshot("backup-manual-destinations")
        assertFalse(defaultArchive.exists())
        assertEquals(0L, LocalConfig.lastBackup)
        assertTrue(requests.isEmpty())
        pressBack()
        assertFalse(defaultArchive.exists())
        clickPreference("web_dav_backup")
        node(R.string.backup_local_only).performClick()
        await { LocalConfig.lastBackup > 0 }
        assertArchive(defaultArchive)
        assertTrue("Local choice must not check, upload or sync backgrounds: $requests", requests.isEmpty())
        clickPreference("web_dav_backup")
        node(R.string.backup_local_webdav).performClick()
        await { upload != null && !File(Backup.zipFilePath).exists() }
        assertTrue(requests.any { it.startsWith("PUT /backup") })
        val received = File(directory, "received.zip").apply { writeBytes(checkNotNull(upload)) }
        assertArchive(received)
        assertArrayEquals(defaultArchive.readBytes(), received.readBytes())
    }

    @Test fun settingsDialogKeepsTargetsIntervalAndEnablementAndShowsDefaultBackupDirectory() {
        preferences.edit().remove(PreferKey.autoBackup).remove(PreferKey.autoBackupWebDav)
            .remove(PreferKey.autoBackupIntervalDays).remove(PreferKey.backupPath).commit()
        launchSettings()
        scrollTo(R.string.set_local_password).assertIsDisplayed()
        scrollTo(R.string.backup_path).assertIsDisplayed()
        compose.onNodeWithText(context.externalFiles.absolutePath, substring = true).assertIsDisplayed()
        clickPreference("localPassword")
        compose.onNode(hasSetTextAction()).performTextReplacement("backup-options-password")
        node(R.string.ok).performClick()
        assertEquals("backup-options-password", LocalConfig.password)
        assertFalse(preferences.contains("password"))
        compose.onNodeWithText(context.externalFiles.absolutePath, substring = true).assertIsDisplayed()
        screenshot("backup-password-and-default-directory")
        clickPreference(PreferKey.backupPath)
        node(R.string.default_path).assertIsDisplayed()
        compose.onNode(hasText(context.externalFiles.absolutePath, substring = true) and hasAnyAncestor(isDialog()))
            .assertIsDisplayed()
        screenshot("backup-default-directory-selector")
        node(R.string.default_path).performClick()
        compose.onNodeWithText(context.externalFiles.absolutePath, substring = true).assertIsDisplayed()
        clickPreference(PreferKey.autoBackup)
        assertTrue("The existing missing-key default remains enabled", AppConfig.autoBackup)
        assertFalse("Opening settings must not persist the pending values", preferences.contains(PreferKey.autoBackup))
        assertTrue(AppConfig.autoBackupWebDav)
        assertEquals(1, AppConfig.autoBackupIntervalDays)
        screenshot("backup-auto-defaults")
        node(R.string.backup_local_only).performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("0")
        node(R.string.ok).performClick()
        node(R.string.auto_backup_interval_invalid).assertIsDisplayed()
        assertTrue(AppConfig.autoBackup)
        assertFalse(preferences.contains(PreferKey.autoBackup))
        assertEquals(1, AppConfig.autoBackupIntervalDays)
        compose.onNode(hasSetTextAction()).performTextReplacement("7")
        node(R.string.ok).performClick()
        assertTrue(AppConfig.autoBackup)
        assertFalse(AppConfig.autoBackupWebDav)
        assertEquals(7, AppConfig.autoBackupIntervalDays)
        scenario!!.recreate()
        clickPreference(PreferKey.autoBackup)
        screenshot("backup-auto-local-seven-days")
        compose.onNode(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.ToggleableState) and hasAnyAncestor(isDialog())).performClick()
        node(R.string.ok).performClick()
        assertFalse(AppConfig.autoBackup)
        clickPreference(PreferKey.autoBackup)
        compose.onNode(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.ToggleableState) and hasAnyAncestor(isDialog())).performClick()
        node(R.string.backup_local_webdav).performClick()
        node(R.string.cancel).performClick()
        assertFalse(AppConfig.autoBackup)
        assertFalse(AppConfig.autoBackupWebDav)
        assertEquals(7, AppConfig.autoBackupIntervalDays)
        assertTrue(requests.isEmpty())
    }

    @Test fun automaticLocalBackupRespectsIntervalAndFailuresDoNotAdvanceSuccessTime() = runBlocking(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        LocalConfig.lastBackup = now - TimeUnit.DAYS.toMillis(7) + 1
        assertFalse(Backup.shouldBackup(now))
        assertTrue(Backup.shouldBackup(now + 1))
        LocalConfig.lastBackup = now - TimeUnit.DAYS.toMillis(6)
        Backup.autoBackupLocked(context)
        assertFalse(File(directory, "backup.zip").exists())
        LocalConfig.lastBackup = now - TimeUnit.DAYS.toMillis(8)
        preferences.edit().putBoolean(PreferKey.autoBackup, false).commit()
        Backup.autoBackupLocked(context)
        assertFalse(File(directory, "backup.zip").exists())
        preferences.edit().putBoolean(PreferKey.autoBackup, true).commit()
        Backup.autoBackupLocked(context)
        assertArchive(File(directory, "backup.zip"))
        val firstSuccess = LocalConfig.lastBackup
        assertTrue(firstSuccess >= now)
        Backup.autoBackupLocked(context)
        assertEquals(firstSuccess, LocalConfig.lastBackup)
        assertTrue("Automatic local mode must make zero WebDav requests: $requests", requests.isEmpty())

        val blocked = File(directory, "not-a-directory").apply { writeText("keep me") }
        preferences.edit().putString(PreferKey.backupPath, blocked.path).commit()
        LocalConfig.lastBackup = 123
        assertTrue(runCatching { Backup.autoBackupLocked(context) }.isFailure)
        assertEquals(123L, LocalConfig.lastBackup)
        assertEquals("keep me", blocked.readText())
        preferences.edit().putString(PreferKey.backupPath, directory.path)
            .putBoolean(PreferKey.autoBackupWebDav, true).commit()
        failUpload = true
        assertTrue(runCatching { Backup.autoBackupLocked(context) }.isFailure)
        assertArchive(File(directory, "backup.zip"))
        assertEquals("A failed cloud upload must remain due for retry", 123L, LocalConfig.lastBackup)
        assertTrue(requests.any { it.startsWith("PUT /backup") })
    }

    @Test fun selectedCloudBackupDoesNotReportSuccessWithoutAuthorization() = runBlocking(Dispatchers.IO) {
        failAuthorization = true
        AppWebDav.upConfig()
        assertFalse(AppWebDav.isOk)
        requests.clear()
        LocalConfig.lastBackup = 123
        assertTrue(runCatching { Backup.backupLocked(context, directory.path, uploadWebDav = true) }.isFailure)
        assertArchive(File(directory, "backup.zip"))
        assertEquals(123L, LocalConfig.lastBackup)
        assertFalse(requests.any { it.startsWith("PUT ") })
        preferences.edit().remove(PreferKey.webDavAccount).remove(PreferKey.webDavPassword).commit()
        AppWebDav.upConfig()
        assertTrue(runCatching { Backup.backupLocked(context, directory.path, uploadWebDav = true) }.isFailure)
        assertEquals(123L, LocalConfig.lastBackup)
        Backup.backupLocked(context, directory.path, uploadWebDav = false)
        assertTrue(LocalConfig.lastBackup > 123)
        assertTrue(requests.isEmpty())
    }

    @Test fun actualArchiveRestoresAllHistoryOptionsAndBothFallbackImages() = runBlocking(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val day = File(context.externalFiles, "covers/history-day-$id.png")
        val night = File(context.externalFiles, "covers/history-night-$id.png")
        try {
            for ((file, color) in listOf(day to android.graphics.Color.RED, night to android.graphics.Color.BLUE)) {
                file.parentFile!!.mkdirs()
                Bitmap.createBitmap(32, 48, Bitmap.Config.ARGB_8888).apply {
                    eraseColor(color)
                    file.outputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it) }
                    recycle()
                }
            }
            val dayBytes = day.readBytes()
            val nightBytes = night.readBytes()
            BackupConfig.ignoreConfig[BackupConfig.otherCoverContentKey] = false
            preferences.edit().putBoolean("enableReadRecord", false)
                .putBoolean("readRecordSimpleLayout", false).putBoolean("readRecordUseDays", true)
                .putBoolean("readRecordShowSeconds", false).putBoolean("readRecordFixedCard", false)
                // The archive must be portable across application package names and storage roots.
                .putString(PreferKey.readRecordCover, "/old-install/covers/${day.name}")
                .putString(PreferKey.readRecordCoverDark, "/old-install/covers/${night.name}").commit()
            LocalConfig.edit().putInt("readRecordSort", 2).commit()
            Backup.backupLocked(context, directory.path, uploadWebDav = false)
            val archive = File(directory, "backup.zip")
            ZipFile(archive).use { zip ->
                assertArrayEquals(dayBytes, zip.getInputStream(zip.getEntry("covers/${day.name}")).use { it.readBytes() })
                assertArrayEquals(nightBytes, zip.getInputStream(zip.getEntry("covers/${night.name}")).use { it.readBytes() })
            }
            day.delete()
            night.delete()
            preferences.edit().putBoolean("enableReadRecord", true)
                .putBoolean("readRecordSimpleLayout", true).putBoolean("readRecordUseDays", false)
                .putBoolean("readRecordShowSeconds", true).putBoolean("readRecordFixedCard", true)
                .remove(PreferKey.readRecordCover).remove(PreferKey.readRecordCoverDark).commit()
            LocalConfig.edit().putInt("readRecordSort", 0).commit()
            Restore.restoreOrThrow(context, archive.toUri(), lanTransfer = true)
            assertFalse(AppConfig.enableReadRecord)
            assertFalse(AppConfig.readRecordSimpleLayout)
            assertTrue(AppConfig.readRecordUseDays)
            assertFalse(AppConfig.readRecordShowSeconds)
            assertFalse(AppConfig.readRecordFixedCard)
            assertEquals(2, LocalConfig.getInt("readRecordSort", -1))
            assertEquals(day.path, preferences.getString(PreferKey.readRecordCover, null))
            assertEquals(night.path, preferences.getString(PreferKey.readRecordCoverDark, null))
            assertArrayEquals(dayBytes, day.readBytes())
            assertArrayEquals(nightBytes, night.readBytes())
            val legacy = File(directory, "legacy-history")
            writePreferenceSnapshot(context, legacy.path, "config") { }
            Restore.restoreLocked(legacy.path)
            assertTrue(AppConfig.readRecordFixedCard)
            assertFalse(preferences.contains(PreferKey.readRecordCover))
            assertFalse(preferences.contains(PreferKey.readRecordCoverDark))
        } finally {
            day.delete()
            night.delete()
        }
    }

    @Test fun actualArchiveRestoresExplicitAutoSettingsAndLegacyMissingKeysUseDefaults() = runBlocking(Dispatchers.IO) {
        preferences.edit().putBoolean(PreferKey.autoBackup, false).commit()
        Backup.backupLocked(context, directory.path, uploadWebDav = false)
        val archive = File(directory, "backup.zip")
        assertArchive(archive)
        preferences.edit().putBoolean(PreferKey.autoBackup, true).putBoolean(PreferKey.autoBackupWebDav, true)
            .putInt(PreferKey.autoBackupIntervalDays, 1).commit()
        Restore.restoreOrThrow(context, archive.toUri(), lanTransfer = true)
        assertFalse(AppConfig.autoBackup)
        assertFalse(AppConfig.autoBackupWebDav)
        assertEquals(7, AppConfig.autoBackupIntervalDays)
        val legacy = File(directory, "legacy").apply { mkdirs() }
        Restore.restoreLocked(legacy.path)
        assertFalse("A backup without settings must leave them alone", AppConfig.autoBackup)
        assertEquals(7, AppConfig.autoBackupIntervalDays)
        writePreferenceSnapshot(context, legacy.path, "config") { }
        Restore.restoreLocked(legacy.path)
        assertTrue(AppConfig.autoBackup)
        assertTrue(AppConfig.autoBackupWebDav)
        assertEquals(1, AppConfig.autoBackupIntervalDays)
        assertTrue(requests.isEmpty())
    }

    @Test fun actualArchiveRestoresTypedExportOptionsWithoutRestoringAnotherDevicesDirectory()=runBlocking(Dispatchers.IO) {
        val expected=CacheExportOptions(format=2,charset="GB18030",useReplace=false,webDav=true,omitNames=true,
            pictures=true,parallel=true,customSections=true,fileName="name + '\u4e66'",episodeName="name + epubIndex",
            directory="content://fixture/tree/original-device")
        cachePreferences.update { expected }
        Backup.backupLocked(context,directory.path,uploadWebDav=false)
        val archive=File(directory,"backup.zip"); assertArchive(archive)
        ZipFile(archive).use { zip ->
            val config=zip.getInputStream(zip.getEntry("config.xml")).bufferedReader().use { it.readText() }
            expected.toLegacyPreferenceMap().keys.forEach { assertTrue(config.contains("name=\"$it\"")) }
            assertFalse(config.contains(expected.directory))
        }
        val localDirectory="content://fixture/tree/current-device"
        cachePreferences.update { CacheExportOptions(directory=localDirectory) }
        Restore.restoreOrThrow(context,archive.toUri(),lanTransfer=true)
        assertEquals(expected.copy(directory=localDirectory),cachePreferences.current())
        assertTrue(CACHE_EXPORT_KEYS.none(preferences::contains))

        val legacy=File(directory,"legacy-export").apply { mkdirs() }
        File(legacy,"config.xml").writeText("""<?xml version="1.0" encoding="utf-8"?><map><int name="exportType" value="1"/><boolean name="exportUseReplace" value="false"/><string name="exportCharset">UTF-8</string></map>""")
        Restore.restoreLocked(legacy.path)
        val restored=cachePreferences.current()
        assertEquals(1,restored.format); assertEquals("UTF-8",restored.charset); assertFalse(restored.useReplace)
        assertEquals(localDirectory,restored.directory); assertTrue(CACHE_EXPORT_KEYS.none(preferences::contains))
    }

    @Test fun actualArchivePreservesAllReadAloudControlsIncludingExplicitFalse() = runBlocking(Dispatchers.IO) {
        val values = linkedMapOf<String, Any>(
            PreferKey.readAloudControlsRealtime to false,
            PreferKey.readAloudControlsPause to false,
            PreferKey.readAloudControlsPosition to false,
            PreferKey.readAloudControlsAutoHide to true,
            PreferKey.readAloudControlsDrag to true,
            PreferKey.readAloudControlsDock to true,
            PreferKey.readAloudControlsWidth to 185,
            PreferKey.readAloudControlsSize to 60,
            PreferKey.readAloudControlsOpacity to 75,
            PreferKey.readAloudControlsThreshold to 250,
            PreferKey.readAloudControlsX to .25f,
            PreferKey.readAloudControlsY to .65f,
        )
        preferences.edit().apply { values.forEach { (key, value) -> putValue(key, value) } }.commit()
        values.keys.forEach { assertTrue(BackupConfig.keyIsNotIgnore(it)) }
        Backup.backupLocked(context, directory.path, uploadWebDav = false)
        val archive = File(directory, "backup.zip")
        ZipFile(archive).use { zip ->
            val config = zip.getInputStream(zip.getEntry("config.xml")).bufferedReader().use { it.readText() }
            values.keys.forEach { assertTrue("Archive contains $it", config.contains(it)) }
        }
        preferences.edit().apply { values.keys.forEach { remove(it) } }.commit()
        Restore.restoreOrThrow(context, archive.toUri(), lanTransfer = true)
        values.forEach { (key, value) -> assertEquals("Restored $key with its original type", value, preferences.all[key]) }
        assertTrue(requests.isEmpty())
    }

    private fun launchSettings() {
        scenario = ActivityScenario.launch(
            AppActivity.startIntent(context, SettingsRoute(SettingsSection.Backup))
        )
    }

    private fun label(id: Int): String {
        lateinit var value: String
        scenario!!.onActivity { value = it.getString(id) }
        return value
    }

    private fun node(id: Int) = compose.onNodeWithText(label(id))

    private fun scrollTo(id: Int): SemanticsNodeInteraction {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(label(id)))
        return node(id).performScrollTo()
    }

    private fun clickPreference(key: String) {
        val titleRes = when (key) {
            "web_dav_backup" -> R.string.backup
            "localPassword" -> R.string.set_local_password
            PreferKey.backupPath -> R.string.backup_path
            PreferKey.autoBackup -> R.string.auto_backup_t
            else -> error("Unsupported preference key: $key")
        }
        instrumentation.waitForIdleSync()
        screenshot("backup-before-click-$key")
        scrollTo(titleRes).performClick()
    }

    private fun assertArchive(file: File) {
        assertTrue("Actual archive missing: $file", file.isFile && file.length() > 0)
        ZipFile(file).use { zip ->
            val books = zip.getInputStream(checkNotNull(zip.getEntry("bookshelf.json"))).bufferedReader().use {
                GSON.fromJsonArray<Book>(it.readText()).getOrThrow()
            }
            assertEquals(7, books.single { it.bookUrl == book.bookUrl }.durChapterIndex)
            assertNotNull(zip.getEntry("config.xml"))
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (!condition() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
        assertTrue("The real backup did not finish", condition())
        instrumentation.waitForIdleSync()
    }

    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        val rendered = CountDownLatch(1)
        scenario!!.onActivity {
            val decor = it.window.decorView
            decor.postOnAnimation { decor.postOnAnimation { rendered.countDown() } }
        }
        assertTrue(rendered.await(5, TimeUnit.SECONDS))
        val image = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(context.getExternalFilesDir("ui-regression"), "$name.png").outputStream().use {
                assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { image.recycle() }
    }
}

private fun SharedPreferences.Editor.putValue(key: String, value: Any?) {
    when (value) {
        is Boolean -> putBoolean(key, value)
        is Int -> putInt(key, value)
        is Long -> putLong(key, value)
        is Float -> putFloat(key, value)
        is String -> putString(key, value)
        is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
    }
}
