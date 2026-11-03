package io.legado.app.ui.book.search

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.legado.app.R
import io.legado.app.data.repository.BookInfoOpenRequest
import io.legado.app.data.repository.SearchScopeSpec
import io.legado.app.data.repository.toSearchBook
import io.legado.app.data.entities.SearchSessionStatus
import io.legado.app.data.entities.SearchScreenDraft
import io.legado.app.ui.book.explore.SearchBookGridItem
import io.legado.app.ui.navigation.SearchRoute
import io.legado.app.utils.resolveBookListSpanCount
import io.legado.app.utils.resolveContentGridColumns
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.delay

/** Search owns no Activity, database, preference listener or Fragment. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun SearchScreen(
    route: SearchRoute,
    onBack: () -> Unit,
    onOpenBook: (BookInfoOpenRequest) -> Unit,
    onManageSources: () -> Unit,
    onLogs: () -> Unit,
    isCurrent: Boolean = true,
    knownDetails: Set<String> = emptySet(),
    viewModel: SearchViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val draft = state.draft
    val focus = LocalFocusManager.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val openBook by rememberUpdatedState(onOpenBook)
    val snackbar = remember { SnackbarHostState() }
    var overflow by rememberSaveable { mutableStateOf(false) }
    val grid = rememberLazyGridState()
    val fontScale = LocalDensity.current.fontScale
    LaunchedEffect(route, viewModel) { viewModel.initialize(route) }
    LifecycleResumeEffect(viewModel, isCurrent) {
        viewModel.setActive(isCurrent)
        onPauseOrDispose { viewModel.setActive(false) }
    }
    LaunchedEffect(viewModel, lifecycle, isCurrent, knownDetails) {
        if (isCurrent) lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.state.map { it.pendingOpen }.distinctUntilChanged().collect { request ->
                if (request != null) {
                    if (request.id !in knownDetails) openBook(request)
                    viewModel.consumeOpen(request.id)
                }
            }
        }
    }
    LaunchedEffect(state.notice) {
        state.notice?.let { snackbar.showSnackbar(it); viewModel.consumeNotice() }
    }
    val bottom by remember { derivedStateOf {
        val info = grid.layoutInfo
        info.totalItemsCount > 0 && (info.visibleItemsInfo.lastOrNull()?.index ?: -1) >= info.totalItemsCount - 1
    } }
    var autoLoadedAtBottom by remember(state.session?.id) { mutableStateOf(false) }
    LaunchedEffect(bottom, state.results.size, state.session?.revision, draft?.showInputHelp, draft?.manuallyStopped, isCurrent) {
        if (!bottom) autoLoadedAtBottom = false
        else if (isCurrent && !autoLoadedAtBottom && state.results.isNotEmpty() && !state.searching &&
            state.session?.status == SearchSessionStatus.Ready && draft?.showInputHelp == false &&
            draft.manuallyStopped.not() && state.session?.hasMore == true) {
            // Room can report Ready just before the previous coroutine returns.
            while (!viewModel.next(automatic = true)) delay(100)
            autoLoadedAtBottom = true
        }
    }
    val returnToResults = draft?.showInputHelp == true && state.session != null && draft.query.trim() == state.session?.query
    BackHandler(returnToResults) { focus.clearFocus(); viewModel.hideSuggestions() }
    val submit = { focus.clearFocus(); viewModel.submit() }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
                title = {
                    OutlinedTextField(
                        state = viewModel.queryEditor,
                        enabled = state.ready, lineLimits = TextFieldLineLimits.SingleLine,
                        placeholder = { Text(stringResource(R.string.search_book_key), maxLines = 1) },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        onKeyboardAction = { submit() },
                        trailingIcon = { Row {
                            if (viewModel.queryEditor.text.isNotEmpty()) IconButton(onClick = { viewModel.queryChanged("") }) {
                                Icon(Icons.Default.Close, stringResource(R.string.clear))
                            }
                            IconButton(onClick = submit, enabled = state.ready) { Icon(Icons.Default.Search, stringResource(R.string.search)) }
                        } },
                        modifier = Modifier.fillMaxWidth().testTag("search_query").onFocusChanged { viewModel.focusChanged(it.isFocused) },
                    )
                },
                actions = { Box {
                    IconButton(onClick = { overflow = true }, enabled = state.ready, modifier = Modifier.testTag("search_menu")) {
                        Icon(Icons.Default.MoreVert, stringResource(R.string.more_menu))
                    }
                    DropdownMenu(expanded = overflow, onDismissRequest = { overflow = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.precision_search)) },
                            trailingIcon = { Checkbox(state.preferences?.precision == true, null) },
                            onClick = { overflow = false; viewModel.setPrecision(state.preferences?.precision != true) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.show_search_read_record)) },
                            trailingIcon = { Checkbox(state.preferences?.showReadRecord == true, null) },
                            onClick = { overflow = false; viewModel.setShowReadRecord(state.preferences?.showReadRecord != true) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.search_result_filter)) }, onClick = { overflow = false; viewModel.showFilter() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.search_scope)) }, onClick = { overflow = false; viewModel.showScope() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.book_source_manage)) }, onClick = { overflow = false; onManageSources() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.log)) }, onClick = { overflow = false; onLogs() })
                        HorizontalDivider()
                        val scope = SearchScopeSpec(draft?.scope.orEmpty())
                        if (scope.isSource) DropdownMenuItem(text = { Text(scope.sourceLabel.orEmpty()) },
                            trailingIcon = { Checkbox(true, null) }, onClick = { overflow = false; viewModel.setScope(SearchScopeSpec("")) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.all_source)) },
                            trailingIcon = { Checkbox(scope.isAll, null) }, onClick = { overflow = false; viewModel.setScope(SearchScopeSpec("")) })
                        state.groups.forEach { group -> DropdownMenuItem(text = { Text(group) },
                            trailingIcon = { Checkbox(group in scope.groups, null) }, onClick = { overflow = false; viewModel.toggleGroup(group) }) }
                    }
                } },
            )
        },
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding).imePadding()) {
            val spanCount = resolveContentGridColumns(resolveBookListSpanCount(maxWidth.value.toInt()),
                maxWidth.value, minimumCellWidthDp = 280f, fontScale = fontScale)
            Column(Modifier.fillMaxSize().testTag("search_screen")) {
                if (!state.ready) {
                    if (state.loadError == null) CircularProgressIndicator(Modifier.padding(24.dp))
                    else { Text(state.loadError.orEmpty(), Modifier.padding(16.dp)); TextButton(onClick = { viewModel.reload(route) }) { Text(stringResource(R.string.retry)) } }
                } else if (draft != null) {
                    if (state.searching) {
                        LinearProgressIndicator(progress = { val session = state.session; if (session == null || session.total == 0) 0f else (session.done.toFloat() / session.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("${state.session?.done ?: 0}/${state.session?.total ?: 0}", Modifier.weight(1f).padding(horizontal = 16.dp).testTag("search_progress"))
                            TextButton(onClick = { viewModel.stop() }) { Text(stringResource(R.string.stop)) }
                        }
                    }
                    if (state.opening) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (draft.showInputHelp) {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (state.shelfMatches.isNotEmpty()) {
                                Text(stringResource(R.string.bookshelf), style = MaterialTheme.typography.titleSmall)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { state.shelfMatches.forEach { book ->
                                    AssistChip(onClick = { focus.clearFocus(); viewModel.openShelf(book) }, label = { Text(book.name) })
                                } }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.searchHistory), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                                if (state.history.isNotEmpty()) TextButton(onClick = viewModel::showClearHistory) { Text(stringResource(R.string.clear)) }
                            }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                state.history.forEach { item -> Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    modifier = Modifier.heightIn(min = 48.dp).combinedClickable(
                                        onClick = { focus.clearFocus(); viewModel.historySelected(item.word) },
                                        onLongClickLabel = stringResource(R.string.delete), onLongClick = { viewModel.deleteHistory(item.word) },
                                    )) { Text(item.word, Modifier.padding(12.dp)) } }
                            }
                        }
                    } else {
                        val session = state.session
                        if (session != null && session.status in setOf(SearchSessionStatus.Partial, SearchSessionStatus.Error, SearchSessionStatus.Interrupted, SearchSessionStatus.Stopped)) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(when (session.status) {
                                    SearchSessionStatus.Interrupted -> stringResource(R.string.search_session_interrupted)
                                    SearchSessionStatus.Stopped -> stringResource(R.string.stop)
                                    else -> if (session.error == "no_sources") stringResource(R.string.error_no_source) else session.error ?: stringResource(R.string.load_error_retry)
                                }, Modifier.weight(1f))
                                TextButton(onClick = viewModel::retry) { Text(stringResource(R.string.retry)) }
                            }
                        }
                        if (state.results.isEmpty() && !state.searching && (session?.completedPage ?: 0) > 0) Text(stringResource(R.string.search_session_no_results), Modifier.padding(24.dp).testTag("search_empty"))
                        LazyVerticalGrid(columns = GridCells.Fixed(spanCount), state = grid, modifier = Modifier.weight(1f).testTag("search_results")) {
                            items(state.results, key = { it.row.resultKey }) { result ->
                                val book = remember(result.row) { result.row.toSearchBook() }
                                Box(Modifier.testTag("search_result:${result.row.resultKey}")) { SearchBookGridItem(book, result.inBookshelf, result.hasReadRecord, result.originCount,
                                    onClick = { viewModel.openResult(result.row.resultKey) }) }
                            }
                        }
                        if (session?.hasMore == true && !state.searching && !draft.manuallyStopped) TextButton(onClick = { viewModel.next() }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text(stringResource(R.string.next_page)) }
                    }
                }
            }
        }
    }
    if (draft?.scopeDialogOpen == true) SearchScopeDialogContent(state, draft, viewModel)
    if (draft?.filterDialogOpen == true) SearchFilterDialog(viewModel.filterEditor,
        viewModel::dismissFilter, viewModel::confirmFilter)
    if (draft?.clearHistoryDialogOpen == true) AlertDialog(onDismissRequest = viewModel::dismissClearHistory,
        text = { Text(stringResource(R.string.sure_clear_search_history)) },
        confirmButton = { TextButton(onClick = viewModel::confirmClearHistory) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = viewModel::dismissClearHistory) { Text(stringResource(R.string.cancel)) } })
    if (draft?.emptyPromptOpen == true) AlertDialog(onDismissRequest = viewModel::dismissEmptyPrompt,
        text = { Text(stringResource(if (state.session?.precision == true) R.string.search_session_relax_precision else R.string.search_session_expand_scope)) },
        confirmButton = { TextButton(onClick = viewModel::acceptEmptyFallback) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = viewModel::dismissEmptyPrompt) { Text(stringResource(R.string.cancel)) } })
}

/** Keep actions outside the scrollable editor, even in a short window with the IME open. */
@Composable
private fun SearchFilterDialog(state: TextFieldState, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val title = stringResource(R.string.search_result_filter)
    val scroll = rememberScrollState()
    // Moving the same editor preserves its input connection and pending edits
    // when the actions change between the side and bottom of the dialog.
    val editor = remember(state) {
        movableContentOf<Modifier> { modifier -> SearchFilterEditor(state, modifier) }
    }
    Dialog(onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BoxWithConstraints(Modifier.fillMaxSize().pointerInput(onDismiss) { detectTapGestures { onDismiss() } }.safeDrawingPadding().imePadding(), contentAlignment = Alignment.Center) {
            val short = maxHeight < 240.dp
            val wide = maxWidth >= 600.dp
            Surface(Modifier.padding(if (short) 4.dp else 16.dp).widthIn(max = if (wide) 720.dp else 560.dp).fillMaxWidth()
                .semantics { paneTitle = title }.pointerInput(Unit) { detectTapGestures {} },
                shape = AlertDialogDefaults.shape, color = AlertDialogDefaults.containerColor,
                tonalElevation = AlertDialogDefaults.TonalElevation) {
                if (wide) {
                    Row(Modifier.padding(horizontal = 24.dp, vertical = if (short) 8.dp else 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).then(if (short) Modifier else Modifier.verticalScroll(scroll)), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            if (!short) SearchFilterDescription(title)
                            editor(Modifier.fillMaxWidth().then(if (short) Modifier.weight(1f, fill = false) else Modifier))
                        }
                        SearchFilterActions(onDismiss, onConfirm)
                    }
                } else {
                    Column(Modifier.padding(horizontal = 24.dp, vertical = if (short) 8.dp else 24.dp)) {
                        Column(Modifier.weight(1f, fill = false).then(if (short) Modifier else Modifier.verticalScroll(scroll)), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            if (!short) SearchFilterDescription(title)
                            editor(Modifier.fillMaxWidth().then(if (short) Modifier.weight(1f, fill = false) else Modifier))
                        }
                        Spacer(Modifier.height(if (short) 4.dp else 16.dp))
                        Box(Modifier.align(Alignment.End)) { SearchFilterActions(onDismiss, onConfirm) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchFilterEditor(state: TextFieldState, modifier: Modifier) {
    val label = stringResource(R.string.search_result_filter)
    OutlinedTextField(state = state, modifier = modifier.semantics { contentDescription = label }.testTag("search_filter"))
}

@Composable
private fun SearchFilterDescription(title: String) {
    Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
    Text(stringResource(R.string.search_result_filter_hint), style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SearchFilterActions(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    Row(horizontalArrangement = Arrangement.End) {
        TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        TextButton(onClick = onConfirm) { Text(stringResource(R.string.ok)) }
    }
}

@Composable
private fun SearchScopeDialogContent(state: SearchScreenState, draft: SearchScreenDraft, viewModel: SearchViewModel) {
    AlertDialog(onDismissRequest = viewModel::dismissScope,
        title = { Text(stringResource(R.string.search_scope)) },
        text = { Column(Modifier.fillMaxWidth().heightIn(max = 440.dp).testTag("search_scope_dialog")) {
            Row {
                listOf(false to R.string.group, true to R.string.book_source).forEach { (sourceMode, label) ->
                    Row(Modifier.weight(1f).heightIn(min = 48.dp).selectable(selected = draft.scopeSourceMode == sourceMode,
                        role = Role.RadioButton, onClick = { viewModel.scopeMode(sourceMode) }), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(draft.scopeSourceMode == sourceMode, null); Text(stringResource(label))
                    }
                }
            }
            if (draft.scopeSourceMode) OutlinedTextField(state = viewModel.scopeQueryEditor,
                lineLimits = TextFieldLineLimits.SingleLine, label = { Text(stringResource(R.string.search_book_source)) }, modifier = Modifier.testTag("search_scope_query"))
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                if (draft.scopeSourceMode) items(state.sources, key = { it.url }) { source ->
                    val selected = SearchScopeSpec(draft.scopeSourceDraft.orEmpty()).sourceUrl == source.url
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected, role = Role.RadioButton,
                        onClick = { viewModel.scopeSource(source) }), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected, null); Text(source.name)
                    }
                } else items(state.groups, key = { it }) { group ->
                    val checked = group in SearchScopeSpec(draft.scopeGroupDraft).groups
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(checked, role = Role.Checkbox,
                        onValueChange = { viewModel.scopeGroup(group) }), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked, null); Text(group)
                    }
                }
            }
            TextButton(onClick = { viewModel.setScope(SearchScopeSpec("")) }) { Text(stringResource(R.string.all_source)) }
        } },
        confirmButton = { TextButton(onClick = viewModel::confirmScope) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = viewModel::dismissScope) { Text(stringResource(R.string.cancel)) } },
    )
}
