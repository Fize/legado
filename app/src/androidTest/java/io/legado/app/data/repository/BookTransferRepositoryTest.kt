package io.legado.app.data.repository

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.constant.BookType
import io.legado.app.data.*
import io.legado.app.data.entities.*
import io.legado.app.data.preferences.CacheExportOptions
import io.legado.app.utils.GSON
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BookTransferRepositoryTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private val db=Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).addLegadoMigrations().build()
    private val repo=BookTransferRepository(db)
    private val config=BookTransferConfig(options=CacheExportOptions(directory="/export"))
    private fun book(url: String="book")=Book(bookUrl=url,origin="source",name="Book-$url",author="Author").also { db.bookDao.insert(it) }
    private fun ref(book: Book)=CacheBookRef(book.bookUrl,transferBookKey(book))
    private suspend fun enqueue(book: Book,kind: String="export")=repo.enqueue(listOf(ref(book)),kind,config).single()
    private fun task(id: String)=db.bookTransferDao.get(id)!!
    @After fun cleanup() { db.close() }

    @Test fun repeatedIdenticalCommandsShareTheSameActiveTask()=runBlocking {
        val book=book(); val id=enqueue(book)
        assertEquals(id,enqueue(book)); assertEquals("queued",task(id).phase)
        assertEquals(id,db.bookTransferDao.lease(book.bookUrl,"export"))
    }
    @Test fun anotherConfigurationCannotBeSilentlyDroppedWhileTheBookIsActive()=runBlocking {
        val book=book(); val id=enqueue(book)
        assertTrue(runCatching { repo.enqueue(listOf(ref(book)),"export",config.copy(options=config.options.copy(format=2))) }.isFailure)
        assertEquals(GSON.toJson(config),task(id).config)
    }
    @Test fun aBatchWithOneChangedBookIsRejectedAtomically()=runBlocking {
        val first=book("first"); val second=book("second"); val refs=listOf(ref(first),ref(second))
        db.bookDao.update(second.copy(origin="another"))
        assertTrue(runCatching { repo.enqueue(refs,"export",config) }.isFailure)
        assertNull(db.bookTransferDao.lease(first.bookUrl,"export")); assertTrue(db.bookTransferDao.active("export").isEmpty())
    }
    @Test fun deletionAndReinsertionAtTheSameUrlCannotReviveAnOldLease()=runBlocking {
        val book=book(); val id=enqueue(book); val running=repo.claim("export")!!
        db.bookDao.delete(book); db.bookDao.insert(book)
        assertNull(db.bookTransferDao.lease(book.bookUrl,"export"))
        assertTrue(runCatching { repo.verify(running) }.isFailure)
        repo.finish(running); assertEquals("failed",task(id).phase)
        assertTrue(runCatching { repo.retry(id) }.isFailure)
        assertNotEquals(id,enqueue(book))
    }
    @Test fun aLateProgressAndCompletionCannotOverwriteCancellation()=runBlocking {
        val book=book(); val id=enqueue(book); val running=repo.claim("export")!!
        repo.cancel("export",book.bookUrl)
        assertTrue(runCatching { repo.progress(running,7,9) }.isFailure)
        repo.finish(running); assertEquals("cancelled",task(id).phase); assertEquals(0,task(id).progress)
    }
    @Test fun retryCreatesANewOwnerAndRejectsOldServiceCleanup()=runBlocking {
        val book=book(); val id=enqueue(book); val old=repo.claim("export")!!
        repo.interrupt(old); repo.retry(id); val current=repo.claim("export")!!
        assertNotEquals(old.owner,current.owner)
        repo.interrupt(old); repo.finish(old,"late")
        repo.progress(current,2,5); assertEquals("running",task(id).phase); assertEquals(2,task(id).progress)
        repo.finish(current); assertEquals("completed",task(id).phase)
    }
    @Test fun processRecoveryRequiresAnExplicitRetryForAnExternalExport()=runBlocking {
        val book=book(); val id=enqueue(book); val running=repo.claim("export")!!
        db.bookTransferDao.update(running.copy(process="dead-process"))
        repo.recover("export"); assertEquals("interrupted",task(id).phase); assertNull(repo.claim("export"))
        repo.retry(id); assertEquals(id,repo.claim("export")!!.id)
    }
    @Test fun queuedExportsFromAnotherProcessRequireExplicitRetryToo()=runBlocking {
        val book=book(); val id=enqueue(book)
        db.bookTransferDao.update(task(id).copy(process="dead-process"))
        assertEquals("interrupted",repo.claim("export")!!.phase)
        assertNull(repo.claim("export"))
        repo.retry(id); repo.recover("export")
        assertEquals("queued",task(id).phase); assertEquals(id,repo.claim("export")!!.id)
    }
    @Test fun aNewServiceDoesNotInterruptNewlyQueuedCommandsAndANewCommandDoesNotResumeOtherOldBooks()=runBlocking {
        val oldBook=book("old"); val old=enqueue(oldBook)
        db.bookTransferDao.update(task(old).copy(process="dead-process"))
        val current=enqueue(book("current"))
        repo.recover("export",force=true)
        assertEquals("interrupted",task(old).phase); assertEquals("queued",task(current).phase)
        assertEquals(current,repo.claim("export")!!.id)
    }
    @Test fun anExplicitNewExportReplacesTheDeadProcessesCommandOnlyForThatBook()=runBlocking {
        val book=book(); val old=enqueue(book)
        db.bookTransferDao.update(task(old).copy(process="dead-process"))
        val current=enqueue(book)
        assertNotEquals(old,current); assertEquals("interrupted",task(old).phase)
        assertEquals(current,repo.claim("export")!!.id)
    }
    @Test fun openingTheScreenDoesNotInterruptTheLiveServiceButANewServiceCanRecoverIt()=runBlocking {
        val book=book(); val id=enqueue(book); repo.claim("export")
        repo.recover("export"); assertEquals("running",task(id).phase)
        repo.recover("export",force=true); assertEquals("interrupted",task(id).phase)
    }
    @Test fun aChangedBookCannotBeClaimedAndDoesNotBlockTheNextTask()=runBlocking {
        val first=book("first"); val id=enqueue(first); val next=enqueue(book("next"))
        db.bookDao.update(first.copy(name="Changed"))
        assertEquals("failed",repo.claim("export")!!.phase); assertEquals("failed",task(id).phase)
        assertEquals(next,repo.claim("export")!!.id)
    }
    @Test fun readingProgressDoesNotInvalidateTheTaskOrGetOverwrittenByIt()=runBlocking {
        val book=book(); val id=enqueue(book); val running=repo.claim("export")!!
        db.bookDao.update(book.copy(durChapterIndex=4,durChapterPos=99))
        assertEquals(99,repo.snapshot(running).durChapterPos)
        repo.finish(running); assertEquals("completed",task(id).phase)
        assertEquals(4,db.bookDao.getBook(book.bookUrl)!!.durChapterIndex)
    }
    @Test fun cancelingOneKindDoesNotStopAnotherKindsTask()=runBlocking {
        val book=book(); val export=enqueue(book); val download=enqueue(book,"download")
        repo.cancel("export")
        assertEquals("cancelled",task(export).phase); assertEquals("queued",task(download).phase)
    }
    @Test fun anOwnedLocalRefreshUpdatesParserMetadataAndTaskIdentityWithoutOverwritingReadingProgress()=runBlocking {
        val local=book().copy(origin=BookType.localTag,type=BookType.local or BookType.text,charset="UTF-8",tocUrl="old")
        db.bookDao.update(local); val id=enqueue(local); val running=repo.claim("export")!!
        val current=local.copy(durChapterIndex=4,durChapterPos=99,customCoverUrl="new-cover",group=2L)
        db.bookDao.update(current)
        val parsed=local.copy(charset="GB18030",tocUrl="new-toc",wordCount="100",latestChapterTime=123,latestChapterTitle="Latest")
        repo.refreshLocalNow(running,parsed,listOf(BookChapter(bookUrl=local.bookUrl,url="new",title="New",index=0)))
        val saved=db.bookDao.getBook(local.bookUrl)!!
        assertEquals("GB18030",saved.charset); assertEquals("new-toc",saved.tocUrl); assertEquals("100",saved.wordCount)
        assertEquals(123L,saved.latestChapterTime); assertEquals("Latest",saved.latestChapterTitle)
        assertEquals(4,saved.durChapterIndex); assertEquals(99,saved.durChapterPos); assertEquals("new-cover",saved.customCoverUrl)
        assertEquals(2L,saved.group); assertEquals(1,saved.totalChapterNum)
        assertNotEquals(running.bookKey,task(id).bookKey); assertEquals(transferBookKey(saved),task(id).bookKey)
        assertEquals("GB18030",repo.snapshot(running).charset)
        repo.progress(running,1,1); repo.finish(running); assertEquals("completed",task(id).phase)
    }
    @Test fun aCancelledLocalParserCannotReplaceTheDirectoryOrBookMetadata()=runBlocking {
        val local=book().copy(origin=BookType.localTag,type=BookType.local or BookType.text,charset="UTF-8")
        db.bookDao.update(local)
        db.bookChapterDao.insert(BookChapter(bookUrl=local.bookUrl,url="old",title="Old"))
        enqueue(local); val running=repo.claim("export")!!; repo.cancel("export")
        assertTrue(runCatching { repo.refreshLocalNow(running,local.copy(charset="GB18030"),
            listOf(BookChapter(bookUrl=local.bookUrl,url="new",title="New"))) }.isFailure)
        assertEquals("Old",db.bookChapterDao.getChapter(local.bookUrl,0)!!.title)
        assertEquals("UTF-8",db.bookDao.getBook(local.bookUrl)!!.charset)
    }
    @Test fun malformedCommandsNeverInsertATask()=runBlocking {
        val book=book()
        assertTrue(runCatching { repo.enqueue(listOf(ref(book)),"export",config.copy(options=config.options.copy(directory=""))) }.isFailure)
        assertTrue(runCatching { repo.enqueue(listOf(ref(book)),"download",config.copy(start=5,end=2)) }.isFailure)
        assertTrue(runCatching { repo.enqueue(listOf(ref(book)),"export",config.copy(options=config.options.copy(webDav=true))) }.isFailure)
        assertTrue(db.bookTransferDao.active("export").isEmpty()); assertTrue(db.bookTransferDao.active("download").isEmpty())
    }
}
