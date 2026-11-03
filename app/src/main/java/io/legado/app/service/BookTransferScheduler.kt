package io.legado.app.service

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import androidx.core.content.ContextCompat
import androidx.work.*
import dagger.hilt.android.qualifiers.ApplicationContext
import io.legado.app.data.entities.BookTransferBatch
import io.legado.app.data.repository.BookTransferRepository
import io.legado.app.data.repository.BookExportJournal
import io.legado.app.data.repository.ExportRecoveryCommand
import io.legado.app.work.BookDownloadWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BookTransferScheduler @Inject constructor(@ApplicationContext private val context: Context,private val tasks: BookTransferRepository,
    private val journal: BookExportJournal) {
    private val schedulingLock=Any()
    suspend fun schedule(ids: List<String>)=withContext(Dispatchers.IO) {
        tasks.dispatchCandidates(ids).forEach { batch ->
            if(batch.phase=="running") return@forEach
            if(batch.kind=="local") {
                tasks.scheduled(batch.id,batch.dispatch)
                ContextCompat.startForegroundService(context,Intent(context,BookExportForegroundService::class.java).putExtra("batchId",batch.id).putExtra("dispatch",batch.dispatch))
            } else if(Build.VERSION.SDK_INT>=34) {
                val scheduler=context.getSystemService(JobScheduler::class.java).forNamespace(NAMESPACE)
                synchronized(schedulingLock) {
                    val existing=scheduler.allPendingJobs
                    if(existing.none { it.extras.getString("batchId")==batch.id && it.extras.getString("dispatch")==batch.dispatch }) {
                        var jobId=batch.id.hashCode() and Int.MAX_VALUE
                        while(existing.any { it.id==jobId }) jobId=if(jobId==Int.MAX_VALUE) 0 else jobId+1
                        val extras=PersistableBundle().apply { putString("batchId",batch.id); putString("dispatch",batch.dispatch); putString("kind",batch.kind) }
                        val job=JobInfo.Builder(jobId,ComponentName(context,BookTransferJobService::class.java))
                            .setUserInitiated(true).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setExtras(extras).build()
                        check(scheduler.schedule(job)==JobScheduler.RESULT_SUCCESS) { "Unable to schedule user transfer" }
                    }
                }
                tasks.scheduled(batch.id,batch.dispatch)
            } else {
                tasks.scheduled(batch.id,batch.dispatch)
                WorkManager.getInstance(context).enqueueUniqueWork(workName(batch.id),ExistingWorkPolicy.KEEP,
                    OneTimeWorkRequestBuilder<BookDownloadWorker>().setInputData(workDataOf("batchId" to batch.id,"dispatch" to batch.dispatch))
                        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build()).result.get()
            }
        }
    }
    suspend fun webDavKey(): String=withContext(Dispatchers.IO) { BookExportWebDavTarget.capture(context).key }
    internal suspend fun scheduleRecovery(ref: ExportRecoveryCommand)=withContext(Dispatchers.IO) {
        check(journal.pending(ref)) { "Recovery request changed" }
        ContextCompat.startForegroundService(context,Intent(context,BookExportForegroundService::class.java)
            .putExtra("recoveryId",ref.id).putExtra("dispatch",ref.dispatch))
        Unit
    }
    suspend fun cancelTasks(ids: List<String>) { tasks.cancelIds(ids).forEach { cancelBatch(it.id,it.dispatch) } }
    suspend fun cancelBatch(id: String,dispatch: String)=withContext(Dispatchers.IO) {
        tasks.cancelBatch(id,dispatch)
        if(Build.VERSION.SDK_INT>=34) {
            val scheduler=context.getSystemService(JobScheduler::class.java).forNamespace(NAMESPACE)
            scheduler.allPendingJobs.filter { it.extras.getString("batchId")==id && it.extras.getString("dispatch")==dispatch }.forEach { scheduler.cancel(it.id) }
        }
        WorkManager.getInstance(context).cancelUniqueWork(workName(id))
    }
    companion object { const val NAMESPACE="legado.book-transfer"; fun workName(id: String)="book-transfer-$id" }
}
