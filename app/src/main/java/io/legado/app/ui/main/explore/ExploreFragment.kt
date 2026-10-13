package io.legado.app.ui.main.explore

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.collection.LruCache
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.script.rhino.runScriptWithContext
import io.legado.app.R
import io.legado.app.base.VMBaseFragment
import io.legado.app.constant.AppLog
import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.data.entities.rule.ExploreKind
import io.legado.app.data.entities.rule.ExploreKind.Type
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.source.clearExploreKindsCache
import io.legado.app.help.source.exploreKinds
import io.legado.app.lib.dialogs.alert
import io.legado.app.ui.book.explore.ExploreShowActivity
import io.legado.app.ui.book.search.SearchActivity
import io.legado.app.ui.book.source.edit.BookSourceEditActivity
import io.legado.app.ui.book.source.manage.BookSourceActivity
import io.legado.app.ui.compose.component.LegadoTopAppBar
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.ui.login.SourceLoginJsExtensions
import io.legado.app.ui.main.MainFragmentInterface
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.dialog.TextDialog
import io.legado.app.utils.InfoMap
import io.legado.app.utils.dpToPx
import io.legado.app.utils.flowWithLifecycleAndDatabaseChange
import io.legado.app.utils.resolveTabletContentPadding
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val GROUP_QUERY_PREFIX = "group:"

internal fun exploreGroupFromQuery(query: CharSequence?): String? {
    return query?.toString()
        ?.takeIf { it.startsWith(GROUP_QUERY_PREFIX) }
        ?.removePrefix(GROUP_QUERY_PREFIX)
}

internal fun isExploreAllQuery(query: CharSequence?): Boolean =
    exploreGroupFromQuery(query) == null

internal fun exploreScrollState(
    pending: Pair<Int, Int>?,
    position: Int,
    currentTop: Int?,
): Pair<Pair<Int, Int>?, Int> {
    val offset = pending?.takeIf { it.first == position }?.second ?: currentTop ?: 0
    val nextPending: Pair<Int, Int>? = if (pending?.first == position) null else position to offset
    return nextPending to offset
}

internal fun selectedExploreGroup(
    query: CharSequence?,
    groups: Set<String>
): String? = exploreGroupFromQuery(query)?.takeIf(groups::contains)

internal fun isExploreBindingCurrent(
    expectedLoadVersion: Int,
    currentLoadVersion: Int,
    expandedPosition: Int,
    bindingPosition: Int,
    boundSourceUrl: String,
    currentSourceUrl: String?,
): Boolean = expectedLoadVersion == currentLoadVersion &&
    expandedPosition == bindingPosition &&
    boundSourceUrl == currentSourceUrl

/**
 * 发现界面
 */
class ExploreFragment() : VMBaseFragment<ExploreViewModel>(),
    MainFragmentInterface {

    constructor(position: Int) : this() {
        val bundle = Bundle()
        bundle.putInt("position", position)
        arguments = bundle
    }

    companion object {
        val exploreInfoMapList = LruCache<String, InfoMap>(99)
    }

    override val position: Int? get() = arguments?.getInt("position")

    override val viewModel by viewModels<ExploreViewModel>()

    private val groups = linkedSetOf<String>()
    private var groupsState by mutableStateOf<Set<String>>(emptySet())
    private var sourcesState by mutableStateOf<List<BookSourcePart>>(emptyList())
    private var searchQueryState by mutableStateOf("")
    private var expandedSourceUrlState by mutableStateOf<String?>(null)
    private var loadingSourceUrlState by mutableStateOf<String?>(null)
    private var kindLoadVersion by mutableIntStateOf(0)
    private val sourceKindsMap = mutableStateMapOf<String, List<ExploreKind>>()
    private var exploreFlowJob: Job? = null
    private var saveInfoMapJob: Job? = null
    private val listState = LazyListState()

    val scope: CoroutineScope
        get() = viewLifecycleOwner.lifecycleScope

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LegadoTheme {
                    ExploreScreen()
                }
            }
        }
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        initGroupData()
        upExploreData()
    }

    @OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
    @Composable
    private fun ExploreScreen() {
        val context = LocalContext.current
        val configuration = LocalConfiguration.current
        val density = LocalDensity.current
        var groupMenuExpanded by remember { mutableStateOf(false) }

        val hPaddingDp = remember(configuration.screenWidthDp) {
            val px = resolveTabletContentPadding(
                containerWidthPx = configuration.screenWidthDp.dpToPx(),
                screenWidthDp = configuration.screenWidthDp,
                maxContentWidthPx = 720.dpToPx(),
                minTabletPaddingPx = 24.dpToPx(),
            )
            with(density) { px.toDp() }
        }

        Scaffold(
            topBar = {
                LegadoTopAppBar(
                    title = stringResource(R.string.discovery),
                    searchQuery = searchQueryState,
                    onSearchQueryChange = { query ->
                        searchQueryState = query
                        upExploreData(query)
                    },
                    searchPlaceholder = stringResource(R.string.screen_find),
                    actions = {
                        IconButton(onClick = { startActivity<BookSourceActivity>() }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_settings),
                                contentDescription = stringResource(R.string.book_source_manage),
                                modifier = Modifier.size(22.dp),
                            )
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
                                val selectedGroup = selectedExploreGroup(searchQueryState, groupsState)
                                DropdownMenuItem(
                                    text = { Text(text = stringResource(R.string.all_source)) },
                                    trailingIcon = {
                                        Checkbox(
                                            checked = isExploreAllQuery(searchQueryState),
                                            onCheckedChange = null,
                                        )
                                    },
                                    onClick = {
                                        groupMenuExpanded = false
                                        searchQueryState = ""
                                        upExploreData("")
                                    },
                                )
                                groupsState.forEach { group ->
                                    DropdownMenuItem(
                                        text = { Text(text = group) },
                                        trailingIcon = {
                                            Checkbox(
                                                checked = group == selectedGroup,
                                                onCheckedChange = null,
                                            )
                                        },
                                        onClick = {
                                            groupMenuExpanded = false
                                            val query = "$GROUP_QUERY_PREFIX$group"
                                            searchQueryState = query
                                            upExploreData(query)
                                        },
                                    )
                                }
                            }
                        }
                    },
                )
            },
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    if (sourcesState.isEmpty() && searchQueryState.isEmpty()) {
                        Text(
                            text = stringResource(R.string.explore_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(16.dp),
                        )
                    } else {
                        LazyColumn(
                            state = listState,
                            contentPadding = PaddingValues(horizontal = hPaddingDp, vertical = 6.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            itemsIndexed(
                                items = sourcesState,
                                key = { _, item -> item.bookSourceUrl },
                            ) { index, item ->
                                val isExpanded = expandedSourceUrlState == item.bookSourceUrl
                                val isLoading = loadingSourceUrlState == item.bookSourceUrl
                                var itemMenuExpanded by remember { mutableStateOf(false) }

                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 6.dp),
                                ) {
                                    Box {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .combinedClickable(
                                                    onClick = { toggleSourceExpanded(item, index) },
                                                    onLongClick = { itemMenuExpanded = true },
                                                )
                                                .padding(vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(
                                                text = item.bookSourceName,
                                                style = MaterialTheme.typography.bodyLarge,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f),
                                            )
                                            if (isLoading) {
                                                CircularProgressIndicator(
                                                    modifier = Modifier
                                                        .padding(end = 8.dp)
                                                        .size(16.dp),
                                                    strokeWidth = 2.dp,
                                                )
                                            }
                                            Icon(
                                                painter = painterResource(
                                                    if (isExpanded) R.drawable.ic_arrow_down else R.drawable.ic_arrow_right,
                                                ),
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp),
                                            )
                                        }

                                        DropdownMenu(
                                            expanded = itemMenuExpanded,
                                            onDismissRequest = { itemMenuExpanded = false },
                                        ) {
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.edit)) },
                                                onClick = {
                                                    itemMenuExpanded = false
                                                    editSource(item.bookSourceUrl)
                                                },
                                            )
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.to_top)) },
                                                onClick = {
                                                    itemMenuExpanded = false
                                                    toTop(item)
                                                },
                                            )
                                            if (item.hasLoginUrl) {
                                                DropdownMenuItem(
                                                    text = { Text(stringResource(R.string.login)) },
                                                    onClick = {
                                                        itemMenuExpanded = false
                                                        context.startActivity<SourceLoginActivity> {
                                                            putExtra("type", "bookSource")
                                                            putExtra("key", item.bookSourceUrl)
                                                        }
                                                    },
                                                )
                                            }
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.search)) },
                                                onClick = {
                                                    itemMenuExpanded = false
                                                    searchBook(item)
                                                },
                                            )
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.refresh)) },
                                                onClick = {
                                                    itemMenuExpanded = false
                                                    refreshExplore(item, index)
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
                                                    itemMenuExpanded = false
                                                    deleteSource(item)
                                                },
                                            )
                                        }
                                    }

                                    if (isExpanded) {
                                        val kinds = sourceKindsMap[item.bookSourceUrl].orEmpty()
                                        if (kinds.isNotEmpty()) {
                                            ExploreKindsSection(
                                                item = item,
                                                kinds = kinds,
                                                index = index,
                                            )
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
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun ExploreKindsSection(
        item: BookSourcePart,
        kinds: List<ExploreKind>,
        index: Int,
    ) {
        val context = LocalContext.current
        val sourceUrl = item.bookSourceUrl
        val infoMap = remember(sourceUrl) {
            exploreInfoMapList[sourceUrl] ?: InfoMap(sourceUrl).also {
                exploreInfoMapList.put(sourceUrl, it)
            }
        }
        val sourceUiCallback = remember(sourceUrl, index) {
            object : SourceLoginJsExtensions.Callback {
                override fun upUiData(data: Map<String, Any?>?) = Unit
                override fun reUiView(deltaUp: Boolean) {
                    refreshExplore(item, index)
                }
            }
        }

        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            kinds.forEachIndexed { kindIdx, kind ->
                val style = remember(kind) { kind.style() }
                val itemModifier = when {
                    style.layout_flexBasisPercent in 0.01f..1f -> {
                        Modifier.fillMaxWidth(style.layout_flexBasisPercent)
                    }
                    style.layout_flexGrow > 0f -> {
                        Modifier.weight(style.layout_flexGrow, fill = true)
                    }
                    else -> Modifier
                }

                var resolvedLabel by remember(kind.title, kind.viewName) {
                    val vn = kind.viewName
                    val initial = if (vn != null && vn.length in 3..19 && vn.first() == '\'' && vn.last() == '\'') {
                        vn.substring(1, vn.length - 1)
                    } else {
                        kind.title
                    }
                    mutableStateOf(initial)
                }

                LaunchedEffect(sourceUrl, kindIdx, kind.viewName) {
                    val vn = kind.viewName
                    if (vn != null && !(vn.length in 3..19 && vn.first() == '\'' && vn.last() == '\'')) {
                        val result = withContext(IO) {
                            val source = appDb.bookSourceDao.getBookSource(sourceUrl)
                            evalUiJs(vn, source, infoMap)
                        }
                        resolvedLabel = if (result.isNullOrEmpty()) "null" else result
                    }
                }

                when (kind.type) {
                    Type.url -> {
                        Surface(
                            modifier = itemModifier.clickable {
                                val url = kind.url?.takeIf { it.isNotBlank() } ?: return@clickable
                                if (kind.title.startsWith("ERROR:")) {
                                    (context as? AppCompatActivity)?.showDialogFragment(
                                        TextDialog("ERROR", url),
                                    )
                                } else {
                                    openExplore(sourceUrl, kind.title, url)
                                }
                            },
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ) {
                            Text(
                                text = resolvedLabel,
                                style = MaterialTheme.typography.bodySmall,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            )
                        }
                    }

                    Type.button -> {
                        Surface(
                            modifier = itemModifier.clickable {
                                val action = kind.action?.takeIf { it.isNotBlank() } ?: return@clickable
                                scope.launch(IO) {
                                    val source = appDb.bookSourceDao.getBookSource(sourceUrl)
                                    val jsExt = SourceLoginJsExtensions(
                                        context as? AppCompatActivity,
                                        source,
                                        callback = sourceUiCallback,
                                    )
                                    evalButtonClick(action, source, infoMap, kind.title, jsExt)
                                }
                            },
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                        ) {
                            Text(
                                text = resolvedLabel,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            )
                        }
                    }

                    Type.text -> {
                        var textVal by remember(sourceUrl, kind.title) {
                            mutableStateOf(infoMap[kind.title].orEmpty())
                        }
                        var debounceJob by remember { mutableStateOf<Job?>(null) }
                        OutlinedTextField(
                            value = textVal,
                            onValueChange = { newText ->
                                textVal = newText
                                infoMap[kind.title] = newText
                                val action = kind.action
                                if (!action.isNullOrBlank()) {
                                    debounceJob?.cancel()
                                    debounceJob = scope.launch(IO) {
                                        delay(600)
                                        val source = appDb.bookSourceDao.getBookSource(sourceUrl)
                                        val jsExt = SourceLoginJsExtensions(
                                            context as? AppCompatActivity,
                                            source,
                                            callback = sourceUiCallback,
                                        )
                                        evalButtonClick(action, source, infoMap, kind.title, jsExt)
                                    }
                                }
                            },
                            label = { Text(resolvedLabel, maxLines = 1) },
                            singleLine = true,
                            modifier = itemModifier,
                        )
                    }

                    Type.toggle -> {
                        val chars = remember(kind.chars) {
                            kind.chars?.filterNotNull()?.takeIf { it.isNotEmpty() }
                                ?: listOf("chars", "is null")
                        }
                        var currentChar by remember(sourceUrl, kind.title) {
                            val existing = infoMap[kind.title]
                            val init = if (existing.isNullOrEmpty()) {
                                (kind.default ?: chars.first()).also { infoMap[kind.title] = it }
                            } else {
                                existing
                            }
                            mutableStateOf(init)
                        }
                        val left = style.layout_justifySelf != "right"
                        Surface(
                            modifier = itemModifier.clickable {
                                val nextIndex = (chars.indexOf(currentChar) + 1) % chars.size
                                val nextChar = chars.getOrElse(nextIndex) { "" }
                                currentChar = nextChar
                                infoMap[kind.title] = nextChar
                                val action = kind.action?.takeIf { it.isNotBlank() } ?: return@clickable
                                scope.launch(IO) {
                                    val source = appDb.bookSourceDao.getBookSource(sourceUrl)
                                    val jsExt = SourceLoginJsExtensions(
                                        context as? AppCompatActivity,
                                        source,
                                        callback = sourceUiCallback,
                                    )
                                    evalButtonClick(action, source, infoMap, kind.title, jsExt)
                                }
                            },
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ) {
                            Text(
                                text = if (left) currentChar + resolvedLabel else resolvedLabel + currentChar,
                                style = MaterialTheme.typography.bodySmall,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            )
                        }
                    }

                    Type.select -> {
                        val chars = remember(kind.chars) {
                            kind.chars?.filterNotNull()?.takeIf { it.isNotEmpty() }
                                ?: listOf("chars", "is null")
                        }
                        var selectedChar by remember(sourceUrl, kind.title) {
                            val existing = infoMap[kind.title]
                            val init = if (existing.isNullOrEmpty()) {
                                (kind.default ?: chars.first()).also { infoMap[kind.title] = it }
                            } else {
                                existing
                            }
                            mutableStateOf(init)
                        }
                        var selectExpanded by remember { mutableStateOf(false) }
                        Box(modifier = itemModifier) {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectExpanded = true },
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = "$resolvedLabel: $selectedChar",
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            DropdownMenu(
                                expanded = selectExpanded,
                                onDismissRequest = { selectExpanded = false },
                            ) {
                                chars.forEach { option ->
                                    DropdownMenuItem(
                                        text = { Text(option) },
                                        onClick = {
                                            selectExpanded = false
                                            selectedChar = option
                                            infoMap[kind.title] = option
                                            val action = kind.action?.takeIf { it.isNotBlank() }
                                            if (action != null) {
                                                scope.launch(IO) {
                                                    val source = appDb.bookSourceDao.getBookSource(sourceUrl)
                                                    val jsExt = SourceLoginJsExtensions(
                                                        context as? AppCompatActivity,
                                                        source,
                                                        callback = sourceUiCallback,
                                                    )
                                                    evalButtonClick(action, source, infoMap, kind.title, jsExt)
                                                }
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun toggleSourceExpanded(item: BookSourcePart, position: Int) {
        if (expandedSourceUrlState == item.bookSourceUrl) {
            expandedSourceUrlState = null
            return
        }
        expandedSourceUrlState = item.bookSourceUrl
        val loadVersion = ++kindLoadVersion
        if (sourceKindsMap.containsKey(item.bookSourceUrl)) {
            scrollTo(position)
            return
        }
        loadingSourceUrlState = item.bookSourceUrl
        Coroutine.async(scope) {
            item.exploreKinds()
        }.onSuccess { kindList ->
            val currentSourceUrl = sourcesState.getOrNull(position)?.bookSourceUrl
            if (isExploreBindingCurrent(
                    expectedLoadVersion = loadVersion,
                    currentLoadVersion = kindLoadVersion,
                    expandedPosition = position,
                    bindingPosition = position,
                    boundSourceUrl = item.bookSourceUrl,
                    currentSourceUrl = currentSourceUrl,
                )
            ) {
                sourceKindsMap[item.bookSourceUrl] = kindList
                scrollTo(position)
            }
        }.onFinally {
            if (loadingSourceUrlState == item.bookSourceUrl) {
                loadingSourceUrlState = null
            }
        }
    }

    private fun refreshExplore(source: BookSourcePart, position: Int) {
        loadingSourceUrlState = source.bookSourceUrl
        Coroutine.async(scope) {
            source.clearExploreKindsCache()
            source.exploreKinds()
        }.onSuccess { kindList ->
            sourceKindsMap[source.bookSourceUrl] = kindList
        }.onFinally {
            if (loadingSourceUrlState == source.bookSourceUrl) {
                loadingSourceUrlState = null
            }
        }
    }

    private suspend fun evalUiJs(jsStr: String, source: BookSource?, infoMap: InfoMap): String? {
        val src = source ?: return null
        return try {
            runScriptWithContext {
                src.evalJS(jsStr) {
                    put("infoMap", infoMap)
                }.toString()
            }
        } catch (e: Exception) {
            AppLog.put(src.getTag() + " exploreUi err:" + (e.localizedMessage ?: e.toString()), e)
            null
        }
    }

    private suspend fun evalButtonClick(
        jsStr: String,
        source: BaseSource?,
        infoMap: InfoMap,
        name: String,
        java: SourceLoginJsExtensions,
    ) {
        val src = source ?: return
        try {
            runScriptWithContext {
                src.evalJS(jsStr) {
                    put("java", java)
                    put("infoMap", infoMap)
                }
            }
        } catch (e: Exception) {
            AppLog.put("ExploreUI Button $name JavaScript error", e)
        }
    }

    private fun initGroupData() {
        viewLifecycleOwner.lifecycleScope.launch {
            appDb.bookSourceDao.flowExploreGroups()
                .flowWithLifecycleAndDatabaseChange(
                    viewLifecycleOwner.lifecycle,
                    Lifecycle.State.RESUMED,
                    AppDatabase.BOOK_SOURCE_TABLE_NAME
                )
                .conflate()
                .distinctUntilChanged()
                .collect {
                    groups.clear()
                    groups.addAll(it)
                    groupsState = groups.toSet()
                    val requestedGroup = exploreGroupFromQuery(searchQueryState)
                    if (requestedGroup != null && requestedGroup !in groups) {
                        searchQueryState = ""
                        upExploreData("")
                    }
                    delay(500)
                }
        }
    }

    private fun upExploreData(searchKey: String? = null) {
        exploreFlowJob?.cancel()
        exploreFlowJob = viewLifecycleOwner.lifecycleScope.launch {
            val selectedGroup = exploreGroupFromQuery(searchKey)
            when {
                searchKey.isNullOrBlank() -> {
                    appDb.bookSourceDao.flowExplore()
                }

                selectedGroup != null -> {
                    appDb.bookSourceDao.flowGroupExplore(selectedGroup)
                }

                else -> {
                    appDb.bookSourceDao.flowExplore(searchKey)
                }
            }.flowWithLifecycleAndDatabaseChange(
                viewLifecycleOwner.lifecycle,
                Lifecycle.State.RESUMED,
                AppDatabase.BOOK_SOURCE_TABLE_NAME
            ).catch {
                AppLog.put("发现界面更新数据出错", it)
            }.conflate().flowOn(IO).collect {
                sourcesState = it
                delay(500)
            }
        }
    }

    override fun onPause() {
        sourceKindsMap.clear()
        saveInfoMapJob?.cancel()
        saveInfoMapJob = scope.launch {
            exploreInfoMapList.snapshot().filter { (_, infoMap) -> infoMap.needSave }.map { (_, infoMap) ->
                launch {
                    infoMap.saveNow()
                }
            }.joinAll()
        }
        super.onPause()
    }

    fun scrollTo(pos: Int) {
        viewLifecycleOwner.lifecycleScope.launch {
            listState.scrollToItem(pos)
        }
    }

    fun openExplore(sourceUrl: String, title: String, exploreUrl: String?) {
        if (exploreUrl.isNullOrBlank()) return
        startActivity<ExploreShowActivity> {
            putExtra("exploreName", title)
            putExtra("sourceUrl", sourceUrl)
            putExtra("exploreUrl", exploreUrl)
        }
    }

    fun editSource(sourceUrl: String) {
        startActivity<BookSourceEditActivity> {
            putExtra("sourceUrl", sourceUrl)
        }
    }

    fun toTop(source: BookSourcePart) {
        viewModel.topSource(source)
    }

    fun deleteSource(source: BookSourcePart) {
        alert(R.string.draw) {
            setMessage(getString(R.string.sure_del) + "\n" + source.bookSourceName)
            noButton()
            yesButton {
                viewModel.deleteSource(source)
            }
        }
    }

    fun searchBook(bookSource: BookSourcePart) {
        SearchActivity.start(requireContext(), bookSource)
    }

    fun compressExplore() {
        if (expandedSourceUrlState != null) {
            expandedSourceUrlState = null
        } else {
            viewLifecycleOwner.lifecycleScope.launch {
                if (AppConfig.isEInkMode) {
                    listState.scrollToItem(0)
                } else {
                    listState.animateScrollToItem(0)
                }
            }
        }
    }
}
