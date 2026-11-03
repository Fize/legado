package io.legado.app.ui.font

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.ui.AppActivity
import io.legado.app.ui.navigation.SettingsRoute
import io.legado.app.ui.navigation.SettingsSection
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FontSelectionInflationTest {

    @Test
    fun coverFontPickerRendersInTheComposeSettingsRoute() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ActivityScenario.launch<AppActivity>(
            AppActivity.startIntent(context, SettingsRoute(SettingsSection.CoverFont))
        ).use {
            onView(withText(R.string.cover_font_select)).perform(click())
            onView(withText(R.string.select_font)).check(matches(isDisplayed()))
            onView(withText(R.string.default_font)).check(matches(isDisplayed()))
            onView(withText(R.string.import_str)).check(matches(isDisplayed()))
        }
    }
}
