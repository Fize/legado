package io.legado.app.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.*
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import io.legado.app.data.entities.BookInfoDeleteStatus
import io.legado.app.data.repository.BookInfoDeleteRepository

@HiltWorker
class BookInfoDeleteWorker @AssistedInject constructor(
    @Assisted context: Context, @Assisted parameters: WorkerParameters,
    private val repository: BookInfoDeleteRepository,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val id = inputData.getString("commandId") ?: return Result.failure()
        val command = repository.execute(id, retryRemoteFailure = runAttemptCount < 3) ?: return Result.failure()
        return when (command.status) {
            BookInfoDeleteStatus.Failed -> Result.failure()
            BookInfoDeleteStatus.Queued -> Result.retry()
            else -> Result.success()
        }
    }
}

/** Repairs the Room commit / WorkManager enqueue gap after process death. */
@HiltWorker
class BookInfoDeleteRecoveryWorker @AssistedInject constructor(
    @Assisted context: Context, @Assisted parameters: WorkerParameters,
    private val repository: BookInfoDeleteRepository,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        repository.pending().forEach { BookInfoDeleteScheduler.enqueue(applicationContext, it.id, remote = it.deleteRemote) }
        return Result.success()
    }
}

object BookInfoDeleteScheduler {
    fun enqueue(context: Context, id: String, replace: Boolean = false, remote: Boolean = false) {
        WorkManager.getInstance(context).enqueueUniqueWork("book-info-delete-$id",
            if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<BookInfoDeleteWorker>().setInputData(workDataOf("commandId" to id))
                .setConstraints(if (remote) bookInfoRemoteConstraints() else Constraints.NONE)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, java.util.concurrent.TimeUnit.SECONDS).build())
    }
    fun cancel(context: Context, id: String) { WorkManager.getInstance(context).cancelUniqueWork("book-info-delete-$id") }
    fun recover(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork("book-info-delete-recovery", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<BookInfoDeleteRecoveryWorker>().build())
    }
}
