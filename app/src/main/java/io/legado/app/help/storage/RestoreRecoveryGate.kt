package io.legado.app.help.storage

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import splitties.init.appCtx

internal class RestoreRecoveryRequiredException : IllegalStateException("恢复尚未完成，请先在应用中处理恢复记录。")

internal enum class RestoreRecoveryStatus { Ready, Required, Retrying, RetryFailed, ReopenRequired }

/** Process-wide recovery status; guarded entry points stay closed until the app is reopened. */
internal object RestoreRecoveryGate {
    private val mutableStatus = MutableStateFlow(RestoreRecoveryStatus.Ready)
    val status = mutableStatus.asStateFlow()
    val isQuarantined get() = status.value != RestoreRecoveryStatus.Ready
    private val recoveryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun requireRecovery() {
        mutableStatus.compareAndSet(RestoreRecoveryStatus.Ready, RestoreRecoveryStatus.Required)
    }

    /** Called only while starting a fresh Application, after its recovery attempt succeeded. */
    fun onStartupReady() { mutableStatus.value = RestoreRecoveryStatus.Ready }

    fun requireBusinessReady() {
        if (isQuarantined) throw RestoreRecoveryRequiredException()
        try {
            if (RestoreFileRecovery.hasPending(appCtx) && !RestoreFileRecovery.pendingIsResolved(appCtx)) {
                requireRecovery()
                throw RestoreRecoveryRequiredException()
            }
        } catch (failure: RestoreRecoveryRequiredException) { throw failure }
        catch (failure: Throwable) {
            requireRecovery()
            throw RestoreRecoveryRequiredException().apply { initCause(failure) }
        }
    }

    fun recordRestoreFailure(context: Context) {
        if (RestoreFileRecovery.hasPending(context)) {
            val resolved = runCatching { RestoreFileRecovery.pendingIsResolved(context) }.getOrDefault(false)
            if (!resolved) requireRecovery()
        }
    }

    fun retry(context: Context) {
        val previous = mutableStatus.value
        if (previous != RestoreRecoveryStatus.Required && previous != RestoreRecoveryStatus.RetryFailed) return
        if (!mutableStatus.compareAndSet(previous, RestoreRecoveryStatus.Retrying)) return
        recoveryScope.launch {
            try {
                backupRestoreMutex.recover { RestoreFileRecovery.recover(context.applicationContext, cleanup = true) }
                // Reload all process-local configuration from disk on the next launch.
                mutableStatus.value = RestoreRecoveryStatus.ReopenRequired
            } catch (failure: Throwable) {
                Log.e("RestoreRecovery", "Recovery remains pending", failure)
                mutableStatus.value = RestoreRecoveryStatus.RetryFailed
            }
        }
    }
}
