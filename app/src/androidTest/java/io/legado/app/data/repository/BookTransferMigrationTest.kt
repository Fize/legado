package io.legado.app.data.repository

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.*
import io.legado.app.data.entities.Book
import io.legado.app.utils.GSON
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BookTransferMigrationTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    private val name="transfer-migration-${UUID.randomUUID()}"
    @get:Rule val helper=MigrationTestHelper(instrumentation,AppDatabase::class.java,DatabaseMigrations.autoMigrationSpecs.toList())
    @After fun cleanup() { context.deleteDatabase(name) }
    @Test fun upgrade131PreservesTaskLeaseProgressAndDraftBeforeAnExplicitRetry()=runBlocking {
        val book=Book(bookUrl="migration-book",origin="source",name="Migration",author="Author")
        val config=GSON.toJson(BookTransferConfig()).replace(",\"threads\":32","")
        helper.createDatabase(name,131).use { old ->
            old.execSQL("INSERT INTO books(bookUrl,origin,originName,name,author,type,tocUrl,charset) VALUES(?,?,?,?,?,?,?,?)",
                arrayOf<Any?>(book.bookUrl,book.origin,book.originName,book.name,book.author,book.type,book.tocUrl,book.charset))
            old.execSQL("INSERT INTO book_transfer_tasks(id,kind,bookUrl,bookKey,config,phase,progress,total,error,process,owner,createdAt) VALUES(?,?,?,?,?,'interrupted',3,7,NULL,'old-process','',123)",
                arrayOf("task","download",book.bookUrl,transferBookKey(book),config))
            old.execSQL("INSERT INTO book_transfer_leases(bookUrl,kind,taskId) VALUES(?,'download','task')",arrayOf(book.bookUrl))
            old.execSQL("INSERT INTO cache_manager_states(id,owner,process,epoch,revision,state,closed) VALUES('manager','owner','old-process',4,9,'{\"groupId\":7}',0)")
        }
        val db=Room.databaseBuilder(context,AppDatabase::class.java,name).addLegadoMigrations().build()
        try {
            assertEquals(132,db.openHelper.writableDatabase.version)
            val task=checkNotNull(db.bookTransferDao.get("task"))
            assertEquals("task",db.bookTransferDao.lease(book.bookUrl,"download"))
            assertEquals(config,task.config); assertEquals(3,task.progress); assertEquals(7,task.total)
            assertEquals(123L,task.createdAt); assertEquals("interrupted",task.phase); assertNull(task.batchId)
            assertEquals(32,GSON.fromJson(task.config,BookTransferConfig::class.java).threads)
            val draft=checkNotNull(db.bookTransferDao.manager("manager"))
            assertEquals(9L,draft.revision); assertEquals("{\"groupId\":7}",draft.state); assertFalse(draft.closed)
            val repo=BookTransferRepository(db)
            repo.retry("task")
            val retried=checkNotNull(db.bookTransferDao.get("task"))
            assertEquals("queued",retried.phase); assertEquals(0,retried.progress)
            assertNotNull(retried.batchId); assertEquals(1,repo.dispatchCandidates(listOf("task")).size)
            db.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        } finally { db.close() }
    }
    @Test fun upgrade130PreservesExistingImportStateAndCreatesEmptyTransferTables() {
        helper.createDatabase(name,130).use {
            it.execSQL("INSERT INTO highlight_rule_manager_states(id,owner,process,epoch,revision,state,closed) VALUES('manager','owner','process',4,9,'{}',0)")
        }
        val db=Room.databaseBuilder(context,AppDatabase::class.java,name).addLegadoMigrations().build()
        try {
            assertEquals(132,db.openHelper.writableDatabase.version)
            assertEquals(9L,db.highlightRuleManagerDao.get("manager")!!.revision)
            assertTrue(db.bookTransferDao.active("export").isEmpty())
            assertTrue(db.bookTransferDao.active("download").isEmpty())
            assertNull(db.bookTransferDao.manager("missing"))
        } finally { db.close() }
    }
}
