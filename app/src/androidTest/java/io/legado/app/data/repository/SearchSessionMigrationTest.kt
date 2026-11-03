package io.legado.app.data.repository

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.AppDatabase
import io.legado.app.data.DatabaseMigrations
import io.legado.app.data.addLegadoMigrations
import io.legado.app.data.entities.SearchSession
import io.legado.app.data.entities.SearchSessionStatus
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SearchSessionMigrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val name = "search-migration-${UUID.randomUUID()}"
    @get:Rule val helper = MigrationTestHelper(instrumentation, AppDatabase::class.java, DatabaseMigrations.autoMigrationSpecs.toList())
    @After fun cleanup() { context.deleteDatabase(name) }
    @Test fun upgrading115PreservesHistoryAndCreatesDurableSearchSessions() {
        helper.createDatabase(name, 115).use { db ->
            db.execSQL("INSERT INTO search_keywords(word,usage,lastUseTime) VALUES('Retained',7,1234)")
        }
        fun open() = Room.databaseBuilder(context, AppDatabase::class.java, name).addLegadoMigrations().build()
        val first = open()
        try {
            val db = first
            assertEquals(7, db.searchKeywordDao.get("Retained")!!.usage)
            val session = SearchSession("session", "Query", "", false, 3, 4, 1, 2, SearchSessionStatus.Interrupted, true, 1, 2, 0, null, 10)
            db.searchSessionDao.insert(session)
        } finally { first.close() }
        val second = open()
        try {
            val db = second
            assertEquals("Query", db.searchSessionDao.get("session")!!.query)
            assertEquals(SearchSessionStatus.Interrupted, db.searchSessionDao.get("session")!!.status)
            assertEquals(1234L, db.searchKeywordDao.get("Retained")!!.lastUseTime)
        } finally { second.close() }
    }
}
