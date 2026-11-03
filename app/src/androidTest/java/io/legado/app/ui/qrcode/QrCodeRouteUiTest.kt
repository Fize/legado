package io.legado.app.ui.qrcode

import android.Manifest
import android.app.Activity
import android.os.Bundle
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.ui.AppActivity
import io.legado.app.ui.navigation.QrCodeRoute
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QrCodeRouteUiTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Test
    fun routeShowsScannerAndReturnsFragmentResult() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(
            context.packageName,
            Manifest.permission.CAMERA,
        )
        val scenario = ActivityScenario.launchActivityForResult<AppActivity>(
            AppActivity.startIntent(context, QrCodeRoute),
        )

        composeRule.onNodeWithText(context.getString(R.string.scan_qr_code))
            .assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.gallery))
            .assertIsDisplayed()

        scenario.onActivity { activity ->
            activity.supportFragmentManager.setFragmentResult(
                QR_SCAN_RESULT_REQUEST,
                Bundle().apply { putString(QR_SCAN_RESULT_TEXT, "legado://emulator-result") },
            )
        }

        assertEquals(Activity.RESULT_OK, scenario.result.resultCode)
        assertEquals(
            "legado://emulator-result",
            scenario.result.resultData?.getStringExtra(QR_SCAN_RESULT_TEXT),
        )
    }
}
