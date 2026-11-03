package io.legado.app.help.storage

import android.os.Process
import androidx.core.net.toUri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.Cookie
import io.legado.app.help.CacheManager
import io.legado.app.help.config.LocalConfig
import io.legado.app.utils.GSON
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Host-only probe: a fixture-key trigger spills WAL pages and keeps the real restore uncommitted. */
@RunWith(AndroidJUnit4::class)
class RestoreDatabaseDeathProbe {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun root(): File {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Run through the host database restore probe", args.getString("databaseRestoreDeath") == "yes")
        val id = requireNotNull(args.getString("databaseRestoreId"))
        require(UUID.fromString(id).toString() == id)
        return File(context.cacheDir, "compat-db-restore/$id")
    }
    private fun token(root: File) = root.name.replace("-", "")
    private fun book(root: File) = Book(bookUrl = "https://db-death-${token(root)}.invalid/book",
        name = "Database death ${token(root)}", author = "Owned fixture", durChapterIndex = 7)
    private fun domain(root: File) = "db-death-${token(root)}.invalid"
    private fun prefix(root: File) = "v_db_${token(root)}_"
    private fun trigger(root: File) = "compat_db_death_${token(root)}"
    private fun save(file: File, value: JSONObject) {
        FileOutputStream(file).use { output -> output.write(value.toString().toByteArray()); output.fd.sync() }
    }
    private fun countCache(root: File): Int = appDb.openHelper.writableDatabase.query(
        "SELECT count(*) FROM caches WHERE substr(key,1,?)=?", arrayOf<Any>(prefix(root).length, prefix(root)),
    ).use { cursor -> check(cursor.moveToFirst()); cursor.getInt(0) }

    private fun cleanup(root: File, lastBackup: Long) {
        val db = appDb.openHelper.writableDatabase
        db.execSQL("DROP TRIGGER IF EXISTS ${trigger(root)}")
        appDb.bookDao.delete(book(root))
        appDb.cookieDao.delete(domain(root))
        CacheManager.deleteMemory("${domain(root)}_cookie")
        db.execSQL("DELETE FROM caches WHERE substr(key,1,?)=?", arrayOf<Any>(prefix(root).length, prefix(root)))
        LocalConfig.lastBackup = lastBackup
    }

    @Test fun holdRealRestoreBeforeCommit() = runBlocking {
        val root = root().apply { check(mkdirs()) }
        val lastBackup = LocalConfig.lastBackup
        val ignored = HashMap(BackupConfig.ignoreConfig)
        assertNull(appDb.bookDao.getBook(book(root).bookUrl))
        assertNull(appDb.cookieDao.get(domain(root)))
        assertEquals(0, countCache(root))
        try {
            appDb.bookDao.insert(book(root))
            appDb.cookieDao.insert(Cookie(domain(root), "sid=old"))
            CacheManager.putMemory("${domain(root)}_cookie", "sid=old")
            BackupConfig.ignoreConfig["ignoreCookies"] = false
            BackupConfig.ignoreConfig["ignoreSourceVariables"] = false
            LocalConfig.lastBackup = 1234L
            val input = File(root, "input.zip")
            ZipOutputStream(input.outputStream()).use { zip ->
                mapOf("bookshelf.json" to GSON.toJson(listOf(book(root).copy(durChapterIndex = 29))),
                    "cookies.json" to """[{"url":"https://${domain(root)}","cookie":"sid=new"}]""",
                    "runtimeSourceCache.json" to """[{"key":"${prefix(root)}barrier","value":"new","deadline":0}]""")
                    .forEach { (name, json) -> zip.putNextEntry(ZipEntry(name)); zip.write(json.toByteArray()); zip.closeEntry() }
            }
            val db = appDb.openHelper.writableDatabase
            db.query("PRAGMA journal_mode").use { check(it.moveToFirst() && it.getString(0) == "wal") }
            // CTEs are embedded SELECTs: top-level WITH is not supported in a trigger body.
            db.execSQL("""CREATE TRIGGER ${trigger(root)} BEFORE INSERT ON caches
                WHEN NEW.key = '${prefix(root)}barrier' BEGIN
                  INSERT INTO caches(key,value,deadline)
                    SELECT '${prefix(root)}spill_'||n, printf('%02048d',n), 0 FROM (
                      WITH RECURSIVE seq(n) AS (SELECT 1 UNION ALL SELECT n+1 FROM seq WHERE n<8192)
                      SELECT n FROM seq);
                  SELECT sum(n) FROM (
                    WITH RECURSIVE seq(n) AS (SELECT 1 UNION ALL SELECT n+1 FROM seq WHERE n<1000000000)
                    SELECT n FROM seq);
                END""")
            var checkpointed = false
            repeat(30) {
                if (!checkpointed) {
                    checkpointed = db.query("PRAGMA wal_checkpoint(TRUNCATE)").use {
                        check(it.moveToFirst()); it.getInt(0) == 0
                    }
                    if (!checkpointed) Thread.sleep(100)
                }
            }
            check(checkpointed)
            save(File(root, "ready.json"), JSONObject().put("id", root.name).put("pid", Process.myPid())
                .put("lastBackupBefore", lastBackup).put("walBaseline", File(context.getDatabasePath("legado.db").path + "-wal").length())
                .put("spillPrefix", prefix(root) + "spill_").put("bookUrl", book(root).bookUrl))
            Restore.restoreOrThrow(context, input.toUri())
            fail("Host must terminate this restore while the transaction is uncommitted")
        } catch (error: Throwable) {
            cleanup(root, lastBackup)
            BackupConfig.ignoreConfig.clear(); BackupConfig.ignoreConfig.putAll(ignored)
            throw error
        }
    }

    @Test fun verifyRollbackAfterHostKill() {
        val root = root()
        val ready = JSONObject(File(root, "ready.json").readText())
        val chapter = appDb.bookDao.getBook(book(root).bookUrl)?.durChapterIndex
        val cookie = appDb.cookieDao.get(domain(root))?.cookie
        val cacheCount = countCache(root)
        val currentLastBackup = LocalConfig.lastBackup
        save(File(root, "recovered.json"), JSONObject().put("id", root.name).put("pid", Process.myPid())
            .put("bookChapter", chapter).put("cookie", cookie).put("cacheRows", cacheCount)
            .put("lastBackup", currentLastBackup))
        try {
            assertEquals(7, chapter)
            assertEquals("sid=old", cookie)
            assertEquals(0, cacheCount)
            assertEquals(1234L, currentLastBackup)
        } finally {
            cleanup(root, ready.getLong("lastBackupBefore"))
        }
        check(appDb.bookDao.getBook(book(root).bookUrl) == null && appDb.cookieDao.get(domain(root)) == null)
        check(countCache(root) == 0)
    }
}
