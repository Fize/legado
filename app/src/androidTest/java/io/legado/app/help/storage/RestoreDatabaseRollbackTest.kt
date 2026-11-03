package io.legado.app.help.storage

import android.database.sqlite.SQLiteException
import android.os.Bundle
import androidx.core.net.toUri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.appDb
import io.legado.app.data.entities.*
import io.legado.app.help.CacheManager
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.config.ReplacePreviewConfig
import io.legado.app.help.http.CookieStore
import io.legado.app.utils.GSON
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import org.json.JSONObject
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Test-owned rows only; an owned-key SQLite trigger injects a late, real database failure. */
@RunWith(AndroidJUnit4::class)
class RestoreDatabaseRollbackTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val id = UUID.randomUUID().toString().replace("-", "")
    private val number = id.take(12).toLong(16) + 1
    private val book = Book(bookUrl = "https://rollback-$id.invalid/book", name = "Rollback $id",
        author = "Transaction fixture", durChapterIndex = 7)
    private val group = BookGroup(groupId = -number, groupName = "Rollback $id", generation = UUID.randomUUID().toString())
    private val rule = ReplaceRule(id = number, name = "Rollback $id", pattern = "old", isEnabled = false)
    private val domain = "rollback-$id.invalid"
    private val memoryKey = "${domain}_cookie"
    private val originalMemory = CacheManager.getFromMemory(memoryKey)
    private val cookie = Cookie(domain, "keep=yes; sid=old")
    private val cacheKey = "v_txn_$id"
    private val trigger = "compat_restore_$id"
    private val coverTrigger = "compat_cover_$id"
    private val parentTable = "compat_parent_$id"
    private val childTable = "compat_child_$id"
    private val coverKey = "legadoCoverRuleConfig"
    private val originalCover = appDb.cacheDao.get(coverKey)
    private val originalCoverMemory = CacheManager.getFromMemory(coverKey)
    private val lastBackup = LocalConfig.lastBackup
    private val ignored = HashMap(BackupConfig.ignoreConfig)
    private val samplePreferences = context.getSharedPreferences("replace_preview", 0)
    private val sampleKey = "sample_$number"
    private val originalSample = samplePreferences.getString(sampleKey, null)
    private val inputs = mutableListOf<File>()
    private val originalStateFile = File(context.cacheDir, "compat-restore-original/$id.json")
    private var owned = false

    @Before fun setUp() {
        assertNull(appDb.bookDao.getBook(book.bookUrl))
        assertNull(appDb.bookGroupDao.getByID(group.groupId))
        assertNull(appDb.replaceRuleDao.findById(rule.id))
        assertNull(appDb.bookHighlightDao.get(-number))
        assertNull(appDb.cookieDao.get(domain))
        assertNull(appDb.cacheDao.get(cacheKey))
        check(originalStateFile.parentFile!!.mkdirs() || originalStateFile.parentFile!!.isDirectory)
        FileOutputStream(originalStateFile).use { output ->
            val state = JSONObject().put("id", id).put("lastBackup", lastBackup)
                .put("sample", originalSample ?: JSONObject.NULL)
                .put("cover", originalCover?.let { GSON.toJson(it) } ?: JSONObject.NULL)
            output.write(state.toString().toByteArray()); output.fd.sync()
        }
        owned = true
        appDb.bookDao.insert(book)
        appDb.replaceRuleDao.insert(rule)
        ReplacePreviewConfig.saveSample(rule.id, "original sample")
        appDb.cookieDao.insert(cookie)
        CacheManager.putMemory(memoryKey, cookie.cookie)
        LocalConfig.lastBackup = 1234L
        BackupConfig.ignoreConfig["ignoreCookies"] = false
        BackupConfig.ignoreConfig["ignoreSourceVariables"] = false
    }

    @After fun cleanUp() {
        appDb.openHelper.writableDatabase.execSQL("DROP TRIGGER IF EXISTS $trigger")
        appDb.openHelper.writableDatabase.execSQL("DROP TRIGGER IF EXISTS $coverTrigger")
        appDb.openHelper.writableDatabase.execSQL("DROP TABLE IF EXISTS $childTable")
        appDb.openHelper.writableDatabase.execSQL("DROP TABLE IF EXISTS $parentTable")
        if (owned) {
            appDb.bookDao.delete(book)
            appDb.bookGroupDao.delete(group)
            appDb.replaceRuleDao.delete(rule)
            appDb.bookHighlightDao.get(-number)?.let { appDb.bookHighlightDao.delete(it) }
            appDb.cookieDao.delete(domain)
            appDb.cacheDao.delete(cacheKey)
            if (originalCover == null) appDb.cacheDao.delete(coverKey) else appDb.cacheDao.insert(originalCover)
            CacheManager.deleteMemory(coverKey)
            originalCoverMemory?.let { CacheManager.putMemory(coverKey, it) }
            samplePreferences.edit().apply {
                if (originalSample == null) remove(sampleKey) else putString(sampleKey, originalSample)
            }.commit()
            CacheManager.deleteMemory(memoryKey)
            originalMemory?.let { CacheManager.putMemory(memoryKey, it) }
        }
        inputs.forEach(File::delete)
        LocalConfig.lastBackup = lastBackup
        BackupConfig.ignoreConfig.clear()
        BackupConfig.ignoreConfig.putAll(ignored)
        originalStateFile.delete()
    }

    @Test fun groupConstraintFailureRollsBackEarlierBookUpdate() = runBlocking {
        val groupJson = GSON.toJsonTree(group).asJsonObject.apply { add("groupName", null) }.toString()
        val result = restore("bookGroup.json" to groupJson.let { "[$it]" })
        observe(result)
        assertTrue(result.isFailure)
        assertEquals(7, appDb.bookDao.getBook(book.bookUrl)!!.durChapterIndex)
        assertNull(appDb.bookGroupDao.getByID(group.groupId))
        assertEquals(1234L, LocalConfig.lastBackup)
    }

    @Test fun highlightConstraintFailureMustNotReportSuccess() = runBlocking {
        val highlight = """[{"time":${-number},"bookUrl":"${book.bookUrl}","bookName":null,"bookAuthor":"fixture","style":"{}"}]"""
        val result = restore("highlight.json" to highlight)
        observe(result)
        assertTrue("A failed annotation insert must fail the restore", result.isFailure)
        assertEquals(7, appDb.bookDao.getBook(book.bookUrl)!!.durChapterIndex)
        assertEquals(1234L, LocalConfig.lastBackup)
    }

    @Test fun duplicateHighlightRulesMustNotReportSuccess() = runBlocking {
        val uuid = UUID.randomUUID().toString()
        val rules = GSON.toJson(listOf(HighlightRule(uuid = uuid), HighlightRule(uuid = uuid)))
        val previous = GSON.toJson(appDb.highlightRuleDao.all)
        val result = restore("highlightRule.json" to rules)
        observe(result)
        assertTrue(result.isFailure)
        assertEquals(previous, GSON.toJson(appDb.highlightRuleDao.all))
        assertEquals(7, appDb.bookDao.getBook(book.bookUrl)!!.durChapterIndex)
        assertEquals(1234L, LocalConfig.lastBackup)
    }

    @Test fun lateConstraintRollsBackEarlierDatabaseRows() = runBlocking {
        val result = lateFailure()
        observe(result)
        requireSqlFailure(result)
        assertEquals(7, appDb.bookDao.getBook(book.bookUrl)!!.durChapterIndex)
        assertEquals("old", appDb.replaceRuleDao.findById(rule.id)!!.pattern)
        assertEquals(cookie, appDb.cookieDao.get(domain))
        assertNull(appDb.cacheDao.get(cacheKey))
        assertEquals(1234L, LocalConfig.lastBackup)
    }

    @Test fun lateConstraintDoesNotPublishCookieMemory() = runBlocking {
        val result = lateFailure()
        observe(result)
        requireSqlFailure(result)
        assertEquals(cookie.cookie, CacheManager.getFromMemory(memoryKey))
    }

    @Test fun lateConstraintDoesNotPublishReplacementSamples() = runBlocking {
        val result = lateFailure()
        observe(result)
        requireSqlFailure(result)
        assertEquals("original sample", ReplacePreviewConfig.sample(rule.id))
    }

    @Test fun lateCoverCacheFailureRollsBackAllEarlierWrites() = runBlocking {
        val db = appDb.openHelper.writableDatabase
        db.execSQL("""CREATE TRIGGER $coverTrigger BEFORE INSERT ON caches
            WHEN NEW.key='$coverKey' AND instr(NEW.value,'$id')>0
            BEGIN SELECT RAISE(ABORT,'compat cover cache failure'); END""")
        val result = restore(*(payload() + ("coverRule.json" to coverJson())))
        observe(result)
        requireSqlFailure(result)
        assertFullyRolledBack()
    }

    @Test fun deferredForeignKeyViolationIsRejectedBeforeCommit() = runBlocking {
        val db = appDb.openHelper.writableDatabase
        db.execSQL("CREATE TABLE $parentTable(id INTEGER PRIMARY KEY)")
        db.execSQL("""CREATE TABLE $childTable(id INTEGER PRIMARY KEY,parentId INTEGER,
            FOREIGN KEY(parentId) REFERENCES $parentTable(id) DEFERRABLE INITIALLY DEFERRED)""")
        db.execSQL("""CREATE TRIGGER $coverTrigger AFTER INSERT ON caches
            WHEN NEW.key='$coverKey' AND instr(NEW.value,'$id')>0
            BEGIN INSERT INTO $childTable(id,parentId) VALUES(1,1); END""")
        val result = restore(*(payload() + ("coverRule.json" to coverJson())))
        observe(result)
        requireSqlFailure(result)
        assertEquals(originalCoverMemory, CacheManager.getFromMemory(coverKey))
        assertFullyRolledBack()
    }

    private fun coverJson() = """{"enable":false,"searchUrl":"","coverRule":"","compatFixture":"$id"}"""

    private fun assertFullyRolledBack() {
        assertFalse(appDb.inTransaction())
        assertEquals(7, appDb.bookDao.getBook(book.bookUrl)!!.durChapterIndex)
        assertEquals(cookie, appDb.cookieDao.get(domain))
        assertEquals(cookie.cookie, CacheManager.getFromMemory(memoryKey))
        assertEquals("old", appDb.replaceRuleDao.findById(rule.id)!!.pattern)
        assertEquals("original sample", ReplacePreviewConfig.sample(rule.id))
        assertNull(appDb.cacheDao.get(cacheKey))
        assertEquals(originalCover, appDb.cacheDao.get(coverKey))
        assertEquals(1234L, LocalConfig.lastBackup)
    }

    @Test fun successfulCommitMergesCookiesAndPublishesSamples() = runBlocking {
        val result = restore(*payload())
        observe(result)
        assertTrue(result.isSuccess)
        assertEquals(29, appDb.bookDao.getBook(book.bookUrl)!!.durChapterIndex)
        assertEquals("new", appDb.replaceRuleDao.findById(rule.id)!!.pattern)
        assertEquals("replacement sample", ReplacePreviewConfig.sample(rule.id))
        val expected = mapOf("keep" to "yes", "sid" to "new", "added" to "two")
        assertEquals(expected, CookieStore.cookieToMap(appDb.cookieDao.get(domain)!!.cookie))
        assertEquals(expected, CookieStore.cookieToMap(CacheManager.getFromMemory(memoryKey) as String))
        assertTrue(LocalConfig.lastBackup > 1234L)
    }

    @Test fun lanTransferStillIgnoresCookieAndRuntimePayloads() = runBlocking {
        installTrigger()
        val result = runCatching { Restore.restoreOrThrow(context, archive(*payload()).toUri(), lanTransfer = true) }
        observe(result)
        assertTrue(result.isSuccess)
        assertEquals(29, appDb.bookDao.getBook(book.bookUrl)!!.durChapterIndex)
        assertEquals(cookie, appDb.cookieDao.get(domain))
        assertEquals(cookie.cookie, CacheManager.getFromMemory(memoryKey))
        assertNull(appDb.cacheDao.get(cacheKey))
    }

    private fun installTrigger() {
        appDb.openHelper.writableDatabase.execSQL("""CREATE TRIGGER $trigger BEFORE INSERT ON caches
            WHEN NEW.key = '$cacheKey' BEGIN SELECT RAISE(ABORT, 'compat late database failure'); END""")
    }

    private suspend fun lateFailure(): Result<Unit> {
        installTrigger()
        return restore(*payload())
    }

    private fun payload(): Array<Pair<String, String>> {
        val updatedRule = rule.copy(pattern = "new").apply { previewText = "replacement sample" }
        return arrayOf("replaceRule.json" to GSON.toJson(listOf(updatedRule)),
            "cookies.json" to """[{"url":"https://$domain","cookie":"sid=new"},{"url":"https://$domain/path","cookie":"added=two"}]""",
            "runtimeSourceCache.json" to """[{"key":"$cacheKey","value":"new","deadline":0}]""")
    }

    private suspend fun restore(vararg entries: Pair<String, String>): Result<Unit> =
        runCatching { Restore.restoreOrThrow(context, archive(*entries).toUri()) }

    private fun requireSqlFailure(result: Result<Unit>) {
        assertTrue(generateSequence(result.exceptionOrNull()) { it.cause }.any { it is SQLiteException })
    }

    private fun observe(result: Result<Unit>) {
        val state = mapOf("success" to result.isSuccess,
            "errors" to generateSequence(result.exceptionOrNull()) { it.cause }.map { it.javaClass.name }.toList(),
            "bookChapter" to appDb.bookDao.getBook(book.bookUrl)?.durChapterIndex,
            "cookieDb" to appDb.cookieDao.get(domain)?.cookie,
            "cookieMemory" to CacheManager.getFromMemory(memoryKey),
            "previewSample" to ReplacePreviewConfig.sample(rule.id), "lastBackup" to LocalConfig.lastBackup)
        InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
            putString("rollback_fixture_state", GSON.toJson(state))
        })
    }

    private fun archive(vararg entries: Pair<String, String>): File =
        File.createTempFile("restore-db-", ".zip", context.cacheDir).also { file ->
            inputs += file
            ZipOutputStream(file.outputStream()).use { zip ->
                (listOf("bookshelf.json" to GSON.toJson(listOf(book.copy(durChapterIndex = 29)))) + entries)
                    .forEach { (name, value) ->
                        zip.putNextEntry(ZipEntry(name)); zip.write(value.toByteArray()); zip.closeEntry()
                    }
            }
        }
}
