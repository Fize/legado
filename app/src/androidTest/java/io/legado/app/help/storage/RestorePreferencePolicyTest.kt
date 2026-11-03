package io.legado.app.help.storage

import androidx.core.net.toUri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.preferences.CacheExportOptions
import io.legado.app.data.preferences.CacheExportPreferencesEntryPoint
import io.legado.app.data.repository.MangaReaderPreferencesEntryPoint
import io.legado.app.help.config.LocalConfig
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

/** Runs the complete restore against one owned book and restores every touched preference. */
@RunWith(AndroidJUnit4::class)
class RestorePreferencePolicyTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val id = UUID.randomUUID().toString()
    private val directory = File(context.cacheDir, "restore-preference-policy-$id")
    private val book = Book(bookUrl = "https://preference-policy-$id.invalid/book",
        name = "Preference policy $id", author = "Compatibility fixture", durChapterIndex = 7)
    private val prefs = context.defaultSharedPreferences
    private val savedPreferences = HashMap(prefs.all)
    private val savedIgnore = HashMap(BackupConfig.ignoreConfig)
    private val savedLastBackup = LocalConfig.lastBackup
    private val exports = EntryPointAccessors.fromApplication(context,
        CacheExportPreferencesEntryPoint::class.java).cacheExportPreferences()
    private val manga = EntryPointAccessors.fromApplication(context,
        MangaReaderPreferencesEntryPoint::class.java).mangaReaderPreferencesStore()
    private val savedExport = runBlocking { exports.current() }
    private val savedManga = runBlocking { manga.current() }

    @Before fun setUp() = runBlocking {
        check(directory.mkdirs())
        appDb.bookDao.insert(book)
        LocalConfig.lastBackup = 1234L
        BackupConfig.ignoreConfig["readConfig"] = false
        exports.update { CacheExportOptions() }
    }

    @After fun cleanUp() = runBlocking {
        appDb.bookDao.delete(book)
        exports.update { savedExport }
        manga.update { savedManga }
        prefs.edit().clear().apply {
            savedPreferences.forEach { (key, value) ->
                when (value) {
                    is Boolean -> putBoolean(key, value)
                    is Int -> putInt(key, value)
                    is Long -> putLong(key, value)
                    is Float -> putFloat(key, value)
                    is String -> putString(key, value)
                    is Set<*> -> { @Suppress("UNCHECKED_CAST")
                        putStringSet(key, value as Set<String>) }
                }
            }
            check(commit())
        }
        LocalConfig.lastBackup = savedLastBackup
        BackupConfig.ignoreConfig.clear()
        BackupConfig.ignoreConfig.putAll(savedIgnore)
        directory.deleteRecursively()
        Unit
    }

    @Test fun unsupportedCharsetFailsBeforeAnyBookOrPreferenceWrite() = rejected {
        putString(PreferKey.exportCharset, "compatibility-unsupported-charset")
    }

    @Test fun illegalCharsetNameFailsBeforeAnyBookOrPreferenceWrite() = rejected {
        putString(PreferKey.exportCharset, "invalid charset!")
    }

    @Test fun oversizedBookScriptFailsBeforeAnyBookOrPreferenceWrite() = rejected {
        putString(PreferKey.bookExportFileName, "x".repeat(500_001))
    }

    @Test fun oversizedEpisodeScriptFailsBeforeAnyBookOrPreferenceWrite() = rejected {
        putString(PreferKey.episodeExportFileName, "x".repeat(500_001))
    }

    @Test fun ignoredMangaPreferencesRemainUnchanged() = runBlocking {
        BackupConfig.ignoreConfig["readConfig"] = true
        val before = manga.current()
        restore {
            putBoolean(PreferKey.mangaRightToLeft, !before.rightToLeftEnabled)
            putInt(PreferKey.mangaPreDownloadNum, before.preDownloadCount + 1)
            putString(PreferKey.mangaColorFilter, "ignored-$id")
        }
        assertEquals(before, manga.current())
        assertEquals(29, appDb.bookDao.getBook(book.bookUrl)!!.durChapterIndex)
    }

    @Test fun enabledMangaPreferencesStillRestore() = runBlocking {
        val before = manga.current()
        restore {
            putBoolean(PreferKey.mangaRightToLeft, !before.rightToLeftEnabled)
            putInt(PreferKey.mangaPreDownloadNum, 23)
        }
        assertEquals(!before.rightToLeftEnabled, manga.current().rightToLeftEnabled)
        assertEquals(23, manga.current().preDownloadCount)
    }

    @Test fun validLegacyExportValuesAndUnrecognizedSettingsStillRestore() = runBlocking {
        restore {
            putString(PreferKey.exportCharset, "GB18030")
            putInt(PreferKey.exportType, 2)
            putString(PreferKey.bookExportFileName, "合法脚本-$id")
            putString("compatibility-unknown-$id", "preserved")
        }
        assertEquals("GB18030", exports.current().charset)
        assertEquals(2, exports.current().format)
        assertEquals("合法脚本-$id", exports.current().fileName)
        assertEquals("preserved", prefs.getString("compatibility-unknown-$id", null))
        assertTrue(LocalConfig.lastBackup > 1234L)
    }

    @Test fun blankCharsetAndOutOfRangeLegacyFormatKeepExistingNormalization() = runBlocking {
        restore { putString(PreferKey.exportCharset, ""); putInt(PreferKey.exportType, 99) }
        assertEquals("UTF-8", exports.current().charset)
        assertEquals(0, exports.current().format)
    }

    private fun rejected(populate: android.content.SharedPreferences.Editor.() -> Unit) = runBlocking {
        val beforePrefs = HashMap(prefs.all)
        val beforeExport = exports.current()
        val beforeManga = manga.current()
        val result = runCatching { restore(populate) }
        assertTrue("Invalid setting must report failure", result.isFailure)
        assertEquals("Book must remain unchanged", 7, appDb.bookDao.getBook(book.bookUrl)!!.durChapterIndex)
        assertEquals(1234L, LocalConfig.lastBackup)
        assertEquals(beforePrefs, prefs.all)
        assertEquals(beforeExport, exports.current())
        assertEquals(beforeManga, manga.current())
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
