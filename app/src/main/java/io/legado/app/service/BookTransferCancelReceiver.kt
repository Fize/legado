package io.legado.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import io.legado.app.constant.AppLog
import io.legado.app.data.repository.BookExportJournal
import io.legado.app.data.repository.ExportRecoveryCommand

@EntryPoint @InstallIn(SingletonComponent::class)
interface BookTransferEntryPoint {
    fun transferRuntime(): BookTransferRuntime
    fun transferScheduler(): BookTransferScheduler
    fun downloadCommands(): BookDownloadCommands
    fun exportJournal(): BookExportJournal
}
class BookTransferCancelReceiver: BroadcastReceiver() {
    override fun onReceive(context: Context,intent: Intent) {
        val recovery=intent.getStringExtra("recoveryId")
        val id=recovery ?: intent.getStringExtra("batchId") ?: return
        val dispatch=intent.getStringExtra("dispatch") ?: return
        val component=EntryPointAccessors.fromApplication(context.applicationContext,BookTransferEntryPoint::class.java)
        val pending=goAsync()
        component.transferRuntime().cleanupScope.launch {
            try { if(recovery!=null) component.exportJournal().cancel(ExportRecoveryCommand(id,dispatch))
                else component.transferScheduler().cancelBatch(id,dispatch) }
            catch(cancelled: CancellationException) { throw cancelled }
            catch(error: Exception) { AppLog.put("Book transfer cancellation failed",error) }
            finally { pending.finish() }
        }
    }
}
