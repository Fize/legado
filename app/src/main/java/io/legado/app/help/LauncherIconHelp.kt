package io.legado.app.help

import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import io.legado.app.R
import io.legado.app.utils.toastOnUi
import splitties.init.appCtx

/**
 * Created by GKF on 2018/2/27.
 * 更换图标
 */
object LauncherIconHelp {
    private val packageManager: PackageManager = appCtx.packageManager
    private val componentNames = arrayListOf(
        ComponentName(appCtx, "${appCtx.packageName}.ui.welcome.Launcher1"),
        ComponentName(appCtx, "${appCtx.packageName}.ui.welcome.Launcher2"),
        ComponentName(appCtx, "${appCtx.packageName}.ui.welcome.Launcher3"),
        ComponentName(appCtx, "${appCtx.packageName}.ui.welcome.Launcher4"),
        ComponentName(appCtx, "${appCtx.packageName}.ui.welcome.Launcher5"),
        ComponentName(appCtx, "${appCtx.packageName}.ui.welcome.Launcher6")
    )

    fun changeIcon(icon: String?) {
        if (icon.isNullOrEmpty()) return
        if (Build.VERSION.SDK_INT < 26) {
            appCtx.toastOnUi(R.string.change_icon_error)
            return
        }
        var hasEnabled = false
        componentNames.forEach {
            if (icon.equals(it.className.substringAfterLast("."), true)) {
                hasEnabled = true
                //启用
                packageManager.setComponentEnabledSetting(
                    it,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP
                )
            } else {
                //禁用
                packageManager.setComponentEnabledSetting(
                    it,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
                )
            }
        }
        if (hasEnabled) {
            packageManager.setComponentEnabledSetting(
                ComponentName(appCtx, "${appCtx.packageName}.ui.welcome.WelcomeActivity"),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
        } else {
            packageManager.setComponentEnabledSetting(
                ComponentName(appCtx, "${appCtx.packageName}.ui.welcome.WelcomeActivity"),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
        }
    }
}
