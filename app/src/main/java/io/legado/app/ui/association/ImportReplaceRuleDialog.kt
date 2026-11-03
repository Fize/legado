package io.legado.app.ui.association

import android.os.Bundle
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import dagger.hilt.android.AndroidEntryPoint
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.ui.replace.ReplaceRuleImportDialog
import io.legado.app.utils.setLayout

/** Compatibility entry point for existing file associations and import intents. */
@AndroidEntryPoint
class ImportReplaceRuleDialog() : BaseComposeDialogFragment() {
    constructor(source: String, finishOnDismiss: Boolean = false) : this() {
        arguments = Bundle().apply {
            putString("source", source)
            putBoolean("finishOnDismiss", finishOnDismiss)
        }
    }

    override fun onStart() {
        super.onStart()
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    @Composable
    override fun DialogContent() {
        ReplaceRuleImportDialog(
            source = arguments?.getString("source").orEmpty(),
            onDismiss = ::dismissAllowingStateLoss,
        )
    }
}
