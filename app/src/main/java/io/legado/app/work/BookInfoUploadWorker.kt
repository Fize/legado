package io.legado.app.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.*
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import io.legado.app.data.entities.BookInfoUploadStatus
import io.legado.app.data.repository.BookInfoUploadRepository

@HiltWorker
class BookInfoUploadWorker @AssistedInject constructor(
    @Assisted context: Context, @Assisted parameters: WorkerParameters,
    private val repository: BookInfoUploadRepository,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val id = inputData.getString("commandId") ?: return Result.failure()
        val command = repository.execute(id, retryNetworkFailure = runAttemptCount < 3) ?: return Result.failure()
        return when (command.status) {
            BookInfoUploadStatus.Failed -> Result.failure()
            BookInfoUploadStatus.Queued -> Result.retry()
            else -> Result.success()
        }
    }
}

@HiltWorker
class BookInfoUploadRecoveryWorker @AssistedInject constructor(
    @Assisted context: Context, @Assisted parameters: WorkerParameters,
    private val repository: BookInfoUploadRepository,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        repository.pending().forEach { BookInfoUploadScheduler.enqueue(applicationContext, it.id) }
        return Result.success()
    }
}

object BookInfoUploadScheduler {
    fun enqueue(context: Context, id: String, replace: Boolean = false) {
        WorkManager.getInstance(context).enqueueUniqueWork("book-info-upload-$id",
            if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<BookInfoUploadWorker>().setInputData(workDataOf("commandId" to id))
                .setConstraints(bookInfoRemoteConstraints())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, java.util.concurrent.TimeUnit.SECONDS).build())
    }
    fun cancel(context: Context, id: String) { WorkManager.getInstance(context).cancelUniqueWork("book-info-upload-$id") }
    fun recover(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork("book-info-upload-recovery", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<BookInfoUploadRecoveryWorker>().build())
    }
}
