package io.legado.app.ui.compat

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import java.io.Closeable
import java.io.File
import java.util.UUID

/** Explicit opt-in: changes emulator display size and always restores prior overrides/settings. */
internal class OrientationWindowSession(private val name: String) : Closeable {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val output = File(instrumentation.targetContext.cacheDir, "orientation-compat/$name-${UUID.randomUUID()}")
    private val originalSize: String
    private val originalDensity: String
    private val rotation: Map<String, String>
    private val samples = JSONArray()

    init {
        assumeTrue("Host must explicitly opt in to display changes",
            InstrumentationRegistry.getArguments().getString("largeScreenOrientation") == "yes")
        assumeTrue("Requires API 36+ large-screen orientation rules", Build.VERSION.SDK_INT >= 36)
        originalSize = shell("wm size")
        originalDensity = shell("wm density")
        rotation = listOf("accelerometer_rotation", "user_rotation").associateWith { shell("settings get system $it").trim() }
        check(output.mkdirs())
        File(output, "original.json").writeText(JSONObject().put("size", originalSize)
            .put("density", originalDensity).put("rotation", JSONObject(rotation)).toString(2))
        shell("settings put system accelerometer_rotation 0")
        shell("settings put system user_rotation 0")
        shell("wm density 320")
        shell("wm size 2560x1600")
        SystemClock.sleep(700)
    }

    fun resize(widthDp: Int, heightDp: Int, activity: () -> Activity, verify: () -> JSONObject) {
        shell("wm size ${widthDp * 2}x${heightDp * 2}")
        val deadline = SystemClock.uptimeMillis() + 15_000
        var ready = false
        while (!ready && SystemClock.uptimeMillis() < deadline) {
            val host = activity()
            instrumentation.runOnMainSync {
                val config = host.resources.configuration
                val view = host.window.decorView
                ready = config.smallestScreenWidthDp >= 600 &&
                    (config.orientation == if (widthDp > heightDp) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT) &&
                    view.width > 0 && view.height > 0 &&
                    ((view.width > view.height) == (widthDp > heightDp))
            }
            if (!ready) SystemClock.sleep(100)
        }
        assertTrue("Actual app window did not adopt ${widthDp}x$heightDp dp", ready)
        instrumentation.waitForIdleSync()
        SystemClock.sleep(700)
        val state = verify()
        val entry = JSONObject().put("state", state)
        val host = activity()
        instrumentation.runOnMainSync {
            val c = host.resources.configuration; val view = host.window.decorView
            assertFalse(host.isFinishing || host.isDestroyed)
            entry.put("activity", host.javaClass.name).put("instance", System.identityHashCode(host))
                .put("widthDp", c.screenWidthDp).put("heightDp", c.screenHeightDp)
                .put("smallestWidthDp", c.smallestScreenWidthDp).put("orientation", c.orientation)
                .put("requestedOrientation", host.requestedOrientation)
                .put("windowWidthPx", view.width).put("windowHeightPx", view.height)
        }
        entry.put("requestedWindowWidthDp", widthDp).put("requestedWindowHeightDp", heightDp)
        samples.put(entry)
        File(output, "samples.json").writeText(samples.toString(2))
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(output, "sample-${samples.length()}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    override fun close() {
        try {
            shell("wm size " + Regex("Override size: (\\d+x\\d+)").find(originalSize)?.groupValues?.get(1).orEmpty().ifEmpty { "reset" })
        } finally {
            try {
                shell("wm density " + Regex("Override density: (\\d+)").find(originalDensity)?.groupValues?.get(1).orEmpty().ifEmpty { "reset" })
            } finally {
                rotation.forEach { (key, value) ->
                    shell(if (value == "null") "settings delete system $key" else "settings put system $key $value")
                }
            }
        }
        File(output, "restored.json").writeText(JSONObject().put("size", shell("wm size"))
            .put("density", shell("wm density")).put("rotation", JSONObject(rotation.keys.associateWith { shell("settings get system $it").trim() })).toString(2))
    }

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .bufferedReader().use { it.readText() }.also { check(!it.contains("Error:")) { "$command: $it" } }
}
