package io.legado.app.ui.config

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import java.io.File

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.text.TextRange
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.ui.AppActivity
import io.legado.app.ui.test.captureDisplayScreenshot
import io.legado.app.ui.book.source.manage.BookSourceActivity
import io.legado.app.utils.ACache
import io.legado.app.utils.GSON
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TestName
import org.junit.runner.RunWith
import java.util.UUID

/** Real source-management host, its saved state, and the actual import review destination. */
@RunWith(AndroidJUnit4::class)
class OnlineImportRecreationUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val testName = TestName()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val cache = ACache.get(cacheDir = false)
    private val historyKey = "bookSourceRecordKey"
    private fun editor() = compose.onNode(hasSetTextAction())
    private fun action(id: Int): SemanticsNodeInteraction {
        val node = compose.onNode(hasText(context.getString(id)) and hasClickAction() and hasAnyAncestor(isDialog()))
        var previous: String? = null
        var stableSince = SystemClock.uptimeMillis()
        compose.waitUntil(10_000) {
            val bounds = runCatching { node.fetchSemanticsNode().boundsInWindow }.getOrNull()
            val snapshot = bounds?.toString()
            if (snapshot != previous) { previous = snapshot; stableSince = SystemClock.uptimeMillis() }
            bounds != null && bounds.width > 0 && bounds.height > 0 &&
                SystemClock.uptimeMillis() - stableSince >= 500
        }
        return node
    }
    private fun ActivityScenario<BookSourceActivity>.captureFailures(
        action: (ActivityScenario<BookSourceActivity>) -> Unit,
    ) = use { scenario ->
        try { action(scenario) } catch (error: Throwable) {
            val prefix = "online-import-${testName.methodName}-failure"
            runCatching {
                val roots = compose.onAllNodes(isRoot(), useUnmergedTree = true)
                File(context.cacheDir, "$prefix-tree.txt").writeText(
                    roots.fetchSemanticsNodes().indices.joinToString("\n") { roots[it].printToString() })
            }
            val automation = instrumentation.uiAutomation
            runCatching {
                val bitmap = captureDisplayScreenshot(automation)
                try { File(context.cacheDir, "$prefix.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                } } finally { bitmap.recycle() }
            }
            for (service in listOf("window displays", "input_method", "activity top")) runCatching {
                val bytes = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("dumpsys $service"))
                    .use { it.readBytes() }
                check(bytes.size <= 2 * 1024 * 1024)
                File(context.cacheDir, "$prefix-${service.substringBefore(' ')}.txt").writeBytes(bytes)
            }
            throw error
        }
    }
    private fun assertDraft(expected: String) {
        try {
            compose.waitUntil(10_000) {
                runCatching {
                    val config = editor().fetchSemanticsNode().config
                    config.getOrNull(SemanticsProperties.EditableText)?.text == expected &&
                        config.getOrNull(SemanticsProperties.InputText)?.text == expected
                }.getOrDefault(false)
            }
        } catch (error: Throwable) {
            val config = editor().fetchSemanticsNode().config
            throw AssertionError("Expected: $expected; editable: ${config.getOrNull(SemanticsProperties.EditableText)?.text}; " +
                "input: ${config.getOrNull(SemanticsProperties.InputText)?.text}; " +
                "selection: ${config.getOrNull(SemanticsProperties.TextSelectionRange)}", error)
        }
    }
    private fun openImport() {
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("legadoTopAppBarOverflow").fetchSemanticsNodes().size == 1
        }
        compose.onNodeWithTag("legadoTopAppBarOverflow").performClick()
        compose.onNodeWithText(context.getString(R.string.import_on_line)).performClick()
    }
    private fun restoreHistory(saved: String?) {
        if (saved == null) cache.remove(historyKey) else cache.put(historyKey, saved)
        assertEquals(saved, cache.getAsString(historyKey))
    }

    @Test fun draftAndContinuedTypingSurviveTwoHostRecreationsAndCancel() {
        val saved = cache.getAsString(historyKey)
        cache.put(historyKey, "https://history.invalid/source.json")
        val draft = "https://draft.invalid/${UUID.randomUUID()}.json"
        try {
            ActivityScenario.launch(BookSourceActivity::class.java).captureFailures { scenario ->
                openImport()
                editor().performTextInput("https://")
                assertDraft("https://")
                compose.onNodeWithContentDescription(context.getString(R.string.delete)).performClick()
                assertEquals("", cache.getAsString(historyKey))
                editor().performTextInput(draft.removePrefix("https://"))
                assertDraft(draft)
                val caret = draft.length / 2
                val selection = TextRange(caret)
                editor().performTextInputSelection(selection)
                repeat(2) {
                    var old: BookSourceActivity? = null
                    scenario.onActivity { old = it }
                    scenario.recreate()
                    scenario.onActivity { assertNotSame(old, it) }
                    assertDraft(draft)
                    editor().assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, selection))
                }
                editor().performTextInput("-after")
                assertDraft(draft.take(caret) + "-after" + draft.drop(caret))
                action(R.string.cancel).performClick()
                openImport()
                assertDraft("")
                assertEquals("", cache.getAsString(historyKey))
                action(R.string.cancel).performClick()
            }
        } finally { restoreHistory(saved) }
    }

    @Test fun confirmationAfterHostRecreationOpensTheExactInlineSourceReview() {
        val saved = cache.getAsString(historyKey)
        cache.remove(historyKey)
        val id = UUID.randomUUID().toString()
        val source = BookSource("https://recreate.invalid/$id", "Recreated source $id")
        val payload = GSON.toJson(listOf(source))
        try {
            ActivityScenario.launch(BookSourceActivity::class.java).captureFailures { scenario ->
                openImport()
                editor().performTextReplacement(payload)
                assertDraft(payload)
                scenario.recreate()
                assertDraft(payload)
                action(R.string.ok).performClick()
                compose.waitUntil(15_000) {
                    compose.onAllNodesWithTag("sourceImportRow:0").fetchSemanticsNodes().size == 1
                }
                compose.onNodeWithText(source.bookSourceName).assertIsDisplayed()
                instrumentation.runOnMainSync {
                    val destination = ActivityLifecycleMonitorRegistry.getInstance()
                        .getActivitiesInStage(Stage.RESUMED).filterIsInstance<AppActivity>().single()
                    val route = JSONObject(destination.intent.getStringExtra("initialDestination")!!)
                    assertEquals(payload, route.getString("source"))
                    destination.finish()
                }
                assertNull(appDb.bookSourceDao.getBookSource(source.bookSourceUrl))
            }
        } finally {
            instrumentation.runOnMainSync {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<AppActivity>().forEach { it.finish() }
            }
            appDb.bookSourceDao.delete(source)
            restoreHistory(saved)
        }
    }
}
