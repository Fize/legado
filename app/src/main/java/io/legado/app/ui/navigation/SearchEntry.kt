package io.legado.app.ui.navigation

import android.app.Activity
import android.content.Context
import android.content.Intent
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.data.repository.SearchScopeSpec
import io.legado.app.ui.AppActivity

fun Context.openBookSearch(query: String? = null, scope: String? = null) {
    val intent = AppActivity.startIntent(this, SearchRoute(query.orEmpty(), scope))
    if (this !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    startActivity(intent)
}
fun Context.openBookSearch(source: BookSource, query: String? = null) =
    openBookSearch(query, SearchScopeSpec.source(source.bookSourceName, source.bookSourceUrl).raw)
fun Context.openBookSearch(source: BookSourcePart, query: String? = null) =
    openBookSearch(query, SearchScopeSpec.source(source.bookSourceName, source.bookSourceUrl).raw)
