package io.legado.app.ui.config

/** Resource text is resolved by the caller's current composition configuration. */
internal fun backupPathSummary(selected: String?, defaultPath: String, defaultLabel: String): String =
    selected?.takeIf(String::isNotBlank) ?: "$defaultLabel\n$defaultPath"
