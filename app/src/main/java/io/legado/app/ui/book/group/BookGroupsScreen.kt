package io.legado.app.ui.book.group

import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.legado.app.R
import io.legado.app.data.repository.BookGroupError
import io.legado.app.data.repository.isSelectableGroup
import io.legado.app.ui.compose.component.LegadoTopAppBar

@Composable
fun BookGroupsDestination(onBack: () -> Unit, viewModel: BookGroupsViewModel = viewModel()) {
    val state by viewModel.controller.state.collectAsStateWithLifecycle()
    BackHandler(enabled = state.editor != null || state.busy) {
        if (!state.busy) viewModel.controller.dismiss()
    }
    BookGroupsContent(state, viewModel.controller, onBack)
}

@Composable
fun BookGroupSelectionDialog(state: BookGroupsUiState, controller: BookGroupsController) {
    if (!state.open) return
    Dialog(onDismissRequest = controller::dismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = !state.busy)) {
        Surface(Modifier.fillMaxSize()) { BookGroupsContent(state, controller, controller::dismiss) }
    }
}

@Composable
private fun BookGroupsContent(state: BookGroupsUiState, controller: BookGroupsController, onClose: () -> Unit) {
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        controller.receiveCover(uri)
    }
    val draft = state.editor
    val title = when {
        draft != null && draft.id == null -> R.string.group_create_title
        draft != null -> R.string.group_edit
        state.selection -> R.string.group_select
        else -> R.string.group_manage
    }
    val dismiss = {
        if (!state.busy) {
            if (draft != null) controller.dismiss() else onClose()
        }
    }
    Scaffold(modifier = Modifier.fillMaxSize().imePadding().testTag("book_groups_screen"), topBar = {
        LegadoTopAppBar(title = stringResource(title), onNavigationClick = dismiss, actions = {
            if (draft == null) IconButton(onClick = controller::newGroup, enabled = !state.loading && !state.busy,
                modifier = Modifier.testTag("group_add")) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.add))
            }
        })
    }, bottomBar = {
        Surface(tonalElevation = 2.dp) {
            Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = dismiss, enabled = !state.busy, modifier = Modifier.testTag("group_cancel")) { Text(stringResource(R.string.cancel)) }
                TextButton(onClick = {
                    when { draft != null -> controller.saveEditor(); state.selection -> controller.confirmSelection(); else -> onClose() }
                }, enabled = !state.busy && !state.loading, modifier = Modifier.testTag("group_confirm")) { Text(stringResource(R.string.ok)) }
            }
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.busy || state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("groups_loading"))
            state.error?.let { error ->
                val message = when (error) {
                    BookGroupError.EmptyName -> stringResource(R.string.group_empty_name)
                    BookGroupError.Limit -> stringResource(R.string.group_limit_reached)
                    BookGroupError.Missing -> stringResource(R.string.group_missing)
                    BookGroupError.Changed -> stringResource(R.string.group_changed)
                    BookGroupError.Protected -> stringResource(R.string.group_protected)
                    BookGroupError.Storage -> state.errorDetail ?: stringResource(R.string.error)
                }
                Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp).testTag("group_error"))
                if (state.loading) TextButton(onClick = controller::retry) { Text(stringResource(R.string.retry)) }
            }
            if (draft != null) {
                val bitmap by produceState<Bitmap?>(null, draft.cover) { value = controller.coverBitmap(draft.cover) }
                val sorts = stringArrayResource(R.array.book_sort)
                var sortExpanded by remember { mutableStateOf(false) }
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(Modifier.width(72.dp).height(100.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                            bitmap?.let { Image(it.asImageBitmap(), stringResource(R.string.img_cover), modifier = Modifier.testTag("group_cover_preview"), contentScale = ContentScale.Crop) }
                                ?: Text(draft.name, modifier = Modifier.padding(8.dp))
                        }
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            OutlinedTextField(draft.name, { name -> controller.updateEditor { it.copy(name = name) } },
                                enabled = !state.busy, singleLine = true, label = { Text(stringResource(R.string.group_name)) },
                                modifier = Modifier.fillMaxWidth().testTag("group_name"))
                            Box {
                                OutlinedButton(onClick = { sortExpanded = true }, enabled = !state.busy, modifier = Modifier.testTag("group_sort")) {
                                    Text("${stringResource(R.string.sort)}: ${sorts.getOrElse(draft.sort + 1) { sorts.first() }}")
                                }
                                DropdownMenu(sortExpanded, { sortExpanded = false }) {
                                    sorts.forEachIndexed { index, label -> DropdownMenuItem(text = { Text(label) }, onClick = {
                                        controller.updateEditor { it.copy(sort = index - 1) }; sortExpanded = false
                                    }) }
                                }
                            }
                        }
                    }
                    OutlinedTextField(if (draft.importedCover) "" else draft.cover.orEmpty(), { value ->
                        controller.updateEditor { it.copy(cover = value.takeIf(String::isNotBlank), importedCover = false) }
                    }, enabled = !state.busy, label = { Text(stringResource(R.string.group_cover_url)) },
                        supportingText = { if (draft.importedCover) Text(stringResource(R.string.group_cover_selected)) },
                        modifier = Modifier.fillMaxWidth().testTag("group_cover"))
                    Row {
                        TextButton(onClick = { controller.chooseCover(); picker.launch(arrayOf("image/*")) }, enabled = !state.busy,
                            modifier = Modifier.testTag("group_cover_pick")) { Text(stringResource(R.string.select_image)) }
                        TextButton(onClick = { controller.updateEditor { it.copy(cover = null, importedCover = false) } }, enabled = !state.busy) { Text(stringResource(R.string.clear)) }
                    }
                    Row(Modifier.fillMaxWidth().toggleable(draft.refresh, enabled = !state.busy, role = Role.Checkbox,
                        onValueChange = { value -> controller.updateEditor { it.copy(refresh = value) } }).testTag("group_refresh"), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(draft.refresh, null); Text(stringResource(R.string.allow_drop_down_refresh))
                    }
                    Row(Modifier.fillMaxWidth().toggleable(draft.onlyRead, enabled = !state.busy, role = Role.Checkbox,
                        onValueChange = { value -> controller.updateEditor { it.copy(onlyRead = value) } }).testTag("group_only_read"), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(draft.onlyRead, null); Text(stringResource(R.string.only_update_read))
                    }
                    if (draft.id != null && (draft.id > 0 || draft.id == Long.MIN_VALUE)) TextButton(onClick = controller::confirmDelete,
                        enabled = !state.busy, modifier = Modifier.testTag("group_delete")) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
                }
            } else {
                val groups = if (state.selection) state.groups.filter { it.isSelectableGroup() } else state.groups
                LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("group_list")) {
                    itemsIndexed(groups, key = { _, group -> group.groupId }) { index, group ->
                        val checked = state.selected and group.groupId != 0L
                        val row = if (state.selection) Modifier.toggleable(checked, enabled = !state.busy, role = Role.Checkbox,
                            onValueChange = { controller.toggle(group.groupId, group.generation) }) else Modifier
                        Row(row.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).testTag("group_row_${group.groupId}"), verticalAlignment = Alignment.CenterVertically) {
                            if (state.selection) Checkbox(checked, null)
                            Text(if (state.selection) group.groupName else group.getManageName(context), modifier = Modifier.weight(1f))
                            IconButton(onClick = { controller.move(group, -1) }, enabled = !state.busy && index > 0,
                                modifier = Modifier.testTag("group_up_${group.groupId}")) { Icon(Icons.Default.KeyboardArrowUp, stringResource(R.string.group_move_up, group.groupName)) }
                            IconButton(onClick = { controller.move(group, 1) }, enabled = !state.busy && index < groups.lastIndex,
                                modifier = Modifier.testTag("group_down_${group.groupId}")) { Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.group_move_down, group.groupName)) }
                            IconButton(onClick = { controller.edit(group) }, enabled = !state.busy, modifier = Modifier.testTag("group_edit_${group.groupId}")) {
                                Icon(Icons.Default.Edit, stringResource(R.string.edit))
                            }
                            if (!state.selection) {
                                val description = stringResource(R.string.group_visibility, group.groupName)
                                Switch(group.show, { controller.setVisible(group, it) }, enabled = !state.busy,
                                    modifier = Modifier.testTag("group_visible_${group.groupId}").semantics { contentDescription = description })
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
    if (state.confirmingDelete) AlertDialog(onDismissRequest = controller::cancelDelete,
        title = { Text(stringResource(R.string.delete)) }, text = { Text(stringResource(R.string.sure_del)) },
        confirmButton = { TextButton(onClick = controller::delete, enabled = !state.busy, modifier = Modifier.testTag("group_delete_confirm")) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = controller::cancelDelete, enabled = !state.busy) { Text(stringResource(R.string.cancel)) } })
}
