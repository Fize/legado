package io.legado.app.ui.qrcode

import android.app.Activity.RESULT_OK
import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract
import io.legado.app.ui.AppActivity
import io.legado.app.ui.navigation.QrCodeRoute

class QrCodeResult : ActivityResultContract<Unit?, String?>() {

    override fun createIntent(context: Context, input: Unit?): Intent {
        return AppActivity.startIntent(context, QrCodeRoute)
    }

    override fun parseResult(resultCode: Int, intent: Intent?): String? {
        if (resultCode == RESULT_OK) {
            intent?.getStringExtra("result")?.let {
                return it
            }
        }
        return null
    }

}
