package io.legado.app.ui.association

import androidx.compose.ui.test.*
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.help.source.SourceVerificationHelp
import io.legado.app.ui.AppActivity
import io.legado.app.ui.navigation.VerificationCodeRoute
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VerificationCodeRouteUiTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Test
    fun routeShowsDialogAndReturnsTypedResult() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val imageUrl = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
        val menuRoute = VerificationCodeRoute(
            imageUrl = imageUrl,
            sourceOrigin = "https://example.test/source",
            sourceName = "VerificationSource",
        )

        ActivityScenario.launch<AppActivity>(AppActivity.startIntent(context, menuRoute)).use {
            composeRule.onNodeWithText(context.getString(R.string.input_verification_code))
                .assertIsDisplayed()
            composeRule.onNodeWithText("VerificationSource").assertIsDisplayed()

            composeRule.onNodeWithTag("verificationCodeMore").performClick()
            composeRule.onNodeWithText(context.getString(R.string.disable_source))
                .assertIsDisplayed()
            composeRule.onNodeWithText(context.getString(R.string.delete_source))
                .assertIsDisplayed()
        }

        val resultKey = SourceVerificationHelp.registerVerificationAttempt(Thread.currentThread())
        val resultRoute = VerificationCodeRoute(
            imageUrl = imageUrl,
            verificationResultKey = resultKey,
        )
        try {
            ActivityScenario.launch<AppActivity>(AppActivity.startIntent(context, resultRoute)).use {
                composeRule.onNodeWithText(context.getString(R.string.input_verification_code))
                    .assertIsDisplayed()

                composeRule.onNodeWithTag("verificationCodeInput").performTextInput("8472")
                composeRule.onNodeWithTag("verificationCodeSubmit").performClick()

                composeRule.waitUntil(timeoutMillis = 5_000) {
                    SourceVerificationHelp.getResult(resultKey) != null
                }
                assertEquals("" to "8472", SourceVerificationHelp.getResult(resultKey))
            }
        } finally {
            SourceVerificationHelp.clearResult(resultKey)
        }
    }
    @Test fun imagePreviewReturnsWithoutCompletingTheVerificationAttempt() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val imageUrl = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
        val key = SourceVerificationHelp.registerVerificationAttempt(Thread.currentThread())
        try {
            ActivityScenario.launch<AppActivity>(AppActivity.startIntent(context, VerificationCodeRoute(imageUrl, verificationResultKey = key))).use {
                composeRule.onNodeWithTag("verificationCodeInput").performTextInput("draft")
                composeRule.onNodeWithTag("verificationCodeImage").performClick()
                composeRule.waitUntil(15000) { composeRule.onAllNodesWithTag("photo_image").fetchSemanticsNodes().isNotEmpty() }
                assertNull(SourceVerificationHelp.getResult(key))
                composeRule.onNodeWithTag("photo_close").performClick()
                composeRule.waitUntil(15000) { composeRule.onAllNodesWithTag("photo_viewer").fetchSemanticsNodes().isEmpty() }
                assertNull(SourceVerificationHelp.getResult(key))
                composeRule.onNodeWithTag("verificationCodeInput").assertTextContains("draft")
                composeRule.onNodeWithTag("verificationCodeSubmit").performClick()
                composeRule.waitUntil(5000) { SourceVerificationHelp.getResult(key) != null }
                assertEquals("" to "draft", SourceVerificationHelp.getResult(key))
            }
        } finally { SourceVerificationHelp.clearResult(key) }
    }

}
