package io.legado.app.ui.association

import androidx.annotation.StringRes
import io.legado.app.R
import io.legado.app.data.entities.BookSource
import io.legado.app.constant.AppPattern
import io.legado.app.utils.splitNotBlank

internal fun matchesSourceImportSearch(
    query: String,
    name: String?,
    url: String?,
    group: String?,
    comment: String?,
): Boolean {
    if (query.isEmpty()) return true
    if (query.startsWith("group:")) {
        val wanted = query.substringAfter("group:").trim()
        return group?.splitNotBlank(AppPattern.splitGroupRegex)
            ?.any { it.trim() == wanted } == true
    }
    return name?.contains(query, ignoreCase = true) == true ||
        url?.contains(query, ignoreCase = true) == true ||
        group?.contains(query, ignoreCase = true) == true ||
        comment?.contains(query, ignoreCase = true) == true
}

/** Labels are presentation; a selected preset keeps its meaning when the locale changes. */
enum class SourceImportPreset(@StringRes val labelId: Int) {
    Enabled(R.string.enabled), Disabled(R.string.disabled), NeedsLogin(R.string.need_login),
    NoGroup(R.string.no_group), ExploreEnabled(R.string.enabled_explore), ExploreDisabled(R.string.disabled_explore);

    fun matches(source: BookSource): Boolean = when (this) {
        Enabled -> source.enabled
        Disabled -> !source.enabled
        NeedsLogin -> !source.loginUrl.isNullOrBlank() || (source.isJsSource() && source.hasLoginForm())
        NoGroup -> source.bookSourceGroup.isNullOrBlank() || source.bookSourceGroup?.trim() == "未分组"
        ExploreEnabled -> source.enabledExplore
        ExploreDisabled -> !source.enabledExplore
    }
}
