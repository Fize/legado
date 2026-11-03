package io.legado.app.ui.book.bookmark

import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.text.TextRange
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.Bookmark
import io.legado.app.ui.AppActivity
import io.legado.app.ui.navigation.AboutRoute
import io.legado.app.utils.defaultSharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Real Fragment saved state plus Room commit/cancel, including fresh native input after recreation. */
@RunWith(AndroidJUnit4::class)
class BookmarkDraftRecreationUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val samples = JSONArray()
    private val savedStates = JSONArray()
    private val owned = mutableListOf<Bookmark>()
    private var savedAutoBackup: Boolean? = null
    private var savedHardwareIme = "null"

    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command),
    ).bufferedReader().use { it.readText().trim() }

    @Before fun suspendAutomaticBackupForOwnedRows() {
        val preferences = context.defaultSharedPreferences
        savedAutoBackup = preferences.all[PreferKey.autoBackup] as Boolean?
        check(preferences.edit().putBoolean(PreferKey.autoBackup, false).commit())
        savedHardwareIme = shell("settings get secure show_ime_with_hard_keyboard")
        require(savedHardwareIme in listOf("null", "0", "1"))
        shell("settings put secure show_ime_with_hard_keyboard 1")
    }

    @After fun restoreAutomaticBackupPreference() {
        check(context.defaultSharedPreferences.edit().apply {
            savedAutoBackup?.let { putBoolean(PreferKey.autoBackup, it) } ?: remove(PreferKey.autoBackup)
        }.commit())
        shell(if (savedHardwareIme == "null") "settings delete secure show_ime_with_hard_keyboard"
            else "settings put secure show_ime_with_hard_keyboard $savedHardwareIme")
    }
    private fun fixture(label: String) = Bookmark(
        time = (UUID.randomUUID().mostSignificantBits and Long.MAX_VALUE),
        bookName = "bookmark-draft-$label-${UUID.randomUUID()}", bookAuthor = "owned fixture",
        chapterIndex = 1, chapterPos = 5, chapterName = "PDF boundary chapter",
        bookText = "original content", content = "original note",
    ).also { assertNull(appDb.bookmarkDao.get(it.time)); owned += it }

    private fun field(index: Int) = compose.onAllNodes(hasSetTextAction())[index]
    private fun assertDraft(text: String, note: String) {
        compose.waitUntil(10_000) {
            val nodes = compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes(atLeastOneRootRequired = false)
            nodes.size == 2 && nodes[0].config.getOrNull(SemanticsProperties.EditableText)?.text == text &&
                nodes[1].config.getOrNull(SemanticsProperties.EditableText)?.text == note
        }
        for ((index, value) in listOf(text, note).withIndex()) field(index).assert(
            SemanticsMatcher("exact saved bookmark draft") {
                it.config.getOrNull(SemanticsProperties.EditableText)?.text == value
            },
        )
        samples.put(JSONObject().put("text", text).put("note", note))
    }
    private fun open(scenario: ActivityScenario<AppActivity>, bookmark: Bookmark, edit: Boolean) {
        scenario.onActivity { BookmarkDialog(bookmark.copy(), if (edit) 0 else -1)
            .showNow(it.supportFragmentManager, "bookmark-draft-test") }
        assertDraft(bookmark.bookText, bookmark.content)
    }
    private fun recreate(scenario: ActivityScenario<AppActivity>, text: String, note: String) {
        var before = 0
        scenario.onActivity {
            before = System.identityHashCode(it)
            it.supportFragmentManager.registerFragmentLifecycleCallbacks(object : FragmentManager.FragmentLifecycleCallbacks() {
                override fun onFragmentSaveInstanceState(fm: FragmentManager, fragment: Fragment, outState: Bundle) {
                    if (fragment is BookmarkDialog) {
                        val state = JSONObject()
                        for (key in listOf("draftBookText", "draftNoteContent", "draftBookTextSelectionStart",
                            "draftBookTextSelectionEnd", "draftNoteContentSelectionStart", "draftNoteContentSelectionEnd", "focusedEditor")) {
                            @Suppress("DEPRECATION")
                            state.put(key, outState.get(key) ?: JSONObject.NULL)
                        }
                        savedStates.put(state)
                    }
                }
            }, false)
        }
        scenario.recreate()
        scenario.onActivity { assertNotEquals(before, System.identityHashCode(it)) }
        assertDraft(text, note)
    }
    private fun click(id: Int) = compose.onNode(hasText(context.getString(id)) and hasClickAction())
        .performScrollTo().performClick()
    private fun untouched(before: List<Bookmark>, target: Long) =
        assertEquals(before.filterNot { it.time == target }, appDb.bookmarkDao.all.filterNot { it.time == target })
    private fun cleanup(name: String) {
        File(context.cacheDir, "bookmark-draft-$name.json").writeText(
            JSONObject().put("case", name).put("samples", samples).toString(2),
        )
        File(context.cacheDir, "bookmark-draft-state-$name.json").writeText(savedStates.toString(2))
        owned.forEach { appDb.bookmarkDao.delete(it) }
    }

    @Test fun editedDraftAndExplicitEmptyValuesSurviveRecreationWhileCancelKeepsAllRows() {
        val original = fixture("edit")
        val other = fixture("untouched")
        appDb.bookmarkDao.insert(original, other)
        val before = appDb.bookmarkDao.all
        try {
            ActivityScenario.launch<AppActivity>(AppActivity.startIntent(context, AboutRoute)).use { scenario ->
                open(scenario, original, edit = true)
                field(0).performTextReplacement("edited content")
                field(1).performTextReplacement("edited note")
                field(0).performClick().assertIsFocused()
                field(0).performTextInputSelection(TextRange(2, 7))
                field(0).assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(2, 7)))
                repeat(2) {
                    recreate(scenario, "edited content", "edited note")
                    compose.waitUntil(5_000) { field(0).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Focused) == true }
                    field(0).assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(2, 7)))
                    assertEquals(before, appDb.bookmarkDao.all)
                }
                click(R.string.cancel)
                compose.waitUntil(5_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isEmpty() }
                assertEquals(before, appDb.bookmarkDao.all)
                open(scenario, original, edit = true)
                field(0).performTextClearance()
                field(1).performTextClearance()
                recreate(scenario, "", "")
                click(R.string.cancel)
                assertEquals(before, appDb.bookmarkDao.all)
            }
        } finally { cleanup("cancel") }
    }

    @Test fun newBookmarkConfirmsLatestNativeInputAfterRecreationOnlyOnceAndKeepsOtherRows() {
        val bookmark = fixture("new")
        val other = fixture("untouched")
        appDb.bookmarkDao.insert(other)
        val before = appDb.bookmarkDao.all
        try {
            ActivityScenario.launch<AppActivity>(AppActivity.startIntent(context, AboutRoute)).use { scenario ->
                open(scenario, bookmark, edit = false)
                field(0).performTextReplacement("new content")
                field(1).performClick().assertIsFocused()
                field(1).performTextReplacement("new note")
                repeat(2) {
                    recreate(scenario, "new content", "new note")
                    compose.waitUntil(5_000) { field(1).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Focused) == true }
                    field(1).assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(8)))
                    assertNull(appDb.bookmarkDao.get(bookmark.time))
                    assertEquals(before, appDb.bookmarkDao.all)
                }
                field(1).assertIsFocused()
                var stableSince = SystemClock.elapsedRealtime()
                compose.waitUntil(15_000) {
                    val visible = shell("dumpsys input_method").contains("mInputShown=true")
                    if (!visible) stableSince = SystemClock.elapsedRealtime()
                    visible && SystemClock.elapsedRealtime() - stableSince >= 400
                }
                instrumentation.sendStringSync("-fresh")
                assertDraft("new content", "new note-fresh")
                click(R.string.ok)
                val expected = bookmark.copy(bookText = "new content", content = "new note-fresh")
                compose.waitUntil(5_000) { appDb.bookmarkDao.get(bookmark.time) == expected }
                untouched(before, bookmark.time)
                scenario.recreate()
                compose.waitForIdle()
                assertEquals(expected, appDb.bookmarkDao.get(bookmark.time))
                assertEquals(1, appDb.bookmarkDao.all.count { it.time == bookmark.time })
                untouched(before, bookmark.time)
                scenario.onActivity { assertNull(it.supportFragmentManager.findFragmentByTag("bookmark-draft-test")) }
            }
        } finally { cleanup("confirm") }
    }
}
