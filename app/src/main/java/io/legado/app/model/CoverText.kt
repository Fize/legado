package io.legado.app.model

import io.legado.app.constant.AppPattern

internal fun normalizeCoverText(value: String?, keepPunctuation: Boolean): String? =
    value?.let { if (keepPunctuation) it.trim() else it.replace(AppPattern.bdRegex, "").trim() }
