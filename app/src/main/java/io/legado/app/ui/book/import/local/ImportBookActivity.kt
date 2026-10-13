package io.legado.app.ui.book.import.local

import android.net.Uri
import android.os.Bundle
import android.widget.CheckBox
import android.widget.LinearLayout
import androidx.activity.addCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.constant.AppConst
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.help.book.cacheLocalUri
import io.legado.app.help.book.removeLocalUriCache
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.permission.Permissions
import io.legado.app.lib.permission.PermissionsCompat
import io.legado.app.model.localBook.LocalBook
import io.legado.app.ui.book.import.BaseImportBookActivity
import io.legado.app.ui.compose.component.LegadoMenuAction
import io.legado.app.ui.compose.component.LegadoSelectActionBar
import io.legado.app.ui.compose.component.LegadoTopAppBar
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.utils.ArchiveUtils
import io.legado.app.utils.ConvertUtils
import io.legado.app.utils.FileDoc
import io.legado.app.utils.dpToPx
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.isUri
import io.legado.app.utils.launch
import io.legado.app.utils.putPrefInt
import io.legado.app.utils.showDialogFragment
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 导入本地书籍界面
 */
class ImportBookActivity : BaseImportBookActivity<ImportBookViewModel>() {

    override val viewModel by viewModels<ImportBookViewModel>()
    private var scanDocJob: Job? = null
    private var startReadJob: Job? = null
    private var isRecursiveScan = false

    private var docsState by mutableStateOf<List<ImportBook>>(emptyList())
    private var selectedBooksState by mutableStateOf<Set<ImportBook>>(emptySet())
    private var pathState by mutableStateOf("")
    private var canGoBackState by mutableStateOf(false)
    private var isLoadingState by mutableStateOf(false)
    private var showEmptyMsgState by mutableStateOf(false)
    private var searchQueryState by mutableStateOf("")
    private var sortState by mutableIntStateOf(0)

    private val selectFolder = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            AppConfig.importBookPath = uri.toString()
            initRootDoc(true)
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        sortState = viewModel.sort
        onBackPressedDispatcher.addCallback(this) {
            if (!goBackDir()) {
                finish()
            }
        }
        lifecycleScope.launch {
            if (setBookStorage() && AppConfig.importBookPath.isNullOrBlank()) {
                AppConfig.importBookPath = AppConfig.defaultBookTreeUri
            }
            initData()
        }
    }

    @Composable
    override fun Content() {
        var sortMenuExpanded by remember { mutableStateOf(false) }
        val checkableItems = remember(docsState) {
            docsState.filter { !it.isDir && !it.isOnBookShelf }
        }
        val selectedItems = remember(docsState, selectedBooksState) {
            checkableItems.filter { it in selectedBooksState }.toHashSet()
        }

        Scaffold(
            topBar = {
                LegadoTopAppBar(
                    title = stringResource(R.string.local_book),
                    onBack = { finish() },
                    searchQuery = searchQueryState,
                    onSearchQueryChange = {
                        searchQueryState = it
                        onSearchTextChange(it)
                    },
                    searchPlaceholder = stringResource(R.string.screen) + " • " + stringResource(R.string.local_book),
                    actions = {
                        IconButton(onClick = { selectFolder.launch() }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_folder_open),
                                contentDescription = stringResource(R.string.select_folder),
                                modifier = Modifier.size(22.dp),
                            )
                        }
                        Box {
                            IconButton(onClick = { sortMenuExpanded = true }) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_sort),
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
                                        Checkbox(checked = sortState == 0, onCheckedChange = null)
                                    },
                                    onClick = {
                                        sortMenuExpanded = false
                                        upSort(0)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(text = stringResource(R.string.sort_by_size)) },
                                    trailingIcon = {
                                        Checkbox(checked = sortState == 1, onCheckedChange = null)
                                    },
                                    onClick = {
                                        sortMenuExpanded = false
                                        upSort(1)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(text = stringResource(R.string.sort_by_time)) },
                                    trailingIcon = {
                                        Checkbox(checked = sortState == 2, onCheckedChange = null)
                                    },
                                    onClick = {
                                        sortMenuExpanded = false
                                        upSort(2)
                                    },
                                )
                            }
                        }
                    },
                    menuActions = listOf(
                        LegadoMenuAction(
                            title = stringResource(R.string.scan_folder),
                            onClick = ::scanFolder,
                        ),
                        LegadoMenuAction(
                            title = stringResource(R.string.import_file_name),
                            onClick = ::alertImportFileName,
                        ),
                        LegadoMenuAction(
                            title = stringResource(R.string.local_book_save_path),
                            onClick = {
                                AlertDialog.Builder(this)
                                    .setTitle(R.string.local_book_save_path)
                                    .setMessage(AppConfig.defaultBookTreeUri ?: File(filesDir, "books").path)
                                    .setPositiveButton(R.string.select_folder) { _, _ ->
                                        localBookTreeSelect.launch {
                                            title = getString(R.string.local_book_save_path)
                                            mode = HandleFileContract.DIR_SYS
                                        }
                                    }
                                    .setNegativeButton(R.string.shared_local_books_private) { _, _ ->
                                        AppConfig.defaultBookTreeUri = Uri.fromFile(File(filesDir, "books")).toString()
                                    }
                                    .show()
                            },
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
                    menuActions = listOf(
                        LegadoMenuAction(
                            title = stringResource(R.string.delete),
                            onClick = {
                                viewModel.deleteDoc(selectedItems) {
                                    docsState = docsState.filterNot { it in selectedItems }
                                    selectedBooksState = emptySet()
                                }
                            },
                        ),
                    ),
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
                    if (docsState.isEmpty() && showEmptyMsgState) {
                        Text(
                            text = stringResource(R.string.empty_msg_import_book),
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
                                items = docsState,
                                key = { it.file.uri.toString() },
                            ) { item ->
                                val isSelected = item in selectedBooksState
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(60.dp)
                                        .clickable {
                                            if (item.isDir) {
                                                nextDoc(item.file)
                                            } else if (!item.isOnBookShelf) {
                                                selectedBooksState = if (isSelected) {
                                                    selectedBooksState - item
                                                } else {
                                                    selectedBooksState + item
                                                }
                                            } else {
                                                startRead(item.file)
                                            }
                                        }
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
                                        val displayName = item.preview?.let { book ->
                                            if (book.author.isBlank()) book.name else "${book.name} / ${book.author}"
                                        } ?: item.name
                                        Text(
                                            text = displayName,
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
                                                    text = item.name.substringAfterLast("."),
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
                                                    text = AppConst.dateFormat.format(item.lastModified),
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

    fun onClickSelectBarMainAction() {
        val selected = HashSet(selectedBooksState)
        val groupName = (viewModel.subDocs.lastOrNull() ?: viewModel.rootDoc)?.name
        if (selected.size < 2 || isRecursiveScan || groupName.isNullOrBlank()) {
            addToBookshelf(selected)
            return
        }
        lifecycleScope.launch(IO) {
            val canAddGroup = appDb.bookGroupDao.canAddGroup
            withContext(Main) {
                alertDirectoryGroup(selected, groupName, canAddGroup)
            }
        }
    }

    private fun addToBookshelf(selected: HashSet<ImportBook>, groupName: String? = null) {
        viewModel.addToBookshelf(selected, groupName) { importedUris ->
            selected.filter { it.file.uri in importedUris }.forEach {
                it.isOnBookShelf = true
            }
            selectedBooksState = emptySet()
            docsState = docsState.toList()
        }
    }

    private fun alertDirectoryGroup(
        selected: HashSet<ImportBook>,
        groupName: String,
        canAddGroup: Boolean,
    ) {
        val checkBox = CheckBox(this).apply {
            setText(R.string.import_directory_group)
            isEnabled = canAddGroup
        }
        val view = LinearLayout(this).apply {
            setPadding(16.dpToPx(), 0, 16.dpToPx(), 0)
            addView(checkBox)
        }
        val dialog = alert(
            titleResource = R.string.import_directory_group_title,
            messageResource = if (canAddGroup) null else R.string.book_group_limit,
        ) {
            setCancelable(false)
            customView { view }
            okButton {
                addToBookshelf(selected, groupName.takeIf { checkBox.isChecked })
            }
            cancelButton {
                addToBookshelf(selected)
            }
        }
        if (!canAddGroup) {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
        }
    }

    private fun initData() {
        viewModel.dataFlowStart = {
            initRootDoc()
        }
        lifecycleScope.launch {
            viewModel.dataFlow.conflate().collect { docs ->
                docsState = docs
            }
        }
    }

    private fun initRootDoc(changedFolder: Boolean = false) {
        if (viewModel.rootDoc != null && !changedFolder) {
            upPath()
        } else {
            val lastPath = AppConfig.importBookPath
            if (lastPath.isNullOrBlank()) {
                showEmptyMsgState = true
                selectFolder.launch()
            } else {
                val rootUri = if (lastPath.isUri()) {
                    lastPath.toUri()
                } else {
                    Uri.fromFile(File(lastPath))
                }
                when {
                    rootUri.isContentScheme() -> initRootPath(rootUri)
                    else -> initRootPath(rootUri.path!!)
                }
            }
        }
    }

    private fun initRootPath(rootUri: Uri) {
        kotlin.runCatching {
            val doc = DocumentFile.fromTreeUri(this, rootUri)
            if (doc == null || doc.name.isNullOrEmpty() || !doc.isDirectory) {
                showEmptyMsgState = true
                selectFolder.launch()
            } else {
                viewModel.subDocs.clear()
                viewModel.rootDoc = FileDoc.fromDocumentFile(doc)
                upPath()
            }
        }.onFailure {
            showEmptyMsgState = true
            selectFolder.launch()
        }
    }

    private fun initRootPath(path: String) {
        showEmptyMsgState = true
        PermissionsCompat.Builder()
            .addPermissions(*Permissions.Group.STORAGE)
            .rationale(R.string.tip_perm_request_storage)
            .onGranted {
                kotlin.runCatching {
                    val file = File(path)
                    if (!file.isDirectory) {
                        showEmptyMsgState = true
                        selectFolder.launch()
                    } else {
                        viewModel.subDocs.clear()
                        viewModel.rootDoc = FileDoc.fromFile(file)
                        upPath()
                    }
                }.onFailure {
                    showEmptyMsgState = true
                    selectFolder.launch()
                }
            }
            .request()
    }

    private fun upSort(sort: Int) {
        viewModel.sort = sort
        sortState = sort
        putPrefInt(PreferKey.localBookImportSort, sort)
        if (scanDocJob?.isActive != true) {
            viewModel.dataCallback?.upAdapter()
        }
    }

    @Synchronized
    private fun upPath() {
        canGoBackState = viewModel.subDocs.isNotEmpty()
        viewModel.rootDoc?.let {
            scanDocJob?.cancel()
            upDocs(it)
        }
    }

    private fun upDocs(rootDoc: FileDoc) {
        isRecursiveScan = false
        showEmptyMsgState = false
        var path = rootDoc.name + File.separator
        var lastDoc = rootDoc
        for (doc in viewModel.subDocs) {
            lastDoc = doc
            path = path + doc.name + File.separator
        }
        pathState = path
        selectedBooksState = emptySet()
        docsState = emptyList()
        viewModel.loadDoc(lastDoc)
    }

    /**
     * 扫描当前文件夹及所有子文件夹
     */
    private fun scanFolder() {
        viewModel.rootDoc?.let { doc ->
            isRecursiveScan = true
            docsState = emptyList()
            val lastDoc = viewModel.subDocs.lastOrNull() ?: doc
            isLoadingState = true
            scanDocJob?.cancel()
            scanDocJob = lifecycleScope.launch(IO) {
                viewModel.scanDoc(lastDoc)
                withContext(Main) {
                    isLoadingState = false
                }
            }
        }
    }

    private fun alertImportFileName() {
        showDialogFragment(
            io.legado.app.ui.widget.dialog.ComposeTextInputDialog(
                title = getString(R.string.import_file_name),
                initialValue = AppConfig.bookImportFileName.orEmpty(),
                message = "使用js处理文件名变量src，将书名作者分别赋值到变量name author",
                hint = "js",
                onConfirm = { AppConfig.bookImportFileName = it },
            )
        )
    }

    @Synchronized
    fun nextDoc(fileDoc: FileDoc) {
        viewModel.subDocs.add(fileDoc)
        upPath()
    }

    @Synchronized
    private fun goBackDir(): Boolean {
        return if (viewModel.subDocs.isNotEmpty()) {
            viewModel.subDocs.removeAt(viewModel.subDocs.lastIndex)
            upPath()
            true
        } else {
            false
        }
    }

    override fun onSearchTextChange(newText: String?) {
        viewModel.updateCallBackFlow(newText)
    }

    fun startRead(fileDoc: FileDoc) {
        if (!ArchiveUtils.isArchive(fileDoc.name)) {
            if (startReadJob?.isActive == true) return
            startReadJob = lifecycleScope.launch(IO) {
                val filePath = fileDoc.toString()
                val book = appDb.bookDao.getBook(filePath)
                    ?: appDb.bookDao.getBookByFileName(fileDoc.name)
                    ?: return@launch
                LocalBook.withParserCacheInvalidated(
                    book.bookUrl,
                    book.originName,
                ) {
                    book.removeLocalUriCache()
                    book.cacheLocalUri(fileDoc.uri)
                }
                withContext(Main) {
                    if (!isFinishing && !isDestroyed) {
                        startReadBook(book)
                    }
                }
            }
        } else {
            onArchiveFileClick(fileDoc)
        }
    }

}
