package io.legado.app.ui.test

import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.help.config.ThemeConfig
import io.legado.app.utils.defaultSharedPreferences
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/** Opt-in theme override for real task-flow acceptance; restore even on failure. */
class AcceptanceThemeRule : TestRule {
    override fun apply(base: Statement, description: Description) = object : Statement() {
        override fun evaluate() {
            val theme = InstrumentationRegistry.getArguments().getString("acceptanceTheme")
            if (theme == null) { base.evaluate(); return }
            require(theme in listOf("1", "2"))
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            val prefs = context.defaultSharedPreferences
            val old = prefs.getString("themeMode", null)
            try {
                prefs.edit().putString("themeMode", theme).commit()
                instrumentation.runOnMainSync { ThemeConfig.applyDayNightInit(context) }
                base.evaluate()
            } finally {
                prefs.edit().apply { if (old == null) remove("themeMode") else putString("themeMode", old) }.commit()
                instrumentation.runOnMainSync { ThemeConfig.applyDayNightInit(context) }
            }
        }
    }
}
