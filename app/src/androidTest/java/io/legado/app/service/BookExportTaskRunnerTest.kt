package io.legado.app.service

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.constant.BookType
import io.legado.app.data.*
import io.legado.app.data.cache.CacheResourceLeases
import io.legado.app.data.entities.*
import io.legado.app.data.preferences.CacheExportOptions
import io.legado.app.data.repository.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BookExportTaskRunnerTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private val db=Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).addLegadoMigrations().build()
    private val tasks=BookTransferRepository(db)
    private val runner=BookExportTaskRunner(context,db,tasks,CacheResourceLeases())
    private val folder=File(context.cacheDir,"export-task-${UUID.randomUUID()}").apply { mkdirs() }
    private val book=Book(bookUrl="https://example.invalid/owned-export",origin="https://example.invalid",name="Owned export",author="Author")
    @Before fun fixture() {
        db.bookDao.insert(book)
        db.bookChapterDao.insert(BookChapter(bookUrl=book.bookUrl,url="volume",title="Volume",isVolume=true))
    }
    @After fun cleanup() { db.close(); folder.deleteRecursively() }
    private suspend fun claim(format: Int=0): BookTransferTask {
        tasks.enqueue(listOf(CacheBookRef(book.bookUrl,transferBookKey(book))),"export",
            BookTransferConfig(CacheExportOptions(format=format,directory=folder.path,fileName="'owned'")))
        return tasks.claim("export")!!
    }
    @Test fun aClaimedTaskExportsEachFormatAndPersistsItsCompletionAndFinalProgress()=runBlocking {
        listOf("txt","epub","pdf").forEachIndexed { format,type ->
            val task=claim(format); runner.run(task)
            val complete=db.bookTransferDao.get(task.id)!!
            assertEquals("completed",complete.phase); assertEquals("",complete.owner)
            assertEquals(1,complete.progress); assertEquals(1,complete.total)
            assertTrue(File(folder,"owned.$type").length()>0)
        }
        assertEquals(3,folder.list()!!.size)
    }
    @Test fun aCancelledTaskCannotReplaceThePreviouslyExportedFile()=runBlocking {
        val task=claim(); val output=File(folder,"owned.txt").apply { writeText("PREVIOUS") }
        tasks.cancel("export")
        assertTrue(runCatching { runner.run(task) }.isFailure)
        assertEquals("PREVIOUS",output.readText()); assertEquals("cancelled",db.bookTransferDao.get(task.id)!!.phase)
        assertEquals(1,folder.list()!!.size)
    }
    @Test fun changingTheBookAfterClaimRejectsTheExportWithoutOverwritingTheFile()=runBlocking {
        val task=claim(); val output=File(folder,"owned.txt").apply { writeText("PREVIOUS") }
        db.bookDao.update(book.copy(origin="other-source"))
        assertTrue(runCatching { runner.run(task) }.isFailure)
        assertEquals("PREVIOUS",output.readText()); assertEquals("failed",db.bookTransferDao.get(task.id)!!.phase)
    }
    @Test fun aRealChangedLocalTxtRefreshesItsMetadataAndRemainsExportableWithoutRebuildingAgain()=runBlocking {
        val input=File(folder,"local-${UUID.randomUUID()}.txt").apply { writeText("第1章 开始\nLocal body 第一段\n第2章 继续\nSecond local body\n") }
        val local=Book(bookUrl=input.path,origin=BookType.localTag,originName=input.name,
            name="Local file fixture",author="Author",type=BookType.local or BookType.text,latestChapterTime=0,
            durChapterIndex=0,durChapterPos=7)
        db.bookDao.insert(local)
        val config=BookTransferConfig(CacheExportOptions(directory=folder.path,fileName="'local-owned'"))
        suspend fun execute(value: Book) {
            tasks.enqueue(listOf(CacheBookRef(value.bookUrl,transferBookKey(value))),"export",config)
            val task=tasks.claim("export")!!; runner.run(task)
            assertEquals("completed",db.bookTransferDao.get(task.id)!!.phase)
        }
        execute(local)
        val saved=db.bookDao.getBook(local.bookUrl)!!
        assertFalse(saved.charset.isNullOrBlank()); assertFalse(saved.wordCount.isNullOrBlank())
        assertTrue(saved.latestChapterTime>=input.lastModified()); assertTrue(saved.totalChapterNum>0)
        assertEquals(7,saved.durChapterPos); assertEquals(0,saved.durChapterIndex)
        assertTrue(File(folder,"local-owned.txt").readText().contains("Local body 第一段"))
        val first=db.bookChapterDao.getChapter(local.bookUrl,0)!!
        db.bookChapterDao.update(first.copy(title="Kept directory title"))
        execute(saved)
        assertEquals("Kept directory title",db.bookChapterDao.getChapter(local.bookUrl,0)!!.title)
        assertTrue(File(folder,"local-owned.txt").readText().contains("Local body 第一段"))
    }
    @Test fun aLateRunnerCannotFinishOrInterruptANewerAttempt()=runBlocking {
        val old=claim(); tasks.interrupt(old); tasks.retry(old.id); val current=tasks.claim("export")!!
        assertTrue(runCatching { runner.run(old) }.isFailure)
        assertEquals(current.owner,db.bookTransferDao.get(old.id)!!.owner)
        runner.run(current); assertEquals("completed",db.bookTransferDao.get(old.id)!!.phase)
    }
}
