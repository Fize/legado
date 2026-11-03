package io.legado.app.ui.book.info

import io.legado.app.data.repository.infoSnapshot

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import dagger.hilt.android.AndroidEntryPoint
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.legado.app.R
import io.legado.app.base.BaseComposeActivity
import io.legado.app.constant.BookType
import io.legado.app.constant.EventBus
import io.legado.app.constant.Theme
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.help.book.addType
import io.legado.app.help.book.isAudio
import io.legado.app.help.book.isImage
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isVideo
import io.legado.app.help.book.isWebFile
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.model.SourceCallBack
import io.legado.app.ui.about.openAppLogs
import io.legado.app.ui.AppActivity
import io.legado.app.ui.navigation.OnlineImportRoute
import io.legado.app.ui.book.changecover.CoverSearchDialog
import io.legado.app.ui.book.changesource.ChangeBookSourceDialog
import io.legado.app.ui.book.group.BookGroupSelectionDialog
import io.legado.app.utils.bookReaderIntent
import io.legado.app.ui.book.read.ReadBookActivity.Companion.RESULT_DELETED
import io.legado.app.ui.navigation.openBookSearch
import io.legado.app.ui.navigation.EditBookInfoRoute
import io.legado.app.ui.book.source.edit.BookSourceEditActivity
import io.legado.app.ui.book.toc.TocActivityResult
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.navigation.SourceLoginRoute
import io.legado.app.ui.navigation.SourceLoginTarget
import io.legado.app.ui.variable.VariableEditorDialog
import io.legado.app.utils.GSON
import io.legado.app.utils.StartActivityContract
import io.legado.app.utils.longToastOnUi
import io.legado.app.utils.observeEvent
import io.legado.app.utils.openFileUri
import io.legado.app.utils.sendToClip
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import io.legado.app.ui.photo.openPhoto

@AndroidEntryPoint
class BookInfoActivity :
    BaseComposeActivity(
        fullScreen = true,
        theme = Theme.Auto,
        toolBarTheme = Theme.Dark,
        transparent = false,
        imageBg = true,
    ),
    ChangeBookSourceDialog.CallBack {

    private val tocActivityResult = registerForActivityResult(TocActivityResult()) {
        if (redirectIfRestoreRecoveryRequired()) return@registerForActivityResult
        it?.let {
            readFromChapter(
                index = it[0] as Int,
                pos = it[1] as Int,
                changed = it[2] as Boolean,
                volumeIndex = it[3] as Int,
                chapterInVolumeIndex = it[4] as Int,
                highlightLayoutTitleLength =
                    (it[TocActivityResult.HIGHLIGHT_LAYOUT_TITLE_LENGTH_INDEX] as Int)
                        .takeUnless { titleLength ->
                            titleLength == TocActivityResult.NO_HIGHLIGHT_LAYOUT_TITLE_LENGTH
                        },
                highlightAnchorText =
                    (it[TocActivityResult.HIGHLIGHT_ANCHOR_TEXT_INDEX] as String)
                        .takeIf(String::isNotEmpty),
            )
        } ?: let {
            if (!viewModel.inBookshelf) {
                viewModel.delBook() //进目录会保存book，此时退出目录触发的book删除，不通知书源回调
            }
        }
    }
    private val localBookTreeSelect = registerForActivityResult(HandleFileContract()) {
        if (redirectIfRestoreRecoveryRequired()) return@registerForActivityResult
        it.uri?.let { treeUri ->
            AppConfig.defaultBookTreeUri = treeUri.toString()
        }
    }
    private val readBookResult = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (redirectIfRestoreRecoveryRequired()) return@registerForActivityResult
        viewModel.upBook(
            name = intent.getStringExtra("name").orEmpty(),
            author = intent.getStringExtra("author").orEmpty(),
        )
        when (it.resultCode) {
            RESULT_OK -> {
                viewModel.inBookshelf = true
            }

            RESULT_DELETED -> {
                setResult(RESULT_OK)
                finish()
            }
        }
    }
    private val infoEditResult = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (redirectIfRestoreRecoveryRequired()) return@registerForActivityResult
        if (it.resultCode == RESULT_OK) {
            viewModel.upEditBook()
        }
    }
    private val editSourceResult = registerForActivityResult(
        StartActivityContract(BookSourceEditActivity::class.java)
    ) {
        if (redirectIfRestoreRecoveryRequired()) return@registerForActivityResult
        if (it.resultCode == RESULT_CANCELED) {
            return@registerForActivityResult
        }
        book?.let { book ->
            viewModel.refreshBookSource(book)
            viewModel.refreshBook(book)
        }
    }
    private var chapterChanged = false
    private val book get() = viewModel.getBook(false)

    override val guardsRestoreRecovery = true

    private val viewModel by viewModels<BookInfoViewModel>()


    override fun onActivityCreated(savedInstanceState: Bundle?) {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.map { it.bookRevision to it.book }.distinctUntilChanged().collect { (_, book) -> book?.let(::showBook) }
                }
                launch {
                    viewModel.uiState.map { it.fileImport.completion to it.book?.bookUrl }.distinctUntilChanged().collect { (completion, key) ->
                        completion ?: return@collect
                        if (completion.upload) return@collect
                        if (completion.bookUrl != null) {
                            if (key != completion.bookUrl) return@collect
                            val book = viewModel.getBook(false) ?: return@collect
                            viewModel.filesController.consumeCompletion(completion.id)
                            if (completion.read) readBook(book)
                        } else completion.uri?.let {
                            openFileUri(Uri.parse(it), "*/*")
                            viewModel.filesController.consumeCompletion(completion.id)
                        }
                    }
                }
                launch {
                    viewModel.uiState.map { it.libraryAction.completion to it.book?.bookUrl }.distinctUntilChanged().collect { (completed, key) ->
                        completed ?: return@collect
                        if (completed.deleted) {
                            viewModel.libraryController.consumeCompletion(completed.id)
                            setResult(RESULT_OK)
                            finish()
                        } else if (completed.read && completed.bookUrl == key) {
                            val book = viewModel.getBook(false) ?: return@collect
                            viewModel.libraryController.consumeCompletion(completed.id)
                            readBook(book)
                        }
                    }
                }
                launch {
                    viewModel.uiState.map { it.pendingActions }.distinctUntilChanged().collect { actions ->
                        val pending = actions.firstOrNull() ?: return@collect
                        val action = pending.action
                        try {
                            when (action) {
                                BookInfoAction.SelectBooksDir -> localBookTreeSelect.launch {
                                    title = getString(R.string.select_book_folder)
                                }
                                is BookInfoAction.ShowToast -> toastOnUi(action.message)
                                is BookInfoAction.ShowToastResource -> toastOnUi(action.resourceId)
                                is BookInfoAction.RunScript -> {
                                    val source = viewModel.bookSource?.takeIf { it.bookSourceUrl == action.sourceKey }?.infoSnapshot()
                                    val book = viewModel.getBook(false)?.takeIf { it.bookUrl == action.bookUrl }
                                    if (source != null && book != null) lifecycleScope.launch(IO) {
                                        try {
                                            runBookInfoScript(this@BookInfoActivity, source, book, action.script)
                                            viewModel.completeScript(action, book)
                                        } catch (error: Exception) {
                                            currentCoroutineContext().ensureActive()
                                            viewModel.reportScriptError(action, error)
                                        }
                                    }
                                }
                            }
                        } finally {
                            viewModel.consumeUiAction(pending)
                        }
                    }
                }
            }
        }
        viewModel.initData(
            bookUrl = intent.getStringExtra("bookUrl").orEmpty(),
            name = intent.getStringExtra("name").orEmpty(),
            author = intent.getStringExtra("author").orEmpty(),
        )
    }

    override fun observeLiveBus() {
        observeEvent<Boolean>(EventBus.REFRESH_BOOK_INFO) {
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                refreshBook()
            }
        }

        observeEvent<Boolean>(EventBus.REFRESH_BOOK_TOC) {
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                refreshToc()
            }
        }
    }

    private fun refreshBook() {
        viewModel.getBook()?.let {
            viewModel.refreshBook(it)
        }
    }

    private fun refreshToc() {
        viewModel.getBook()?.let {
            viewModel.loadChapter(it, true, isFromBookInfo = true)
        }
    }


    private fun showBook(book: Book) {
        viewModel.refreshCover()
        viewModel.loadKinds(book)
        upGroup(book.group)
    }

    private fun upGroup(groupId: Long) {
        viewModel.loadGroup(groupId)
    }

    private fun onTocViewClick() {
        if (viewModel.uiState.value.shelfMutation) return
        if (viewModel.uiState.value.chapters.isEmpty()) {
            toastOnUi(R.string.chapter_list_empty)
            return
        }
        val book = viewModel.getBook(false)
        if (book == null) {
            toastOnUi(R.string.book_not_exist)
            return
        }
        if (!viewModel.inBookshelf) {
            viewModel.saveBook(book, withChapters = true) {
                openChapterList()
            }
        } else {
            openChapterList()
        }
    }

    private fun setSourceVariable() = viewModel.openSourceVariableEditor()

    private fun setBookVariable() = viewModel.openBookVariableEditor()


    private fun openChapterList() {
        viewModel.getBook()?.let {
            tocActivityResult.launch(it.bookUrl)
        }
    }

    private fun readFromChapter(
        index: Int,
        pos: Int,
        changed: Boolean,
        volumeIndex: Int,
        chapterInVolumeIndex: Int,
        highlightLayoutTitleLength: Int?,
        highlightAnchorText: String?,
    ) {
        viewModel.getBook(false)?.let { book ->
            val deferHighlightPosition = highlightLayoutTitleLength != null &&
                !book.isAudio && !book.isVideo &&
                (book.isLocal || !book.isImage || !AppConfig.showMangaUi)
            if (!deferHighlightPosition) {
                book.durChapterIndex = index
                book.durChapterPos = pos
                book.durVolumeIndex = volumeIndex
                book.chapterInVolumeIndex = chapterInVolumeIndex
            }
            chapterChanged = changed
            if (!viewModel.inBookshelf) book.addType(BookType.notShelf)
            viewModel.saveBook(
                book,
                withChapters = !viewModel.inBookshelf,
                preserveReadingProgress = false,
            ) {
                startReadActivity(
                    book,
                    index.takeIf { deferHighlightPosition },
                    pos.takeIf { deferHighlightPosition },
                    highlightLayoutTitleLength.takeIf { deferHighlightPosition },
                    highlightAnchorText.takeIf { deferHighlightPosition }
                )
            }
        }
    }


    private fun readBook(book: Book) {
        if (viewModel.uiState.value.shelfMutation) return
        if (!viewModel.inBookshelf) {
            book.addType(BookType.notShelf)
            viewModel.saveBook(book, withChapters = true) {
                startReadActivity(book)
            }
        } else {
            viewModel.saveBook(book) {
                startReadActivity(book)
            }
        }
    }

    private fun startReadActivity(
        book: Book,
        highlightIndex: Int? = null,
        highlightChapterPos: Int? = null,
        highlightLayoutTitleLength: Int? = null,
        highlightAnchorText: String? = null,
    ) {
        readBookResult.launch(bookReaderIntent(book) {
            putExtra("inBookshelf", viewModel.inBookshelf)
            putExtra("chapterChanged", chapterChanged)
            if (highlightIndex != null && highlightChapterPos != null && highlightLayoutTitleLength != null) {
                putExtra("index", highlightIndex)
                putExtra("chapterPos", highlightChapterPos)
                putExtra(TocActivityResult.EXTRA_HIGHLIGHT_LAYOUT_TITLE_LENGTH, highlightLayoutTitleLength)
                highlightAnchorText?.let { putExtra(TocActivityResult.EXTRA_HIGHLIGHT_ANCHOR_TEXT, it) }
            }
        })
    }

    override val oldBook: Book?
        get() = viewModel.getBook(false)

    override fun changeTo(
        source: BookSource,
        book: Book,
        toc: List<BookChapter>,
        onSuccess: () -> Unit,
    ) {
        viewModel.changeTo(source, book, toc, onSuccess)
    }



    private fun handleScreenAction(action: BookInfoUiAction) {
        when (action) {
            BookInfoUiAction.Back -> finish()
            is BookInfoUiAction.CancelDelete -> viewModel.cancelPendingDelete(action.id)
            is BookInfoUiAction.CancelUpload -> viewModel.cancelPendingUpload(action.id)
            BookInfoUiAction.Refresh -> refreshBook()
            BookInfoUiAction.Top -> viewModel.topBook()
            BookInfoUiAction.SourceVariable -> setSourceVariable()
            BookInfoUiAction.BookVariable -> setBookVariable()
            BookInfoUiAction.Upload -> viewModel.requestUpload()
            BookInfoUiAction.Logs -> openAppLogs()
            BookInfoUiAction.Toc -> onTocViewClick()
            BookInfoUiAction.Group -> viewModel.openGroupSelection()
            BookInfoUiAction.Cover -> viewModel.openCoverSearch()
            BookInfoUiAction.CoverPhoto -> viewModel.getBook(false)?.let { book ->
                book.getDisplayCover()?.let { openPhoto(it, book.getCoverSourceOrigin(), book) }
            }
            BookInfoUiAction.Edit -> viewModel.getBook()?.let {
                infoEditResult.launch(AppActivity.startIntent(this, EditBookInfoRoute(it.bookUrl)))
            }
            BookInfoUiAction.Login -> viewModel.uiState.value.source?.let {
                startActivity(AppActivity.startIntent(this, SourceLoginRoute(SourceLoginTarget.BookSource,
                    key = it.bookSourceUrl, bookUrl = book?.bookUrl)))
            }
            BookInfoUiAction.EditSource -> viewModel.getBook()?.let {
                if (it.isLocal) return
                if (!viewModel.hasBookSource(it.origin)) toastOnUi(R.string.error_no_source)
                else editSourceResult.launch { putExtra("sourceUrl", it.origin) }
            }
            BookInfoUiAction.ChangeSource -> viewModel.getBook()?.let {
                showDialogFragment(ChangeBookSourceDialog(it.name, it.author))
            }
            BookInfoUiAction.Shelf -> {
                if (viewModel.uiState.value.shelfMutation) return
                viewModel.getBook()?.let {
                    when {
                        viewModel.inBookshelf -> viewModel.requestDelete()
                        it.isWebFile -> viewModel.openWebFiles()
                        else -> viewModel.addToBookshelf(null)
                    }
                }
            }
            BookInfoUiAction.Read -> viewModel.getBook()?.let {
                if (it.isWebFile) viewModel.openWebFiles(readAfterImport = true) else readBook(it)
            }
            is BookInfoUiAction.CanUpdate -> viewModel.setCanUpdate(action.enabled)
            is BookInfoUiAction.SplitLongChapter -> {
                viewModel.getBook()?.let { it.setSplitLongChapter(action.enabled); viewModel.loadBookInfo(it, false) }
                if (!action.enabled) longToastOnUi(R.string.need_more_time_load_content)
            }
            is BookInfoUiAction.DeleteAlert -> LocalConfig.bookInfoDeleteAlert = action.enabled
            is BookInfoUiAction.Callback -> handleCallback(action)
            BookInfoUiAction.IntroToggle -> viewModel.toggleIntroExpanded()
            BookInfoUiAction.IntroRetry -> viewModel.retryIntro()
            is BookInfoUiAction.IntroMeasured -> viewModel.onIntroMeasured(action.key, action.collapsible)
            is BookInfoUiAction.IntroButton -> viewModel.onIntroButtonClick(action.key, "info button ${action.name}", action.script)
            is BookInfoUiAction.IntroImage -> if (viewModel.uiState.value.intro.contentKey == action.key) {
                openPhoto(action.url, viewModel.uiState.value.source?.bookSourceUrl)
            }
            is BookInfoUiAction.IntroLink -> {
                val state = viewModel.uiState.value
                if (state.intro.contentKey != action.key) return
                when {
                    action.url.startsWith("javascript:", true) -> viewModel.onIntroButtonClick(action.key, "info link", action.url.substringAfter(':'))
                    action.url.startsWith("data:image/", true) -> openPhoto(action.url, state.source?.bookSourceUrl)
                    action.url.startsWith("legado://") || action.url.startsWith("yuedu://") -> startActivity(AppActivity.startIntent(this, OnlineImportRoute(action.url)))
                    else -> startActivity(AppActivity.startIntent(this, io.legado.app.ui.navigation.OpenUrlConfirmRoute(
                        uri = action.url, sourceOrigin = state.source?.bookSourceUrl, sourceName = state.source?.bookSourceName)))
                }
            }
        }
    }

    private fun handleCallback(action: BookInfoUiAction.Callback) {
        val book = viewModel.getBook(false) ?: return
        val source = viewModel.uiState.value.source
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
        SourceCallBack.callBackBtn(this, event, source, book, null, result = result) {
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) ||
                viewModel.uiState.value.book?.bookUrl != book.bookUrl) return@callBackBtn
            when (action.event) {
                BookInfoCallbackEvent.Share -> startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); putExtra(Intent.EXTRA_TEXT, result); type = "text/plain"
                }, book.name))
                BookInfoCallbackEvent.CopyBookUrl, BookInfoCallbackEvent.CopyTocUrl -> result?.let(::sendToClip)
                BookInfoCallbackEvent.ClearCache -> viewModel.clearCache(book)
                BookInfoCallbackEvent.Name, BookInfoCallbackEvent.LongName -> openBookSearch(book.name)
                BookInfoCallbackEvent.Author, BookInfoCallbackEvent.LongAuthor -> openBookSearch(book.author)
                BookInfoCallbackEvent.Kind -> if (source != null && action.argument != null) openBookSearch(source, action.argument)
                BookInfoCallbackEvent.Custom, BookInfoCallbackEvent.LongKind -> Unit
            }
        }
    }

    @Composable
    override fun Content() {
        val state by viewModel.uiState.collectAsStateWithLifecycle()
        state.groupsEditor?.let { BookGroupSelectionDialog(it, viewModel.groupsController) }
        state.variableEditor?.let {
            VariableEditorDialog(it, viewModel::updateVariableDraft, viewModel::saveVariableEditor,
                viewModel::dismissVariableEditor, viewModel::retryVariableEditor)
        }
        CoverSearchDialog(state.coverSearch, viewModel.coverController)
        BookInfoLibraryDialog(state.libraryAction, viewModel.libraryController)
        BookInfoFilesDialog(state.fileImport, state.webFiles, viewModel.filesController,
            state.book?.bookUrl == state.fileImport.bookUrl, viewModel::setImportedFileUpload)
        BookInfoScreen(state, LocalConfig.bookInfoDeleteAlert, ::handleScreenAction)
    }

}
