package io.legado.app.ui.book.manga

import androidx.compose.runtime.Composable

/** Complete reader UI, independent of an Activity and ready to be hosted by a navigation entry. */
@Composable
internal fun MangaReaderScreen(
    state: MangaReaderUiState,
    showTitleBarAddition: Boolean,
    eInkMode: Boolean,
    onAction: (MangaReaderAction) -> Unit,
    onDialogAction: (MangaReaderDialogAction) -> Unit,
    overlay: @Composable () -> Unit = {},
) {
    MangaReaderSurface(state, showTitleBarAddition, eInkMode, onAction, overlay)
    MangaReaderDialogs(state, onDialogAction)
}
