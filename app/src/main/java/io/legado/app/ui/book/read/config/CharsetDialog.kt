package io.legado.app.ui.book.read.config

import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.legado.app.R
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.constant.AppConst.charsets
import io.legado.app.model.ReadBook
import io.legado.app.ui.compose.component.LegadoTextInputDialog

class CharsetDialog : BaseComposeDialogFragment(adaptationSoftKeyboard = true) {
    @Composable
    override fun Content() {
        LegadoTextInputDialog(
            title = stringResource(R.string.set_charset),
            initialValue = requireArguments().getString(INITIAL_CHARSET).orEmpty(),
            hint = "charset",
            suggestions = charsets,
            onDismiss = { dismissAllowingStateLoss() },
            onConfirm = { ReadBook.setCharset(it) },
        )
    }

    companion object {
        private const val INITIAL_CHARSET = "initialCharset"
        fun create(initialCharset: String) = CharsetDialog().apply {
            arguments = Bundle().apply { putString(INITIAL_CHARSET, initialCharset) }
        }
    }
}
