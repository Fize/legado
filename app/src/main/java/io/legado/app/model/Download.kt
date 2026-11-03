package io.legado.app.model

import android.content.Context
import io.legado.app.constant.IntentAction
import io.legado.app.service.DownloadService
import io.legado.app.help.storage.RestoreRecoveryGate
import io.legado.app.help.storage.restoreWriteGeneration
import io.legado.app.utils.startService

object Download {
    internal const val EXTRA_RESTORE_SEQUENCE = "downloadRestoreSequence"
    internal const val EXTRA_RESTORE_PROCESS = "downloadRestoreProcess"

    fun start(context: Context, url: String, fileName: String, isAppUpdate: Boolean = false) {
        if (RestoreRecoveryGate.isQuarantined) return
        val generation = restoreWriteGeneration.capture()
        context.startService<DownloadService> {
            action = IntentAction.start
            putExtra("url", url)
            putExtra("fileName", fileName)
            putExtra("isAppUpdate", isAppUpdate)
            putExtra(EXTRA_RESTORE_SEQUENCE, generation.sequence)
            putExtra(EXTRA_RESTORE_PROCESS, generation.processId)
        }
    }

}
