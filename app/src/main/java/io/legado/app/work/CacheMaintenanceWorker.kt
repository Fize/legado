package io.legado.app.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import io.legado.app.constant.AppLog
import io.legado.app.data.cache.BookCacheMaintenanceRepository
import io.legado.app.data.repository.HighlightRuleManagerRepository
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

@HiltWorker
class CacheMaintenanceWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted parameters: WorkerParameters,
    private val repository: BookCacheMaintenanceRepository,
    private val ruleManager: HighlightRuleManagerRepository,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        repository.clean()
        ruleManager.cleanFiles()
        Result.success()
    } catch (error: CancellationException) { throw error }
    catch (error: Exception) {
        AppLog.put("Book cache maintenance failed", error)
        if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()
    }

    companion object { const val MAX_RETRIES = 5 }
}

object CacheMaintenanceScheduler {
    const val STARTUP_NAME = "book-cache-maintenance-startup"
    const val PERIODIC_NAME = "book-cache-maintenance-periodic"
    fun enqueue(context: Context) {
        val manager = WorkManager.getInstance(context)
        // Cleanup frees storage and needs no network. StorageNotLow would prevent it when most useful.
        val constraints = Constraints.Builder().setRequiresBatteryNotLow(true).build()
        manager.enqueueUniqueWork(STARTUP_NAME, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<CacheMaintenanceWorker>().setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
        manager.enqueueUniquePeriodicWork(PERIODIC_NAME, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<CacheMaintenanceWorker>(24, TimeUnit.HOURS, 6, TimeUnit.HOURS)
                .setConstraints(constraints).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
    }
}
