package io.legado.app.data.repository

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.*
import io.legado.app.data.entities.*
import io.legado.app.data.preferences.CacheExportOptions
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BookTransferBatchRepositoryTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private val db=Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).addLegadoMigrations().build()
    private val repo=BookTransferRepository(db)
    private fun book(url: String)=Book(bookUrl=url,origin="source",name=url,author="Author").also { db.bookDao.insert(it) }
    private fun ref(book: Book)=CacheBookRef(book.bookUrl,transferBookKey(book))
    private val config=BookTransferConfig(CacheExportOptions(directory="/export"))
    @After fun cleanup() { db.close() }
    @Test fun aLargeUserCommandCreatesOneDispatchAndKeepsItsBookOrder()=runBlocking {
        val books=(0 until 200).map { book("book-$it") }
        val ids=repo.enqueue(books.map(::ref),"download",config)
        val batches=repo.dispatchCandidates(ids); assertEquals(1,batches.size)
        assertEquals(ids,db.bookTransferDao.batchTasks(batches.single().id).map { it.id })
        assertEquals("download",batches.single().kind)
    }
    @Test fun oldDispatchAndDuplicateStartCannotClaimTheCurrentBatch()=runBlocking {
        val ids=repo.enqueue(listOf(ref(book("one"))),"export",config); val batch=repo.dispatchCandidates(ids).single()
        assertNull(repo.claimBatch(batch.id,"wrong")); val running=repo.claimBatch(batch.id,batch.dispatch)!!
        assertNull(repo.claimBatch(batch.id,batch.dispatch)); assertEquals("running",running.phase)
    }
    @Test fun aSystemStoppedDownloadCanResumeWithoutAnOldOwnerOverwritingTheNewAttempt()=runBlocking {
        val ids=repo.enqueue(listOf(ref(book("one"))),"download",config); val batch=repo.dispatchCandidates(ids).single()
        val first=repo.claimBatch(batch.id,batch.dispatch)!!; val task=repo.claimNext(first)!!
        repo.interrupt(task); repo.stopBatch(first,true)
        assertEquals("queued",db.bookTransferDao.get(task.id)!!.phase)
        val next=repo.claimBatch(batch.id,batch.dispatch,true)!!
        assertNotEquals(first.owner,next.owner); val current=repo.claimNext(next)!!
        repo.stopBatch(first,false); repo.finish(task,"late")
        assertEquals(current.owner,db.bookTransferDao.get(current.id)!!.owner)
        repo.finish(current); repo.finishBatch(next); assertEquals("completed",db.bookTransferDao.batch(batch.id)!!.phase)
    }
    @Test fun aNativeDownloadJobMayResumeAfterProcessDeathButTheLegacyWorkerMayNot()=runBlocking {
        val ids=repo.enqueue(listOf(ref(book("one"))),"download",config); val batch=repo.dispatchCandidates(ids).single()
        db.bookTransferDao.update(batch.copy(process="dead",phase="running",owner="old"))
        db.bookTransferDao.update(db.bookTransferDao.get(ids.single())!!.copy(process="dead",phase="running",owner="old"))
        assertNull(repo.claimBatch(batch.id,batch.dispatch,true,false))
        val resumed=repo.claimBatch(batch.id,batch.dispatch,true,true)!!
        assertEquals(BookTransferProcess.id,resumed.process); assertEquals("running",repo.claimNext(resumed)!!.phase)
    }
    @Test fun externalExportsNeverResumeAnOldProcessesOutputAutomatically()=runBlocking {
        val ids=repo.enqueue(listOf(ref(book("one"))),"export",config); val batch=repo.dispatchCandidates(ids).single()
        db.bookTransferDao.update(batch.copy(process="dead"))
        assertNull(repo.claimBatch(batch.id,batch.dispatch,true,true))
        assertEquals("interrupted",db.bookTransferDao.get(ids.single())!!.phase)
    }
    @Test fun cancellingABatchPreventsItsSystemRetryButDoesNotAffectAnotherCommand()=runBlocking {
        val first=repo.enqueue(listOf(ref(book("one"))),"download",config)
        val second=repo.enqueue(listOf(ref(book("two"))),"download",config)
        val batch=repo.dispatchCandidates(first).single(); repo.cancelBatch(batch.id,batch.dispatch)
        assertNull(repo.claimBatch(batch.id,batch.dispatch,true)); assertEquals("cancelled",db.bookTransferDao.get(first.single())!!.phase)
        assertEquals("queued",db.bookTransferDao.get(second.single())!!.phase)
    }
    @Test fun oneBookCanBeCancelledWhileTheRestOfTheBatchContinues()=runBlocking {
        val ids=repo.enqueue(listOf(ref(book("one")),ref(book("two"))),"export",config)
        val batch=repo.dispatchCandidates(ids).single(); val owned=repo.claimBatch(batch.id,batch.dispatch)!!
        assertTrue(repo.cancelIds(listOf(ids.first())).isEmpty())
        val next=repo.claimNext(owned)!!; assertEquals(ids.last(),next.id)
        repo.finish(next); repo.finishBatch(owned); assertEquals("completed",db.bookTransferDao.batch(batch.id)!!.phase)
    }
    @Test fun progressCannotRegressWhenParallelChapterCompletionsArriveOutOfOrder()=runBlocking {
        repo.enqueue(listOf(ref(book("one"))),"download",config); val task=repo.claim("download")!!
        repo.progress(task,3,5); repo.progress(task,2,5)
        assertEquals(3,db.bookTransferDao.get(task.id)!!.progress)
        repo.progress(task,100,5); assertEquals(5,db.bookTransferDao.get(task.id)!!.progress)
    }
    @Test fun summaryCountsMixedTerminalStatesAndAnEmptyBatchWithoutLoadingConfigurations()=runBlocking {
        val empty=db.bookTransferDao.batchSummary("absent")
        assertEquals(BookTransferBatchSummary(0,0,0,0),empty)
        val ids=repo.enqueue(listOf(ref(book("one")),ref(book("two"))),"export",config)
        val batch=repo.dispatchCandidates(ids).single(); val owned=repo.claimBatch(batch.id,batch.dispatch)!!
        assertEquals(BookTransferBatchSummary(2,0,0,2),db.bookTransferDao.batchSummary(batch.id))
        repo.finish(repo.claimNext(owned)!!,"expected failure")
        repo.cancelIds(listOf(ids.last()))
        assertEquals(BookTransferBatchSummary(0,1,1,2),db.bookTransferDao.batchSummary(batch.id))
        repo.finishBatch(owned); assertEquals("failed",db.bookTransferDao.batch(batch.id)!!.phase)
    }
    @Test fun bulkStopAndResumePreserveCompletedBooksAndTheExactConfigurationPayload()=runBlocking {
        val large=config.copy(options=config.options.copy(fileName="x".repeat(100_000)))
        val ids=repo.enqueue(listOf(ref(book("one")),ref(book("two"))),"download",large)
        val batch=repo.dispatchCandidates(ids).single(); val owned=repo.claimBatch(batch.id,batch.dispatch)!!
        val first=repo.claimNext(owned)!!; repo.finish(first)
        val second=repo.claimNext(owned)!!; val original=second.config
        repo.stopBatch(owned,true)
        assertEquals("completed",db.bookTransferDao.get(first.id)!!.phase)
        assertEquals("queued",db.bookTransferDao.get(second.id)!!.phase)
        val resumed=repo.claimBatch(batch.id,batch.dispatch,true)!!
        val current=repo.claimNext(resumed)!!
        assertEquals(second.id,current.id); assertEquals(original,current.config)
        assertNotEquals(second.owner,current.owner)
        repo.finish(current); assertNull(repo.claimNext(resumed)); repo.finishBatch(resumed)
        assertEquals("completed",db.bookTransferDao.batch(batch.id)!!.phase)
    }
    @Test fun aRunnerInterruptedBeforeSystemCleanupRequeuesItsProgressButKeepsExplicitCancellation()=runBlocking {
        val ids=repo.enqueue(listOf(ref(book("one")),ref(book("two"))),"download",config)
        val batch=repo.dispatchCandidates(ids).single(); val owned=repo.claimBatch(batch.id,batch.dispatch)!!
        val task=repo.claimNext(owned)!!; repo.progress(task,2,5)
        repo.cancelIds(listOf(ids.last())); repo.interrupt(task); repo.stopBatch(owned,true)
        val queued=db.bookTransferDao.get(task.id)!!
        assertEquals("queued",queued.phase); assertEquals(2,queued.progress)
        assertEquals("",queued.owner); assertEquals("waiting",db.bookTransferDao.batch(batch.id)!!.phase)
        assertEquals("cancelled",db.bookTransferDao.get(ids.last())!!.phase)
        val resumed=repo.claimBatch(batch.id,batch.dispatch,true)!!; val next=repo.claimNext(resumed)!!
        repo.interrupt(task); repo.stopBatch(owned,false)
        assertEquals(next.owner,db.bookTransferDao.get(next.id)!!.owner)
    }
}
