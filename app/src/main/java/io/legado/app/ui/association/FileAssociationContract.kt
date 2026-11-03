package io.legado.app.ui.association

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import io.legado.app.data.repository.FileAssociationAction
import io.legado.app.data.repository.FileAssociationRequest

/** The exported import alias keeps the system share target separate from text search. */
object FileAssociationContract {
    const val ACTIVITY_ALIAS = "io.legado.app.ui.association.FileAssociationActivity"

    fun component(context: Context) = ComponentName(context, ACTIVITY_ALIAS)

    fun accepts(intent: Intent, restoringRequest: Boolean = false): Boolean {
        // Recents can retain a consumed file/share intent after a package update.
        // Resume only an explicitly saved request, rather than importing it again.
        if (!restoringRequest && intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return false
        if (intent.component?.className?.endsWith(".receiver.SharedReceiverActivity") == true) return false
        return when (intent.action) {
            Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE -> true
            Intent.ACTION_VIEW -> intent.component?.className == ACTIVITY_ALIAS ||
                intent.data?.scheme in setOf("content", "file", "app")
            else -> false
        }
    }

    fun read(intent: Intent): FileAssociationRequest {
        val streams = when (intent.action) {
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(
                intent, Intent.EXTRA_STREAM, Uri::class.java,
            ).orEmpty()
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(
                intent, Intent.EXTRA_STREAM, Uri::class.java,
            ))
            else -> emptyList()
        }
        return FileAssociationRequest(
            action = when (intent.action) {
                Intent.ACTION_VIEW -> FileAssociationAction.View
                Intent.ACTION_SEND -> FileAssociationAction.Send
                Intent.ACTION_SEND_MULTIPLE -> FileAssociationAction.SendMultiple
                else -> FileAssociationAction.Unsupported
            },
            mimeType = intent.type,
            sourceUri = intent.data?.toString(),
            streams = streams.map(Uri::toString),
            text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString(),
        )
    }
}
