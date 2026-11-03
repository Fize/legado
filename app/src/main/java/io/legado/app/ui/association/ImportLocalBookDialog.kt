package io.legado.app.ui.association

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.dialog
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.constant.AppConst
import io.legado.app.ui.book.import.local.ImportBook
import io.legado.app.ui.compose.component.LegadoMenuAction
import io.legado.app.ui.compose.component.LegadoTopAppBar
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.utils.ConvertUtils

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LocalBookImportDialog(
    viewModel: FileAssociationViewModel,
    items: List<ImportBook>,
    importing: Boolean,
    choosingDestination: Boolean,
    onDismiss: () -> Unit,
) {
    var selectedUris by remember(items) {
        mutableStateOf<Set<Uri>>(items.filter { it.file.uri in viewModel.selectedLocalBooks }
            .mapTo(linkedSetOf()) { it.file.uri })
    }
    val busy = importing || choosingDestination
    val eligibleItems = remember(items) { items.filter { !it.isDir && !it.isOnBookShelf } }
    val allSelected = eligibleItems.isNotEmpty() && selectedUris.size == eligibleItems.size

    fun updateSelection(next: Set<Uri>) {
        selectedUris = next
        viewModel.updateLocalSelection(next)
    }

    LegadoTheme {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            // Keep room for selectable files when display and font scaling leave a
            // short window. Action text and minimum touch targets retain their size.
            val verticalMargin = if (maxHeight < 320.dp) 4.dp else 16.dp
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(onDismiss, busy) { detectTapGestures { if (!busy) onDismiss() } }
                    .padding(horizontal = 24.dp, vertical = verticalMargin),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    modifier = Modifier
                        .widthIn(max = 640.dp)
                        .fillMaxWidth()
                        .pointerInput(Unit) { detectTapGestures {} }
                        .semantics { dialog() }
                        .testTag("sharedLocalBooksDialog"),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 6.dp,
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        LegadoTopAppBar(
                            title = stringResource(R.string.local_book),
                            menuActions = if (!importing) {
                                listOf(
                                    LegadoMenuAction(
                                        title = stringResource(R.string.local_book_save_path),
                                        onClick = { viewModel.requestLocalBookDirectory(false) },
                                    ),
                                )
                            } else emptyList(),
                        )

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f, fill = false)
                                .heightIn(max = 420.dp),
                        ) {
                            LazyColumn(modifier = Modifier.fillMaxWidth().testTag("sharedLocalBooksList")) {
                                items(items = items, key = { it.file.uri.toString() }) { item ->
                                    val selected = item.file.uri in selectedUris
                                    val canSelect = !item.isDir && !item.isOnBookShelf
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = 60.dp)
                                            .clip(RoundedCornerShape(4.dp))
                                            .testTag("sharedLocalBook:${item.file.name}")
                                            .clickable(enabled = !importing && canSelect) {
                                                val next = selectedUris.toMutableSet().apply {
                                                    if (selected) remove(item.file.uri) else add(item.file.uri)
                                                }
                                                updateSelection(next)
                                            }
                                            .padding(horizontal = 12.dp, vertical = 8.dp),
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
                                                    checked = selected,
                                                    enabled = !importing,
                                                    onCheckedChange = { checked ->
                                                        val next = selectedUris.toMutableSet().apply {
                                                            if (checked) add(item.file.uri) else remove(item.file.uri)
                                                        }
                                                        updateSelection(next)
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
                                            } ?: item.file.name
                                            Text(
                                                text = displayName,
                                                style = MaterialTheme.typography.bodyLarge,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            if (!item.isDir) {
                                                Spacer(modifier = Modifier.height(2.dp))
                                                FlowRow(
                                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                                ) {
                                                    Text(
                                                        text = item.name.substringAfterLast("."),
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onPrimary,
                                                        modifier = Modifier
                                                            .background(
                                                                MaterialTheme.colorScheme.primary,
                                                                RoundedCornerShape(2.dp),
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

                            if (importing) {
                                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                            }
                        }

                        HorizontalDivider()
                        // Keep each action's natural width; wrap whole actions instead
                        // of squeezing the final label into the remainder of one row.
                        FlowRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            TextButton(
                                onClick = {
                                    updateSelection(
                                        if (allSelected) emptySet() else eligibleItems.mapTo(linkedSetOf()) { it.file.uri },
                                    )
                                },
                                enabled = !busy,
                                modifier = Modifier.testTag("sharedLocalBooksSelectAll"),
                            ) {
                                Text(
                                    text = stringResource(
                                        if (allSelected) R.string.select_cancel_count else R.string.select_all_count,
                                        selectedUris.size,
                                        items.size,
                                    ),
                                )
                            }
                            TextButton(
                                onClick = onDismiss,
                                enabled = !importing,
                                modifier = Modifier.testTag("sharedLocalBooksCancel"),
                            ) {
                                Text(text = stringResource(R.string.cancel))
                            }
                            TextButton(
                                onClick = { viewModel.confirmLocalBooks() },
                                enabled = selectedUris.isNotEmpty() && !busy,
                                modifier = Modifier.testTag("sharedLocalBooksConfirm"),
                            ) {
                                Text(text = stringResource(R.string.add_to_bookshelf))
                            }
                        }
                    }
                }
            }
        }
    }
}
