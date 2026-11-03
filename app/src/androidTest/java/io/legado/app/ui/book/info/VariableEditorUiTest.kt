package io.legado.app.ui.book.info

import android.content.Intent
import android.os.SystemClock
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.constant.BookType
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.repository.SourceVariableRepository
import io.legado.app.help.book.BookHelp
import io.legado.app.help.config.LocalConfig
import io.legado.app.ui.book.source.edit.BookSourceEditActivity
import io.legado.app.ui.variable.VariableEditorController
import io.legado.app.utils.defaultSharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class VariableEditorUiTest {
    @get:Rule val composeRule = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val id = UUID.randomUUID().toString()
    private val source = BookSource("https://variable-$id.invalid", "Variable source $id", variableComment = "Source note $id")
    private val book = Book(bookUrl = "variable://$id", name = "Variable book $id", author = "Author", origin = source.bookSourceUrl,
        tocUrl = "variable://$id/toc", coverUrl = "android.resource://${context.packageName}/${R.mipmap.ic_launcher}")
    private val repository = SourceVariableRepository()
    private val prefs = context.defaultSharedPreferences
    private val savedPrefs = listOf(PreferKey.autoBackup).associateWith { prefs.all[it] }
    private val savedLocal = listOf("privacyPolicyOk", "readHelpVersion", "readMenuHelpVersion").associateWith { LocalConfig.all[it] }

    @Before fun setUp() {
        prefs.edit().putBoolean(PreferKey.autoBackup, false).commit()
        LocalConfig.edit().putBoolean("privacyPolicyOk", true).putInt("readHelpVersion", 1).putInt("readMenuHelpVersion", 1).commit()
        book.putCustomVariable("Original $id")
        book.putVariable("parser", "Retained $id")
        appDb.bookSourceDao.insert(source)
        appDb.bookDao.insert(book)
        appDb.bookChapterDao.insert(BookChapter(bookUrl = book.bookUrl, url = "chapter", title = "Chapter"))
        runBlocking { repository.write(source.getKey(), "Source original $id") }
    }

    @After fun cleanUp() {
        runBlocking { repository.write(source.getKey(), null) }
        appDb.bookChapterDao.delByBook(book.bookUrl)
        appDb.bookDao.delete(book)
        BookHelp.clearCache(book)
        appDb.bookSourceDao.delete(source)
        prefs.edit().apply { savedPrefs.forEach { (key, value) -> if (value is Boolean) putBoolean(key, value) else remove(key) } }.commit()
        LocalConfig.edit().apply { savedLocal.forEach { (key, value) -> when (value) {
            null -> remove(key); is Boolean -> putBoolean(key, value); is Int -> putInt(key, value)
        } } }.commit()
    }

    @Test fun bookDraftSurvivesRecreationAndCancelDoesNotWrite() {
        launch().use { scenario ->
            ready(scenario)
            scenario.onActivity { model(it).openBookVariableEditor() }
            composeRule.waitForIdle()
            val before = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
            try { java.io.File(context.cacheDir, "variable-editor-before.png").outputStream().use { before.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
            finally { before.recycle() }
            lateinit var label: String
            scenario.onActivity { label = it.getString(R.string.variable_value) }
            composeRule.onNodeWithText(label).assertIsDisplayed()
            composeRule.onNodeWithText("Source note $id", substring = true).assertIsDisplayed()
            composeRule.onNodeWithTag("variable_editor_input").performTextReplacement("Draft $id")
            await { state(scenario).variableEditor?.draft == "Draft $id" }
            composeRule.onNodeWithTag("variable_editor_input").assertTextContains("Draft $id")
            composeRule.waitForIdle()
            scenario.recreate()
            await { state(scenario).variableEditor?.draft == "Draft $id" }
            composeRule.onNodeWithTag("variable_editor_input").assertTextContains("Draft $id")
            val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
            try {
                java.io.File(context.cacheDir, "variable-editor-ui.png").outputStream().use {
                    assertTrue(screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
                }
            } finally { screenshot.recycle() }
            composeRule.onNodeWithTag("variable_editor_cancel").performClick()
            await { state(scenario).variableEditor == null }
            assertEquals("Original $id", appDb.bookDao.getBook(book.bookUrl)!!.getCustomVariable())
            scenario.onActivity { model(it).openBookVariableEditor() }
            composeRule.onNodeWithTag("variable_editor_input").assertTextContains("Original $id")
            composeRule.onNodeWithTag("variable_editor_input").performTextReplacement("Saved $id")
            composeRule.onNodeWithTag("variable_editor_save").performClick()
            await { state(scenario).variableEditor == null }
            assertEquals("Saved $id", appDb.bookDao.getBook(book.bookUrl)!!.getCustomVariable())
            assertEquals("Retained $id", appDb.bookDao.getBook(book.bookUrl)!!.getVariable("parser"))
        }
    }

    @Test fun sourceCancelKeepsItsValueAndSavingAnEmptyDraftDeletesIt() {
        launch().use { scenario ->
            ready(scenario)
            scenario.onActivity { model(it).openSourceVariableEditor() }
            await { state(scenario).variableEditor?.ready == true }
            composeRule.onNodeWithTag("variable_editor_input").performTextReplacement("Cancelled $id")
            composeRule.onNodeWithTag("variable_editor_cancel").performClick()
            assertEquals("Source original $id", runBlocking { repository.read(source.getKey()) })
            scenario.onActivity { model(it).openSourceVariableEditor() }
            await { state(scenario).variableEditor?.ready == true }
            composeRule.onNodeWithTag("variable_editor_input").assertTextContains("Source original $id")
            composeRule.onNodeWithTag("variable_editor_input").performTextReplacement("")
            composeRule.onNodeWithTag("variable_editor_save").performClick()
            await { state(scenario).variableEditor == null }
            assertEquals("", source.getVariable())
            assertNull(appDb.cacheDao.get("sourceVariable_${source.getKey()}"))
        }
    }

    @Test fun previewSaveChangesOnlyThePreviewUntilItIsAddedToTheShelf() {
        book.type = BookType.text or BookType.notShelf
        appDb.bookDao.update(book)
        launch().use { scenario ->
            ready(scenario)
            scenario.onActivity { model(it).openBookVariableEditor() }
            composeRule.onNodeWithTag("variable_editor_input").performTextReplacement("Preview $id")
            composeRule.onNodeWithTag("variable_editor_save").performClick()
            await { state(scenario).variableEditor == null && state(scenario).recoveryReady }
            assertEquals("Preview $id", state(scenario).book!!.getCustomVariable())
            assertEquals("Original $id", appDb.bookDao.getBook(book.bookUrl)!!.getCustomVariable())
            scenario.onActivity { model(it).addToBookshelf(null) }
            await { state(scenario).inBookshelf && !state(scenario).shelfMutation }
            assertEquals("Preview $id", appDb.bookDao.getBook(book.bookUrl)!!.getCustomVariable())
        }
    }

    @Test fun aFailedSaveRetainsTheDraftAndCanBeRetried() {
        launch().use { scenario ->
            ready(scenario)
            scenario.onActivity { model(it).openBookVariableEditor() }
            composeRule.onNodeWithTag("variable_editor_input").performTextReplacement("Retry $id")
            appDb.bookDao.delete(book)
            composeRule.onNodeWithTag("variable_editor_save").performClick()
            await { state(scenario).variableEditor?.error != null }
            assertEquals("Retry $id", state(scenario).variableEditor!!.draft)
            assertFalse(state(scenario).variableEditor!!.saving)
            appDb.bookDao.insert(book)
            composeRule.onNodeWithTag("variable_editor_save").performClick()
            await { state(scenario).variableEditor == null }
            assertEquals("Retry $id", appDb.bookDao.getBook(book.bookUrl)!!.getCustomVariable())
        }
    }

    @Test fun aNewOwnerRestoresTheDraftWithoutReloadingOrWritingIt() {
        val saved = SavedStateHandle()
        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val secondScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            lateinit var first: VariableEditorController
            instrumentation.runOnMainSync { first = VariableEditorController(saved, firstScope, repository); first.openSource(source.getKey(), source.variableComment) }
            await { first.state.value?.ready == true }
            lateinit var restored: SavedStateHandle
            instrumentation.runOnMainSync {
                first.updateDraft("Recovered $id")
                restored = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })
                firstScope.cancel()
            }
            lateinit var second: VariableEditorController
            instrumentation.runOnMainSync { second = VariableEditorController(restored, secondScope, repository) }
            assertEquals("Recovered $id", second.state.value!!.draft)
            assertTrue(second.state.value!!.ready)
            assertEquals("Source original $id", runBlocking { repository.read(source.getKey()) })
            instrumentation.runOnMainSync { second.dismiss() }
            assertNull(second.state.value)
            assertFalse(restored.contains("variableEditorKey"))
        } finally { firstScope.cancel(); secondScope.cancel() }
    }

    @Test fun sourceEditorUsesTheSameComposeEditorAndItsSavedStateFactory() {
        ActivityScenario.launch<BookSourceEditActivity>(Intent(context, BookSourceEditActivity::class.java).putExtra("sourceUrl", source.bookSourceUrl)).use { scenario ->
            lateinit var action: String
            scenario.onActivity { action = it.getString(R.string.set_source_variable) }
            composeRule.waitUntil(20_000) {
                composeRule.onAllNodes(androidx.compose.ui.test.hasTestTag("legadoTopAppBarOverflow")).fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithTag("legadoTopAppBarOverflow").performClick()
            composeRule.onNodeWithText(action).performClick()
            composeRule.waitUntil(20_000) { composeRule.onAllNodes(androidx.compose.ui.test.hasTestTag("variable_editor_input")).fetchSemanticsNodes().isNotEmpty() }
            composeRule.waitUntil(20_000) {
                composeRule.onAllNodes(androidx.compose.ui.test.hasText("Source original $id")).fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithTag("variable_editor_input").performTextReplacement("Source edit $id")
            scenario.recreate()
            composeRule.onNodeWithTag("variable_editor_input").assertTextContains("Source edit $id")
            composeRule.onNodeWithTag("variable_editor_save").performClick()
            await { runBlocking { repository.read(source.getKey()) } == "Source edit $id" }
        }
    }

    private fun model(activity: BookInfoActivity) = ViewModelProvider(activity)[BookInfoViewModel::class.java]
    private fun launch() = ActivityScenario.launch<BookInfoActivity>(Intent(context, BookInfoActivity::class.java).putExtra("bookUrl", book.bookUrl))
    private fun state(scenario: ActivityScenario<BookInfoActivity>): BookInfoUiState {
        lateinit var result: BookInfoUiState
        scenario.onActivity { result = model(it).uiState.value }
        return result
    }
    private fun ready(scenario: ActivityScenario<BookInfoActivity>) = await { state(scenario).book?.bookUrl == book.bookUrl && state(scenario).chapters.size == 1 }
    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 20_000
        while (!condition() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
        assertTrue("Variable editor did not reach expected state", condition())
        instrumentation.waitForIdleSync()
    }
}
