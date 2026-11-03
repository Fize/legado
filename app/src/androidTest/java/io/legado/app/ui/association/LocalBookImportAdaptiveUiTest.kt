package io.legado.app.ui.association

import android.content.ClipData
import android.content.Intent
import android.graphics.Rect
import android.graphics.Point
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.WindowManager
import android.view.Choreographer
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.navigation3.runtime.NavEntry
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.help.book.BookHelp
import io.legado.app.ui.AppActivity
import io.legado.app.ui.navigation.BookshelfRoute
import io.legado.app.ui.navigation.FileAssociationRoute
import io.legado.app.ui.navigation.navigationViewModelStores
import io.legado.app.utils.defaultSharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real share/import with system font scale and orientation, not a synthetic dialog container. */
@RunWith(AndroidJUnit4::class)
class LocalBookImportAdaptiveUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val automation = instrumentation.uiAutomation
    private val scale = InstrumentationRegistry.getArguments().getString("compatFontScale", "1.0")!!
        .also { require(it in listOf("1.0", "1.3", "2.0")) }
    private val prefs = context.defaultSharedPreferences
    private val oldPrefs = listOf(PreferKey.defaultBookTreeUri, PreferKey.autoBackup, PreferKey.language).associateWith { prefs.all[it] }
    private val oldFont = setting("font_scale")
    private val oldEffectiveFont = oldFont.toFloatOrNull() ?: 1f
    private val oldAutoRotation = setting("accelerometer_rotation")
    private val oldRotation = setting("user_rotation")
    private val id = UUID.randomUUID().toString()
    private val directory = File(context.cacheDir, "adaptive-local-import-$id")
    private val destination = File(directory, "copied")
    private lateinit var files: List<File>
    private lateinit var originalBytes: List<ByteArray>
    private var scenario: ActivityScenario<AppActivity>? = null
    private var activity: AppActivity? = null
    private var orientation = "not-opened"
    private var desiredRotation = android.app.UiAutomation.ROTATION_FREEZE_0
    private val trace = JSONArray()
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))
        .bufferedReader().use { it.readText() }
    private fun setting(key: String) = shell("settings get system $key").trim()
    private fun restore(key: String, value: String) {
        if (value == "null") shell("settings delete system $key") else {
            require(value.matches(Regex("[0-9.]+")))
            shell("settings put system $key $value")
        }
    }
    private fun model(): FileAssociationViewModel {
        val host = checkNotNull(activity)
        val route = host.initialDestination as FileAssociationRoute
        return ViewModelProvider(navigationViewModelStores(host, BookshelfRoute)
            .getOrCreateOwner(NavEntry(route) {}.contentKey))[FileAssociationViewModel::class.java]
    }
    private fun selected(): Int {
        var count = -1
        instrumentation.runOnMainSync { count = model().selectedLocalBooks.size }
        return count
    }
    @Before fun setup() {
        destination.mkdirs()
        files = (0..11).map { index -> File(directory, "Adaptive-$id-$index-long-title-中文.txt").apply {
            writeText("第1章 Adaptive local import $index\n" + "Fixture $id, file $index. Original remains unchanged.\n".repeat(30))
        } }
        originalBytes = files.map(File::readBytes)
        prefs.edit().putString(PreferKey.defaultBookTreeUri, destination.path)
            .putBoolean(PreferKey.autoBackup, false).putString(PreferKey.language, "en").commit()
        shell("settings put system font_scale $scale")
        compose.waitUntil(15_000) { kotlin.math.abs(context.resources.configuration.fontScale - scale.toFloat()) < .01f }
    }
    @After fun cleanup() {
        val errors = arrayListOf<Throwable>()
        fun finish(action: () -> Unit) { runCatching(action).exceptionOrNull()?.let(errors::add) }
        finish { saveScreenshot("last") }
        finish { scenario?.close() }
        finish {
            if (::files.isInitialized) files.forEach { source ->
                appDb.bookDao.getBook(File(destination, source.name).path)?.let { book ->
                    appDb.bookChapterDao.delByBook(book.bookUrl); appDb.bookDao.delete(book); BookHelp.clearCache(book)
                }
            }
            directory.deleteRecursively()
        }
        finish { prefs.edit().apply { oldPrefs.forEach { (key, value) -> when (value) {
            null -> remove(key); is String -> putString(key, value); is Boolean -> putBoolean(key, value)
            else -> error("Unexpected preference type")
        } } }.commit() }
        finish {
            restore("font_scale", if (oldFont == "null") oldEffectiveFont.toString() else oldFont)
            compose.waitUntil(15_000) { kotlin.math.abs(context.resources.configuration.fontScale - oldEffectiveFont) < .01f }
        }
        finish { assertTrue(automation.setRotation(android.app.UiAutomation.ROTATION_UNFREEZE)) }
        finish { restore("accelerometer_rotation", oldAutoRotation) }
        finish { restore("user_rotation", oldRotation) }
        finish {
            assertEquals(oldAutoRotation, setting("accelerometer_rotation")); assertEquals(oldRotation, setting("user_rotation"))
            oldPrefs.forEach { (key, value) -> assertEquals(value, prefs.all[key]) }
            trace.put(JSONObject().put("settings_restored", true))
        }
        File(context.cacheDir, "local-import-$scale-$orientation.json").writeText(trace.toString(2))
        if (errors.isNotEmpty()) throw AssertionError("Local import fixture cleanup failed", errors.first())
    }
    private fun saveScreenshot(stage: String) {
        val bitmap = checkNotNull(automation.takeScreenshot())
        try { File(context.cacheDir, "local-import-$scale-$orientation-$stage.png").outputStream().use {
            assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
        } } finally { bitmap.recycle() }
    }
    private fun label(id: Int) = checkNotNull(activity).getString(id)
    private fun textLayout(text: String): TextLayoutResult {
        val layouts = arrayListOf<TextLayoutResult>()
        compose.onNodeWithText(text, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.single()
    }
    private fun nativeBounds(text: String): Rect? {
        fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.text?.toString() == text && node.isVisibleToUser && node.isEnabled) return node
            repeat(node.childCount) { index -> find(node.getChild(index))?.let { return it } }
            return null
        }
        var node = find(automation.rootInActiveWindow) ?: return null
        while (!node.isClickable && node.parent != null) node = node.parent
        return Rect().also(node::getBoundsInScreen)
    }
    private fun assertLayout(stage: String) {
        compose.waitForIdle()
        compose.waitUntil(10_000) { nativeBounds(label(R.string.add_to_bookshelf))?.let { it.width() > 0 && it.height() > 0 } == true }
        val count = selected()
        val selector = activity!!.getString(if (count == files.size) R.string.select_cancel_count else R.string.select_all_count, count, files.size)
        compose.onNodeWithText(selector).assertIsDisplayed()
        compose.waitUntil(10_000) { nativeBounds(selector) != null }
        automation.waitForIdle(100, 5_000)
        // Compose semantics can advance before the Android 8 compositor presents
        // the new selection. Wait real platform frames before the raw screenshot.
        val rendered = CountDownLatch(1)
        instrumentation.runOnMainSync {
            val choreographer = Choreographer.getInstance()
            choreographer.postFrameCallback(object : Choreographer.FrameCallback {
                var remaining = 3
                override fun doFrame(frameTimeNanos: Long) {
                    if (--remaining == 0) rendered.countDown() else choreographer.postFrameCallback(this)
                }
            })
        }
        assertTrue("Platform did not present the dialog frames", rendered.await(5, TimeUnit.SECONDS))
        saveScreenshot(stage)
        val text = label(R.string.add_to_bookshelf)
        val layout = textLayout(text)
        val textBounds = compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        // FlowRow can reuse a paragraph laid out against a wider constraint while
        // the Text node takes its intrinsic width. Check every actual character
        // against the visible node, rather than treating unused paragraph width
        // as painted overflow. Keep the raw paragraph flag in the trace.
        val inkFits = layout.lineCount == 1 && layout.getLineEnd(0, visibleEnd = true) == text.length &&
            !layout.isLineEllipsized(0) && text.indices.all { index ->
                val glyph = layout.getBoundingBox(index)
                glyph.left >= -.5f && glyph.top >= -.5f && glyph.right <= textBounds.width + .5f && glyph.bottom <= textBounds.height + .5f
            }
        val dialog = compose.onNodeWithTag("sharedLocalBooksDialog").fetchSemanticsNode().boundsInRoot
        trace.put(JSONObject().put("stage", stage).put("orientation", orientation)
            .put("font_scale", activity!!.resources.configuration.fontScale).put("selected", selected())
            .put("window_width_dp", activity!!.resources.configuration.screenWidthDp)
            .put("window_height_dp", activity!!.resources.configuration.screenHeightDp)
            .put("confirm_lines", layout.lineCount).put("confirm_overflow", layout.hasVisualOverflow)
            .put("confirm_ink_fits", inkFits).put("confirm_paragraph_width", layout.multiParagraph.width)
            .put("confirm_layout_size", layout.size.toString())
            .put("confirm_paragraph_height", layout.multiParagraph.height)
            .put("confirm_constraints", layout.layoutInput.constraints.toString())
            .put("confirm_text_bounds", textBounds.toString())
            .put("dialog_bounds", dialog.toString()))
        assertEquals("Confirmation was squeezed into multiple lines", 1, layout.lineCount)
        assertTrue("Confirmation characters are clipped or omitted", inkFits)
        assertTrue("Wide dialog lost its content width cap", dialog.width <= activity!!.resources.displayMetrics.density * 640 + 2)
        if (orientation == "landscape" && activity!!.resources.configuration.screenWidthDp >= 700) {
            assertTrue("Landscape dialog did not use available width", dialog.width >= activity!!.resources.displayMetrics.density * 600 - 2)
        }
        val bitmap = checkNotNull(automation.takeScreenshot())
        try {
            for (tag in listOf("sharedLocalBooksConfirm", "sharedLocalBooksCancel")) {
                compose.onNodeWithTag(tag).assertIsDisplayed().assertIsEnabled()
                val title = if (tag.endsWith("Confirm")) text else label(R.string.cancel)
                var bounds: Rect? = null
                compose.waitUntil(10_000) { bounds = nativeBounds(title); bounds != null }
                val rectangle = checkNotNull(bounds)
                trace.getJSONObject(trace.length() - 1).put(tag + "_bounds", rectangle.toShortString())
                assertTrue("Action outside visible screen: $rectangle", rectangle.left >= 0 && rectangle.top >= 0 && rectangle.right <= bitmap.width && rectangle.bottom <= bitmap.height)
                assertTrue("Action touch target too small: $rectangle", rectangle.height() >= activity!!.resources.displayMetrics.density * 48 - 2)
            }
        } finally { bitmap.recycle() }
        val list = compose.onNodeWithTag("sharedLocalBooksList").fetchSemanticsNode().boundsInRoot
        trace.getJSONObject(trace.length() - 1).put("list_bounds", list.toString())
        assertTrue("No usable list remains above the actions", list.height >= activity!!.resources.displayMetrics.density * 48 - 2)
    }
    private fun ready(landscape: Boolean) {
        var lastRotationRequest = SystemClock.elapsedRealtime()
        compose.waitUntil(20_000) {
            scenario!!.onActivity { activity = it }
            val host = checkNotNull(activity)
            val config = host.resources.configuration
            if ((config.screenWidthDp > config.screenHeightDp) != landscape && SystemClock.elapsedRealtime() - lastRotationRequest > 500) {
                automation.setRotation(desiredRotation)
                lastRotationRequest = SystemClock.elapsedRealtime()
            }
            (config.screenWidthDp > config.screenHeightDp) == landscape && kotlin.math.abs(config.fontScale - scale.toFloat()) < .01f &&
                compose.onAllNodesWithTag("sharedLocalBooksDialog").fetchSemanticsNodes().isNotEmpty()
        }
    }
    @Suppress("DEPRECATION")
    private fun rotationFor(landscape: Boolean): Int {
        val display = context.getSystemService(WindowManager::class.java).defaultDisplay
        val size = Point().also(display::getRealSize)
        val naturalLandscape = (size.x > size.y) xor (display.rotation % 2 != 0)
        return if (landscape == naturalLandscape) android.app.UiAutomation.ROTATION_FREEZE_0 else android.app.UiAutomation.ROTATION_FREEZE_90
    }
    private fun exercise(landscape: Boolean) {
        orientation = if (landscape) "landscape" else "portrait"
        val rotation = rotationFor(landscape).also { desiredRotation = it }
        assertTrue(automation.setRotation(rotation))
        val uris = ArrayList(files.map { FileProvider.getUriForFile(context, "${context.packageName}.fileProvider", it) })
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).setType("text/plain").setComponent(FileAssociationContract.component(context))
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .apply { clipData = ClipData.newUri(context.contentResolver, "Adaptive books", uris.first()).also { clip -> uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) } } }
        scenario = ActivityScenario.launch(intent)
        assertTrue(automation.setRotation(rotation)); ready(landscape)
        if (!landscape) {
            compose.waitUntil(10_000) { nativeBounds(label(R.string.add_to_bookshelf)) != null }
            val bitmap = checkNotNull(automation.takeScreenshot())
            val x = bitmap.width - activity!!.resources.displayMetrics.density * 2
            val y = bitmap.height / 2f
            bitmap.recycle()
            val time = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(time, SystemClock.uptimeMillis(), action, x, y, 0)
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                try { assertTrue(automation.injectInputEvent(event, true)) } finally { event.recycle() }
            }
            compose.waitUntil(10_000) { scenario!!.state == androidx.lifecycle.Lifecycle.State.DESTROYED }
            assertTrue(destination.listFiles().orEmpty().isEmpty())
            trace.put(JSONObject().put("outside_dismissed_without_import", true))
            scenario!!.close()
            scenario = ActivityScenario.launch(intent)
            assertTrue(automation.setRotation(rotation)); ready(landscape)
        }
        assertEquals(12, selected()); assertLayout("initial")
        compose.onNodeWithTag("sharedLocalBooksList").performScrollToIndex(11)
        compose.onNodeWithTag("sharedLocalBook:${files.last().name}").assertIsDisplayed()
        compose.onNodeWithTag("sharedLocalBooksList").performScrollToIndex(0)
        compose.onNodeWithTag("sharedLocalBook:${files.first().name}").performTouchInput { click() }
        assertEquals(11, selected())
        scenario!!.recreate(); ready(landscape); assertEquals(11, selected()); assertLayout("recreated")
        compose.onNodeWithTag("sharedLocalBooksSelectAll").performTouchInput { click() }; assertEquals(12, selected())
        compose.onNodeWithTag("sharedLocalBooksSelectAll").performTouchInput { click() }; assertEquals(0, selected())
        compose.onNodeWithTag("sharedLocalBooksConfirm").assertIsNotEnabled()
        compose.onNodeWithTag("sharedLocalBook:${files.first().name}").performTouchInput { click() }
        assertEquals(1, selected()); assertLayout("selected-one")
        if (landscape) {
            compose.onNodeWithTag("sharedLocalBooksConfirm").performTouchInput { click() }
            compose.waitUntil(20_000) { appDb.bookDao.has(File(destination, files.first().name).path) }
            assertArrayEquals(files.first().readBytes(), File(destination, files.first().name).readBytes())
            assertTrue(files.drop(1).all { !File(destination, it.name).exists() })
        } else {
            compose.onNodeWithTag("sharedLocalBooksCancel").performTouchInput { click() }
            compose.waitUntil(10_000) { scenario!!.state == androidx.lifecycle.Lifecycle.State.DESTROYED }
            assertTrue(destination.listFiles().orEmpty().isEmpty())
        }
        files.forEachIndexed { index, file -> assertArrayEquals("Original file $index changed", originalBytes[index], file.readBytes()) }
        trace.put(JSONObject().put("actual_import", landscape).put("originals_preserved", true))
    }
    @Test fun portraitSelectionAndCancellationRemainUsable() = exercise(false)
    @Test fun landscapeSelectionAndConfirmationRemainUsable() = exercise(true)
}
