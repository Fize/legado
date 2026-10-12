package io.legado.app.ui.file

import android.os.Bundle
import androidx.activity.addCallback
import androidx.activity.viewModels
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import io.legado.app.R
import io.legado.app.base.VMBaseComposeActivity
import io.legado.app.constant.AppConst
import io.legado.app.ui.compose.component.LegadoTopAppBar
import io.legado.app.ui.file.utils.FilePickerIcon
import io.legado.app.utils.ConvertUtils
import io.legado.app.utils.openFileUri
import java.io.File

class FileManageActivity : VMBaseComposeActivity<FileManageViewModel>() {

    override val viewModel by viewModels<FileManageViewModel>()
    private val dirParent = ".."

    private var currentFilesState by mutableStateOf<List<File>>(emptyList())
    private var subDocsState by mutableStateOf<List<File>>(emptyList())
    private var searchQueryState by mutableStateOf("")

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        onBackPressedDispatcher.addCallback(this) {
            if (viewModel.lastDir != viewModel.rootDoc) {
                gotoLastDir()
                return@addCallback
            }
            finish()
        }
        viewModel.upFiles(viewModel.rootDoc)
    }

    override fun observeLiveBus() {
        viewModel.filesLiveData.observe(this) {
            searchQueryState = ""
            currentFilesState = it
            subDocsState = viewModel.subDocs.toList()
        }
    }

    private fun gotoLastDir() {
        viewModel.subDocs.removeLastOrNull()
        subDocsState = viewModel.subDocs.toList()
        viewModel.upFiles(viewModel.lastDir)
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    override fun Content() {
        val arrowBitmap = remember { ConvertUtils.toBitmap(FilePickerIcon.getArrow())!!.asImageBitmap() }
        val upBitmap = remember { ConvertUtils.toBitmap(FilePickerIcon.getUpDir())!!.asImageBitmap() }
        val folderBitmap = remember { ConvertUtils.toBitmap(FilePickerIcon.getFolder())!!.asImageBitmap() }
        val fileBitmap = remember { ConvertUtils.toBitmap(FilePickerIcon.getFile())!!.asImageBitmap() }

        val displayedFiles = remember(currentFilesState, searchQueryState) {
            if (searchQueryState.isNotEmpty()) {
                currentFilesState.filter {
                    it.name == dirParent || it.name.contains(searchQueryState)
                }
            } else {
                currentFilesState
            }
        }

        Scaffold(
            topBar = {
                LegadoTopAppBar(
                    title = stringResource(R.string.file_manage),
                    onBack = { finish() },
                    searchQuery = searchQueryState,
                    onSearchQueryChange = { searchQueryState = it },
                    searchPlaceholder = stringResource(R.string.screen) + " • " + stringResource(R.string.file_manage),
                )
            },
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .windowInsetsPadding(WindowInsets.navigationBars),
            ) {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    item(key = "root_breadcrumb") {
                        Row(
                            modifier = Modifier
                                .clickable {
                                    viewModel.subDocs.clear()
                                    subDocsState = emptyList()
                                    viewModel.upFiles(viewModel.rootDoc)
                                }
                                .padding(horizontal = 4.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "root",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Image(
                                bitmap = arrowBitmap,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                    itemsIndexed(
                        items = subDocsState,
                        key = { _, file -> file.absolutePath },
                    ) { index, file ->
                        Row(
                            modifier = Modifier
                                .clickable {
                                    viewModel.subDocs = viewModel.subDocs.subList(0, index + 1)
                                    subDocsState = viewModel.subDocs.toList()
                                    viewModel.upFiles(viewModel.subDocs.lastOrNull())
                                }
                                .padding(horizontal = 4.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = file.name,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Image(
                                bitmap = arrowBitmap,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(
                        items = displayedFiles,
                        key = { "${it.absolutePath}_${it == viewModel.lastDir}" },
                    ) { item ->
                        var menuExpanded by remember { mutableStateOf(false) }
                        val isParentEntry = item == viewModel.lastDir

                        Box {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .combinedClickable(
                                        onClick = {
                                            if (item == viewModel.lastDir) {
                                                gotoLastDir()
                                            } else if (item.isDirectory) {
                                                viewModel.subDocs.add(item)
                                                subDocsState = viewModel.subDocs.toList()
                                                viewModel.upFiles(item)
                                            } else {
                                                openFileUri(
                                                    FileProvider.getUriForFile(
                                                        this@FileManageActivity,
                                                        AppConst.authority,
                                                        item,
                                                    )
                                                )
                                            }
                                        },
                                        onLongClick = {
                                            if (item == viewModel.lastDir) {
                                                return@combinedClickable
                                            }
                                            menuExpanded = true
                                        },
                                    )
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                val iconBitmap = when {
                                    isParentEntry -> upBitmap
                                    item.isDirectory -> folderBitmap
                                    else -> fileBitmap
                                }
                                Image(
                                    bitmap = iconBitmap,
                                    contentDescription = null,
                                    modifier = Modifier.size(24.dp),
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = if (isParentEntry) dirParent else item.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                            }

                            DropdownMenu(
                                expanded = menuExpanded,
                                onDismissRequest = { menuExpanded = false },
                            ) {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = stringResource(R.string.delete),
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    },
                                    onClick = {
                                        menuExpanded = false
                                        viewModel.delFile(item)
                                    },
                                )
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    }
                }
            }
        }
    }

}
