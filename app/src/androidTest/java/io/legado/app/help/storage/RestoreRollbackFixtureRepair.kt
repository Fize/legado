package io.legado.app.help.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.appDb
import io.legado.app.data.entities.Cache
import io.legado.app.help.CacheManager
import io.legado.app.help.config.LocalConfig
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Repair only a host-identified, abandoned test fixture after an instrumentation crash. */
@RunWith(AndroidJUnit4::class)
class RestoreRollbackFixtureRepair {
    @Test fun removeOwnedFixtureAndRestoreSavedMetadata() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Host-only fixture repair", args.getString("repairRollbackFixture") == "yes")
        val id = requireNotNull(args.getString("rollbackFixtureId"))
        require(id.matches(Regex("[a-f0-9]{32}")))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val number = id.take(12).toLong(16) + 1
        val stateFile = File(context.cacheDir, "compat-restore-original/$id.json")
        val state = if (stateFile.isFile) JSONObject(stateFile.readText()) else null
        require(state == null || state.getString("id") == id)
        val lastBackup = state?.getLong("lastBackup")
            ?: requireNotNull(args.getString("legacyFixtureLastBackup")).toLong()
        val url = "https://rollback-$id.invalid/book"
        val book = appDb.bookDao.getBook(url)
        require(book == null || (book.name == "Rollback $id" && book.author == "Transaction fixture"))
        val rule = appDb.replaceRuleDao.findById(number)
        require(rule == null || rule.name == "Rollback $id")
        val coverKey = "legadoCoverRuleConfig"
        if (state == null) {
            require(appDb.cacheDao.get(coverKey)?.value?.contains(id) != true) {
                "Missing original cover snapshot; refusing to guess its value"
            }
        }
        val db = appDb.openHelper.writableDatabase
        db.execSQL("DROP TRIGGER IF EXISTS compat_restore_$id")
        db.execSQL("DROP TRIGGER IF EXISTS compat_cover_$id")
        db.execSQL("DROP TABLE IF EXISTS compat_child_$id")
        db.execSQL("DROP TABLE IF EXISTS compat_parent_$id")
        book?.let { appDb.bookDao.delete(it) }
        rule?.let { appDb.replaceRuleDao.delete(it) }
        appDb.bookHighlightDao.get(-number)?.let { appDb.bookHighlightDao.delete(it) }
        appDb.bookGroupDao.getByID(-number)?.let {
            require(it.groupName == "Rollback $id"); appDb.bookGroupDao.delete(it)
        }
        appDb.cookieDao.delete("rollback-$id.invalid")
        CacheManager.deleteMemory("rollback-$id.invalid_cookie")
        appDb.cacheDao.delete("v_txn_$id")
        context.getSharedPreferences("replace_preview", 0).edit().apply {
            if (state == null || state.isNull("sample")) remove("sample_$number")
            else putString("sample_$number", state.getString("sample"))
        }.commit()
        if (state != null) {
            if (state.isNull("cover")) appDb.cacheDao.delete(coverKey)
            else appDb.cacheDao.insert(GSON.fromJsonObject<Cache>(state.getString("cover")).getOrThrow())
            CacheManager.deleteMemory(coverKey)
        }
        check(LocalConfig.edit().putLong("lastBackup", lastBackup).commit())
        stateFile.delete()
        require(appDb.bookDao.getBook(url) == null && appDb.replaceRuleDao.findById(number) == null)
    }
}
