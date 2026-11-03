package io.legado.app.ui.book.manga

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import androidx.compose.runtime.saveable.rememberSaveable

/** Stateless reader chrome and image surface, shared by the Navigation destination. */
@Composable
internal fun MangaReaderSurface(
    state: MangaReaderUiState,
    showTitleBarAddition: Boolean,
    eInkMode: Boolean,
    onAction: (MangaReaderAction) -> Unit,
    overlay: @Composable () -> Unit = {},
) {
    var showOverflowMenu by rememberSaveable { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val footer = mangaFooter(state)
    LaunchedEffect(state.noticeMessage) {
        state.noticeMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            onAction(MangaReaderAction.NoticeHandled(message))
        }
    }
        Box(modifier = Modifier.fillMaxSize()) {
            MangaReaderContent(
                state = state,
                onVisibleItem = { page, content, layout -> onAction(MangaReaderAction.VisibleItem(page, content, layout)) },
                onCommandHandled = { onAction(MangaReaderAction.CommandHandled(it)) },
                onMiddleTap = {
                    if (!state.menuVisible && !state.loadingVisible) onAction(MangaReaderAction.ShowMenu(true))
                },
                onPreviousPage = { onAction(MangaReaderAction.TurnPage(-1)) },
                onNextPage = { onAction(MangaReaderAction.TurnPage(1)) },
                onSaveImage = { if (state.longClickSaveEnabled) onAction(MangaReaderAction.SaveImage(it)) },
                onLoadNextChapter = {
                    if (state.hasNextChapter) onAction(MangaReaderAction.LoadNext)
                },
            )

            if (footer.visible && footer.text.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp)
                        .padding(bottom = 10.dp),
                    contentAlignment = if (footer.centered) Alignment.Center else Alignment.CenterStart
                ) {
                    Text(
                        text = footer.text,
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        style = MaterialTheme.typography.labelSmall.copy(
                            shadow = androidx.compose.ui.graphics.Shadow(
                                color = Color.Black,
                                blurRadius = 4f
                            )
                        )
                    )
                }
            }

            if (state.menuVisible) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { onAction(MangaReaderAction.ShowMenu(false)) }
                )
            }

            AnimatedVisibility(
                visible = state.menuVisible,
                enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter)
            ) {
                MangaTopMenuBar(
                    bookTitle = state.bookTitle,
                    chapterName = state.chapterName,
                    chapterUrl = state.chapterUrl,
                    sourceName = state.sourceName.ifBlank { stringResource(R.string.book_source) },
                    showAddition = showTitleBarAddition,
                    showOverflowMenu = showOverflowMenu,
                    onShowOverflowMenuChange = { showOverflowMenu = it },
                    onBack = { onAction(MangaReaderAction.Back) },
                    onTitleClick = { onAction(MangaReaderAction.BookInfo) },
                    onChapterClick = { onAction(MangaReaderAction.ChapterLink) },
                    onChapterLongClick = { onAction(MangaReaderAction.ChapterLinkMode) },
                    onChangeSource = {
                        onAction(MangaReaderAction.ChangeSource)
                    },
                    onRefresh = {
                        onAction(MangaReaderAction.Refresh)
                    },
                    onOpenCatalog = {
                        onAction(MangaReaderAction.Catalog)
                    },
                    overflowMenuContent = {
                        MangaOverflowMenuItems(state, onDismiss = { showOverflowMenu = false }) {
                            onAction(MangaReaderAction.Menu(it))
                        }
                    }
                )
            }

            AnimatedVisibility(
                visible = state.menuVisible,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                MangaBottomMenuBar(
                    isRightToLeft = state.rightToLeft,
                    pageValue = state.seekPage,
                    pageCount = state.seekPageCount,
                    onPrevChapter = { onAction(MangaReaderAction.PreviousChapter) },
                    onNextChapter = { onAction(MangaReaderAction.NextChapter) },
                    onSeekPage = { onAction(MangaReaderAction.SeekPage(it)) }
                )
            }

            if (state.loadingVisible) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val errorMsg = state.loadingErrorMessageRes?.let { stringResource(it) }
                        ?: state.loadingErrorMessage
                    if (errorMsg == null) {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            if (!eInkMode) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(36.dp),
                                    strokeWidth = 3.dp,
                                    strokeCap = StrokeCap.Round
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                            }
                            Text(
                                text = stringResource(R.string.loading),
                                color = MaterialTheme.colorScheme.onBackground
                            )
                        }
                    } else {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = errorMsg,
                                color = MaterialTheme.colorScheme.onBackground,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 24.dp)
                            )
                            if (state.canRetryLoading) {
                                TextButton(
                                    onClick = {
                                        onAction(MangaReaderAction.Retry)
                                    },
                                    modifier = Modifier.padding(16.dp)
                                ) {
                                    Text(
                                        text = stringResource(R.string.retry),
                                        fontSize = 18.sp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }
            }

            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter),
            )

            overlay()
        }

}
