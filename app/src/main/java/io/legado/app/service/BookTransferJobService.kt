package io.legado.app.service

import io.legado.app.help.storage.RestoreRecoveryGate
import android.os.Handler
import android.os.Looper
import android.app.job.JobParameters
import android.app.job.JobService
import android.os.Build
import dagger.hilt.android.AndroidEntryPoint
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.BookTransferBatch
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

@AndroidEntryPoint
class BookTransferJobService: JobService() {
    @Inject lateinit var runtime: BookTransferRuntime
    @Inject lateinit var db: AppDatabase
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private data class Running(val job: Job,val stopped: AtomicBoolean)
    private val running=mutableMapOf<String,Running>()
    override fun onStartJob(params: JobParameters): Boolean {
        if (RestoreRecoveryGate.isQuarantined) {
            Handler(Looper.getMainLooper()).post { jobFinished(params, true) }
            return true
        }
        if(Build.VERSION.SDK_INT<34) return false
        val id=params.extras.getString("batchId") ?: return false
        val dispatch=params.extras.getString("dispatch") ?: return false
        if(running.containsKey(dispatch)) return true
        val notificationId=params.jobId
        setNotification(params,notificationId,BookTransferNotifications.create(this,id,dispatch,getString(R.string.cache_export)),JOB_END_NOTIFICATION_POLICY_REMOVE)
        val stopped=AtomicBoolean(false)
        val job=scope.launch(start=CoroutineStart.LAZY) {
            try {
                coroutineScope {
                    var updates: Job?=null
                    try {
                        runtime.execute(id,dispatch,true,stopped::get) { batch ->
                            updates=launch(Dispatchers.Main) { observeProgress(params,batch) }
                        }
                    } finally { updates?.cancel() }
                }
            } catch(cancelled: CancellationException) { throw cancelled }
            catch(error: Exception) { AppLog.put("User transfer job failed",error) }
            finally {
                val owned=running[dispatch]
                if(owned?.stopped===stopped) {
                    running.remove(dispatch)
                    if(!stopped.get()) jobFinished(params,false)
                }
            }
        }
        running[dispatch]=Running(job,stopped); job.start(); return true
    }
    private suspend fun observeProgress(params: JobParameters,batch: BookTransferBatch) {
        if(Build.VERSION.SDK_INT<34) return
        db.bookTransferDao.batchProgress(batch.id).distinctUntilChanged().collect { value ->
            setNotification(params,params.jobId,BookTransferNotifications.create(this,batch.id,batch.dispatch,
                getString(if(batch.kind=="download") R.string.cache_download_title else R.string.export_book),
                value.progress.coerceIn(0,Int.MAX_VALUE.toLong()).toInt(),value.total.coerceIn(0,Int.MAX_VALUE.toLong()).toInt()),JOB_END_NOTIFICATION_POLICY_REMOVE)
        }
    }

    override fun onStopJob(params: JobParameters): Boolean {
        val dispatch=params.extras.getString("dispatch") ?: return false
        running.remove(dispatch)?.let { it.stopped.set(true); it.job.cancel() }
        return params.extras.getString("kind")=="download"
    }
    override fun onDestroy() {
        running.values.forEach { it.stopped.set(true); it.job.cancel() }; running.clear(); scope.cancel()
        super.onDestroy()
    }
}
