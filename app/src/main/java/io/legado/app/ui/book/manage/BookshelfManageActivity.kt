package io.legado.app.ui.book.manage

import android.os.Bundle
import android.widget.CheckBox
import android.widget.LinearLayout
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.VMBaseComposeActivity
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.data.entities.BookSource
import io.legado.app.help.DirectLinkUpload
import io.legado.app.help.book.contains
import io.legado.app.help.book.isLocal
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.ui.book.group.GroupManageDialog
import io.legado.app.ui.book.group.GroupSelectDialog
import io.legado.app.ui.book.info.BookInfoActivity
import io.legado.app.ui.compose.component.LegadoMenuAction
import io.legado.app.ui.compose.component.LegadoSelectActionBar
import io.legado.app.ui.compose.component.LegadoTopAppBar
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.main.filterBooksForTocUpdate
import io.legado.app.ui.widget.dialog.WaitDialog
import io.legado.app.utils.cnCompare
import io.legado.app.utils.dpToPx
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.postEvent
import io.legado.app.utils.sendToClip
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Collections
import kotlin.math.max

/**
 * 书架管理
 */
class BookshelfManageActivity :
    VMBaseComposeActivity<BookshelfManageViewModel>(),
    SourcePickerDialog.Callback,
    GroupSelectDialog.CallBack {

    override val viewModel by viewModels<BookshelfManageViewModel>()
    private val itemGroupRequestCode = 12
    private val groupRequestCode = 22
    private val addToGroupRequestCode = 34
    private val removeToGroupRequestCode = 42
    private var booksFlowJob: Job? = null

    private var booksState by mutableStateOf<List<Book>>(emptyList())
    private var groupListState by mutableStateOf<List<BookGroup>>(emptyList())
    private var groupNameState by mutableStateOf("")
    private var searchQueryState by mutableStateOf("")
    private var selectedBookUrlsState by mutableStateOf<Set<String>>(emptySet())
    private var openBookInfoByClickTitleState by mutableStateOf(AppConfig.openBookInfoByClickTitle)
    private var actionItem: Book? = null

    private val waitDialog by lazy { WaitDialog(this) }
    private val exportDir = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            alert(R.string.export_success) {
                if (uri.toString().isAbsUrl()) {
                    setMessage(DirectLinkUpload.getSummary() + "\n\n" + uri)
                }
                if (!uri.toString().isAbsUrl()) setMessage(uri.toString())
                okButton {
                    sendToClip(uri.toString())
                }
            }
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        viewModel.groupId = intent.getLongExtra("groupId", -1)
        lifecycleScope.launch {
            viewModel.groupName = withContext(IO) {
                appDb.bookGroupDao.getByID(viewModel.groupId)?.groupName
                    ?: getString(R.string.no_group)
            }
            groupNameState = viewModel.groupName.orEmpty()
        }
        waitDialog.setOnCancelListener {
            viewModel.batchChangeSourceCoroutine?.cancel()
            viewModel.batchPersistCoverCoroutine?.cancel()
        }
        initGroupData()
        upBookDataByGroupId()
    }

    override fun observeLiveBus() {
        viewModel.batchChangeSourceState.observe(this) {
            if (it) {
                waitDialog.setText(R.string.change_source_batch)
                waitDialog.show()
            } else {
                waitDialog.dismiss()
            }
        }
        viewModel.batchChangeSourceProcessLiveData.observe(this) {
            waitDialog.setText(it)
        }
        viewModel.batchPersistCoverState.observe(this) {
            if (it) {
                waitDialog.setText(R.string.persist_network_covers)
                waitDialog.show()
            } else {
                waitDialog.dismiss()
            }
        }
        viewModel.batchPersistCoverProcess.observe(this) {
            waitDialog.setText(it)
        }
    }

    private val displayedBooks: List<Book>
        get() = if (searchQueryState.isBlank()) {
            booksState
        } else {
            booksState.filter { it.contains(searchQueryState) }
        }

    private val selection: List<Book>
        get() = displayedBooks.filter { it.bookUrl in selectedBookUrlsState }

    @Composable
    override fun Content() {
        var groupMenuExpanded by remember { mutableStateOf(false) }
        val filteredBooks = remember(booksState, searchQueryState) {
            if (searchQueryState.isBlank()) {
                booksState
            } else {
                booksState.filter { it.contains(searchQueryState) }
            }
        }
        val selectedBooks = remember(filteredBooks, selectedBookUrlsState) {
            filteredBooks.filter { it.bookUrl in selectedBookUrlsState }
        }

        Scaffold(
            topBar = {
                LegadoTopAppBar(
                    title = stringResource(R.string.bookshelf_management),
                    subtitle = groupNameState,
                    onBack = { finish() },
                    searchQuery = searchQueryState,
                    onSearchQueryChange = { searchQueryState = it },
                    searchPlaceholder = stringResource(R.string.screen) + " • " + groupNameState,
                    actions = {
                        Box {
                            IconButton(onClick = { groupMenuExpanded = true }) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_groups),
                                    contentDescription = stringResource(R.string.group),
                                    modifier = Modifier.size(22.dp),
                                )
                            }
                            DropdownMenu(
                                expanded = groupMenuExpanded,
                                onDismissRequest = { groupMenuExpanded = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text(text = stringResource(R.string.group_manage)) },
                                    onClick = {
                                        groupMenuExpanded = false
                                        showDialogFragment<GroupManageDialog>()
                                    },
                                )
                                HorizontalDivider()
                                groupListState.forEach { bookGroup ->
                                    DropdownMenuItem(
                                        text = { Text(text = bookGroup.groupName) },
                                        onClick = {
                                            groupMenuExpanded = false
                                            viewModel.groupName = bookGroup.groupName
                                            groupNameState = bookGroup.groupName
                                            viewModel.groupId = bookGroup.groupId
                                            upBookDataByGroupId()
                                        },
                                    )
                                }
                            }
                        }
                    },
                    menuActions = listOf(
                        LegadoMenuAction(
                            title = stringResource(R.string.export_all_use_book_source),
                            onClick = {
                                viewModel.saveAllUseBookSourceToFile { file ->
                                    exportDir.launch {
                                        mode = HandleFileContract.EXPORT
                                        fileData = HandleFileContract.FileData(
                                            "bookSource.json",
                                            file,
                                            "application/json"
                                        )
                                    }
                                }
                            },
                        ),
                        LegadoMenuAction(
                            title = stringResource(R.string.open_book_info_by_click_title),
                            checked = openBookInfoByClickTitleState,
                            onClick = {
                                val next = !openBookInfoByClickTitleState
                                AppConfig.openBookInfoByClickTitle = next
                                openBookInfoByClickTitleState = next
                            },
                        ),
                    ),
                )
            },
            bottomBar = {
                LegadoSelectActionBar(
                    modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars),
                    selectedCount = selectedBooks.size,
                    totalCount = filteredBooks.size,
                    onSelectAllChange = { selectAll ->
                        selectedBookUrlsState = if (selectAll) {
                            filteredBooks.mapTo(linkedSetOf()) { it.bookUrl }
                        } else {
                            emptySet()
                        }
                    },
                    onRevertSelection = {
                        selectedBookUrlsState = filteredBooks.mapNotNullTo(linkedSetOf()) {
                            if (it.bookUrl in selectedBookUrlsState) null else it.bookUrl
                        }
                    },
                    mainActionText = stringResource(R.string.move_to_group),
                    onMainActionClick = { selectGroup(groupRequestCode, 0) },
                    menuActions = listOf(
                        LegadoMenuAction(
                            title = stringResource(R.string.delete),
                            onClick = ::alertDelSelection,
                        ),
                        LegadoMenuAction(
                            title = stringResource(R.string.allow_update),
                            onClick = { viewModel.upCanUpdate(selection, true) },
                        ),
                        LegadoMenuAction(
                            title = stringResource(R.string.disable_update),
                            onClick = { viewModel.upCanUpdate(selection, false) },
                        ),
                        LegadoMenuAction(
                            title = stringResource(R.string.add_to_group),
                            onClick = { selectGroup(addToGroupRequestCode, 0) },
                        ),
                        LegadoMenuAction(
                            title = stringResource(R.string.remove_group),
                            onClick = { selectGroup(removeToGroupRequestCode, 0) },
                        ),
                        LegadoMenuAction(
                            title = stringResource(R.string.change_source_batch),
                            onClick = { showDialogFragment<SourcePickerDialog>() },
                        ),
                        LegadoMenuAction(
                            title = stringResource(R.string.clear_cache),
                            onClick = { viewModel.clearCache(selection) },
                        ),
                        LegadoMenuAction(
                            title = stringResource(R.string.persist_network_covers),
                            onClick = { viewModel.persistNetworkCovers(selection) },
                        ),
                        LegadoMenuAction(
                            title = stringResource(R.string.restore_network_covers),
                            onClick = ::alertRestoreNetworkCovers,
                        ),
                        LegadoMenuAction(
                            title = stringResource(R.string.restore_source_covers),
                            onClick = ::alertRestoreSourceCovers,
                        ),
                        LegadoMenuAction(
                            title = stringResource(R.string.check_selected_interval),
                            onClick = { checkSelectedInterval(filteredBooks) },
                        ),
                        LegadoMenuAction(
                            title = stringResource(R.string.update_book_info_toc),
                            onClick = ::updateBooksToc,
                        ),
                    ),
                )
            },
        ) { innerPadding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                items(
                    items = filteredBooks,
                    key = { it.bookUrl },
                ) { item ->
                    val isSelected = item.bookUrl in selectedBookUrlsState
                    val groupText = remember(item.group, groupListState) {
                        getGroupName(item.group)
                    }

                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedBookUrlsState = if (isSelected) {
                                        selectedBookUrlsState - item.bookUrl
                                    } else {
                                        selectedBookUrlsState + item.bookUrl
                                    }
                                }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = isSelected,
                                onCheckedChange = { checked ->
                                    selectedBookUrlsState = if (checked) {
                                        selectedBookUrlsState + item.bookUrl
                                    } else {
                                        selectedBookUrlsState - item.bookUrl
                                    }
                                },
                            )
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 4.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = item.name,
                                        style = MaterialTheme.typography.bodyLarge,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = if (openBookInfoByClickTitleState) {
                                            Modifier.clickable { openBook(item) }
                                        } else {
                                            Modifier
                                        },
                                    )
                                    if (item.author.isNotEmpty()) {
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Text(
                                            text = item.author,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(
                                        text = if (item.isLocal) stringResource(R.string.local_book) else item.originName,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = groupText.ifEmpty { stringResource(R.string.no_group) },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    TextButton(
                                        onClick = {
                                            actionItem = item
                                            selectGroup(itemGroupRequestCode, item.group)
                                        },
                                    ) {
                                        Text(text = stringResource(R.string.group))
                                    }
                                    TextButton(
                                        onClick = { deleteBook(item) },
                                    ) {
                                        Text(
                                            text = stringResource(R.string.delete),
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    private fun checkSelectedInterval(books: List<Book>) {
        val selectedPosition = linkedSetOf<Int>()
        books.forEachIndexed { index, it ->
            if (it.bookUrl in selectedBookUrlsState) {
                selectedPosition.add(index)
            }
        }
        if (selectedPosition.isEmpty()) return
        val minPosition = Collections.min(selectedPosition)
        val maxPosition = Collections.max(selectedPosition)
        val updated = selectedBookUrlsState.toMutableSet()
        for (i in minPosition..maxPosition) {
            books.getOrNull(i)?.let {
                updated.add(it.bookUrl)
            }
        }
        selectedBookUrlsState = updated
    }

    private fun getGroupList(groupId: Long): List<String> {
        val groupNames = arrayListOf<String>()
        groupListState.forEach {
            if (it.groupId > 0 && it.groupId and groupId > 0) {
                groupNames.add(it.groupName)
            }
        }
        return groupNames
    }

    private fun getGroupName(groupId: Long): String {
        val groupNames = getGroupList(groupId)
        if (groupNames.isEmpty()) {
            return ""
        }
        return groupNames.joinToString(",")
    }

    private fun initGroupData() {
        lifecycleScope.launch {
            appDb.bookGroupDao.flowAll().catch {
                AppLog.put("书架管理界面获取分组数据失败\n${it.localizedMessage}", it)
            }.flowOn(IO).conflate().collect {
                groupListState = it
            }
        }
    }

    private fun upBookDataByGroupId() {
        booksFlowJob?.cancel()
        booksFlowJob = lifecycleScope.launch {
            val bookSort = AppConfig.getBookSortByGroupId(viewModel.groupId)
            appDb.bookDao.flowByGroup(viewModel.groupId).map { list ->
                when (bookSort) {
                    1 -> list.sortedByDescending {
                        it.latestChapterTime
                    }

                    2 -> list.sortedWith { o1, o2 ->
                        o1.name.cnCompare(o2.name)
                    }

                    3 -> list.sortedBy {
                        it.order
                    }

                    4 -> list.sortedByDescending {
                        max(it.latestChapterTime, it.durChapterTime)
                    }

                    else -> list.sortedByDescending {
                        it.durChapterTime
                    }
                }
            }.catch {
                AppLog.put("书架管理界面获取书籍列表失败\n${it.localizedMessage}", it)
            }.flowOn(IO)
                .conflate().collect {
                    booksState = it
                }
        }
    }

    private fun alertRestoreNetworkCovers() {
        alert(
            titleResource = R.string.restore_network_covers,
            messageResource = R.string.restore_network_covers_confirm
        ) {
            okButton {
                viewModel.restoreNetworkCovers(selection)
            }
            noButton()
        }
    }

    private fun alertRestoreSourceCovers() {
        alert(
            titleResource = R.string.restore_source_covers,
            messageResource = R.string.restore_source_covers_confirm
        ) {
            okButton {
                viewModel.restoreSourceCovers(selection)
            }
            noButton()
        }
    }

    private fun updateBooksToc() {
        val books = filterBooksForTocUpdate(selection)
        if (books.isEmpty()) {
            toastOnUi(R.string.no_book_can_update)
            return
        }
        postEvent(EventBus.UP_BOOKS_TOC, books)
        toastOnUi(getString(R.string.update_book_info_toc_submitted, books.size))
    }

    private fun alertDelSelection() {
        alert(titleResource = R.string.draw, messageResource = R.string.sure_del) {
            val checkBox = CheckBox(this@BookshelfManageActivity).apply {
                setText(R.string.delete_book_file)
                isChecked = LocalConfig.deleteBookOriginal
            }
            val view = LinearLayout(this@BookshelfManageActivity).apply {
                setPadding(16.dpToPx(), 0, 16.dpToPx(), 0)
                addView(checkBox)
            }
            customView { view }
            okButton {
                LocalConfig.deleteBookOriginal = checkBox.isChecked
                viewModel.deleteBook(selection, checkBox.isChecked)
            }
            noButton()
        }
    }

    fun selectGroup(requestCode: Int, groupId: Long) {
        showDialogFragment(
            GroupSelectDialog(groupId, requestCode)
        )
    }

    override fun upGroup(requestCode: Int, groupId: Long) {
        when (requestCode) {
            groupRequestCode -> selection.let { books ->
                val array = Array(books.size) {
                    books[it].copy(group = groupId)
                }
                viewModel.updateBook(*array)
            }

            itemGroupRequestCode -> {
                actionItem?.let {
                    viewModel.updateBook(it.copy(group = groupId))
                }
            }

            addToGroupRequestCode -> selection.let { books ->
                val array = Array(books.size) { index ->
                    val book = books[index]
                    book.copy(group = book.group or groupId)
                }
                viewModel.updateBook(*array)
            }

            removeToGroupRequestCode -> selection.let { books ->
                val array = Array(books.size) { index ->
                    val book = books[index]
                    book.copy(group = book.group and groupId.inv())
                }
                viewModel.updateBook(*array)
            }
        }
    }

    fun updateBookOrder(books: List<Book>, resetAll: Boolean) {
        viewModel.updateBookOrder(books, resetAll)
    }

    fun deleteBook(book: Book) {
        alert(titleResource = R.string.draw, messageResource = R.string.sure_del) {
            var checkBox: CheckBox? = null
            if (book.isLocal) {
                checkBox = CheckBox(this@BookshelfManageActivity).apply {
                    setText(R.string.delete_book_file)
                    isChecked = LocalConfig.deleteBookOriginal
                }
                val view = LinearLayout(this@BookshelfManageActivity).apply {
                    setPadding(16.dpToPx(), 0, 16.dpToPx(), 0)
                    addView(checkBox)
                }
                customView { view }
            }
            okButton {
                if (checkBox != null) {
                    LocalConfig.deleteBookOriginal = checkBox.isChecked
                }
                viewModel.deleteBook(listOf(book), LocalConfig.deleteBookOriginal)
            }
        }
    }

    fun openBook(book: Book) {
        startActivity<BookInfoActivity> {
            putExtra("name", book.name)
            putExtra("author", book.author)
        }
    }

    override fun sourceOnClick(source: BookSource) {
        viewModel.changeSource(selection, source)
        viewModel.batchChangeSourceState.value = true
    }

}
