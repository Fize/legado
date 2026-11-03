package io.legado.app.data.repository

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.AppDatabase
import io.legado.app.data.addLegadoMigrations
import io.legado.app.data.entities.*
import io.legado.app.help.HighlightRuleMatcher
import io.legado.app.help.HighlightStyle
import io.legado.app.model.TextReaderDocument
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class TextReaderHighlightRepositoryTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private val db=Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).addLegadoMigrations().build()
    private val id=UUID.randomUUID().toString()
    private val book=Book(bookUrl="fixture://rule-$id",origin="source-$id",name=id,author=id,durChapterPos=73)
    private val repo=TextReaderHighlightRepository(db)
    private val document=TextReaderDocument("Title amber\namber 😀 fresh old",12)
    @Before fun setup() { db.bookDao.insert(book) }
    @After fun cleanup() { db.close() }
    private fun rule(pattern: String="amber",scope: String?=null,title: Boolean=false,body: Boolean=true,
        enabled: Boolean=true,regex: Boolean=false,order: Int=0): HighlightRule {
        val value=HighlightRule(pattern=pattern,scope=scope,isEnabled=enabled,isRegex=regex,order=order,applyToTitle=title,applyToBody=body)
            .apply { applyStyle(HighlightStyle(fill=0xffff0000.toInt())) }
        return value.copy(id=db.highlightRuleDao.insert(value).single())
    }
    private suspend fun result(doc: TextReaderDocument=document)=withTimeout(10_000) { repo.observe(book.bookUrl,book.origin,doc).first() }
    @Test fun enabledRulesUseTheExistingBookNameAndSourceScopeContract()=runBlocking {
        val global=rule(); val name=rule(scope="prefix ${book.name}"); val source=rule(scope=book.origin)
        rule(scope="unrelated-${UUID.randomUUID()}"); rule(enabled=false)
        assertEquals(setOf(global.id,name.id,source.id),result().matches.map { it.rule.id }.toSet())
        assertEquals(73,db.bookDao.getBook(book.bookUrl)!!.durChapterPos); assertNull(db.readerSessionDao.get(id))
    }
    @Test fun titleAndBodyAreMatchedInTheirOwnDisplayedSegments()=runBlocking {
        val title=rule(title=true,body=false); val body=rule(regex=true,pattern="^amber")
        val hits=result().matches
        assertEquals(6,hits.single { it.rule.id==title.id }.start)
        assertEquals(12,hits.single { it.rule.id==body.id }.start)
    }
    @Test fun orderingFollowsSortOrderAndThenDatabaseIdentity()=runBlocking {
        val second=rule(order=2); val first=rule(order=1); val third=rule(order=2)
        assertEquals(listOf(first.id,second.id,third.id),result().matches.map { it.rule.id })
    }
    @Test fun invalidRegexDoesNotSuppressAValidFollowingRule()=runBlocking {
        rule(pattern="[",regex=true); val valid=rule()
        val result=result(); assertTrue(result.completed); assertEquals(valid.id,result.matches.single().rule.id)
    }
    @Test fun emptyBookIdentityDoesNotMatchEveryNonemptyScope()=runBlocking {
        db.bookDao.update(book.copy(name="",origin="")); rule(scope="different")
        val result=withTimeout(10_000) { repo.observe(book.bookUrl,"",document).first() }
        assertTrue(result.matches.isEmpty())
    }
    @Test fun updatesToTheSameIdPublishFreshPatternStyleAndTitleFlags()=runBlocking {
        val original=rule()
        val values=mutableListOf<TextReaderRuleHighlights>()
        val collection=launch { repo.observe(book.bookUrl,book.origin,document).take(2).toList(values) }
        withTimeout(10_000) { while(values.isEmpty()) delay(10) }
        db.highlightRuleDao.update(original.copy(pattern="fresh",applyToTitle=true,style="{\"bold\":true}"))
        withTimeout(10_000) { collection.join() }
        assertEquals("fresh",values.last().matches.single().rule.pattern); assertTrue(values.last().matches.single().rule.style.bold)
        assertNotEquals(values.first().matches.single().rule.fingerprint,values.last().matches.single().rule.fingerprint)
    }
    @Test fun disablingAndDeletingRulesClearTheirResults()=runBlocking {
        val first=rule(); val results=mutableListOf<TextReaderRuleHighlights>()
        val collection=launch { repo.observe(book.bookUrl,book.origin,document).take(3).toList(results) }
        withTimeout(10_000) { while(results.size<1) delay(10) }
        db.highlightRuleDao.update(first.copy(isEnabled=false))
        withTimeout(10_000) { while(results.size<2) delay(10) }
        val next=rule(pattern="fresh")
        withTimeout(10_000) { collection.join() }
        assertTrue(results[1].matches.isEmpty()); assertEquals(next.id,results[2].matches.single().rule.id)
        db.highlightRuleDao.delete(next); assertTrue(result().matches.isEmpty())
    }
    @Test fun anEditedRuleCannotBeDisabledUsingTheOldPopupSnapshot()=runBlocking {
        val original=rule(); val snapshot=result().matches.single().rule
        db.highlightRuleDao.update(original.copy(name="Edited elsewhere"))
        assertTrue(runCatching { repo.disable(snapshot) }.isFailure)
        assertTrue(db.highlightRuleDao.findById(original.id)!!.isEnabled)
    }
    @Test fun aRuleReplacementWithTheSameIdIsNotTheOldPopupTarget()=runBlocking {
        val original=rule(); val snapshot=result().matches.single().rule
        db.highlightRuleDao.update(original.copy(uuid=UUID.randomUUID().toString()))
        assertTrue(runCatching { repo.disable(snapshot) }.isFailure)
    }
    @Test fun aCurrentPopupCanDisableItsRuleWithoutChangingReadingProgress()=runBlocking {
        val original=rule(); repo.disable(result().matches.single().rule)
        assertFalse(db.highlightRuleDao.findById(original.id)!!.isEnabled)
        assertEquals(73,db.bookDao.getBook(book.bookUrl)!!.durChapterPos)
    }
    @Test fun hitsNeverSplitTheMiddleOfAnEmojiSurrogatePair()=runBlocking {
        rule(pattern="\uDE00"); val emoji=rule(pattern="😀")
        val hits=result().matches
        assertEquals(emoji.id,hits.single().rule.id)
        assertEquals("😀",document.text.substring(hits.single().start,hits.single().end))
    }
    @Test fun theMatchLimitDiscardsPartialRuleHighlighting()=runBlocking {
        rule(pattern="x")
        val result=result(TextReaderDocument("x".repeat(10_001),0))
        assertFalse(result.completed); assertTrue(result.matches.isEmpty())
    }
    @Test fun aLateOldWorkerCannotPublishAfterTheRuleWasEdited()=runBlocking {
        val original=rule(pattern="old"); val started=CountDownLatch(1); val release=CountDownLatch(1)
        val controlled=TextReaderHighlightRepository(db,TextReaderRuleEngine { text,rules,active,title ->
            if(rules.firstOrNull()?.pattern=="old") { started.countDown(); check(release.await(5,TimeUnit.SECONDS)) }
            HighlightRuleMatcher.matchDetailed(text,rules,active,titleLength=title)
        })
        val emitted=java.util.Collections.synchronizedList(mutableListOf<TextReaderRuleHighlights>())
        val deferred=async(Dispatchers.Default) { controlled.observe(book.bookUrl,book.origin,document).onEach { emitted+=it }.first { it.matches.firstOrNull()?.rule?.pattern=="fresh" } }
        try {
            assertTrue(started.await(5,TimeUnit.SECONDS)); db.highlightRuleDao.update(original.copy(pattern="fresh")); release.countDown()
            withTimeout(10_000) { deferred.await() }
            assertTrue(emitted.none { it.matches.any { hit -> hit.rule.pattern=="old" } })
        } finally { release.countDown(); deferred.cancelAndJoin() }
    }
    @Test fun sourceChangesRejectTheOldWorkerAndClearItsHighlights()=runBlocking {
        rule(); val started=CountDownLatch(1); val release=CountDownLatch(1)
        val controlled=TextReaderHighlightRepository(db,TextReaderRuleEngine { text,rules,active,title ->
            started.countDown(); check(release.await(5,TimeUnit.SECONDS)); HighlightRuleMatcher.matchDetailed(text,rules,active,titleLength=title)
        })
        val deferred=async(Dispatchers.Default) { controlled.observe(book.bookUrl,book.origin,document).first { !it.validBook } }
        try {
            assertTrue(started.await(5,TimeUnit.SECONDS)); db.bookDao.update(book.copy(origin="new-origin")); release.countDown()
            assertTrue(withTimeout(10_000) { deferred.await() }.matches.isEmpty())
        } finally { release.countDown(); deferred.cancelAndJoin() }
    }
    @Test fun cancellingTheObserverStopsTheMatchingWorker()=runBlocking {
        rule(); val started=CountDownLatch(1); val stopped=CountDownLatch(1)
        val controlled=TextReaderHighlightRepository(db,TextReaderRuleEngine { _,_,active,_ ->
            started.countDown(); while(active()) Thread.yield(); stopped.countDown(); HighlightRuleMatcher.MatchResult(emptyList(),false)
        })
        val collection=launch(Dispatchers.Default) { controlled.observe(book.bookUrl,book.origin,document).collect() }
        try { assertTrue(started.await(5,TimeUnit.SECONDS)); withTimeout(3_000) { collection.cancelAndJoin() }; assertTrue(stopped.await(1,TimeUnit.SECONDS)) }
        finally { collection.cancelAndJoin() }
    }
}
