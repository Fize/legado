package io.legado.app.service

import android.content.Intent
import android.os.PowerManager
import java.util.concurrent.TimeUnit
import android.content.pm.ServiceInfo
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.NotificationId
import io.legado.app.data.AppDatabase
import io.legado.app.data.repository.BookTransferRepository
import io.legado.app.data.repository.BookExportJournal
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject

@AndroidEntryPoint
class BookExportForegroundService: LifecycleService() {
    @Inject lateinit var tasks: BookTransferRepository
    @Inject lateinit var runtime: BookTransferRuntime
    @Inject lateinit var db: AppDatabase
    @Inject lateinit var journal: BookExportJournal
    private var runner: Job?=null
    private var lastStart=0
    private val wakeLock by lazy { getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"$packageName:book-export").apply { setReferenceCounted(false) } }
    override fun onStartCommand(intent: Intent?,flags: Int,startId: Int): Int {
        super.onStartCommand(intent,flags,startId); lastStart=startId
        val recovery=intent?.getStringExtra("recoveryId")
        val id=recovery ?: intent?.getStringExtra("batchId")
        val dispatch=intent?.getStringExtra("dispatch")
        if(id==null || dispatch==null) { stopSelfResult(startId); return START_NOT_STICKY }
        if(runner?.isActive!=true) {
        ServiceCompat.startForeground(this,NotificationId.BookExportForeground,
            BookTransferNotifications.create(this,id,dispatch,getString(if(recovery!=null) R.string.cache_recovery_title else R.string.export_book),recovery=recovery!=null),ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        runner=lifecycleScope.launch {
            try {
                while(isActive) {
                    val observedStart=lastStart
                    val recovering=withContext(Dispatchers.IO) { journal.next() }
                    if(recovering!=null) {
                        ServiceCompat.startForeground(this@BookExportForegroundService,NotificationId.BookExportForeground,
                            BookTransferNotifications.create(this@BookExportForegroundService,recovering.id,recovering.dispatch,
                                getString(R.string.cache_recovery_title),recovery=true),ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                        wakeLock.acquire(TimeUnit.HOURS.toMillis(6))
                        try { journal.execute(recovering) }
                        catch(cancelled: CancellationException) { currentCoroutineContext().ensureActive() }
                        catch(error: Exception) { AppLog.put("Export recovery failed",error) }
                        finally { if(wakeLock.isHeld) wakeLock.release() }
                        continue
                    }
                    val batch=tasks.nextLocalBatch()
                    if(batch==null) {
                        if(observedStart==lastStart && stopSelfResult(observedStart)) break
                        continue
                    }
                    var progress: Job?=null
                    wakeLock.acquire(TimeUnit.HOURS.toMillis(6))
                    try {
                        runtime.execute(batch.id,batch.dispatch,false,{ false }) {
                            progress=launch {
                                db.bookTransferDao.batchProgress(batch.id).distinctUntilChanged().collect { value ->
                                    ServiceCompat.startForeground(this@BookExportForegroundService,NotificationId.BookExportForeground,
                                        BookTransferNotifications.create(this@BookExportForegroundService,batch.id,batch.dispatch,getString(R.string.export_book),
                                            value.progress.coerceIn(0,Int.MAX_VALUE.toLong()).toInt(),value.total.coerceIn(0,Int.MAX_VALUE.toLong()).toInt()),ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                                }
                            }
                        }
                    } catch(cancelled: CancellationException) { throw cancelled }
                    catch(error: Exception) { AppLog.put("Local export batch failed",error) }
                    finally { progress?.cancel(); if(wakeLock.isHeld) wakeLock.release() }
                }
            } finally { runner=null }
        }
        }
        return START_NOT_STICKY
    }
    override fun onTimeout(startId: Int,fgsType: Int) {
        runner?.cancel(); if(wakeLock.isHeld) wakeLock.release(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }
    override fun onDestroy() {
        runner?.cancel(); if(wakeLock.isHeld) wakeLock.release(); stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy()
    }
}
