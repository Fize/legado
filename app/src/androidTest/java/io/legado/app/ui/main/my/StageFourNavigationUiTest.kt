package io.legado.app.ui.main.my

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.help.config.ThemeConfig
import io.legado.app.ui.AppActivity
import io.legado.app.ui.navigation.BookshelfRoute
import io.legado.app.ui.navigation.MyRoute
import io.legado.app.ui.navigation.SettingsRoute
import io.legado.app.ui.navigation.SettingsSection
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StageFourNavigationUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun myAndBackupSettingsRemainAccessibleInBothThemes() {
        val prefs = context.getSharedPreferences("${context.packageName}_preferences", android.content.Context.MODE_PRIVATE)
        val original = prefs.getString("themeMode", "0")
        try {
            listOf("1", "2").forEach { theme ->
                prefs.edit().putString("themeMode", theme).commit()
                instrumentation.runOnMainSync { ThemeConfig.applyDayNightInit(context) }
                ActivityScenario.launch<AppActivity>(AppActivity.startIntent(context, BookshelfRoute)).use { scenario ->
                    scenario.onActivity { it.navigateTo(MyRoute) }
                    compose.waitUntil(20_000) { compose.onAllNodesWithTag("my_preferences").fetchSemanticsNodes().isNotEmpty() }
                    var tools = ""
                    scenario.onActivity { tools = it.getString(R.string.my_management_tools) }
                    compose.onNodeWithText(tools).assertIsDisplayed()
                    Thread.sleep(500)
                    instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
                        java.io.FileOutputStream(java.io.File(context.getExternalFilesDir(null), "stage4-my-$theme.png")).use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                        bitmap.recycle()
                    }
                    listOf(R.string.backup_restore to SettingsSection.Backup, R.string.theme_setting to SettingsSection.Theme, R.string.other_setting to SettingsSection.Other).forEach { (labelId, section) ->
                        var label = ""
                        scenario.onActivity { label = it.getString(labelId) }
                        compose.onNodeWithTag("my_preferences").performScrollToNode(hasText(label))
                        compose.onNodeWithText(label).performClick()
                        compose.waitUntil(20_000) { var open = false; scenario.onActivity { open = it.currentDestination == SettingsRoute(section) }; open }
                        scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
                        compose.waitUntil(20_000) { compose.onAllNodesWithTag("my_preferences").fetchSemanticsNodes().isNotEmpty() }
                    }
                }
            }
        } finally {
            prefs.edit().putString("themeMode", original).commit()
            instrumentation.runOnMainSync { ThemeConfig.applyDayNightInit(context) }
        }
    }
}
