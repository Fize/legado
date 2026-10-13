package io.legado.app.ui.replace

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.data.appDb
import io.legado.app.ui.compose.component.LegadoMenuAction
import io.legado.app.ui.compose.component.LegadoTextInputDialog
import io.legado.app.ui.compose.component.LegadoTopAppBar
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.utils.setLayout
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch

class GroupManageDialog : BaseDialogFragment() {

    private val viewModel: ReplaceRuleViewModel by activityViewModels()
    private var groupsState by mutableStateOf<List<String>>(emptyList())
    private var isAddingGroup by mutableStateOf(false)
    private var editingGroup by mutableStateOf<String?>(null)

    override fun onStart() {
        super.onStart()
        setLayout(0.9f, 0.9f)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        lifecycleScope.launch {
            appDb.replaceRuleDao.flowGroups().conflate().collect {
                groupsState = it
            }
        }
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LegadoTheme {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.surface,
                    ) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            LegadoTopAppBar(
                                title = stringResource(R.string.group_manage),
                                onBack = { dismiss() },
                                menuActions = listOf(
                                    LegadoMenuAction(
                                        title = stringResource(R.string.add_group),
                                        iconRes = R.drawable.ic_add,
                                        showAsAction = true,
                                        onClick = { isAddingGroup = true },
                                    ),
                                ),
                            )
                            LazyColumn(modifier = Modifier.fillMaxSize()) {
                                items(
                                    items = groupsState,
                                    key = { it },
                                ) { group ->
                                    Column {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 16.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(
                                                text = group,
                                                style = MaterialTheme.typography.bodyLarge,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f),
                                            )
                                            TextButton(onClick = { editGroup(group) }) {
                                                Text(stringResource(R.string.edit))
                                            }
                                            TextButton(onClick = { viewModel.delGroup(group) }) {
                                                Text(
                                                    text = stringResource(R.string.delete),
                                                    color = MaterialTheme.colorScheme.error,
                                                )
                                            }
                                        }
                                        HorizontalDivider(
                                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                        )
                                    }
                                }
                            }
                        }
                    }
                if (isAddingGroup) {
                    LegadoTextInputDialog(
                        title = stringResource(R.string.add_group),
                        initialValue = "",
                        hint = stringResource(R.string.group_name),
                        onDismiss = { isAddingGroup = false },
                        onConfirm = { value ->
                            value.takeIf { it.isNotBlank() }?.let(viewModel::addGroup)
                        },
                    )
                }
                editingGroup?.let { group ->
                    LegadoTextInputDialog(
                        title = stringResource(R.string.group_edit),
                        initialValue = group,
                        hint = stringResource(R.string.group_name),
                        onDismiss = { editingGroup = null },
                        onConfirm = { value -> viewModel.upGroup(group, value) },
                    )
                }
                }
            }
        }
    }

    private fun editGroup(group: String) {
        editingGroup = group
    }

}
