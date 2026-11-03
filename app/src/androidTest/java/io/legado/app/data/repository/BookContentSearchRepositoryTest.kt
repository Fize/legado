package io.legado.app.data.repository

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.AppDatabase
import io.legado.app.data.addLegadoMigrations
import io.legado.app.data.cache.CacheResourceLeases
import io.legado.app.data.entities.*
import io.legado.app.help.book.BookHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.model.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BookContentSearchRepositoryTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private val db=Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).addLegadoMigrations().build()
    private val leases=CacheResourceLeases()
    private val entries=TextReaderEntryRepository(db)
    private val content=TextReaderContentRepository(db,leases)
    private val repo=BookContentSearchRepository(db,content,entries)
    private val id=UUID.randomUUID().toString()
    private val book=Book(bookUrl="fixture://content-search-$id",origin="source-$id",name=id,author=id,
        readConfig=Book.ReadConfig(useReplaceRule=false))
    private val chapters=(0..2).map { BookChapter(bookUrl=book.bookUrl,url="fixture://chapter-$id-$it",index=it,title="Chapter $it") }
    private val oldTitle=ReadBookConfig.titleMode
    private val oldManual=AppConfig.manualReplaceRule
    private val oldConversion=AppConfig.chineseConverterType
    @Before fun setup() {
        ReadBookConfig.titleMode=2; AppConfig.manualReplaceRule=false; AppConfig.chineseConverterType=0
        db.bookDao.insert(book); db.bookChapterDao.insert(*chapters.toTypedArray())
        BookHelp.saveText(book,chapters[0],"First needle\nSecond needle 😀")
        BookHelp.saveText(book,chapters[1],"Last needle second chapter")
    }
    @After fun cleanup() {
        BookHelp.clearCache(book); db.close()
        ReadBookConfig.titleMode=oldTitle; AppConfig.manualReplaceRule=oldManual; AppConfig.chineseConverterType=oldConversion
    }
    private suspend fun open(query: String="needle",catalog: String?=null)=repo.open(id,book.bookUrl,"owner",query,catalog)
    private fun current()=checkNotNull(db.bookContentSearchDao.get(id))

    @Test fun searchReadsCachedChaptersWithoutClaimingOrChangingReadingProgress() = runBlocking {
        db.readerSessionDao.claim(ReaderSessionState("reader-$id",book.bookUrl,book.bookUrl,true,false,updatedAt=1),"reader-owner")
        repo.run(open())
        val saved=current()
        assertTrue(saved.complete); assertEquals(3,saved.resultCount); assertEquals(3,saved.searchedChapters)
        assertEquals(1,saved.skippedChapters); assertFalse(saved.running)
        assertEquals(listOf(0,0,1),repo.page(id,0).map { it.chapterIndex })
        assertTrue(db.readerSessionDao.ownsBook("reader-$id","reader-owner",0,book.bookUrl))
        assertEquals(0,db.bookDao.getBook(book.bookUrl)!!.durChapterPos)
        assertEquals(-1,db.readerSessionDao.get("reader-$id")!!.chapterIndex)
    }
    @Test fun progressCommitsPreserveTheLatestIndependentQueryDraft() = runBlocking {
        val initial=open(); repo.draft(initial,"Next query draft")
        repo.run(initial)
        assertEquals("Next query draft",current().draft)
        assertEquals("needle",current().query); assertEquals(3,current().resultCount)
    }
    @Test fun completedSessionsIgnoreRepeatedWorkersAndLateFailureReports() = runBlocking {
        val initial=open(); repo.run(initial); repo.run(initial)
        assertTrue(current().complete); assertNull(current().error); assertEquals(3,current().resultCount)
        assertFalse(db.bookContentSearchDao.setRunning(initial,false,"Late failure"))
        assertNull(current().error)
    }
    @Test fun aLateResumeCannotTurnACompletedSessionIntoAnEndlessRunningState() = runBlocking {
        val initial=open(); repo.run(initial)
        val completed=repo.resume(initial)
        assertTrue(completed.complete); assertFalse(completed.running)
        repo.run(completed); assertEquals(3,current().resultCount)
    }
    @Test fun searchingBeforeReplacementPublishesTheReadersActualCoordinates() = runBlocking {
        val rule=ReplaceRule(id=System.nanoTime(),name="Owned search rule",pattern="needle",replacement="replacement",scope=book.name,isEnabled=true)
        db.replaceRuleDao.insert(rule); db.bookDao.update(book.copy(readConfig=Book.ReadConfig(useReplaceRule=true)))
        val first=open(); val raw=repo.restart(first,"needle",false,true,false); repo.run(raw)
        val finished=current(); repo.select(finished,0,null)
        val entry=entries.cachedSearchEntry(book.bookUrl,0)
        val actual=entries.document(entry,checkNotNull(content.cachedContent(book.bookUrl,book.origin,0,entry.processing)))
        assertTrue(actual.text.contains("replacement")); assertFalse(actual.text.contains("needle"))
        assertEquals(actual.storagePosition(actual.text.indexOf("replacement")),current().selectedPosition)
        assertEquals(textReaderLinkKey(actual.text),current().selectedDocumentKey)
    }
    @Test fun changingAnEnabledRuleInvalidatesTheOldSearchSnapshot() = runBlocking {
        val rule=ReplaceRule(id=System.nanoTime(),name="Owned scope",pattern="needle",replacement="one",scope=book.name,isEnabled=true)
        db.replaceRuleDao.insert(rule); val initial=open()
        db.replaceRuleDao.update(rule.copy(replacement="two"))
        repo.run(initial); assertFalse(current().complete); assertNotNull(current().error)
        assertEquals(0,current().resultCount)
    }
    @Test fun expiredResultCacheKeepsTheDraftAndRevokesOldWriters() = runBlocking {
        val initial=open(); repo.draft(initial,"Retained draft"); repo.run(initial)
        db.openHelper.writableDatabase.execSQL("UPDATE book_content_search_sessions SET updatedAt=1 WHERE id=?",arrayOf(id))
        assertEquals(1,db.bookContentSearchDao.pruneResults(2))
        assertEquals("Retained draft",current().draft); assertEquals("needle",current().query)
        assertTrue(repo.page(id,0).isEmpty()); assertTrue(current().generation>initial.generation)
        repo.run(initial); assertNull(current().error)
        val fresh=repo.open(id,book.bookUrl,"fresh-owner","")
        repo.run(fresh); assertEquals(3,current().resultCount)
    }
    @Test fun disposingAnOldOwnerCannotReleaseItsReplacement() = runBlocking {
        open(); val replacement=repo.open(id,book.bookUrl,"replacement","")
        repo.release(id,"owner"); io.legado.app.help.globalExecutor.submit {}.get(5,java.util.concurrent.TimeUnit.SECONDS)
        assertEquals(replacement.owner,current().owner); assertEquals(replacement.epoch,current().epoch)
        repo.release(id,"replacement"); io.legado.app.help.globalExecutor.submit {}.get(5,java.util.concurrent.TimeUnit.SECONDS)
        assertEquals("",current().owner); assertFalse(current().running)
    }
    @Test fun aClosedSearchStillProvidesImmutableLegacyResultDataWithoutAnInMemoryHandoff() = runBlocking {
        repo.run(open()); repo.release(id,"owner")
        io.legado.app.help.globalExecutor.submit {}.get(5,java.util.concurrent.TimeUnit.SECONDS)
        val snapshot=repo.legacyResults(id,book.bookUrl)
        assertEquals("needle",snapshot.first.query)
        assertEquals(listOf(0,1,0),snapshot.second.map { it.resultCountWithinChapter })
        assertEquals(listOf(0,1,2),snapshot.second.map { it.resultCount })
        assertTrue(snapshot.second.all { it.resultText.contains("needle") })
    }
    @Test fun aNewGenerationRejectsOldSearchResultsAndCompletion() = runBlocking {
        val old=open(); val next=repo.restart(old,"second",false,true,false)
        repo.run(old); assertEquals(next.generation,current().generation); assertTrue(current().running)
        repo.run(next); assertTrue(current().complete); assertEquals(1,current().resultCount)
        assertEquals(next.generation,repo.page(id,0).single().generation)
    }
    @Test fun aNewOwnerRejectsThePreviousInstancesWrites() = runBlocking {
        val old=open(); val next=repo.open(id,book.bookUrl,"new-owner","needle")
        repo.run(old); assertTrue(current().running); assertEquals(next.epoch,current().epoch)
        repo.run(next); assertEquals(3,current().resultCount)
    }
    @Test fun stopRevokesAWorkerAndResumeContinuesThePersistedSession() = runBlocking {
        val old=open(); val stopped=repo.stop(old)
        repo.run(old); assertFalse(current().running); assertEquals(0,current().resultCount)
        repo.run(repo.resume(stopped)); assertTrue(current().complete); assertEquals(3,current().resultCount)
    }
    @Test fun invalidRegexDoesNotEraseExistingResultsOrChangeTheGeneration() = runBlocking {
        repo.run(open()); val before=current()
        assertTrue(runCatching { repo.restart(before,"(",true,true,false) }.isFailure)
        assertEquals(before.generation,current().generation); assertEquals(3,repo.page(id,0).size)
    }
    @Test fun zeroWidthRegexResultsCompleteWithoutRepeatingTheEndCursor() = runBlocking {
        val initial=open(); repo.run(repo.restart(initial,"(?=needle)",true,true,false))
        assertTrue(current().complete); assertEquals(3,current().resultCount)
        assertTrue(repo.page(id,0).all { it.displayStart==it.displayEnd })
    }
    @Test fun resultsAreCommittedInChunksAndPagesRemainBounded() = runBlocking {
        BookHelp.saveText(book,chapters[0],"needle ".repeat(300)); BookHelp.saveText(book,chapters[1],"No matches")
        repo.run(open()); assertEquals(300,current().resultCount)
        assertEquals(100,repo.page(id,0).size); assertEquals(100,repo.page(id,200).size)
        assertEquals(299,repo.page(id,200).last().ordinal)
    }
    @Test fun aFreshRepositoryResumesAfterTheLastCommittedMatch() = runBlocking {
        val initial=open()
        val entry=entries.cachedSearchEntry(book.bookUrl,0)
        val chapter=checkNotNull(content.cachedContent(book.bookUrl,book.origin,0,entry.processing,false))
        val document=entries.document(entry,chapter); val at=document.text.indexOf("needle")
        val hit=BookContentSearchHit(id,0,initial.generation,0,chapters[0].url,chapters[0].title,textReaderLinkKey(document.text),
            at,at+6,at,at+6,"needle",0,6)
        assertTrue(db.bookContentSearchDao.append(initial,listOf(hit),initial.copy(resultCount=1,matchOffset=at+6)))
        val fresh=BookContentSearchRepository(db,content,entries)
        val restored=fresh.open(id,book.bookUrl,"replacement-owner","")
        assertEquals(1,restored.resultCount); assertEquals(at+6,restored.matchOffset)
        fresh.run(restored); assertEquals(3,current().resultCount)
        assertEquals(listOf(0,1,2),fresh.page(id,0).map { it.ordinal })
    }
    @Test fun editedOrReplacedChapterResultsCannotBeSelected() = runBlocking {
        repo.run(open()); val finished=current()
        BookHelp.saveText(book,chapters[0],"Edited body")
        assertTrue(runCatching { repo.select(finished,0,null) }.isFailure)
        assertNull(current().selectedOrdinal)
        db.bookChapterDao.insert(chapters[1].copy(url="fixture://replacement-$id"))
        assertTrue(runCatching { repo.select(finished,2,null) }.isFailure)
        assertNull(current().selectedOrdinal)
    }
    @Test fun changingTheSourceRejectsTheOldQueryAndLeavesProgressUntouched() = runBlocking {
        val initial=open(); db.bookDao.update(book.copy(origin="new-source"))
        repo.run(initial); assertFalse(current().complete); assertNotNull(current().error)
        assertEquals(0,db.bookDao.getBook(book.bookUrl)!!.durChapterPos)
    }
    @Test fun anExpiredReadingRequestRollsBackTheSearchSelection() = runBlocking {
        val reader="reader-$id"; val token="reader-owner"
        db.readerSessionDao.claim(ReaderSessionState(reader,book.bookUrl,book.bookUrl,true,false,updatedAt=1),token)
        val request=checkNotNull(db.readerSessionDao.beginCatalog(reader,token,"catalog-$id",0,17,2))
        repo.run(open(catalog=request.id)); val finished=current()
        checkNotNull(db.readerSessionDao.moveBook(reader,token,book.bookUrl,0,19,3))
        assertTrue(runCatching { repo.select(finished,2,request.id) }.isFailure)
        assertNull(current().selectedOrdinal); assertNull(current().selectedPosition)
        assertNull(db.readerSessionDao.catalog(request.id)!!.chapterIndex)
        assertEquals(19,db.readerSessionDao.get(reader)!!.pageIndex)
        assertEquals(0,db.bookDao.getBook(book.bookUrl)!!.durChapterPos)
    }
    @Test fun selectingAHitDeliversOnlyItsBoundReadingRequest() = runBlocking {
        val reader="reader-$id"; val token="reader-owner"
        db.readerSessionDao.claim(ReaderSessionState(reader,book.bookUrl,book.bookUrl,true,false,updatedAt=1),token)
        val request=checkNotNull(db.readerSessionDao.beginCatalog(reader,token,"catalog-$id",0,17,2))
        repo.run(open(catalog=request.id)); val finished=current()
        assertTrue(runCatching { repo.select(finished,0,"other-request") }.isFailure)
        val selected=repo.select(finished,2,request.id); assertTrue(selected.delivered)
        val delivered=checkNotNull(db.readerSessionDao.catalog(request.id))
        assertEquals(1,delivered.chapterIndex); assertEquals(current().selectedPosition,delivered.pageIndex)
        assertEquals(17,db.readerSessionDao.get(reader)!!.pageIndex)
        assertEquals(0,db.bookDao.getBook(book.bookUrl)!!.durChapterPos)
    }
}
