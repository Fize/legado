package io.legado.app.ui.main.explore

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import io.legado.app.R
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.data.entities.rule.ExploreKind
import io.legado.app.data.entities.rule.ExploreKind.Type
import io.legado.app.ui.compose.component.LegadoTopAppBar
import io.legado.app.ui.login.SourceLoginJsExtensions
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ExploreRootScreen(
    onSearchBooks: () -> Unit,
    onManageSources: () -> Unit,
    onEditSource: (String) -> Unit,
    onLoginSource: (String) -> Unit,
    onSearchSource: (BookSourcePart) -> Unit,
    onOpenExplore: (sourceUrl: String, title: String, exploreUrl: String) -> Unit,
    exploreViewModel: ExploreViewModel = viewModel(),
) {
    val uiState by exploreViewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    var groupMenuExpanded by remember { mutableStateOf(false) }
    var itemMenuSource by remember { mutableStateOf<BookSourcePart?>(null) }
    var deleteSource by remember { mutableStateOf<BookSourcePart?>(null) }
    var sourceErrorDetails by remember { mutableStateOf<String?>(null) }

    DisposableEffect(lifecycleOwner, exploreViewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) exploreViewModel.saveNeededInfoMaps()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            LegadoTopAppBar(
                title = stringResource(R.string.discovery),
                searchQuery = uiState.searchQuery,
                onSearchQueryChange = exploreViewModel::setSearchQuery,
                searchPlaceholder = stringResource(R.string.screen_find),
                actions = {
                    TextButton(onClick = onSearchBooks, modifier = Modifier.testTag("explore-search-books")) {
                        Text(stringResource(R.string.find_books_online), maxLines = 1)
                    }
                    IconButton(onClick = onManageSources) {
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
                            val selectedGroup = selectedExploreGroup(uiState.searchQuery, uiState.groups)
                            ExploreGroupItem(
                                title = stringResource(R.string.all_source),
                                selected = isExploreAllQuery(uiState.searchQuery),
                                onClick = {
                                    groupMenuExpanded = false
                                    exploreViewModel.setSearchQuery("")
                                },
                            )
                            uiState.groups.forEach { group ->
                                ExploreGroupItem(
                                    title = group,
                                    selected = group == selectedGroup,
                                    onClick = {
                                        groupMenuExpanded = false
                                        exploreViewModel.setSearchQuery("$GROUP_QUERY_PREFIX$group")
                                    },
                                )
                            }
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        if (uiState.sources.isEmpty() && uiState.searchQuery.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.explore_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(16.dp),
                )
            }
        } else {
            Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding).testTag("explore_content"),
                contentAlignment = Alignment.TopCenter,
            ) {
                LazyColumn(
                    modifier = Modifier.widthIn(max = 720.dp).fillMaxSize().testTag("explore_sources"),
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 6.dp),
                ) {
                    items(uiState.sources, key = { it.bookSourceUrl }) { source ->
                        val expanded = source.bookSourceUrl == uiState.expandedSourceUrl
                        Surface(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
                          Column {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .combinedClickable(
                                        onClick = { exploreViewModel.toggleSource(source) },
                                        onLongClick = { itemMenuSource = source },
                                    )
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = source.bookSourceName,
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                if (source.bookSourceUrl == uiState.loadingSourceUrl) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.padding(end = 8.dp).size(16.dp),
                                        strokeWidth = 2.dp,
                                    )
                                }
                                Icon(
                                    painter = painterResource(
                                        if (expanded) R.drawable.ic_arrow_down else R.drawable.ic_arrow_right,
                                    ),
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                            if (expanded) {
                                val kinds = uiState.sourceKinds[source.bookSourceUrl].orEmpty()
                                if (source.bookSourceUrl in uiState.failedSourceErrors) {
                                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("explore-source-error:${source.bookSourceUrl}"), verticalAlignment = Alignment.CenterVertically) {
                                        Text(stringResource(R.string.load_error_retry), modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                                        TextButton(onClick = { sourceErrorDetails = uiState.failedSourceErrors[source.bookSourceUrl] }, modifier = Modifier.testTag("explore-source-details:${source.bookSourceUrl}")) { Text(stringResource(R.string.explore_error_details)) }
                                        TextButton(onClick = { exploreViewModel.refreshSource(source) }, modifier = Modifier.testTag("explore-source-retry:${source.bookSourceUrl}")) { Text(stringResource(R.string.retry)) }
                                    }
                                }
                                if (kinds.isNotEmpty()) {
                                    ExploreKindsSection(
                                        source = source,
                                        kinds = kinds,
                                        exploreViewModel = exploreViewModel,
                                        onOpenExplore = onOpenExplore,
                                        onRefresh = { exploreViewModel.refreshSource(source) },
                                    )
                                }
                            }
                          }
                        }
                    }
                }
            }
        }
    }

    sourceErrorDetails?.let { details ->
        AlertDialog(onDismissRequest = { sourceErrorDetails = null }, title = { Text(stringResource(R.string.error)) },
            text = { Text(details, Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = { sourceErrorDetails = null }, modifier = Modifier.testTag("explore-error-details-close")) { Text(stringResource(R.string.ok)) } })
    }

    val menuSource = itemMenuSource
    DropdownMenu(
        expanded = menuSource != null,
        onDismissRequest = { itemMenuSource = null },
    ) {
        if (menuSource != null) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.edit)) },
                onClick = { itemMenuSource = null; onEditSource(menuSource.bookSourceUrl) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.to_top)) },
                onClick = { itemMenuSource = null; exploreViewModel.topSource(menuSource) },
            )
            if (menuSource.hasLoginUrl) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.login)) },
                    onClick = { itemMenuSource = null; onLoginSource(menuSource.bookSourceUrl) },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.search)) },
                onClick = { itemMenuSource = null; onSearchSource(menuSource) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.refresh)) },
                onClick = { itemMenuSource = null; exploreViewModel.refreshSource(menuSource) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) },
                onClick = { itemMenuSource = null; deleteSource = menuSource },
            )
        }
    }

    deleteSource?.let { source ->
        AlertDialog(
            onDismissRequest = { deleteSource = null },
            title = { Text(stringResource(R.string.draw)) },
            text = { Text(stringResource(R.string.sure_del) + "\n" + source.bookSourceName) },
            dismissButton = {
                TextButton(onClick = { deleteSource = null }) { Text(stringResource(R.string.cancel)) }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        exploreViewModel.deleteSource(source)
                        deleteSource = null
                    },
                ) { Text(stringResource(R.string.ok)) }
            },
        )
    }
}

@Composable
private fun ExploreGroupItem(title: String, selected: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(title) },
        trailingIcon = { Checkbox(checked = selected, onCheckedChange = null) },
        onClick = onClick,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExploreKindsSection(
    source: BookSourcePart,
    kinds: List<ExploreKind>,
    exploreViewModel: ExploreViewModel,
    onOpenExplore: (String, String, String) -> Unit,
    onRefresh: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sourceUrl = source.bookSourceUrl
    val infoMap = remember(sourceUrl) { exploreViewModel.infoMap(sourceUrl) }
    val sourceUiCallback = remember(sourceUrl) {
        object : SourceLoginJsExtensions.Callback {
            override fun upUiData(data: Map<String, Any?>?) = Unit
            override fun reUiView(deltaUp: Boolean) {
                scope.launch { onRefresh() }
            }
        }
    }
    DisposableEffect(infoMap) {
        onDispose { exploreViewModel.saveInfoMap(infoMap) }
    }

    fun runAction(action: String, name: String) {
        scope.launch(IO) {
            val fullSource = exploreViewModel.source(sourceUrl) ?: return@launch
            val java = SourceLoginJsExtensions(
                activity = context as? AppCompatActivity,
                source = fullSource,
                callback = sourceUiCallback,
            )
            exploreViewModel.runKindAction(sourceUrl, action, infoMap, java, name)
        }
    }

    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        kinds.forEachIndexed { index, kind ->
            val style = remember(kind) { kind.style() }
            val itemModifier = when {
                style.layout_flexBasisPercent in 0.01f..1f ->
                    Modifier.fillMaxWidth(style.layout_flexBasisPercent)
                style.layout_flexGrow > 0f -> Modifier.weight(style.layout_flexGrow, fill = true)
                else -> Modifier
            }
            val initialLabel = kind.viewName?.takeIf {
                it.length in 3..19 && it.first() == '\'' && it.last() == '\''
            }?.substring(1, kind.viewName!!.length - 1) ?: kind.title
            var label by remember(sourceUrl, kind.title, kind.viewName) { mutableStateOf(initialLabel) }
            var errorMessage by remember(sourceUrl, kind.title) { mutableStateOf<String?>(null) }
            LaunchedEffect(sourceUrl, index, kind.viewName) {
                val viewName = kind.viewName
                if (!viewName.isNullOrBlank() && viewName != initialLabel) {
                    label = exploreViewModel.resolveKindLabel(sourceUrl, viewName, infoMap)
                        .orEmpty().ifBlank { "null" }
                }
            }

            when (kind.type) {
                Type.url -> {
                    val url = kind.url.orEmpty()
                    Surface(
                        modifier = itemModifier.clickable {
                            if (url.isNotBlank()) {
                                if (kind.title.startsWith("ERROR:")) errorMessage = url
                                else onOpenExplore(sourceUrl, kind.title, url)
                            }
                        },
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ) {
                        Text(label, style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center, maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                    }
                }
                Type.button -> {
                    Surface(
                        modifier = itemModifier.clickable {
                            kind.action?.takeIf(String::isNotBlank)?.let { runAction(it, kind.title) }
                        },
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Text(label, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            textAlign = TextAlign.Center, maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                    }
                }
                Type.text -> {
                    var textValue by remember(sourceUrl, kind.title) { mutableStateOf(infoMap[kind.title].orEmpty()) }
                    var debounce by remember(sourceUrl, kind.title) { mutableStateOf<Job?>(null) }
                    OutlinedTextField(
                        value = textValue,
                        onValueChange = { value ->
                            textValue = value
                            infoMap[kind.title] = value
                            debounce?.cancel()
                            kind.action?.takeIf(String::isNotBlank)?.let { action ->
                                debounce = scope.launch {
                                    delay(600)
                                    runAction(action, kind.title)
                                }
                            }
                        },
                        label = { Text(label, maxLines = 1) },
                        singleLine = true,
                        modifier = itemModifier,
                    )
                }
                Type.toggle -> {
                    val chars = kind.chars?.filterNotNull()?.takeIf(List<String>::isNotEmpty)
                        ?: listOf("chars", "is null")
                    var current by remember(sourceUrl, kind.title) {
                        mutableStateOf((infoMap[kind.title] ?: kind.default ?: chars.first()).also {
                            infoMap[kind.title] = it
                        })
                    }
                    val text = if (style.layout_justifySelf == "right") label + current else current + label
                    Surface(
                        modifier = itemModifier.clickable {
                            current = chars[(chars.indexOf(current) + 1).mod(chars.size)]
                            infoMap[kind.title] = current
                            kind.action?.takeIf(String::isNotBlank)?.let { runAction(it, kind.title) }
                        },
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ) {
                        Text(text, style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center, maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                    }
                }
                Type.select -> {
                    val chars = kind.chars?.filterNotNull()?.takeIf(List<String>::isNotEmpty)
                        ?: listOf("chars", "is null")
                    var selected by remember(sourceUrl, kind.title) {
                        mutableStateOf((infoMap[kind.title] ?: kind.default ?: chars.first()).also {
                            infoMap[kind.title] = it
                        })
                    }
                    var expanded by remember(sourceUrl, kind.title) { mutableStateOf(false) }
                    Box(modifier = itemModifier) {
                        Surface(
                            modifier = Modifier.fillMaxWidth().clickable { expanded = true },
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ) {
                            Text("$label: $selected", style = MaterialTheme.typography.bodySmall,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                        }
                        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            chars.forEach { option ->
                                DropdownMenuItem(text = { Text(option) }, onClick = {
                                    selected = option
                                    infoMap[kind.title] = option
                                    expanded = false
                                    kind.action?.takeIf(String::isNotBlank)?.let { runAction(it, kind.title) }
                                })
                            }
                        }
                    }
                }
            }

            errorMessage?.let { message ->
                AlertDialog(
                    onDismissRequest = { errorMessage = null },
                    title = { Text("ERROR") },
                    text = { Text(message) },
                    confirmButton = {
                        TextButton(onClick = { errorMessage = null }) {
                            Text(stringResource(R.string.ok))
                        }
                    },
                )
            }
        }
    }
}
