package io.legado.app.ui.book.manga

import io.legado.app.data.repository.MangaReaderPreferences

internal enum class MangaReaderSetting {
    ScaleDisabled, LongPressSave, ClickScrollDisabled, Horizontal, DoublePage,
    RightToLeft, SnapDisabled, AnimationDisabled, TitleHidden, EInk, Gray,
}

internal sealed interface MangaReaderAction {
    data class VisibleItem(val item: io.legado.app.ui.book.manga.entities.BaseMangaPage,
        val contentVersion: Int, val layoutVersion: Int) : MangaReaderAction
    data class CommandHandled(val version: Long) : MangaReaderAction
    data class ShowMenu(val visible: Boolean) : MangaReaderAction
    data class TurnPage(val direction: Int) : MangaReaderAction
    data class SaveImage(val source: String) : MangaReaderAction
    data class SeekPage(val index: Int) : MangaReaderAction
    data class Menu(val action: MangaReaderMenuAction) : MangaReaderAction
    data class NoticeHandled(val message: String) : MangaReaderAction
    data object LoadNext : MangaReaderAction
    data object Back : MangaReaderAction
    data object BookInfo : MangaReaderAction
    data object ChapterLink : MangaReaderAction
    data object ChapterLinkMode : MangaReaderAction
    data object ChangeSource : MangaReaderAction
    data object Refresh : MangaReaderAction
    data object Catalog : MangaReaderAction
    data object PreviousChapter : MangaReaderAction
    data object NextChapter : MangaReaderAction
    data object Retry : MangaReaderAction
}

internal sealed interface MangaReaderMenuAction {
    data class Toggle(val setting: MangaReaderSetting) : MangaReaderMenuAction
    data object AutoPage : MangaReaderMenuAction
    data object AutoScroll : MangaReaderMenuAction
    data object Download : MangaReaderMenuAction
    data object Preload : MangaReaderMenuAction
    data object AutoSpeed : MangaReaderMenuAction
    data object Footer : MangaReaderMenuAction
    data object ColorFilter : MangaReaderMenuAction
    data object Epaper : MangaReaderMenuAction
}

internal fun MangaReaderPreferences.toggle(setting: MangaReaderSetting): MangaReaderPreferences = when (setting) {
    MangaReaderSetting.ScaleDisabled -> copy(scaleDisabled = !scaleDisabled)
    MangaReaderSetting.LongPressSave -> copy(longClickSaveEnabled = !longClickSaveEnabled)
    MangaReaderSetting.ClickScrollDisabled -> copy(clickScrollDisabled = !clickScrollDisabled)
    MangaReaderSetting.Horizontal -> copy(horizontalScrollEnabled = !horizontalScrollEnabled)
    MangaReaderSetting.DoublePage -> copy(doublePageEnabled = !doublePageEnabled)
    MangaReaderSetting.RightToLeft -> copy(rightToLeftEnabled = !rightToLeftEnabled)
    MangaReaderSetting.SnapDisabled -> copy(horizontalPageSnapDisabled = !horizontalPageSnapDisabled)
    MangaReaderSetting.AnimationDisabled -> copy(pageAnimationDisabled = !pageAnimationDisabled)
    MangaReaderSetting.TitleHidden -> copy(titleHidden = !titleHidden)
    MangaReaderSetting.EInk -> copy(eInkEnabled = !eInkEnabled, grayEnabled = false)
    MangaReaderSetting.Gray -> copy(grayEnabled = !grayEnabled, eInkEnabled = false)
}

internal enum class MangaAutomaticReading { Off, Page, Continuous }

internal fun automaticMangaCommand(state: MangaReaderUiState): MangaReaderCommand? = when {
    state.loadingVisible || state.menuVisible || state.dialog != null -> null
    state.autoPageEnabled -> MangaReaderCommand.TurnPage(1, state.pageAnimationEnabled)
    state.autoScrollEnabled -> MangaReaderCommand.ScrollBy(state.autoPageSpeed.coerceAtLeast(1).toFloat())
    else -> null
}

internal data class MangaAutomaticRecipe(val page: Boolean, val continuous: Boolean,
    val speed: Int, val menu: Boolean, val loading: Boolean, val dialog: Boolean)
internal fun automaticMangaRecipe(state: MangaReaderUiState) = MangaAutomaticRecipe(
    state.autoPageEnabled, state.autoScrollEnabled, state.autoPageSpeed, state.menuVisible, state.loadingVisible, state.dialog != null)
