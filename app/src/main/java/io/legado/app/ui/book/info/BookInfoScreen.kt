package io.legado.app.ui.book.info

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isLocalTxt
import io.legado.app.help.book.isWebFile
import io.legado.app.help.book.readProgress
import io.legado.app.ui.compose.component.LegadoBookCover
import io.legado.app.ui.compose.component.LegadoTopAppBar
import androidx.compose.ui.platform.LocalWindowInfo
import kotlin.math.roundToInt

@Composable
fun BookInfoScreen(state: BookInfoUiState, deleteAlertEnabled: Boolean, onAction: (BookInfoUiAction) -> Unit) {
    val currentBook = state.book
    val isLoading = state.loading
    val chapters = state.chapters
    val groupNames = state.groups
    val kinds = state.kinds
    val bookSource = state.source
    val inBookshelf = state.inBookshelf
    val hasCustomButton = state.hasCustomButton
    val mutationBlocked = state.shelfMutation || state.pendingDelete?.status in setOf(
        io.legado.app.data.entities.BookInfoDeleteStatus.Queued, io.legado.app.data.entities.BookInfoDeleteStatus.Running)
    val window = LocalWindowInfo.current.containerSize
    val isLandscape = window.width > window.height
    var showMenu by remember { mutableStateOf(false) }

    var canUpdateChecked by remember(currentBook, showMenu) {
        mutableStateOf(currentBook?.canUpdate ?: true)
    }
    var splitLongChapterChecked by remember(currentBook, showMenu) {
        mutableStateOf(currentBook?.getSplitLongChapter() ?: true)
    }
    var deleteAlertChecked by remember(showMenu) {
        mutableStateOf(deleteAlertEnabled)
    }

    Scaffold(
        topBar = {
            LegadoTopAppBar(
                title = stringResource(R.string.book_info),
                onNavigationClick = { onAction(BookInfoUiAction.Back) },
                actions = {
                    if (hasCustomButton) {
                        TextButton(
                            onClick = { onAction(BookInfoUiAction.Callback(BookInfoCallbackEvent.Custom)) }
                        ) {
                            Text(stringResource(R.string.custom_button))
                        }
                    }
                    if (inBookshelf) {
                        IconButton(
                            onClick = { onAction(BookInfoUiAction.Edit) }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = stringResource(R.string.edit),
                            )
                        }
                    }
                    IconButton(
                        onClick = { onAction(BookInfoUiAction.Callback(BookInfoCallbackEvent.Share)) }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = stringResource(R.string.share),
                        )
                    }
                    IconButton(enabled = !mutationBlocked, onClick = { onAction(BookInfoUiAction.Refresh) }) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.refresh),
                        )
                    }
                    IconButton(onClick = { showMenu = true }) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = stringResource(R.string.more_menu),
                        )
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                    ) {
                        if (bookSource?.hasLogin() == true) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.login)) },
                                onClick = { showMenu = false; onAction(BookInfoUiAction.Login) },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.to_top)) },
                            onClick = {
                                showMenu = false
                                onAction(BookInfoUiAction.Top)
                            },
                        )
                        if (bookSource != null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.set_source_variable)) },
                                onClick = {
                                    showMenu = false
                                    onAction(BookInfoUiAction.SourceVariable)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.set_book_variable)) },
                                onClick = {
                                    showMenu = false
                                    onAction(BookInfoUiAction.BookVariable)
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.copy_book_url)) },
                            onClick = { showMenu = false; onAction(BookInfoUiAction.Callback(BookInfoCallbackEvent.CopyBookUrl)) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.copy_toc_url)) },
                            onClick = { showMenu = false; onAction(BookInfoUiAction.Callback(BookInfoCallbackEvent.CopyTocUrl)) },
                        )
                        if (bookSource != null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.allow_update)) },
                                trailingIcon = {
                                    Checkbox(
                                        checked = canUpdateChecked,
                                        onCheckedChange = null,
                                    )
                                },
                                onClick = {
                                    showMenu = false
                                    val next = !canUpdateChecked
                                    canUpdateChecked = next
                                    onAction(BookInfoUiAction.CanUpdate(next))
                                },
                            )
                        }
                        if (currentBook?.isLocalTxt == true) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.split_long_chapter)) },
                                trailingIcon = {
                                    Checkbox(
                                        checked = splitLongChapterChecked,
                                        onCheckedChange = null,
                                    )
                                },
                                onClick = { onAction(BookInfoUiAction.SplitLongChapter(!splitLongChapterChecked)); showMenu = false },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.clear_cache)) },
                            onClick = { showMenu = false; onAction(BookInfoUiAction.Callback(BookInfoCallbackEvent.ClearCache)) },
                        )
                        if (currentBook?.isLocal == true) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.upload_to_remote)) },
                                onClick = {
                                    showMenu = false
                                    onAction(BookInfoUiAction.Upload)
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.delete_alert)) },
                            trailingIcon = {
                                Checkbox(
                                    checked = deleteAlertChecked,
                                    onCheckedChange = null,
                                )
                            },
                            onClick = {
                                showMenu = false
                                val next = !deleteAlertChecked
                                deleteAlertChecked = next
                                onAction(BookInfoUiAction.DeleteAlert(next))
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.log)) },
                            onClick = {
                                showMenu = false
                                onAction(BookInfoUiAction.Logs)
                            },
                        )
                    }
                },
            )
        },
        bottomBar = {
            Surface(
                tonalElevation = 3.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars),
            ) {
                Column {
                    state.pendingUpload?.let { pending ->
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("book_upload_pending")) {
                            Text(stringResource(if (pending.status == io.legado.app.data.entities.BookInfoUploadStatus.Failed)
                                R.string.book_upload_failed else R.string.book_upload_pending))
                            if (state.uploadCancellationFailed) Text(stringResource(R.string.book_upload_cancel_failed), color = MaterialTheme.colorScheme.error)
                            TextButton(enabled = !state.cancellingUpload, onClick = { onAction(BookInfoUiAction.CancelUpload(pending.id)) },
                                modifier = Modifier.testTag("book_upload_cancel")) { Text(stringResource(R.string.book_upload_cancel)) }
                        }
                    }
                    state.pendingDelete?.let { pending ->
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("book_delete_pending")) {
                            Text(stringResource(if (pending.status == io.legado.app.data.entities.BookInfoDeleteStatus.Failed)
                                R.string.book_delete_failed else R.string.book_delete_pending))
                            if (state.deleteCancellationFailed) Text(stringResource(R.string.book_delete_cancel_failed), color = MaterialTheme.colorScheme.error)
                            if (pending.cancellable) TextButton(enabled = !state.cancellingDelete,
                                onClick = { onAction(BookInfoUiAction.CancelDelete(pending.id)) }, modifier = Modifier.testTag("book_delete_cancel")) {
                                Text(stringResource(R.string.book_delete_cancel))
                            }
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedButton(
                            enabled = currentBook != null && !mutationBlocked,
                            onClick = { onAction(BookInfoUiAction.Shelf) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                text = if (inBookshelf) {
                                    stringResource(R.string.remove_from_bookshelf)
                                } else {
                                    stringResource(R.string.add_to_bookshelf)
                                }
                            )
                        }
                        Button(
                            enabled = currentBook != null && !mutationBlocked,
                            onClick = { onAction(BookInfoUiAction.Read) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(text = stringResource(R.string.reading))
                        }
                    }
                }
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            if (currentBook != null) {
                if (isLandscape) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(0.45f)
                                .fillMaxHeight()
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            BookHeaderSection(state, currentBook, onAction)
                            BookMetadataActionsSection(currentBook, chapters, isLoading, groupNames, onAction)
                        }
                        Column(
                            modifier = Modifier
                                .weight(0.55f)
                                .fillMaxHeight()
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            BookKindsSection(kinds, onAction)
                            BookIntroSection(state, onAction)
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        BookHeaderSection(state, currentBook, onAction)
                        BookKindsSection(kinds, onAction)
                        BookMetadataActionsSection(currentBook, chapters, isLoading, groupNames, onAction)
                        BookIntroSection(state, onAction)
                    }
                }
            }

            if (isLoading) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .align(Alignment.TopCenter),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookHeaderSection(state: BookInfoUiState, book: Book, onAction: (BookInfoUiAction) -> Unit) {
    val coverVer = state.coverRevision
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val coverPath = remember(book.customCoverUrl, book.coverUrl, coverVer) {
            book.getDisplayCover()
        }
        LegadoBookCover(
            path = coverPath,
            name = book.name,
            author = book.author,
            loadOnlyWifi = false,
            sourceOrigin = book.getCoverSourceOrigin(),
            modifier = Modifier
                .width(96.dp)
                .height(134.dp)
                .testTag("book_info_cover")
                .combinedClickable(
                    onClick = { onAction(BookInfoUiAction.Cover) },
                    onLongClick = { onAction(BookInfoUiAction.CoverPhoto) },
                ),
        )
        Spacer(modifier = Modifier.width(14.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = book.name,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.combinedClickable(
                    onClick = { onAction(BookInfoUiAction.Callback(BookInfoCallbackEvent.Name)) },
                    onLongClick = { onAction(BookInfoUiAction.Callback(BookInfoCallbackEvent.LongName)) },
                ),
            )
            Text(
                text = stringResource(R.string.author_show, book.getRealAuthor()),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.combinedClickable(
                    onClick = { onAction(BookInfoUiAction.Callback(BookInfoCallbackEvent.Author)) },
                    onLongClick = { onAction(BookInfoUiAction.Callback(BookInfoCallbackEvent.LongAuthor)) },
                ),
            )
            Text(
                text = stringResource(
                    R.string.lasted_show,
                    if (book.isWebFile) stringResource(R.string.downloading)
                    else book.latestChapterTitle.orEmpty(),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun BookKindsSection(kinds: List<String>, onAction: (BookInfoUiAction) -> Unit) {
    if (kinds.isEmpty()) return
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        kinds.forEach { kind ->
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.combinedClickable(
                    onClick = { onAction(BookInfoUiAction.Callback(BookInfoCallbackEvent.Kind, kind)) },
                    onLongClick = { onAction(BookInfoUiAction.Callback(BookInfoCallbackEvent.LongKind, kind)) },
                ),
            ) {
                Text(
                    text = kind,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun BookMetadataActionsSection(
    book: Book,
    chapters: List<BookChapter>,
    isLoading: Boolean,
    groupNames: List<String>,
    onAction: (BookInfoUiAction) -> Unit,
) {
    val tocSummary = when {
        isLoading -> stringResource(R.string.loading)
        chapters.isEmpty() -> stringResource(R.string.error_load_toc)
        else -> {
            val title = resolveBookInfoTocTitle(
                book.durChapterTitle,
                book.durChapterIndex,
                chapters,
            ) ?: stringResource(R.string.no_last_chapter)
            val readStatus = resolveBookInfoReadProgress(book)?.let { percent ->
                stringResource(R.string.read_y, "$percent%")
            }
            listOfNotNull(title, readStatus).joinToString("  ·  ")
        }
    }
    val tocText = stringResource(R.string.toc_s, tocSummary)
    val groupTitle = groupNames.takeIf { it.isNotEmpty() }?.joinToString(", ")
        ?: stringResource(if (book.isLocal) R.string.local_no_group else R.string.no_group)
    val groupText = stringResource(R.string.group_s, groupTitle)
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        HorizontalDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.origin_show, book.originName),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .clickable { onAction(BookInfoUiAction.EditSource) }
                    .padding(vertical = 6.dp),
            )
            TextButton(
                onClick = { onAction(BookInfoUiAction.ChangeSource) }
            ) {
                Text(stringResource(R.string.change_origin))
            }
        }
        if (!book.isWebFile) {
            HorizontalDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = { onAction(BookInfoUiAction.Toc) })
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = tocText,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 6.dp),
                )
                TextButton(onClick = { onAction(BookInfoUiAction.Toc) }) {
                    Text(stringResource(R.string.view_toc))
                }
            }
        }
        HorizontalDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = groupText,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 6.dp),
            )
            TextButton(
                onClick = { onAction(BookInfoUiAction.Group) }
            ) {
                Text(stringResource(R.string.change_group))
            }
        }
        HorizontalDivider()
    }
}

@Composable
private fun BookIntroSection(state: BookInfoUiState, onAction: (BookInfoUiAction) -> Unit) {
    io.legado.app.ui.book.info.intro.BookIntroContent(
        state = state.intro,
        source = state.source,
        bookUrl = state.book?.bookUrl.orEmpty(),
        onOverflow = { key, collapsible -> onAction(BookInfoUiAction.IntroMeasured(key, collapsible)) },
        onToggle = { onAction(BookInfoUiAction.IntroToggle) },
        onRetry = { onAction(BookInfoUiAction.IntroRetry) },
        onButton = { name, script -> onAction(BookInfoUiAction.IntroButton(state.intro.contentKey, name, script)) },
        onImage = { onAction(BookInfoUiAction.IntroImage(state.intro.contentKey, it)) },
        onLink = { onAction(BookInfoUiAction.IntroLink(state.intro.contentKey, it)) },
    )
}

internal fun resolveBookInfoTocTitle(
    storedTitle: String?,
    currentIndex: Int,
    chapters: List<BookChapter>,
): String? {
    return storedTitle?.takeIf { it.isNotBlank() }
        ?: (chapters.getOrNull(currentIndex) ?: chapters.lastOrNull())
            ?.getDisplayTitle(chineseConvert = false)
            ?.takeIf { it.isNotBlank() }
}

internal fun resolveBookInfoReadProgress(book: Book): Int? {
    if (book.totalChapterNum <= 1) return null
    return book.readProgress()?.let { (it * 100).roundToInt() }
}
