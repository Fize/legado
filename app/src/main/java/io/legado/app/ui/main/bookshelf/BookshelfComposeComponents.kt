package io.legado.app.ui.main.bookshelf

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.readProgress
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.BookshelfReadProgressMode
import io.legado.app.ui.compose.component.LegadoBookCover
import io.legado.app.ui.compose.component.LegadoGroupCover
import io.legado.app.utils.toTimeAgo
import kotlin.math.roundToInt

enum class BookshelfMenuAction {
    SEARCH,
    FIND_BOOKS,
    UPDATE_TOC,
    ADD_LOCAL,
    ADD_REMOTE,
    ADD_URL,
    BOOKSHELF_MANAGE,
    CACHE_EXPORT,
    GROUP_MANAGE,
    BOOKSHELF_LAYOUT,
    EXPORT_BOOKSHELF,
    IMPORT_BOOKSHELF,
    LOG,
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun BookshelfTopBar(
    title: String,
    onMenuAction: (BookshelfMenuAction) -> Unit,
    onBackClick: (() -> Unit)? = null,
    bookGroups: List<BookGroup>? = null,
    selectedTabIndex: Int = 0,
    onTabSelected: ((Int) -> Unit)? = null,
    onTabReselected: ((Int) -> Unit)? = null,
    onTabLongClick: ((BookGroup) -> Unit)? = null,
) {
    var overflowExpanded by remember { mutableStateOf(false) }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
                navigationIcon = {
                    if (onBackClick != null) {
                        IconButton(onClick = onBackClick) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.back),
                            )
                        }
                    }
                },
                title = {
                    if (bookGroups != null && bookGroups.isNotEmpty() && onTabSelected != null) {
                        val safeSelected = selectedTabIndex.coerceIn(0, bookGroups.lastIndex)
                        LazyRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            itemsIndexed(
                                items = bookGroups,
                                key = { _, group -> group.groupId },
                            ) { index, group ->
                                val isSelected = index == safeSelected
                                Surface(
                                    color = if (isSelected) {
                                        MaterialTheme.colorScheme.secondaryContainer
                                    } else {
                                        Color.Transparent
                                    },
                                    contentColor = if (isSelected) {
                                        MaterialTheme.colorScheme.onSecondaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    shape = RoundedCornerShape(20.dp),
                                    modifier = Modifier.combinedClickable(
                                        onClick = {
                                            if (isSelected) {
                                                onTabReselected?.invoke(index)
                                            } else {
                                                onTabSelected(index)
                                            }
                                        },
                                        onLongClick = {
                                            onTabLongClick?.invoke(group)
                                        },
                                    ),
                                ) {
                                    Text(
                                        text = group.groupName,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                    )
                                }
                            }
                        }
                    } else {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { onMenuAction(BookshelfMenuAction.SEARCH) }) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = stringResource(R.string.search),
                        )
                    }
                    IconButton(onClick = { onMenuAction(BookshelfMenuAction.ADD_LOCAL) }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_add),
                            contentDescription = stringResource(R.string.book_local),
                        )
                    }
                    Box {
                        IconButton(onClick = { overflowExpanded = true }) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = stringResource(R.string.more_menu),
                            )
                        }
                        DropdownMenu(
                            expanded = overflowExpanded,
                            onDismissRequest = { overflowExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.update_toc)) },
                                leadingIcon = {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_refresh_black_24dp),
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp),
                                    )
                                },
                                onClick = {
                                    overflowExpanded = false
                                    onMenuAction(BookshelfMenuAction.UPDATE_TOC)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.book_local)) },
                                leadingIcon = {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_add),
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp),
                                    )
                                },
                                onClick = {
                                    overflowExpanded = false
                                    onMenuAction(BookshelfMenuAction.ADD_LOCAL)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.add_remote_book)) },
                                leadingIcon = {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_add),
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp),
                                    )
                                },
                                onClick = {
                                    overflowExpanded = false
                                    onMenuAction(BookshelfMenuAction.ADD_REMOTE)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.add_url)) },
                                leadingIcon = {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_add_online),
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp),
                                    )
                                },
                                onClick = {
                                    overflowExpanded = false
                                    onMenuAction(BookshelfMenuAction.ADD_URL)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.bookshelf_management)) },
                                leadingIcon = {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_arrange),
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp),
                                    )
                                },
                                onClick = {
                                    overflowExpanded = false
                                    onMenuAction(BookshelfMenuAction.BOOKSHELF_MANAGE)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.cache_export)) },
                                leadingIcon = {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_download_line),
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp),
                                    )
                                },
                                onClick = {
                                    overflowExpanded = false
                                    onMenuAction(BookshelfMenuAction.CACHE_EXPORT)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.group_manage)) },
                                leadingIcon = {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_groups),
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp),
                                    )
                                },
                                onClick = {
                                    overflowExpanded = false
                                    onMenuAction(BookshelfMenuAction.GROUP_MANAGE)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.bookshelf_layout)) },
                                leadingIcon = {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_view_quilt),
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp),
                                    )
                                },
                                onClick = {
                                    overflowExpanded = false
                                    onMenuAction(BookshelfMenuAction.BOOKSHELF_LAYOUT)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.export_bookshelf)) },
                                leadingIcon = {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_export),
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp),
                                    )
                                },
                                onClick = {
                                    overflowExpanded = false
                                    onMenuAction(BookshelfMenuAction.EXPORT_BOOKSHELF)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.import_bookshelf)) },
                                leadingIcon = {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_import),
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp),
                                    )
                                },
                                onClick = {
                                    overflowExpanded = false
                                    onMenuAction(BookshelfMenuAction.IMPORT_BOOKSHELF)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.log)) },
                                leadingIcon = {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_cfg_about),
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp),
                                    )
                                },
                                onClick = {
                                    overflowExpanded = false
                                    onMenuAction(BookshelfMenuAction.LOG)
                                },
                            )
                        }
                    }
                },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BookshelfHeaderSection(
    showStats: Boolean,
    statsText: String,
    continueBook: Book?,
    onContinueClick: (Book) -> Unit,
    onContinueLongClick: (Book) -> Unit,
) {
    if (!showStats && continueBook == null) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (showStats && statsText.isNotEmpty()) {
            Text(
                text = statsText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (continueBook != null) {
            val chapterTitle = continueBook.durChapterTitle
                ?.takeIf { it.isNotBlank() }
                ?: stringResource(R.string.read_not_started)
            val percent = ((continueBook.readProgress() ?: 0f) * 100).roundToInt().coerceIn(0, 100)

            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("bookshelf_continue_reading")
                    .combinedClickable(
                        onClick = { onContinueClick(continueBook) },
                        onLongClick = { onContinueLongClick(continueBook) },
                    ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 58.dp)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LegadoBookCover(
                        path = continueBook.getDisplayCover(),
                        name = continueBook.name,
                        author = continueBook.author,
                        loadOnlyWifi = false,
                        sourceOrigin = continueBook.getCoverSourceOrigin(),
                        modifier = Modifier.size(width = 30.dp, height = 46.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.continue_read), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary, maxLines = 1)
                        Text(continueBook.name, style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("$chapterTitle · $percent%", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun UnreadBadgeOrLoading(
    isUpdating: Boolean,
    showUnread: Boolean,
    unreadCount: Int,
    highlighted: Boolean,
    modifier: Modifier = Modifier,
) {
    if (isUpdating) {
        CircularProgressIndicator(
            modifier = modifier.size(20.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary,
        )
    } else if (showUnread && unreadCount > 0) {
        val bgColor = if (highlighted) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        }
        val contentColor = if (highlighted) {
            MaterialTheme.colorScheme.onError
        } else {
            MaterialTheme.colorScheme.onSecondaryContainer
        }
        Surface(
            color = bgColor,
            shape = RoundedCornerShape(8.dp),
            modifier = modifier,
        ) {
            Text(
                text = unreadCount.toString(),
                color = contentColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BookshelfBookListItem(
    book: Book,
    compact: Boolean,
    isUpdating: Boolean,
    showUnread: Boolean,
    showLastUpdateTime: Boolean,
    timeAgoVersion: Int,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val progressFraction = remember(book.durChapterIndex, book.durChapterPos, book.totalChapterNum) {
        book.readProgress()?.takeIf { AppConfig.showBookshelfReadProgress }
    }
    val progressPercent = remember(progressFraction) {
        progressFraction?.let { (it * 100).roundToInt() }
    }
    val progressThickness = remember {
        BookshelfReadProgressMode.thicknessDp(AppConfig.bookshelfReadProgressMode).dp
    }
    val timeAgoText = remember(book.latestChapterTime, showLastUpdateTime, book.isLocal, timeAgoVersion) {
        if (showLastUpdateTime && !book.isLocal) {
            book.latestChapterTime.toTimeAgo()
        } else {
            ""
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LegadoBookCover(
            path = book.getDisplayCover(),
            name = book.name,
            author = book.author,
            loadOnlyWifi = false,
            sourceOrigin = book.getCoverSourceOrigin(),
            modifier = if (compact) {
                Modifier.size(width = 48.dp, height = 64.dp)
            } else {
                Modifier.size(width = 66.dp, height = 90.dp)
            },
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = book.name,
                    fontSize = 16.sp,
                    fontWeight = if (compact) FontWeight.Normal else FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(8.dp))
                UnreadBadgeOrLoading(
                    isUpdating = !book.isLocal && isUpdating,
                    showUnread = showUnread,
                    unreadCount = book.getUnreadChapterNum(),
                    highlighted = book.lastCheckCount > 0,
                )
            }

            if (compact) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_author),
                        contentDescription = stringResource(R.string.author),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = book.author,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                    Text(
                        text = " • ",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = book.durChapterTitle.orEmpty(),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (timeAgoText.isNotEmpty()) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = timeAgoText,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_author),
                        contentDescription = stringResource(R.string.author),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = book.author,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (timeAgoText.isNotEmpty()) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = timeAgoText,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_history),
                        contentDescription = stringResource(R.string.read_dur_progress),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = book.durChapterTitle.orEmpty(),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_book_last),
                    contentDescription = stringResource(R.string.lasted_show),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = book.latestChapterTitle.orEmpty(),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (progressPercent != null) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "$progressPercent%",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }

            if (progressFraction != null) {
                LinearProgressIndicator(
                    progress = { progressFraction.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(progressThickness),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BookshelfBookGridItem(
    book: Book,
    showBooknameMode: Int,
    isUpdating: Boolean,
    showUnread: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val progressFraction = remember(book.durChapterIndex, book.durChapterPos, book.totalChapterNum) {
        book.readProgress()?.takeIf { AppConfig.showBookshelfReadProgress }
    }
    val progressThickness = remember {
        BookshelfReadProgressMode.thicknessDp(AppConfig.bookshelfReadProgressMode).dp
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(4.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f),
        ) {
            LegadoBookCover(
                path = book.getDisplayCover(),
                name = book.name,
                author = book.author,
                loadOnlyWifi = false,
                sourceOrigin = book.getCoverSourceOrigin(),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(4.dp),
            )

            if (showBooknameMode == 2) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(4.dp)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Color(0xBB000000)),
                            )
                        )
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = book.name,
                        fontSize = 11.sp,
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            UnreadBadgeOrLoading(
                isUpdating = !book.isLocal && isUpdating,
                showUnread = showUnread,
                unreadCount = book.getUnreadChapterNum(),
                highlighted = book.lastCheckCount > 0,
                modifier = Modifier.align(Alignment.TopEnd),
            )

            if (progressFraction != null) {
                LinearProgressIndicator(
                    progress = { progressFraction.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp)
                        .height(progressThickness),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                )
            }
        }

        if (showBooknameMode == 0) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = book.name,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                minLines = 2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BookshelfGroupListItem(
    item: BookshelfGroupItem,
    compact: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LegadoGroupCover(item.group.cover, item.previewBooks,
            modifier = if (compact) {
                Modifier.size(width = 48.dp, height = 64.dp)
            } else {
                Modifier.size(width = 66.dp, height = 90.dp)
            },
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = item.group.groupName,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BookshelfGroupGridItem(
    item: BookshelfGroupItem,
    showBooknameMode: Int,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(4.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f),
        ) {
            LegadoGroupCover(item.group.cover, item.previewBooks,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(4.dp),
            )

            if (showBooknameMode == 2 && item.group.groupName.isNotBlank()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(4.dp)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Color(0xBB000000)),
                            )
                        )
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = item.group.groupName,
                        fontSize = 11.sp,
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        if (showBooknameMode == 0) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = item.group.groupName,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                minLines = 2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

data class BookshelfConfigState(
    val groupStyle: Int,
    val readProgressMode: Int,
    val showUnread: Boolean,
    val showLastUpdateTime: Boolean,
    val showWaitUpBooks: Boolean,
    val showFastScroller: Boolean,
    val showRecentReading: Boolean,
    val showBookshelfStats: Boolean,
    val bookshelfLayout: Int,
    val bookshelfSort: Int,
    val showBookname: Int,
    val margin: Int,
)

@Composable
fun BookshelfConfigDialog(
    initialState: BookshelfConfigState,
    onDismiss: () -> Unit,
    onConfirm: (BookshelfConfigState) -> Unit,
) {
    val groupStyles = stringArrayResource(R.array.group_style)
    val readProgressModes = stringArrayResource(R.array.bookshelf_read_progress)

    var groupStyle by remember {
        mutableIntStateOf(initialState.groupStyle.coerceIn(0, (groupStyles.size - 1).coerceAtLeast(0)))
    }
    var readProgressMode by remember {
        mutableIntStateOf(initialState.readProgressMode.coerceIn(0, (readProgressModes.size - 1).coerceAtLeast(0)))
    }
    var showUnread by remember { mutableStateOf(initialState.showUnread) }
    var showLastUpdateTime by remember { mutableStateOf(initialState.showLastUpdateTime) }
    var showWaitUpBooks by remember { mutableStateOf(initialState.showWaitUpBooks) }
    var showFastScroller by remember { mutableStateOf(initialState.showFastScroller) }
    var showRecentReading by remember { mutableStateOf(initialState.showRecentReading) }
    var showBookshelfStats by remember { mutableStateOf(initialState.showBookshelfStats) }
    var bookshelfLayout by remember { mutableIntStateOf(initialState.bookshelfLayout.coerceIn(0, 6)) }
    var bookshelfSort by remember { mutableIntStateOf(initialState.bookshelfSort.coerceIn(0, 5)) }
    var showBookname by remember { mutableIntStateOf(initialState.showBookname.coerceIn(0, 2)) }
    var margin by remember { mutableIntStateOf(initialState.margin.coerceIn(0, 60)) }

    var groupStyleExpanded by remember { mutableStateOf(false) }
    var readProgressExpanded by remember { mutableStateOf(false) }

    val layoutOptions = listOf(
        stringResource(R.string.layout_list),
        stringResource(R.string.layout_list_compact),
        stringResource(R.string.layout_grid2),
        stringResource(R.string.layout_grid3),
        stringResource(R.string.layout_grid4),
        stringResource(R.string.layout_grid5),
        stringResource(R.string.layout_grid6),
    )
    val sortOptions = listOf(
        stringResource(R.string.bookshelf_px_0),
        stringResource(R.string.bookshelf_px_1),
        stringResource(R.string.bookshelf_px_2),
        stringResource(R.string.bookshelf_px_3),
        stringResource(R.string.bookshelf_px_4),
        stringResource(R.string.bookshelf_px_5),
    )
    val bookNameOptions = listOf(
        stringResource(R.string.show),
        stringResource(R.string.hide),
        stringResource(R.string.overlay),
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.widthIn(max = 560.dp),
        title = { Text(stringResource(R.string.bookshelf_layout)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(stringResource(R.string.group_style), modifier = Modifier.weight(1f))
                    Box {
                        OutlinedButton(onClick = { groupStyleExpanded = true }) {
                            Text(groupStyles.getOrElse(groupStyle) { "" })
                        }
                        DropdownMenu(
                            expanded = groupStyleExpanded,
                            onDismissRequest = { groupStyleExpanded = false },
                        ) {
                            groupStyles.forEachIndexed { idx, label ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = {
                                        groupStyle = idx
                                        groupStyleExpanded = false
                                    },
                                )
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(stringResource(R.string.read_progress), modifier = Modifier.weight(1f))
                    Box {
                        OutlinedButton(onClick = { readProgressExpanded = true }) {
                            Text(readProgressModes.getOrElse(readProgressMode) { "" })
                        }
                        DropdownMenu(
                            expanded = readProgressExpanded,
                            onDismissRequest = { readProgressExpanded = false },
                        ) {
                            readProgressModes.forEachIndexed { idx, label ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = {
                                        readProgressMode = idx
                                        readProgressExpanded = false
                                    },
                                )
                            }
                        }
                    }
                }

                SwitchRow(
                    label = stringResource(R.string.show_unread),
                    checked = showUnread,
                    onCheckedChange = { showUnread = it },
                )
                SwitchRow(
                    label = stringResource(R.string.show_last_update_time),
                    checked = showLastUpdateTime,
                    onCheckedChange = { showLastUpdateTime = it },
                )
                SwitchRow(
                    label = stringResource(R.string.show_wait_up_count),
                    checked = showWaitUpBooks,
                    onCheckedChange = { showWaitUpBooks = it },
                )
                SwitchRow(
                    label = stringResource(R.string.show_bookshelf_fast_scroller),
                    checked = showFastScroller,
                    onCheckedChange = { showFastScroller = it },
                )
                SwitchRow(
                    label = stringResource(R.string.recent_reading),
                    checked = showRecentReading,
                    onCheckedChange = { showRecentReading = it },
                )
                SwitchRow(
                    label = stringResource(R.string.bookshelf_statistics),
                    checked = showBookshelfStats,
                    onCheckedChange = { showBookshelfStats = it },
                )

                HorizontalDivider()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.view),
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium,
                        )
                        layoutOptions.forEachIndexed { idx, label ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { bookshelfLayout = idx },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = bookshelfLayout == idx,
                                    onClick = { bookshelfLayout = idx },
                                )
                                Text(label, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.sort),
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium,
                        )
                        sortOptions.forEachIndexed { idx, label ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { bookshelfSort = idx },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = bookshelfSort == idx,
                                    onClick = { bookshelfSort = idx },
                                )
                                Text(label, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }

                if (bookshelfLayout > 1) {
                    HorizontalDivider()
                    Text(
                        text = stringResource(R.string.book_name),
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium,
                    )
                    Row(modifier = Modifier.fillMaxWidth()) {
                        bookNameOptions.forEachIndexed { idx, label ->
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { showBookname = idx },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = showBookname == idx,
                                    onClick = { showBookname = idx },
                                )
                                Text(label, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }

                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(stringResource(R.string.margin))
                    Text(margin.toString())
                }
                Slider(
                    value = margin.toFloat(),
                    onValueChange = { margin = it.roundToInt().coerceIn(0, 60) },
                    valueRange = 0f..60f,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm(
                    BookshelfConfigState(
                        groupStyle = groupStyle,
                        readProgressMode = readProgressMode,
                        showUnread = showUnread,
                        showLastUpdateTime = showLastUpdateTime,
                        showWaitUpBooks = showWaitUpBooks,
                        showFastScroller = showFastScroller,
                        showRecentReading = showRecentReading,
                        showBookshelfStats = showBookshelfStats,
                        bookshelfLayout = bookshelfLayout,
                        bookshelfSort = bookshelfSort,
                        showBookname = showBookname,
                        margin = margin,
                    )
                )
                onDismiss()
            }) {
                Text(stringResource(R.string.ok))
            }
        },
    )
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}
