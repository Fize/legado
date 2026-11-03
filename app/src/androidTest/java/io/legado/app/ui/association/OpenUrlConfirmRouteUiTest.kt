package io.legado.app.ui.association

import androidx.test.core.app.ActivityScenario
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.ui.AppActivity
import io.legado.app.ui.navigation.OpenUrlConfirmRoute
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OpenUrlConfirmRouteUiTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Test
    fun routeShowsDialogAndSourceActions() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val route = OpenUrlConfirmRoute(
            uri = "https://example.com",
            mimeType = "text/html",
            sourceOrigin = "https://example.test/source",
            sourceName = "EmulatorSource",
        )

        ActivityScenario.launch<AppActivity>(AppActivity.startIntent(context, route)).use {
            composeRule.onNodeWithText("跳转确认").assertIsDisplayed()
            composeRule.onNodeWithText("EmulatorSource").assertIsDisplayed()
            composeRule.onNodeWithText("正在请求跳转链接/应用，是否跳转？").assertIsDisplayed()

            composeRule.onNodeWithTag("openUrlConfirmMore").performClick()

            composeRule.onNodeWithText(context.getString(R.string.disable_source)).assertIsDisplayed()
            composeRule.onNodeWithText(context.getString(R.string.delete_source)).assertIsDisplayed()
        }
    }
}
