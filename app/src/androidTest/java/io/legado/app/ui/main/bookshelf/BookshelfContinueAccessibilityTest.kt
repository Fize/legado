package io.legado.app.ui.main.bookshelf

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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BookshelfContinueAccessibilityTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun continueReadingIsAnAccessibleAction() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val book = Book(name = "Accessibility test book", durChapterPos = 100)
        var opened = 0
        var continueLabel = ""

        fun contains(node: AccessibilityNodeInfo, value: String): Boolean {
            if (node.text?.contains(value) == true || node.contentDescription?.contains(value) == true) return true
            return (0 until node.childCount).any { node.getChild(it)?.let { child -> contains(child, value) } == true }
        }
        fun findAction(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.isClickable && contains(node, book.name)) return node
            for (index in 0 until node.childCount) findAction(node.getChild(index))?.let { return it }
            return null
        }

        ActivityScenario.launch<AppActivity>(AppActivity.startIntent(context, AboutRoute)).use { scenario ->
            scenario.onActivity { activity ->
                continueLabel = activity.getString(R.string.continue_read)
                activity.setContent {
                    MaterialTheme { BookshelfHeaderSection(false, "", book, { opened++ }, {}) }
                }
            }
            var action: AccessibilityNodeInfo? = null
            fun dump(node: AccessibilityNodeInfo?, depth: Int = 0): String {
                if (node == null) return "null\n"
                return "${" ".repeat(depth)}text=${node.text} desc=${node.contentDescription} clickable=${node.isClickable}\n" +
                    (0 until node.childCount).joinToString("") { dump(node.getChild(it), depth + 1) }
            }
            try {
                compose.waitUntil(5_000) {
                    action = findAction(instrumentation.uiAutomation.rootInActiveWindow)
                    action != null
                }
            } catch (error: Throwable) {
                throw AssertionError(dump(instrumentation.uiAutomation.rootInActiveWindow), error)
            }
            assertNotNull(action)
            assertTrue(contains(action!!, continueLabel))
            assertTrue(action!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            compose.waitForIdle()
            compose.runOnIdle { assertEquals(1, opened) }
        }
    }

    @Test fun searchAndAddAreAccessibleActions() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val actions = mutableListOf<BookshelfMenuAction>()
        var searchLabel = ""
        var addLabel = ""

        fun contains(node: AccessibilityNodeInfo, label: String): Boolean {
            if (node.text?.contains(label) == true || node.contentDescription?.contains(label) == true) return true
            return (0 until node.childCount).any { node.getChild(it)?.let { child -> contains(child, label) } == true }
        }
        fun findAction(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.isClickable && contains(node, label)) return node
            for (index in 0 until node.childCount) findAction(node.getChild(index), label)?.let { return it }
            return null
        }

        ActivityScenario.launch<AppActivity>(AppActivity.startIntent(context, AboutRoute)).use { scenario ->
            scenario.onActivity { activity ->
                searchLabel = activity.getString(R.string.search)
                addLabel = activity.getString(R.string.book_local)
                activity.setContent { MaterialTheme { BookshelfTopBar("Shelf", { actions.add(it) }) } }
            }
            for (label in listOf(searchLabel, addLabel)) {
                var action: AccessibilityNodeInfo? = null
                compose.waitUntil(5_000) {
                    action = findAction(instrumentation.uiAutomation.rootInActiveWindow, label)
                    action != null
                }
                assertTrue(action!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                compose.waitForIdle()
            }
            compose.runOnIdle {
                assertEquals(listOf(BookshelfMenuAction.SEARCH, BookshelfMenuAction.ADD_LOCAL), actions)
            }
        }
    }
}
