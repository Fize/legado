package io.legado.app.ui.book.info.intro

internal fun introCanCollapse(fullHeight: Int, collapsedHeight: Int) = collapsedHeight > 0 && fullHeight > collapsedHeight
internal fun introDisplayHeight(expanded: Boolean, fullHeight: Int, collapsedHeight: Int): Int =
    if (expanded) fullHeight.coerceAtLeast(0) else minOf(fullHeight.coerceAtLeast(0), collapsedHeight.coerceAtLeast(0))
