package io.legado.app.ui.book.info

import io.legado.app.data.repository.infoSnapshot

import android.app.Activity
import android.content.Intent
import androidx.core.net.toUri
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.ViewModelStoreProvider
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.data.entities.*
import io.legado.app.help.book.*
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.model.SourceCallBack
import io.legado.app.ui.book.changecover.CoverSearchDialog
import io.legado.app.ui.book.changesource.*
import io.legado.app.ui.book.group.BookGroupSelectionDialog
import io.legado.app.ui.book.source.edit.BookSourceEditActivity
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.navigation.*
import io.legado.app.ui.photo.openPhoto
import io.legado.app.ui.variable.VariableEditorDialog
import io.legado.app.utils.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** The complete detail UI; platform objects are confined to this entry's visible lifecycle. */
@Composable
fun BookInfoDestination(route: BookInfoRoute, onBack: () -> Unit, onNavigate: (AppRoute) -> Unit,
    onResult: (Int, Intent?) -> Unit,
    childEntries: Set<String> = emptySet(),
    entry: BookInfoEntryViewModel = viewModel(), model: BookInfoViewModel = viewModel()) {
    val activity = LocalActivity.current as? AppCompatActivity ?: return
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateFlow.collectAsState()
    val state by model.uiState.collectAsStateWithLifecycle()
    val navigation by entry.state.collectAsStateWithLifecycle()
    val currentBack by rememberUpdatedState(onBack)
    val currentNavigate by rememberUpdatedState(onNavigate)
    val currentResult by rememberUpdatedState(onResult)
    var activeScope by remember { mutableStateOf<CoroutineScope?>(null) }
    var sourceOpen by rememberSaveable(route.requestId) { mutableStateOf(false) }
    var sourceEdited by rememberSaveable(route.requestId) { mutableIntStateOf(0) }
    var sourceEditedOrigin by rememberSaveable(route.requestId) { mutableStateOf<String?>(null) }
    val parentOwner = checkNotNull(LocalViewModelStoreOwner.current)
    val sourceOwner = remember(parentOwner) { ViewModelStoreProvider(parentOwner, "detail-source").getOrCreateOwner("dialog") }
    val folder = rememberLauncherForActivityResult(HandleFileContract()) { result ->
        result.uri?.let { AppConfig.defaultBookTreeUri = it.toString() }
    }
    val editSource = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode != Activity.RESULT_CANCELED) {
            sourceEdited++
            sourceEditedOrigin = it.data?.getStringExtra("origin")
        }
    }
    fun requestBack() {
        if (!route.returnToReader) currentBack()
        else if (model.uiState.value.book == null) entry.closeForReader()
        else model.requestReturnToReader()
    }
    fun begin(kind: BookInfoChildKind) {
        if (kind == BookInfoChildKind.Reader && route.returnToReader) requestBack() else model.requestChild(kind)
    }
    val actions = remember(activity, model, entry, route) {
        BookInfoPlatformActions(activity, model, route.requestId, lifecycle, { activeScope }, { currentNavigate(it) },
            ::requestBack, ::begin, { source -> editSource.launch(Intent(activity, BookSourceEditActivity::class.java)
                .putExtra("sourceUrl", source)) }, {
                if (model.getBook(false) != null) sourceOpen = true
            })
    }
    LaunchedEffect(route, entry) { entry.initialize(route) }
    LaunchedEffect(navigation.returnToReaderRequested) {
        if (navigation.returnToReaderRequested) model.requestReturnToReader()
    }
    LaunchedEffect(state.readerReturnPreparation?.ready, lifecycleState, navigation.busy, navigation.failed) {
        if (state.readerReturnPreparation?.ready == true && lifecycleState.isAtLeast(Lifecycle.State.RESUMED) &&
            !navigation.busy && !navigation.failed) entry.closeForReader()
    }
    SideEffect {
        entry.setChildEntries(childEntries)
        model.acknowledgeChildRequest(navigation.child?.id, navigation.request?.revision)
    }
    LaunchedEffect(sourceOpen) { if (!sourceOpen) sourceOwner.viewModelStore.clear() }
    LaunchedEffect(state.sourceChangeCompleted, sourceOpen) {
        state.sourceChangeCompleted?.let {
            if (it.dismiss || !sourceOpen) {
                if (it.dismiss) sourceOpen = false
                model.consumeSourceChange(it.id)
            }
        }
    }

    val initialChild = navigation.child
    val orphanReader = initialChild?.kind == BookInfoChildKind.Reader && initialChild.phase == BookInfoChildPhase.Displayed &&
        initialChild.id !in childEntries
    val deletedReader = initialChild?.kind == BookInfoChildKind.Reader && initialChild.readerDeleted
    val initialBookUrl = initialChild?.takeIf { it.kind == BookInfoChildKind.Reader && it.phase == BookInfoChildPhase.Returned }
        ?.returnBookUrl ?: navigation.bootstrap?.bookUrl
    LaunchedEffect(navigation.bootstrap, navigation.closed, lifecycleState, orphanReader, deletedReader, initialBookUrl) {
        if (!navigation.closed && lifecycleState.isAtLeast(Lifecycle.State.RESUMED) && !orphanReader && !deletedReader) {
            navigation.bootstrap?.let { model.initializeEntry(route, it.copy(bookUrl = initialBookUrl ?: it.bookUrl)) }
        }
    }
    LaunchedEffect(model, route) {
        model.uiState.map { it.bookRevision to it.book }.distinctUntilChanged().collect { (_, book) ->
            book ?: return@collect
            model.refreshCover(); model.loadKinds(book); model.loadGroup(book.group)
        }
    }
    LaunchedEffect(lifecycle, entry, model, route) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            coroutineScope {
                activeScope = this
                entry.setResumed(true)
                launch {
                    model.uiState.map { it.pendingActions }.distinctUntilChanged().collect { pending ->
                        val next = pending.firstOrNull() ?: return@collect
                        model.consumeUiAction(next)
                        when (val action = next.action) {
                            BookInfoAction.SelectBooksDir -> folder.launch { title = activity.getString(R.string.select_book_folder) }
                            is BookInfoAction.ShowToast -> activity.toastOnUi(action.message)
                            is BookInfoAction.ShowToastResource -> activity.toastOnUi(action.resourceId)
                            is BookInfoAction.RunScript -> {
                                val source = model.uiState.value.source?.takeIf { it.bookSourceUrl == action.sourceKey }?.infoSnapshot()
                                val book = model.getBook(false)?.takeIf { it.bookUrl == action.bookUrl }
                                if (source != null && book != null) launch(Dispatchers.IO) {
                                    try { runBookInfoScript(activity, source, book, action.script, this@coroutineScope, route.requestId); model.completeScript(action, book) }
                                    catch (cancelled: CancellationException) { throw cancelled }
                                    catch (error: Exception) { model.reportScriptError(action, error) }
                                }
                            }
                        }
                    }
                }
                launch {
                    combine(entry.state, model.uiState) { request, book ->
                        request.request?.takeIf { request.child == null && !request.closed && book.book != null &&
                            model.needsEntryReconciliation(it) && !book.shelfMutation } to book.entryRecoveryAttempt
                    }.distinctUntilChanged().collectLatest { (request, _) -> if (request != null) model.reconcileEntryBook(request) }
                }
                launch {
                    entry.state.map { it.refresh to it.refreshAttempt }.distinctUntilChanged().collectLatest { (refresh, _) ->
                        refresh ?: return@collectLatest
                        val bootstrap = entry.state.value.bootstrap ?: return@collectLatest
                        model.initializeEntry(route, bootstrap.copy(bookUrl = refresh.bookUrl))
                        model.awaitEntryInitialization()
                        model.pauseLoadingForChild()
                        if (model.applyEntryRefresh(refresh.bookUrl)) entry.refreshApplied(refresh.childId, refresh.bookUrl)
                        else entry.refreshFailed(refresh.childId)
                    }
                }
                launch {
                    combine(entry.state, model.uiState) { request, book -> request.navigation to book.book }
                        .distinctUntilChanged().collect { (child, book) ->
                            if (child == null || book == null || book.bookUrl != child.bookUrl) return@collect
                            val destination = when (child.kind) {
                                BookInfoChildKind.Catalog -> BookTocRoute(child.bookUrl, bookInfoChildRequestId = child.id)
                                BookInfoChildKind.Edit -> EditBookInfoRoute(child.bookUrl, child.id)
                                BookInfoChildKind.Reader -> when {
                                    book.isAudio -> AudioPlayerRoute(book.bookUrl, child.id, true)
                                    book.isVideo -> VideoPlayerRoute(book.bookUrl, requestId = child.id, returnToDetails = true)
                                    !book.isLocal && book.isImage && AppConfig.showMangaUi -> MangaReaderRoute(book.bookUrl,
                                        requestId = child.id, returnToDetails = true, inBookshelf = !book.isNotShelf)
                                    else -> PlatformTextReaderRoute(book.bookUrl, requestId = child.id)
                                }
                            }
                            currentNavigate(destination)
                            entry.navigationHandled(child.id)
                        }
                }
                launch {
                    entry.state.map { it.closed to it.request?.deleted }.distinctUntilChanged().collect { (closed, deleted) -> if (closed) {
                        if (deleted == true) currentResult(Activity.RESULT_OK, null)
                        currentBack()
                    } }
                }
                launch {
                    model.uiState.map { it.libraryAction.completion to it.fileImport.completion }.distinctUntilChanged()
                        .collect { (library, file) ->
                            val key = model.uiState.value.book?.bookUrl
                            when {
                                library?.deleted == true -> {
                                    model.libraryController.consumeCompletion(library.id)
                                    currentResult(Activity.RESULT_OK, null); currentBack()
                                }
                                library?.read == true && library.bookUrl == key -> {
                                    model.libraryController.consumeCompletion(library.id); begin(BookInfoChildKind.Reader)
                                }
                                file != null && !file.upload && file.bookUrl == key && key != null -> {
                                    model.filesController.consumeCompletion(file.id)
                                    if (file.read) begin(BookInfoChildKind.Reader)
                                }
                                file?.uri != null && file.bookUrl == null -> {
                                    activity.openFileUri(file.uri.toUri(), "*/*")
                                    model.filesController.consumeCompletion(file.id)
                                }
                            }
                        }
                }
                launch {
                    snapshotFlow { sourceEdited }.collectLatest { count ->
                        if (count > 0) model.getBook(false)?.let { book ->
                            if (model.applyEntryRefresh(book.bookUrl)) {
                                model.getBook(false)?.let(model::refreshBook)
                                sourceEdited = 0
                            }
                        }
                    }
                }
                try { awaitCancellation() }
                finally {
                    activeScope = null; entry.setResumed(false)
                    if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                        activity.supportFragmentManager.fragments.filterIsInstance<io.legado.app.ui.widget.dialog.BottomWebViewDialog>()
                            .filter { it.ownerEntryKey == route.requestId }.forEach { it.dismissAllowingStateLoss() }
                    }
                }
            }
        }
    }
    BackHandler { if (!navigation.busy && navigation.refresh == null && state.childPreparation == null &&
        state.readerReturnPreparation == null) requestBack() }
    if (state.book != null) {
        BookInfoScreen(state.copy(shelfMutation = state.shelfMutation || state.metadataWrites > 0 || navigation.busy || navigation.child != null || state.childPreparation != null || state.readerReturnPreparation != null),
            LocalConfig.bookInfoDeleteAlert) { action ->
            if (state.readerReturnPreparation == null && state.childPreparation == null && (action == BookInfoUiAction.Back ||
                    (!navigation.busy && navigation.child == null && state.metadataWrites == 0))) actions.handle(action)
        }
    } else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (state.initializationFailed) Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.book_not_exist))
            TextButton(onClick = { navigation.bootstrap?.let { model.retryEntryInitialization(route, it) } }) { Text(stringResource(R.string.retry)) }
        } else CircularProgressIndicator()
    }
    state.groupsEditor?.let { BookGroupSelectionDialog(it, model.groupsController) }
    state.variableEditor?.let { VariableEditorDialog(it, model::updateVariableDraft, model::saveVariableEditor,
        model::dismissVariableEditor, model::retryVariableEditor) }
    CoverSearchDialog(state.coverSearch, model.coverController)
    BookInfoLibraryDialog(state.libraryAction, model.libraryController,
        state.pendingDelete?.takeIf { it.id == state.libraryAction.requestId && it.cancellable && !state.cancellingDelete }
            ?.let { pending -> { model.cancelPendingDelete(pending.id) } },
        state.pendingUpload?.takeIf { it.id == state.libraryAction.requestId && !state.cancellingUpload }
            ?.let { pending -> { model.cancelPendingUpload(pending.id) } })
    BookInfoFilesDialog(state.fileImport, state.webFiles, model.filesController,
        state.book?.bookUrl == state.fileImport.bookUrl, model::setImportedFileUpload)
    if (state.childPreparation?.failed == true) AlertDialog(onDismissRequest = model::cancelChildPreparation,
        text = { Text(stringResource(R.string.error)) },
        confirmButton = { TextButton(onClick = model::retryChildPreparation) { Text(stringResource(R.string.retry)) } },
        dismissButton = { TextButton(onClick = model::cancelChildPreparation) { Text(stringResource(R.string.cancel)) } })
    if (state.readerReturnPreparation?.failed == true) AlertDialog(onDismissRequest = model::cancelReturnPreparation,
        text = { Text(stringResource(R.string.error)) },
        confirmButton = { TextButton(onClick = model::requestReturnToReader) { Text(stringResource(R.string.retry)) } },
        dismissButton = { TextButton(onClick = model::cancelReturnPreparation) { Text(stringResource(R.string.cancel)) } })
    if (state.entryRecoveryFailed && state.childPreparation == null) AlertDialog(onDismissRequest = { currentBack() },
        text = { Text(stringResource(R.string.error)) },
        confirmButton = { TextButton(onClick = model::retryEntryRecovery) { Text(stringResource(R.string.retry)) } },
        dismissButton = { TextButton(onClick = { currentBack() }) { Text(stringResource(R.string.cancel)) } })
    if (navigation.failed) AlertDialog(onDismissRequest = { currentBack() }, text = { Text(stringResource(R.string.error)) },
        confirmButton = { TextButton(onClick = {
            entry.retryChildKind?.let(::begin) ?: entry.retry()
        }) { Text(stringResource(R.string.retry)) } },
        dismissButton = { TextButton(onClick = { currentBack() }) { Text(stringResource(R.string.cancel)) } })
    if (sourceOpen && lifecycleState.isAtLeast(Lifecycle.State.RESUMED)) Dialog(onDismissRequest = { sourceOpen = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val sourceModel: ChangeBookSourceViewModel = viewModel(viewModelStoreOwner = sourceOwner)
        LaunchedEffect(sourceModel) {
            model.getBook(false)?.let { book -> sourceModel.initData(Bundle().apply {
                putString("name", book.name); putString("author", book.author)
            }, book, false) }
        }
        LaunchedEffect(sourceModel, sourceEditedOrigin) {
            sourceEditedOrigin?.let { sourceModel.startSearch(it); sourceEditedOrigin = null }
        }
        DisposableEffect(sourceModel) { onDispose { sourceModel.stopSearch() } }
        LaunchedEffect(state.sourceChangeCompleted, sourceModel) {
            state.sourceChangeCompleted?.takeUnless { it.dismiss }?.let {
                it.removedSource?.let(sourceModel::sourceRemoved)
                model.consumeSourceChange(it.id)
            }
        }
        ChangeBookSourceScreen(sourceModel, state.book?.type ?: 0, state.book?.bookUrl,
            onBack = { sourceOpen = false }, onSourceManage = { currentNavigate(BookSourceManageRoute) },
            onEditSource = { editSource.launch(Intent(activity, BookSourceEditActivity::class.java).putExtra("sourceUrl", it.origin)) },
            onResult = { result -> when (result) {
                is SourceChangeResult.Success -> {
                    model.requestSourceChange(result.source, result.book, result.toc, result.deleteAfterChange, result.dismissDialog)
                }
                is SourceChangeResult.Error -> AppLog.put("换源失败", result.throwable, true)
            } })
    }
}

private class BookInfoPlatformActions(val activity: AppCompatActivity, val model: BookInfoViewModel, val ownerKey: String,
    val lifecycle: Lifecycle, val scope: () -> CoroutineScope?, val navigate: (AppRoute) -> Unit,
    val back: () -> Unit, val begin: (BookInfoChildKind) -> Unit, val editSource: (String) -> Unit,
    val changeSource: () -> Unit) {
    fun handle(action: BookInfoUiAction) {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        when (action) {
            BookInfoUiAction.Back -> back()
            is BookInfoUiAction.CancelDelete -> model.cancelPendingDelete(action.id)
            is BookInfoUiAction.CancelUpload -> model.cancelPendingUpload(action.id)
            BookInfoUiAction.Refresh -> model.getBook(false)?.let(model::refreshBook)
            BookInfoUiAction.Top -> model.topBook()
            BookInfoUiAction.SourceVariable -> model.openSourceVariableEditor()
            BookInfoUiAction.BookVariable -> model.openBookVariableEditor()
            BookInfoUiAction.Upload -> model.requestUpload()
            BookInfoUiAction.Logs -> navigate(AppLogRoute)
            BookInfoUiAction.Toc -> begin(BookInfoChildKind.Catalog)
            BookInfoUiAction.Group -> model.openGroupSelection()
            BookInfoUiAction.Cover -> model.openCoverSearch()
            BookInfoUiAction.CoverPhoto -> model.getBook(false)?.let { book ->
                book.getDisplayCover()?.let { activity.openPhoto(it, book.getCoverSourceOrigin(), book) }
            }
            BookInfoUiAction.Edit -> begin(BookInfoChildKind.Edit)
            BookInfoUiAction.Login -> model.uiState.value.source?.let {
                navigate(SourceLoginRoute(SourceLoginTarget.BookSource, it.bookSourceUrl, bookUrl = model.getBook(false)?.bookUrl))
            }
            BookInfoUiAction.EditSource -> model.getBook(false)?.takeUnless { it.isLocal }?.let { book -> scope()?.launch {
                val exists = withContext(Dispatchers.IO) { model.hasBookSource(book.origin) }
                if (exists) editSource(book.origin) else activity.toastOnUi(R.string.error_no_source)
            } }
            BookInfoUiAction.ChangeSource -> changeSource()
            BookInfoUiAction.Shelf -> model.getBook(false)?.let { when {
                model.inBookshelf -> model.requestDelete()
                it.isWebFile -> model.openWebFiles()
                else -> model.addToBookshelf(null)
            } }
            BookInfoUiAction.Read -> model.getBook(false)?.let {
                if (it.isWebFile) model.openWebFiles(readAfterImport = true) else begin(BookInfoChildKind.Reader)
            }
            is BookInfoUiAction.CanUpdate -> model.setCanUpdate(action.enabled)
            is BookInfoUiAction.SplitLongChapter -> {
                model.getBook(false)?.let { it.setSplitLongChapter(action.enabled); model.loadBookInfo(it, false) }
                if (!action.enabled) activity.longToastOnUi(R.string.need_more_time_load_content)
            }
            is BookInfoUiAction.DeleteAlert -> LocalConfig.bookInfoDeleteAlert = action.enabled
            is BookInfoUiAction.Callback -> callback(action)
            BookInfoUiAction.IntroToggle -> model.toggleIntroExpanded()
            BookInfoUiAction.IntroRetry -> model.retryIntro()
            is BookInfoUiAction.IntroMeasured -> model.onIntroMeasured(action.key, action.collapsible)
            is BookInfoUiAction.IntroButton -> model.onIntroButtonClick(action.key, "info button ${action.name}", action.script)
            is BookInfoUiAction.IntroImage -> if (model.uiState.value.intro.contentKey == action.key)
                activity.openPhoto(action.url, model.uiState.value.source?.bookSourceUrl)
            is BookInfoUiAction.IntroLink -> {
                val state = model.uiState.value
                if (state.intro.contentKey != action.key) return
                when {
                    action.url.startsWith("javascript:", true) -> model.onIntroButtonClick(action.key, "info link", action.url.substringAfter(':'))
                    action.url.startsWith("data:image/", true) -> activity.openPhoto(action.url, state.source?.bookSourceUrl)
                    action.url.startsWith("legado://") || action.url.startsWith("yuedu://") -> navigate(OnlineImportRoute(action.url))
                    else -> navigate(OpenUrlConfirmRoute(action.url, sourceOrigin = state.source?.bookSourceUrl, sourceName = state.source?.bookSourceName))
                }
            }
        }
    }
    private fun callback(action: BookInfoUiAction.Callback) {
        val active = scope() ?: return
        val book = model.getBook(false) ?: return
        val source = model.uiState.value.source
        val event = when (action.event) {
            BookInfoCallbackEvent.Custom -> SourceCallBack.CLICK_CUSTOM_BUTTON
            BookInfoCallbackEvent.Share -> SourceCallBack.CLICK_SHARE_BOOK
            BookInfoCallbackEvent.CopyBookUrl -> SourceCallBack.CLICK_COPY_BOOK_URL
            BookInfoCallbackEvent.CopyTocUrl -> SourceCallBack.CLICK_COPY_TOC_URL
            BookInfoCallbackEvent.ClearCache -> SourceCallBack.CLICK_CLEAR_CACHE
            BookInfoCallbackEvent.Name -> SourceCallBack.CLICK_BOOK_NAME
            BookInfoCallbackEvent.LongName -> SourceCallBack.LONG_CLICK_BOOK_NAME
            BookInfoCallbackEvent.Author -> SourceCallBack.CLICK_AUTHOR
            BookInfoCallbackEvent.LongAuthor -> SourceCallBack.LONG_CLICK_AUTHOR
            BookInfoCallbackEvent.Kind -> SourceCallBack.CLICK_BOOK_LABEL
            BookInfoCallbackEvent.LongKind -> SourceCallBack.LONG_CLICK_BOOK_LABEL
        }
        if (source == null && action.event in setOf(BookInfoCallbackEvent.Kind, BookInfoCallbackEvent.LongKind)) return
        val result = when (action.event) {
            BookInfoCallbackEvent.Share -> "${book.bookUrl}#${GSON.toJson(book)}"
            BookInfoCallbackEvent.CopyBookUrl -> book.bookUrl
            BookInfoCallbackEvent.CopyTocUrl -> book.tocUrl
            BookInfoCallbackEvent.Name, BookInfoCallbackEvent.LongName -> book.name
            BookInfoCallbackEvent.Author, BookInfoCallbackEvent.LongAuthor -> book.author
            BookInfoCallbackEvent.Kind, BookInfoCallbackEvent.LongKind -> action.argument
            else -> null
        }
        SourceCallBack.callBackBtn(activity, event, source, book, null, result = result, scope = active, uiOwnerKey = ownerKey) {
            if (!active.isActive || model.uiState.value.book?.bookUrl != book.bookUrl) return@callBackBtn
            when (action.event) {
                BookInfoCallbackEvent.Share -> activity.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    putExtra(Intent.EXTRA_TEXT, result); type = "text/plain"
                }, book.name))
                BookInfoCallbackEvent.CopyBookUrl, BookInfoCallbackEvent.CopyTocUrl -> result?.let(activity::sendToClip)
                BookInfoCallbackEvent.ClearCache -> model.clearCache(book)
                BookInfoCallbackEvent.Name, BookInfoCallbackEvent.LongName -> navigate(SearchRoute(book.name))
                BookInfoCallbackEvent.Author, BookInfoCallbackEvent.LongAuthor -> navigate(SearchRoute(book.author))
                BookInfoCallbackEvent.Kind -> if (source != null && action.argument != null) activity.openBookSearch(source, action.argument)
                BookInfoCallbackEvent.Custom, BookInfoCallbackEvent.LongKind -> Unit
            }
        }
    }
}
