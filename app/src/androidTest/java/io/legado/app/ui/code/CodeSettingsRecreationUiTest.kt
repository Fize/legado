package io.legado.app.ui.code

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import io.github.rosemoe.sora.widget.CodeEditor
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.defaultSharedPreferences
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CodeSettingsRecreationUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferences = context.defaultSharedPreferences

    @Test fun pendingFlagsSurviveTwoRecreationsAndApplyToTheCurrentEditor() {
        val keys = listOf(PreferKey.editNonPrintable, PreferKey.editAutoComplete,
            PreferKey.language, PreferKey.autoBackup)
        val saved = keys.associateWith { preferences.all[it] }
        val text = "const original = 1;\n  const after = 2;"
        preferences.edit().putInt(PreferKey.editNonPrintable, 0)
            .putBoolean(PreferKey.editAutoComplete, true)
            .putString(PreferKey.language, "en").putBoolean(PreferKey.autoBackup, false).commit()
        try {
            val intent = Intent(context, CodeEditActivity::class.java)
                .putExtra("text", text).putExtra("languageName", "source.js")
            ActivityScenario.launch<CodeEditActivity>(intent).use { scenario ->
                compose.onNodeWithContentDescription(context.getString(R.string.more_menu)).performClick()
                compose.onNodeWithText(context.getString(R.string.config_settings)).performClick()
                val leading = context.getString(R.string.whitespace_leading)
                compose.onNodeWithText(leading).performScrollTo().performClick()
                assertEquals(0, AppConfig.editNonPrintable)
                repeat(2) {
                    var previous: CodeEditActivity? = null
                    scenario.onActivity { previous = it }
                    scenario.recreate()
                    scenario.onActivity { assertNotSame(previous, it) }
                    compose.onNodeWithText(leading).performScrollTo().assertIsDisplayed()
                    compose.onNode(hasText(leading) and hasAnySibling(isToggleable()), useUnmergedTree = true)
                        .assertExists()
                    compose.onNode(isToggleable() and hasAnySibling(hasText(leading)), useUnmergedTree = true)
                        .assertIsOn()
                    assertEquals("Recreation must not commit the pending flags", 0, AppConfig.editNonPrintable)
                }
                compose.onNodeWithText(context.getString(R.string.auto_complete)).performScrollTo().performClick()
                assertFalse(AppConfig.editAutoComplete)
                scenario.onActivity {
                    assertFalse(ViewModelProvider(it)[CodeEditViewModel::class.java].language!!.isAutoCompleteEnabled)
                    assertEquals(text, it.codeEditorForTest().text.toString())
                }
                Espresso.pressBack()
                compose.waitForIdle()
                scenario.onActivity {
                    assertEquals(CodeEditor.FLAG_DRAW_WHITESPACE_LEADING, it.codeEditorForTest().nonPrintablePaintingFlags)
                    assertEquals(text, it.codeEditorForTest().text.toString())
                }
                assertEquals(CodeEditor.FLAG_DRAW_WHITESPACE_LEADING, AppConfig.editNonPrintable)
            }
        } finally {
            preferences.edit().apply {
                saved.forEach { (key, value) -> when (value) {
                    null -> remove(key)
                    is Boolean -> putBoolean(key, value)
                    is Int -> putInt(key, value)
                    is String -> putString(key, value)
                    else -> error("Unexpected preference type for $key")
                } }
            }.commit()
            saved.forEach { (key, value) -> assertEquals(value, preferences.all[key]) }
        }
    }
}
