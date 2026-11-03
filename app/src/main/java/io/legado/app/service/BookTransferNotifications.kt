package io.legado.app.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import io.legado.app.R
import io.legado.app.constant.AppConst
import io.legado.app.ui.AppActivity
import io.legado.app.ui.navigation.CacheRoute

internal object BookTransferNotifications {
    fun create(context: Context,batchId: String,dispatch: String,title: String,progress: Int=0,total: Int=0,recovery: Boolean=false): Notification {
        val open=AppActivity.startIntent(context,CacheRoute()).setAction("book-transfer:$batchId")
        val flags=PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val cancel=Intent(context,BookTransferCancelReceiver::class.java).setAction("book-transfer-cancel:$batchId:$dispatch")
            .putExtra(if(recovery) "recoveryId" else "batchId",batchId).putExtra("dispatch",dispatch)
        return NotificationCompat.Builder(context,AppConst.channelIdDownload)
            .setSmallIcon(R.drawable.ic_download).setContentTitle(title).setOnlyAlertOnce(true).setOngoing(true)
            .setContentIntent(PendingIntent.getActivity(context,batchId.hashCode(),open,flags))
            .setProgress(total.coerceAtLeast(0),progress.coerceAtLeast(0),total<=0)
            .addAction(R.drawable.ic_stop_black_24dp,context.getString(R.string.cancel),
                PendingIntent.getBroadcast(context,batchId.hashCode(),cancel,flags))
            .build()
    }
}
