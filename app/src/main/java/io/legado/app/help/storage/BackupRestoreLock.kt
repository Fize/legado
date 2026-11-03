package io.legado.app.help.storage

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes backup/restore and rejects snapshots of an unresolved restore. */
internal class BackupRestoreLock {
    @PublishedApi internal val mutex = Mutex()
    val isLocked get() = mutex.isLocked

    suspend inline fun <T> withLock(owner: Any? = null, action: () -> T): T {
        lock(owner)
        try { return action() } finally { unlock(owner) }
    }

    suspend fun lock(owner: Any? = null) {
        mutex.lock(owner)
        try { RestoreRecoveryGate.requireBusinessReady() }
        catch (failure: Throwable) { mutex.unlock(owner); throw failure }
    }

    fun tryLock(owner: Any? = null): Boolean {
        if (!mutex.tryLock(owner)) return false
        return try { RestoreRecoveryGate.requireBusinessReady(); true }
        catch (_: RestoreRecoveryRequiredException) { mutex.unlock(owner); false }
        catch (failure: Throwable) { mutex.unlock(owner); throw failure }
    }

    fun unlock(owner: Any? = null) = mutex.unlock(owner)

    /** Recovery must be able to repair the very state that ordinary operations reject. */
    suspend fun <T> recover(action: suspend () -> T): T = mutex.withLock { action() }
}

internal val backupRestoreMutex = BackupRestoreLock()
