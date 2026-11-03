package io.legado.app.ui.book.manga

import android.app.Activity
import android.os.Build
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.*
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect

/** Only the resumed destination owns these window attributes, and returns each original value on pause. */
@Composable
internal fun MangaReaderWindowEffects(activity: Activity, menuVisible: Boolean, brightness: Int?) {
    var owner by remember(activity) { mutableStateOf<ReaderWindowOwner?>(null) }
    val menu by rememberUpdatedState(menuVisible)
    val level by rememberUpdatedState(brightness)
    LifecycleResumeEffect(activity) {
        val lease = ReaderWindowOwner(activity.window)
        owner = lease
        lease.apply(menu, level)
        onPauseOrDispose { lease.restore(); owner = null }
    }
    SideEffect { owner?.apply(menuVisible, brightness) }
}

private class ReaderWindowOwner(private val window: Window) {
    private val controller = WindowCompat.getInsetsController(window, window.decorView)
    private val insets = ViewCompat.getRootWindowInsets(window.decorView)
    private val statusVisible = insets?.isVisible(WindowInsetsCompat.Type.statusBars()) ?: true
    private val navigationVisible = insets?.isVisible(WindowInsetsCompat.Type.navigationBars()) ?: true
    private val captionVisible = insets?.isVisible(WindowInsetsCompat.Type.captionBar()) ?: false
    private val behavior = controller.systemBarsBehavior
    private val brightness = window.attributes.screenBrightness
    private val keepOn = window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0
    private val cutout = if (Build.VERSION.SDK_INT >= 28) window.attributes.layoutInDisplayCutoutMode else 0

    fun apply(showBars: Boolean, level: Int?) {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply {
            screenBrightness = level?.coerceIn(0, 255)?.div(255f) ?: brightness
            if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (showBars) controller.show(WindowInsetsCompat.Type.systemBars()) else controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    fun restore() {
        if (keepOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply {
            screenBrightness = brightness
            if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = if (
                Build.VERSION.SDK_INT >= 30 && cutout == WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            ) WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS else when (cutout) {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES ->
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER ->
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER
                else -> WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
            }
        }
        controller.systemBarsBehavior = behavior
        if (statusVisible) controller.show(WindowInsetsCompat.Type.statusBars()) else controller.hide(WindowInsetsCompat.Type.statusBars())
        if (navigationVisible) controller.show(WindowInsetsCompat.Type.navigationBars()) else controller.hide(WindowInsetsCompat.Type.navigationBars())
        if (captionVisible) controller.show(WindowInsetsCompat.Type.captionBar()) else controller.hide(WindowInsetsCompat.Type.captionBar())
    }
}
