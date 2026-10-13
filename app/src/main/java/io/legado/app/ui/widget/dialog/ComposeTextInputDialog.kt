package io.legado.app.ui.widget.dialog

import androidx.compose.runtime.Composable
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.ui.compose.component.LegadoTextInputDialog

class ComposeTextInputDialog(
    private val title: String,
    private val initialValue: String = "",
    private val message: String? = null,
    private val hint: String = "",
    private val singleLine: Boolean = true,
    private val suggestions: List<String> = emptyList(),
    private val onDeleteSuggestion: ((String) -> Unit)? = null,
    private val neutralText: String? = null,
    private val onNeutral: (() -> Unit)? = null,
    private val onConfirm: (String) -> Unit,
) : BaseComposeDialogFragment(adaptationSoftKeyboard = true) {

    @Composable
    override fun Content() {
        LegadoTextInputDialog(
            title = title,
            initialValue = initialValue,
            message = message,
            hint = hint,
            singleLine = singleLine,
            suggestions = suggestions,
            onDeleteSuggestion = onDeleteSuggestion,
            neutralText = neutralText,
            onNeutral = onNeutral,
            onDismiss = { dismissAllowingStateLoss() },
            onConfirm = onConfirm,
        )
    }
}
