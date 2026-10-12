package io.legado.app.ui.association

import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.activityViewModels
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.constant.AppConst
import io.legado.app.ui.book.import.local.ImportBook
import io.legado.app.ui.compose.component.LegadoMenuAction
import io.legado.app.ui.compose.component.LegadoTopAppBar
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.utils.ConvertUtils
import io.legado.app.utils.setLayout

class ImportLocalBookDialog : BaseDialogFragment() {
    private val viewModel by activityViewModels<FileAssociationViewModel>()

    private var itemsState by mutableStateOf<List<ImportBook>>(emptyList())
    private var selectedState by mutableStateOf<Set<ImportBook>>(emptySet())
    private var importingState by mutableStateOf(false)
    private var choosingDestinationState by mutableStateOf(false)

    override fun onStart() {
        super.onStart()
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LegadoTheme {
                    DialogContent()
                }
            }
        }
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        val items = viewModel.localBookBatch.value.orEmpty()
        itemsState = items
        selectedState = items.filter { it.file.uri in viewModel.selectedLocalBooks }.toSet()
        updateSelectionInViewModel(selectedState)

        viewModel.importingLocalBooks.observe(viewLifecycleOwner) { importing ->
            isCancelable = !importing
            importingState = importing
        }
        viewModel.localBookDestination.observe(viewLifecycleOwner) { choosing ->
            choosingDestinationState = choosing
        }
    }

    private fun updateSelectionInViewModel(selected: Set<ImportBook>) {
        selectedState = selected
        viewModel.updateLocalSelection(selected.map { it.file.uri })
    }

    @Composable
    private fun DialogContent() {
        val busy = importingState || choosingDestinationState
        val allSelected = itemsState.isNotEmpty() && selectedState.size == itemsState.size

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                LegadoTopAppBar(
                    title = stringResource(R.string.local_book),
                    menuActions = if (!importingState) {
                        listOf(
                            LegadoMenuAction(
                                title = stringResource(R.string.local_book_save_path),
                                onClick = { viewModel.requestLocalBookDirectory(false) },
                            ),
                        )
                    } else {
                        emptyList()
                    },
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 160.dp, max = 420.dp),
                ) {
                    LazyColumn(modifier = Modifier.fillMaxWidth()) {
                        items(
                            items = itemsState,
                            key = { it.file.uri.toString() },
                        ) { item ->
                            val isSelected = item in selectedState
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(60.dp)
                                    .clickable(enabled = !importingState) {
                                        if (!item.isDir && !item.isOnBookShelf) {
                                            val next = if (isSelected) {
                                                selectedState - item
                                            } else {
                                                selectedState + item
                                            }
                                            updateSelectionInViewModel(next)
                                        }
                                    }
                                    .padding(end = 12.dp),
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
                                            checked = isSelected,
                                            enabled = !importingState,
                                            onCheckedChange = { checked ->
                                                val next = if (checked) {
                                                    selectedState + item
                                                } else {
                                                    selectedState - item
                                                }
                                                updateSelectionInViewModel(next)
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
                                    } ?: item.name
                                    Text(
                                        text = displayName,
                                        style = MaterialTheme.typography.bodyLarge,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (!item.isDir) {
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(12.dp),
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

                    if (importingState) {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    }
                }

                HorizontalDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = {
                            val next = if (allSelected) {
                                emptySet()
                            } else {
                                itemsState.filter { !it.isDir && !it.isOnBookShelf }.toSet()
                            }
                            updateSelectionInViewModel(next)
                        },
                        enabled = !busy,
                    ) {
                        Text(
                            text = stringResource(
                                if (allSelected) R.string.select_cancel_count else R.string.select_all_count,
                                selectedState.size,
                                itemsState.size,
                            ),
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(
                        onClick = { dismiss() },
                        enabled = !importingState,
                    ) {
                        Text(text = stringResource(R.string.cancel))
                    }
                    TextButton(
                        onClick = { viewModel.confirmLocalBooks() },
                        enabled = selectedState.isNotEmpty() && !busy,
                    ) {
                        Text(text = stringResource(R.string.add_to_bookshelf))
                    }
                }
            }
        }
    }

    override fun onCancel(dialog: DialogInterface) {
        super.onCancel(dialog)
        activity?.finish()
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        if (activity?.isChangingConfigurations != true) activity?.finish()
    }
}
