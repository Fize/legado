package io.legado.app.ui.association

import android.content.DialogInterface
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.activityViewModels
import io.legado.app.R
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.utils.FileDoc
import io.legado.app.utils.setLayout

/** Confirmation for shared book lists and backups, with the existing import/restore operations. */
class ImportDataDialog() : BaseComposeDialogFragment() {
    constructor(type: String, source: String) : this() {
        arguments = Bundle().apply {
            putString("type", type)
            putString("source", source)
        }
    }

    private val viewModel by activityViewModels<FileAssociationViewModel>()
    private var importingState by mutableStateOf(false)
    private var titleText by mutableStateOf("")
    private var messageText by mutableStateOf("")

    override fun onStart() {
        super.onStart()
        setLayout(0.9f, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        val backup = requireArguments().getString("type") == "backup"
        val uri = Uri.parse(requireArguments().getString("source"))
        val name = FileDoc.fromUri(uri, false).name
        titleText = getString(if (backup) R.string.restore_confirmation else R.string.import_bookshelf)
        messageText = if (backup) "$name\n${getString(R.string.restore_message)}" else name

        viewModel.importingData.observe(viewLifecycleOwner) { importing ->
            isCancelable = !importing
            importingState = importing
            if (importing) {
                messageText = getString(R.string.importing)
            }
        }
        viewModel.importedData.observe(viewLifecycleOwner) { imported ->
            if (imported) dismissAllowingStateLoss()
        }
    }

    @Composable
    override fun DialogContent() {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Text(
                    text = titleText,
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = messageText,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = { dismissAllowingStateLoss() },
                        enabled = !importingState
                    ) {
                        Text(stringResource(R.string.cancel))
                    }
                    TextButton(
                        onClick = {
                            viewModel.importData(
                                requireArguments().getString("type")!!,
                                requireArguments().getString("source")!!,
                            )
                        },
                        enabled = !importingState
                    ) {
                        Text(stringResource(R.string.ok))
                    }
                }
            }
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        if (activity?.isChangingConfigurations != true) activity?.finish()
    }
}
