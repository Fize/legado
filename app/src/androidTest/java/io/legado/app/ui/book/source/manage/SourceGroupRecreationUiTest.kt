package io.legado.app.ui.book.source.manage

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.ui.test.captureDisplayScreenshot
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.utils.GSON
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.io.File

@RunWith(AndroidJUnit4::class)
class SourceGroupRecreationUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dao = appDb.bookSourceDao
    private fun captureFailure(name: String = "filtered") {
        runCatching {
            File(context.cacheDir, "source-group-$name-tree.txt")
                .writeText(compose.onRoot(useUnmergedTree = true).printToString())
        }
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        runCatching {
            captureDisplayScreenshot(automation).let { bitmap ->
                File(context.cacheDir, "source-group-$name.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
        }
        for (service in listOf("window displays", "input_method")) runCatching {
            val bytes = ParcelFileDescriptor.AutoCloseInputStream(
                automation.executeShellCommand("dumpsys $service")
            ).use { it.readBytes() }
            check(bytes.size <= 2 * 1024 * 1024)
            File(context.cacheDir, "source-group-$name-${service.substringBefore(' ')}.txt").writeBytes(bytes)
        }
    }
    private fun assertRenderedGroup(source: BookSource, group: String?) {
        val expected = if (group.isNullOrBlank()) source.bookSourceName else "${source.bookSourceName} ($group)"
        try {
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText(expected).fetchSemanticsNodes().size == 1
            }
        } catch (error: Throwable) {
            val shown = compose.onAllNodes(hasText(source.bookSourceName, substring = true))
                .fetchSemanticsNodes().flatMap { node ->
                    node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
                }
            File(context.cacheDir, "source-group-rendered-failure.json").writeText(
                JSONObject().put("expected", expected).put("rendered", JSONArray(shown))
                    .put("database_group", dao.getBookSource(source.bookSourceUrl)?.bookSourceGroup).toString(2))
            captureFailure("rendered")
            throw error
        }
    }
    private fun editor() = compose.onNode(hasSetTextAction() and hasAnyAncestor(isDialog()))
    private fun checkbox(name: String) = compose.onNode(
        SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox) and
            hasAnySibling(hasText(name, substring = true)), useUnmergedTree = true)
    private fun revealSource(name: String) {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(name, substring = true))
        checkbox(name).assertIsDisplayed()
    }
    private fun assertDraft(value: String) {
        compose.waitUntil(10_000) {
            runCatching {
                val state = editor().fetchSemanticsNode().config
                state.getOrNull(SemanticsProperties.EditableText)?.text == value &&
                    state.getOrNull(SemanticsProperties.InputText)?.text == value
            }.getOrDefault(false)
        }
    }
    private fun openGroup(remove: Boolean) {
        val menu = compose.onNodeWithTag("selectionOverflow")
        var previous: String? = null
        var stableSince = SystemClock.uptimeMillis()
        // Compose idleness does not wait for the platform IME to finish resizing
        // the window after a dialog closes. Keep the actual pointer target stable.
        compose.waitUntil(10_000) {
            val bounds = runCatching { menu.fetchSemanticsNode().boundsInWindow }.getOrNull()
            val snapshot = bounds?.toString()
            if (snapshot != previous) { previous = snapshot; stableSince = SystemClock.uptimeMillis() }
            bounds != null && bounds.width > 0 && bounds.height > 0 &&
                SystemClock.uptimeMillis() - stableSince >= 500
        }
        menu.performClick()
        val actionText = context.getString(if (remove) R.string.remove_group else R.string.add_group)
        compose.waitUntil(10_000) { compose.onAllNodesWithText(actionText).fetchSemanticsNodes().size == 1 }
        compose.onNodeWithText(actionText).performScrollTo().assertIsDisplayed().performClick()
    }
    private fun exercise(remove: Boolean, cancel: Boolean) {
        val id = UUID.randomUUID().toString().take(8)
        val group = "group-$id"
        val order = dao.maxOrder + 1
        val selected = BookSource("https://selected-$id.invalid", "selected-$id",
            bookSourceGroup = if (remove) "keep,$group" else "keep", customOrder = order)
        val untouched = BookSource("https://untouched-$id.invalid", "untouched-$id",
            bookSourceGroup = "keep,$group", bookSourceComment = "unchanged-$id", customOrder = order + 1)
        dao.insert(selected, untouched)
        val before = GSON.toJson(dao.getBookSource(untouched.bookSourceUrl))
        try {
            ActivityScenario.launch(BookSourceActivity::class.java).use { scenario ->
                try {
                compose.waitUntil(15_000) {
                    compose.onAllNodes(hasText(selected.bookSourceName, substring = true))
                        .fetchSemanticsNodes().isNotEmpty()
                }
                checkbox(selected.bookSourceName).performClick()
                checkbox(selected.bookSourceName).assertIsOn()
                checkbox(untouched.bookSourceName).assertIsOff()
                openGroup(remove)
                editor().performTextReplacement(group)
                assertDraft(group)
                repeat(2) {
                    var old: BookSourceActivity? = null
                    scenario.onActivity { old = it }
                    scenario.recreate()
                    scenario.onActivity { assertNotSame(old, it) }
                    assertDraft(group)
                }
                compose.onNode(hasText(context.getString(if (cancel) R.string.cancel else R.string.ok)) and
                    hasClickAction()).performClick()
                val expected = if (cancel) selected.bookSourceGroup else if (remove) "keep" else "keep,$group"
                compose.waitUntil(10_000) { dao.getBookSource(selected.bookSourceUrl)?.bookSourceGroup == expected }
                checkbox(selected.bookSourceName).assertIsOn()
                checkbox(untouched.bookSourceName).assertIsOff()
                assertEquals(before, GSON.toJson(dao.getBookSource(untouched.bookSourceUrl)))
                assertRenderedGroup(selected, expected)
                assertRenderedGroup(untouched, untouched.bookSourceGroup)
                openGroup(remove)
                assertDraft("")
                compose.onNode(hasText(context.getString(R.string.cancel)) and hasClickAction()).performClick()
                } catch (error: Throwable) {
                    captureFailure(if (cancel) "cancel" else if (remove) "remove" else "add")
                    throw error
                }
            }
        } finally {
            dao.delete(selected, untouched)
            assertNull(dao.getBookSource(selected.bookSourceUrl))
            assertNull(dao.getBookSource(untouched.bookSourceUrl))
        }
    }

    @Test fun addAfterTwoRecreationsChangesOnlyTheSelectedSource() = exercise(remove = false, cancel = false)
    @Test fun removeAfterTwoRecreationsPreservesOtherGroupsAndSources() = exercise(remove = true, cancel = false)
    @Test fun cancelAfterTwoRecreationsKeepsGroupsAndSelection() = exercise(remove = false, cancel = true)

    @Test fun filteredOperationKeepsItsVisibleTargetsAndHiddenSelectionAfterRecreation() {
        val id = UUID.randomUUID().toString().take(8)
        val order = dao.maxOrder + 1
        val hidden = BookSource("https://hidden-$id.invalid", "hidden-$id", bookSourceGroup = "keep", customOrder = order)
        val visible = BookSource("https://visible-$id.invalid", "visible-$id", bookSourceGroup = "keep", customOrder = order + 1)
        dao.insert(hidden, visible)
        val before = GSON.toJson(dao.getBookSource(hidden.bookSourceUrl))
        try {
            ActivityScenario.launch(BookSourceActivity::class.java).use { scenario ->
                try {
                compose.waitUntil(15_000) {
                    compose.onAllNodes(hasText(hidden.bookSourceName, substring = true))
                        .fetchSemanticsNodes().isNotEmpty()
                }
                checkbox(hidden.bookSourceName).performClick()
                compose.onNodeWithTag("bookSourceSearch").performClick()
                compose.onNodeWithTag("legadoSearchField").performTextReplacement(visible.bookSourceName)
                compose.waitUntil(10_000) {
                    compose.onAllNodes(hasText(hidden.bookSourceName, substring = true)).fetchSemanticsNodes().isEmpty()
                }
                compose.onNodeWithText(context.getString(R.string.select_all_count, 0, 1)).assertIsDisplayed()
                compose.onNodeWithText(context.getString(R.string.delete)).assertIsNotEnabled()
                compose.onNodeWithText(context.getString(R.string.revert_selection)).assertIsNotEnabled()
                checkbox(visible.bookSourceName).performClick()
                compose.onNodeWithText(context.getString(R.string.select_all_count, 1, 1)).assertIsDisplayed()
                openGroup(remove = false)
                editor().performTextReplacement("new-$id")
                repeat(2) {
                    scenario.recreate()
                    assertDraft("new-$id")
                }
                compose.onNode(hasText(context.getString(R.string.ok)) and hasClickAction()).performClick()
                compose.waitUntil(10_000) {
                    dao.getBookSource(visible.bookSourceUrl)?.bookSourceGroup == "keep,new-$id"
                }
                assertEquals(before, GSON.toJson(dao.getBookSource(hidden.bookSourceUrl)))
                assertRenderedGroup(visible, "keep,new-$id")
                compose.onNodeWithText(context.getString(R.string.select_all_count, 1, 1)).assertIsDisplayed()
                compose.onNodeWithTag("legadoSearchField").performTextReplacement("")
                // The search text updates before the asynchronous query/list does.
                // Wait for both fixture rows in the collection, not for an off-screen node.
                var rowsAfterClear = 0
                compose.waitUntil(10_000) {
                    rowsAfterClear = compose.onNode(hasScrollToIndexAction()).fetchSemanticsNode().config
                        .getOrNull(SemanticsProperties.CollectionInfo)?.rowCount ?: 0
                    rowsAfterClear >= 2
                }
                var owner: BookSourceActivity? = null
                var imeBefore = false
                scenario.onActivity {
                    owner = it
                    imeBefore = ViewCompat.getRootWindowInsets(it.window.decorView)
                        ?.isVisible(WindowInsetsCompat.Type.ime()) == true
                }
                if (imeBefore) Espresso.pressBack()
                var imeAfter = true
                compose.waitUntil(5_000) {
                    scenario.onActivity {
                        assertSame("Hiding the IME must keep the source screen", owner, it)
                        imeAfter = ViewCompat.getRootWindowInsets(it.window.decorView)
                            ?.isVisible(WindowInsetsCompat.Type.ime()) == true
                    }
                    !imeAfter
                }
                revealSource(hidden.bookSourceName)
                checkbox(hidden.bookSourceName).assertIsOn()
                revealSource(visible.bookSourceName)
                checkbox(visible.bookSourceName).assertIsOn()
                File(context.cacheDir, "source-group-filtered-recovery.json").writeText(
                    JSONObject().put("ime_before", imeBefore).put("ime_after", imeAfter)
                        .put("rows_after_clear", rowsAfterClear)
                        .put("same_activity", true).put("both_selected_after_real_scroll", true)
                        .put("hidden_source_unchanged", before == GSON.toJson(dao.getBookSource(hidden.bookSourceUrl)))
                        .put("visible_group", dao.getBookSource(visible.bookSourceUrl)?.bookSourceGroup).toString(2))
                } catch (error: Throwable) {
                    captureFailure()
                    throw error
                }
            }
        } finally { dao.delete(hidden, visible) }
    }
}
