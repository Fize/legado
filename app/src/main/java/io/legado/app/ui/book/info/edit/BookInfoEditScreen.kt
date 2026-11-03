package io.legado.app.ui.book.info.edit

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.legado.app.R
import io.legado.app.data.entities.coverSourceOrigin
import io.legado.app.data.repository.BookInfoEditField
import io.legado.app.help.book.isLocal
import io.legado.app.ui.book.changecover.CoverSearchDialog
import io.legado.app.ui.compose.component.LegadoBookCover
import io.legado.app.ui.compose.component.LegadoTopAppBar

@Composable
fun BookInfoEditScreen(bookUrl: String, onBack: () -> Unit, onSaved: () -> Unit,
    viewModel: BookInfoEditViewModel = viewModel(), owner: io.legado.app.data.entities.BookInfoChildRequest? = null) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            runCatching { context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            viewModel.importCover(it)
        }
    }
    LaunchedEffect(bookUrl, owner?.producerToken) { viewModel.setNavigationOwner(owner); viewModel.loadBook(bookUrl) }
    LaunchedEffect(state.saved) { if (state.saved) { viewModel.consumeSaved(); onSaved() } }
    BackHandler(state.busy) { }
    CoverSearchDialog(state.coverSearch, viewModel.coverController)
    BookInfoEditContent(state, viewModel, onBack = { if (!state.busy) onBack() },
        onSelectCover = { picker.launch(arrayOf("image/*")) }, onSearchCover = viewModel::openCoverSearch)
}

@Composable
private fun BookInfoEditContent(
    state: BookInfoEditUiState,
    viewModel: BookInfoEditViewModel,
    onBack: () -> Unit,
    onSelectCover: () -> Unit,
    onSearchCover: () -> Unit,
) {
    val draft = state.draft
    val editable = !state.busy && !state.saved && !state.loading && state.book != null
    val bookTypes = stringArrayResource(R.array.book_type)
    var typeMenuExpanded by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            LegadoTopAppBar(
                title = stringResource(R.string.book_info_edit),
                onBack = onBack,
                actions = {
                    IconButton(onClick = viewModel::save, enabled = editable, modifier = Modifier.testTag("book_edit_save")) {
                        Icon(painterResource(R.drawable.ic_save), stringResource(R.string.action_save))
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (state.loading || state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("book_edit_progress"))
            state.error?.let { error ->
                val message = when (error) {
                    BookInfoEditError.Missing -> stringResource(R.string.book_not_exist)
                    BookInfoEditError.Name -> stringResource(R.string.book_edit_name_required)
                    BookInfoEditError.Draft -> stringResource(R.string.book_edit_draft_restore_failed)
                    BookInfoEditError.Conflict -> stringResource(R.string.book_edit_identity_conflict)
                    BookInfoEditError.Storage -> state.errorMessage ?: stringResource(R.string.error)
                }
                Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("book_edit_error"))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) {
                val displayCover = if (BookInfoEditField.Cover in state.dirty || state.coverRevision > 0) draft.coverUrl.takeIf(String::isNotBlank) ?: state.book?.coverUrl
                    else state.book?.getDisplayCover()
                key(state.coverRevision) {
                    LegadoBookCover(
                        path = displayCover,
                        name = draft.bookName,
                        sourceOrigin = if (BookInfoEditField.Cover in state.dirty || state.coverRevision > 0) draft.coverSource
                            ?: coverSourceOrigin(state.book?.origin.orEmpty(), draft.coverUrl) else state.book?.getCoverSourceOrigin(),
                        author = draft.bookAuthor,
                        modifier = Modifier.width(90.dp).height(130.dp),
                    )
                }
                Column(
                    modifier = Modifier.weight(1f).padding(start = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    OutlinedTextField(
                        value = draft.bookName,
                        onValueChange = { value -> viewModel.updateDraft { it.copy(bookName = value) } },
                        label = { Text(stringResource(R.string.book_name)) },
                        singleLine = true,
                        enabled = editable,
                        modifier = Modifier.fillMaxWidth().testTag("book_edit_name"),
                    )
                    OutlinedTextField(
                        value = draft.bookAuthor,
                        onValueChange = { value -> viewModel.updateDraft { it.copy(bookAuthor = value) } },
                        label = { Text(stringResource(R.string.author)) },
                        singleLine = true,
                        enabled = editable,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.book_type), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.width(8.dp))
                Box {
                    Row(
                        modifier = Modifier.clickable(enabled = editable) { typeMenuExpanded = true }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            bookTypes.getOrElse(draft.selectedTypeIndex) {
                                bookTypes.firstOrNull().orEmpty()
                            },
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                    }
                    DropdownMenu(
                        expanded = typeMenuExpanded,
                        onDismissRequest = { typeMenuExpanded = false },
                    ) {
                        bookTypes.forEachIndexed { index, label ->
                            if (index == 3 && state.book?.isLocal == true) return@forEachIndexed
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    viewModel.updateDraft { it.copy(selectedTypeIndex = index) }
                                    typeMenuExpanded = false
                                },
                            )
                        }
                    }
                }
            }

            OutlinedTextField(
                value = draft.coverUrl,
                onValueChange = { value -> viewModel.updateDraft { it.copy(coverUrl = value, coverSource = null) } },
                label = { Text(stringResource(R.string.cover_path)) },
                enabled = editable,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onSelectCover,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("book_edit_pick_cover"),
                    enabled = editable,
                    contentPadding = PaddingValues(horizontal = 4.dp),
                ) {
                    Text(
                        stringResource(R.string.select_local_image),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                OutlinedButton(
                    onClick = onSearchCover,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("book_edit_search_cover"),
                    enabled = editable,
                    contentPadding = PaddingValues(horizontal = 4.dp),
                ) {
                    Text(
                        stringResource(R.string.change_cover_source),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                OutlinedButton(
                    onClick = viewModel::refreshCover,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    enabled = editable,
                    contentPadding = PaddingValues(horizontal = 4.dp),
                ) {
                    Text(
                        stringResource(R.string.refresh_cover),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            OutlinedTextField(
                value = draft.bookIntro,
                onValueChange = { value -> viewModel.updateDraft { it.copy(bookIntro = value) } },
                label = { Text(stringResource(R.string.book_intro)) },
                enabled = editable,
                minLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
