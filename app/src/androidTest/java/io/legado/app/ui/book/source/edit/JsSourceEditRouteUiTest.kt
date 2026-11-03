package io.legado.app.ui.book.source.edit

import android.app.Activity
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.legado.app.ui.AppActivity
import io.legado.app.ui.code.CodeEditActivity
import io.legado.app.ui.navigation.JavaScriptSourceEditRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class JsSourceEditRouteUiTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Test
    fun routeLaunchesEditorAndPropagatesCancellation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val scenario = ActivityScenario.launchActivityForResult<AppActivity>(
            AppActivity.startIntent(context, JavaScriptSourceEditRoute()),
        )
        var editor: CodeEditActivity? = null

        composeRule.waitUntil(timeoutMillis = 10_000) {
            instrumentation.runOnMainSync {
                editor = ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<CodeEditActivity>()
                    .firstOrNull()
            }
            editor != null
        }

        assertNotNull(editor)
        instrumentation.runOnMainSync { editor?.finish() }
        assertEquals(Activity.RESULT_CANCELED, scenario.result.resultCode)
    }
}
