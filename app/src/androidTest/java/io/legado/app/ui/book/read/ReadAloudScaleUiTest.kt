package io.legado.app.ui.book.read

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import android.widget.ImageView
import androidx.core.view.isVisible
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.constant.BookType
import io.legado.app.constant.PageAnim
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.config.LocalConfig
import io.legado.app.model.ReadBook
import io.legado.app.model.localBook.TextFile
import io.legado.app.service.BaseReadAloudService
import io.legado.app.ui.book.read.config.ClickActionConfigDialog
import io.legado.app.ui.book.read.config.ReadAloudControlsDialog
import io.legado.app.ui.book.read.page.ReadView
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.dpToPx
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Actual reader layout and touch bounds; this fixture does not test service lifecycle. */
@RunWith(AndroidJUnit4::class)
class ReadAloudScaleUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val prefs = context.defaultSharedPreferences
    private val savedMenuHelp = LocalConfig.all["readMenuHelpVersion"]
    private val savedPrefs = listOf(PreferKey.readAloudControlsPause, PreferKey.readAloudControlsSize,
        PreferKey.readAloudControlsRealtime, PreferKey.readAloudControlsPosition,
        PreferKey.readAloudControlsAutoHide,
        PreferKey.readAloudControlsDrag, PreferKey.readAloudControlsDock, PreferKey.readAloudControlsOpacity,
        PreferKey.readAloudControlsX, PreferKey.readAloudControlsY, "readAloudControlsWidth")
        .associateWith { prefs.all[it] }
    private val savedRunning = BaseReadAloudService.isRun
    private val savedPaused = BaseReadAloudService.pause
    private val savedFollowing = BaseReadAloudService.followReadAloudPosition
    private var scenario: ActivityScenario<ReadBookActivity>? = null
    private var book: Book? = null
    private var textFile: File? = null

    @Before fun setUp() {
        prefs.edit().putBoolean(PreferKey.readAloudControlsPause, true)
            // This fixture measures controls; independent follow/visibility behavior is tested separately.
            .putBoolean(PreferKey.readAloudControlsRealtime, false)
            .putBoolean(PreferKey.readAloudControlsPosition, true)
            .putBoolean(PreferKey.readAloudControlsAutoHide, false)
            .putBoolean(PreferKey.readAloudControlsDrag, false)
            .putBoolean(PreferKey.readAloudControlsDock, false)
            .remove("readAloudControlsWidth").remove(PreferKey.readAloudControlsOpacity).commit()
        LocalConfig.edit().putInt("readMenuHelpVersion", 1).commit()
        val file = File.createTempFile("aloud-menu-", ".txt", context.cacheDir).also { textFile = it }
        file.writeText((0..60).joinToString("\n") { "Reader content line $it for the playback menu regression." })
        val fixture = Book(bookUrl = file.absolutePath, originName = file.name, name = file.name,
            charset = "UTF-8", type = BookType.local or BookType.text, totalChapterNum = 1)
            .apply { setPageAnim(PageAnim.noAnim) }
        book = fixture
        appDb.bookDao.insert(fixture)
        appDb.bookChapterDao.insert(BookChapter(bookUrl = fixture.bookUrl, url = "aloud-menu-chapter",
            title = "Playback controls", start = 0L, end = file.length()))
        scenario = ActivityScenario.launch(Intent(context, ReadBookActivity::class.java)
            .putExtra("bookUrl", fixture.bookUrl))
        scenario!!.onActivity { activity ->
            activity.supportFragmentManager.fragments.filterIsInstance<ClickActionConfigDialog>()
                .forEach { it.dismissAllowingStateLoss() }
        }
        await("reader content") {
            ReadBook.book?.bookUrl == fixture.bookUrl && ReadBook.curTextChapter?.isCompleted == true &&
                !it.findViewById<ReadView>(R.id.read_view).curPage.textPage.isMsgPage && it.bottomDialog == 0
        }
    }

    @After fun tearDown() {
        instrumentation.runOnMainSync {
            playbackFlag("isRun", savedRunning)
            playbackFlag("pause", savedPaused)
            if (savedFollowing) BaseReadAloudService.restoreReadAloudFollow()
            else BaseReadAloudService.detachReadAloudFollow()
        }
        scenario?.close()
        book?.let {
            appDb.bookChapterDao.delByBook(it.bookUrl)
            appDb.bookDao.delete(it)
        }
        textFile?.delete()
        TextFile.clear()
        LocalConfig.edit().apply {
            if (savedMenuHelp == null) remove("readMenuHelpVersion")
            else putInt("readMenuHelpVersion", savedMenuHelp as Int)
        }.commit()
        prefs.edit().apply {
            savedPrefs.forEach { (key, value) ->
                when (value) {
                    null -> remove(key)
                    is Boolean -> putBoolean(key, value)
                    is Int -> putInt(key, value)
                    is Float -> putFloat(key, value)
                }
            }
        }.commit()
    }

    @Test fun widthControlsWholeBarAndCircleWithMatchingTouchBounds() {
        val evidence = StringBuilder()
        for (widthDp in listOf(288, 144, 85, 432)) {
            prefs.edit().putInt("readAloudControlsWidth", widthDp).commit()
            scenario!!.onActivity {
                playbackFlag("isRun", true)
                BaseReadAloudService.detachReadAloudFollow()
                it.showReadAloudControls()
            }
            await("position bar visible") { it.findViewById<View>(R.id.ll_back_to_speech).isShown }
            screenshot("aloud-scale-position-$widthDp")
            var expectedHeight = 0
            val bounds = Rect()
            val clicks = IntArray(2)
            scenario!!.onActivity { activity ->
                val bar = activity.findViewById<View>(R.id.read_aloud_float_bar)
                val parent = bar.parent as View
                val expectedWidth = minOf(widthDp.dpToPx(), parent.width - 32.dpToPx())
                expectedHeight = (expectedWidth / 6f).roundToInt()
                evidence.appendLine("requested=$widthDp actual=${bar.width}x${bar.height} expected=${expectedWidth}x$expectedHeight")
                File(context.getExternalFilesDir("ui-regression"), "aloud-scale-bounds.txt").writeText(evidence.toString())
                assertEquals("Long control must use the selected total width", expectedWidth, bar.width)
                assertEquals("Long control height must scale with its width", expectedHeight, bar.height)
                assertEquals(1f, bar.scaleX, 0f)
                assertEquals(1f, bar.scaleY, 0f)
                assertTrue(bar.getGlobalVisibleRect(bounds))
                // An odd width can be centered on a half pixel; visible integer bounds round outward.
                val globalTransform = Matrix()
                bar.transformMatrixToGlobal(globalTransform)
                val transformedBounds = RectF(0f, 0f, bar.width.toFloat(), bar.height.toFloat())
                globalTransform.mapRect(transformedBounds)
                val expectedBounds = Rect().also { transformedBounds.roundOut(it) }
                assertEquals("The complete transformed control must remain visible", expectedBounds, bounds)
                listOf(R.id.ll_back_to_speech, R.id.ll_read_from_here).forEachIndexed { index, id ->
                    activity.findViewById<View>(id).setOnClickListener { clicks[index]++ }
                }
                for (id in listOf(R.id.tv_back_to_speech, R.id.tv_read_from_here)) {
                    val text = activity.findViewById<TextView>(id)
                    val layout = checkNotNull(text.layout)
                    val availableWidth = text.width - text.compoundPaddingLeft - text.compoundPaddingRight
                    evidence.appendLine(
                        "text=${activity.resources.getResourceEntryName(id)} width=${text.width} " +
                            "padding=${text.compoundPaddingLeft},${text.compoundPaddingRight} " +
                            "available=$availableWidth lines=${layout.lineCount} height=${layout.height}/${text.height} " +
                            "textSize=${text.textSize} autoType=${androidx.core.widget.TextViewCompat.getAutoSizeTextType(text)} " +
                            "autoMin=${androidx.core.widget.TextViewCompat.getAutoSizeMinTextSize(text)} " +
                            "autoMax=${androidx.core.widget.TextViewCompat.getAutoSizeMaxTextSize(text)}",
                    )
                    for (line in 0 until layout.lineCount) {
                        evidence.appendLine("  line=$line width=${layout.getLineWidth(line)}")
                    }
                    File(context.getExternalFilesDir("ui-regression"), "aloud-scale-bounds.txt")
                        .writeText(evidence.toString())
                    assertTrue("Text must fit vertically", layout.height <= text.height - text.compoundPaddingTop - text.compoundPaddingBottom)
                    for (line in 0 until layout.lineCount) {
                        assertEquals("Action text must remain complete", 0, layout.getEllipsisCount(line))
                        assertTrue(
                            "Action text must fit horizontally: line=${layout.getLineWidth(line)} " +
                                "available=$availableWidth text=${text.text}",
                            layout.getLineWidth(line) <= availableWidth + 1,
                        )
                    }
                }
            }
            tap(bounds.left + bounds.width() * .25f, bounds.exactCenterY())
            tap(bounds.left + bounds.width() * .75f, bounds.exactCenterY())
            tap(bounds.exactCenterX(), bounds.top - 3f)
            var openedReaderDialog = false
            scenario!!.onActivity {
                assertEquals("Only the first visible action should receive its tap", 1, clicks[0])
                assertEquals("Only the second visible action should receive its tap", 1, clicks[1])
                // The outside tap may invoke the reader's configured action underneath the bar.
                // Modal visibility is independent of this test's control-size assertions.
                openedReaderDialog = it.bottomDialog > 0
                if (openedReaderDialog) assertFalse(it.findViewById<View>(R.id.iv_pause_aloud).isShown)
            }
            if (openedReaderDialog) {
                assertTrue(instrumentation.uiAutomation.performGlobalAction(
                    android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
                await("reader dialog dismissed") { it.bottomDialog == 0 }
            }
            scenario!!.onActivity { it.findViewById<ReadMenu>(R.id.read_menu).runMenuOut(anim = false) }
            await("reader menu dismissed") { !it.findViewById<ReadMenu>(R.id.read_menu).isShown && it.bottomDialog == 0 }
            for (paused in listOf(false, true)) {
                scenario!!.onActivity {
                    playbackFlag("pause", paused)
                    BaseReadAloudService.restoreReadAloudFollow()
                    it.showReadAloudControls()
                }
                await("pause control visible") { it.findViewById<View>(R.id.iv_pause_aloud).isShown }
                screenshot("aloud-scale-circle-$widthDp-$paused")
                scenario!!.onActivity { activity ->
                    val bar = activity.findViewById<View>(R.id.read_aloud_float_bar)
                    val pause = activity.findViewById<ImageView>(R.id.iv_pause_aloud)
                    assertEquals("Circle follows the same scale", expectedHeight, bar.width)
                    assertEquals(expectedHeight, bar.height)
                    assertEquals(expectedHeight, pause.width)
                    assertEquals(expectedHeight, pause.height)
                    assertCircleIconPixels(pause, "aloud-scale-icon-$widthDp-$paused", evidence)
                }
            }
        }
        File(context.getExternalFilesDir("ui-regression"), "aloud-scale-bounds.txt").writeText(evidence.toString())
    }

    @Test fun legacySizeAndSelectedWidthSurviveSettingsAndReaderRecreation() {
        prefs.edit().putInt(PreferKey.readAloudControlsSize, 72).remove("readAloudControlsWidth").commit()
        scenario!!.onActivity {
            ReadAloudControlsDialog().show(it.supportFragmentManager, "scale-settings")
        }
        await("width preference") { activity ->
            val dialog = activity.supportFragmentManager.findFragmentByTag("scale-settings")
            val fragment = dialog?.childFragmentManager?.findFragmentByTag("controls")
                as? ReadAloudControlsDialog.ControlsPreferenceFragment
            fragment?.view != null
        }
        screenshot("aloud-scale-legacy-settings")
        scenario!!.onActivity { activity ->
            val dialog = activity.supportFragmentManager.findFragmentByTag("scale-settings") as ReadAloudControlsDialog
            val fragment = dialog.childFragmentManager.findFragmentByTag("controls")
                as ReadAloudControlsDialog.ControlsPreferenceFragment
            assertEquals("Old 72dp height maps to the original long width", 432, readAloudControlWidth(prefs))
            prefs.edit().putInt("readAloudControlsWidth", 85).commit()
            dialog.dismiss()
        }
        await("settings dismissed") { it.bottomDialog == 0 }
        scenario!!.recreate()
        scenario!!.onActivity {
            playbackFlag("isRun", true)
            BaseReadAloudService.detachReadAloudFollow()
            it.showReadAloudControls()
        }
        await("restored position control") { it.findViewById<View>(R.id.ll_back_to_speech).isShown }
        screenshot("aloud-scale-restored-85")
        scenario!!.onActivity { activity ->
            assertEquals(85, prefs.getInt("readAloudControlsWidth", -1))
            val bar = activity.findViewById<View>(R.id.read_aloud_float_bar)
            assertEquals(85.dpToPx(), bar.width)
        }
    }

    @Test fun draggedPositionSurvivesDisablingDragAndResetRestoresDefault() {
        for (pauseControl in listOf(false, true)) verifyLockedPosition(pauseControl)
    }

    private fun verifyLockedPosition(pauseControl: Boolean) {
        val controlId = if (pauseControl) R.id.iv_pause_aloud else R.id.ll_back_to_speech
        prefs.edit().remove(PreferKey.readAloudControlsX).remove(PreferKey.readAloudControlsY)
            .putBoolean(PreferKey.readAloudControlsDrag, true)
            .putBoolean(PreferKey.readAloudControlsPause, true)
            .putInt(PreferKey.readAloudControlsWidth, 85).commit()
        scenario!!.onActivity {
            playbackFlag("isRun", true)
            if (pauseControl) BaseReadAloudService.restoreReadAloudFollow()
            else BaseReadAloudService.detachReadAloudFollow()
            it.showReadAloudControls(resetPosition = true)
        }
        await("movable control visible") { it.findViewById<View>(controlId).isShown }
        screenshot("aloud-lock-default-$pauseControl")

        var defaultX = 0f
        var defaultY = 0f
        var startGlobalX = 0f
        var startGlobalY = 0f
        scenario!!.onActivity { activity ->
            val bar = activity.findViewById<View>(R.id.read_aloud_float_bar)
            defaultX = bar.x
            defaultY = bar.y
            val location = IntArray(2)
            bar.getLocationOnScreen(location)
            startGlobalX = location[0] + bar.width / 2f
            startGlobalY = location[1] + bar.height / 2f
        }
        // Move inside the real control bounds; the production listener stores normalized coordinates on ACTION_UP.
        drag(startGlobalX, startGlobalY, startGlobalX - 48.dpToPx(), startGlobalY - 72.dpToPx())
        var movedX = 0f
        var movedY = 0f
        var storedX = 0f
        var storedY = 0f
        scenario!!.onActivity { activity ->
            val bar = activity.findViewById<View>(R.id.read_aloud_float_bar)
            movedX = bar.x
            movedY = bar.y
            storedX = prefs.getFloat(PreferKey.readAloudControlsX, Float.NaN)
            storedY = prefs.getFloat(PreferKey.readAloudControlsY, Float.NaN)
            assertTrue("Drag must move the real control", movedX < defaultX - 24.dpToPx() && movedY < defaultY - 24.dpToPx())
            assertTrue("Drag must persist normalized X", storedX.isFinite() && storedX != .5f)
            assertTrue("Drag must persist normalized Y", storedY.isFinite() && storedY >= 0f)
        }

        prefs.edit().putBoolean(PreferKey.readAloudControlsDrag, false).commit()
        scenario!!.onActivity { it.showReadAloudControls() }
        await("control remains visible after disabling drag") { it.findViewById<View>(controlId).isShown }
        scenario!!.onActivity { activity ->
            val bar = activity.findViewById<View>(R.id.read_aloud_float_bar)
            assertEquals("Disabling drag must retain X", movedX, bar.x, 1f)
            assertEquals("Disabling drag must retain Y", movedY, bar.y, 1f)
        }

        // With dragging disabled the same real gesture must leave both the view and stored coordinates unchanged.
        var movedGlobalX = 0f
        var movedGlobalY = 0f
        scenario!!.onActivity { activity ->
            val bar = activity.findViewById<View>(R.id.read_aloud_float_bar)
            val location = IntArray(2)
            bar.getLocationOnScreen(location)
            movedGlobalX = location[0] + bar.width / 2f
            movedGlobalY = location[1] + bar.height / 2f
        }
        drag(movedGlobalX, movedGlobalY, movedGlobalX + 48.dpToPx(), movedGlobalY + 48.dpToPx())
        scenario!!.onActivity { activity ->
            val bar = activity.findViewById<View>(R.id.read_aloud_float_bar)
            assertEquals("Disabled drag must ignore movement on X", movedX, bar.x, 1f)
            assertEquals("Disabled drag must ignore movement on Y", movedY, bar.y, 1f)
            assertEquals(storedX, prefs.getFloat(PreferKey.readAloudControlsX, Float.NaN), 0f)
            assertEquals(storedY, prefs.getFloat(PreferKey.readAloudControlsY, Float.NaN), 0f)
        }
        screenshot("aloud-lock-retained-$pauseControl")

        scenario!!.onActivity { it.showReadAloudControls(resetPosition = true) }
        await("control remains visible after reset") {
            it.findViewById<View>(controlId).isShown &&
                !prefs.contains(PreferKey.readAloudControlsX) && !prefs.contains(PreferKey.readAloudControlsY)
        }
        scenario!!.onActivity { activity ->
            val bar = activity.findViewById<View>(R.id.read_aloud_float_bar)
            assertFalse("Reset must remove stored X", prefs.contains(PreferKey.readAloudControlsX))
            assertFalse("Reset must remove stored Y", prefs.contains(PreferKey.readAloudControlsY))
            assertEquals("Reset must restore default X", defaultX, bar.x, 1f)
            assertEquals("Reset must restore default Y", defaultY, bar.y, 1f)
            File(context.getExternalFilesDir("ui-regression"), "aloud-lock-$pauseControl.txt").writeText(
                "default=$defaultX,$defaultY moved=$movedX,$movedY stored=$storedX,$storedY " +
                    "lockedGestureUnchanged=true reset=${bar.x},${bar.y} coordinatesRemoved=true")
        }
        screenshot("aloud-lock-reset-$pauseControl")
    }

    @Test fun oldSmallWidthIsClampedAndOpacityDefaultsOnlyWhenUnset() {
        prefs.edit().putInt("readAloudControlsWidth", 40).remove(PreferKey.readAloudControlsOpacity).commit()
        scenario!!.onActivity {
            playbackFlag("isRun", true)
            BaseReadAloudService.detachReadAloudFollow()
            it.showReadAloudControls()
        }
        await("clamped control") { it.findViewById<View>(R.id.ll_back_to_speech).isShown }
        screenshot("aloud-scale-old40-clamped85-opacity90")
        scenario!!.onActivity { activity ->
            val bar = activity.findViewById<View>(R.id.read_aloud_float_bar)
            assertEquals(85.dpToPx(), bar.width)
            assertEquals("Unset opacity uses 90 percent", 229,
                Color.alpha((bar.background as GradientDrawable).color!!.defaultColor))
            ReadAloudControlsDialog().show(activity.supportFragmentManager, "new-defaults")
        }
        await("new settings") { activity ->
            val dialog = activity.supportFragmentManager.findFragmentByTag("new-defaults")
            val fragment = dialog?.childFragmentManager?.findFragmentByTag("controls")
                as? ReadAloudControlsDialog.ControlsPreferenceFragment
            fragment?.view != null
        }
        scenario!!.onActivity { activity ->
            val dialog = activity.supportFragmentManager.findFragmentByTag("new-defaults") as ReadAloudControlsDialog
            val fragment = dialog.childFragmentManager.findFragmentByTag("controls")
                as ReadAloudControlsDialog.ControlsPreferenceFragment
            assertEquals(85, readAloudControlWidth(prefs))
            prefs.edit().putInt("readAloudControlsWidth", 85).commit()
            assertEquals(85, prefs.getInt("readAloudControlsWidth", -1))
            assertEquals(90, prefs.getInt(PreferKey.readAloudControlsOpacity, 90))
            prefs.edit().putInt(PreferKey.readAloudControlsOpacity, 30).commit()
            dialog.dismiss()
        }
        await("new settings dismissed") { it.bottomDialog == 0 }
        scenario!!.recreate()
        scenario!!.onActivity {
            playbackFlag("isRun", true)
            BaseReadAloudService.detachReadAloudFollow()
            it.showReadAloudControls()
        }
        await("explicit opacity restored") { it.findViewById<View>(R.id.ll_back_to_speech).isShown }
        screenshot("aloud-scale-explicit-opacity30")
        scenario!!.onActivity { activity ->
            val bar = activity.findViewById<View>(R.id.read_aloud_float_bar)
            assertEquals("Explicit opacity remains unchanged", 30, prefs.getInt(PreferKey.readAloudControlsOpacity, -1))
            assertEquals(76, Color.alpha((bar.background as GradientDrawable).color!!.defaultColor))
            ReadAloudControlsDialog().show(activity.supportFragmentManager, "saved-opacity")
        }
        await("saved opacity preference") { activity ->
            val dialog = activity.supportFragmentManager.findFragmentByTag("saved-opacity")
            val fragment = dialog?.childFragmentManager?.findFragmentByTag("controls")
                as? ReadAloudControlsDialog.ControlsPreferenceFragment
            fragment?.view != null && prefs.getInt(PreferKey.readAloudControlsOpacity, -1) == 30
        }
        scenario!!.onActivity {
            (it.supportFragmentManager.findFragmentByTag("saved-opacity") as ReadAloudControlsDialog).dismiss()
        }
    }

    /** Render the actual ImageButton, then measure its nontransparent icon pixels, not its view box. */
    private fun assertCircleIconPixels(pause: ImageView, name: String, evidence: StringBuilder) {
        val content = Rect(pause.paddingLeft, pause.paddingTop,
            pause.width - pause.paddingRight, pause.height - pause.paddingBottom)
        val drawableBounds = RectF(pause.drawable.bounds)
        pause.imageMatrix.mapRect(drawableBounds)
        drawableBounds.offset(pause.paddingLeft.toFloat(), pause.paddingTop.toFloat())
        assertTrue("The complete drawable must fit the inner circle",
            drawableBounds.left >= content.left - 1 && drawableBounds.top >= content.top - 1 &&
                drawableBounds.right <= content.right + 1 && drawableBounds.bottom <= content.bottom + 1)
        val bitmap = Bitmap.createBitmap(pause.width, pause.height, Bitmap.Config.ARGB_8888)
        try {
            pause.draw(Canvas(bitmap))
            val pixels = Rect(pause.width, pause.height, 0, 0)
            for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                if (Color.alpha(bitmap.getPixel(x, y)) > 16) {
                    pixels.left = minOf(pixels.left, x)
                    pixels.top = minOf(pixels.top, y)
                    pixels.right = maxOf(pixels.right, x + 1)
                    pixels.bottom = maxOf(pixels.bottom, y + 1)
                }
            }
            evidence.appendLine("$name view=${pause.width}x${pause.height} content=$content drawable=$drawableBounds pixels=$pixels")
            File(context.getExternalFilesDir("ui-regression"), "$name.png").outputStream()
                .use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            File(context.getExternalFilesDir("ui-regression"), "aloud-scale-bounds.txt").writeText(evidence.toString())
            assertTrue("The icon must remain visibly rendered", pixels.width() >= 2 && pixels.height() >= 2)
            assertTrue("Actual icon pixels must stay inside the padded circle", content.contains(pixels))
        } finally { bitmap.recycle() }
    }

    private fun drag(fromX: Float, fromY: Float, toX: Float, toY: Float) {
        val downTime = SystemClock.uptimeMillis()
        fun send(action: Int, x: Float, y: Float, eventTime: Long) {
            MotionEvent.obtain(downTime, eventTime, action, x, y, 0).let {
                try { instrumentation.sendPointerSync(it) } finally { it.recycle() }
            }
        }
        send(MotionEvent.ACTION_DOWN, fromX, fromY, downTime)
        repeat(4) { index ->
            val fraction = (index + 1) / 4f
            send(MotionEvent.ACTION_MOVE, fromX + (toX - fromX) * fraction,
                fromY + (toY - fromY) * fraction, downTime + (index + 1) * 40L)
        }
        send(MotionEvent.ACTION_UP, toX, toY, downTime + 200L)
        instrumentation.waitForIdleSync()
    }

    private fun tap(x: Float, y: Float) {
        val time = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            MotionEvent.obtain(time, SystemClock.uptimeMillis(), action, x, y, 0).let {
                try { instrumentation.sendPointerSync(it) } finally { it.recycle() }
            }
        }
        instrumentation.waitForIdleSync()
    }

    private fun playbackFlag(name: String, value: Boolean) {
        BaseReadAloudService::class.java.getDeclaredField(name).apply { isAccessible = true }
            .setBoolean(null, value)
    }

    private fun await(description: String, condition: (ReadBookActivity) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 30000
        do {
            var ready = false
            scenario!!.onActivity { ready = condition(it) }
            if (ready) return
            SystemClock.sleep(50)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError("Timed out waiting for $description")
    }

    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        val rendered = CountDownLatch(1)
        scenario!!.onActivity {
            val decor = it.window.decorView
            decor.postOnAnimation { decor.postOnAnimation { rendered.countDown() } }
        }
        assertTrue(rendered.await(5, TimeUnit.SECONDS))
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(context.getExternalFilesDir("ui-regression"), "$name.png").outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { bitmap.recycle() }
    }
}
