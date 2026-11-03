package io.legado.app.ui.login

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.ui.AppActivity
import io.legado.app.ui.navigation.SourceLoginRoute
import io.legado.app.ui.navigation.SourceLoginTarget
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SourceLoginEmptyToggleUiTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val source = BookSource(
        bookSourceUrl = "https://empty-toggle-${UUID.randomUUID()}.invalid",
        bookSourceName = "Toggle fixture",
        loginUi = """[{"name":"EmptyToggle","type":"toggle","chars":[]},{"name":"NullToggle","type":"toggle","chars":[null]},{"name":"NormalToggle","type":"toggle","chars":["off-","on-"]}]""",
    )
    @get:Rule val compose = createEmptyComposeRule()

    @Test @OptIn(ExperimentalTestApi::class)
    fun emptyChoicesAreDisabledAndNormalChoicesStillCycleAfterRecreation() {
        appDb.bookSourceDao.insert(source)
        val route = SourceLoginRoute(SourceLoginTarget.BookSource, key = source.bookSourceUrl)
        ActivityScenario.launch<AppActivity>(AppActivity.startIntent(context, route)).use { scenario ->
            repeat(2) { round ->
                compose.waitUntilAtLeastOneExists(hasText("EmptyToggle", substring = true), 15_000)
                for (label in listOf("EmptyToggle", "NullToggle")) {
                    val node = compose.onNode(hasText(label, substring = true) and hasClickAction())
                    node.assertIsNotEnabled()
                    node.performTouchInput { click() }
                }
                val before = if (round == 0) "off-NormalToggle" else "on-NormalToggle"
                val after = if (round == 0) "on-NormalToggle" else "off-NormalToggle"
                compose.onNode(hasText(before) and hasClickAction()).performClick()
                compose.waitUntilAtLeastOneExists(hasText(after), 10_000)
                if (round == 0) scenario.recreate()
            }
        }
    }

    @After fun cleanup() { source.removeLoginInfo(); appDb.bookSourceDao.delete(source) }
}
