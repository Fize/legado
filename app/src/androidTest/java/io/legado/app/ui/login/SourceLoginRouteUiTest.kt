package io.legado.app.ui.login

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.ui.AppActivity
import io.legado.app.ui.navigation.SourceLoginRoute
import io.legado.app.ui.navigation.SourceLoginTarget
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SourceLoginRouteUiTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val source = BookSource(
        bookSourceUrl = "https://login-route.invalid/${UUID.randomUUID()}",
        bookSourceName = "Typed login route",
        loginUrl = "/login",
    )

    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Before
    fun insertSource() = runBlocking { appDb.bookSourceDao.insert(source) }

    @After
    fun deleteSource() = runBlocking { appDb.bookSourceDao.delete(source) }

    @Test
    @OptIn(ExperimentalTestApi::class)
    fun typedBookSourceRouteShowsWebLogin() {
        val route = SourceLoginRoute(
            SourceLoginTarget.BookSource,
            key = source.bookSourceUrl,
        )

        ActivityScenario.launch<AppActivity>(AppActivity.startIntent(context, route)).use {
            val expectedTitle = context.getString(R.string.login_source, source.bookSourceName)
            composeRule.waitUntilAtLeastOneExists(hasText(expectedTitle), 10_000)
            composeRule.onNodeWithText(expectedTitle).assertIsDisplayed()
            composeRule.onNodeWithContentDescription(context.getString(R.string.ok))
                .assertIsDisplayed()
        }
    }
}
