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
class HighlightRuleImportMigrationTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    private val name="highlight-import-migration-${UUID.randomUUID()}"
    @get:Rule val helper=MigrationTestHelper(instrumentation,AppDatabase::class.java,DatabaseMigrations.autoMigrationSpecs.toList())
    @After fun cleanup() { context.deleteDatabase(name) }
    @Test fun upgrade129PreservesTheExistingManagerDraftAndCreatesEmptyImportTables() {
        helper.createDatabase(name,129).use {
            it.execSQL("INSERT INTO highlight_rule_manager_states(id,owner,process,epoch,revision,state,closed) VALUES('manager','owner','process',4,9,'{\"filter\":\"all\",\"selected\":[\"rule\"]}',0)")
        }
        val db=Room.databaseBuilder(context,AppDatabase::class.java,name).addLegadoMigrations().build()
        try {
            assertEquals(132,db.openHelper.writableDatabase.version)
            val draft=db.highlightRuleManagerDao.get("manager")!!
            assertEquals("owner",draft.owner); assertEquals(9L,draft.revision); assertEquals(4L,draft.epoch)
            assertTrue(draft.state.contains("rule")); assertNull(db.highlightRuleImportDao.get("import"))
            assertTrue(db.highlightRuleImportDao.rows("import").isEmpty())
        } finally { db.close() }
    }
}
