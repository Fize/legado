package io.legado.app.data.repository

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.*
import io.legado.app.data.entities.*
import io.legado.app.utils.GSON
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class HighlightRuleFileMaintenanceTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private val root=File(context.cacheDir,"rule-maintenance-${UUID.randomUUID()}")
    private val db=Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).addLegadoMigrations().build()
    private val files=HighlightRuleFiles(context,root)
    private val repo=HighlightRuleManagerRepository(db,files)
    private val id=UUID.randomUUID().toString(); private val owner=UUID.randomUUID().toString()
    private val now=System.currentTimeMillis()
    private fun payload(key: String=UUID.randomUUID().toString()): String {
        files.prepare(key,HighlightRuleFile(type=HighlightRuleFile.TYPE,rules=emptyList())); return key
    }
    private fun file(key: String)=File(root,"$key.json")
    private suspend fun pending(kind: String="share"): HighlightRuleTransfer {
        db.highlightRuleDao.insert(HighlightRule(name="own",pattern="own"))
        repo.open(id,owner,1)
        return repo.prepare(id,owner,1,repo.observe().first().map { it.ref },kind).draft.transfer!!
    }
    @After fun cleanup() { db.close(); root.deleteRecursively() }
    @Test fun completedSharesKeepAFullGracePeriodThenRemovePayloadAndMarker()=runBlocking {
        val transfer=pending(); repo.clearTransfer(id,owner,2,transfer.id)
        assertTrue(file(transfer.id).isFile)
        assertEquals(0,repo.cleanFiles(now+HighlightRuleFiles.RETENTION_MILLIS-1))
        assertTrue(file(transfer.id).exists())
        assertEquals(1,repo.cleanFiles(System.currentTimeMillis()+HighlightRuleFiles.RETENTION_MILLIS+1))
        assertFalse(file(transfer.id).exists()); assertFalse(File(root,"${transfer.id}.retained").exists())
    }
    @Test fun pendingTransfersAreProtectedEvenWhenTheirFilesAndMarkersAreOld()=runBlocking {
        val transfer=pending("export"); files.retain(transfer.id,1)
        assertTrue(file(transfer.id).setLastModified(1))
        assertEquals(0,repo.cleanFiles(now)); assertTrue(file(transfer.id).exists())
        assertFalse(File(root,"${transfer.id}.retained").exists())
    }
    @Test fun legacyAndUnpublishedFilesStartRetentionAtDiscoveryNotTheirFileDate()=runBlocking {
        val key=payload(); assertTrue(file(key).setLastModified(1))
        assertEquals(0,repo.cleanFiles(now)); assertTrue(file(key).exists())
        assertEquals(0,repo.cleanFiles(now+HighlightRuleFiles.RETENTION_MILLIS-1))
        assertEquals(1,repo.cleanFiles(now+HighlightRuleFiles.RETENTION_MILLIS)); assertFalse(file(key).exists())
    }
    @Test fun aCorruptOpenManagerPreventsCleanupUntilTheOwnedDraftIsClosed()=runBlocking {
        val key=payload(); files.retain(key,1); repo.open(id,owner,1)
        db.highlightRuleManagerDao.update(db.highlightRuleManagerDao.get(id)!!.copy(state="corrupt"))
        assertEquals(0,repo.cleanFiles(now)); assertTrue(file(key).exists())
        repo.close(id,owner)
        assertEquals(1,repo.cleanFiles(now)); assertFalse(file(key).exists())
    }
    @Test fun interruptedAtomicPayloadAndRetentionWritesAreCollectedAfterTheirGracePeriod()=runBlocking {
        root.mkdirs(); val key=UUID.randomUUID().toString()
        File(root,"$key.json.new").writeText("partial")
        File(root,"$key.retained.new").writeText("partial marker")
        assertEquals(0,repo.cleanFiles(now))
        assertEquals(1,repo.cleanFiles(now+HighlightRuleFiles.RETENTION_MILLIS))
        assertTrue(root.listFiles()!!.isEmpty())
    }
    @Test fun corruptAndFutureRetentionMarkersRestartTheGracePeriod()=runBlocking {
        val broken=payload(); val future=payload()
        File(root,"$broken.retained").writeText("broken")
        files.retain(future,now+1000)
        assertEquals(0,repo.cleanFiles(now))
        assertEquals(2,repo.cleanFiles(now+HighlightRuleFiles.RETENTION_MILLIS))
    }
    @Test fun aPreviousOwnerCannotReleaseOrAgeTheReplacementOwnersPayload()=runBlocking {
        val transfer=pending(); val replacement=UUID.randomUUID().toString()
        repo.open(id,replacement,2)
        assertTrue(runCatching { repo.clearTransfer(id,owner,2,transfer.id) }.isFailure)
        assertFalse(File(root,"${transfer.id}.retained").exists())
        assertEquals(0,repo.cleanFiles(now+HighlightRuleFiles.RETENTION_MILLIS*2))
        assertTrue(file(transfer.id).exists())
    }
    @Test fun closingAShareStartsRetentionAndRepeatedCleanupIsIdempotent()=runBlocking {
        val transfer=pending(); repo.close(id,owner)
        assertTrue(file(transfer.id).exists())
        val later=System.currentTimeMillis()+HighlightRuleFiles.RETENTION_MILLIS+1
        assertEquals(1,repo.cleanFiles(later)); assertEquals(0,repo.cleanFiles(later))
    }
    @Test fun unrelatedNamesAreNeverIncludedInPayloadCleanup()=runBlocking {
        root.mkdirs(); val unrelated=File(root,"keep.txt").apply { writeText("keep") }
        val odd=File(root,"${UUID.randomUUID()}.unrelated").apply { writeText("keep") }
        assertEquals(0,repo.cleanFiles(now+HighlightRuleFiles.RETENTION_MILLIS*2))
        assertEquals("keep",unrelated.readText()); assertEquals("keep",odd.readText())
    }
    @Test fun syntacticallyValidButIncompleteTransferMetadataProtectsAllPayloads()=runBlocking {
        val transfer=pending(); files.retain(transfer.id,1)
        val record=db.highlightRuleManagerDao.get(id)!!
        val json=GSON.fromJson(record.state,com.google.gson.JsonObject::class.java)
        json.getAsJsonObject("transfer").remove("id")
        db.highlightRuleManagerDao.update(record.copy(state=GSON.toJson(json)))
        assertEquals(0,repo.cleanFiles(now)); assertTrue(file(transfer.id).exists())
        assertTrue(runCatching { repo.open(id,owner,2) }.isFailure)
        repo.close(id,owner)
        assertEquals(1,repo.cleanFiles(now)); assertFalse(file(transfer.id).exists())
    }

}
