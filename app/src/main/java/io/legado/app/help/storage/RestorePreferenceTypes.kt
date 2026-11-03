package io.legado.app.help.storage

import io.legado.app.constant.PreferKey

// Strict Framework getters used during restore, theme/cover refresh, and the
// AppConfig preference listener. Validate types, not UI ranges or enum values.
private val eagerIntegerPreferences = setOf(
    PreferKey.readStyleSelect, PreferKey.comicStyleSelect, PreferKey.autoReadSpeed,
    PreferKey.editFontScale, PreferKey.editNonPrintable, PreferKey.showBoardLine,
    PreferKey.editTheme, PreferKey.editThemeDark,
    PreferKey.clickActionTL, PreferKey.clickActionTC, PreferKey.clickActionTR,
    PreferKey.clickActionML, PreferKey.clickActionMC, PreferKey.clickActionMR,
    PreferKey.clickActionBL, PreferKey.clickActionBC, PreferKey.clickActionBR,
    PreferKey.cPrimary, PreferKey.cAccent, PreferKey.cBackground, PreferKey.cBBackground,
    PreferKey.cNPrimary, PreferKey.cNAccent, PreferKey.cNBackground, PreferKey.cNBBackground,
    PreferKey.coverTitleLargeSize, PreferKey.coverTitleSmallSize,
    PreferKey.coverAuthorLargeSize, PreferKey.coverAuthorSmallSize,
)
private val eagerStringPreferences = setOf(
    PreferKey.themeMode, PreferKey.userAgent, PreferKey.customHosts,
)

/** Call with the effective (non-ignored) map before any persistent restore writes. */
internal fun validateEagerRestorePreferenceTypes(values: Map<String, *>) {
    values.forEach { (key, value) ->
        // XML null entries are ignored by the existing writer; unknown keys and
        // compatible Boolean/string conversions retain their existing behavior.
        if (value != null) {
            when (key) {
                in eagerIntegerPreferences -> require(value is Int) { "config.xml 配置项 $key 需要整数类型" }
                in eagerStringPreferences -> require(value is String) { "config.xml 配置项 $key 需要字符串类型" }
            }
        }
    }
}
