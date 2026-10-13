package io.legado.app.ui.highlight

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.data.appDb
import io.legado.app.lib.dialogs.alert
import io.legado.app.model.ReadBook
import io.legado.app.ui.compose.component.LegadoTextInputDialog
import io.legado.app.ui.compose.component.LegadoTopAppBar
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.utils.setLayout
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class HighlightGroupManageDialog : BaseDialogFragment() {

    private val viewModel: HighlightRuleViewModel by activityViewModels()
    private var groupsState by mutableStateOf<List<String>>(emptyList())
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
            appDb.highlightRuleDao.flowGroups().conflate().collect {
                groupsState = it
            }
        }
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LegadoTheme {
                    DialogContent()
                }
            }
        }
    }

    @Composable
    private fun DialogContent() {
        Scaffold(
            topBar = {
                LegadoTopAppBar(
                    title = stringResource(R.string.highlight_rule_group_manage),
                    onBack = { dismissAllowingStateLoss() },
                )
            },
        ) { innerPadding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                items(
                    items = groupsState,
                    key = { it },
                ) { group ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = group,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { editGroup(group) }) {
                            Text(text = stringResource(R.string.edit))
                        }
                        TextButton(onClick = { deleteGroup(group) }) {
                            Text(
                                text = stringResource(R.string.delete),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
        editingGroup?.let { group ->
            LegadoTextInputDialog(
                title = stringResource(R.string.group_edit),
                initialValue = group,
                hint = stringResource(R.string.group_name),
                onDismiss = { editingGroup = null },
                onConfirm = { value ->
                    value.trim().takeIf { it.isNotEmpty() }?.let {
                        viewModel.renameGroup(group, it)
                    }
                },
            )
        }
    }

    private fun editGroup(group: String) {
        editingGroup = group
    }

    private fun deleteGroup(group: String) {
        alert(title = getString(R.string.highlight_rule_group_delete)) {
            setMessage(R.string.highlight_rule_group_delete_message)
            positiveButton(R.string.delete) {
                viewModel.deleteGroup(group).onSuccess { ReadBook.upHighlightRules() }
            }
            negativeButton(R.string.move_to_group) { chooseMoveTarget(group) }
            neutralButton(android.R.string.cancel)
        }
    }

    private fun chooseMoveTarget(source: String) {
        lifecycleScope.launch {
            val values = appDb.highlightRuleDao.flowGroups().first()
                .filterNot { it == source }.map { MoveTarget("[$it]", it) } +
                MoveTarget(getString(R.string.no_group), null)
            alert(title = getString(R.string.move_to_group)) {
                items(values) { _, target, _ ->
                    viewModel.moveGroup(source, target.group)
                        .onSuccess { ReadBook.upHighlightRules() }
                }
            }
        }
    }

    private data class MoveTarget(val title: String, val group: String?) {
        override fun toString() = title
    }
}
