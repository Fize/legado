package io.legado.app.help.storage

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.AppDatabase
import io.legado.app.data.addLegadoMigrations
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class ExportRestoreBoundaryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).addLegadoMigrations().build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Before fun ready() { assertFalse(RestoreRecoveryGate.isQuarantined); assertFalse(RestoreFileRecovery.hasPending(context)) }
    @After fun cleanup() = runBlocking { scope.cancel(); scope.coroutineContext[Job]!!.join(); db.close() }

    @Test fun aSynchronousPublicationWaitsForOrdinaryContentionAndRunsExactlyOnce() = runBlocking {
        val entered = CountDownLatch(1); val calls = AtomicInteger(); var work: Deferred<Int>? = null
        backupRestoreMutex.recover {
            work = scope.async {
                val boundary = ExportRestoreBoundary(db, restoreWriteGeneration.capture(), currentCoroutineContext())
                entered.countDown(); boundary.synchronous { calls.incrementAndGet() }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS)); delay(100)
            assertFalse(work!!.isCompleted); assertEquals(0, calls.get())
        }
        assertEquals(1, withTimeout(5000) { work!!.await() }); assertEquals(1, calls.get())
    }

    @Test fun cancellationWhileWaitingCannotLaterPublish() = runBlocking {
        val entered = CountDownLatch(1); val calls = AtomicInteger(); var work: Deferred<Int>? = null
        backupRestoreMutex.recover {
            work = scope.async {
                val boundary = ExportRestoreBoundary(db, restoreWriteGeneration.capture(), currentCoroutineContext())
                entered.countDown(); boundary.synchronous { calls.incrementAndGet() }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS)); delay(100); work!!.cancel()
            withTimeout(5000) { work!!.join() }; assertEquals(0, calls.get())
        }
        assertEquals(0, calls.get()); assertTrue(work!!.isCancelled)
    }

    @Test fun roomOwnerChecksDoNotWaitAndPublicationUsesLockBeforeTransaction() = runBlocking {
        backupRestoreMutex.recover {
            withTimeout(5000) { withContext(Dispatchers.IO) {
                val boundary = ExportRestoreBoundary(db, restoreWriteGeneration.capture(), currentCoroutineContext())
                db.runInTransaction { boundary.checkCurrent() }
            } }
        }
        withContext(Dispatchers.IO) {
            val boundary = ExportRestoreBoundary(db, restoreWriteGeneration.capture(), currentCoroutineContext())
            boundary.synchronous { db.runInTransaction { boundary.checkCurrent() } }
        }
    }

    @Test fun aWriterFailureDoesNotCancelTheCallerOrLeaveAChildJobBehind() = runBlocking {
        withTimeout(5000) { scope.async {
            val boundary = ExportRestoreBoundary(db, restoreWriteGeneration.capture(), currentCoroutineContext())
            assertTrue(runCatching { boundary.synchronous { throw IOException("owned writer failure") } }.exceptionOrNull() is IOException)
            currentCoroutineContext().ensureActive()
            assertEquals(7, boundary.synchronous { 7 })
        }.await() }
    }
}
