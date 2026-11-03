package io.legado.app.ui.test

import android.app.UiAutomation
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/** Capture actual display pixels on API 26, where UiAutomation can scale/crop the image. */
fun displayScreenshotBackend(): String =
    if (Build.VERSION.SDK_INT == 26) "shell-screencap" else "uiautomation"

fun captureDisplayScreenshot(automation: UiAutomation, compare: Boolean = false): Bitmap {
    if (Build.VERSION.SDK_INT != 26) return checkNotNull(automation.takeScreenshot())
    val comparison = compare && InstrumentationRegistry.getArguments()
        .getString("compatScreenshotComparison") == "true"
    val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
    if (comparison) {
        val legacy = checkNotNull(automation.takeScreenshot())
        try {
            File(cache, "compat-display-legacy.png").outputStream().use {
                check(legacy.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { legacy.recycle() }
    }
    val image = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("screencap -p"))
        .use { checkNotNull(BitmapFactory.decodeStream(it)) { "System screencap did not return an image" } }
    if (comparison) {
        try {
            File(cache, "compat-display-native.png").outputStream().use {
                check(image.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } catch (error: Throwable) { image.recycle(); throw error }
    }
    return image
}
