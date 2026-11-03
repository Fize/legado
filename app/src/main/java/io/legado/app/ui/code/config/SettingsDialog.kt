package io.legado.app.ui.code.config

import android.content.DialogInterface
import android.os.Bundle
import android.view.View
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.rosemoe.sora.widget.CodeEditor.FLAG_DRAW_LINE_SEPARATOR
import io.github.rosemoe.sora.widget.CodeEditor.FLAG_DRAW_WHITESPACE_FOR_EMPTY_LINE
import io.github.rosemoe.sora.widget.CodeEditor.FLAG_DRAW_WHITESPACE_INNER
import io.github.rosemoe.sora.widget.CodeEditor.FLAG_DRAW_WHITESPACE_IN_SELECTION
import io.github.rosemoe.sora.widget.CodeEditor.FLAG_DRAW_WHITESPACE_LEADING
import io.github.rosemoe.sora.widget.CodeEditor.FLAG_DRAW_WHITESPACE_TRAILING
import io.legado.app.R
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.widget.number.NumberPickerDialog
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.putPrefInt

class SettingsDialog : BaseComposeDialogFragment() {

    private val callBack: CallBack get() = requireActivity() as CallBack
    private var initialEditNonPrintable = AppConfig.editNonPrintable
    private var fontSizeState by mutableIntStateOf(AppConfig.editFontScale)
    private var autoCompleteState by mutableStateOf(AppConfig.editAutoComplete)
    private var nonPrintableFlagsState by mutableIntStateOf(AppConfig.editNonPrintable)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedInstanceState?.let {
            initialEditNonPrintable = it.getInt("initialNonPrintable", initialEditNonPrintable)
            nonPrintableFlagsState = it.getInt("pendingNonPrintable", nonPrintableFlagsState)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("initialNonPrintable", initialEditNonPrintable)
        outState.putInt("pendingNonPrintable", nonPrintableFlagsState)
        super.onSaveInstanceState(outState)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) = Unit

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    override fun Content() {
        val flagItems = listOf(
            FLAG_DRAW_WHITESPACE_LEADING to stringResource(R.string.whitespace_leading),
            FLAG_DRAW_WHITESPACE_INNER to stringResource(R.string.whitespace_inner),
            FLAG_DRAW_WHITESPACE_TRAILING to stringResource(R.string.whitespace_trailing),
            FLAG_DRAW_WHITESPACE_FOR_EMPTY_LINE to stringResource(R.string.whitespace_empty),
            FLAG_DRAW_LINE_SEPARATOR to stringResource(R.string.line_separator),
            FLAG_DRAW_WHITESPACE_IN_SELECTION to stringResource(R.string.whitespace_selection)
        )

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.config_settings),
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 18.sp,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                Text(
                    text = fontSizeState.toFontSizeStr(),
                    fontSize = 16.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            NumberPickerDialog(requireContext())
                                .setTitle(getString(R.string.font_scale))
                                .setMaxValue(36)
                                .setMinValue(9)
                                .setValue(AppConfig.editFontScale)
                                .setCustomButton(R.string.btn_default_s) {
                                    putPrefInt(PreferKey.editFontScale, 16)
                                    callBack.upEdit(fontSize = 16)
                                    fontSizeState = 16
                                }
                                .show {
                                    putPrefInt(PreferKey.editFontScale, it)
                                    callBack.upEdit(fontSize = it)
                                    fontSizeState = it
                                }
                        }
                        .padding(vertical = 12.dp)
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val next = !autoCompleteState
                            autoCompleteState = next
                            putPrefBoolean(PreferKey.editAutoComplete, next)
                            callBack.upEdit(autoComplete = next)
                        }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.auto_complete),
                        fontSize = 16.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Checkbox(
                        checked = autoCompleteState,
                        onCheckedChange = { isChecked ->
                            autoCompleteState = isChecked
                            putPrefBoolean(PreferKey.editAutoComplete, isChecked)
                            callBack.upEdit(autoComplete = isChecked)
                        }
                    )
                }

                Text(
                    text = stringResource(R.string.non_printable_set),
                    fontSize = 16.sp,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                )

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    flagItems.forEach { (flag, label) ->
                        val checked = nonPrintableFlagsState and flag != 0
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable {
                                nonPrintableFlagsState = if (checked) {
                                    nonPrintableFlagsState and flag.inv()
                                } else {
                                    nonPrintableFlagsState or flag
                                }
                            }
                        ) {
                            Text(text = label, fontSize = 16.sp)
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { isChecked ->
                                    nonPrintableFlagsState = if (isChecked) {
                                        nonPrintableFlagsState or flag
                                    } else {
                                        nonPrintableFlagsState and flag.inv()
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        val host = activity
        super.onDismiss(dialog)
        // Destroying the old dialog window during recreation is not a user commit.
        if (host != null && !host.isChangingConfigurations &&
            nonPrintableFlagsState != initialEditNonPrintable) {
            putPrefInt(PreferKey.editNonPrintable, nonPrintableFlagsState)
            (host as CallBack).upEdit(editNonPrintable = nonPrintableFlagsState)
        }
    }

    private fun Int.toFontSizeStr(): String {
        return getString(R.string.font_size, this)
    }

    interface CallBack {
        fun upEdit(
            fontSize: Int? = null,
            autoComplete: Boolean? = null,
            autoWarp: Boolean? = null,
            editNonPrintable: Int? = null
        )
    }

}
