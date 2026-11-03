package io.legado.app.ui.about

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import io.legado.app.R
import io.legado.app.data.entities.ReadRecordShow
import io.legado.app.help.glide.ImageLoader
import io.legado.app.ui.compose.component.LegadoImageState
import io.legado.app.ui.compose.component.LegadoTopAppBar
import io.legado.app.ui.compose.component.rememberLegadoImageState
import java.text.SimpleDateFormat

internal fun formatDuring(mss: Long, useDays: Boolean = false, showSeconds: Boolean = true): String {
    val totalHours = mss / (1000 * 60 * 60)
    val days = if (useDays) totalHours / 24 else 0
    val hours = if (useDays) totalHours % 24 else totalHours
    val minutes = mss % (1000 * 60 * 60) / (1000 * 60)
    val seconds = mss % (1000 * 60) / 1000
    return buildString {
        if (days > 0) append("${days}天")
        if (hours > 0) append("${hours}小时")
        if (minutes > 0) append("${minutes}分钟")
        if (showSeconds && seconds > 0) append("${seconds}秒")
    }.ifBlank { if (showSeconds) "0秒" else "0分钟" }
}

@Composable
fun ReadRecordScreen(
    onBack: () -> Unit,
    onOpenDestination: (ReadRecordDestination) -> Unit,
    viewModel: ReadRecordViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val lifecycleOwner = LocalLifecycleOwner.current
    var sortExpanded by remember { mutableStateOf(false) }
    var moreExpanded by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(state.destination) {
        state.destination?.let {
            onOpenDestination(it)
            viewModel.consumeDestination()
        }
    }
    LaunchedEffect(state.error) {
        state.error?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeError()
        }
    }

    Scaffold(
        topBar = {
            LegadoTopAppBar(
                title = stringResource(R.string.read_record),
                onBack = onBack,
                searchQuery = state.query,
                onSearchQueryChange = viewModel::setQuery,
                actions = {
                    Box {
                        IconButton(onClick = { sortExpanded = true }) {
                            Icon(painterResource(R.drawable.ic_baseline_sort_24), stringResource(R.string.sort))
                        }
                        DropdownMenu(sortExpanded, { sortExpanded = false }) {
                            listOf(
                                0 to stringResource(R.string.sort_by_name),
                                1 to stringResource(R.string.reading_time_sort),
                                2 to stringResource(R.string.last_read_time_sort),
                            ).forEach { (mode, title) ->
                                DropdownMenuItem(
                                    text = { Text(title) },
                                    leadingIcon = { RadioButton(state.sortMode == mode, null) },
                                    onClick = {
                                        sortExpanded = false
                                        viewModel.setSortMode(mode)
                                    },
                                )
                            }
                        }
                    }
                    Box {
                        IconButton(onClick = { moreExpanded = true }) {
                            Icon(painterResource(R.drawable.ic_more), stringResource(R.string.more_menu))
                        }
                        DropdownMenu(moreExpanded, { moreExpanded = false }) {
                            RecordToggleItem(R.string.enable_record, state.enabled, viewModel::setEnabled)
                            RecordToggleItem(R.string.read_record_simple_layout, state.simpleLayout, viewModel::setSimpleLayout)
                            RecordToggleItem(R.string.read_record_use_days, state.useDays, viewModel::setUseDays)
                            RecordToggleItem(R.string.read_record_show_seconds, state.showSeconds, viewModel::setShowSeconds)
                            RecordToggleItem(R.string.read_record_fixed_card, state.fixedCard, viewModel::setFixedCard)
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.read_record_clear)) },
                                onClick = {
                                    moreExpanded = false
                                    viewModel.requestClear()
                                },
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { innerPadding ->
        Column(Modifier.fillMaxSize().padding(innerPadding)) {
            if (state.simpleLayout || state.fixedCard) {
                ReadRecordSummary(
                    records = state.allRecords,
                    useDays = state.useDays,
                    showSeconds = state.showSeconds,
                    compact = state.simpleLayout,
                    onClear = viewModel::requestClear,
                )
            }
            when {
                state.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                state.records.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text(stringResource(R.string.read_record_empty))
                }
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 16.dp),
                ) {
                    if (!state.simpleLayout && !state.fixedCard) {
                        item(key = "read_record_summary") {
                            ReadRecordSummary(
                                records = state.allRecords,
                                useDays = state.useDays,
                                showSeconds = state.showSeconds,
                                compact = false,
                                onClear = viewModel::requestClear,
                            )
                        }
                    }
                    items(state.records, key = { "${it.bookName}::${it.author}" }) { record ->
                        if (state.simpleLayout) {
                            ReadRecordCompactRow(
                                item = record,
                                useDays = state.useDays,
                                showSeconds = state.showSeconds,
                                onClick = { viewModel.open(record) },
                                onRemove = { viewModel.requestDelete(record) },
                            )
                        } else {
                            ReadRecordEnhancedRow(
                                item = record,
                                chapterTitle = state.booksByIdentity[record.bookName to record.author]
                                    ?.durChapterTitle?.takeIf(String::isNotBlank)
                                    ?: record.lastChapterTitle?.takeIf(String::isNotBlank)
                                    ?: stringResource(R.string.read_record_no_chapter),
                                cover = state.booksByIdentity[record.bookName to record.author]
                                    ?.getDisplayCover() ?: record.coverUrl,
                                useDays = state.useDays,
                                showSeconds = state.showSeconds,
                                onClick = { viewModel.open(record) },
                                onRemove = { viewModel.requestDelete(record) },
                            )
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
    ReadRecordDialogs(state, viewModel)
}

@Composable
private fun RecordToggleItem(label: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    DropdownMenuItem(
        text = { Text(stringResource(label)) },
        trailingIcon = { Checkbox(checked, null) },
        onClick = { onChange(!checked) },
    )
}

@Composable
private fun ReadRecordSummary(
    records: List<ReadRecordShow>,
    useDays: Boolean,
    showSeconds: Boolean,
    compact: Boolean,
    onClear: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.read_record_book_count, records.size), fontWeight = FontWeight.Bold)
            Text(
                stringResource(R.string.read_record_total_duration, formatDuring(records.sumOf { it.readTime }, useDays, showSeconds)),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (compact) TextButton(onClick = onClear) { Text(stringResource(R.string.clear)) }
    }
}

@Composable
private fun ReadRecordCompactRow(
    item: ReadRecordShow,
    useDays: Boolean,
    showSeconds: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    val author = item.displayAuthor.ifBlank { stringResource(R.string.read_record_no_author) }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(item.bookName, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(stringResource(R.string.author_show, author), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                stringResource(R.string.reading_time_tag) + " " + formatDuring(item.readTime, useDays, showSeconds),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onRemove) { Text(stringResource(R.string.clear)) }
    }
}

@Composable
private fun ReadRecordEnhancedRow(
    item: ReadRecordShow,
    chapterTitle: String,
    cover: String?,
    useDays: Boolean,
    showSeconds: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    val author = item.displayAuthor.ifBlank { stringResource(R.string.read_record_no_author) }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        ReadRecordCoverImage(cover, item.bookName)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.bookName, fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.weight(1f))
                IconButton(onClick = onRemove, modifier = Modifier.size(36.dp)) {
                    Icon(painterResource(R.drawable.ic_outline_delete), stringResource(R.string.delete))
                }
            }
            Text(author, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            Text(chapterTitle, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            Text(
                formatDuring(item.readTime, useDays, showSeconds),
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.End,
                modifier = Modifier.fillMaxWidth(),
            )
            if (item.lastRead > 0) {
                Text(
                    SimpleDateFormat("yyyy-MM-dd", LocalConfiguration.current.locales[0]).format(item.lastRead),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun ReadRecordCoverImage(path: String?, contentDescription: String) {
    val imageState by rememberLegadoImageState(path) { context -> ImageLoader.load(context, path) }
    Box(Modifier.size(width = 52.dp, height = 72.dp), contentAlignment = Alignment.Center) {
        when (val state = imageState) {
            is LegadoImageState.Success -> Image(
                state.bitmap,
                contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            LegadoImageState.Loading -> CircularProgressIndicator(Modifier.size(20.dp))
            LegadoImageState.Error -> Surface(
                modifier = Modifier.fillMaxSize(),
                shape = RoundedCornerShape(4.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {}
        }
    }
}

@Composable
private fun ReadRecordDialogs(state: ReadRecordUiState, viewModel: ReadRecordViewModel) {
    if (state.confirmClear) {
        ConfirmDialog(stringResource(R.string.sure_del), viewModel::clear, viewModel::dismissDialogs)
    }
    state.deleteTarget?.let { record ->
        val author = record.displayAuthor.ifBlank { stringResource(R.string.read_record_no_author) }
        AlertDialog(
            onDismissRequest = viewModel::dismissDialogs,
            title = { Text(stringResource(R.string.delete)) },
            text = { Text(stringResource(R.string.sure_del_any, "${record.bookName} · $author")) },
            confirmButton = { TextButton({ viewModel.delete(record) }) { Text(stringResource(R.string.delete)) } },
            dismissButton = {
                if (record.hasCombinedAuthors && viewModel.authors(record).size > 1) {
                    TextButton({ viewModel.showAuthorPicker(record) }) {
                        Text(stringResource(R.string.read_record_remove_author))
                    }
                }
                TextButton(viewModel::dismissDialogs) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
    state.authorPickerTarget?.let { record ->
        AlertDialog(
            onDismissRequest = viewModel::dismissDialogs,
            title = { Text(stringResource(R.string.read_record_remove_author)) },
            text = {
                Column {
                    viewModel.authors(record).forEach { author ->
                        Text(
                            author,
                            modifier = Modifier.fillMaxWidth().clickable {
                                viewModel.requestAuthorRemoval(record, author)
                            }.padding(12.dp),
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(viewModel::dismissDialogs) { Text(stringResource(R.string.cancel)) } },
        )
    }
    state.authorRemoval?.let { (record, author) ->
        ConfirmDialog(
            stringResource(R.string.read_record_remove_author_confirm, author),
            { viewModel.removeAuthor(record, author) },
            viewModel::dismissDialogs,
        )
    }
}

@Composable
private fun ConfirmDialog(message: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delete)) },
        text = { Text(message) },
        confirmButton = { TextButton(onConfirm) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
