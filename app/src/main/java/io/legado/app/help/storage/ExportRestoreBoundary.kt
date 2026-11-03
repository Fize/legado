package io.legado.app.help.storage

import io.legado.app.data.AppDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.SupervisorJob
import kotlin.coroutines.CoroutineContext

/** Adapts the retained engine's synchronous publication callbacks, which run on IO. */
internal class ExportRestoreBoundary(
    private val db: AppDatabase,
    private val generation: RestoreWriteGeneration.Token,
    private val execution: CoroutineContext,
) {
    // The engine also invokes this from a Room transaction. Never wait for the
    // restore mutex here: restore acquires that mutex before opening its transaction.
    fun checkCurrent() {
        execution.ensureActive()
        generation.requireCurrent()
        if (RestoreRecoveryGate.isQuarantined) throw cancelled(RestoreRecoveryRequiredException())
    }

    suspend fun <T> mutate(action: suspend () -> T): T {
        try {
            return backupRestoreMutex.withLock {
                checkCurrent()
                action()
            }
        } catch (required: RestoreRecoveryRequiredException) {
            throw cancelled(required)
        }
    }

    fun <T> synchronous(action: () -> T): T = synchronous(execution, action)

    fun <T> synchronous(caller: CoroutineContext, action: () -> T): T {
        check(!db.inTransaction()) { "Export publication must acquire the restore lock before its database transaction" }
        caller.ensureActive()
        val parent = SupervisorJob(caller[Job])
        // Only the short progress/refresh/replacement callback is bridged. Rendering
        // and network waits remain outside the mutex; parent cancellation aborts a waiter.
        return try { runBlocking(parent) { mutate { caller.ensureActive(); action() } } }
        finally { parent.complete() }
    }

    private fun cancelled(required: RestoreRecoveryRequiredException) =
        CancellationException("Export stopped for data recovery").apply { initCause(required) }
}
