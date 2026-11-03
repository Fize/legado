package io.legado.app.ui.book.toc

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.legado.app.R
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookHighlight
import io.legado.app.data.entities.Bookmark
import io.legado.app.help.book.isLocalTxt
import io.legado.app.ui.compose.component.LegadoTopAppBar
import io.legado.app.ui.compose.component.LegadoMenuAction

@Composable
fun BookTocDestination(
    bookUrl: String,
    openTxtTocRules: Boolean = false,
    onBack: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenRuleManager: () -> Unit,
    onSelect: (BookTocSelection) -> Unit,
    viewModel: BookTocRouteViewModel = viewModel(key = "book-toc:$bookUrl"),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val book = state.book
    val snackbarHostState = remember { SnackbarHostState() }
    val jsonExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) {
        it?.let { uri -> viewModel.exportBookmarks(uri, markdown = false) }
    }
    val markdownExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) {
        it?.let { uri -> viewModel.exportBookmarks(uri, markdown = true) }
    }
    LaunchedEffect(bookUrl, openTxtTocRules) {
        viewModel.initialize(bookUrl, openTxtTocRules)
    }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Column {
                LegadoTopAppBar(
                    title = state.book?.name ?: stringResource(R.string.chapter_list),
                    subtitle = state.book?.author,
                    onBack = onBack,
                    searchQuery = state.query,
                    onSearchQueryChange = viewModel::setQuery,
                    menuActions = buildList {
                        if (book != null && state.selectedTab == BookTocTab.Chapters) {
                            add(LegadoMenuAction(
                                title = stringResource(R.string.reverse_toc),
                                checked = state.reverseToc,
                                onClick = viewModel::toggleReverseToc,
                            ))
                            add(LegadoMenuAction(
                                title = stringResource(R.string.expand_toc),
                                checked = state.tocExpanded,
                                onClick = viewModel::toggleTocExpanded,
                            ))
                            add(LegadoMenuAction(
                                title = stringResource(R.string.use_replace),
                                checked = state.useReplaceTitles,
                                onClick = viewModel::toggleUseReplaceTitles,
                            ))
                            add(LegadoMenuAction(
                                title = stringResource(R.string.load_word_count),
                                checked = state.countWords,
                                onClick = viewModel::toggleCountWords,
                            ))
                            if (book.isLocalTxt) {
                                add(LegadoMenuAction(
                                    title = stringResource(R.string.split_long_chapter),
                                    checked = state.splitLongChapters,
                                    onClick = viewModel::toggleSplitLongChapters,
                                ))
                                add(LegadoMenuAction(
                                    title = stringResource(R.string.txt_toc_rule),
                                    onClick = viewModel::openTxtTocRulePicker,
                                ))
                            }
                        }
                        if (book != null && state.selectedTab == BookTocTab.Bookmarks) {
                            add(LegadoMenuAction(
                                title = stringResource(R.string.export),
                                onClick = { jsonExport.launch("bookmark-${book.name} ${book.author}.json") },
                            ))
                            add(LegadoMenuAction(
                                title = stringResource(R.string.export_md),
                                onClick = { markdownExport.launch("bookmark-${book.name} ${book.author}.md") },
                            ))
                        }
                        add(LegadoMenuAction(title = stringResource(R.string.log), onClick = onOpenLogs))
                    },
                )
                val tabs = listOf(
                    BookTocTab.Chapters to stringResource(R.string.chapter_list),
                    BookTocTab.Bookmarks to stringResource(R.string.bookmark),
                    BookTocTab.Highlights to stringResource(R.string.highlight_tab),
                )
                PrimaryTabRow(selectedTabIndex = state.selectedTab.ordinal) {
                    tabs.forEachIndexed { index, (tab, title) ->
                        Tab(
                            selected = state.selectedTab == tab,
                            onClick = { viewModel.selectTab(tab) },
                            text = { Text(title) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        when {
            state.loading -> Column(
                Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) { CircularProgressIndicator() }
            state.error != null -> Text(
                text = state.error.orEmpty(),
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                color = MaterialTheme.colorScheme.error,
            )
            else -> when (state.selectedTab) {
                BookTocTab.Chapters -> ChapterTocList(
                    state = state,
                    onChapter = { viewModel.selectChapter(it)?.let(onSelect) },
                    onPdfOutline = { viewModel.selectPdfOutline(it.node)?.let(onSelect) },
                    onToggleVolume = viewModel::toggleVolume,
                    onTogglePdfOutline = viewModel::togglePdfOutline,
                    modifier = Modifier.fillMaxSize().padding(padding),
                )
                BookTocTab.Bookmarks -> BookmarkTocList(
                    items = state.bookmarks,
                    scrollIndex = state.bookmarkScrollIndex,
                    onClick = { onSelect(viewModel.selectBookmark(it)) },
                    onLongClick = viewModel::editBookmark,
                    modifier = Modifier.fillMaxSize().padding(padding),
                )
                BookTocTab.Highlights -> HighlightTocList(
                    items = state.highlights,
                    scrollIndex = state.highlightScrollIndex,
                    onClick = { viewModel.selectHighlight(it)?.let(onSelect) },
                    onLongClick = viewModel::editHighlight,
                    modifier = Modifier.fillMaxSize().padding(padding),
                )
            }
        }
    }

    state.editingBookmark?.let { bookmark ->
        var bookText by remember(bookmark.time) { mutableStateOf(bookmark.bookText) }
        var note by remember(bookmark.time) { mutableStateOf(bookmark.content) }
        AlertDialog(
            onDismissRequest = viewModel::dismissEditors,
            title = { Text(stringResource(R.string.bookmark)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(bookmark.chapterName, style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(bookText, { bookText = it }, label = { Text(stringResource(R.string.content)) })
                    OutlinedTextField(
                        note,
                        { note = it },
                        label = { Text(stringResource(R.string.note_content)) },
                        modifier = Modifier.testTag("bookTocBookmarkNote"),
                    )
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { viewModel.deleteBookmark(bookmark) }) {
                        Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = viewModel::dismissEditors) { Text(stringResource(R.string.cancel)) }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.saveBookmark(bookmark.copy(bookText = bookText, content = note)) }) {
                    Text(stringResource(R.string.ok))
                }
            },
        )
    }
    state.editingHighlight?.let { highlight ->
        var bookText by remember(highlight.time) { mutableStateOf(highlight.bookText) }
        var note by remember(highlight.time) { mutableStateOf(highlight.note) }
        AlertDialog(
            onDismissRequest = viewModel::dismissEditors,
            title = { Text(stringResource(R.string.highlight_note)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(highlight.chapterName, style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(bookText, { bookText = it }, label = { Text(stringResource(R.string.content)) })
                    OutlinedTextField(
                        note,
                        { note = it },
                        label = { Text(stringResource(R.string.note_content)) },
                        modifier = Modifier.testTag("bookTocHighlightNote"),
                    )
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { viewModel.deleteHighlight(highlight) }) {
                        Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = viewModel::dismissEditors) { Text(stringResource(R.string.cancel)) }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.saveHighlight(highlight.copy(bookText = bookText, note = note)) }) {
                    Text(stringResource(R.string.ok))
                }
            },
        )
    }
    if (state.showTxtTocRulePicker) {
        val visibleRules = state.txtTocRules.filter { rule ->
            val query = state.txtTocRuleQuery.trim()
            query.isBlank() || rule.name.contains(query, true) || rule.rule.contains(query, true) ||
                rule.replacement.contains(query, true)
        }
        AlertDialog(
            onDismissRequest = viewModel::dismissTxtTocRulePicker,
            title = { Text(stringResource(R.string.txt_toc_rule)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = state.txtTocRuleQuery,
                        onValueChange = viewModel::setTxtTocRuleQuery,
                        label = { Text(stringResource(R.string.search)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    LazyColumn(Modifier.heightIn(max = 400.dp)) {
                        items(visibleRules, key = { it.id }) { rule ->
                            Row(
                                Modifier.fillMaxWidth().clickable { viewModel.selectTxtTocRule(rule.name) },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = rule.name == state.selectedTxtTocRuleName,
                                    onClick = { viewModel.selectTxtTocRule(rule.name) },
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(rule.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (!rule.example.isNullOrBlank()) {
                                        Text(rule.example.orEmpty(), style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                                            overflow = TextOverflow.Ellipsis)
                                    }
                                }
                                Switch(
                                    checked = rule.enable,
                                    onCheckedChange = { viewModel.setTxtTocRuleEnabled(rule, it) },
                                )
                            }
                            HorizontalDivider()
                        }
                    }
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        viewModel.dismissTxtTocRulePicker()
                        onOpenRuleManager()
                    }) { Text(stringResource(R.string.manage_txt_toc_rules)) }
                    TextButton(onClick = viewModel::dismissTxtTocRulePicker) { Text(stringResource(R.string.cancel)) }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = viewModel::applySelectedTxtTocRule,
                    enabled = state.txtTocRules.any { it.name == state.selectedTxtTocRuleName },
                ) { Text(stringResource(R.string.ok)) }
            },
        )
    }
}

@Composable
private fun ChapterTocList(
    state: BookTocUiState,
    onChapter: (BookChapter) -> Unit,
    onPdfOutline: (PdfOutlineRow) -> Unit,
    onToggleVolume: (Int) -> Unit,
    onTogglePdfOutline: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val currentItem = state.chapterItems.indexOfFirst {
        it.readingChapter?.index == state.book?.durChapterIndex
    }
    LaunchedEffect(currentItem) {
        if (currentItem >= 0) listState.scrollToItem(currentItem)
    }
    LazyColumn(state = listState, modifier = modifier.windowInsetsPadding(WindowInsets.navigationBars)) {
        if (state.usesPdfOutline) {
            items(state.pdfOutline, key = { "pdf:${it.node.id}" }) { row ->
                Row(
                    Modifier.fillMaxWidth().clickable {
                        if (row.node.pageIndex != null) onPdfOutline(row)
                        else if (row.canToggle) onTogglePdfOutline(row.node.id)
                    }.padding(start = (16 + row.node.depth * 18).dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(row.node.title.ifBlank { stringResource(R.string.chapter_list) }, Modifier.weight(1f))
                    if (row.hasChildren) {
                        androidx.compose.material3.IconButton(onClick = { onTogglePdfOutline(row.node.id) }) {
                            Icon(
                                imageVector = if (row.collapsed) Icons.AutoMirrored.Filled.KeyboardArrowRight else Icons.Default.ExpandMore,
                                contentDescription = null,
                            )
                        }
                    }
                    row.node.pageIndex?.let { Text("${it + 1}", Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelSmall) }
                }
                HorizontalDivider()
            }
        } else {
            items(state.chapterItems, key = TocListItem::key) { item ->
                val volume = item as? TocListItem.Volume
                val isCurrent = item.readingChapter?.index == state.book?.durChapterIndex
                Row(
                    Modifier.fillMaxWidth().clickable {
                        if (volume?.canToggle == true) onToggleVolume(volume.chapter.index)
                        else item.readingChapter?.let(onChapter)
                    }.padding(start = (16 + item.depth * 18).dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            state.displayTitles[item.key] ?: item.chapter.title,
                            style = if (volume != null) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyLarge,
                            fontWeight = if (volume != null || isCurrent) FontWeight.Medium else FontWeight.Normal,
                            color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (volume != null) Text(
                            stringResource(R.string.all_chapter_num, volume.chapterCount),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (volume == null && state.countWords && !item.chapter.wordCount.isNullOrBlank()) {
                            Text(
                                item.chapter.wordCount.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (volume?.canToggle == true) Icon(
                        imageVector = if (volume.collapsed) Icons.AutoMirrored.Filled.KeyboardArrowRight else Icons.Default.ExpandMore,
                        contentDescription = null,
                    )
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun BookmarkTocList(
    items: List<Bookmark>,
    scrollIndex: Int,
    onClick: (Bookmark) -> Unit,
    onLongClick: (Bookmark) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(items, scrollIndex) {
        if (items.isNotEmpty()) listState.scrollToItem(scrollIndex.coerceAtMost(items.lastIndex))
    }
    LazyColumn(state = listState, modifier = modifier.windowInsetsPadding(WindowInsets.navigationBars)) {
        items(items, key = Bookmark::time) { bookmark ->
            Column(
                Modifier.fillMaxWidth().combinedClickable(
                    onClick = { onClick(bookmark) },
                    onLongClick = { onLongClick(bookmark) },
                ).padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(bookmark.chapterName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                if (bookmark.bookText.isNotEmpty()) Text(bookmark.bookText, style = MaterialTheme.typography.bodyMedium)
                if (bookmark.content.isNotEmpty()) Text(bookmark.content, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            HorizontalDivider()
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun HighlightTocList(
    items: List<BookHighlight>,
    scrollIndex: Int,
    onClick: (BookHighlight) -> Unit,
    onLongClick: (BookHighlight) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(items, scrollIndex) {
        if (items.isNotEmpty()) listState.scrollToItem(scrollIndex.coerceAtMost(items.lastIndex))
    }
    LazyColumn(state = listState, modifier = modifier.windowInsetsPadding(WindowInsets.navigationBars)) {
        items(items, key = BookHighlight::time) { highlight ->
            Column(
                Modifier.fillMaxWidth().combinedClickable(
                    onClick = { onClick(highlight) },
                    onLongClick = { onLongClick(highlight) },
                ).padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(highlight.chapterName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                if (highlight.bookText.isNotEmpty()) Text(highlight.bookText, style = MaterialTheme.typography.bodyMedium)
                if (highlight.note.isNotEmpty()) Text(highlight.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            HorizontalDivider()
        }
    }
}
