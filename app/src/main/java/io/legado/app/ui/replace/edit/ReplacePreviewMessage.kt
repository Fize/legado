package io.legado.app.ui.replace.edit

import androidx.annotation.StringRes
import io.legado.app.R

/** Persist semantic error identity so the current configuration supplies its presentation. */
internal enum class ReplacePreviewMessage(@StringRes val labelId: Int) {
    Error(R.string.replace_preview_error),
    Timeout(R.string.replace_preview_timeout),
    ContextUnavailable(R.string.replace_preview_context_unavailable),
    Javascript(R.string.replace_preview_js_error);

    companion object {
        fun fromKey(key: String): ReplacePreviewMessage = entries.firstOrNull { it.name == key } ?: Error
        fun fromReason(reason: ReplacePreviewException.Reason?): ReplacePreviewMessage = when (reason) {
            ReplacePreviewException.Reason.TIMEOUT -> Timeout
            ReplacePreviewException.Reason.CONTEXT_UNAVAILABLE -> ContextUnavailable
            ReplacePreviewException.Reason.JS_EVALUATION -> Javascript
            null -> Error
        }
    }
}
