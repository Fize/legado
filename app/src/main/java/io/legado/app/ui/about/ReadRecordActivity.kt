package io.legado.app.ui.about

import android.os.Bundle
import android.widget.ImageView
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.bumptech.glide.request.RequestOptions
import io.legado.app.R
import io.legado.app.base.BaseComposeActivity
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.ReadRecordAuthors
import io.legado.app.data.entities.ReadRecordShow
import io.legado.app.help.book.ReadRecordCoverCache
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.glide.ImageLoader
import io.legado.app.help.glide.OkHttpModelLoader
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.ui.book.search.SearchActivity
import io.legado.app.ui.compose.component.LegadoTopAppBar
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.cnCompare
import io.legado.app.utils.dpToPx
import io.legado.app.utils.getCompatDrawable
import io.legado.app.utils.getInt
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putInt
import io.legado.app.utils.startActivityForBook
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale

internal fun formatDuring(mss: Long, useDays: Boolean = false, showSeconds: Boolean = true): String {
    val totalHours = mss / (1000 * 60 * 60)
    val days = if (useDays) totalHours / 24 else 0
    val hours = if (useDays) totalHours % 24 else totalHours
    val minutes = mss % (1000 * 60 * 60) / (1000 * 60)
    val seconds = mss % (1000 * 60) / 1000
    val h = if (hours > 0) "${hours}小时" else ""
    val d = if (days > 0) "${days}天" else ""
    val m = if (minutes > 0) "${minutes}分钟" else ""
    val s = if (showSeconds && seconds > 0) "${seconds}秒" else ""
    return "$d$h$m$s".ifBlank { if (showSeconds) "0秒" else "0分钟" }
}

class ReadRecordActivity : BaseComposeActivity() {

    private var dataJob: Job? = null
    private var booksByIdentityState by mutableStateOf<Map<Pair<String, String>, Book>>(emptyMap())
    private var allRecordsState by mutableStateOf<List<ReadRecordShow>>(emptyList())
    private var readRecordsState by mutableStateOf<List<ReadRecordShow>>(emptyList())
    private var searchQueryState by mutableStateOf("")

    private var sortModeState by mutableIntStateOf(LocalConfig.getInt("readRecordSort"))
    private var enableRecordState by mutableStateOf(AppConfig.enableReadRecord)
    private var simpleLayoutState by mutableStateOf(AppConfig.readRecordSimpleLayout)
    private var useDaysState by mutableStateOf(AppConfig.readRecordUseDays)
    private var showSecondsState by mutableStateOf(AppConfig.readRecordShowSeconds)
    private var fixedCardState by mutableStateOf(AppConfig.readRecordFixedCard)

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        initData()
    }

    override fun onResume() {
        super.onResume()
        initData(searchQueryState)
    }

    @Composable
    override fun Content() {
        var sortMenuExpanded by remember { mutableStateOf(false) }
        var moreMenuExpanded by remember { mutableStateOf(false) }

        Scaffold(
            topBar = {
                LegadoTopAppBar(
                    title = stringResource(R.string.read_record),
                    onBack = { finish() },
                    searchQuery = searchQueryState,
                    onSearchQueryChange = { query ->
                        searchQueryState = query
                        initData(query)
                    },
                    actions = {
                        Box {
                            IconButton(onClick = { sortMenuExpanded = true }) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_baseline_sort_24),
                                    contentDescription = stringResource(R.string.sort),
                                )
                            }
                            DropdownMenu(
                                expanded = sortMenuExpanded,
                                onDismissRequest = { sortMenuExpanded = false },
                            ) {
                                val sortOptions = listOf(
                                    0 to stringResource(R.string.sort_by_name),
                                    1 to stringResource(R.string.reading_time_sort),
                                    2 to stringResource(R.string.last_read_time_sort),
                                )
                                sortOptions.forEach { (mode, title) ->
                                    DropdownMenuItem(
                                        text = { Text(title) },
                                        leadingIcon = {
                                            RadioButton(
                                                selected = sortModeState == mode,
                                                onClick = null,
                                            )
                                        },
                                        onClick = {
                                            sortMenuExpanded = false
                                            sortModeState = mode
                                            LocalConfig.putInt("readRecordSort", mode)
                                            initData(searchQueryState)
                                        },
                                    )
                                }
                            }
                        }

                        Box {
                            IconButton(onClick = { moreMenuExpanded = true }) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_more),
                                    contentDescription = stringResource(R.string.more_menu),
                                )
                            }
                            DropdownMenu(
                                expanded = moreMenuExpanded,
                                onDismissRequest = { moreMenuExpanded = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.enable_record)) },
                                    trailingIcon = {
                                        Checkbox(
                                            checked = enableRecordState,
                                            onCheckedChange = null,
                                        )
                                    },
                                    onClick = {
                                        val next = !enableRecordState
                                        enableRecordState = next
                                        AppConfig.enableReadRecord = next
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.read_record_simple_layout)) },
                                    trailingIcon = {
                                        Checkbox(
                                            checked = simpleLayoutState,
                                            onCheckedChange = null,
                                        )
                                    },
                                    onClick = {
                                        val next = !simpleLayoutState
                                        simpleLayoutState = next
                                        AppConfig.readRecordSimpleLayout = next
                                        initData(searchQueryState)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.read_record_use_days)) },
                                    trailingIcon = {
                                        Checkbox(
                                            checked = useDaysState,
                                            onCheckedChange = null,
                                        )
                                    },
                                    onClick = {
                                        val next = !useDaysState
                                        useDaysState = next
                                        AppConfig.readRecordUseDays = next
                                        initData(searchQueryState)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.read_record_show_seconds)) },
                                    trailingIcon = {
                                        Checkbox(
                                            checked = showSecondsState,
                                            onCheckedChange = null,
                                        )
                                    },
                                    onClick = {
                                        val next = !showSecondsState
                                        showSecondsState = next
                                        AppConfig.readRecordShowSeconds = next
                                        initData(searchQueryState)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.read_record_fixed_card)) },
                                    trailingIcon = {
                                        Checkbox(
                                            checked = fixedCardState,
                                            onCheckedChange = null,
                                        )
                                    },
                                    onClick = {
                                        val next = !fixedCardState
                                        fixedCardState = next
                                        AppConfig.readRecordFixedCard = next
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.read_record_clear)) },
                                    onClick = {
                                        moreMenuExpanded = false
                                        clearRecords()
                                    },
                                )
                            }
                        }
                    },
                )
            },
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .windowInsetsPadding(WindowInsets.navigationBars),
            ) {
                if (simpleLayoutState) {
                    CompactSummaryHeader(
                        totalReadTime = formatDuring(
                            allRecordsState.sumOf { it.readTime },
                            useDaysState,
                            showSecondsState,
                        ),
                        onClear = ::clearRecords,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                } else if (fixedCardState) {
                    EnhancedSummaryCard(
                        records = allRecordsState,
                        useDays = useDaysState,
                        showSeconds = showSecondsState,
                    )
                }

                if (readRecordsState.isEmpty()) {
                    Text(
                        text = stringResource(R.string.read_record_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                    )
                }

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 16.dp),
                ) {
                    if (!simpleLayoutState && !fixedCardState) {
                        item(key = "enhanced_summary_header") {
                            EnhancedSummaryCard(
                                records = allRecordsState,
                                useDays = useDaysState,
                                showSeconds = showSecondsState,
                            )
                        }
                    }

                    items(
                        items = readRecordsState,
                        key = { "${it.bookName}::${it.author}" },
                    ) { item ->
                        if (simpleLayoutState) {
                            ReadRecordCompactRow(
                                item = item,
                                useDays = useDaysState,
                                showSeconds = showSecondsState,
                                onClick = { openRecordBook(item) },
                                onRemove = { sureDelAlert(item) },
                            )
                        } else {
                            ReadRecordEnhancedRow(
                                item = item,
                                chapterTitle = booksByIdentityState[item.bookName to item.author]?.durChapterTitle
                                    ?.takeIf { it.isNotBlank() }
                                    ?: item.lastChapterTitle?.takeIf { it.isNotBlank() }
                                    ?: stringResource(R.string.read_record_no_chapter),
                                useDays = useDaysState,
                                showSeconds = showSecondsState,
                                onClick = { openRecordBook(item) },
                                onRemove = { sureDelAlert(item) },
                            )
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                    }
                }
            }
        }
    }

    @Composable
    private fun CompactSummaryHeader(
        totalReadTime: String,
        onClear: () -> Unit,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.all_read_time),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = totalReadTime,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            TextButton(onClick = onClear) {
                Text(stringResource(R.string.clear))
            }
        }
    }

    @Composable
    private fun EnhancedSummaryCard(
        records: List<ReadRecordShow>,
        useDays: Boolean,
        showSeconds: Boolean,
    ) {
        val bg = backgroundColor
        val cardColor = Color(
            if (ColorUtils.isColorLight(bg)) bg
            else ColorUtils.blendColors(bg, android.graphics.Color.WHITE, 0.08f)
        )
        val countStr = records.size.toString()
        val label = stringResource(R.string.read_record_book_count, records.size)
        val accent = Color(accentColor)
        val annotatedCount = remember(label, countStr, accent) {
            buildAnnotatedString {
                append(label)
                val start = label.indexOf(countStr)
                if (start >= 0) {
                    addStyle(
                        SpanStyle(
                            color = accent,
                            fontSize = 25.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                        start,
                        start + countStr.length,
                    )
                }
            }
        }
        val top = remember(records) { records.sortedByDescending { it.readTime }.take(3) }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = cardColor),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.read_record_achievement),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = annotatedCount,
                        fontSize = 18.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(
                            R.string.read_record_total_duration,
                            formatDuring(records.sumOf { it.readTime }, useDays, showSeconds),
                        ),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Box(
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .size(width = 112.dp, height = 108.dp),
                ) {
                    top.getOrNull(2)?.let { record ->
                        ReadRecordCoverImage(
                            record = record,
                            width = 48.dp,
                            height = 64.dp,
                            modifier = Modifier
                                .offset(x = 4.dp, y = 30.dp)
                                .rotate(-10f),
                        )
                    }
                    top.getOrNull(1)?.let { record ->
                        ReadRecordCoverImage(
                            record = record,
                            width = 54.dp,
                            height = 72.dp,
                            modifier = Modifier
                                .offset(x = 28.dp, y = 20.dp)
                                .rotate(-3f),
                        )
                    }
                    top.getOrNull(0)?.let { record ->
                        ReadRecordCoverImage(
                            record = record,
                            width = 60.dp,
                            height = 80.dp,
                            modifier = Modifier
                                .offset(x = 46.dp, y = 8.dp)
                                .rotate(8f),
                        )
                    }
                }
            }
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
        val author = if (item.hasCombinedAuthors) {
            stringResource(R.string.read_record_legacy_authors, item.displayAuthor)
        } else {
            item.displayAuthor.ifBlank { stringResource(R.string.read_record_no_author) }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.bookName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.author_show, author),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.reading_time_tag),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = formatDuring(item.readTime, useDays, showSeconds),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.last_read_time_tag),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (item.lastRead > 0) dateFormat.format(item.lastRead) else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
            TextButton(onClick = onRemove) {
                Text(stringResource(R.string.clear))
            }
        }
    }

    @Composable
    private fun ReadRecordEnhancedRow(
        item: ReadRecordShow,
        chapterTitle: String,
        useDays: Boolean,
        showSeconds: Boolean,
        onClick: () -> Unit,
        onRemove: () -> Unit,
    ) {
        val author = if (item.hasCombinedAuthors) {
            stringResource(R.string.read_record_legacy_authors, item.displayAuthor)
        } else {
            item.displayAuthor.ifBlank { stringResource(R.string.read_record_no_author) }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            ReadRecordCoverImage(
                record = item,
                width = 48.dp,
                height = 64.dp,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        text = item.bookName,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = onRemove,
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_outline_delete),
                            contentDescription = stringResource(R.string.delete),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = author,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = chapterTitle,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = formatDuring(item.readTime, useDays, showSeconds),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.End,
                        )
                        if (item.lastRead > 0) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = dateFormat.format(item.lastRead),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.End,
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun ReadRecordCoverImage(
        record: ReadRecordShow,
        width: Dp,
        height: Dp,
        modifier: Modifier = Modifier,
    ) {
        val booksMap = booksByIdentityState
        AndroidView(
            modifier = modifier.size(width = width, height = height),
            factory = { ctx ->
                ImageView(ctx).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                }
            },
            update = { imageView ->
                loadCover(imageView, record, booksMap)
            },
        )
    }

    private fun initData(searchKey: String? = searchQueryState) {
        dataJob?.cancel()
        val currentSortMode = sortModeState
        dataJob = lifecycleScope.launch {
            val (allRecords, readRecords, books) = withContext(IO) {
                val bookshelf = appDb.bookDao.all.sortedBy { it.durChapterTime }
                    .associateBy { it.name to it.author }
                val all = appDb.readRecordDao.allShow
                val filtered = if (searchKey.isNullOrBlank()) all
                else appDb.readRecordDao.search(searchKey)
                val sorted = filtered.let { records ->
                    when (currentSortMode) {
                        1 -> records.sortedByDescending { it.readTime }
                        2 -> records.sortedByDescending { it.lastRead }
                        else -> records.sortedWith { o1, o2 ->
                            o1.bookName.cnCompare(o2.bookName).takeIf { it != 0 }
                                ?: o1.displayAuthor.cnCompare(o2.displayAuthor)
                        }
                    }
                }
                Triple(all, sorted, bookshelf)
            }
            booksByIdentityState = books
            allRecordsState = allRecords
            readRecordsState = readRecords
        }
    }

    private fun openRecordBook(item: ReadRecordShow) {
        lifecycleScope.launch {
            val book = withContext(IO) {
                appDb.bookDao.findByName(item.bookName)
                    .filter { it.author == item.author }
                    .maxByOrNull { it.durChapterTime }
            }
            if (book == null) {
                SearchActivity.start(this@ReadRecordActivity, item.bookName)
            } else {
                startActivityForBook(book)
            }
        }
    }

    private fun loadCover(
        image: ImageView,
        record: ReadRecordShow,
        booksByIdentity: Map<Pair<String, String>, Book>,
    ) {
        val placeholder = getCompatDrawable(R.drawable.read_record_cover_placeholder)
        val fallback = getPrefString(
            if (AppConfig.isNightTheme) PreferKey.readRecordCoverDark else PreferKey.readRecordCover
        )
        val fallbackRequest = ImageLoader.load(this, fallback)
            .error(placeholder)
            .transform(CenterCrop(), RoundedCorners(4.dpToPx()))
        val book = booksByIdentity[record.bookName to record.author]
        val cover = book?.getDisplayCover()?.takeIf { it.isNotBlank() } ?: record.coverUrl
        var options = RequestOptions().set(
            OkHttpModelLoader.loadOnlyWifiOption,
            AppConfig.loadCoverOnlyWifi,
        )
        book?.getCoverSourceOrigin()?.let {
            options = options.set(OkHttpModelLoader.sourceOriginOption, it)
        }
        image.contentDescription = record.bookName
        ImageLoader.load(this, cover)
            .apply(options)
            .placeholder(placeholder)
            .error(
                ImageLoader.load(this, record.coverUrl)
                    .apply(options)
                    .error(fallbackRequest)
                    .transform(CenterCrop(), RoundedCorners(4.dpToPx()))
            )
            .transform(CenterCrop(), RoundedCorners(4.dpToPx()))
            .into(image)
    }

    private fun clearRecords() {
        alert(R.string.delete, R.string.sure_del) {
            yesButton {
                lifecycleScope.launch {
                    withContext(IO) {
                        appDb.readRecordDao.clear()
                        ReadRecordCoverCache.prune()
                    }
                    initData(searchQueryState)
                }
            }
            noButton()
        }
    }

    private fun sureDelAlert(item: ReadRecordShow) {
        alert(R.string.delete) {
            val author = item.displayAuthor.ifBlank { getString(R.string.read_record_no_author) }
            setMessage(getString(R.string.sure_del_any, "${item.bookName} · $author"))
            yesButton {
                lifecycleScope.launch {
                    withContext(IO) {
                        appDb.readRecordDao.deleteByBook(item.bookName, item.author)
                        ReadRecordCoverCache.prune()
                    }
                    initData(searchQueryState)
                }
            }
            noButton()
            if (item.hasCombinedAuthors && ReadRecordAuthors.decode(item.author).size > 1) {
                neutralButton(R.string.read_record_remove_author) { removeAuthorAlert(item) }
            }
        }
    }

    private fun removeAuthorAlert(item: ReadRecordShow) {
        val authors = ReadRecordAuthors.decode(item.author).toList()
        alert(R.string.read_record_remove_author) {
            items(authors) { _, index ->
                val removedAuthor = authors[index]
                alert(R.string.read_record_remove_author) {
                    setMessage(getString(R.string.read_record_remove_author_confirm, removedAuthor))
                    yesButton {
                        lifecycleScope.launch {
                            withContext(IO) {
                                appDb.readRecordDao.removeLegacyAuthor(item.bookName, item.author, removedAuthor)
                                ReadRecordCoverCache.prune()
                            }
                            initData(searchQueryState)
                        }
                    }
                    noButton()
                }
            }
            noButton()
        }
    }

}
