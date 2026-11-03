package io.legado.app.help.storage

import androidx.core.net.toUri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.preferences.CacheExportPreferencesEntryPoint
import io.legado.app.data.repository.MangaReaderPreferencesEntryPoint
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.utils.GSON
import io.legado.app.utils.defaultSharedPreferences
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Actual restore failures must not commit a book before discovering a bad setting type. */
@RunWith(AndroidJUnit4::class)
class RestorePreferenceTypeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val id = UUID.randomUUID().toString()
    private val directory = File(context.cacheDir, "restore-preference-types-$id")
    private val book = Book(bookUrl = "https://preference-types-$id.invalid/book",
        name = "Preference types $id", author = "Compatibility fixture", durChapterIndex = 7)
    private val prefs = context.defaultSharedPreferences
    private val savedPreferences = HashMap(prefs.all)
    private val savedIgnore = HashMap(BackupConfig.ignoreConfig)
    private val savedLastBackup = LocalConfig.lastBackup
    // Initialize before corrupt input is tested; preserve both persisted and live values.
    private val savedReaderBooleans = listOf(ReadBookConfig.shareLayout, ReadBookConfig.hideStatusBar, ReadBookConfig.hideNavigationBar)
    private val savedReader = listOf(ReadBookConfig.readStyleSelect, ReadBookConfig.comicStyleSelect, ReadBookConfig.autoReadSpeed)
    private val exports = EntryPointAccessors.fromApplication(context,
        CacheExportPreferencesEntryPoint::class.java).cacheExportPreferences()
    private val manga = EntryPointAccessors.fromApplication(context,
        MangaReaderPreferencesEntryPoint::class.java).mangaReaderPreferencesStore()
    private val savedExport = runBlocking { exports.current() }
    private val savedManga = runBlocking { manga.current() }

    @Before fun setup() {
        check(directory.mkdirs())
        appDb.bookDao.insert(book)
        LocalConfig.lastBackup = 1234L
        BackupConfig.ignoreConfig["readConfig"] = false
        BackupConfig.ignoreConfig["themeConfig"] = false
        BackupConfig.ignoreConfig["coverConfig"] = false
        BackupConfig.ignoreConfig[PreferKey.themeMode] = false
    }

    @After fun cleanup() = runBlocking {
        appDb.bookDao.delete(book)
        exports.update { savedExport }
        manga.update { savedManga }
        // Setters also publish persisted values. Restore the exact snapshot afterwards.
        prefs.edit().remove(PreferKey.readStyleSelect).remove(PreferKey.comicStyleSelect).remove(PreferKey.autoReadSpeed).commit()
        ReadBookConfig.readStyleSelect = savedReader[0]
        ReadBookConfig.comicStyleSelect = savedReader[1]
        ReadBookConfig.autoReadSpeed = savedReader[2]
        ReadBookConfig.shareLayout = savedReaderBooleans[0]
        ReadBookConfig.hideStatusBar = savedReaderBooleans[1]
        ReadBookConfig.hideNavigationBar = savedReaderBooleans[2]
        prefs.edit().clear().apply {
            savedPreferences.forEach { (key, value) -> when (value) {
                is Boolean -> putBoolean(key, value)
                is Int -> putInt(key, value)
                is Long -> putLong(key, value)
                is Float -> putFloat(key, value)
                is String -> putString(key, value)
                is Set<*> -> { @Suppress("UNCHECKED_CAST") putStringSet(key, value as Set<String>) }
            } }
            check(commit())
        }
        assertEquals(savedPreferences, prefs.all)
        LocalConfig.lastBackup = savedLastBackup
        BackupConfig.ignoreConfig.clear(); BackupConfig.ignoreConfig.putAll(savedIgnore)
        directory.deleteRecursively()
        assertFalse(appDb.bookDao.has(book.bookUrl))
        assertFalse(directory.exists())
        assertEquals(savedLastBackup, LocalConfig.lastBackup)
        Unit
    }

    @Test fun stringReadStyleIndexDoesNotPartiallyCommitTheBook() = rejected(PreferKey.readStyleSelect) {
        putString(PreferKey.readStyleSelect, "not-an-integer")
    }

    @Test fun longComicStyleIndexDoesNotPartiallyCommitTheBook() = rejected(PreferKey.comicStyleSelect) {
        putLong(PreferKey.comicStyleSelect, 2L)
    }

    @Test fun booleanAutoReadSpeedDoesNotPartiallyCommitTheBook() = rejected(PreferKey.autoReadSpeed) {
        putBoolean(PreferKey.autoReadSpeed, true)
    }

    @Test fun wrongEditorFontTypeIsRejectedBeforeTheAppConfigListener() = rejected(PreferKey.editFontScale) {
        putString(PreferKey.editFontScale, "wrong-editor-font-type")
    }

    @Test fun integerUserAgentIsRejectedBeforeTheAppConfigListener() = rejected(PreferKey.userAgent) {
        putInt(PreferKey.userAgent, 123)
    }

    @Test fun booleanThemeModeIsRejectedBeforeTheAppConfigListener() = rejected(PreferKey.themeMode) {
        putBoolean(PreferKey.themeMode, true)
    }

    @Test fun wrongCustomCoverFontSizeCannotFailAfterTheBookCommit() = rejected(PreferKey.coverTitleLargeSize) {
        putBoolean(PreferKey.coverCustomFontSize, true)
        putString(PreferKey.coverTitleLargeSize, "wrong-cover-font-size")
    }

    @Test fun ignoredWrongThemeTypeDoesNotBlockTheBookRestore() = runBlocking {
        BackupConfig.ignoreConfig["themeConfig"] = true
        restore { putString(PreferKey.cPrimary, "ignored-theme-type") }
        assertEquals(29, appDb.bookDao.getBook(book.bookUrl)!!.durChapterIndex)
        assertEquals(savedPreferences[PreferKey.cPrimary], prefs.all[PreferKey.cPrimary])
    }

    @Test fun legacyBooleanStringsAndDoublePageValueKeepExistingNormalization() = runBlocking {
        restore {
            putString(PreferKey.shareLayout, "true")
            putString(PreferKey.hideNavigationBar, "false")
            putInt(PreferKey.doublePageHorizontal, 0)
        }
        assertTrue(ReadBookConfig.shareLayout)
        assertFalse(ReadBookConfig.hideNavigationBar)
        assertTrue(prefs.getBoolean(PreferKey.shareLayout, false))
        assertFalse(prefs.getBoolean(PreferKey.hideNavigationBar, true))
        assertEquals("2", prefs.getString(PreferKey.doublePageHorizontal, null))
    }

    @Test fun ignoredWrongReadPreferenceTypeDoesNotBlockTheBookRestore() = runBlocking {
        BackupConfig.ignoreConfig["readConfig"] = true
        restore { putString(PreferKey.readStyleSelect, "ignored-type") }
        assertEquals(29, appDb.bookDao.getBook(book.bookUrl)!!.durChapterIndex)
        assertEquals(savedPreferences[PreferKey.readStyleSelect], prefs.all[PreferKey.readStyleSelect])
    }

    @Test fun typedIntegerValuesAndUnknownSettingsRemainRestorable() = runBlocking {
        restore {
            putInt(PreferKey.readStyleSelect, -1)
            putInt(PreferKey.comicStyleSelect, 999)
            putInt(PreferKey.autoReadSpeed, 46)
            putStringSet("compatibility-unknown-$id", setOf("keep", "future"))
        }
        assertEquals(29, appDb.bookDao.getBook(book.bookUrl)!!.durChapterIndex)
        assertEquals(-1, prefs.getInt(PreferKey.readStyleSelect, 0))
        assertEquals(999, prefs.getInt(PreferKey.comicStyleSelect, 0))
        assertEquals(46, ReadBookConfig.autoReadSpeed)
        assertEquals(setOf("keep", "future"), prefs.getStringSet("compatibility-unknown-$id", null))
    }

    private fun rejected(key: String, populate: android.content.SharedPreferences.Editor.() -> Unit) = runBlocking {
        val before = HashMap(prefs.all)
        val liveUserAgent = AppConfig.userAgent
        val liveFontScale = AppConfig.editFontScale
        val liveThemeMode = AppConfig.themeMode
        val result = runCatching { restore(populate) }
        assertTrue("Invalid type must fail", result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        assertTrue(result.exceptionOrNull()!!.message.orEmpty().contains("config.xml"))
        assertTrue(result.exceptionOrNull()!!.message.orEmpty().contains(key))
        assertEquals("Book was committed before the setting failed", 7, appDb.bookDao.getBook(book.bookUrl)!!.durChapterIndex)
        assertEquals(before, prefs.all)
        assertEquals(liveUserAgent, AppConfig.userAgent)
        assertEquals(liveFontScale, AppConfig.editFontScale)
        assertEquals(liveThemeMode, AppConfig.themeMode)
        assertEquals(1234L, LocalConfig.lastBackup)
        assertEquals(savedExport, exports.current())
        assertEquals(savedManga, manga.current())
    }

    private suspend fun restore(populate: android.content.SharedPreferences.Editor.() -> Unit) {
        writePreferenceSnapshot(context, directory.path, "config", populate)
        val archive = File(directory, "input.zip")
        ZipOutputStream(archive.outputStream()).use { zip ->
            for ((name, bytes) in listOf("config.xml" to File(directory, "config.xml").readBytes(),
                "bookshelf.json" to GSON.toJson(listOf(book.copy(durChapterIndex = 29))).toByteArray())) {
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
        }
        Restore.restoreOrThrow(context, archive.toUri())
    }
}
