package io.legado.app.ui.login

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.TextRange
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.model.login.LoginUiV2
import io.legado.app.ui.AppActivity
import io.legado.app.ui.navigation.SourceLoginRoute
import io.legado.app.ui.navigation.SourceLoginTarget
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import org.json.JSONObject
import android.os.SystemClock

@RunWith(AndroidJUnit4::class)
class SourceLoginV2RecreationUiTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val source = BookSource(
        bookSourceUrl = "https://login-v2-${UUID.randomUUID()}.invalid",
        bookSourceName = "V2 login fixture",
        loginUi = LoginUiV2.MARKER,
        loginUrl = """
            function loginUi(state) {
                if (!state.step) return {rows:[{key:'account',name:'Account',type:'text'},
                    {name:'Next',type:'button',action:'advance',countdown:30}]};
                return {rows:[{key:'code',name:'Code',type:'text'},
                    {name:'Next',type:'button',action:'advance',countdown:30},
                    {name:'Finish',type:'button',action:'finish'}]};
            }
            function loginAction(action,state,form) {
                if (action == 'advance') return {state:{step:'code'}};
                return {login:{code:form.code},close:true};
            }
        """.trimIndent(),
    )
    @get:Rule val compose = createEmptyComposeRule()

    @Test @OptIn(ExperimentalTestApi::class)
    fun currentStepDraftAndSelectionSurviveRecreation() {
        appDb.bookSourceDao.insert(source)
        val route = SourceLoginRoute(SourceLoginTarget.BookSource, key = source.bookSourceUrl)
        ActivityScenario.launch<AppActivity>(AppActivity.startIntent(context, route)).use { scenario ->
            compose.waitUntilAtLeastOneExists(hasText("Account"), 15_000)
            compose.onNode(hasSetTextAction()).performTextReplacement("account-fixture")
            compose.onNodeWithText("Next").performClick()
            compose.waitUntilAtLeastOneExists(hasText("Code"), 15_000)
            compose.onNode(hasText("Next", substring = true) and hasClickAction()).assertIsNotEnabled()
            compose.onNode(hasSetTextAction()).performTextReplacement("code-fixture")
            compose.onNode(hasSetTextAction()).performTextInputSelection(TextRange(2, 6))
            repeat(2) {
                scenario.recreate()
                compose.waitUntilAtLeastOneExists(hasText("Code"), 15_000)
                compose.onNode(hasSetTextAction()).assert(SemanticsMatcher("exact V2 draft") { node ->
                    node.config.getOrNull(SemanticsProperties.EditableText)?.text == "code-fixture"
                })
                compose.onNode(hasSetTextAction()).assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(2, 6)))
                compose.onNode(hasText("Next", substring = true) and hasClickAction()).assertIsNotEnabled()
            }
            assertNull(source.getLoginInfo())
            compose.onNode(hasSetTextAction()).performTextInput("X")
            compose.onNode(hasSetTextAction()).assert(SemanticsMatcher("selection replaced") { node ->
                node.config.getOrNull(SemanticsProperties.EditableText)?.text == "coXixture"
            })
            compose.onNodeWithText("Finish").performClick()
            compose.waitUntil(10_000) {
                runCatching { JSONObject(source.getLoginInfo().orEmpty()).getString("code") == "coXixture" }
                    .getOrDefault(false)
            }
        }
        assertEquals("coXixture", JSONObject(source.getLoginInfo().orEmpty()).getString("code"))
    }

    @Test @OptIn(ExperimentalTestApi::class)
    fun countdownRemainsActiveAndExpiresAfterRecreation() {
        val shortSource = source.copy(loginUrl = source.loginUrl!!
            .replace("countdown:30", "countdown:15")
            .replace("return {rows:[{key:'code'", "Packages.java.lang.Thread.sleep(1200); return {rows:[{key:'code'"))
        appDb.bookSourceDao.insert(shortSource)
        val route = SourceLoginRoute(SourceLoginTarget.BookSource, key = source.bookSourceUrl)
        ActivityScenario.launch<AppActivity>(AppActivity.startIntent(context, route)).use { scenario ->
            compose.waitUntilAtLeastOneExists(hasText("Account"), 15_000)
            val started = SystemClock.elapsedRealtime()
            compose.onNodeWithText("Next").performClick()
            compose.waitUntilAtLeastOneExists(hasText("Code"), 15_000)
            val countdown = compose.onNode(hasText("Next", substring = true) and hasClickAction())
            countdown.assertIsNotEnabled()
            scenario.recreate()
            scenario.recreate()
            compose.waitUntilAtLeastOneExists(hasText("Code"), 15_000)
            countdown.assertIsNotEnabled()
            compose.waitUntil(20_000) { runCatching { countdown.assertIsEnabled() }.isSuccess }
            assertTrue("Countdown was extended by recreation", SystemClock.elapsedRealtime() - started < 20_000)
        }
    }

    @After fun cleanup() { source.removeLoginInfo(); appDb.bookSourceDao.delete(source) }
}
