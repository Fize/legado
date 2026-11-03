package io.legado.app.help.storage

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters

/** Do not construct business workers or discard their queued work while recovery is blocked. */
internal class RestoreRecoveryWorkerFactory(private val delegate: WorkerFactory) : WorkerFactory() {
    override fun createWorker(context: Context, workerClassName: String, parameters: WorkerParameters): ListenableWorker? {
        if (RestoreRecoveryGate.isQuarantined) return object : CoroutineWorker(context, parameters) {
            override suspend fun doWork() = Result.retry()
        }
        return delegate.createWorker(context, workerClassName, parameters)
    }
}
