package io.legado.app.help.storage

import android.content.SharedPreferences
import android.os.Process
import androidx.datastore.dataStoreFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.data.preferences.CacheExportPreferencesEntryPoint
import io.legado.app.data.repository.MangaReaderPreferencesEntryPoint
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.ReplacePreviewConfig
import io.legado.app.model.VideoPlay.VIDEO_PREF_NAME
import io.legado.app.utils.GSON
import io.legado.app.utils.defaultSharedPreferences
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RestoreSettingsSafetyTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val id = UUID.randomUUID().toString()
    private val root = File(context.cacheDir, "restore-settings-fixture-$id")
    private val prefs = context.defaultSharedPreferences
    private val video = context.getSharedPreferences(VIDEO_PREF_NAME, 0)
    private val samples = context.getSharedPreferences("replace_preview", 0)
    private val original = listOf(prefs, video, samples, LocalConfig).associateWith { HashMap(it.all) }
    private val ignored = HashMap(BackupConfig.ignoreConfig)
    private val book = Book(bookUrl = "https://settings-$id.invalid/book", name = "Settings $id", durChapterIndex = 7)
    private val rule = ReplaceRule(id = UUID.randomUUID().mostSignificantBits.and(Long.MAX_VALUE).coerceAtLeast(1), name = "Old $id")
    private val trigger = "restore_settings_${id.replace("-", "")}"
    private val exports = EntryPointAccessors.fromApplication(context, CacheExportPreferencesEntryPoint::class.java).cacheExportPreferences()
    private val mangas = EntryPointAccessors.fromApplication(context, MangaReaderPreferencesEntryPoint::class.java).mangaReaderPreferencesStore()
    private val exportBefore = runBlocking { exports.current() }
    private val mangaBefore = runBlocking { mangas.current() }
    private val exportFile = context.dataStoreFile("cache_export.pb")
    private val mangaFile = context.dataStoreFile("manga_reader_preferences.pb")
    private val exportBytes = exportFile.readBytes()
    private val mangaBytes = mangaFile.readBytes()
    private lateinit var baseline: Map<SharedPreferences, Map<String, *>>
    private val key = "restore_settings_$id"

    @Before fun setup() {
        assertTrue(Process.myUid() >= 10000)
        assertFalse(RestoreFileRecovery.hasPending(context))
        check(root.mkdirs())
        appDb.bookDao.insert(book); appDb.replaceRuleDao.insert(rule)
        LocalConfig.lastBackup = 1234L
        check(samples.edit().putString("sample_${rule.id}", "old-sample").commit())
        BackupConfig.ignoreConfig["readConfig"] = false
        BackupConfig.ignoreConfig["coverConfig"] = false
        baseline = listOf(prefs, video, samples, LocalConfig).associateWith { HashMap(it.all) }
    }

    @After fun cleanup() = runBlocking {
        backupRestoreMutex.withLock {
            appDb.openHelper.writableDatabase.execSQL("DROP TRIGGER IF EXISTS $trigger")
            RestoreFileRecovery.recover(context, cleanup = true)
            appDb.bookDao.delete(book); appDb.replaceRuleDao.delete(rule)
            exports.update { exportBefore }; mangas.update { mangaBefore }
            ReadBookConfig.apply {
                readStyleSelect = prefs.getInt(PreferKey.readStyleSelect, 0)
                comicStyleSelect = prefs.getInt(PreferKey.comicStyleSelect, 0)
                autoReadSpeed = prefs.getInt(PreferKey.autoReadSpeed, 46)
            }
            original.forEach { (target, values) -> restore(target, values) }
            BackupConfig.ignoreConfig.clear(); BackupConfig.ignoreConfig.putAll(ignored)
            root.deleteRecursively()
            assertFalse(RestoreFileRecovery.hasPending(context))
            assertNull(appDb.cacheDao.get(RestoreFileRecovery.COMMIT_KEY))
        }
    }

    @Test fun markerFailureRestoresBothStoresAllPreferencesAndPreviewSamples() = runBlocking {
        input(includeSample = false)
        appDb.openHelper.writableDatabase.execSQL("""CREATE TRIGGER $trigger BEFORE INSERT ON caches
            WHEN NEW.key = '${RestoreFileRecovery.COMMIT_KEY}' BEGIN SELECT RAISE(ABORT, 'after settings'); END""")
        assertTrue(runCatching { Restore.restoreLocked(root.path) }.isFailure)
        assertBefore()
    }

    @Test fun laterStoreWriteFailureCannotLeaveEarlierSettingsOrBookCommitted() = runBlocking {
        input()
        val copy = File(root, "original-cache-export.pb")
        check(exportFile.renameTo(copy)); check(exportFile.mkdir())
        try {
            assertTrue("Expected actual DataStore rename failure", runCatching { Restore.restoreLocked(root.path) }.isFailure)
        } finally {
            check(exportFile.isDirectory && exportFile.list()?.isEmpty() == true && exportFile.delete())
            check(copy.renameTo(exportFile))
        }
        // Persistent I/O may also prevent rollback; after the volume works, retry the retained journal.
        RestoreFileRecovery.recover(context, cleanup = true)
        assertBefore()
    }

    @Test fun persistentStoreUnavailabilityQuarantinesUntilRepairAndPreservesRecoveryMaterials() = runBlocking {
        input()
        val storeBefore = File(root, "original-cache-export-persistent.pb")
        check(exportFile.renameTo(storeBefore))
        check(exportFile.mkdir())
        val journal = File(context.filesDir, "restore-settings-v1.bin")
        val existingArchive = File(root, "existing.zip").apply { writeText("original archive $id") }
        var repaired = false
        try {
            assertTrue(runCatching { Restore.restoreLocked(root.path) }.isFailure)
            RestoreRecoveryGate.recordRestoreFailure(context)
            assertTrue(RestoreFileRecovery.hasPending(context))
            assertFalse(RestoreFileRecovery.pendingIsResolved(context))
            assertEquals(RestoreRecoveryStatus.Required, RestoreRecoveryGate.status.value)
            val pending = journal.readBytes()

            val blocked = runCatching { Backup.backupLocked(context, root.path, uploadWebDav = false) }
                .exceptionOrNull()
            assertTrue("Unresolved restore must block backup", blocked is RestoreRecoveryRequiredException)
            assertArrayEquals(pending, journal.readBytes())
            assertEquals("original archive $id", existingArchive.readText())
            assertTrue(exportFile.isDirectory)
            assertEquals(emptyList<File>(), exportFile.listFiles()?.toList())

            RestoreRecoveryGate.retry(context)
            withTimeout(15_000) {
                while (RestoreRecoveryGate.status.value == RestoreRecoveryStatus.Retrying) delay(10)
            }
            assertEquals(RestoreRecoveryStatus.RetryFailed, RestoreRecoveryGate.status.value)
            assertArrayEquals(pending, journal.readBytes())
            assertEquals("original archive $id", existingArchive.readText())

            check(exportFile.delete())
            check(storeBefore.renameTo(exportFile))
            repaired = true
            RestoreRecoveryGate.retry(context)
            withTimeout(15_000) {
                while (RestoreRecoveryGate.status.value == RestoreRecoveryStatus.Retrying) delay(10)
            }
            assertEquals(RestoreRecoveryStatus.ReopenRequired, RestoreRecoveryGate.status.value)
            assertBefore()
            assertEquals("original archive $id", existingArchive.readText())
            assertFalse(journal.exists())
        } finally {
            if (!repaired) {
                exportFile.deleteRecursively()
                if (storeBefore.exists()) check(storeBefore.renameTo(exportFile))
            }
            RestoreRecoveryGate.onStartupReady()
            runCatching { RestoreFileRecovery.recover(context, cleanup = true) }
        }
    }

    @Test fun successCommitsBookBothStoresVideoSamplesAndTimestampTogether() = runBlocking {
        input()
        Restore.restoreLocked(root.path)
        assertEquals(29, appDb.bookDao.getBook(book.bookUrl)!!.durChapterIndex)
        assertEquals("new-$id", prefs.getString(key, null)); assertEquals("video-$id", video.getString(key, null))
        assertEquals((exportBefore.format + 1) % 3, exports.current().format)
        assertEquals(if (mangaBefore.preDownloadCount == 23) 22 else 23, mangas.current().preDownloadCount)
        assertEquals("new-sample", ReplacePreviewConfig.sample(rule.id))
        assertTrue(LocalConfig.lastBackup > 1234L)
        assertFalse(RestoreFileRecovery.hasPending(context))
        assertNull(appDb.cacheDao.get(RestoreFileRecovery.COMMIT_KEY))
    }

    @Test fun missingPreviewSampleClearsAnExistingSampleOnlyOnSuccess() = runBlocking {
        input(includeSample = false)
        Restore.restoreLocked(root.path)
        assertEquals(29, appDb.bookDao.getBook(book.bookUrl)!!.durChapterIndex)
        assertEquals("", ReplacePreviewConfig.sample(rule.id))
        assertTrue(LocalConfig.lastBackup > 1234L)
        assertFalse(RestoreFileRecovery.hasPending(context))
    }

    @Test fun typedJournalReplaysOnlyItsWriteSetAndSupportsLongStrings() = runBlocking {
        backupRestoreMutex.withLock {
            val long = "原".repeat(40_000)
            check(prefs.edit().putString(key, long).putInt("${key}_int", 17).putLong("${key}_long", Long.MAX_VALUE)
                .putFloat("${key}_float", 1.25f).putBoolean("${key}_boolean", true)
                .putStringSet("${key}_set", setOf("一", "two")).commit())
            val before = HashMap(prefs.all)
            val transaction = RestoreSettingsTransaction.prepare(context, UUID.randomUUID().toString(), false)
            transaction.editDefault().putString(key, "changed").remove("${key}_int").putLong("${key}_long", 3)
                .putFloat("${key}_float", 2f).putBoolean("${key}_boolean", false)
                .putStringSet("${key}_set", setOf("new")).putString("${key}_absent", "new").apply()
            check(prefs.edit().putString("${key}_unrelated", "retain-concurrent-value").commit())
            checkNotNull(RestoreSettingsTransaction.read(context)).apply { resolve(false); cleanup() }
            assertEquals(before + ("${key}_unrelated" to "retain-concurrent-value"), prefs.all)
        }
    }

    @Test fun resolvedJournalDoesNotUndoSubsequentUserEdits() = runBlocking {
        backupRestoreMutex.withLock {
            check(prefs.edit().putString(key, "old").commit())
            val transaction = RestoreSettingsTransaction.prepare(context, UUID.randomUUID().toString(), false)
            transaction.editDefault().putString(key, "restored").apply()
            checkNotNull(RestoreSettingsTransaction.read(context)).resolve(false)
            check(prefs.edit().putString(key, "after-recovery").commit())
            checkNotNull(RestoreSettingsTransaction.read(context)).apply { resolve(false); cleanup() }
            assertEquals("after-recovery", prefs.getString(key, null))
        }
    }

    @Test fun malformedSettingsJournalDoesNotMutatePreferences() = runBlocking {
        backupRestoreMutex.withLock {
            val before = HashMap(prefs.all)
            val transaction = RestoreSettingsTransaction.prepare(context, UUID.randomUUID().toString(), false)
            val journal = File(context.filesDir, "restore-settings-v1.bin")
            val valid = journal.readBytes()
            try {
                journal.writeText("truncated")
                assertTrue(runCatching { RestoreSettingsTransaction.read(context) }.isFailure)
                assertEquals(before, prefs.all)
                val corrupt = valid.copyOf().apply { this[size / 2] = (this[size / 2].toInt() xor 1).toByte() }
                journal.writeBytes(corrupt)
                assertTrue(runCatching { RestoreSettingsTransaction.read(context) }.isFailure)
                assertEquals(before, prefs.all)
            } finally { journal.writeBytes(valid); transaction.resolve(false); transaction.cleanup() }
        }
    }

    private fun input(includeSample: Boolean = true) {
        File(root, "bookshelf.json").writeText(GSON.toJson(listOf(book.copy(durChapterIndex = 29))))
        val replacement = rule.copy(name = "New $id").apply { previewText = if (includeSample) "new-sample" else null }
        File(root, "replaceRule.json").writeText(GSON.toJson(listOf(replacement)))
        writePreferenceSnapshot(context, root.path, "config") {
            putString(key, "new-$id")
            putInt(PreferKey.exportType, (exportBefore.format + 1) % 3)
            putInt(PreferKey.mangaPreDownloadNum, if (mangaBefore.preDownloadCount == 23) 22 else 23)
            putInt("readRecordSort", 2)
        }
        writePreferenceSnapshot(context, root.path, "videoConfig") { putString(key, "video-$id") }
    }

    private suspend fun assertBefore() {
        assertEquals("Settings failure committed book", 7, appDb.bookDao.getBook(book.bookUrl)!!.durChapterIndex)
        baseline.forEach { (target, values) -> assertEquals(values, target.all) }
        assertEquals(exportBefore, exports.current()); assertEquals(mangaBefore, mangas.current())
        assertArrayEquals(exportBytes, exportFile.readBytes()); assertArrayEquals(mangaBytes, mangaFile.readBytes())
        assertEquals("Old $id", appDb.replaceRuleDao.all.first { it.id == rule.id }.name)
        assertFalse(RestoreFileRecovery.hasPending(context)); assertNull(appDb.cacheDao.get(RestoreFileRecovery.COMMIT_KEY))
    }

    private fun restore(target: SharedPreferences, values: Map<String, *>) {
        target.edit().clear().apply {
            values.forEach { (key, value) -> when (value) {
                is String -> putString(key, value); is Int -> putInt(key, value); is Long -> putLong(key, value)
                is Float -> putFloat(key, value); is Boolean -> putBoolean(key, value)
                is Set<*> -> { @Suppress("UNCHECKED_CAST") putStringSet(key, value as Set<String>) }
            } }
            check(commit())
        }
    }
}
