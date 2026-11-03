package io.legado.app.data.repository

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.*
import io.legado.app.data.entities.Book
import io.legado.app.data.preferences.CacheExportOptions
import io.legado.app.utils.GSON
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class CacheManagerRepositoryTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private val db=Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).addLegadoMigrations().build()
    private val tasks=BookTransferRepository(db)
    private val repo=CacheManagerRepository(db,tasks)
    private val id=UUID.randomUUID().toString(); private val owner=UUID.randomUUID().toString()
    private fun book(url: String="book",index: Int=3)=Book(bookUrl=url,origin="source",name="Book-$url",author="Author",durChapterIndex=index).also { db.bookDao.insert(it) }
    private fun ref(value: Book)=CacheBookRef(value.bookUrl,transferBookKey(value))
    private suspend fun open()=repo.open(id,owner,1,7)
    private fun options()=CacheExportOptions(directory=context.cacheDir.path)
    @After fun cleanup() { db.close() }
    @Test fun restoredEntryKeepsTheGroupDialogAndTextInsteadOfTheNewRouteDefault()=runBlocking {
        val book=book(); open()
        val prepared=repo.prepare(id,owner,1,CacheManagerDialog(kind="export",refs=listOf(ref(book)),options=options(),scope="1-3",chunk="2"))
        repo.persist(id,owner,2,prepared.draft.copy(groupId=9))
        val restored=repo.open(id,UUID.randomUUID().toString(),2,-1)
        assertEquals(9L,restored.draft.groupId); assertEquals("1-3",restored.draft.dialog!!.scope); assertEquals("2",restored.draft.dialog!!.chunk)
        assertTrue(runCatching { (restored.draft.dialog!!.refs as MutableList<*>).clear() }.isFailure)
    }
    @Test fun staleOwnersCannotConfirmChangeOrCloseTheCurrentEntry()=runBlocking {
        val book=book(); open(); val ready=repo.prepare(id,owner,1,CacheManagerDialog(kind="export",refs=listOf(ref(book)),options=options()))
        val next=UUID.randomUUID().toString(); repo.open(id,next,2,7)
        assertTrue(runCatching { repo.confirm(id,owner,2,ready.draft.dialog!!.id) }.isFailure)
        assertTrue(runCatching { repo.persist(id,owner,3,ready.draft.copy(groupId=5)) }.isFailure)
        assertTrue(runCatching { repo.close(id,owner) }.isFailure)
        assertFalse(db.bookTransferDao.manager(id)!!.closed); assertTrue(db.bookTransferDao.active("export").isEmpty())
    }
    @Test fun confirmationEnqueuesOneBatchAndPersistsTheDispatchReceiptAtomically()=runBlocking {
        val books=listOf(book("one"),book("two")); open()
        val ready=repo.prepare(id,owner,1,CacheManagerDialog(kind="export",refs=books.map(::ref),options=options()))
        val done=repo.confirm(id,owner,2,ready.draft.dialog!!.id)
        assertNull(done.draft.dialog); assertEquals(2,done.draft.pendingTasks.size)
        assertEquals(1,tasks.dispatchCandidates(done.draft.pendingTasks).size)
        assertTrue(runCatching { repo.confirm(id,owner,3,ready.draft.dialog!!.id) }.isFailure)
        assertEquals(2,db.bookTransferDao.active("export").size)
        val restored=repo.open(id,UUID.randomUUID().toString(),2,0)
        assertEquals(done.draft.pendingTasks,restored.draft.pendingTasks)
    }
    @Test fun afterCurrentAndAllCommandsCaptureTheCorrectPerBookRanges()=runBlocking {
        val first=book("first",3); val second=book("second",8); open()
        val ready=repo.prepare(id,owner,1,CacheManagerDialog(kind="download",refs=listOf(ref(first),ref(second)),afterCurrent=true))
        val done=repo.confirm(id,owner,2,ready.draft.dialog!!.id)
        val configs=done.draft.pendingTasks.map { db.bookTransferDao.get(it)!! }.associate { it.bookUrl to GSON.fromJson(it.config,BookTransferConfig::class.java) }
        assertEquals(3,configs[first.bookUrl]!!.start); assertEquals(8,configs[second.bookUrl]!!.start)
        assertTrue(configs.values.all { it.end==-1 }); assertEquals(1,tasks.dispatchCandidates(done.draft.pendingTasks).size)
        tasks.cancel("download")
        val all=repo.prepare(id,owner,3,CacheManagerDialog(kind="download",refs=listOf(ref(first)),afterCurrent=false))
        val complete=repo.confirm(id,owner,4,all.draft.dialog!!.id)
        val last=db.bookTransferDao.get(complete.draft.pendingTasks.last())!!
        assertEquals(0,GSON.fromJson(last.config,BookTransferConfig::class.java).start)
    }
    @Test fun oneChangedBookRejectsTheWholeConfirmationAndKeepsTheDialog()=runBlocking {
        val first=book("first"); val second=book("second"); open()
        val ready=repo.prepare(id,owner,1,CacheManagerDialog(kind="export",refs=listOf(ref(first),ref(second)),options=options()))
        db.bookDao.update(second.copy(origin="changed"))
        assertTrue(runCatching { repo.confirm(id,owner,2,ready.draft.dialog!!.id) }.isFailure)
        assertTrue(db.bookTransferDao.active("export").isEmpty())
        assertEquals(ready.draft.dialog!!.id,GSON.fromJson(db.bookTransferDao.manager(id)!!.state,CacheManagerDraft::class.java).dialog!!.id)
    }
    @Test fun folderAttemptsRejectOldAndDuplicateResultsAndKeepTheExportInputs()=runBlocking {
        val book=book(); open()
        repo.prepare(id,owner,1,CacheManagerDialog(kind="export",refs=listOf(ref(book)),options=options(),scope="1,3",chunk="2"))
        val first=repo.chooseFolder(id,owner,2).draft.folder!!.id
        val next=repo.chooseFolder(id,owner,3).draft.folder!!.id
        assertTrue(runCatching { repo.folderResult(id,owner,4,first,"content://fixture/tree/old") }.isFailure)
        val done=repo.folderResult(id,owner,4,next,"content://fixture/tree/new")
        assertNull(done.draft.folder); assertEquals("1,3",done.draft.dialog!!.scope); assertEquals("2",done.draft.dialog!!.chunk)
        assertEquals("content://fixture/tree/new",done.draft.dialog!!.options.directory); assertTrue(done.draft.dialog!!.directoryEdited)
        assertTrue(runCatching { repo.folderResult(id,owner,5,next,"content://fixture/tree/duplicate") }.isFailure)
    }
    @Test fun aPendingFolderRestoresWithoutLosingItsIdentityAndCanBeExplicitlyAbandoned()=runBlocking {
        open(); repo.prepare(id,owner,1,CacheManagerDialog(kind="folder",options=options()))
        val pending=repo.chooseFolder(id,owner,2)
        val nextOwner=UUID.randomUUID().toString(); val restored=repo.open(id,nextOwner,2,0)
        assertEquals(pending.draft.folder,restored.draft.folder)
        val failed=repo.folderResult(id,nextOwner,3,pending.draft.folder!!.id,null)
        assertEquals("failed",failed.draft.folder!!.phase)
        assertNull(repo.persist(id,nextOwner,4,failed.draft.copy(folder=null)).draft.folder)
    }
    @Test fun dispatchReceiptsCannotBeDiscardedThroughOrdinaryDraftChanges()=runBlocking {
        val book=book(); open(); val ready=repo.prepare(id,owner,1,CacheManagerDialog(kind="export",refs=listOf(ref(book)),options=options()))
        val done=repo.confirm(id,owner,2,ready.draft.dialog!!.id)
        assertTrue(runCatching { repo.persist(id,owner,3,done.draft.copy(pendingTasks=emptyList())) }.isFailure)
        assertTrue(repo.acknowledge(id,owner,3,done.draft.pendingTasks).draft.pendingTasks.isEmpty())
    }
    @Test fun aRetryReceivesANewBatchAndADurableReceipt()=runBlocking {
        val book=book(); open(); val ready=repo.prepare(id,owner,1,CacheManagerDialog(kind="export",refs=listOf(ref(book)),options=options()))
        val done=repo.confirm(id,owner,2,ready.draft.dialog!!.id); val task=done.draft.pendingTasks.single()
        val previous=db.bookTransferDao.get(task)!!.batchId
        repo.acknowledge(id,owner,3,listOf(task)); tasks.cancel("export")
        val retry=repo.retryTask(id,owner,4,task)
        assertEquals(listOf(task),retry.draft.pendingTasks); assertNotEquals(previous,db.bookTransferDao.get(task)!!.batchId)
    }
    @Test fun aClosedEntryCannotBeResurrectedByRestoringTheSameIdentifier()=runBlocking {
        open(); repo.close(id,owner)
        val restored=repo.open(id,UUID.randomUUID().toString(),2,88)
        assertTrue(restored.closed); assertNull(restored.draft.dialog)
    }
    @Test fun aCorruptDraftIsPreservedUntilItsNewOwnerExplicitlyClosesIt()=runBlocking {
        open(); val old=db.bookTransferDao.manager(id)!!; db.bookTransferDao.manager(old.copy(state="{"))
        val next=UUID.randomUUID().toString()
        assertTrue(runCatching { repo.open(id,next,2,7) }.isFailure)
        assertEquals(next,db.bookTransferDao.manager(id)!!.owner); assertEquals("{",db.bookTransferDao.manager(id)!!.state)
        repo.close(id,next); assertTrue(db.bookTransferDao.manager(id)!!.closed)
    }
}
