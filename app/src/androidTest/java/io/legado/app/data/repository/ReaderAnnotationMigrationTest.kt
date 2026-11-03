package io.legado.app.data.repository

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ReaderAnnotationMigrationTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    private val name="annotation-migration-${UUID.randomUUID()}"
    @get:Rule val helper=MigrationTestHelper(instrumentation,AppDatabase::class.java,DatabaseMigrations.autoMigrationSpecs.toList())
    @After fun cleanup() { context.deleteDatabase(name) }
    @Test fun upgrade126PreservesExistingBookmarksAndHighlights() {
        helper.createDatabase(name,126).use {
            it.execSQL("INSERT INTO bookmarks(time,bookName,bookAuthor,chapterIndex,chapterPos,chapterName,bookText,content) VALUES(7,'Retained','Author',2,31,'Chapter','amber 😀','old note')")
            it.execSQL("INSERT INTO highlights(time,bookUrl,chapterUrl,bookName,bookAuthor,chapterIndex,chapterPos,chapterPosEnd,layoutTitleLength,chapterName,bookText,style,note) VALUES(8,'book','chapter','Retained','Author',2,31,39,6,'Chapter','amber 😀','{}','highlight note')")
        }
        val db=Room.databaseBuilder(context,AppDatabase::class.java,name).addLegadoMigrations().build()
        try {
            assertEquals(132,db.openHelper.writableDatabase.version)
            assertEquals("old note",db.bookmarkDao.get(7)!!.content)
            assertEquals(31,db.bookmarkDao.get(7)!!.chapterPos)
            assertEquals("highlight note",db.bookHighlightDao.get(8)!!.note)
            assertEquals(39,db.bookHighlightDao.get(8)!!.chapterPosEnd)
            assertNull(db.readerAnnotationDao.draft("no-reader"))
        } finally { db.close() }
    }
}
