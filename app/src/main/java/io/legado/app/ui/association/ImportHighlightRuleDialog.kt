package io.legado.app.ui.association

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
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
import io.legado.app.ui.compose.component.LegadoTopAppBar
import io.legado.app.ui.widget.dialog.WaitDialog
import io.legado.app.utils.setLayout

class ImportHighlightRuleDialog() : BaseComposeDialogFragment() {

    constructor(source: String, finishOnDismiss: Boolean = false) : this() {
        arguments = Bundle().apply {
            putString(ARG_SOURCE, source)
            putBoolean(ARG_FINISH_ON_DISMISS, finishOnDismiss)
        }
    }

    private val viewModel by viewModels<ImportHighlightRuleViewModel>()
    private var waitDialog: WaitDialog? = null
    private var isLoading by mutableStateOf(true)
    private var messageText by mutableStateOf<String?>(null)
    private var selectFooterText by mutableStateOf("")
    private var selectAllEnabled by mutableStateOf(false)
    private var okEnabled by mutableStateOf(false)
    private var listVersion by mutableIntStateOf(0)

    override fun onStart() {
        super.onStart()
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onDestroyView() {
        waitDialog?.dismiss()
        waitDialog = null
        super.onDestroyView()
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        isLoading = true
        viewModel.importingLiveData.observe(viewLifecycleOwner) { importing ->
            if (importing) {
                if (waitDialog == null) {
                    waitDialog = WaitDialog(requireContext()).also { it.show() }
                }
            } else {
                waitDialog?.dismiss()
                waitDialog = null
            }
            upSelectText()
        }
        viewModel.importSuccessLiveData.observe(viewLifecycleOwner) { success ->
            if (success) dismissAllowingStateLoss()
        }
        viewModel.errorLiveData.observe(viewLifecycleOwner) {
            waitDialog?.dismiss()
            waitDialog = null
            isLoading = false
            messageText = it
        }
        viewModel.successLiveData.observe(viewLifecycleOwner) {
            isLoading = false
            if (it > 0) {
                messageText = null
                selectAllEnabled = true
                listVersion++
                upSelectText()
            } else {
                messageText = getString(R.string.wrong_format)
            }
        }
        val source = arguments?.getString(ARG_SOURCE)
        if (source.isNullOrEmpty()) {
            dismiss()
        } else {
            viewModel.load(source)
        }
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
                LegadoTopAppBar(title = stringResource(R.string.highlight_rule))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp, max = 420.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val msg = messageText
                    if (viewModel.items.isNotEmpty() && currentVersion >= 0) {
                        LazyColumn(modifier = Modifier.fillMaxWidth()) {
                            itemsIndexed(viewModel.items) { index, item ->
                                val statusRes = when (item.status) {
                                    HighlightRuleImportStatus.NEW -> R.string.import_status_new
                                    HighlightRuleImportStatus.UPDATE -> R.string.import_status_update
                                    HighlightRuleImportStatus.EXISTING -> R.string.import_status_exist
                                }
                                ImportSourceItemRow(
                                    name = item.rule.getDisplayName(),
                                    checked = viewModel.selectStatus.getOrElse(index) { false },
                                    statusText = stringResource(statusRes),
                                    showOpen = false,
                                    onCheckedChange = { isChecked ->
                                        if (index in viewModel.selectStatus.indices) {
                                            viewModel.selectStatus[index] = isChecked
                                            listVersion++
                                            upSelectText()
                                        }
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
                    selectAllEnabled = selectAllEnabled,
                    okEnabled = okEnabled,
                    onSelectAllClick = {
                        val select = !viewModel.isSelectAll
                        viewModel.selectStatus.indices.forEach { viewModel.selectStatus[it] = select }
                        listVersion++
                        upSelectText()
                    },
                    onCancelClick = { dismissAllowingStateLoss() },
                    onOkClick = { viewModel.importSelected() }
                )
            }
        }
    }

    private fun upSelectText() {
        okEnabled = viewModel.selectCount > 0 && viewModel.importingLiveData.value != true
        selectFooterText = getString(
            if (viewModel.isSelectAll) R.string.select_cancel_count else R.string.select_all_count,
            viewModel.selectCount,
            viewModel.items.size
        )
    }

    private companion object {
        const val ARG_SOURCE = "source"
        const val ARG_FINISH_ON_DISMISS = "finishOnDismiss"
    }
}

internal fun AppCompatActivity.showImportHighlightRuleDialog(
    source: String,
    finishOnDismiss: Boolean = false
) {
    val tag = ImportHighlightRuleDialog::class.simpleName
    if (supportFragmentManager.findFragmentByTag(tag) == null) {
        ImportHighlightRuleDialog(source, finishOnDismiss)
            .show(supportFragmentManager, tag)
    }
}
