package io.legado.app.work

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import io.legado.app.R
import io.legado.app.data.AppDatabase
import io.legado.app.constant.AppLog
import io.legado.app.service.BookTransferNotifications
import io.legado.app.service.BookTransferRuntime
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged

@HiltWorker
class BookDownloadWorker @AssistedInject constructor(@Assisted context: Context,@Assisted parameters: WorkerParameters,
    private val runtime: BookTransferRuntime,private val db: AppDatabase): CoroutineWorker(context,parameters) {
    override suspend fun doWork(): Result {
        val id=inputData.getString("batchId") ?: return Result.failure()
        val dispatch=inputData.getString("dispatch") ?: return Result.failure()
        return try {
            suspend fun progress(current: Int=0,total: Int=0) {
                setForeground(ForegroundInfo((id.hashCode() and Int.MAX_VALUE).coerceAtLeast(1),
                    BookTransferNotifications.create(applicationContext,id,dispatch,applicationContext.getString(R.string.cache_download_title),current,total),ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC))
            }
            progress()
            coroutineScope {
                var updates: Job?=null
                try {
                    runtime.execute(id,dispatch,false,{ isStopped }) { batch ->
                        updates=launch {
                            db.bookTransferDao.batchProgress(batch.id).distinctUntilChanged().collect { value ->
                                progress(value.progress.coerceIn(0,Int.MAX_VALUE.toLong()).toInt(),value.total.coerceIn(0,Int.MAX_VALUE.toLong()).toInt())
                            }
                        }
                    }
                } finally { updates?.cancel() }
            }
            Result.success()
        } catch(cancelled: CancellationException) { throw cancelled }
        catch(error: Exception) { AppLog.put("Book download worker failed",error); Result.failure() }
    }
}
