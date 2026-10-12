package io.legado.app.ui.book.import.remote

import android.net.Uri
import android.os.Bundle
import androidx.activity.addCallback
import androidx.activity.viewModels
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.constant.AppConst
import io.legado.app.data.appDb
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.model.remote.RemoteBook
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.book.import.BaseImportBookActivity
import io.legado.app.ui.compose.component.LegadoMenuAction
import io.legado.app.ui.compose.component.LegadoSelectActionBar
import io.legado.app.ui.compose.component.LegadoTopAppBar
import io.legado.app.utils.ArchiveUtils
import io.legado.app.utils.ConvertUtils
import io.legado.app.utils.FileDoc
import io.legado.app.utils.find
import io.legado.app.utils.showDialogFragment
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import java.io.File

/**
 * 展示远程书籍
 */
class RemoteBookActivity : BaseImportBookActivity<RemoteBookViewModel>(),
    ServersDialog.Callback {

    override val viewModel by viewModels<RemoteBookViewModel>()

    private var remoteBooksState by mutableStateOf<List<RemoteBook>>(emptyList())
    private var selectedBooksState by mutableStateOf<Set<RemoteBook>>(emptySet())
    private var pathState by mutableStateOf("")
    private var canGoBackState by mutableStateOf(false)
    private var isLoadingState by mutableStateOf(false)
    private var searchQueryState by mutableStateOf("")
    private var sortKeyState by mutableStateOf(RemoteBookSort.Default)

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        sortKeyState = viewModel.sortKey
        onBackPressedDispatcher.addCallback(this) {
            if (!goBackDir()) {
                finish()
            }
        }
        lifecycleScope.launch {
            if (!setBookStorage()) {
                finish()
                return@launch
            }
            launch {
                viewModel.dataFlow.conflate().collect { sortedRemoteBooks ->
                    isLoadingState = false
                    remoteBooksState = sortedRemoteBooks
                    delay(500)
                }
            }
            viewModel.initData {
                upPath()
            }
        }
    }

    override fun observeLiveBus() {
        viewModel.permissionDenialLiveData.observe(this) {
            localBookTreeSelect.launch {
                title = getString(R.string.select_book_folder)
            }
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    override fun Content() {
        var sortMenuExpanded by remember { mutableStateOf(false) }
        val checkableItems = remember(remoteBooksState) {
            remoteBooksState.filter { !it.isDir && !it.isOnBookShelf }
        }
        val selectedItems = remember(remoteBooksState, selectedBooksState) {
            checkableItems.filter { it in selectedBooksState }.toHashSet()
        }

        Scaffold(
            topBar = {
                LegadoTopAppBar(
                    title = stringResource(R.string.remote_book),
                    onBack = { finish() },
                    searchQuery = searchQueryState,
                    onSearchQueryChange = {
                        searchQueryState = it
                        onSearchTextChange(it)
                    },
                    searchPlaceholder = stringResource(R.string.screen) + " • " + stringResource(R.string.remote_book),
                    actions = {
                        IconButton(onClick = { upPath() }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_refresh_black_24dp),
                                contentDescription = stringResource(R.string.refresh),
                                modifier = Modifier.size(22.dp),
                            )
                        }
                        Box {
                            IconButton(onClick = { sortMenuExpanded = true }) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_baseline_sort_24),
                                    contentDescription = stringResource(R.string.sort),
                                    modifier = Modifier.size(22.dp),
                                )
                            }
                            DropdownMenu(
                                expanded = sortMenuExpanded,
                                onDismissRequest = { sortMenuExpanded = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text(text = stringResource(R.string.sort_by_name)) },
                                    trailingIcon = {
                                        Checkbox(
                                            checked = sortKeyState == RemoteBookSort.Name,
                                            onCheckedChange = null,
                                        )
                                    },
                                    onClick = {
                                        sortMenuExpanded = false
                                        sortCheck(RemoteBookSort.Name)
                                        upPath()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(text = stringResource(R.string.sort_by_lastUpdateTime)) },
                                    trailingIcon = {
                                        Checkbox(
                                            checked = sortKeyState == RemoteBookSort.Default,
                                            onCheckedChange = null,
                                        )
                                    },
                                    onClick = {
                                        sortMenuExpanded = false
                                        sortCheck(RemoteBookSort.Default)
                                        upPath()
                                    },
                                )
                            }
                        }
                    },
                    menuActions = listOf(
                        LegadoMenuAction(
                            title = stringResource(R.string.server_config),
                            iconRes = R.drawable.ic_cfg_other,
                            onClick = { showDialogFragment<ServersDialog>() },
                        ),
                        LegadoMenuAction(
                            title = stringResource(R.string.log),
                            iconRes = R.drawable.ic_cfg_about,
                            onClick = { showDialogFragment<AppLogDialog>() },
                        ),
                    ),
                )
            },
            bottomBar = {
                LegadoSelectActionBar(
                    modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars),
                    selectedCount = selectedItems.size,
                    totalCount = checkableItems.size,
                    onSelectAllChange = { selectAll ->
                        selectedBooksState = if (selectAll) {
                            checkableItems.toSet()
                        } else {
                            emptySet()
                        }
                    },
                    onRevertSelection = {
                        selectedBooksState = checkableItems.mapNotNullTo(linkedSetOf()) {
                            if (it in selectedBooksState) null else it
                        }
                    },
                    mainActionText = stringResource(R.string.add_to_bookshelf),
                    onMainActionClick = ::onClickSelectBarMainAction,
                )
            },
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                Surface(
                    tonalElevation = 1.dp,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = pathState,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        OutlinedButton(
                            onClick = { goBackDir() },
                            enabled = canGoBackState,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        ) {
                            Text(text = stringResource(R.string.go_back), fontSize = 13.sp)
                        }
                    }
                }
                if (isLoadingState) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                HorizontalDivider()

                Box(modifier = Modifier.fillMaxSize()) {
                    if (remoteBooksState.isEmpty()) {
                        Text(
                            text = stringResource(R.string.empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(16.dp),
                        )
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(
                                items = remoteBooksState,
                                key = { it.path },
                            ) { item ->
                                val isSelected = item in selectedBooksState
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(60.dp)
                                        .combinedClickable(
                                            onClick = {
                                                if (item.isDir) {
                                                    openDir(item)
                                                } else if (!item.isOnBookShelf) {
                                                    selectedBooksState = if (isSelected) {
                                                        selectedBooksState - item
                                                    } else {
                                                        selectedBooksState + item
                                                    }
                                                } else {
                                                    startRead(item)
                                                }
                                            },
                                            onLongClick = {
                                                if (item.isOnBookShelf) {
                                                    addToBookShelfAgain(item)
                                                }
                                            },
                                        )
                                        .padding(end = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Box(
                                        modifier = Modifier.width(60.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        when {
                                            item.isDir -> Icon(
                                                painter = painterResource(R.drawable.ic_folder),
                                                contentDescription = null,
                                            )
                                            item.isOnBookShelf -> Icon(
                                                painter = painterResource(R.drawable.ic_book_has),
                                                contentDescription = null,
                                            )
                                            else -> Checkbox(
                                                checked = isSelected,
                                                onCheckedChange = { checked ->
                                                    selectedBooksState = if (checked) {
                                                        selectedBooksState + item
                                                    } else {
                                                        selectedBooksState - item
                                                    }
                                                },
                                            )
                                        }
                                    }
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.Center,
                                    ) {
                                        Text(
                                            text = item.filename,
                                            style = MaterialTheme.typography.bodyLarge,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        if (!item.isDir) {
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                            ) {
                                                Text(
                                                    text = item.contentType,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onPrimary,
                                                    modifier = Modifier
                                                        .background(
                                                            MaterialTheme.colorScheme.primary,
                                                            RoundedCornerShape(2.dp)
                                                        )
                                                        .padding(horizontal = 5.dp, vertical = 1.dp),
                                                    maxLines = 1,
                                                )
                                                Text(
                                                    text = ConvertUtils.formatFileSize(item.size),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                                Text(
                                                    text = AppConst.dateFormat.format(item.lastModify),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
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
        }
    }

    private fun sortCheck(sortKey: RemoteBookSort) {
        if (viewModel.sortKey == sortKey) {
            viewModel.sortAscending = !viewModel.sortAscending
        } else {
            viewModel.sortAscending = true
            viewModel.sortKey = sortKey
        }
        sortKeyState = viewModel.sortKey
    }

    fun onClickSelectBarMainAction() {
        val selected = HashSet(selectedBooksState)
        isLoadingState = true
        viewModel.addToBookshelf(selected) {
            selectedBooksState = emptySet()
            remoteBooksState = remoteBooksState.toList()
            isLoadingState = false
        }
    }

    private fun goBackDir(): Boolean {
        if (viewModel.dirList.isEmpty()) {
            return false
        }
        viewModel.dirList.removeLastOrNull()
        upPath()
        return true
    }

    private fun upPath() {
        canGoBackState = viewModel.dirList.isNotEmpty()
        var path = if (viewModel.isDefaultWebdav) {
            "books" + File.separator
        } else {
            File.separator
        }
        viewModel.dirList.forEach {
            path = path + it.filename + File.separator
        }
        pathState = path
        viewModel.dataCallback?.clear()
        selectedBooksState = emptySet()
        viewModel.loadRemoteBookList(
            viewModel.dirList.lastOrNull()?.path
        ) {
            isLoadingState = it
        }
    }

    fun openDir(remoteBook: RemoteBook) {
        viewModel.dirList.add(remoteBook)
        upPath()
    }

    override fun onDialogDismiss(tag: String) {
        viewModel.initData {
            upPath()
        }
    }

    override fun onSearchTextChange(newText: String?) {
        viewModel.updateCallBackFlow(newText)
    }

    private fun showRemoteBookDownloadAlert(
        remoteBook: RemoteBook,
        onDownloadFinish: (() -> Unit)? = null
    ) {
        alert(
            R.string.draw,
            R.string.archive_not_found
        ) {
            okButton {
                viewModel.addToBookshelf(hashSetOf(remoteBook)) {
                    onDownloadFinish?.invoke()
                }
            }
            noButton()
        }
    }

    fun startRead(remoteBook: RemoteBook) {
        val downloadFileName = remoteBook.filename
        if (!ArchiveUtils.isArchive(downloadFileName)) {
            appDb.bookDao.getBookByFileName(downloadFileName)?.let {
                startReadBook(it)
            }
        } else {
            AppConfig.defaultBookTreeUri ?: return
            val downloadArchiveFileDoc = FileDoc.fromUri(Uri.parse(AppConfig.defaultBookTreeUri), true)
                .find(downloadFileName)
            if (downloadArchiveFileDoc == null) {
                showRemoteBookDownloadAlert(remoteBook) {
                    startRead(remoteBook)
                }
            } else {
                onArchiveFileClick(downloadArchiveFileDoc)
            }
        }
    }

    fun addToBookShelfAgain(remoteBook: RemoteBook) {
        alert(getString(R.string.sure), "是否重新加入书架？") {
            yesButton {
                isLoadingState = true
                viewModel.addToBookshelf(hashSetOf(remoteBook)) {
                    isLoadingState = false
                }
            }
            noButton()
        }
    }

}
