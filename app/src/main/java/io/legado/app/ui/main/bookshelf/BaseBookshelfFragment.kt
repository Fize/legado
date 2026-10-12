package io.legado.app.ui.main.bookshelf

import android.annotation.SuppressLint
import android.content.Intent
import androidx.annotation.LayoutRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LiveData
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.VMBaseFragment
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.databinding.DialogEditTextBinding
import io.legado.app.help.DirectLinkUpload
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.book.cache.CacheActivity
import io.legado.app.ui.book.group.GroupManageDialog
import io.legado.app.ui.book.import.local.ImportBookActivity
import io.legado.app.ui.book.import.remote.RemoteBookActivity
import io.legado.app.ui.book.info.BookInfoActivity
import io.legado.app.ui.book.manage.BookshelfManageActivity
import io.legado.app.ui.book.search.SearchActivity
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.main.MainFragmentInterface
import io.legado.app.ui.main.MainViewModel
import io.legado.app.ui.widget.dialog.WaitDialog
import io.legado.app.utils.flowWithLifecycleAndDatabaseChangeFirst
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.postEvent
import io.legado.app.utils.readText
import io.legado.app.utils.sendToClip
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.startActivityForBook
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

abstract class BaseBookshelfFragment(@LayoutRes layoutId: Int = 0) : VMBaseFragment<BookshelfViewModel>(layoutId),
    MainFragmentInterface {

    override val position: Int? get() = arguments?.getInt("position")

    val activityViewModel by activityViewModels<MainViewModel>()
    override val viewModel by viewModels<BookshelfViewModel>()

    private val importBookshelf = registerForActivityResult(HandleFileContract()) {
        kotlin.runCatching {
            it.uri?.readText(requireContext())?.let { text ->
                viewModel.importBookshelf(text, groupId)
            }
        }.onFailure {
            toastOnUi(it.localizedMessage ?: "ERROR")
        }
    }
    private val exportResult = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            alert(R.string.export_success) {
                if (uri.toString().isAbsUrl()) {
                    setMessage(DirectLinkUpload.getSummary())
                }
                val alertBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
                    editView.hint = getString(R.string.path)
                    editView.setText(uri.toString())
                }
                customView { alertBinding.root }
                okButton {
                    requireContext().sendToClip(uri.toString())
                }
            }
        }
    }
    abstract val groupId: Long
    abstract val books: List<Book>
    abstract var onlyUpdateRead: Boolean
    private var groupsLiveData: LiveData<List<BookGroup>>? = null
    private val waitDialog by lazy {
        WaitDialog(requireContext()).apply {
            setOnCancelListener {
                viewModel.addBookJob?.cancel()
            }
        }
    }

    protected var continueBook by mutableStateOf<Book?>(null)
    protected var showShelfStatsState by mutableStateOf(false)
    protected var shelfStatsTextState by mutableStateOf("")
    protected var showConfigDialogState by mutableStateOf(false)
    private var shelfHeaderFlowJob: Job? = null

    override fun onDestroyView() {
        shelfHeaderFlowJob?.cancel()
        shelfHeaderFlowJob = null
        continueBook = null
        super.onDestroyView()
    }

    protected fun subscribeShelfHeaderRefresh() {
        shelfHeaderFlowJob?.cancel()
        shelfHeaderFlowJob = null
        continueBook = null
        val showRecentReading = AppConfig.showBookshelfRecentReading
        val showBookshelfStats = AppConfig.showBookshelfStats
        showShelfStatsState = showBookshelfStats
        if (!showRecentReading && !showBookshelfStats) return

        shelfHeaderFlowJob = viewLifecycleOwner.lifecycleScope.launch {
            appDb.bookDao.flowShelfBookCount()
                .flowWithLifecycleAndDatabaseChangeFirst(
                    viewLifecycleOwner.lifecycle,
                    Lifecycle.State.RESUMED,
                    AppDatabase.BOOK_TABLE_NAME
                )
                .catch { AppLog.put("书架头部刷新出错", it) }
                .conflate()
                .flowOn(Dispatchers.Default)
                .collect { bookCount ->
                    val (book, readingCount) = withContext(Dispatchers.IO) {
                        val book = if (showRecentReading) {
                            appDb.bookDao.lastReadBookOnShelf
                        } else {
                            null
                        }
                        val readingCount = if (showBookshelfStats) {
                            appDb.bookDao.readingCount
                        } else {
                            0
                        }
                        book to readingCount
                    }
                    continueBook = book
                    if (showBookshelfStats) {
                        shelfStatsTextState =
                            getString(R.string.bookshelf_stats, bookCount, readingCount)
                    }
                }
        }
    }

    @Composable
    protected fun ShelfHeaderComposable() {
        BookshelfHeaderSection(
            showStats = showShelfStatsState,
            statsText = shelfStatsTextState,
            continueBook = continueBook,
            onContinueClick = { startActivityForBook(it) },
            onContinueLongClick = { book ->
                startActivity(
                    Intent(requireContext(), BookInfoActivity::class.java).apply {
                        putExtra("name", book.name)
                        putExtra("author", book.author)
                    }
                )
            },
        )
    }

    @Composable
    protected fun BookshelfDialogsComposable() {
        if (showConfigDialogState) {
            BookshelfConfigDialog(
                initialState = BookshelfConfigState(
                    groupStyle = AppConfig.bookGroupStyle,
                    readProgressMode = AppConfig.bookshelfReadProgressMode,
                    showUnread = AppConfig.showUnread,
                    showLastUpdateTime = AppConfig.showLastUpdateTime,
                    showWaitUpBooks = AppConfig.showWaitUpCount,
                    showFastScroller = AppConfig.showBookshelfFastScroller,
                    showRecentReading = AppConfig.showBookshelfRecentReading,
                    showBookshelfStats = AppConfig.showBookshelfStats,
                    bookshelfLayout = AppConfig.bookshelfLayout,
                    bookshelfSort = AppConfig.bookshelfSort,
                    showBookname = AppConfig.showBookname,
                    margin = AppConfig.bookshelfMargin,
                ),
                onDismiss = { showConfigDialogState = false },
                onConfirm = ::applyBookshelfConfig,
            )
        }
    }

    abstract fun gotoTop()

    protected fun handleBookshelfMenuAction(action: BookshelfMenuAction) {
        when (action) {
            BookshelfMenuAction.SEARCH -> startActivity<SearchActivity>()
            BookshelfMenuAction.UPDATE_TOC -> activityViewModel.upToc(books, onlyUpdateRead)
            BookshelfMenuAction.ADD_LOCAL -> startActivity<ImportBookActivity>()
            BookshelfMenuAction.ADD_REMOTE -> startActivity<RemoteBookActivity>()
            BookshelfMenuAction.ADD_URL -> showAddBookByUrlAlert()
            BookshelfMenuAction.BOOKSHELF_MANAGE -> startActivity<BookshelfManageActivity> {
                putExtra("groupId", groupId)
            }
            BookshelfMenuAction.CACHE_EXPORT -> startActivity<CacheActivity> {
                putExtra("groupId", groupId)
            }
            BookshelfMenuAction.GROUP_MANAGE -> showDialogFragment(GroupManageDialog())
            BookshelfMenuAction.BOOKSHELF_LAYOUT -> configBookshelf()
            BookshelfMenuAction.EXPORT_BOOKSHELF -> viewModel.exportBookshelf(books) { file ->
                exportResult.launch {
                    mode = HandleFileContract.EXPORT
                    fileData =
                        HandleFileContract.FileData("bookshelf.json", file, "application/json")
                }
            }
            BookshelfMenuAction.IMPORT_BOOKSHELF -> importBookshelfAlert(groupId)
            BookshelfMenuAction.LOG -> showDialogFragment<AppLogDialog>()
        }
    }

    protected fun initBookGroupData() {
        groupsLiveData?.removeObservers(viewLifecycleOwner)
        groupsLiveData = appDb.bookGroupDao.show.apply {
            observe(viewLifecycleOwner) {
                upGroup(it)
            }
        }
    }

    abstract fun upGroup(data: List<BookGroup>)

    abstract fun upSort()

    override fun observeLiveBus() {
        viewModel.addBookProgressLiveData.observe(this) { count ->
            if (count < 0) {
                waitDialog.dismiss()
            } else {
                waitDialog.setText("添加中... ($count)")
            }
        }
    }

    @SuppressLint("InflateParams")
    fun showAddBookByUrlAlert() {
        alert(titleResource = R.string.add_book_url) {
            val alertBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
                editView.hint = "url"
            }
            customView { alertBinding.root }
            okButton {
                alertBinding.editView.text?.toString()?.let {
                    waitDialog.setText("添加中...")
                    waitDialog.show()
                    viewModel.addBookByUrl(it, groupId)
                }
            }
            cancelButton()
        }
    }

    fun configBookshelf() {
        showConfigDialogState = true
    }

    private fun applyBookshelfConfig(newState: BookshelfConfigState) {
        var notifyMain = false
        var recreate = false
        val oldShowBookname = AppConfig.showBookname
        val oldReadProgressMode = AppConfig.bookshelfReadProgressMode
        val oldBookshelfSort = AppConfig.bookshelfSort
        val oldBookshelfLayout = AppConfig.bookshelfLayout

        if (AppConfig.bookGroupStyle != newState.groupStyle) {
            AppConfig.bookGroupStyle = newState.groupStyle
            notifyMain = true
        }
        if (oldShowBookname != newState.showBookname) {
            AppConfig.showBookname = newState.showBookname
            recreate = true
        }
        if (AppConfig.bookshelfMargin != newState.margin) {
            AppConfig.bookshelfMargin = newState.margin
            recreate = true
        }
        if (AppConfig.showUnread != newState.showUnread) {
            AppConfig.showUnread = newState.showUnread
            postEvent(EventBus.BOOKSHELF_REFRESH, "")
        }
        if (AppConfig.showLastUpdateTime != newState.showLastUpdateTime) {
            AppConfig.showLastUpdateTime = newState.showLastUpdateTime
            postEvent(EventBus.BOOKSHELF_REFRESH, "")
        }
        if (oldReadProgressMode != newState.readProgressMode) {
            AppConfig.bookshelfReadProgressMode = newState.readProgressMode
            postEvent(EventBus.BOOKSHELF_REFRESH, "")
        }
        if (AppConfig.showWaitUpCount != newState.showWaitUpBooks) {
            AppConfig.showWaitUpCount = newState.showWaitUpBooks
            activityViewModel.postUpBooksLiveData(true)
        }
        if (AppConfig.showBookshelfFastScroller != newState.showFastScroller) {
            AppConfig.showBookshelfFastScroller = newState.showFastScroller
            postEvent(EventBus.BOOKSHELF_REFRESH, "")
        }
        if (AppConfig.showBookshelfRecentReading != newState.showRecentReading) {
            AppConfig.showBookshelfRecentReading = newState.showRecentReading
            recreate = true
        }
        if (AppConfig.showBookshelfStats != newState.showBookshelfStats) {
            AppConfig.showBookshelfStats = newState.showBookshelfStats
            recreate = true
        }
        if (oldBookshelfSort != newState.bookshelfSort) {
            AppConfig.bookshelfSort = newState.bookshelfSort
            upSort()
        }
        if (oldBookshelfLayout != newState.bookshelfLayout) {
            AppConfig.bookshelfLayout = newState.bookshelfLayout
            recreate = true
        }
        if (recreate) {
            postEvent(EventBus.RECREATE, "")
        } else if (notifyMain) {
            postEvent(EventBus.NOTIFY_MAIN, false)
        }
    }

    private fun importBookshelfAlert(groupId: Long) {
        alert(titleResource = R.string.import_bookshelf) {
            val alertBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
                editView.hint = "url/json"
            }
            customView { alertBinding.root }
            okButton {
                alertBinding.editView.text?.toString()?.let {
                    viewModel.importBookshelf(it, groupId)
                }
            }
            cancelButton()
            neutralButton(R.string.select_file) {
                importBookshelf.launch {
                    mode = HandleFileContract.FILE
                    allowExtensions = arrayOf("txt", "json")
                }
            }
        }
    }

}
