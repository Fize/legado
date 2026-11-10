package io.legado.app.ui.book.search

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.help.config.ThemeConfig
import io.legado.app.ui.AppActivity
import io.legado.app.ui.compat.OrientationWindowSession
import org.json.JSONObject
import io.legado.app.ui.navigation.SearchRoute
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SearchThemeUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun networkSearchRemainsUsableInLightAndDark() {
        val prefs = context.getSharedPreferences("${context.packageName}_preferences", android.content.Context.MODE_PRIVATE)
        val original = prefs.getString("themeMode", "0")
        try {
            listOf("1", "2").forEach { theme ->
                prefs.edit().putString("themeMode", theme).commit()
                instrumentation.runOnMainSync { ThemeConfig.applyDayNightInit(context) }
                ActivityScenario.launch<AppActivity>(AppActivity.startIntent(context, SearchRoute())).use { scenario ->
                    var back = ""
                    var clear = ""
                    var search = ""
                    scenario.onActivity { back = it.getString(R.string.back); clear = it.getString(R.string.clear); search = it.getString(R.string.search) }
                    compose.waitUntil(20_000) { runCatching { compose.onNodeWithTag("search_query").assertIsEnabled() }.isSuccess }
                    compose.onNodeWithTag("search_query").performTextInput("test")
                    compose.onNodeWithTag("search_query").assertTextContains("test")
                    compose.onNodeWithContentDescription(back).assertIsDisplayed()
                    compose.onNodeWithContentDescription(clear).assertIsDisplayed().performClick()
                    compose.onNodeWithTag("search_query").performTextInput("test")
                    compose.onNodeWithContentDescription(search).assertIsDisplayed()
                    Thread.sleep(2000)
                    instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
                        java.io.FileOutputStream(java.io.File(context.getExternalFilesDir(null), "search-theme-$theme.png")).use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                        bitmap.recycle()
                    }
                }
            }
        } finally {
            prefs.edit().putString("themeMode", original).commit()
            instrumentation.runOnMainSync { ThemeConfig.applyDayNightInit(context) }
        }
    }
    @Test fun searchDraftSurvivesExpandedDisplayChanges() {
        ActivityScenario.launch<AppActivity>(AppActivity.startIntent(context, SearchRoute())).use { scenario ->
            var host: AppActivity? = null
            scenario.onActivity { host = it }
            compose.waitUntil(20_000) { runCatching { compose.onNodeWithTag("search_query").assertIsEnabled() }.isSuccess }
            compose.onNodeWithTag("search_query").performTextInput("window draft")
            OrientationWindowSession("search-stage5").use { window ->
                for ((width, height) in listOf(1200 to 800, 800 to 1200, 900 to 700)) {
                    window.resize(width, height, { scenario.onActivity { host = it }; checkNotNull(host) }) {
                        compose.onNodeWithTag("search_query").assertIsDisplayed().assertTextContains("window draft")
                        compose.onNodeWithTag("search_menu").assertIsDisplayed()
                        JSONObject().put("draft", "window draft")
                    }
                }
            }
            compose.onNodeWithTag("search_query").assertTextContains("window draft")
        }
    }

}
