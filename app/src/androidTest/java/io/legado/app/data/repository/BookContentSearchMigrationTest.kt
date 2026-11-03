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
class BookContentSearchMigrationTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    private val name="content-search-migration-${UUID.randomUUID()}"
    @get:Rule val helper=MigrationTestHelper(instrumentation,AppDatabase::class.java,DatabaseMigrations.autoMigrationSpecs.toList())
    @After fun cleanup() { context.deleteDatabase(name) }
    @Test fun upgrade125PreservesExistingDataAndCreatesTheSearchTables() {
        helper.createDatabase(name,125).use { it.execSQL("INSERT INTO search_keywords(word,usage,lastUseTime) VALUES('Retained content search',7,1234)") }
        val db=Room.databaseBuilder(context,AppDatabase::class.java,name).addLegadoMigrations().build()
        try {
            assertEquals(132,db.openHelper.writableDatabase.version)
            db.openHelper.readableDatabase.query("SELECT usage,lastUseTime FROM search_keywords WHERE word='Retained content search'").use {
                assertTrue(it.moveToFirst()); assertEquals(7,it.getInt(0)); assertEquals(1234,it.getLong(1))
            }
            assertNull(db.bookContentSearchDao.get(UUID.randomUUID().toString()))
            assertTrue(db.bookContentSearchDao.page(UUID.randomUUID().toString(),0,100).isEmpty())
        } finally { db.close() }
    }
}
