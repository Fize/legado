package io.legado.app.data.repository

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.*
import io.legado.app.data.entities.HighlightRule
import io.legado.app.help.HighlightStyle
import io.legado.app.model.mergeReaderHighlightStyle
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class HighlightRuleEditorRepositoryTest {
    private val db=Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext,AppDatabase::class.java).addLegadoMigrations().build()
    private val repo=HighlightRuleEditorRepository(db)
    private val id=UUID.randomUUID().toString()
    private val owner=UUID.randomUUID().toString()
    private fun insert(): HighlightRule {
        val rule=HighlightRule(name="original",pattern="amber",scope="book",group="group",isEnabled=false,order=12,timeoutMillisecond=27,
            style="{\"fill\":1,\"extension\":7}")
        return rule.copy(id=db.highlightRuleDao.insert(rule).single())
    }
    @After fun cleanup() { db.close() }
    @Test fun aPreparedSelectionKeepsItsStyleAndDoesNotCreateARule()=runBlocking {
        val request=repo.prepare("amber 😀",scope="Book",style="{\"extension\":9,\"fill\":3}")
        val value=repo.decode(repo.open(request,null,owner))
        assertEquals("amber 😀",value.name); assertEquals(value.name,value.pattern); assertEquals("Book",value.scope)
        assertTrue(value.style.contains("extension")); assertTrue(db.highlightRuleDao.all.isEmpty())
    }
    @Test fun existingIdentityAndUneditedMetadataSurviveSave()=runBlocking {
        val original=insert(); val draft=repo.open(id,original.id,owner)
        repo.update(id,owner,1,repo.decode(draft).copy(name="Changed",pattern="violet"))
        repo.finish(id,owner,true)
        val saved=db.highlightRuleDao.findById(original.id)!!
        assertEquals(original.uuid,saved.uuid); assertEquals("Changed",saved.name); assertFalse(saved.isEnabled)
        assertEquals(12,saved.order); assertEquals(27L,saved.timeoutMillisecond); assertEquals(original.style,saved.style)
    }
    @Test fun openingANewOwnerRestoresLargeDraftsFromRoom()=runBlocking {
        val draft=repo.open(id,null,owner); val text="amber 😀".repeat(30_000)
        repo.update(id,owner,1,repo.decode(draft).copy(pattern=text))
        val restored=repo.open(id,null,UUID.randomUUID().toString())
        assertEquals(text,repo.decode(restored).pattern); assertEquals(1L,restored.revision)
        assertTrue(db.highlightRuleDao.all.isEmpty())
    }
    @Test fun oldOwnerCannotWriteAfterRecreation()=runBlocking {
        val draft=repo.open(id,null,owner); repo.open(id,null,UUID.randomUUID().toString())
        assertTrue(runCatching { repo.update(id,owner,2,repo.decode(draft).copy(pattern="stale")) }.isFailure)
        assertEquals("",repo.decode(db.highlightRuleDraftDao.get(id)!!).pattern)
    }
    @Test fun oldOwnerCannotCancelTheReplacementEditor()=runBlocking {
        repo.open(id,null,owner); repo.open(id,null,UUID.randomUUID().toString())
        assertTrue(runCatching { repo.finish(id,owner,false) }.isFailure)
        assertEquals("editing",db.highlightRuleDraftDao.get(id)!!.phase)
    }
    @Test fun repeatedSaveCannotInsertDuplicateRules()=runBlocking {
        val draft=repo.open(id,null,owner); repo.update(id,owner,1,repo.decode(draft).copy(pattern="amber"))
        val first=repo.finish(id,owner,true); val second=repo.finish(id,owner,true)
        assertEquals(first.savedId,second.savedId); assertEquals(1,db.highlightRuleDao.all.size)
        assertEquals("",second.value)
    }
    @Test fun reopeningASavedRequestRemainsTerminal()=runBlocking {
        val draft=repo.open(id,null,owner); repo.update(id,owner,1,repo.decode(draft).copy(pattern="amber")); repo.finish(id,owner,true)
        val restored=repo.open(id,null,UUID.randomUUID().toString())
        assertEquals("saved",restored.phase); assertEquals(1,db.highlightRuleDao.all.size)
    }
    @Test fun cancellationLeavesTheRuleUntouchedAndRejectsLateInput()=runBlocking {
        val original=insert(); val draft=repo.open(id,original.id,owner)
        repo.update(id,owner,1,repo.decode(draft).copy(pattern="unsaved")); repo.finish(id,owner,false)
        assertEquals(highlightRuleIdentity(original),highlightRuleIdentity(db.highlightRuleDao.findById(original.id)!!))
        assertTrue(runCatching { repo.update(id,owner,2,original) }.isFailure)
        assertEquals("",db.highlightRuleDraftDao.get(id)!!.value)
    }
    @Test fun emptyPatternCannotBeCommitted()=runBlocking {
        repo.open(id,null,owner); assertTrue(runCatching { repo.finish(id,owner,true) }.isFailure)
        assertEquals("editing",db.highlightRuleDraftDao.get(id)!!.phase); assertTrue(db.highlightRuleDao.all.isEmpty())
    }
    @Test fun invalidRegexCannotBeCommitted()=runBlocking {
        val draft=repo.open(id,null,owner); repo.update(id,owner,1,repo.decode(draft).copy(pattern="[",isRegex=true))
        assertTrue(runCatching { repo.finish(id,owner,true) }.isFailure); assertTrue(db.highlightRuleDao.all.isEmpty())
    }
    @Test fun aDeletedRuleCannotBeResurrectedByItsEditor()=runBlocking {
        val original=insert(); repo.open(id,original.id,owner); db.highlightRuleDao.delete(original)
        assertTrue(runCatching { repo.finish(id,owner,true) }.isFailure); assertTrue(db.highlightRuleDao.all.isEmpty())
    }
    @Test fun importedReplacementCannotBeOverwritten()=runBlocking {
        val original=insert(); repo.open(id,original.id,owner)
        val replacement=original.copy(uuid=UUID.randomUUID().toString(),pattern="replacement"); db.highlightRuleDao.update(replacement)
        assertTrue(runCatching { repo.finish(id,owner,true) }.isFailure)
        assertEquals(replacement.uuid,db.highlightRuleDao.findById(original.id)!!.uuid)
    }
    @Test fun concurrentMetadataEditsCauseAConflict()=runBlocking {
        val original=insert(); repo.open(id,original.id,owner); db.highlightRuleDao.update(original.copy(group="another"))
        assertTrue(runCatching { repo.finish(id,owner,true) }.isFailure)
        assertEquals("another",db.highlightRuleDao.findById(original.id)!!.group)
    }
    @Test fun independentEditorsCannotOverwriteEachOther()=runBlocking {
        val original=insert(); val other=UUID.randomUUID().toString()
        repo.open(id,original.id,owner); val draft=repo.open(other,original.id,owner)
        repo.update(other,owner,1,repo.decode(draft).copy(name="Winner")); repo.finish(other,owner,true)
        assertTrue(runCatching { repo.finish(id,owner,true) }.isFailure)
        assertEquals("Winner",db.highlightRuleDao.findById(original.id)!!.name)
    }
    @Test fun lateRevisionsAndRuleIdentityChangesAreRejected()=runBlocking {
        val draft=repo.open(id,null,owner); val value=repo.decode(draft)
        repo.update(id,owner,2,value.copy(pattern="latest"))
        assertTrue(runCatching { repo.update(id,owner,1,value) }.isFailure)
        assertTrue(runCatching { repo.update(id,owner,3,value.copy(uuid=UUID.randomUUID().toString())) }.isFailure)
        assertEquals("latest",repo.decode(db.highlightRuleDraftDao.get(id)!!).pattern)
    }
    @Test fun requestCannotBeReusedForAnotherRule()=runBlocking {
        repo.open(id,null,owner); val original=insert()
        assertTrue(runCatching { repo.open(id,original.id,owner) }.isFailure)
    }
    @Test fun invalidRequestKeysAreRejected()=runBlocking {
        assertTrue(runCatching { repo.open("../draft",null,owner) }.isFailure)
        assertTrue(runCatching { repo.open(id,null,"owner") }.isFailure)
        assertNull(db.highlightRuleDraftDao.get(id))
    }
    @Test fun appendOrderingDoesNotWrapWhenImportedOrderIsMaxInt()=runBlocking {
        val original=insert(); db.highlightRuleDao.update(original.copy(order=Int.MAX_VALUE))
        val draft=repo.open(id,null,owner); repo.update(id,owner,1,repo.decode(draft).copy(pattern="new"))
        val result=repo.finish(id,owner,true)
        assertEquals(Int.MAX_VALUE,db.highlightRuleDao.findById(result.savedId!!)!!.order)
    }
    @Test fun queuedFieldEditsPreserveTheLatestStyleAndItsExtensions()=runBlocking {
        val original=insert(); repo.open(id,original.id,owner)
        repo.mutate(id,owner,1) { it.copy(style=mergeReaderHighlightStyle(it.style,HighlightStyle(bold=true))) }
        repo.mutate(id,owner,2) { it.copy(name="new name") }
        val restored=repo.decode(repo.open(id,original.id,UUID.randomUUID().toString()))
        assertEquals("new name",restored.name); assertTrue(restored.styleObj().bold); assertTrue(restored.style.contains("extension"))
    }
    @Test fun aLateOldClaimCannotDisplaceTheNewViewModelOwner()=runBlocking {
        val replacement=UUID.randomUUID().toString()
        repo.open(id,null,replacement,20)
        assertTrue(runCatching { repo.open(id,null,owner,10) }.isFailure)
        assertEquals(replacement,db.highlightRuleDraftDao.get(id)!!.owner)
    }

    @Test fun anImportedIdentityCannotBeReplacedByANewDraft()=runBlocking {
        val draft=repo.open(id,null,owner)
        val value=repo.decode(draft).copy(pattern="draft")
        repo.update(id,owner,1,value)
        val imported=value.copy(name="Imported",pattern="winner")
        val inserted=db.highlightRuleDao.insert(imported).single()
        assertTrue(runCatching { repo.finish(id,owner,true) }.isFailure)
        assertEquals(1,db.highlightRuleDao.all.size)
        assertEquals("winner",db.highlightRuleDao.findById(inserted)!!.pattern)
        assertEquals("editing",db.highlightRuleDraftDao.get(id)!!.phase)
    }

}
