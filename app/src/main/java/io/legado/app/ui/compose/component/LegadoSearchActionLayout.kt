package io.legado.app.ui.compose.component

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.dp

/** Stable editor composition with stacked or side-by-side search and actions. */
@Composable
fun LegadoSearchActionLayout(
    compact: Boolean,
    modifier: Modifier,
    topBar: @Composable () -> Unit,
    searchBar: @Composable () -> Unit,
    bottomBar: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    // Keep each child in one composition slot while changing its placement.
    // Moving an editor between Scaffold subcompositions drops its IME session.
    Layout(modifier = modifier, content = {
        Box { topBar() }
        Box { searchBar() }
        Box { bottomBar() }
        Box { content() }
    }) { children, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val top = children[0].measure(loose)
        val footerWidth = if (compact) minOf(240.dp.roundToPx(), width / 2) else width
        val footer = children[2].measure(loose.copy(minWidth = footerWidth, maxWidth = footerWidth))
        val searchWidth = if (compact) width - footerWidth else width
        val search = children[1].measure(loose.copy(minWidth = searchWidth, maxWidth = searchWidth))
        val headerHeight = if (compact) maxOf(search.height, footer.height) else top.height + search.height
        val bodyHeight = (height - headerHeight - if (compact) 0 else footer.height).coerceAtLeast(0)
        val body = children[3].measure(loose.copy(minWidth = width, minHeight = bodyHeight, maxHeight = bodyHeight))
        layout(width, height) {
            top.placeRelative(0, 0)
            search.placeRelative(0, if (compact) (headerHeight - search.height) / 2 else top.height)
            footer.placeRelative(if (compact) searchWidth else 0,
                if (compact) (headerHeight - footer.height) / 2 else height - footer.height)
            body.placeRelative(0, headerHeight)
        }
    }
}
