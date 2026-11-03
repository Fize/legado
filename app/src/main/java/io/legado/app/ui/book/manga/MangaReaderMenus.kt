package io.legado.app.ui.book.manga

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import kotlin.math.roundToInt
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun MangaTopMenuBar(
    bookTitle: String,
    chapterName: String,
    chapterUrl: String,
    sourceName: String,
    showAddition: Boolean,
    showOverflowMenu: Boolean,
    onShowOverflowMenuChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    onTitleClick: () -> Unit,
    onChapterClick: () -> Unit,
    onChapterLongClick: () -> Unit,
    onChangeSource: () -> Unit,
    onRefresh: () -> Unit,
    onOpenCatalog: () -> Unit,
    overflowMenuContent: @Composable () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
        ) {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                title = {
                    Text(
                        text = bookTitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable(onClick = onTitleClick)
                    )
                },
                actions = {
                    IconButton(onClick = onChangeSource) {
                        Icon(
                            Icons.Default.SwapHoriz,
                            contentDescription = stringResource(R.string.change_origin)
                        )
                    }
                    IconButton(onClick = onRefresh) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.refresh)
                        )
                    }
                    IconButton(onClick = onOpenCatalog) {
                        Icon(
                            Icons.AutoMirrored.Filled.List,
                            contentDescription = stringResource(R.string.chapter_list)
                        )
                    }
                    Box {
                        IconButton(onClick = { onShowOverflowMenuChange(true) }) {
                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.reader_menu_more))
                        }
                        DropdownMenu(
                            expanded = showOverflowMenu,
                            onDismissRequest = { onShowOverflowMenuChange(false) }
                        ) {
                            overflowMenuContent()
                        }
                    }
                }
            )
            if (showAddition && (chapterName.isNotEmpty() || chapterUrl.isNotEmpty() || sourceName.isNotEmpty())) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .combinedClickable(
                                onClick = onChapterClick,
                                onLongClick = onChapterLongClick
                            )
                    ) {
                        if (chapterName.isNotEmpty()) {
                            Text(
                                text = chapterName,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (chapterUrl.isNotEmpty()) {
                            Text(
                                text = chapterUrl,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    if (sourceName.isNotEmpty()) {
                        Surface(
                            color = MaterialTheme.colorScheme.primary,
                            shape = RoundedCornerShape(2.dp),
                            modifier = Modifier.padding(start = 8.dp)
                        ) {
                            Text(
                                text = sourceName,
                                color = MaterialTheme.colorScheme.onPrimary,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MangaCheckableMenuItem(
    title: String,
    checked: Boolean,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        modifier = Modifier.semantics(mergeDescendants = true) {
            role = Role.Checkbox
            toggleableState = if (checked) ToggleableState.On else ToggleableState.Off
        },
        text = { Text(title) },
        trailingIcon = {
            Checkbox(checked = checked, onCheckedChange = null)
        },
        onClick = onClick
    )
}

@Composable
internal fun MangaBottomMenuBar(
    isRightToLeft: Boolean,
    pageValue: Int,
    pageCount: Int,
    onPrevChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onSeekPage: (Int) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp
    ) {
        CompositionLocalProvider(
            LocalLayoutDirection provides if (isRightToLeft) LayoutDirection.Rtl else LayoutDirection.Ltr
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onPrevChapter) {
                    Text(
                        text = stringResource(R.string.previous_chapter),
                        fontSize = 14.sp
                    )
                }
                val maxPage = (pageCount - 1).coerceAtLeast(0)
                Slider(
                    value = pageValue.coerceIn(0, maxPage).toFloat(),
                    onValueChange = {
                        onSeekPage(it.roundToInt().coerceIn(0, maxPage))
                    },
                    valueRange = 0f..maxPage.coerceAtLeast(1).toFloat(),
                    enabled = maxPage > 0,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp)
                )
                TextButton(onClick = onNextChapter) {
                    Text(
                        text = stringResource(R.string.next_chapter),
                        fontSize = 14.sp
                    )
                }
            }
        }
    }
}


/** Menu renders the current snapshot and emits typed intentions; it owns no reader or settings. */
@Composable
internal fun MangaOverflowMenuItems(
    state: MangaReaderUiState,
    onDismiss: () -> Unit,
    onAction: (MangaReaderMenuAction) -> Unit,
) {
    @Composable fun action(title: String, action: MangaReaderMenuAction) {
        DropdownMenuItem(text = { Text(title) }, onClick = { onDismiss(); onAction(action) })
    }
    @Composable fun setting(title: String, enabled: Boolean, setting: MangaReaderSetting) {
        MangaCheckableMenuItem(title, enabled) { onAction(MangaReaderMenuAction.Toggle(setting)) }
    }
    action(stringResource(R.string.offline_cache), MangaReaderMenuAction.Download)
    action(stringResource(R.string.pre_download_m, state.preDownloadCount), MangaReaderMenuAction.Preload)
    setting(stringResource(R.string.disable_manga_scale), state.scaleDisabled, MangaReaderSetting.ScaleDisabled)
    setting(stringResource(R.string.manga_long_click_save_image), state.longClickSaveEnabled, MangaReaderSetting.LongPressSave)
    setting(stringResource(R.string.disable_manga_click_scroll), state.clickScrollDisabled, MangaReaderSetting.ClickScrollDisabled)
    MangaCheckableMenuItem(stringResource(R.string.enable_auto_page_scroll), state.autoPageEnabled) { onAction(MangaReaderMenuAction.AutoPage) }
    MangaCheckableMenuItem(stringResource(R.string.enable_auto_scroll), state.autoScrollEnabled) { onAction(MangaReaderMenuAction.AutoScroll) }
    if (state.autoPageEnabled || state.autoScrollEnabled) {
        action(stringResource(R.string.manga_auto_page_speed, state.autoPageSpeed), MangaReaderMenuAction.AutoSpeed)
    }
    setting(stringResource(R.string.enable_manga_horizontal_scroll), state.horizontalScrollEnabled, MangaReaderSetting.Horizontal)
    setting(stringResource(R.string.enable_manga_double_page), state.doublePageEnabled, MangaReaderSetting.DoublePage)
    if (state.horizontal) {
        setting(stringResource(R.string.manga_right_to_left), state.rightToLeftEnabled, MangaReaderSetting.RightToLeft)
        if (!state.pageAnimationDisabled) {
            setting(stringResource(R.string.disable_horizontal_page_snap), state.horizontalPageSnapDisabled, MangaReaderSetting.SnapDisabled)
        }
    }
    setting(stringResource(R.string.disable_manga_page_anim), state.pageAnimationDisabled, MangaReaderSetting.AnimationDisabled)
    action(stringResource(R.string.manga_footer_config), MangaReaderMenuAction.Footer)
    action(stringResource(R.string.manga_color_filter), MangaReaderMenuAction.ColorFilter)
    setting(stringResource(R.string.hide_manga_title), state.titleHidden, MangaReaderSetting.TitleHidden)
    setting(stringResource(R.string.manga_epaper), state.eInkEnabled, MangaReaderSetting.EInk)
    if (state.eInkEnabled) action(stringResource(R.string.manga_epaper_stting), MangaReaderMenuAction.Epaper)
    setting(stringResource(R.string.enable_manga_gray), state.grayEnabled, MangaReaderSetting.Gray)
}
