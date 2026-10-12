package io.legado.app.ui.book.changesource

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.SearchBook
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.primaryColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.utils.ColorUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChangeSourceTopBar(
    title: String,
    subtitle: String?,
    showNavigationIcon: Boolean,
    onBack: () -> Unit,
    isSearching: Boolean,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onStartStopClick: () -> Unit,
    onSourceManageClick: () -> Unit,
    showRefreshList: Boolean = false,
    onRefreshListClick: () -> Unit = {},
    showClose: Boolean = false,
    onCloseClick: () -> Unit = {},
    showAutomation: Boolean = false,
    isAutomationActive: Boolean = false,
    onAutomationClick: () -> Unit = {},
    checkAuthor: Boolean,
    onCheckAuthorChange: (Boolean) -> Unit,
    loadInfo: Boolean,
    onLoadInfoChange: (Boolean) -> Unit,
    loadToc: Boolean,
    onLoadTocChange: (Boolean) -> Unit,
    loadWordCount: Boolean,
    onLoadWordCountChange: (Boolean) -> Unit,
    sortRespondTime: Boolean,
    onSortRespondTimeChange: (Boolean) -> Unit,
    wordCountFilterEnabled: Boolean,
    onWordCountFilterClick: () -> Unit,
    groups: List<String>,
    selectedGroup: String,
    onGroupSelect: (String) -> Unit,
) {
    val context = LocalContext.current
    val barBg = Color(context.primaryColor)
    val barContent = Color(context.primaryTextColor)
    var searchExpanded by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    var groupDialogVisible by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        TopAppBar(
            title = {
                if (searchExpanded) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = onSearchQueryChange,
                        placeholder = {
                            Text(
                                text = stringResource(R.string.screen),
                                fontSize = 14.sp
                            )
                        },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(end = 16.dp),
                        trailingIcon = {
                            IconButton(
                                onClick = {
                                    if (searchQuery.isNotEmpty()) {
                                        onSearchQueryChange("")
                                    } else {
                                        searchExpanded = false
                                    }
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = stringResource(R.string.close)
                                )
                            }
                        }
                    )
                } else {
                    Column {
                        Text(
                            text = title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = barContent,
                            fontSize = 18.sp
                        )
                        if (!subtitle.isNullOrBlank()) {
                            Text(
                                text = subtitle,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = barContent.copy(alpha = 0.8f),
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            },
            navigationIcon = {
                if (showNavigationIcon && !searchExpanded) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                            tint = barContent
                        )
                    }
                }
            },
            actions = {
                if (!searchExpanded) {
                    IconButton(onClick = { searchExpanded = true }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_screen),
                            contentDescription = stringResource(R.string.screen),
                            tint = barContent
                        )
                    }
                }
                IconButton(onClick = onStartStopClick) {
                    Icon(
                        painter = painterResource(
                            if (isSearching) R.drawable.ic_stop_black_24dp
                            else R.drawable.ic_refresh_black_24dp
                        ),
                        contentDescription = stringResource(
                            if (isSearching) R.string.stop else R.string.refresh
                        ),
                        tint = barContent
                    )
                }
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = stringResource(R.string.more_menu),
                            tint = barContent
                        )
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.book_source_manage)) },
                            onClick = {
                                menuExpanded = false
                                onSourceManageClick()
                            }
                        )
                        if (showAutomation) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(
                                            if (isAutomationActive) R.string.chapter_source_automation_stop
                                            else R.string.chapter_source_automation
                                        )
                                    )
                                },
                                onClick = {
                                    menuExpanded = false
                                    onAutomationClick()
                                }
                            )
                        }
                        if (showRefreshList) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.refresh_list)) },
                                onClick = {
                                    menuExpanded = false
                                    onRefreshListClick()
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.checkAuthor)) },
                            trailingIcon = {
                                Checkbox(checked = checkAuthor, onCheckedChange = null)
                            },
                            onClick = {
                                onCheckAuthorChange(!checkAuthor)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.load_word_count)) },
                            trailingIcon = {
                                Checkbox(checked = loadWordCount, onCheckedChange = null)
                            },
                            onClick = {
                                onLoadWordCountChange(!loadWordCount)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.change_source_sort_respond_time)) },
                            trailingIcon = {
                                Checkbox(checked = sortRespondTime, onCheckedChange = null)
                            },
                            onClick = {
                                onSortRespondTimeChange(!sortRespondTime)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.change_source_word_count_filter)) },
                            trailingIcon = {
                                Checkbox(checked = wordCountFilterEnabled, onCheckedChange = null)
                            },
                            onClick = {
                                menuExpanded = false
                                onWordCountFilterClick()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.load_info)) },
                            trailingIcon = {
                                Checkbox(checked = loadInfo, onCheckedChange = null)
                            },
                            onClick = {
                                onLoadInfoChange(!loadInfo)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.load_toc)) },
                            trailingIcon = {
                                Checkbox(checked = loadToc, onCheckedChange = null)
                            },
                            onClick = {
                                onLoadTocChange(!loadToc)
                            }
                        )
                        val groupLabel = if (selectedGroup.isNotEmpty() && selectedGroup in groups) {
                            "${stringResource(R.string.group)}($selectedGroup)"
                        } else {
                            stringResource(R.string.group)
                        }
                        DropdownMenuItem(
                            text = { Text(groupLabel) },
                            onClick = {
                                menuExpanded = false
                                groupDialogVisible = true
                            }
                        )
                        if (showClose) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.close)) },
                                onClick = {
                                    menuExpanded = false
                                    onCloseClick()
                                }
                            )
                        }
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = barBg,
                titleContentColor = barContent,
                actionIconContentColor = barContent,
                navigationIconContentColor = barContent
            )
        )
        if (isSearching) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
            )
        } else {
            Spacer(modifier = Modifier.height(2.dp))
        }
    }

    if (groupDialogVisible) {
        val allSourceLabel = stringResource(R.string.all_source)
        val allOptions = remember(groups, allSourceLabel) { listOf("") + groups }
        AlertDialog(
            onDismissRequest = { groupDialogVisible = false },
            title = { Text(stringResource(R.string.group)) },
            text = {
                Column {
                    allOptions.forEach { group ->
                        val isSelected = if (group.isEmpty()) {
                            selectedGroup.isEmpty() || selectedGroup !in groups
                        } else {
                            group == selectedGroup
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    groupDialogVisible = false
                                    onGroupSelect(group)
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = isSelected, onClick = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = if (group.isEmpty()) allSourceLabel else group)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { groupDialogVisible = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ChangeSourceItemRow(
    item: SearchBook,
    isCurrent: Boolean,
    showSelectedHighlight: Boolean,
    score: Int,
    loadWordCount: Boolean,
    onClick: () -> Unit,
    onScoreChange: (Int) -> Unit,
    onTopSource: () -> Unit,
    onBottomSource: () -> Unit,
    onEditSource: () -> Unit,
    onDisableSource: () -> Unit,
    onDeleteSource: () -> Unit,
) {
    val context = LocalContext.current
    val selectedBgColor = remember(context) {
        Color(ColorUtils.withAlpha(context.accentColor, 0.1f))
    }
    var showContextMenu by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val rowBg = if (showSelectedHighlight && isCurrent) selectedBgColor else Color.Transparent

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(rowBg)
            .combinedClickable(
                onClick = onClick,
                onLongClick = { showContextMenu = true }
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Praise / Dislike column (30dp width)
            Column(
                modifier = Modifier
                    .width(30.dp)
                    .fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                if (score >= 0) {
                    Icon(
                        painter = painterResource(R.drawable.ic_praise),
                        contentDescription = stringResource(R.string.like_source),
                        tint = colorResource(
                            if (score > 0) R.color.md_red_A200 else R.color.md_red_100
                        ),
                        modifier = Modifier
                            .size(28.dp)
                            .clickable {
                                onScoreChange(if (score > 0) 0 else 1)
                            }
                            .padding(5.dp)
                    )
                }
                if (score <= 0) {
                    Icon(
                        painter = painterResource(R.drawable.ic_praise),
                        contentDescription = stringResource(R.string.not_like_source),
                        tint = colorResource(
                            if (score < 0) R.color.md_blue_A200 else R.color.md_blue_100
                        ),
                        modifier = Modifier
                            .size(28.dp)
                            .rotate(180f)
                            .clickable {
                                onScoreChange(if (score < 0) 0 else -1)
                            }
                            .padding(5.dp)
                    )
                }
            }

            // Middle content
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = item.originName,
                        color = colorResource(R.color.primaryText),
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (item.author.isNotBlank()) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = item.author,
                            color = colorResource(R.color.secondaryText),
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 160.dp)
                        )
                    }
                }

                val lastChapter = item.getDisplayLastChapterTitle()
                if (lastChapter.isNotBlank()) {
                    Text(
                        text = lastChapter,
                        color = colorResource(R.color.secondaryText),
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (loadWordCount && !item.chapterWordCountText.isNullOrBlank()) {
                    Text(
                        text = item.chapterWordCountText.orEmpty(),
                        color = colorResource(R.color.secondaryText),
                        fontSize = 12.sp
                    )
                }

                if (loadWordCount && item.respondTime >= 0) {
                    Text(
                        text = stringResource(R.string.respondTime, item.respondTime),
                        color = colorResource(R.color.secondaryText),
                        fontSize = 12.sp
                    )
                }
            }

            // Right check mark (40dp width)
            Box(
                modifier = Modifier.size(40.dp),
                contentAlignment = Alignment.Center
            ) {
                if (isCurrent) {
                    Icon(
                        painter = painterResource(R.drawable.ic_check),
                        contentDescription = null,
                        tint = colorResource(R.color.primaryText),
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }

        DropdownMenu(
            expanded = showContextMenu,
            onDismissRequest = { showContextMenu = false }
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.to_top)) },
                onClick = {
                    showContextMenu = false
                    onTopSource()
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.to_bottom)) },
                onClick = {
                    showContextMenu = false
                    onBottomSource()
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.edit_source)) },
                onClick = {
                    showContextMenu = false
                    onEditSource()
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.disable_source)) },
                onClick = {
                    showContextMenu = false
                    onDisableSource()
                }
            )
            DropdownMenuItem(
                text = {
                    Text(
                        text = stringResource(R.string.delete_source),
                        color = MaterialTheme.colorScheme.error
                    )
                },
                onClick = {
                    showContextMenu = false
                    showDeleteConfirm = true
                }
            )
        }
    }

    HorizontalDivider(thickness = 0.5.dp, color = colorResource(R.color.divider))

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.draw)) },
            text = {
                Text("${stringResource(R.string.sure_del)}\n${item.originName}")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        onDeleteSource()
                    }
                ) {
                    Text(stringResource(R.string.yes))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.no))
                }
            }
        )
    }
}

@Composable
internal fun ChangeSourceBottomBar(
    statusText: String,
    middleEllipsis: Boolean = false,
    onDurClick: () -> Unit,
    onScrollTop: () -> Unit,
    onScrollBottom: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .background(colorResource(R.color.background))
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = statusText,
            color = colorResource(R.color.primaryText),
            fontSize = 12.sp,
            maxLines = 1,
            overflow = if (middleEllipsis) TextOverflow.MiddleEllipsis else TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onDurClick)
                .padding(horizontal = 10.dp)
        )
        IconButton(
            onClick = onScrollTop,
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_arrow_drop_up),
                contentDescription = stringResource(R.string.go_to_top),
                tint = colorResource(R.color.primaryText)
            )
        }
        IconButton(
            onClick = onScrollBottom,
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_arrow_drop_down),
                contentDescription = stringResource(R.string.go_to_bottom),
                tint = colorResource(R.color.primaryText)
            )
        }
    }
}

@Composable
internal fun ChangeSourceWordCountFilterDialog(
    onDismiss: () -> Unit,
    onChanged: (reloadMeasurements: Boolean) -> Unit,
) {
    var selectedMode by remember { mutableIntStateOf(AppConfig.changeSourceWordCountFilterMode) }
    var showRangeStep by remember { mutableStateOf(false) }

    if (!showRangeStep) {
        val modes = listOf(
            ChangeSourceResultOptions.FILTER_OFF to stringResource(R.string.change_source_word_count_filter_off),
            ChangeSourceResultOptions.FILTER_ABSOLUTE to stringResource(R.string.change_source_word_count_filter_absolute),
            ChangeSourceResultOptions.FILTER_RELATIVE to stringResource(R.string.change_source_word_count_filter_relative),
        )
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.change_source_word_count_filter)) },
            text = {
                Column {
                    modes.forEach { (mode, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedMode = mode
                                    if (mode == ChangeSourceResultOptions.FILTER_OFF) {
                                        if (AppConfig.changeSourceWordCountFilterMode != mode) {
                                            AppConfig.changeSourceWordCountFilterMode = mode
                                            onChanged(false)
                                        }
                                        onDismiss()
                                    } else {
                                        showRangeStep = true
                                    }
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = AppConfig.changeSourceWordCountFilterMode == mode,
                                onClick = null
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = label)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    } else {
        val sameMode = AppConfig.changeSourceWordCountFilterMode == selectedMode
        val defaultMin = if (selectedMode == ChangeSourceResultOptions.FILTER_RELATIVE) 70 else 1000
        val defaultMax = if (selectedMode == ChangeSourceResultOptions.FILTER_RELATIVE) 130 else 5000
        var minText by remember(selectedMode) {
            mutableStateOf(
                (if (sameMode) AppConfig.changeSourceWordCountFilterMin else defaultMin).toString()
            )
        }
        var maxText by remember(selectedMode) {
            mutableStateOf(
                (if (sameMode) AppConfig.changeSourceWordCountFilterMax else defaultMax).toString()
            )
        }
        var hasError by remember { mutableStateOf(false) }
        val suffix = if (selectedMode == ChangeSourceResultOptions.FILTER_RELATIVE) "%" else null

        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.change_source_word_count_filter)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = minText,
                        onValueChange = {
                            minText = it
                            hasError = false
                        },
                        label = { Text(stringResource(R.string.change_source_word_count_minimum)) },
                        suffix = suffix?.let { s -> { Text(s) } },
                        isError = hasError,
                        supportingText = if (hasError) {
                            { Text(stringResource(R.string.error_scope_input)) }
                        } else null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = maxText,
                        onValueChange = {
                            maxText = it
                            hasError = false
                        },
                        label = { Text(stringResource(R.string.change_source_word_count_maximum)) },
                        suffix = suffix?.let { s -> { Text(s) } },
                        isError = hasError,
                        supportingText = if (hasError) {
                            { Text(stringResource(R.string.error_scope_input)) }
                        } else null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val newMin = minText.trim().toIntOrNull()
                        val newMax = maxText.trim().toIntOrNull()
                        if (newMin == null || newMax == null || newMin < 0 || newMax < newMin) {
                            hasError = true
                            return@TextButton
                        }
                        val changed = selectedMode != AppConfig.changeSourceWordCountFilterMode ||
                                newMin != AppConfig.changeSourceWordCountFilterMin ||
                                newMax != AppConfig.changeSourceWordCountFilterMax
                        AppConfig.changeSourceWordCountFilterMin = newMin
                        AppConfig.changeSourceWordCountFilterMax = newMax
                        AppConfig.changeSourceWordCountFilterMode = selectedMode
                        if (changed) onChanged(true)
                        onDismiss()
                    }
                ) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

internal fun selectedChapterSourceItems(
    chapters: List<BookChapter>,
    selectedIndices: Set<Int>,
): List<Pair<BookChapter, String?>> = chapters.mapIndexedNotNull { position, chapter ->
    if (!chapter.isVolume && chapter.index in selectedIndices) {
        chapter to chapters.getOrNull(position + 1)?.url
    } else {
        null
    }
}
