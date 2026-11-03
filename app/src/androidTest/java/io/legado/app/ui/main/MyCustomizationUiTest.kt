package io.legado.app.ui.main

import android.graphics.Point
import android.view.WindowManager
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.ui.test.captureCleanupDisplayScreenshot
import io.legado.app.ui.test.runCleanupSteps
import io.legado.app.ui.test.captureDisplayScreenshot
import io.legado.app.ui.test.displayScreenshotBackend
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.ui.AppActivity
import io.legado.app.ui.main.my.MyAction
import io.legado.app.ui.navigation.AboutRoute
import io.legado.app.utils.defaultSharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

/** Production root navigation, customization dialog and persisted My/More destinations. */
@RunWith(AndroidJUnit4::class)
class MyCustomizationUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val preferences = context.defaultSharedPreferences
    private val saved = listOf(PreferKey.myMoreItems, PreferKey.language, PreferKey.autoBackup)
        .associateWith { key -> preferences.all[key].let { if (it is Set<*>) it.toSet() else it } }
    private val initial = setOf(MyAction.CheckUpdate.key, MyAction.CheckBetaUpdate.key)
    private val rotation = InstrumentationRegistry.getArguments().getString("compatSettingsRotation", "0")!!.toInt().also { require(it in 0..1) }
    @Suppress("DEPRECATION")
    private fun rotationFor(landscape: Boolean): Int {
        val display = context.getSystemService(WindowManager::class.java).defaultDisplay
        val size = Point().also(display::getRealSize)
        val naturalLandscape = (size.x > size.y) xor (display.rotation % 2 != 0)
        return if (landscape == naturalLandscape) android.app.UiAutomation.ROTATION_FREEZE_0
        else android.app.UiAutomation.ROTATION_FREEZE_90
    }
    private var scenario: ActivityScenario<AppActivity>? = null
    private var case = "not-started"
    private val trace = JSONArray()
    private fun label(id: Int) = context.getString(id)
    private fun dialogText(id: Int) = hasText(label(id)) and hasAnyAncestor(isDialog())
    private fun entry(id: Int) = compose.onNode(dialogText(id))
    private fun list() = compose.onNode(hasScrollAction() and hasAnyAncestor(isDialog()))
    private fun action(id: Int) = compose.onNode(dialogText(id) and hasClickAction())

    @Before fun open() {
        preferences.edit().putString(PreferKey.language, "en").putBoolean(PreferKey.autoBackup, false)
            .putStringSet(PreferKey.myMoreItems, initial).commit()
        scenario = ActivityScenario.launch(AppActivity.startIntent(context, AboutRoute))
        compose.waitUntil(15_000) {
            instrumentation.uiAutomation.setRotation(rotationFor(rotation == 1))
            var oriented = false
            scenario!!.onActivity { oriented = (it.resources.configuration.screenWidthDp > it.resources.configuration.screenHeightDp) == (rotation == 1) }
            oriented && compose.onAllNodes(isSelectable() and hasText(label(R.string.my))).fetchSemanticsNodes().size == 1
        }
        compose.onNode(isSelectable() and hasText(label(R.string.my))).performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("my_preferences").fetchSemanticsNodes().size == 1 }
    }

    @After fun cleanup() = runCleanupSteps(
        {
            captureCleanupDisplayScreenshot(instrumentation.uiAutomation).let { image ->
                try { File(context.cacheDir, "my-customization-$case-last.png").outputStream().use {
                    check(image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
                } } finally { image.recycle() }
            }
        },
        { scenario?.close() },
        { instrumentation.uiAutomation.setRotation(android.app.UiAutomation.ROTATION_UNFREEZE) },
        {
            preferences.edit().apply { saved.forEach { (key, value) -> when (value) {
                null -> remove(key)
                is String -> putString(key, value)
                is Boolean -> putBoolean(key, value)
                is Set<*> -> putStringSet(key, value.map { it as String }.toSet())
                else -> error("Unexpected preference type")
            } } }.commit()
            saved.forEach { (key, value) -> assertEquals("Preference not restored: $key", value, preferences.all[key]) }
            File(context.cacheDir, "my-customization-$case.json").writeText(JSONObject()
                .put("case", case).put("samples", trace).put("preferences_restored", true).toString(2))
        },
    )

    private fun openDialog() {
        compose.onNodeWithContentDescription(label(R.string.more_menu)).performClick()
        compose.onNodeWithText(label(R.string.customize_my)).performClick()
        compose.onNode(isDialog()).assertExists()
    }

    private fun select(id: Int) {
        list().performScrollToNode(hasText(label(id)))
        assertTouchable(id)
        entry(id).performTouchInput { click() }
    }

    private fun assertTouchable(id: Int) {
        entry(id).assertIsDisplayed()
        var density = 1f
        scenario!!.onActivity { density = it.resources.displayMetrics.density }
        val bounds = entry(id).fetchSemanticsNode().boundsInRoot
        assertTrue("Choice row has no usable visible touch area: $bounds", bounds.width >= 48 * density - 2 && bounds.height >= 48 * density - 2)
    }

    private fun assertSelection(id: Int, checked: Boolean) {
        list().performScrollToNode(hasText(label(id)))
        assertTouchable(id)
        entry(id).assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))
        if (checked) entry(id).assertIsOn() else entry(id).assertIsOff()
    }

    private fun sample(stage: String) {
        action(R.string.cancel).assertIsDisplayed().assertIsEnabled()
        action(R.string.ok).assertIsDisplayed().assertIsEnabled()
        val row = JSONObject().put("stage", stage).put("screenshot_backend", displayScreenshotBackend())
        scenario!!.onActivity { activity ->
            val c = activity.resources.configuration
            row.put("width_dp", c.screenWidthDp).put("height_dp", c.screenHeightDp)
                .put("font_scale", c.fontScale).put("density_dpi", c.densityDpi)
        }
        val automation = instrumentation.uiAutomation
        automation.waitForIdle(100, 5000)
        val image = captureDisplayScreenshot(automation, compare = true)
        fun find(node: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.text?.toString() == text && node.isVisibleToUser) return node
            repeat(node.childCount) { find(node.getChild(it), text)?.let { found -> return found } }
            return null
        }
        try {
            for (id in listOf(R.string.cancel, R.string.ok)) {
                var node = checkNotNull(find(automation.rootInActiveWindow, label(id)))
                while (!node.isClickable && node.parent != null) node = node.parent
                val rect = Rect().also(node::getBoundsInScreen)
                assertTrue("Dialog action outside screen: $rect", rect.left >= 0 && rect.top >= 0 &&
                    rect.right <= image.width && rect.bottom <= image.height)
                assertTrue("Dialog action too small: $rect", rect.height() >= context.resources.displayMetrics.density * 48 - 2)
                row.put(label(id), rect.toShortString())
            }
            File(context.cacheDir, "my-customization-$case-$stage.png").outputStream().use {
                assertTrue(image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { image.recycle(); trace.put(row) }
    }

    @Test fun aDraftRemainsOpenAfterRecreationAndCancelNeverPersistsIt() {
        case = "cancel"
        openDialog(); select(R.string.book_source_manage); select(R.string.check_beta_update)
        assertEquals(initial, preferences.getStringSet(PreferKey.myMoreItems, null))
        sample("editing")
        scenario!!.recreate(); compose.waitForIdle()
        compose.onNode(isDialog()).assertExists()
        assertSelection(R.string.book_source_manage, true); assertSelection(R.string.check_beta_update, false)
        sample("recreated")
        action(R.string.cancel).performClick()
        assertEquals(initial, preferences.getStringSet(PreferKey.myMoreItems, null))
        openDialog()
        assertSelection(R.string.book_source_manage, false); assertSelection(R.string.check_beta_update, true)
        action(R.string.cancel).performClick()
    }

    @Test fun confirmationAfterRecreationMovesTheEntryToTheActualMorePage() {
        case = "confirm"
        openDialog(); select(R.string.book_source_manage)
        sample("editing")
        scenario!!.recreate(); compose.waitForIdle()
        compose.onNode(isDialog()).assertExists()
        assertSelection(R.string.book_source_manage, true)
        sample("recreated")
        assertEquals(initial, preferences.getStringSet(PreferKey.myMoreItems, null))
        action(R.string.ok).performClick()
        compose.waitUntil(5000) { preferences.getStringSet(PreferKey.myMoreItems, null) == initial + MyAction.BookSourceManage.key }
        compose.onNodeWithTag("my_preferences").performScrollToNode(hasText(label(R.string.reader_menu_more)))
        compose.onNodeWithText(label(R.string.reader_menu_more)).performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(label(R.string.book_source_manage)))
        compose.onNodeWithText(label(R.string.book_source_manage)).assertIsDisplayed()
    }

    @Test fun checkboxRowsExposeTheirSelectionAndCanBeToggledBySemanticAction() {
        case = "semantics"
        openDialog()
        assertSelection(R.string.book_source_manage, false)
        entry(R.string.book_source_manage).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        assertSelection(R.string.book_source_manage, true)
        sample("editing")
        action(R.string.cancel).performClick()
        assertEquals(initial, preferences.getStringSet(PreferKey.myMoreItems, null))
    }
}
