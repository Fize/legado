package io.legado.app.ui.association

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.viewModels
import io.legado.app.R
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.data.entities.DictRule
import io.legado.app.ui.compose.component.LegadoTopAppBar
import io.legado.app.ui.widget.dialog.CodeDialog
import io.legado.app.ui.widget.dialog.WaitDialog
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.setLayout
import io.legado.app.utils.showDialogFragment

class ImportDictRuleDialog() : BaseComposeDialogFragment(),
    CodeDialog.Callback {

    constructor(source: String, finishOnDismiss: Boolean = false) : this() {
        arguments = Bundle().apply {
            putString("source", source)
            putBoolean("finishOnDismiss", finishOnDismiss)
        }
    }

    private val viewModel by viewModels<ImportDictRuleViewModel>()
    private var isLoading by mutableStateOf(true)
    private var messageText by mutableStateOf<String?>(null)
    private var selectFooterText by mutableStateOf("")
    private var listVersion by mutableIntStateOf(0)

    override fun onStart() {
        super.onStart()
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        isLoading = true
        viewModel.errorLiveData.observe(viewLifecycleOwner) {
            isLoading = false
            messageText = it
        }
        viewModel.successLiveData.observe(viewLifecycleOwner) {
            isLoading = false
            if (it > 0) {
                messageText = null
                listVersion++
                upSelectText()
            } else {
                messageText = getString(R.string.wrong_format)
            }
        }
        val source = arguments?.getString("source")
        if (source.isNullOrEmpty()) {
            dismiss()
            return
        }
        viewModel.importSource(source)
    }

    @Composable
    override fun DialogContent() {
        val currentVersion = listVersion
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                LegadoTopAppBar(title = stringResource(R.string.import_dict_rule))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp, max = 420.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val msg = messageText
                    if (viewModel.allSources.isNotEmpty() && currentVersion >= 0) {
                        LazyColumn(modifier = Modifier.fillMaxWidth()) {
                            itemsIndexed(viewModel.allSources) { index, item ->
                                val localSource = viewModel.checkSources.getOrNull(index)
                                val statusRes = when (localSource) {
                                    null -> R.string.import_status_new
                                    else -> R.string.import_status_exist
                                }
                                ImportSourceItemRow(
                                    name = item.name,
                                    checked = viewModel.selectStatus.getOrElse(index) { false },
                                    statusText = stringResource(statusRes),
                                    showOpen = true,
                                    onCheckedChange = { isChecked ->
                                        viewModel.selectStatus[index] = isChecked
                                        listVersion++
                                        upSelectText()
                                    },
                                    onOpenClick = {
                                        showDialogFragment(
                                            CodeDialog(
                                                GSON.toJson(item),
                                                disableEdit = false,
                                                requestId = index.toString()
                                            )
                                        )
                                    }
                                )
                            }
                        }
                    } else if (msg != null) {
                        ImportEmptyOrErrorState(message = msg, showIcon = false)
                    }
                    if (isLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(36.dp))
                    }
                }

                HorizontalDivider()
                ImportDialogFooter(
                    selectAllText = selectFooterText,
                    onSelectAllClick = {
                        val selectAll = viewModel.isSelectAll
                        viewModel.selectStatus.forEachIndexed { index, b ->
                            if (b != !selectAll) {
                                viewModel.selectStatus[index] = !selectAll
                            }
                        }
                        listVersion++
                        upSelectText()
                    },
                    onCancelClick = { dismissAllowingStateLoss() },
                    onOkClick = {
                        val waitDialog = WaitDialog(requireContext())
                        waitDialog.show()
                        viewModel.importSelect {
                            waitDialog.dismiss()
                            dismissAllowingStateLoss()
                        }
                    }
                )
            }
        }
    }

    private fun upSelectText() {
        selectFooterText = if (viewModel.isSelectAll) {
            getString(
                R.string.select_cancel_count,
                viewModel.selectCount,
                viewModel.allSources.size
            )
        } else {
            getString(
                R.string.select_all_count,
                viewModel.selectCount,
                viewModel.allSources.size
            )
        }
    }

    override fun onCodeSave(code: String, requestId: String?) {
        requestId?.toInt()?.let {
            GSON.fromJsonObject<DictRule>(code).getOrNull()?.let { source ->
                viewModel.allSources[it] = source
                listVersion++
            }
        }
    }
}
