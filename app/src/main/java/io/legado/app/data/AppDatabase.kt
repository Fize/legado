package io.legado.app.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import android.util.Log
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import io.legado.app.constant.PreferKey
import io.legado.app.data.dao.BookChapterDao
import io.legado.app.data.dao.BookInfoDeleteDao
import io.legado.app.data.entities.BookInfoDeleteCommand
import io.legado.app.data.entities.BookInfoDeletionLease
import io.legado.app.data.dao.BookInfoRequestDao
import io.legado.app.data.entities.BookInfoRequest
import io.legado.app.data.entities.BookInfoChildRequest
import io.legado.app.data.dao.ReaderSessionDao
import io.legado.app.data.entities.ReaderSessionState
import io.legado.app.data.entities.ReaderBookOwner
import io.legado.app.data.entities.ReaderCatalogRequest
import io.legado.app.data.dao.BookDao
import io.legado.app.data.dao.BookGroupDao
import io.legado.app.data.dao.BookHighlightDao
import io.legado.app.data.dao.BookMemoDao
import io.legado.app.data.dao.BookSourceDao
import io.legado.app.data.dao.BookmarkDao
import io.legado.app.data.dao.CacheDao
import io.legado.app.data.dao.CookieDao
import io.legado.app.data.dao.DictRuleDao
import io.legado.app.data.dao.HttpTTSDao
import io.legado.app.data.dao.HighlightRuleDao
import io.legado.app.data.dao.KeyboardAssistsDao
import io.legado.app.data.dao.ReadRecordDao
import io.legado.app.data.dao.ReplaceRuleDao
import io.legado.app.data.dao.RuleSubDao
import io.legado.app.data.dao.SearchBookDao
import io.legado.app.data.dao.SearchSessionDao
import io.legado.app.data.dao.SearchKeywordDao
import io.legado.app.data.dao.ServerDao
import io.legado.app.data.dao.TxtTocRuleDao
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookGroup
import io.legado.app.data.entities.BookHighlight
import io.legado.app.data.entities.BookMemo
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourceCheckState
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.data.entities.Bookmark
import io.legado.app.data.entities.Cache
import io.legado.app.data.entities.Cookie
import io.legado.app.data.entities.DictRule
import io.legado.app.data.entities.HttpTTS
import io.legado.app.data.entities.HighlightRule
import io.legado.app.data.entities.KeyboardAssist
import io.legado.app.data.entities.ReadRecord
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.data.entities.RuleSub
import io.legado.app.data.entities.SearchBook
import io.legado.app.data.entities.SearchScreenDraft
import io.legado.app.data.entities.SearchSessionSource
import io.legado.app.data.entities.SearchSession
import io.legado.app.data.entities.SearchSessionResult
import io.legado.app.data.entities.SearchKeyword
import io.legado.app.data.entities.Server
import io.legado.app.data.entities.TxtTocRule
import io.legado.app.help.DefaultData
import org.intellij.lang.annotations.Language
import splitties.init.appCtx
import java.util.Locale

val appDb by lazy {
    Room.databaseBuilder(appCtx, AppDatabase::class.java, AppDatabase.DATABASE_NAME)
        .fallbackToDestructiveMigrationFrom(false, 1, 2, 3, 4, 5, 6, 7, 8, 9)
        .addLegadoMigrations()
        .allowMainThreadQueries()
        .addCallback(AppDatabase.dbCallback)
        .build()
}

@Database(
    version = 132,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 130, to = 131),
        AutoMigration(from = 131, to = 132),
        AutoMigration(from = 129, to = 130),
        AutoMigration(from = 128, to = 129),
        AutoMigration(from = 127, to = 128),
        AutoMigration(from = 126, to = 127),
        AutoMigration(from = 125, to = 126),
        AutoMigration(from = 124, to = 125),
        AutoMigration(from = 123, to = 124),
        AutoMigration(from = 122, to = 123),
        AutoMigration(from = 121, to = 122),
        AutoMigration(from = 120, to = 121),
        AutoMigration(from = 119, to = 120),
        AutoMigration(from = 118, to = 119),
        AutoMigration(from = 116, to = 117),
        AutoMigration(from = 115, to = 116),
        AutoMigration(from = 43, to = 44),
        AutoMigration(from = 44, to = 45),
        AutoMigration(from = 45, to = 46),
        AutoMigration(from = 46, to = 47),
        AutoMigration(from = 47, to = 48),
        AutoMigration(from = 48, to = 49),
        AutoMigration(from = 49, to = 50),
        AutoMigration(from = 50, to = 51),
        AutoMigration(from = 51, to = 52),
        AutoMigration(from = 52, to = 53),
        AutoMigration(from = 53, to = 54),
        AutoMigration(from = 54, to = 55, spec = DatabaseMigrations.Migration_54_55::class),
        AutoMigration(from = 55, to = 56),
        AutoMigration(from = 56, to = 57),
        AutoMigration(from = 57, to = 58),
        AutoMigration(from = 58, to = 59),
        AutoMigration(from = 59, to = 60),
        AutoMigration(from = 60, to = 61),
        AutoMigration(from = 61, to = 62),
        AutoMigration(from = 62, to = 63),
        AutoMigration(from = 63, to = 64),
        AutoMigration(from = 64, to = 65, spec = DatabaseMigrations.Migration_64_65::class),
        AutoMigration(from = 65, to = 66),
        AutoMigration(from = 66, to = 67),
        AutoMigration(from = 67, to = 68),
        AutoMigration(from = 68, to = 69),
        AutoMigration(from = 69, to = 70),
        AutoMigration(from = 70, to = 71),
        AutoMigration(from = 71, to = 72),
        AutoMigration(from = 72, to = 73),
        AutoMigration(from = 73, to = 74),
        AutoMigration(from = 74, to = 75),
        AutoMigration(from = 75, to = 76),
        AutoMigration(from = 76, to = 77),
        AutoMigration(from = 77, to = 78),
        AutoMigration(from = 78, to = 79),
        AutoMigration(from = 79, to = 80),
        AutoMigration(from = 80, to = 81, spec = DatabaseMigrations.Migration_80_81::class),
        AutoMigration(from = 81, to = 82),
        AutoMigration(from = 82, to = 83),
        AutoMigration(from = 83, to = 84, spec = DatabaseMigrations.Migration_83_84::class),
        AutoMigration(from = 84, to = 85, spec = DatabaseMigrations.Migration_84_85::class),
        AutoMigration(from = 85, to = 86),
        AutoMigration(from = 86, to = 87),
        AutoMigration(from = 87, to = 88),
        AutoMigration(from = 88, to = 89),
        AutoMigration(from = 89, to = 90),
        AutoMigration(from = 90, to = 91),
        AutoMigration(from = 91, to = 92),
        AutoMigration(from = 92, to = 93),
        AutoMigration(from = 93, to = 94),
        AutoMigration(from = 94, to = 95),
        AutoMigration(from = 95, to = 96),
        AutoMigration(from = 96, to = 97, spec = DatabaseMigrations.Migration_96_97::class),
        AutoMigration(from = 97, to = 98),
        AutoMigration(from = 98, to = 99, spec = DatabaseMigrations.Migration_98_99::class),
        AutoMigration(from = 99, to = 100),
        AutoMigration(from = 101, to = 102, spec = DatabaseMigrations.Migration_101_102::class),
        AutoMigration(from = 102, to = 103),
        AutoMigration(from = 103, to = 104),
        AutoMigration(from = 110, to = 111, spec = DatabaseMigrations.Migration_110_111::class),
    ],
    entities = [io.legado.app.data.entities.BookTransferBatch::class, io.legado.app.data.entities.BookTransferTask::class, io.legado.app.data.entities.BookTransferLease::class, io.legado.app.data.entities.CacheManagerRecord::class, io.legado.app.data.entities.HighlightRuleImportSession::class, io.legado.app.data.entities.HighlightRuleImportRow::class, io.legado.app.data.entities.HighlightRuleManagerRecord::class, io.legado.app.data.entities.HighlightRuleDraft::class, io.legado.app.data.entities.ReaderAnnotationDraft::class, io.legado.app.data.entities.BookContentSearchSession::class, io.legado.app.data.entities.BookContentSearchHit::class,io.legado.app.data.entities.ReaderDetailRequest::class, io.legado.app.data.entities.ReaderDetailBookLease::class,
        io.legado.app.data.entities.BookInfoUploadCommand::class, io.legado.app.data.entities.BookInfoUploadLease::class,
        BookInfoDeleteCommand::class, BookInfoDeletionLease::class, Book::class, BookGroup::class, BookSource::class, BookChapter::class,
        ReplaceRule::class, SearchBook::class, SearchKeyword::class, Cookie::class,
        Bookmark::class, TxtTocRule::class, ReadRecord::class, HttpTTS::class, Cache::class,
        RuleSub::class, DictRule::class, KeyboardAssist::class, Server::class,
        BookHighlight::class, HighlightRule::class, BookSourceCheckState::class,
        BookMemo::class, BookInfoRequest::class, BookInfoChildRequest::class, ReaderSessionState::class, ReaderBookOwner::class, ReaderCatalogRequest::class, SearchSession::class, SearchSessionResult::class, SearchScreenDraft::class, SearchSessionSource::class],
    views = [BookSourcePart::class],
)
abstract class AppDatabase : RoomDatabase() {

    abstract val bookTransferDao: io.legado.app.data.dao.BookTransferDao
    abstract val highlightRuleImportDao: io.legado.app.data.dao.HighlightRuleImportDao
    abstract val highlightRuleManagerDao: io.legado.app.data.dao.HighlightRuleManagerDao
    abstract val highlightRuleDraftDao: io.legado.app.data.dao.HighlightRuleDraftDao
    abstract val readerAnnotationDao: io.legado.app.data.dao.ReaderAnnotationDao

    abstract val bookContentSearchDao: io.legado.app.data.dao.BookContentSearchDao

    abstract val bookInfoDeleteDao: BookInfoDeleteDao
    abstract val bookInfoUploadDao: io.legado.app.data.dao.BookInfoUploadDao
    abstract val readerDetailDao: io.legado.app.data.dao.ReaderDetailDao
    abstract val bookInfoRequestDao: BookInfoRequestDao
    abstract val readerSessionDao: ReaderSessionDao
    abstract val bookDao: BookDao
    abstract val bookGroupDao: BookGroupDao
    abstract val bookHighlightDao: BookHighlightDao
    abstract val bookMemoDao: BookMemoDao
    abstract val highlightRuleDao: HighlightRuleDao
    abstract val bookSourceDao: BookSourceDao
    abstract val bookChapterDao: BookChapterDao
    abstract val replaceRuleDao: ReplaceRuleDao
    abstract val searchBookDao: SearchBookDao
    abstract val searchSessionDao: SearchSessionDao
    abstract val searchKeywordDao: SearchKeywordDao
    abstract val bookmarkDao: BookmarkDao
    abstract val cookieDao: CookieDao
    abstract val txtTocRuleDao: TxtTocRuleDao
    abstract val readRecordDao: ReadRecordDao
    abstract val httpTTSDao: HttpTTSDao
    abstract val cacheDao: CacheDao
    abstract val ruleSubDao: RuleSubDao
    abstract val dictRuleDao: DictRuleDao
    abstract val keyboardAssistsDao: KeyboardAssistsDao
    abstract val serverDao: ServerDao

    companion object {

        const val DATABASE_NAME = "legado.db"

        const val BOOK_TABLE_NAME = "books"
        const val BOOK_GROUP_TABLE_NAME = "book_groups"
        const val BOOK_SOURCE_TABLE_NAME = "book_sources"
        const val RSS_SOURCE_TABLE_NAME = "rssSources"

        val dbCallback = object : Callback() {

            override fun onCreate(db: SupportSQLiteDatabase) {
                // 只在 API 级别 23 (Marshmallow) 及以上版本尝试设置区域设置
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    try {
                        Log.d("AppDatabaseCallback", "准备 设置 locale for API ${Build.VERSION.SDK_INT}...")
                        db.setLocale(Locale.CHINESE)
                        // 在 21 上报错，但无法拦截
                        Log.d("AppDatabaseCallback", "成功 设置 locale for API ${Build.VERSION.SDK_INT}.")
                    } catch (e: Exception) {
                        Log.e("AppDatabaseCallback", "错误 设置 locale in onCreate for API ${Build.VERSION.SDK_INT}", e)
                    }
                } else {
                    Log.i("AppDatabaseCallback", "跳过 setLocale for API ${Build.VERSION.SDK_INT} (below M).")
                }
            }

            override fun onOpen(db: SupportSQLiteDatabase) {
                db.execSQL("delete from book_groups where groupId = ${BookGroup.IdAll}")
                @Language("sql")
                val insertBookGroupLocalSql = """
                    insert into book_groups(groupId, groupName, 'order', enableRefresh, show, generation)
                    select ${BookGroup.IdLocal}, '本地', -9, 0, 1, lower(hex(randomblob(16)))
                    where not exists (select * from book_groups where groupId = ${BookGroup.IdLocal})
                """.trimIndent()
                db.execSQL(insertBookGroupLocalSql)
                @Language("sql")
                val insertBookGroupMusicSql = """
                    insert into book_groups(groupId, groupName, 'order', show, generation)
                    select ${BookGroup.IdAudio}, '音频', -8, 1, lower(hex(randomblob(16)))
                    where not exists (select * from book_groups where groupId = ${BookGroup.IdAudio})
                """.trimIndent()
                db.execSQL(insertBookGroupMusicSql)
                @Language("sql")
                val insertBookGroupNetNoneGroupSql = """
                    insert into book_groups(groupId, groupName, 'order', show, generation)
                    select ${BookGroup.IdNetNone}, '网络未分组', -7, 1, lower(hex(randomblob(16)))
                    where not exists (select * from book_groups where groupId = ${BookGroup.IdNetNone})
                """.trimIndent()
                db.execSQL(insertBookGroupNetNoneGroupSql)
                @Language("sql")
                val insertBookGroupLocalNoneGroupSql = """
                    insert into book_groups(groupId, groupName, 'order', show, generation)
                    select ${BookGroup.IdLocalNone}, '本地未分组', -6, 0, lower(hex(randomblob(16)))
                    where not exists (select * from book_groups where groupId = ${BookGroup.IdLocalNone})
                """.trimIndent()
                db.execSQL(insertBookGroupLocalNoneGroupSql)
                @Language("sql")
                val insertBookGroupVideoSql = """
                    insert into book_groups(groupId, groupName, 'order', show, generation)
                    select ${BookGroup.IdVideo}, '视频', -5, 1, lower(hex(randomblob(16)))
                    where not exists (select * from book_groups where groupId = ${BookGroup.IdVideo})
                    """.trimIndent()
                db.execSQL(insertBookGroupVideoSql)
                @Language("sql")
                val insertBookGroupErrorSql = """
                    insert into book_groups(groupId, groupName, 'order', show, generation)
                    select ${BookGroup.IdError}, '更新失败', -1, 1, lower(hex(randomblob(16)))
                    where not exists (select * from book_groups where groupId = ${BookGroup.IdError})
                """.trimIndent()
                db.execSQL(insertBookGroupErrorSql)
                @Language("sql")
                val upBookSourceLoginUiSql =
                    "update book_sources set loginUi = null where loginUi = 'null'"
                db.execSQL(upBookSourceLoginUiSql)
                @Language("sql")
                val upHttpTtsLoginUiSql =
                    "update httpTTS set loginUi = null where loginUi = 'null'"
                db.execSQL(upHttpTtsLoginUiSql)
                @Language("sql")
                val upHttpTtsConcurrentRateSql =
                    "update httpTTS set concurrentRate = '0' where concurrentRate is null"
                db.execSQL(upHttpTtsConcurrentRateSql)
                db.query("select * from keyboardAssists order by serialNo").use {
                    if (it.count == 0) {
                        DefaultData.keyboardAssists.forEach { keyboardAssist ->
                            val contentValues = ContentValues().apply {
                                put("type", keyboardAssist.type)
                                put("key", keyboardAssist.key)
                                put("value", keyboardAssist.value)
                                put("serialNo", keyboardAssist.serialNo)
                            }
                            db.insert(
                                "keyboardAssists",
                                SQLiteDatabase.CONFLICT_REPLACE,
                                contentValues
                            )
                        }
                    }
                }
            }
        }

    }

}
