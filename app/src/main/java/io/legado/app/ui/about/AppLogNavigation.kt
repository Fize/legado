package io.legado.app.ui.about

import android.content.Context
import io.legado.app.ui.AppActivity
import io.legado.app.ui.navigation.AppLogRoute

fun Context.openAppLogs() {
    startActivity(AppActivity.startIntent(this, AppLogRoute))
}
