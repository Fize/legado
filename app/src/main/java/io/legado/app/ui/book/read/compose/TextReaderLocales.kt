package io.legado.app.ui.book.read.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.intl.LocaleList

/** Configuration locales belong to Composition; neither platform locales nor layouts enter state. */
@Composable
internal fun rememberTextReaderLocales(): LocaleList {
    val tags = LocalConfiguration.current.locales.toLanguageTags()
    return remember(tags) { if (tags.isBlank()) LocaleList.current else LocaleList(tags) }
}
