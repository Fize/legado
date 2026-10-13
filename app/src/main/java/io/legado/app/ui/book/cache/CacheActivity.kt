package io.legado.app.ui.book.cache

import android.annotation.SuppressLint
import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.VMBaseComposeActivity
import io.legado.app.constant.AppConst.charsets
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.constant.IntentAction
import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookGroup
import io.legado.app.help.book.getExportFileName
import io.legado.app.help.book.isAudio
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.tryParesExportFileName
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.selector
import io.legado.app.model.CacheBook
import io.legado.app.service.ExportBookService
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.compose.component.LegadoMenuAction
import io.legado.app.ui.compose.component.LegadoTopAppBar
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.utils.ACache
import io.legado.app.utils.FileDoc
import io.legado.app.utils.checkWrite
import io.legado.app.utils.cnCompare
import io.legado.app.utils.enableCustomExport
import io.legado.app.utils.flowWithLifecycleAndDatabaseChange
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.observeEvent
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startService
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.verificationField
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * cache/download 缓存界面
 */
class CacheActivity : VMBaseComposeActivity<CacheViewModel>() {

    override val viewModel by viewModels<CacheViewModel>()

    private val exportBookPathKey = "exportBookPath"
    private val exportTypes = arrayListOf("txt", "epub", "pdf")
    private var booksFlowJob: Job? = null
    private var groupId: Long = -1

    private var booksState by mutableStateOf<List<Book>>(emptyList())
    private var groupListState by mutableStateOf<List<BookGroup>>(emptyList())
    private var subtitleState by mutableStateOf("")
    private var isCacheRunningState by mutableStateOf(CacheBook.isRun)
    private var refreshKeyState by mutableIntStateOf(0)
    private var sectionExportDialogState by mutableStateOf<Pair<String, Int>?>(null)

    private val exportDir = registerForActivityResult(HandleFileContract()) { result ->
        var isReadyPath = false
        var dirPath = ""
        result.uri?.let { uri ->
            if (uri.isContentScheme()) {
                ACache.get().put(exportBookPathKey, uri.toString())
                dirPath = uri.toString()
                isReadyPath = true
            } else {
                uri.path?.let { path ->
                    ACache.get().put(exportBookPathKey, path)
                    dirPath = path
                    isReadyPath = true
                }
            }
        }
        if (!isReadyPath) {
            return@registerForActivityResult
        }
        if (enableCustomExport()) {
            configExportSection(dirPath, result.requestCode)
        } else {
            startExport(dirPath, result.requestCode)
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        groupId = intent.getLongExtra("groupId", -1)
        lifecycleScope.launch {
            subtitleState = withContext(IO) {
                appDb.bookGroupDao.getByID(groupId)?.groupName
                    ?: getString(R.string.no_group)
            }
        }
        initGroupData()
        initBookData()
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    override fun Content() {
        var downloadMenuExpanded by remember { mutableStateOf(false) }
        var groupMenuExpanded by remember { mutableStateOf(false) }
        var menuStateTick by remember { mutableIntStateOf(0) }
        val tick = refreshKeyState + menuStateTick

        Scaffold(
            topBar = {
                LegadoTopAppBar(
                    title = stringResource(R.string.cache_export),
                    subtitle = subtitleState,
                    onBack = { finish() },
                    actions = {
                        Box {
                            val downloadTitle = stringResource(
                                if (isCacheRunningState) R.string.stop else R.string.download_start
                            )
                            val downloadIcon = if (isCacheRunningState) {
                                R.drawable.ic_stop_black_24dp
                            } else {
                                R.drawable.ic_play_24dp
                            }
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .combinedClickable(
                                        onClick = { startDownloadAfterCurrent() },
                                        onLongClick = { downloadMenuExpanded = true },
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    painter = painterResource(downloadIcon),
                                    contentDescription = downloadTitle,
                                    modifier = Modifier.size(22.dp),
                                )
                            }
                            DropdownMenu(
                                expanded = downloadMenuExpanded,
                                onDismissRequest = { downloadMenuExpanded = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text(text = stringResource(R.string.menu_download_after)) },
                                    onClick = {
                                        downloadMenuExpanded = false
                                        startDownloadAfterCurrent()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(text = stringResource(R.string.menu_download_all)) },
                                    onClick = {
                                        downloadMenuExpanded = false
                                        startDownloadAll()
                                    },
                                )
                            }
                        }

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
                                groupListState.forEach { bookGroup ->
                                    DropdownMenuItem(
                                        text = { Text(text = bookGroup.groupName) },
                                        onClick = {
                                            groupMenuExpanded = false
                                            subtitleState = bookGroup.groupName
                                            groupId = bookGroup.groupId
                                            initBookData()
                                        },
                                    )
                                }
                            }
                        }
                    },
                    menuActions = remember(tick) {
                        listOf(
                            LegadoMenuAction(
                                title = getString(R.string.export_all),
                                onClick = ::exportAll,
                            ),
                            LegadoMenuAction(
                                title = getString(R.string.replace_purify),
                                checked = AppConfig.exportUseReplace,
                                onClick = {
                                    AppConfig.exportUseReplace = !AppConfig.exportUseReplace
                                    menuStateTick++
                                },
                            ),
                            LegadoMenuAction(
                                title = getString(R.string.custom_export_section),
                                checked = AppConfig.enableCustomExport,
                                onClick = {
                                    AppConfig.enableCustomExport = !AppConfig.enableCustomExport
                                    menuStateTick++
                                },
                            ),
                            LegadoMenuAction(
                                title = getString(R.string.export_to_web_dav),
                                checked = AppConfig.exportToWebDav,
                                onClick = {
                                    AppConfig.exportToWebDav = !AppConfig.exportToWebDav
                                    menuStateTick++
                                },
                            ),
                            LegadoMenuAction(
                                title = getString(R.string.export_no_chapter_name),
                                checked = AppConfig.exportNoChapterName,
                                onClick = {
                                    AppConfig.exportNoChapterName = !AppConfig.exportNoChapterName
                                    menuStateTick++
                                },
                            ),
                            LegadoMenuAction(
                                title = getString(R.string.export_pics_file),
                                checked = AppConfig.exportPictureFile,
                                onClick = {
                                    AppConfig.exportPictureFile = !AppConfig.exportPictureFile
                                    menuStateTick++
                                },
                            ),
                            LegadoMenuAction(
                                title = getString(R.string.parallel_export_book),
                                checked = AppConfig.parallelExportBook,
                                onClick = {
                                    AppConfig.parallelExportBook = !AppConfig.parallelExportBook
                                    menuStateTick++
                                },
                            ),
                            LegadoMenuAction(
                                title = getString(R.string.export_folder),
                                onClick = { selectExportFolder(-1) },
                            ),
                            LegadoMenuAction(
                                title = getString(R.string.export_file_name),
                                onClick = ::alertExportFileName,
                            ),
                            LegadoMenuAction(
                                title = "${getString(R.string.export_type)}(${getTypeName()})",
                                onClick = {
                                    showExportTypeConfig { menuStateTick++ }
                                },
                            ),
                            LegadoMenuAction(
                                title = "${getString(R.string.export_charset)}(${AppConfig.exportCharset})",
                                onClick = {
                                    showCharsetConfig { menuStateTick++ }
                                },
                            ),
                            LegadoMenuAction(
                                title = getString(R.string.log),
                                onClick = { showDialogFragment<AppLogDialog>() },
                            ),
                        )
                    },
                )
            },
        ) { innerPadding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .windowInsetsPadding(WindowInsets.navigationBars),
            ) {
                itemsIndexed(
                    items = booksState,
                    key = { _, book -> book.bookUrl },
                ) { index, item ->
                    @Suppress("UNUSED_EXPRESSION")
                    tick
                    val cs = viewModel.cacheChapters[item.bookUrl]
                    val downloadCountText = when {
                        item.isLocal -> stringResource(R.string.local_book)
                        cs == null -> stringResource(R.string.loading)
                        else -> stringResource(R.string.download_count, cs.size, item.totalChapterNum)
                    }
                    val isBookDownloading = CacheBook.cacheBookMap[item.bookUrl]?.isStop() == false
                    val msg = exportMsg(item.bookUrl)
                    val progress = exportProgress(item.bookUrl)

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = stringResource(R.string.author_show, item.getRealAuthor()),
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = downloadCountText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (!item.isLocal) {
                                IconButton(
                                    onClick = {
                                        CacheBook.cacheBookMap[item.bookUrl]?.let {
                                            if (!it.isStop()) {
                                                CacheBook.remove(this@CacheActivity, item.bookUrl)
                                            } else {
                                                CacheBook.start(this@CacheActivity, item, 0, item.lastChapterIndex)
                                            }
                                        } ?: run {
                                            CacheBook.start(this@CacheActivity, item, 0, item.lastChapterIndex)
                                        }
                                        refreshKeyState++
                                    },
                                ) {
                                    Icon(
                                        painter = painterResource(
                                            if (isBookDownloading) R.drawable.ic_stop_black_24dp else R.drawable.ic_play_24dp
                                        ),
                                        contentDescription = stringResource(
                                            if (isBookDownloading) R.string.stop else R.string.start
                                        ),
                                    )
                                }
                            }
                            TextButton(onClick = { export(index) }) {
                                Text(text = stringResource(R.string.export))
                            }
                        }
                        if (msg != null) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = msg,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else if (progress != null) {
                            Spacer(modifier = Modifier.height(4.dp))
                            val fraction = if (item.totalChapterNum > 0) {
                                (progress.toFloat() / item.totalChapterNum.toFloat()).coerceIn(0f, 1f)
                            } else {
                                0f
                            }
                            LinearProgressIndicator(
                                progress = { fraction },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    HorizontalDivider()
                }
            }
        }

        sectionExportDialogState?.let { (path, position) ->
            SectionExportDialog(
                path = path,
                position = position,
                onDismiss = { sectionExportDialogState = null },
            )
        }
    }

    private fun startDownloadAfterCurrent() {
        if (!CacheBook.isRun) {
            sureCacheBook {
                booksState.forEach { book ->
                    CacheBook.start(
                        this@CacheActivity,
                        book,
                        book.durChapterIndex,
                        book.lastChapterIndex
                    )
                }
            }
        } else {
            CacheBook.stop(this@CacheActivity)
        }
    }

    private fun startDownloadAll() {
        if (!CacheBook.isRun) {
            sureCacheBook {
                booksState.forEach { book ->
                    CacheBook.start(
                        this@CacheActivity,
                        book,
                        0,
                        book.lastChapterIndex
                    )
                }
            }
        } else {
            CacheBook.stop(this@CacheActivity)
        }
    }

    private fun initBookData() {
        booksFlowJob?.cancel()
        booksFlowJob = lifecycleScope.launch {
            appDb.bookDao.flowByGroup(groupId).map { books ->
                val booksDownload = books.filter {
                    !it.isAudio
                }
                when (AppConfig.getBookSortByGroupId(groupId)) {
                    1 -> booksDownload.sortedByDescending { it.latestChapterTime }
                    2 -> booksDownload.sortedWith { o1, o2 ->
                        o1.name.cnCompare(o2.name)
                    }

                    3 -> booksDownload.sortedBy { it.order }
                    4 -> booksDownload.sortedByDescending {
                        max(it.latestChapterTime, it.durChapterTime)
                    }

                    else -> booksDownload.sortedByDescending { it.durChapterTime }
                }
            }.flowWithLifecycleAndDatabaseChange(
                lifecycle, table = AppDatabase.BOOK_TABLE_NAME
            ).catch {
                AppLog.put("缓存管理界面获取书籍列表失败\n${it.localizedMessage}", it)
            }.flowOn(IO).conflate().collect { books ->
                booksState = books
                viewModel.loadCacheFiles(books)
                refreshKeyState++
            }
        }
    }

    private fun initGroupData() {
        lifecycleScope.launch {
            appDb.bookGroupDao.flowAll().catch {
                AppLog.put("缓存管理界面获取分组数据失败\n${it.localizedMessage}", it)
            }.flowOn(IO).conflate().collect {
                groupListState = it
                refreshKeyState++
            }
        }
    }

    private fun notifyItemChanged(@Suppress("UNUSED_PARAMETER") bookUrl: String) {
        refreshKeyState++
    }

    private fun updateDownloadMenuState() {
        isCacheRunningState = CacheBook.isRun
        refreshKeyState++
    }

    override fun observeLiveBus() {
        viewModel.upAdapterLiveData.observe(this) {
            notifyItemChanged(it)
        }
        observeEvent<String>(EventBus.EXPORT_BOOK) {
            notifyItemChanged(it)
        }
        observeEvent<String>(EventBus.UP_DOWNLOAD) {
            notifyItemChanged(it)
        }
        observeEvent<String>(EventBus.UP_DOWNLOAD_STATE) {
            updateDownloadMenuState()
        }
        observeEvent<Pair<Book, BookChapter>>(EventBus.SAVE_CONTENT) { (book, chapter) ->
            viewModel.cacheChapters[book.bookUrl]?.add(chapter.url)
            notifyItemChanged(book.bookUrl)
        }
    }

    fun export(position: Int) {
        val path = ACache.get().getAsString(exportBookPathKey)
        lifecycleScope.launch {
            if (path.isNullOrEmpty() ||
                withContext(IO) { !FileDoc.fromDir(path).checkWrite() }
            ) {
                selectExportFolder(position)
            } else if (enableCustomExport()) {
                configExportSection(path, position)
            } else {
                startExport(path, position)
            }
        }
    }

    private fun exportAll() {
        val path = ACache.get().getAsString(exportBookPathKey)
        if (path.isNullOrEmpty()) {
            selectExportFolder(-10)
        } else {
            startExport(path, -10)
        }
    }

    private fun configExportSection(path: String, position: Int) {
        sectionExportDialogState = path to position
    }

    @Composable
    private fun SectionExportDialog(
        path: String,
        position: Int,
        onDismiss: () -> Unit,
    ) {
        var allExport by remember { mutableStateOf(false) }
        var selectExport by remember { mutableStateOf(true) }
        var epubFilename by remember { mutableStateOf(AppConfig.episodeExportFileName.orEmpty()) }
        var epubSizeText by remember { mutableStateOf("1") }
        var inputScope by remember { mutableStateOf("") }
        var scopeError by remember { mutableStateOf<String?>(null) }
        var helperText by remember { mutableStateOf("Variable: name, author, epubIndex") }

        fun verifyExportFileNameJsStr(js: String): Boolean {
            return tryParesExportFileName(js) && epubFilename.isNotEmpty()
        }

        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(text = stringResource(R.string.select_section_export)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                allExport = true
                                selectExport = false
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = allExport,
                            onCheckedChange = { checked ->
                                if (checked) {
                                    allExport = true
                                    selectExport = false
                                }
                            },
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = stringResource(R.string.export_all))
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selectExport = true
                                allExport = false
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = selectExport,
                            onCheckedChange = { checked ->
                                if (checked) {
                                    selectExport = true
                                    allExport = false
                                }
                            },
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = stringResource(R.string.custom_export))
                    }
                    OutlinedTextField(
                        value = epubFilename,
                        onValueChange = {
                            epubFilename = it
                            if (verifyExportFileNameJsStr(it)) {
                                AppConfig.episodeExportFileName = it
                            }
                        },
                        enabled = selectExport,
                        label = { Text(text = stringResource(R.string.export_file_name)) },
                        supportingText = { Text(text = helperText) },
                        trailingIcon = if (selectExport) {
                            {
                                IconButton(
                                    onClick = {
                                        val book = booksState.getOrNull(position)
                                        if (book != null) {
                                            helperText = if (verifyExportFileNameJsStr(epubFilename)) {
                                                "${resources.getString(R.string.result_analyzed)}: ${
                                                    book.getExportFileName("epub", 1, epubFilename)
                                                }"
                                            } else {
                                                "Error"
                                            }
                                        } else {
                                            helperText = "Error"
                                            AppLog.put("未找到书籍，position is $position")
                                        }
                                    },
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_play_24dp),
                                        contentDescription = "Execute script",
                                    )
                                }
                            }
                        } else {
                            null
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = epubSizeText,
                        onValueChange = { epubSizeText = it.take(6) },
                        enabled = selectExport,
                        label = { Text(text = stringResource(R.string.file_contains_number)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = inputScope,
                        onValueChange = {
                            inputScope = it
                            scopeError = null
                        },
                        enabled = selectExport,
                        label = { Text(text = stringResource(R.string.export_chapter_index)) },
                        placeholder = { Text(text = "1-5,8,10-18") },
                        isError = scopeError != null,
                        supportingText = scopeError?.let { err -> { Text(text = err) } },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (allExport) {
                            startExport(path, position)
                            onDismiss()
                            return@TextButton
                        }
                        if (!verificationField(inputScope)) {
                            scopeError = getString(R.string.error_scope_input)
                            return@TextButton
                        }
                        val epubSize = epubSizeText.toIntOrNull() ?: 1
                        booksState.getOrNull(position)?.let { book ->
                            startService<ExportBookService> {
                                action = IntentAction.start
                                putExtra("bookUrl", book.bookUrl)
                                putExtra("exportType", "epub")
                                putExtra("exportPath", path)
                                putExtra("epubSize", epubSize)
                                putExtra("epubScope", inputScope)
                            }
                        }
                        onDismiss()
                    },
                ) {
                    Text(text = stringResource(R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text(text = stringResource(R.string.cancel))
                }
            },
        )
    }

    private fun selectExportFolder(exportPosition: Int) {
        val default = arrayListOf<SelectItem<Int>>()
        val path = ACache.get().getAsString(exportBookPathKey)
        if (!path.isNullOrEmpty()) {
            default.add(SelectItem(path, -1))
        }
        exportDir.launch {
            otherActions = default
            requestCode = exportPosition
        }
    }

    private fun startExport(path: String, exportPosition: Int) {
        val exportType = when (AppConfig.exportType) {
            1 -> "epub"
            2 -> "pdf"
            else -> "txt"
        }
        if (exportPosition == -10) {
            if (booksState.isNotEmpty()) {
                booksState.forEach { book ->
                    startService<ExportBookService> {
                        action = IntentAction.start
                        putExtra("bookUrl", book.bookUrl)
                        putExtra("exportType", exportType)
                        putExtra("exportPath", path)
                    }
                }
            } else {
                toastOnUi(R.string.no_book)
            }
        } else if (exportPosition >= 0) {
            booksState.getOrNull(exportPosition)?.let { book ->
                startService<ExportBookService> {
                    action = IntentAction.start
                    putExtra("bookUrl", book.bookUrl)
                    putExtra("exportType", exportType)
                    putExtra("exportPath", path)
                }
            }
        }
    }

    private fun alertExportFileName() {
        showDialogFragment(
            io.legado.app.ui.widget.dialog.ComposeTextInputDialog(
                title = getString(R.string.export_file_name),
                initialValue = AppConfig.bookExportFileName.orEmpty(),
                message = "Variable: name, author.",
                hint = "file name js",
                onConfirm = { AppConfig.bookExportFileName = it },
            )
        )
    }

    private fun getTypeName(): String {
        return exportTypes.getOrElse(AppConfig.exportType) {
            exportTypes[0]
        }
    }

    private fun showExportTypeConfig(onUpdated: () -> Unit = {}) {
        selector(R.string.export_type, exportTypes) { _, i ->
            AppConfig.exportType = i
            onUpdated()
        }
    }

    private fun showCharsetConfig(onUpdated: () -> Unit = {}) {
        showDialogFragment(
            io.legado.app.ui.widget.dialog.ComposeTextInputDialog(
                title = getString(R.string.set_charset),
                initialValue = AppConfig.exportCharset,
                hint = "charset name",
                suggestions = charsets,
                onConfirm = {
                    AppConfig.exportCharset = it.ifBlank { "UTF-8" }
                    onUpdated()
                },
            )
        )
    }

    private fun sureCacheBook(action: () -> Unit) {
        alert(R.string.draw) {
            setMessage(R.string.sure_cache_book)
            noButton()
            yesButton {
                action.invoke()
            }
        }
    }

    fun exportProgress(bookUrl: String): Int? {
        return ExportBookService.exportProgress[bookUrl]
    }

    fun exportMsg(bookUrl: String): String? {
        return ExportBookService.exportMsg[bookUrl]
    }

}
