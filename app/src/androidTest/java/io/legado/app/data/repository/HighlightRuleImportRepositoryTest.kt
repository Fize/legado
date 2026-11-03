package io.legado.app.data.repository

import androidx.core.content.FileProvider
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.constant.AppConst
import io.legado.app.data.*
import io.legado.app.data.entities.*
import io.legado.app.utils.GSON
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class HighlightRuleImportRepositoryTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private val db=Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).addLegadoMigrations().build()
    private val repo=HighlightRuleImportRepository(context,db)
    private val id=UUID.randomUUID().toString(); private val owner=UUID.randomUUID().toString()
    private val input=File(context.filesDir,"rule-import-${UUID.randomUUID()}.json")
    private val source get()=FileProvider.getUriForFile(context,AppConst.authority,input).toString()
    private fun write(vararg rules: HighlightRule) { input.writeText(GSON.toJson(HighlightRuleFile(HighlightRuleFile.TYPE,rules.toList()))) }
    private fun local(rule: HighlightRule)=rule.copy(id=db.highlightRuleDao.insert(rule).single())
    private suspend fun open()=repo.open(id,source,owner,1)
    @After fun cleanup() { db.close(); input.delete() }
    @Test fun readyPreviewRestoresItsSelectionAndSnapshotAfterTheProviderFileDisappears()=runBlocking {
        val first=HighlightRule(name="First",pattern="first"); val second=HighlightRule(name="Second",pattern="second")
        write(first,second); val ready=open()
        repo.select(id,owner,ready.session.revision+1,first.uuid,false); input.delete()
        val restored=repo.open(id,source,UUID.randomUUID().toString(),2)
        assertEquals(listOf(first.uuid,second.uuid),restored.choices.map { it.uuid })
        assertFalse(restored.choices.first().selected); assertTrue(restored.choices.last().selected)
        assertTrue(db.highlightRuleDao.all.isEmpty())
        assertTrue(runCatching { (restored.choices as MutableList<*>).clear() }.isFailure)
    }
    @Test fun importKeepsLocalIdentitiesAndUnknownStyleFieldsWithoutRemoteIdCollisions()=runBlocking {
        val existing=local(HighlightRule(name="Local",pattern="old"))
        val changed=existing.copy(id=999,name="Imported",pattern="new",style="{\"future\":{\"x\":1},\"bold\":true}")
        val added=HighlightRule(id=existing.id,name="Added",pattern="added")
        write(changed,added); open(); val done=repo.finish(id,owner,true)
        assertEquals("completed",done.session.phase); assertTrue(done.choices.isEmpty())
        val rows=db.highlightRuleDao.all
        assertEquals(existing.id,rows.single { it.uuid==existing.uuid }.id)
        assertNotEquals(existing.id,rows.single { it.uuid==added.uuid }.id)
        assertEquals(changed.style,rows.single { it.uuid==existing.uuid }.style)
        assertTrue(db.highlightRuleImportDao.rows(id).isEmpty()); assertEquals("",done.session.source)
    }
    @Test fun unchangedRulesStartUnselectedButMayBeExplicitlySelected()=runBlocking {
        val existing=local(HighlightRule(name="Same",pattern="same")); write(existing.copy(id=99,order=88))
        val ready=open(); assertEquals("EXISTING",ready.choices.single().status); assertFalse(ready.choices.single().selected)
        repo.select(id,owner,ready.session.revision+1,existing.uuid,true)
        repo.finish(id,owner,true); assertEquals(existing.id,db.highlightRuleDao.all.single().id)
    }
    @Test fun anEditedPreviewTargetRejectsTheWholeBatchUntilAnExplicitReview()=runBlocking {
        val existing=local(HighlightRule(name="Local",pattern="old")); val added=HighlightRule(pattern="added")
        write(existing.copy(pattern="incoming"),added); open()
        db.highlightRuleDao.update(existing.copy(pattern="concurrent"))
        assertTrue(runCatching { repo.finish(id,owner,true) }.isFailure)
        assertEquals("concurrent",db.highlightRuleDao.all.single().pattern)
        assertEquals("ready",db.highlightRuleImportDao.get(id)!!.phase)
        input.delete(); repo.retry(id,owner); repo.finish(id,owner,true)
        assertEquals("incoming",db.highlightRuleDao.all.single { it.uuid==existing.uuid }.pattern)
        assertEquals(2,db.highlightRuleDao.all.size)
    }
    @Test fun aNewlyAppearedUuidCannotBeOverwrittenByAnOldNewRulePreview()=runBlocking {
        val incoming=HighlightRule(pattern="incoming"); write(incoming); open()
        val current=local(incoming.copy(pattern="appeared"))
        assertTrue(runCatching { repo.finish(id,owner,true) }.isFailure)
        assertEquals("appeared",db.highlightRuleDao.findById(current.id)!!.pattern)
    }
    @Test fun replacingADeletedRuleWithTheSameUuidStillInvalidatesItsOldPreview()=runBlocking {
        val current=local(HighlightRule(pattern="old")); write(current.copy(pattern="incoming")); open()
        db.highlightRuleDao.delete(current); db.highlightRuleDao.insert(current.copy(id=current.id+100))
        assertTrue(runCatching { repo.finish(id,owner,true) }.isFailure)
        assertEquals("old",db.highlightRuleDao.all.single().pattern)
    }
    @Test fun unselectedConcurrentChangesArePreservedByTheImportTransaction()=runBlocking {
        val first=local(HighlightRule(pattern="first")); val second=local(HighlightRule(pattern="second"))
        write(first.copy(pattern="incoming"),second.copy(pattern="unselected")); val ready=open()
        repo.select(id,owner,ready.session.revision+1,second.uuid,false)
        db.highlightRuleDao.update(second.copy(name="Concurrent",pattern="keep"))
        repo.finish(id,owner,true)
        assertEquals("incoming",db.highlightRuleDao.findById(first.id)!!.pattern)
        assertEquals("keep",db.highlightRuleDao.findById(second.id)!!.pattern)
        assertEquals("Concurrent",db.highlightRuleDao.findById(second.id)!!.name)
    }
    @Test fun oldClaimsSelectionsConfirmationAndCancelCannotAffectANewOwner()=runBlocking {
        write(HighlightRule(pattern="candidate")); val ready=open(); val next=UUID.randomUUID().toString()
        repo.open(id,source,next,3)
        assertTrue(runCatching { repo.open(id,source,owner,2) }.isFailure)
        assertTrue(runCatching { repo.select(id,owner,ready.session.revision+1,null,false) }.isFailure)
        assertTrue(runCatching { repo.finish(id,owner,true) }.isFailure)
        assertTrue(runCatching { repo.cancel(id,source,owner,1) }.isFailure)
        assertEquals(next,db.highlightRuleImportDao.get(id)!!.owner); assertTrue(db.highlightRuleDao.all.isEmpty())
    }
    @Test fun staleSelectionRevisionsAndAChangedSourceAreRejected()=runBlocking {
        val rule=HighlightRule(pattern="candidate"); write(rule); val ready=open()
        repo.select(id,owner,ready.session.revision+2,null,false)
        assertTrue(runCatching { repo.select(id,owner,ready.session.revision+1,null,true) }.isFailure)
        assertTrue(runCatching { repo.open(id,source+"?different",owner,2) }.isFailure)
        assertFalse(db.highlightRuleImportDao.choices(id).single().selected)
    }
    @Test fun successfulConfirmationIsIdempotentAndRestoresWithoutReadingTheProvider()=runBlocking {
        val rule=HighlightRule(pattern="candidate"); write(rule); open()
        repo.finish(id,owner,true); input.delete()
        repo.finish(id,owner,true)
        val restored=repo.open(id,source,UUID.randomUUID().toString(),2)
        assertEquals("completed",restored.session.phase); assertEquals(1,db.highlightRuleDao.all.size)
    }
    @Test fun cancellingClearsCandidatesAndNeverImportsThemOnRestore()=runBlocking {
        write(HighlightRule(pattern="candidate")); open(); repo.finish(id,owner,false); input.delete()
        val restored=repo.open(id,source,UUID.randomUUID().toString(),2)
        assertEquals("cancelled",restored.session.phase); assertTrue(restored.choices.isEmpty())
        assertTrue(db.highlightRuleDao.all.isEmpty())
    }
    @Test fun malformedFilesFailWithoutPartialCandidatesAndCanBeRetriedExplicitly()=runBlocking {
        input.writeText("not json"); assertTrue(runCatching { open() }.isFailure)
        assertEquals("failed",db.highlightRuleImportDao.get(id)!!.phase)
        assertTrue(db.highlightRuleImportDao.rows(id).isEmpty()); assertTrue(db.highlightRuleDao.all.isEmpty())
        write(HighlightRule(pattern="fixed")); val retried=repo.retry(id,owner)
        assertEquals("ready",retried.session.phase); assertEquals(1,retried.choices.size)
    }
    @Test fun aCancelledOrReplacedOwnerRejectsAnAlreadyRunningHttpPreview()=runBlocking {
        val listener=ServerSocket(0); val arrived=CountDownLatch(1); val release=CountDownLatch(1)
        val body=GSON.toJson(HighlightRuleFile(HighlightRuleFile.TYPE,listOf(HighlightRule(pattern="late")))).toByteArray()
        val server=thread {
            runCatching { listener.accept().use { socket ->
                socket.soTimeout=10_000
                val reader=socket.getInputStream().bufferedReader()
                while(reader.readLine()?.isNotEmpty()==true) { }
                arrived.countDown(); check(release.await(10,TimeUnit.SECONDS))
                socket.getOutputStream().apply {
                    write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray()); write(body); flush()
                }
            } }
        }
        val url="http://127.0.0.1:${listener.localPort}/rules"
        val request=async(Dispatchers.IO) { runCatching { repo.open(id,url,owner,1) } }
        try {
            assertTrue(arrived.await(10,TimeUnit.SECONDS))
            val next=UUID.randomUUID().toString(); repo.cancel(id,url,next,2)
            release.countDown(); assertTrue(request.await().isFailure)
            assertEquals("cancelled",db.highlightRuleImportDao.get(id)!!.phase)
            assertTrue(db.highlightRuleImportDao.rows(id).isEmpty()); assertTrue(db.highlightRuleDao.all.isEmpty())
        } finally { release.countDown(); listener.close(); server.join(10_000); request.cancelAndJoin() }
    }
    @Test fun previewMetadataExcludesLargeExpressionsAndStyles()=runBlocking {
        val rule=HighlightRule(name="Short",pattern="x".repeat(150_000),style="{\"future\":\"${"y".repeat(150_000)}\"}")
        write(rule); val ready=open()
        assertEquals("Short",ready.choices.single().name)
        assertTrue(GSON.toJson(ready.choices).length<1024)
        assertTrue(db.highlightRuleImportDao.rows(id).single().value.length>300_000)
        write(rule.copy(name="",pattern="😀".repeat(1000)))
        val fallback=repo.open(UUID.randomUUID().toString(),source,UUID.randomUUID().toString(),2)
        assertEquals("😀".repeat(512),fallback.choices.single().name)
    }
    @Test fun unsuccessfulHttpResponsesNeverBecomeImportablePreviewsEvenWithValidJson()=runBlocking {
        val body=GSON.toJson(HighlightRuleFile(HighlightRuleFile.TYPE,listOf(HighlightRule(pattern="must-not-import"))))
        val server=object: fi.iki.elonen.NanoHTTPD("127.0.0.1",0) {
            override fun serve(session: IHTTPSession)=newFixedLengthResponse(Response.Status.NOT_FOUND,"application/json",body)
        }
        server.start(fi.iki.elonen.NanoHTTPD.SOCKET_READ_TIMEOUT,false)
        try {
            assertTrue(runCatching { repo.open(id,"http://127.0.0.1:${server.listeningPort}/missing",owner,1) }.isFailure)
            assertEquals("failed",db.highlightRuleImportDao.get(id)!!.phase)
            assertTrue(db.highlightRuleImportDao.rows(id).isEmpty()); assertTrue(db.highlightRuleDao.all.isEmpty())
        } finally { server.stop() }
    }

    private suspend fun parentRequest(): Pair<String,String> {
        val parent=UUID.randomUUID().toString(); val managerOwner=UUID.randomUUID().toString()
        val manager=HighlightRuleManagerRepository(db,HighlightRuleFiles(context))
        manager.open(parent,managerOwner,1)
        val pending=manager.startImport(parent,managerOwner,1).draft.importRequest!!
        manager.importResult(parent,managerOwner,2,pending.id,source)
        return parent to pending.id
    }
    @Test fun aClosedOrDeletedParentCannotBeResurrectedAsAStandaloneImport()=runBlocking {
        write(HighlightRule(pattern="candidate")); val (parent,child)=parentRequest()
        repo.open(child,source,owner,1,parent)
        val record=db.highlightRuleManagerDao.get(parent)!!
        HighlightRuleManagerRepository(db,HighlightRuleFiles(context)).close(parent,record.owner)
        assertEquals("cancelled",repo.open(child,source,UUID.randomUUID().toString(),2,parent).session.phase)
        db.highlightRuleManagerDao.delete(parent)
        assertTrue(runCatching { repo.open(child,source,UUID.randomUUID().toString(),3,parent) }.isFailure)
        assertNull(db.highlightRuleImportDao.get(child)); assertTrue(db.highlightRuleDao.all.isEmpty())
    }
    @Test fun aManagerImportCannotBeClaimedOrCancelledWithoutItsOriginalParent()=runBlocking {
        write(HighlightRule(pattern="candidate")); val (parent,child)=parentRequest()
        assertTrue(runCatching { repo.open(child,source,owner,1) }.isFailure)
        assertEquals(parent,db.highlightRuleImportDao.get(child)!!.parent)
        repo.open(child,source,owner,2,parent)
        assertTrue(runCatching { repo.cancel(child,source,UUID.randomUUID().toString(),3) }.isFailure)
        assertEquals("ready",db.highlightRuleImportDao.get(child)!!.phase)
        assertEquals(owner,db.highlightRuleImportDao.get(child)!!.owner)
    }

    @Test fun importNormalizationOfGroupScopeAndTimeoutIsAlwaysShownAsAnUpdate()=runBlocking {
        listOf(HighlightRule(pattern="group",group=" Group "),HighlightRule(pattern="scope",scope=" "),
            HighlightRule(pattern="timeout",timeoutMillisecond=0)).forEach { candidate ->
            val current=local(candidate); write(current)
            val request=UUID.randomUUID().toString(); val key=UUID.randomUUID().toString()
            val preview=repo.open(request,source,key,1)
            assertEquals("UPDATE",preview.choices.single().status); assertTrue(preview.choices.single().selected)
            repo.finish(request,key,true)
            val saved=db.highlightRuleDao.findById(current.id)!!
            when(candidate.pattern) {
                "group" -> assertEquals("Group",saved.group)
                "scope" -> assertNull(saved.scope)
                "timeout" -> assertEquals(HighlightRule.DEFAULT_TIMEOUT_MILLISECONDS,saved.timeoutMillisecond)
            }
        }
    }

}
