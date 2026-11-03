package io.legado.app.service

import io.legado.app.data.AppDatabase
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.BookTransferBatch
import io.legado.app.data.repository.BookTransferRepository
import io.legado.app.help.storage.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Serializes callbacks for one OS dispatch without serializing unrelated user commands. */
@Singleton
class BookTransferRuntime @Inject constructor(private val tasks: BookTransferRepository,private val db: AppDatabase,
    private val downloads: BookDownloadTaskRunner,private val exports: BookExportTaskRunner) {
    private val locks=ConcurrentHashMap<String,Mutex>()
    val cleanupScope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    suspend fun execute(id: String,dispatch: String,allowProcessResume: Boolean,
        resumeAfterStop: () -> Boolean,onClaim: (BookTransferBatch) -> Unit = {}) {
        val generation = restoreWriteGeneration.capture()
        withContext(Dispatchers.IO) {
        locks.computeIfAbsent(id) { Mutex() }.withLock {
            val batch=transferMutation(generation) { tasks.claimBatch(id,dispatch,allowSystemDownloadResume=true,allowProcessResume=allowProcessResume) } ?: return@withLock
            var finished=false
            try {
                onClaim(batch)
                while(currentCoroutineContext().isActive) {
                    val task=transferMutation(generation) { tasks.claimNext(batch) } ?: break
                    if(task.phase!="running") continue
                    try {
                        supervisorScope {
                            val work=async { if(task.kind=="download") downloads.run(task,generation) else exports.run(task,generation) }
                            val cancellation=launch {
                                db.bookTransferDao.execution(task.id).collect { value ->
                                    if(value==null || value.phase in listOf("cancelled","interrupted") || value.phase=="running" && value.owner!=task.owner) work.cancel()
                                }
                            }
                            try { work.await() } finally { cancellation.cancel() }
                        }
                    } catch(cancelled: CancellationException) { currentCoroutineContext().ensureActive() }
                    catch(error: Exception) { AppLog.put("Book transfer failed",error) }
                }
                currentCoroutineContext().ensureActive()
                transferMutation(generation) { tasks.finishBatch(batch) }; finished=true
            } finally {
                if(!finished) withContext(NonCancellable) {
                    try {
                        backupRestoreMutex.withLock {
                            // Successful restore retired this owner. After rollback, stop
                            // a still-owned obsolete execution without requeuing old work.
                            tasks.stopBatch(batch, generation.isCurrent && resumeAfterStop())
                        }
                    } catch (_: RestoreRecoveryRequiredException) {
                        // Do not write task metadata while recovery remains unresolved.
                    }
                }
            }
        }
        }
    }

    private suspend inline fun <T> transferMutation(generation: RestoreWriteGeneration.Token, action: () -> T): T {
        try {
            return backupRestoreMutex.withLock {
                currentCoroutineContext().ensureActive()
                generation.requireCurrent()
                action()
            }
        } catch (required: RestoreRecoveryRequiredException) {
            throw CancellationException("Transfer batch stopped for data recovery").apply { initCause(required) }
        }
    }
}
