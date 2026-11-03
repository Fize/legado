package io.legado.app.ui.book.source.manage

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.ClipData
import android.net.Uri
import android.os.Bundle
import android.os.Build
import java.io.File
import io.legado.app.data.repository.SourceTargetStateStore
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.legado.app.R
import io.legado.app.base.VMBaseComposeActivity
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSourceCheckState
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.help.DirectLinkUpload
import io.legado.app.help.SourceSharePassphrase
import io.legado.app.help.config.AppConfig
import io.legado.app.help.storage.restoreWriteGeneration
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.sourceSharePassphraseButton
import io.legado.app.model.CheckSource
import io.legado.app.model.Debug
import io.legado.app.ui.navigation.openBookSearch
import io.legado.app.ui.book.source.edit.BookSourceEditActivity
import io.legado.app.ui.compose.component.LegadoMenuAction
import io.legado.app.ui.compose.component.LegadoTextInputDialog
import io.legado.app.ui.compose.component.LegadoSearchBar
import io.legado.app.ui.compose.component.LegadoSelectActionBar
import io.legado.app.ui.compose.component.LegadoTopAppBar
import io.legado.app.ui.config.SourceCheckDialog
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.AppActivity
import io.legado.app.ui.compose.component.LegadoSearchActionLayout
import io.legado.app.ui.navigation.BookSourceImportRoute
import io.legado.app.ui.navigation.SourceLoginRoute
import io.legado.app.ui.navigation.SourceLoginTarget
import io.legado.app.ui.qrcode.QrCodeResult
import io.legado.app.utils.ACache
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.cnCompare
import io.legado.app.utils.dpToPx
import io.legado.app.utils.flowWithLifecycleAndDatabaseChange
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.launch
import io.legado.app.utils.observeEvent
import io.legado.app.utils.sendToClip
import io.legado.app.utils.share
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.splitNotBlank
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Collections

private const val BOOK_SOURCE_GROUP_QUERY_PREFIX = "group:"
private enum class SourceGroupEditMode { Add, Remove }

internal fun isMissingBookSourceGroupFilter(
    query: CharSequence?,
    groups: Set<String>,
): Boolean {
    val searchKey = query?.toString() ?: return false
    if (!searchKey.startsWith(BOOK_SOURCE_GROUP_QUERY_PREFIX)) return false
    return searchKey.removePrefix(BOOK_SOURCE_GROUP_QUERY_PREFIX) !in groups
}

/**
 * 书源管理界面 (Jetpack Compose)
 */
class BookSourceActivity : VMBaseComposeActivity<BookSourceViewModel>() {

    override val guardsRestoreRecovery = true

    override val viewModel by viewModels<BookSourceViewModel>()
    private val importRecordKey = "bookSourceRecordKey"
    private var showOnlineImportDialogState by mutableStateOf(false)
    private var groupEditModeState by mutableStateOf<SourceGroupEditMode?>(null)
    private var groupEditTargetsState: List<String> = emptyList()
    private val sourceTargetStore by lazy { SourceTargetStateStore(File(noBackupFilesDir, "source-target-state")) }
    private var sourceTargetLease: SourceTargetStateStore.Lease? = null
    private val finalMessageRegex = Regex("成功|失败")

    private var sourceFlowJob: Job? = null
    private var checkMessageRefreshJob: Job? = null
    private val groups = linkedSetOf<String>()
    private var groupsState by mutableStateOf<List<String>>(emptyList())
    var sort by mutableStateOf(BookSourceSort.Default)
        private set
    private var sortAscending by mutableStateOf(true)
    private var checkSourceUiSessionId: Long? = null
    private var checkSnackBarMsgState by mutableStateOf<String?>(null)
    private var groupSourcesByDomain by mutableStateOf(false)
    private var showCheckStatus by mutableStateOf(false)
    private var checkStatusFilter by mutableIntStateOf(0)
    private var blockSourceNavigationState by mutableStateOf(false)
    private val checkStatuses = listOf(
        "",
        BookSourceCheckState.NEEDS_CHECK,
        BookSourceCheckState.PASSED,
        BookSourceCheckState.FAILED,
    )
    private val hostMap = hashMapOf<String, String>()

    // BookSourcePart equality is URL identity; fresh query rows may change their other fields.
    private var itemsState by mutableStateOf<List<BookSourcePart>>(emptyList(), referentialEqualityPolicy())
    private var selectedUrlsState by mutableStateOf<Set<String>>(emptySet())
    private var bookshelfCountsState by mutableStateOf<Map<String, Int>>(emptyMap())
    private var searchQueryState by mutableStateOf("")
    private var isSearchActiveState by mutableStateOf(false)
    private var checkRefreshTickState by mutableIntStateOf(0)

    private val selection: List<BookSourcePart>
        get() = itemsState.filter { selectedUrlsState.contains(it.bookSourceUrl) }

    private val qrResult = registerForActivityResult(QrCodeResult()) {
        if (redirectIfRestoreRecoveryRequired()) return@registerForActivityResult
        it ?: return@registerForActivityResult
        openBookSourceImport(it)
    }
    private val importDoc = registerForActivityResult(HandleFileContract()) {
        if (redirectIfRestoreRecoveryRequired()) return@registerForActivityResult
        it.uri?.let { uri ->
            openBookSourceImport(uri.toString(), uri)
        }
    }
    private fun openBookSourceImport(source: String, grantUri: Uri? = null) {
        val intent = AppActivity.startIntent(this, BookSourceImportRoute(source))
        grantUri?.let { uri ->
            intent.data = uri
            intent.clipData = ClipData.newRawUri("book source", uri)
            intent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(intent)
    }

    private val exportDir = registerForActivityResult(HandleFileContract()) {
        if (redirectIfRestoreRecoveryRequired()) return@registerForActivityResult
        it.uri?.let { uri ->
            val url = uri.toString()
            alert(R.string.export_success) {
                if (url.isAbsUrl()) {
                    setMessage(DirectLinkUpload.getSummary() + "\n\n" + url)
                    sourceSharePassphraseButton(
                        url,
                        SourceSharePassphrase.Type.BOOK_SOURCE,
                    )
                }
                if (!url.isAbsUrl()) setMessage(url)
                okButton {
                    sendToClip(url)
                }
            }
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        showOnlineImportDialogState = savedInstanceState?.getBoolean("onlineImportOpen") ?: false
        groupEditModeState = SourceGroupEditMode.entries.firstOrNull {
            it.name == savedInstanceState?.getString("groupEditMode")
        }
        try {
            // Task membership, rather than elapsed time, determines when abandoned snapshots expire.
            runCatching {
                val tasks = getSystemService(ActivityManager::class.java).appTasks.map {
                    sourceTargetTaskId(checkNotNull(it.taskInfo))
                }.toSet() + taskId
                sourceTargetStore.pruneRemovedTasks(tasks)
            }.onFailure { AppLog.put("清理书源选择快照失败", it) }
            val reference = savedInstanceState?.getString("sourceTargetsSnapshot")
            val lists = if (reference != null) {
                sourceTargetLease = sourceTargetStore.claim(reference)
                sourceTargetStore.read(reference).also { check(it.size == 2) }
            } else {
                check(savedInstanceState?.getBoolean("sourceTargetsUnavailable") != true)
                // Saved tasks from the preceding implementation still contain inline lists.
                listOf(savedInstanceState?.getStringArrayList("selectedSourceUrls").orEmpty(),
                    savedInstanceState?.getStringArrayList("groupEditTargets").orEmpty())
            }
            selectedUrlsState = lists[0].toSet()
            groupEditTargetsState = lists[1]
        } catch (error: Exception) {
            AppLog.put("恢复书源选择失败", error)
            selectedUrlsState = emptySet()
            groupEditTargetsState = emptyList()
            groupEditModeState = null
            toastOnUi("无法恢复书源选择，请重新选择后操作")
        }
        if (sourceTargetLease == null) {
            runCatching { sourceTargetStore.create(taskId) }.onSuccess { sourceTargetLease = it }
                .onFailure { AppLog.put("创建书源选择快照失败", it) }
        }
        showCheckStatus = AppConfig.showSourceCheckStatus
        blockSourceNavigationState = AppConfig.blockSourceNavigation
        val savedSearch = savedInstanceState?.getString("sourceSearch").orEmpty()
        searchQueryState = savedSearch
        isSearchActiveState = savedSearch.isNotEmpty()
        checkStatusFilter = if (showCheckStatus) {
            savedInstanceState?.getInt("checkStatusFilter")?.coerceIn(0, 3) ?: 0
        } else 0
        upBookSource(savedSearch)
        initLiveDataGroup()
        lifecycleScope.launch {
            appDb.bookDao.flowBookshelfSourceOrigins()
                .map { origins -> origins.groupingBy { it }.eachCount() }
                .distinctUntilChanged()
                .flowOn(IO)
                .flowWithLifecycle(lifecycle)
                .catch { AppLog.put("更新书源书架引用数出错", it) }
                .collect { bookshelfCountsState = it }
        }
        resumeCheckSource()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (isRestoreRecoveryBlocked) { super.onSaveInstanceState(outState); return }
        outState.putInt("checkStatusFilter", checkStatusFilter)
        outState.putString("sourceSearch", searchQueryState)
        outState.putBoolean("onlineImportOpen", showOnlineImportDialogState)
        outState.putString("groupEditMode", groupEditModeState?.name)
        try {
            val lease = sourceTargetLease ?: sourceTargetStore.create(taskId).also { sourceTargetLease = it }
            outState.putString("sourceTargetsSnapshot", sourceTargetStore.save(lease,
                listOf(selectedUrlsState.toList(), groupEditTargetsState)))
        } catch (error: Exception) {
            AppLog.put("保存书源选择失败", error)
            outState.putBoolean("sourceTargetsUnavailable", true)
            outState.remove("groupEditMode")
            toastOnUi("书源选择未能保存，返回后请重新选择")
        }
        super.onSaveInstanceState(outState)
    }

    override fun setupSystemBar() {
        super.setupSystemBar()
        // Compose consumes system/IME insets itself, including on Android 8.
        WindowCompat.setDecorFitsSystemWindows(window, false)
    }

    @Composable
    override fun Content() {
        val items = itemsState
        val selectedUrls = selectedUrlsState
        val bookshelfCounts = bookshelfCountsState
        @Suppress("UNUSED_VARIABLE")
        val checkTick = checkRefreshTickState
        val canDrag = sort == BookSourceSort.Default && !groupSourcesByDomain

        var showSortMenu by remember { mutableStateOf(false) }
        var showGroupMenu by remember { mutableStateOf(false) }

        BoxWithConstraints(Modifier.fillMaxSize().navigationBarsPadding().imePadding()) {
        // A short window with large text cannot stack both search and selection
        // above the IME. Move the same input session beside the selection menu.
        val compactLayout = isSearchActiveState && maxHeight < 280.dp
        LegadoSearchActionLayout(
            compact = compactLayout,
            modifier = Modifier.fillMaxSize().then(if (compactLayout) Modifier.statusBarsPadding() else Modifier),
            searchBar = {
                if (isSearchActiveState) LegadoSearchBar(
                    query = searchQueryState,
                    onQueryChange = { searchQueryState = it; upBookSource(it) },
                    placeholder = stringResource(R.string.search_book_source),
                    compact = compactLayout,
                )
            },
            topBar = {
                if (!compactLayout) Column {
                    LegadoTopAppBar(
                        title = stringResource(R.string.book_source),
                        subtitle = searchQueryState.takeIf { it.isNotBlank() },
                        onBack = { finish() },
                        actions = {
                            IconButton(
                                onClick = { isSearchActiveState = !isSearchActiveState },
                                modifier = Modifier.testTag("bookSourceSearch"),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Search,
                                    contentDescription = stringResource(R.string.search_book_source),
                                )
                            }
                            Box {
                                IconButton(onClick = { showSortMenu = true }) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_sort),
                                        contentDescription = stringResource(R.string.sort),
                                    )
                                }
                                DropdownMenu(
                                    expanded = showSortMenu,
                                    onDismissRequest = { showSortMenu = false },
                                ) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.sort_desc)) },
                                        trailingIcon = {
                                            Checkbox(
                                                checked = !sortAscending,
                                                onCheckedChange = null,
                                            )
                                        },
                                        onClick = {
                                            sortAscending = !sortAscending
                                            upBookSource(searchQueryState)
                                        },
                                    )
                                    HorizontalDivider()
                                    val sortOptions = listOf(
                                        BookSourceSort.Default to R.string.sort_manual,
                                        BookSourceSort.Weight to R.string.sort_auto,
                                        BookSourceSort.Name to R.string.sort_by_name,
                                        BookSourceSort.Url to R.string.sort_by_url,
                                        BookSourceSort.Update to R.string.sort_by_lastUpdateTime,
                                        BookSourceSort.Respond to R.string.sort_by_respondTime,
                                        BookSourceSort.Enable to R.string.is_enabled,
                                    )
                                    sortOptions.forEach { (sortMode, labelRes) ->
                                        DropdownMenuItem(
                                            text = { Text(stringResource(labelRes)) },
                                            trailingIcon = {
                                                if (sort == sortMode) {
                                                    Icon(
                                                        imageVector = Icons.Default.Check,
                                                        contentDescription = null,
                                                    )
                                                }
                                            },
                                            onClick = {
                                                sort = sortMode
                                                showSortMenu = false
                                                upBookSource(searchQueryState)
                                            },
                                        )
                                    }
                                }
                            }
                            Box {
                                IconButton(onClick = { showGroupMenu = true }) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_groups),
                                        contentDescription = stringResource(R.string.menu_action_group),
                                    )
                                }
                                DropdownMenu(
                                    expanded = showGroupMenu,
                                    onDismissRequest = { showGroupMenu = false },
                                ) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.group_manage)) },
                                        onClick = {
                                            showGroupMenu = false
                                            showDialogFragment<GroupManageDialog>()
                                        },
                                    )
                                    val quickFilters = listOf(
                                        R.string.enabled,
                                        R.string.disabled,
                                        R.string.need_login,
                                        R.string.no_group,
                                        R.string.enabled_explore,
                                        R.string.disabled_explore,
                                    )
                                    quickFilters.forEach { resId ->
                                        val label = stringResource(resId)
                                        DropdownMenuItem(
                                            text = { Text(label) },
                                            onClick = {
                                                showGroupMenu = false
                                                searchQueryState = label
                                                isSearchActiveState = true
                                                upBookSource(label)
                                            },
                                        )
                                    }
                                    if (groupsState.isNotEmpty()) {
                                        HorizontalDivider()
                                        groupsState.forEach { groupName ->
                                            DropdownMenuItem(
                                                text = { Text(groupName) },
                                                onClick = {
                                                    showGroupMenu = false
                                                    val q = "group:$groupName"
                                                    searchQueryState = q
                                                    isSearchActiveState = true
                                                    upBookSource(q)
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                        },
                        menuActions = listOf(
                            LegadoMenuAction(
                                title = stringResource(R.string.import_local),
                                iconRes = R.drawable.ic_import,
                                onClick = {
                                    importDoc.launch {
                                        mode = HandleFileContract.FILE
                                        allowExtensions = arrayOf("txt", "json", "js")
                                    }
                                },
                            ),
                            LegadoMenuAction(
                                title = stringResource(R.string.import_on_line),
                                iconRes = R.drawable.ic_import,
                                onClick = { showImportDialog() },
                            ),
                            LegadoMenuAction(
                                title = stringResource(R.string.import_by_qr_code),
                                iconRes = R.drawable.ic_import,
                                onClick = { qrResult.launch() },
                            ),
                            LegadoMenuAction(
                                title = stringResource(R.string.group_sources_by_domain),
                                iconRes = R.drawable.ic_add_online,
                                checked = groupSourcesByDomain,
                                onClick = {
                                    groupSourcesByDomain = !groupSourcesByDomain
                                    upBookSource(searchQueryState)
                                },
                            ),
                            LegadoMenuAction(
                                title = stringResource(R.string.show_source_check_status),
                                iconRes = R.drawable.ic_find_replace,
                                checked = showCheckStatus,
                                onClick = {
                                    showCheckStatus = !showCheckStatus
                                    AppConfig.showSourceCheckStatus = showCheckStatus
                                    if (!showCheckStatus) {
                                        checkStatusFilter = 0
                                    }
                                    checkRefreshTickState++
                                    upBookSource(searchQueryState)
                                },
                            ),
                            LegadoMenuAction(
                                title = stringResource(R.string.block_source_navigation),
                                iconRes = R.drawable.ic_block,
                                checked = blockSourceNavigationState,
                                onClick = {
                                    blockSourceNavigationState = !blockSourceNavigationState
                                    AppConfig.blockSourceNavigation = blockSourceNavigationState
                                },
                            ),
                        ),
                    )
                    if (showCheckStatus) {
                        val filterOptions = stringArrayResource(R.array.source_check_status_options)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            filterOptions.forEachIndexed { index, optionLabel ->
                                FilterChip(
                                    selected = checkStatusFilter == index,
                                    onClick = {
                                        if (checkStatusFilter != index) {
                                            checkStatusFilter = index
                                            upBookSource(searchQueryState)
                                        }
                                    },
                                    label = { Text(optionLabel) },
                                    modifier = Modifier.testTag("bookSourceCheckFilter$index"),
                                )
                            }
                        }
                    }
                }
            },
            bottomBar = {
                Column {
                    checkSnackBarMsgState?.let { msg ->
                        Surface(
                            color = MaterialTheme.colorScheme.inverseSurface,
                            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = msg,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(
                                    onClick = {
                                        checkSourceUiSessionId?.let { sessionId ->
                                            CheckSource.stop(this@BookSourceActivity, sessionId)
                                        }
                                    }
                                ) {
                                    Text(
                                        text = stringResource(R.string.cancel),
                                        color = MaterialTheme.colorScheme.inversePrimary,
                                    )
                                }
                            }
                        }
                    }
                    LegadoSelectActionBar(
                        compact = compactLayout,
                        selectedCount = items.count { it.bookSourceUrl in selectedUrls },
                        totalCount = items.size,
                        mainActionText = stringResource(R.string.delete),
                        onSelectAllChange = { selectAll ->
                            selectAll(selectAll)
                        },
                        onRevertSelection = {
                            revertSelection()
                        },
                        onMainActionClick = {
                            onClickSelectBarMainAction()
                        },
                        menuActions = listOf(
                            LegadoMenuAction(
                                title = stringResource(R.string.enable_selection),
                                onClick = { viewModel.enableSelection(selection) },
                            ),
                            LegadoMenuAction(
                                title = stringResource(R.string.disable_selection),
                                onClick = { viewModel.disableSelection(selection) },
                            ),
                            LegadoMenuAction(
                                title = stringResource(R.string.add_group),
                                onClick = { selectionAddToGroups() },
                            ),
                            LegadoMenuAction(
                                title = stringResource(R.string.remove_group),
                                onClick = { selectionRemoveFromGroups() },
                            ),
                            LegadoMenuAction(
                                title = stringResource(R.string.enable_explore),
                                onClick = { viewModel.enableSelectExplore(selection) },
                            ),
                            LegadoMenuAction(
                                title = stringResource(R.string.disable_explore),
                                onClick = { viewModel.disableSelectExplore(selection) },
                            ),
                            LegadoMenuAction(
                                title = stringResource(R.string.selection_to_top),
                                onClick = { viewModel.topSource(*selection.toTypedArray()) },
                            ),
                            LegadoMenuAction(
                                title = stringResource(R.string.selection_to_bottom),
                                onClick = { viewModel.bottomSource(*selection.toTypedArray()) },
                            ),
                            LegadoMenuAction(
                                title = stringResource(R.string.export_selection),
                                onClick = {
                                    viewModel.saveToFile(
                                        selection,
                                        items.size,
                                        searchQueryState,
                                        sortAscending,
                                        sort,
                                    ) { file, name ->
                                        exportDir.launch {
                                            mode = HandleFileContract.EXPORT
                                            val isJs = file.name.endsWith(".js")
                                            fileData = HandleFileContract.FileData(
                                                name,
                                                file,
                                                if (isJs) "text/javascript" else "application/json",
                                            )
                                        }
                                    }
                                },
                            ),
                            LegadoMenuAction(
                                title = stringResource(R.string.share_selected_source),
                                onClick = {
                                    viewModel.saveToFile(
                                        selection,
                                        items.size,
                                        searchQueryState,
                                        sortAscending,
                                        sort,
                                    ) { file, _ ->
                                        share(file, if (file.name.endsWith(".js")) "text/javascript" else "text/*")
                                    }
                                },
                            ),
                            LegadoMenuAction(
                                title = stringResource(R.string.check_select_source),
                                iconRes = R.drawable.ic_check_source,
                                onClick = { checkSource() },
                            ),
                            LegadoMenuAction(
                                title = stringResource(R.string.check_selected_interval),
                                onClick = { checkSelectedInterval() },
                            ),
                        ),
                    )
                }
            },
        ) {
            if (items.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                ) {
                    itemsIndexed(
                        items = items,
                        key = { _, item -> item.bookSourceUrl },
                    ) { index, item ->
                        val isSelected = selectedUrls.contains(item.bookSourceUrl)
                        val hostHeader = if (groupSourcesByDomain) {
                            val currentHost = getSourceHost(item.bookSourceUrl)
                            val prevHost = items.getOrNull(index - 1)?.let { getSourceHost(it.bookSourceUrl) }
                            if (index == 0 || currentHost != prevHost) currentHost else null
                        } else null

                        val debugMsg = if (Debug.isChecking) Debug.debugMessageMap[item.bookSourceUrl].orEmpty() else ""
                        val statusText = when (item.checkStatus) {
                            BookSourceCheckState.PASSED -> stringResource(R.string.source_check_passed)
                            BookSourceCheckState.FAILED -> stringResource(R.string.source_check_failed)
                            else -> stringResource(R.string.source_check_needed)
                        }
                        val displayCheckText = if (!showCheckStatus && debugMsg.isEmpty()) {
                            null
                        } else {
                            debugMsg.ifEmpty {
                                if (item.checkDetail.isEmpty()) statusText else "$statusText：${item.checkDetail}"
                            }
                        }
                        val showCheckProgress = debugMsg.isNotEmpty() && !debugMsg.contains(finalMessageRegex)

                        BookSourceItemRow(
                            source = item,
                            isSelected = isSelected,
                            hostHeader = hostHeader,
                            bookshelfCount = bookshelfCounts[item.bookSourceUrl] ?: 0,
                            checkMessage = displayCheckText,
                            showCheckProgress = showCheckProgress,
                            canDrag = canDrag,
                            onToggleSelect = { checked ->
                                selectedUrlsState = if (checked) {
                                    selectedUrls + item.bookSourceUrl
                                } else {
                                    selectedUrls - item.bookSourceUrl
                                }
                            },
                            onToggleEnable = { checked ->
                                enable(checked, item)
                            },
                            onEdit = { edit(item) },
                            onMoveStep = { direction ->
                                val targetIndex = index + direction
                                val target = items.getOrNull(targetIndex)
                                if (target != null) {
                                    move(item.bookSourceUrl, target.bookSourceUrl, direction > 0) {
                                        reload()
                                    }
                                }
                            },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
        }
        if (showOnlineImportDialogState) {
            OnlineImportDialog()
        }
        groupEditModeState?.let { GroupEditDialog(it) }
    }

    @Composable
    private fun BookSourceItemRow(
        source: BookSourcePart,
        isSelected: Boolean,
        hostHeader: String?,
        bookshelfCount: Int,
        checkMessage: String?,
        showCheckProgress: Boolean,
        canDrag: Boolean,
        onToggleSelect: (Boolean) -> Unit,
        onToggleEnable: (Boolean) -> Unit,
        onEdit: () -> Unit,
        onMoveStep: (Int) -> Unit,
    ) {
        var showRowMenu by remember { mutableStateOf(false) }
        var dragAccumulator by remember { mutableFloatStateOf(0f) }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggleSelect(!isSelected) }
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            if (hostHeader != null) {
                Text(
                    text = hostHeader,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (canDrag) {
                    Icon(
                        imageVector = Icons.Default.DragHandle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .size(24.dp)
                            .pointerInput(source.bookSourceUrl) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { dragAccumulator = 0f },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        dragAccumulator += dragAmount.y
                                        if (dragAccumulator > 96f) {
                                            dragAccumulator = 0f
                                            onMoveStep(1)
                                        } else if (dragAccumulator < -96f) {
                                            dragAccumulator = 0f
                                            onMoveStep(-1)
                                        }
                                    },
                                )
                            },
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                }
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = onToggleSelect,
                )
                Text(
                    text = source.getDisPlayNameGroup(),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 4.dp),
                )
                if (source.hasJs) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier.padding(end = 6.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.js_source_badge),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                        )
                    }
                }
                Switch(
                    checked = source.enabled,
                    onCheckedChange = onToggleEnable,
                    modifier = Modifier.padding(end = 4.dp),
                )
                IconButton(
                    onClick = onEdit,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_edit),
                        contentDescription = stringResource(R.string.edit),
                        modifier = Modifier.size(20.dp),
                    )
                }
                Box {
                    IconButton(
                        onClick = { showRowMenu = true },
                        modifier = Modifier.size(36.dp).testTag("bookSourceRowMenu:${source.bookSourceUrl}"),
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = stringResource(R.string.more_menu),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    if (source.hasExploreUrl) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .align(Alignment.TopEnd)
                                .clip(CircleShape)
                                .background(if (source.enabledExplore) Color.Green else Color.Red)
                        )
                    }
                    DropdownMenu(
                        expanded = showRowMenu,
                        onDismissRequest = { showRowMenu = false },
                    ) {
                        val defaultOrder = sort == BookSourceSort.Default
                        if (defaultOrder) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.to_top)) },
                                onClick = {
                                    showRowMenu = false
                                    toTop(source)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.to_bottom)) },
                                onClick = {
                                    showRowMenu = false
                                    toBottom(source)
                                },
                            )
                        }
                        if (source.hasLoginUrl) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.login)) },
                                onClick = {
                                    showRowMenu = false
                                    startActivity(
                                        AppActivity.startIntent(
                                            this@BookSourceActivity,
                                            SourceLoginRoute(
                                                SourceLoginTarget.BookSource,
                                                key = source.bookSourceUrl,
                                            ),
                                        ),
                                    )
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.search)) },
                            onClick = {
                                showRowMenu = false
                                searchBook(source)
                            },
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = stringResource(R.string.delete),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            },
                            onClick = {
                                showRowMenu = false
                                del(source)
                                selectedUrlsState = selectedUrlsState - source.bookSourceUrl
                            },
                        )
                        if (source.hasExploreUrl) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(
                                            if (source.enabledExplore) R.string.disable_explore else R.string.enable_explore
                                        )
                                    )
                                },
                                onClick = {
                                    showRowMenu = false
                                    enableExplore(!source.enabledExplore, source)
                                },
                            )
                        }
                    }
                }
            }
            Text(
                text = stringResource(R.string.source_bookshelf_count, bookshelfCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 40.dp, top = 2.dp),
            )
            if (checkMessage != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 40.dp, top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = checkMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (showCheckProgress) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .padding(start = 8.dp)
                                .size(18.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                }
            }
        }
    }

    private fun upBookSource(searchKey: String? = null) {
        sourceFlowJob?.cancel()
        sourceFlowJob = lifecycleScope.launch {
            when {
                searchKey.isNullOrEmpty() -> {
                    appDb.bookSourceDao.flowAll()
                }

                searchKey == getString(R.string.enabled) -> {
                    appDb.bookSourceDao.flowEnabled()
                }

                searchKey == getString(R.string.disabled) -> {
                    appDb.bookSourceDao.flowDisabled()
                }

                searchKey == getString(R.string.need_login) -> {
                    appDb.bookSourceDao.flowLogin()
                }

                searchKey == getString(R.string.no_group) -> {
                    appDb.bookSourceDao.flowNoGroup()
                }

                searchKey == getString(R.string.enabled_explore) -> {
                    appDb.bookSourceDao.flowEnabledExplore()
                }

                searchKey == getString(R.string.disabled_explore) -> {
                    appDb.bookSourceDao.flowDisabledExplore()
                }

                searchKey.startsWith("group:") -> {
                    val key = searchKey.substringAfter("group:")
                    appDb.bookSourceDao.flowGroupSearch(key)
                }

                else -> {
                    appDb.bookSourceDao.flowSearch(searchKey)
                }
            }.map { sources ->
                val state = if (showCheckStatus) checkStatuses[checkStatusFilter] else ""
                sources.filter { state.isEmpty() || it.checkStatus == state }
            }.map { data ->
                hostMap.clear()
                if (groupSourcesByDomain) {
                    data.sortedWith(
                        compareBy<BookSourcePart> { getSourceHost(it.bookSourceUrl) == "#" }
                            .thenBy { getSourceHost(it.bookSourceUrl) }
                            .thenByDescending { it.lastUpdateTime })
                } else if (sortAscending) {
                    when (sort) {
                        BookSourceSort.Weight -> data.sortedBy { it.weight }
                        BookSourceSort.Name -> data.sortedWith { o1, o2 ->
                            o1.bookSourceName.cnCompare(o2.bookSourceName)
                        }

                        BookSourceSort.Url -> data.sortedBy { it.bookSourceUrl }
                        BookSourceSort.Update -> data.sortedByDescending { it.lastUpdateTime }
                        BookSourceSort.Respond -> data.sortedBy { it.respondTime }
                        BookSourceSort.Enable -> data.sortedWith { o1, o2 ->
                            var sortNum = -o1.enabled.compareTo(o2.enabled)
                            if (sortNum == 0) {
                                sortNum = o1.bookSourceName.cnCompare(o2.bookSourceName)
                            }
                            sortNum
                        }

                        else -> data
                    }
                } else {
                    when (sort) {
                        BookSourceSort.Weight -> data.sortedByDescending { it.weight }
                        BookSourceSort.Name -> data.sortedWith { o1, o2 ->
                            o2.bookSourceName.cnCompare(o1.bookSourceName)
                        }

                        BookSourceSort.Url -> data.sortedByDescending { it.bookSourceUrl }
                        BookSourceSort.Update -> data.sortedBy { it.lastUpdateTime }
                        BookSourceSort.Respond -> data.sortedByDescending { it.respondTime }
                        BookSourceSort.Enable -> data.sortedWith { o1, o2 ->
                            var sortNum = o1.enabled.compareTo(o2.enabled)
                            if (sortNum == 0) {
                                sortNum = o1.bookSourceName.cnCompare(o2.bookSourceName)
                            }
                            sortNum
                        }

                        else -> data.reversed()
                    }
                }
            }.flowWithLifecycle(lifecycle).catch {
                AppLog.put("书源界面更新书源出错", it)
            }.flowOn(IO).conflate().collect { data ->
                itemsState = data
                delay(500)
            }
        }
    }

    private fun initLiveDataGroup() {
        lifecycleScope.launch {
            appDb.bookSourceDao.flowGroups()
                .flowWithLifecycleAndDatabaseChange(
                    lifecycle,
                    table = AppDatabase.BOOK_SOURCE_TABLE_NAME
                )
                .conflate()
                .distinctUntilChanged()
                .collect {
                    groups.clear()
                    groups.addAll(it)
                    groupsState = it
                    if (isMissingBookSourceGroupFilter(searchQueryState, groups)) {
                        searchQueryState = ""
                        upBookSource("")
                    }
                    delay(500)
                }
        }
    }

    fun selectAll(selectAll: Boolean) {
        if (selectAll) {
            selectedUrlsState = itemsState.mapTo(linkedSetOf()) { it.bookSourceUrl }
        } else {
            revertSelection()
        }
    }

    fun revertSelection() {
        val current = selectedUrlsState
        val next = linkedSetOf<String>()
        itemsState.forEach {
            if (!current.contains(it.bookSourceUrl)) {
                next.add(it.bookSourceUrl)
            }
        }
        selectedUrlsState = next
    }

    fun checkSelectedInterval() {
        val selectedPosition = linkedSetOf<Int>()
        itemsState.forEachIndexed { index, it ->
            if (selectedUrlsState.contains(it.bookSourceUrl)) {
                selectedPosition.add(index)
            }
        }
        if (selectedPosition.isEmpty()) return
        val minPosition = Collections.min(selectedPosition)
        val maxPosition = Collections.max(selectedPosition)
        val next = selectedUrlsState.toMutableSet()
        for (i in minPosition..maxPosition) {
            itemsState.getOrNull(i)?.let {
                next.add(it.bookSourceUrl)
            }
        }
        selectedUrlsState = next
    }

    private fun onClickSelectBarMainAction() {
        alert(titleResource = R.string.draw, messageResource = R.string.sure_del) {
            yesButton { viewModel.del(selection) }
            noButton()
        }
    }

    private fun checkSource() {
        runCatching {
            showDialogFragment(SourceCheckDialog.create(CheckSource.keyword, selection.map { it.bookSourceUrl }, taskId))
        }.onFailure {
            AppLog.put("保存校验目标失败", it)
            toastOnUi("无法保存校验目标，请稍后重试")
        }
    }

    internal fun confirmSourceCheck(keyword: String, sourceUrls: Collection<String>) {
        keepScreenOn(true)
        if (keyword.isNotEmpty()) CheckSource.keyword = keyword
        val targets = sourceUrls.toSet()
        if (targets.isEmpty()) {
            keepScreenOn(false)
            return
        }
        val generation = restoreWriteGeneration.capture()
        val checkSessionId = Debug.tryStartCheckSession()
        if (checkSessionId == null) {
            keepScreenOn(false)
            toastOnUi("书源调试通道占用中，请稍后重试")
            return
        }
        lifecycleScope.launch {
            try {
                // Resolve restored targets inside the existing restore write boundary.
                CheckSource.startSelected(this@BookSourceActivity, targets, checkSessionId, generation)
                checkSourceUiSessionId = checkSessionId
                startCheckMessageRefreshJob()
            } catch (error: Exception) {
                keepScreenOn(false)
                toastOnUi(error.localizedMessage ?: "无法启动书源检验")
            }
        }
    }

    private fun resumeCheckSource() {
        if (!Debug.isChecking) {
            return
        }
        keepScreenOn(true)
        CheckSource.resume(this)
        startCheckMessageRefreshJob()
    }

    private fun selectionAddToGroups() {
        groupEditTargetsState = selection.map { it.bookSourceUrl }
        if (groupEditTargetsState.isEmpty()) return
        groupEditModeState = SourceGroupEditMode.Add
    }

    private fun selectionRemoveFromGroups() {
        groupEditTargetsState = selection.map { it.bookSourceUrl }
        if (groupEditTargetsState.isEmpty()) return
        groupEditModeState = SourceGroupEditMode.Remove
    }

    @Composable
    private fun GroupEditDialog(mode: SourceGroupEditMode) {
        LegadoTextInputDialog(
            title = stringResource(if (mode == SourceGroupEditMode.Add) R.string.add_group else R.string.remove_group),
            initialValue = "",
            hint = stringResource(R.string.group_name),
            suggestions = groupsState,
            onDismiss = {
                groupEditModeState = null
                groupEditTargetsState = emptyList()
            },
            onConfirm = { value ->
                if (value.isNotEmpty()) {
                    if (mode == SourceGroupEditMode.Add) {
                        viewModel.selectionAddToGroups(groupEditTargetsState, value)
                    } else {
                        viewModel.selectionRemoveFromGroups(groupEditTargetsState, value)
                    }
                }
            },
        )
    }

    private fun showImportDialog() {
        showOnlineImportDialogState = true
    }

    @Composable
    private fun OnlineImportDialog() {
        val aCache = remember { ACache.get(cacheDir = false) }
        val cacheUrls = remember {
            (aCache.getAsString(importRecordKey)?.splitNotBlank(",")?.toList()
                ?: emptyList()).toMutableStateList()
        }
        LegadoTextInputDialog(
            title = stringResource(R.string.import_on_line),
            initialValue = "",
            hint = "url",
            // URLs and pasted source JSON must keep their exact characters.
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
            suggestions = cacheUrls,
            onDeleteSuggestion = {
                cacheUrls.remove(it)
                aCache.put(importRecordKey, cacheUrls.joinToString(","))
            },
            onDismiss = { showOnlineImportDialogState = false },
            onConfirm = { text ->
                if (text.isAbsUrl() && !cacheUrls.contains(text)) {
                    cacheUrls.add(0, text)
                    aCache.put(importRecordKey, cacheUrls.joinToString(","))
                }
                openBookSourceImport(text)
            },
        )
    }

    override fun observeLiveBus() {
        observeEvent<Pair<Long, String>>(EventBus.CHECK_SOURCE) { (checkSessionId, msg) ->
            if (!Debug.isChecking(checkSessionId)) return@observeEvent
            checkSourceUiSessionId = checkSessionId
            checkSnackBarMsgState = msg
            checkRefreshTickState++
        }
        observeEvent<Long>(EventBus.CHECK_SOURCE_DONE) { checkSessionId ->
            if (checkSourceUiSessionId != null && checkSourceUiSessionId != checkSessionId) {
                return@observeEvent
            }
            checkSourceUiSessionId = null
            keepScreenOn(false)
            checkSnackBarMsgState = null
            checkRefreshTickState++
            Bundle().apply {
                putString("checkSourceMessage", null)
            }
        }
    }

    private fun startCheckMessageRefreshJob() {
        checkMessageRefreshJob?.cancel()
        checkMessageRefreshJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    checkRefreshTickState++
                    if (!Debug.isChecking) {
                        checkMessageRefreshJob?.cancel()
                    }
                    delay(300L)
                }
            }
        }
    }

    private fun keepScreenOn(on: Boolean) {
        val isScreenOn =
            (window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0
        if (on == isScreenOn) return
        if (on) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    fun getSourceHost(origin: String): String {
        return hostMap.getOrPut(origin) {
            NetworkUtils.getSubDomainOrNull(origin) ?: "#"
        }
    }

    fun del(bookSource: BookSourcePart) {
        alert(R.string.draw) {
            setMessage(getString(R.string.sure_del) + "\n" + bookSource.bookSourceName)
            noButton()
            yesButton {
                viewModel.del(listOf(bookSource))
            }
        }
    }

    fun edit(bookSource: BookSourcePart) {
        startActivity<BookSourceEditActivity> {
            putExtra("sourceUrl", bookSource.bookSourceUrl)
        }
    }

    fun move(sourceUrl: String, targetUrl: String, after: Boolean, onFinally: () -> Unit) {
        viewModel.move(sourceUrl, targetUrl, if (sortAscending) after else !after, onFinally)
    }

    fun reload() = upBookSource(searchQueryState)

    fun enable(enable: Boolean, bookSource: BookSourcePart) {
        viewModel.enable(enable, listOf(bookSource))
    }

    fun enableExplore(enable: Boolean, bookSource: BookSourcePart) {
        viewModel.enableExplore(enable, listOf(bookSource))
    }

    fun toTop(bookSource: BookSourcePart) {
        if (sortAscending) {
            viewModel.topSource(bookSource)
        } else {
            viewModel.bottomSource(bookSource)
        }
    }

    fun toBottom(bookSource: BookSourcePart) {
        if (sortAscending) {
            viewModel.bottomSource(bookSource)
        } else {
            viewModel.topSource(bookSource)
        }
    }

    fun searchBook(bookSource: BookSourcePart) {
        this.openBookSearch(bookSource)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) sourceTargetLease?.let { lease ->
            runCatching { sourceTargetStore.release(lease) }.onFailure { AppLog.put("清理书源选择快照失败", it) }
        }
        if (!wasActivityContentCreated) return
        if (!Debug.isChecking) {
            Debug.debugMessageMap.clear()
        }
    }
}

/** Inactive pre-Q tasks have id == -1 but remain restorable through their persistent ID. */
internal fun sourceTargetTaskId(info: ActivityManager.RecentTaskInfo): Int {
    @Suppress("DEPRECATION")
    return if (Build.VERSION.SDK_INT >= 29) info.taskId else info.persistentId
}
