package io.legado.app.ui.book.manga

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.legado.app.R
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.legado.app.BuildConfig
import io.legado.app.constant.AppLog
import io.legado.app.data.repository.ReaderOpenRequest
import io.legado.app.help.book.isImage
import io.legado.app.help.source.getSourceType
import io.legado.app.help.storage.Backup
import io.legado.app.model.BookCover
import io.legado.app.receiver.NetworkChangedListener
import io.legado.app.ui.book.changesource.*
import io.legado.app.ui.book.manga.entities.MangaPage
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.book.source.edit.BookSourceEditActivity
import io.legado.app.ui.book.source.manage.BookSourceActivity
import io.legado.app.ui.browser.WebViewActivity
import io.legado.app.ui.navigation.BookTocRoute
import io.legado.app.ui.navigation.BookInfoRoute
import io.legado.app.ui.navigation.AppRoute
import io.legado.app.ui.navigation.MangaReaderRoute
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.openUrl
import io.legado.app.utils.startActivity
import kotlinx.coroutines.launch

/** The actual Navigation 3 reader destination. All platform references remain in this UI scope. */
@Composable
fun MangaReaderDestination(
    route: MangaReaderRoute,
    onBack: () -> Unit,
    onNavigate: (AppRoute) -> Unit,
    onResult: (Int, Intent?) -> Unit,
    detailEntries: Set<String> = emptySet(),
    isCurrent: Boolean = true,
    viewModel: ReadMangaViewModel = viewModel(),
) {
    val context = LocalContext.current
    val activity = LocalActivity.current ?: return
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val windowSize = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current
    val state by viewModel.readerUiState.collectAsStateWithLifecycle()
    val downloadFailure=stringResource(R.string.cache_operation_failed)
    val sourceModel: ChangeBookSourceViewModel = viewModel(key = "manga-source:${route.requestId}")
    val scope = rememberCoroutineScope()
    val currentBack by rememberUpdatedState(onBack)
    val network = remember(context) { NetworkChangedListener(context) }
    val currentState by rememberUpdatedState(state)
    val lifecycleState by lifecycle.currentStateFlow.collectAsState()
    SideEffect { viewModel.setDetailEntries(detailEntries) }

    LaunchedEffect(route, viewModel) {
        viewModel.observePreferences()
        viewModel.initialize(ReaderOpenRequest(route.requestId, route.bookUrl, route.inBookshelf,
            route.chapterChanged, route.chapterIndex, route.pageIndex, route.returnToDetails))
    }
    LaunchedEffect(windowSize, density) {
        with(density) {
            viewModel.updateWindowSize(windowSize.width.toDp().value.toInt(), windowSize.height.toDp().value.toInt())
        }
    }
    LifecycleResumeEffect(viewModel, network, isCurrent) {
        if (isCurrent) viewModel.resumeReader()
        network.onNetworkChanged = {
            scope.launch { if (NetworkUtils.isAvailable()) viewModel.networkAvailable() }
        }
        network.register()
        onPauseOrDispose {
            network.unRegister()
            network.onNetworkChanged = null
            viewModel.pauseReader()
            if (!BuildConfig.DEBUG) Backup.autoBack(context)
        }
    }
    LaunchedEffect(viewModel, lifecycle, route.requestId, isCurrent) {
        if (isCurrent) lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.observeCatalog(route.requestId).collect { result ->
                if (result != null) viewModel.resumeReader()
            }
        }
    }
    LaunchedEffect(viewModel, lifecycle, route.requestId, isCurrent) {
        if (isCurrent) lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.observeDetails(route.requestId).collect(viewModel::detailChanged)
        }
    }
    LaunchedEffect(state.detailNavigation?.id, lifecycleState, isCurrent, detailEntries) {
        val request = state.detailNavigation ?: return@LaunchedEffect
        if (isCurrent && lifecycleState.isAtLeast(Lifecycle.State.RESUMED) && request.id !in detailEntries) {
            onNavigate(BookInfoRoute(request.bookUrl, request.id, returnToReader = true))
            viewModel.detailNavigationHandled(request.id)
        }
    }
    LaunchedEffect(state.exit) {
        state.exit?.let {
            if (!viewModel.finishExit()) return@let
            when (it) {
                MangaReaderExit.AddedToBookshelf -> onResult(Activity.RESULT_OK, null)
                MangaReaderExit.Deleted -> onResult(ReadBookActivity.RESULT_DELETED, null)
                MangaReaderExit.Closed -> Unit
            }
            currentBack()
        }
    }
    BackHandler {
        if (state.exit != null) return@BackHandler
        when {
            state.navigationPending -> {
                if (state.detailNavigation != null) viewModel.cancelDetailsNavigation() else viewModel.cancelCatalogNavigation()
                viewModel.setMenuVisible(false)
            }
            state.dialog != null -> viewModel.showDialog(null)
            state.menuVisible -> viewModel.setMenuVisible(false)
            else -> viewModel.requestExit()
        }
    }
    MangaReaderWindowEffects(activity, state.menuVisible, state.windowBrightness)

    val editSource = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        it.data?.getStringExtra("origin")?.let(sourceModel::startSearch)
    }
    // Persist a small image identity while Android's tree picker owns the foreground.
    var imageBook by rememberSaveable(route.requestId) { mutableStateOf<String?>(null) }
    var imageChapter by rememberSaveable(route.requestId) { mutableStateOf<Int?>(null) }
    var imagePage by rememberSaveable(route.requestId) { mutableStateOf<Int?>(null) }
    var imageHash by rememberSaveable(route.requestId) { mutableStateOf<String?>(null) }
    val imageChangedMessage = stringResource(R.string.manga_image_changed_retry)
    val imageDirectoryMessage = stringResource(R.string.manga_image_directory_error)
    var imageTree by rememberSaveable(route.requestId) { mutableStateOf<String?>(null) }
    val imageDirectory = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) { imageBook = null; imageTree = null }
        else runCatching {
            context.contentResolver.takePersistableUriPermission(uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            viewModel.rememberImageDirectory(uri)
            imageTree = uri.toString()
        }.onFailure { AppLog.put("保存图片目录失败\n$it", it); viewModel.showNotice(imageDirectoryMessage); imageBook = null }
    }
    LaunchedEffect(imageTree, imageBook, state.items, state.bookUrl, state.loadingVisible) {
        val uri = imageTree?.let(android.net.Uri::parse) ?: return@LaunchedEffect
        val expectedBook = imageBook ?: return@LaunchedEffect
        if (state.loadingVisible) return@LaunchedEffect
        if (state.bookUrl == expectedBook) {
            val page = state.items.filterIsInstance<MangaPage>().firstOrNull {
                it.chapterIndex == imageChapter && it.index == imagePage && mangaImageIdentity(it.mImageUrl) == imageHash
            }
            if (page == null) viewModel.showNotice(imageChangedMessage)
            else viewModel.saveImage(page.mImageUrl, uri)
        }
        imageBook = null; imageTree = null
    }
    LaunchedEffect(state.contentVersion, state.anchorItemIndex, state.preDownloadCount) {
        state.items.asSequence().drop(state.anchorItemIndex + 1).filterIsInstance<MangaPage>()
            .take(state.preDownloadCount.coerceAtLeast(0)).forEach { page ->
                BookCover.preloadManga(context, mangaImagePath(page), sourceOrigin = state.sourceOrigin, bookUrl = state.bookUrl).preload()
            }
    }

    fun openSource() {
        val book = viewModel.snapshot().book ?: return
        viewModel.setMenuVisible(false)
        sourceModel.initData(Bundle().apply { putString("name", book.name); putString("author", book.author) }, book, false)
        viewModel.showDialog(MangaReaderDialog.ChangeSource)
    }
    fun handle(action: MangaReaderAction) {
        if (viewModel.readerUiState.value.navigationPending && action != MangaReaderAction.Back &&
            action !is MangaReaderAction.VisibleItem && action !is MangaReaderAction.CommandHandled && action !is MangaReaderAction.NoticeHandled) return
        if (!viewModel.readerUiState.value.interactive && action !is MangaReaderAction.VisibleItem &&
            action !is MangaReaderAction.CommandHandled && action !is MangaReaderAction.NoticeHandled) return
        when (action) {
            is MangaReaderAction.VisibleItem -> viewModel.acceptVisibleItem(action.item, action.contentVersion, action.layoutVersion)
            is MangaReaderAction.CommandHandled -> viewModel.consumeReaderCommand(action.version)
            is MangaReaderAction.ShowMenu -> viewModel.setMenuVisible(action.visible)
            is MangaReaderAction.TurnPage -> viewModel.turnPage(action.direction)
            is MangaReaderAction.SeekPage -> viewModel.seekPage(action.index)
            is MangaReaderAction.Menu -> viewModel.handleMenuAction(action.action)
            is MangaReaderAction.NoticeHandled -> if (currentState.noticeMessage == action.message) viewModel.clearNoticeMessage()
            is MangaReaderAction.SaveImage -> {
                val page = currentState.items.filterIsInstance<MangaPage>().firstOrNull { it.mImageUrl == action.source } ?: return
                val directory = viewModel.savedImageDirectory()
                if (directory != null) viewModel.saveImage(page.mImageUrl, directory)
                else {
                    imageBook = currentState.bookUrl; imageChapter = page.chapterIndex; imagePage = page.index
                    imageHash = mangaImageIdentity(page.mImageUrl); imageTree = null
                    imageDirectory.launch(null)
                }
            }
            MangaReaderAction.Catalog -> scope.launch { viewModel.beginCatalog()?.let {
                onNavigate(BookTocRoute(it.bookUrl, readerCatalogRequestId = it.id))
            } }
            MangaReaderAction.Back -> viewModel.requestExit()
            MangaReaderAction.BookInfo -> viewModel.requestDetails()
            MangaReaderAction.ChapterLink -> {
                val url = currentState.chapterUrl
                if (url.isNotBlank()) {
                    if (viewModel.openReaderLinksInBrowser) context.openUrl(url.substringBefore(",{"))
                    else context.startActivity<WebViewActivity> {
                        putExtra("title", currentState.chapterName); putExtra("url", url)
                        viewModel.snapshot().source?.let { putExtra("sourceOrigin", it.bookSourceUrl)
                            putExtra("sourceName", it.bookSourceName); putExtra("sourceType", it.getSourceType()) }
                    }
                }
            }
            MangaReaderAction.ChapterLinkMode -> viewModel.showDialog(MangaReaderDialog.BrowserOpenMode)
            MangaReaderAction.ChangeSource -> openSource()
            MangaReaderAction.Refresh -> { viewModel.showLoading(); viewModel.snapshot().book?.let(viewModel::refreshContentDur) }
            MangaReaderAction.LoadNext -> viewModel.refreshContent()
            MangaReaderAction.PreviousChapter -> viewModel.moveToPreviousChapter(true)
            MangaReaderAction.NextChapter -> viewModel.moveToNextChapter(true)
            MangaReaderAction.Retry -> viewModel.retryLoading()
        }
    }
    Box(Modifier.fillMaxSize().onPreviewKeyEvent {
        if (it.key == Key.Menu && state.dialog == null && !state.loadingVisible) {
            if (it.type == KeyEventType.KeyDown) viewModel.setMenuVisible(!state.menuVisible)
            true
        } else false
    }) {
        MangaReaderScreen(state, viewModel.showReadTitleBarAddition, viewModel.globalEInkModeEnabled,
            onAction = ::handle,
            onDialogAction = { action ->
                if (!viewModel.handleDialogAction(action)) when (action) {
                    is MangaReaderDialogAction.Download -> viewModel.downloadRange(action.start,action.end,downloadFailure)
                    is MangaReaderDialogAction.Bookshelf -> viewModel.decideBookshelf(action.keep)
                    else -> Unit
                }
            }) {
            if (state.dialog == MangaReaderDialog.ChangeSource) Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                val book = viewModel.snapshot().book
                ChangeBookSourceScreen(sourceModel, oldBookType = book?.type ?: 0, oldBookUrl = book?.bookUrl,
                    onBack = { viewModel.showDialog(null) },
                    onSourceManage = { context.startActivity<BookSourceActivity>() },
                    onEditSource = { source -> editSource.launch(Intent(context, BookSourceEditActivity::class.java).putExtra("sourceUrl", source.origin)) },
                    onResult = { result -> when (result) {
                        is SourceChangeResult.Success -> if (result.book.isImage) {
                            val completion = SourceChangeCompletion(result.deleteAfterChange, sourceModel::del)
                            viewModel.showLoading()
                            viewModel.changeTo(result.book, result.toc, completion::success)
                            if (result.dismissDialog) viewModel.showDialog(null)
                        }
                        is SourceChangeResult.Error -> AppLog.put("换源获取目录出错\n${result.throwable.localizedMessage}", result.throwable, true)
                    } })
            }
        }
    }
}

private fun mangaImageIdentity(url: String): String = java.security.MessageDigest.getInstance("SHA-256")
    .digest(url.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(java.util.Locale.ROOT, it) }
