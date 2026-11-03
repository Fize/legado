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
class HighlightRuleEditorMigrationTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    private val name="rule-editor-migration-${UUID.randomUUID()}"
    @get:Rule val helper=MigrationTestHelper(instrumentation,AppDatabase::class.java,DatabaseMigrations.autoMigrationSpecs.toList())
    @After fun cleanup() { context.deleteDatabase(name) }
    @Test fun upgrade127PreservesRuleIdentityAndAllStyleMetadata() {
        val uuid=UUID.randomUUID().toString()
        helper.createDatabase(name,127).use {
            it.execSQL("INSERT INTO highlightRules(id,uuid,name,pattern,isRegex,scope,isEnabled,style,sortOrder,timeoutMillisecond,`group`,applyToTitle,applyToBody) VALUES(7,?,'Name','amber',1,'Book',0,'{\"extension\":7}',12,27,'Group',1,0)",arrayOf(uuid))
        }
        val db=Room.databaseBuilder(context,AppDatabase::class.java,name).addLegadoMigrations().build()
        try {
            assertEquals(132,db.openHelper.writableDatabase.version)
            val rule=db.highlightRuleDao.findById(7)!!
            assertEquals(uuid,rule.uuid); assertEquals("{\"extension\":7}",rule.style); assertEquals("Group",rule.group)
            assertFalse(rule.isEnabled); assertTrue(rule.applyToTitle); assertFalse(rule.applyToBody)
            assertEquals(27L,rule.timeoutMillisecond); assertEquals(12,rule.order)
            assertNull(db.highlightRuleDraftDao.get("no-request"))
        } finally { db.close() }
    }
}
