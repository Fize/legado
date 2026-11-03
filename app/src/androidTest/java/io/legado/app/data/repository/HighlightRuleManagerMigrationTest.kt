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
class HighlightRuleManagerMigrationTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    private val name="manager-migration-${UUID.randomUUID()}"
    @get:Rule val helper=MigrationTestHelper(instrumentation,AppDatabase::class.java,DatabaseMigrations.autoMigrationSpecs.toList())
    @After fun cleanup() { context.deleteDatabase(name) }
    @Test fun upgrade128KeepsAnExistingEditorDraftAndItsOwner() {
        helper.createDatabase(name,128).use {
            it.execSQL("INSERT INTO highlight_rule_drafts(id,ruleId,owner,revision,originalKey,value,phase,savedId,updatedAt,process,epoch) VALUES('draft',NULL,'owner',7,NULL,'{\"pattern\":\"amber\"}','editing',NULL,1,'process',3)")
        }
        val db=Room.databaseBuilder(context,AppDatabase::class.java,name).addLegadoMigrations().build()
        try {
            assertEquals(132,db.openHelper.writableDatabase.version)
            val draft=db.highlightRuleDraftDao.get("draft")!!
            assertEquals(7L,draft.revision); assertEquals("owner",draft.owner); assertEquals(3L,draft.epoch)
            assertTrue(draft.value.contains("amber")); assertNull(db.highlightRuleManagerDao.get("manager"))
        } finally { db.close() }
    }
}
