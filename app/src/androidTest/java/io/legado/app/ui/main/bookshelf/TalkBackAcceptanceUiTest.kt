package io.legado.app.ui.main.bookshelf

import android.app.UiAutomation
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.data.entities.Book
import io.legado.app.ui.AppActivity
import io.legado.app.ui.navigation.AboutRoute
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TalkBackAcceptanceUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).bufferedReader().use { it.readText() }
    private fun contains(node: AccessibilityNodeInfo, text: String): Boolean =
        node.text?.contains(text) == true || node.contentDescription?.contains(text) == true ||
            (0 until node.childCount).any { node.getChild(it)?.let { child -> contains(child, text) } == true }
    private fun find(node: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isClickable && contains(node, text)) return node
        for (i in 0 until node.childCount) find(node.getChild(i), text)?.let { return it }
        return null
    }
    /** Opt-in hardware input test. The host sends emulator `event mouse` double taps
     * for each coordinate published in talkback-touch-ready.txt. Framework-injected
     * taps bypass TalkBack's input processing and cannot verify this gesture. */
    @Test fun realTalkBackFocusAndDoubleTapActivateShelfActions() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("hardwareTouch") == "true")
        val enabled = shell("settings get secure enabled_accessibility_services").trim()
        val active = shell("settings get secure accessibility_enabled").trim()
        val service = "com.google.android.marvin.talkback/com.google.android.marvin.talkback.TalkBackService"
        try {
            val services = if (enabled == "null" || enabled.isBlank()) service else if (enabled.contains(service)) enabled else "$enabled:$service"
            shell("settings put secure enabled_accessibility_services $services")
            shell("settings put secure accessibility_enabled 1")
            compose.waitUntil(20_000) { shell("dumpsys accessibility").contains("Bound services:{Service[label=TalkBack") }
            SystemClock.sleep(2000)
            ActivityScenario.launch<AppActivity>(AppActivity.startIntent(instrumentation.targetContext, AboutRoute)).use { scenario ->
                var count = 0
                var label = ""
                var searchLabel = ""
                var addLabel = ""
                var longClicks = 0
                val actions = mutableListOf<BookshelfMenuAction>()
                scenario.onActivity { activity ->
                    label = activity.getString(R.string.continue_read)
                    searchLabel = activity.getString(R.string.search)
                    addLabel = activity.getString(R.string.book_local)
                    activity.setContent { MaterialTheme {
                        BookshelfHeaderSection(false, "", Book(name = "TalkBack fixture", durChapterPos = 100), { count++ }, { longClicks++ })
                    } }
                }
                val ready = java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "talkback-touch-ready.txt")
                try {
                    for ((index, target) in listOf(label, searchLabel, addLabel).withIndex()) {
                        if (index == 1) scenario.onActivity { activity ->
                            activity.setContent { MaterialTheme { BookshelfTopBar("Shelf", { actions.add(it) }) } }
                        }
                        var action: AccessibilityNodeInfo? = null
                        compose.waitUntil(15_000) { action = find(automation.rootInActiveWindow, target); action != null }
                        assertTrue(action!!.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS))
                        SystemClock.sleep(1000)
                        if (index == 0) {
                            assertTrue(action!!.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK))
                            compose.waitUntil(5000) { longClicks == 1 }
                        }
                        val rect = android.graphics.Rect(); action!!.getBoundsInScreen(rect)
                        ready.writeText("$index ${rect.centerX()} ${rect.centerY()}")
                        compose.waitUntil(60_000) { if (index == 0) count == 1 else actions.size == index }
                        ready.delete()
                    }
                    assertEquals(1, count)
                    assertEquals(1, longClicks)
                    assertEquals(listOf(BookshelfMenuAction.SEARCH, BookshelfMenuAction.ADD_LOCAL), actions)
                } finally { ready.delete() }
            }
        } finally {
            shell(if (enabled == "null") "settings delete secure enabled_accessibility_services" else "settings put secure enabled_accessibility_services $enabled")
            shell(if (active == "null") "settings delete secure accessibility_enabled" else "settings put secure accessibility_enabled $active")
        }
    }
}
