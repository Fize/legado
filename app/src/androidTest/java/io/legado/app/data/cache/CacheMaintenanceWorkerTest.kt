package io.legado.app.data.cache

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dagger.hilt.android.EntryPointAccessors
import io.legado.app.data.entities.Book
import io.legado.app.utils.externalFiles
import io.legado.app.work.CacheMaintenanceScheduler
import io.legado.app.work.CacheMaintenanceWorker
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class CacheMaintenanceWorkerTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val manager = WorkManager.getInstance(context)
    private fun runWorker(name: String) {
        val request = OneTimeWorkRequestBuilder<CacheMaintenanceWorker>().build()
        manager.enqueueUniqueWork(name, ExistingWorkPolicy.KEEP, request).result.get(10, TimeUnit.SECONDS)
        val deadline = SystemClock.uptimeMillis() + 20_000
        var state: WorkInfo.State? = null
        do {
            state = manager.getWorkInfoById(request.id).get(10, TimeUnit.SECONDS)?.state
            if (state?.isFinished == true) break
            SystemClock.sleep(50)
        } while (SystemClock.uptimeMillis() < deadline)
        assertEquals("Real Hilt worker must complete successfully", WorkInfo.State.SUCCEEDED, state)
    }
    @Test fun theConfiguredHiltFactoryAndParserAdapterShareTheSameResourceLeases() {
        val id = UUID.randomUUID().toString()
        val book = Book(bookUrl = "worker://$id", name = "Worker fixture")
        val leases = EntryPointAccessors.fromApplication(context, BookCacheEntryPoint::class.java).cacheResourceLeases()
        val folder = File(context.externalFiles, "book_cache/${book.getFolderName()}")
        val first = "cache-test-protected-$id"; val second = "cache-test-orphan-$id"
        val lease = leases.acquireBook(book)
        try {
            folder.mkdirs(); val file = File(folder, "chapter.nb").apply { writeText("ALPHA") }
            runWorker(first); assertEquals("ALPHA", file.readText())
            lease.close(); runWorker(second); assertFalse(file.exists())
        } finally {
            lease.close(); manager.cancelUniqueWork(first); manager.cancelUniqueWork(second); folder.deleteRecursively()
        }
    }
    @Test fun schedulerKeepsOnePeriodicRequestAndDoesNotRequireNetworkOrFreeStorage() {
        CacheMaintenanceScheduler.enqueue(context)
        val initial = manager.getWorkInfosForUniqueWork(CacheMaintenanceScheduler.PERIODIC_NAME).get(10, TimeUnit.SECONDS)
        assertEquals(1, initial.size)
        CacheMaintenanceScheduler.enqueue(context)
        val again = manager.getWorkInfosForUniqueWork(CacheMaintenanceScheduler.PERIODIC_NAME).get(10, TimeUnit.SECONDS)
        assertEquals(initial.single().id, again.single().id)
        assertTrue(again.single().constraints.requiresBatteryNotLow())
        assertFalse(again.single().constraints.requiresStorageNotLow())
        assertEquals(NetworkType.NOT_REQUIRED, again.single().constraints.requiredNetworkType)
        assertTrue(manager.getWorkInfosForUniqueWork(CacheMaintenanceScheduler.STARTUP_NAME).get(10, TimeUnit.SECONDS)
            .count { !it.state.isFinished } <= 1)
    }
    @Test fun theRealWorkerRemovesExpiredRuleSharesAndKeepsPendingPayloads()=kotlinx.coroutines.runBlocking {
        val files=io.legado.app.data.repository.HighlightRuleFiles(context)
        val repo=io.legado.app.data.repository.HighlightRuleManagerRepository(io.legado.app.data.appDb,files)
        val id=UUID.randomUUID().toString(); val owner=UUID.randomUUID().toString()
        val completed=UUID.randomUUID().toString(); val pending=UUID.randomUUID().toString()
        val work="rule-file-maintenance-${UUID.randomUUID()}"
        try {
            val empty=io.legado.app.data.entities.HighlightRuleFile(type=io.legado.app.data.entities.HighlightRuleFile.TYPE,rules=emptyList())
            files.prepare(completed,empty); files.retain(completed,1)
            val digest=files.prepare(pending,empty); files.retain(pending,1)
            repo.open(id,owner,io.legado.app.data.repository.HighlightManagerOwnership.next())
            repo.persist(id,owner,1,io.legado.app.data.repository.HighlightRuleManagerDraft(
                transfer=io.legado.app.data.repository.HighlightRuleTransfer(pending,"export",digest)))
            runWorker(work)
            assertFalse(File(context.filesDir,"highlight-rule-transfers/$completed.json").exists())
            assertTrue(File(context.filesDir,"highlight-rule-transfers/$pending.json").exists())
        } finally {
            manager.cancelUniqueWork(work)
            io.legado.app.data.appDb.highlightRuleManagerDao.delete(id)
            files.remove(completed); files.remove(pending)
        }
    }

}
