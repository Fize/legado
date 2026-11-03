package io.legado.app.ui.book.changesource

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.legado.app.R
import io.legado.app.data.entities.SearchBook
import kotlinx.coroutines.launch

@Composable
internal fun ChangeBookSourceScreen(
    viewModel: ChangeBookSourceViewModel,
    oldBookType: Int,
    oldBookUrl: String?,
    onBack: () -> Unit,
    onSourceManage: () -> Unit,
    onEditSource: (SearchBook) -> Unit,
    onResult: (SourceChangeResult) -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(uiState.scrollRevision) {
        val index = uiState.scrollTargetIndex ?: return@LaunchedEffect
        if (index in uiState.searchItems.indices) listState.scrollToItem(index)
    }

    LaunchedEffect(uiState.changeSourceResultRevision) {
        viewModel.consumeChangeSourceResult(uiState.changeSourceResultRevision)?.let(onResult)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colorResource(R.color.background))
    ) {
        ChangeSourceTopBar(
            title = viewModel.name,
            subtitle = viewModel.author,
            showNavigationIcon = true,
            onBack = onBack,
            isSearching = uiState.isSearching,
            searchQuery = uiState.searchQuery,
            onSearchQueryChange = viewModel::screen,
            onStartStopClick = viewModel::startOrStopSearch,
            onSourceManageClick = onSourceManage,
            showRefreshList = true,
            onRefreshListClick = { viewModel.startRefreshList() },
            showClose = true,
            onCloseClick = onBack,
            checkAuthor = uiState.checkAuthor,
            onCheckAuthorChange = viewModel::setCheckAuthor,
            loadInfo = uiState.loadInfo,
            onLoadInfoChange = viewModel::setLoadInfo,
            loadToc = uiState.loadToc,
            onLoadTocChange = viewModel::setLoadToc,
            loadWordCount = uiState.loadWordCount,
            onLoadWordCountChange = viewModel::setLoadWordCount,
            sortRespondTime = uiState.sortRespondTime,
            onSortRespondTimeChange = viewModel::setSortRespondTime,
            wordCountFilterEnabled =
                uiState.wordCountFilterMode != ChangeSourceResultOptions.FILTER_OFF,
            onWordCountFilterClick = {
                viewModel.setWordCountFilterDialogVisible(true)
            },
            groups = uiState.groups,
            selectedGroup = uiState.selectedGroup,
            onGroupSelect = viewModel::selectGroup,
        )

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            items(items = uiState.searchItems, key = { it.bookUrl }) { item ->
                ChangeSourceItemRow(
                    item = item,
                    isCurrent = uiState.currentBookUrl == item.bookUrl,
                    showSelectedHighlight = true,
                    score = viewModel.getBookScore(item),
                    loadWordCount = uiState.loadWordCount,
                    onClick = {
                        if (item.bookUrl != oldBookUrl) {
                            viewModel.requestChangeSource(item, oldBookType)
                        }
                    },
                    onScoreChange = { viewModel.setBookScore(item, it) },
                    onTopSource = { viewModel.topSource(item) },
                    onBottomSource = { viewModel.bottomSource(item) },
                    onEditSource = { onEditSource(item) },
                    onDisableSource = { viewModel.disableSource(item) },
                    onDeleteSource = {
                        if (oldBookUrl == item.bookUrl) {
                            viewModel.autoChangeSource(oldBookType, item)
                        } else {
                            viewModel.del(item)
                        }
                    },
                )
            }
        }

        ChangeSourceBottomBar(
            statusText = if (
                uiState.progressCount == 0 && uiState.progressSourceName.isEmpty()
            ) {
                uiState.currentOriginName
            } else {
                stringResource(
                    R.string.change_source_progress,
                    uiState.searchItems.size,
                    uiState.progressCount,
                    viewModel.totalSourceCount,
                    uiState.progressSourceName,
                )
            },
            onDurClick = { viewModel.requestScrollCurrentSource() },
            onScrollTop = {
                if (uiState.searchItems.isNotEmpty()) {
                    coroutineScope.launch { listState.scrollToItem(0) }
                }
            },
            onScrollBottom = {
                if (uiState.searchItems.isNotEmpty()) {
                    coroutineScope.launch { listState.scrollToItem(uiState.searchItems.lastIndex) }
                }
            },
        )
    }

    if (uiState.showWordCountFilterDialog) {
        ChangeSourceWordCountFilterDialog(
            onDismiss = { viewModel.setWordCountFilterDialogVisible(false) },
            onChanged = viewModel::onWordCountFilterChanged,
        )
    }

    if (uiState.showEmptyGroupDialog) {
        AlertDialog(
            onDismissRequest = viewModel::dismissEmptyGroupDialog,
            title = { Text("搜索结果为空") },
            text = { Text("${uiState.selectedGroup}分组搜索结果为空，是否切换到全部分组") },
            confirmButton = {
                TextButton(onClick = viewModel::searchAllGroups) {
                    Text(stringResource(R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissEmptyGroupDialog) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (uiState.showRelativeWordCountWarning) {
        AlertDialog(
            onDismissRequest = viewModel::dismissRelativeWordCountWarning,
            text = {
                Text(stringResource(R.string.change_source_relative_word_count_unavailable))
            },
            confirmButton = {
                TextButton(onClick = viewModel::dismissRelativeWordCountWarning) {
                    Text(stringResource(R.string.ok))
                }
            },
        )
    }

    if (uiState.typeMismatchBook != null) {
        AlertDialog(
            onDismissRequest = viewModel::dismissTypeMismatch,
            title = { Text(stringResource(R.string.book_type_different)) },
            text = { Text(stringResource(R.string.soure_change_source)) },
            confirmButton = {
                TextButton(onClick = viewModel::confirmTypeMismatch) {
                    Text(stringResource(R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissTypeMismatch) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (uiState.changeSourceLoading) {
        AlertDialog(
            onDismissRequest = {
                if (uiState.changeSourceCancelable) viewModel.cancelChangeSource()
            },
            icon = { CircularProgressIndicator() },
            title = { Text(stringResource(R.string.load_toc)) },
            confirmButton = {
                if (uiState.changeSourceCancelable) {
                    TextButton(onClick = viewModel::cancelChangeSource) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            },
        )
    }
}
