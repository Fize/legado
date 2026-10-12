package io.legado.app.ui.code

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.help.IntentData
import io.legado.app.ui.code.CurlAnalyzeUrlConverter.ConversionException
import io.legado.app.ui.code.CurlAnalyzeUrlConverter.ErrorReason
import io.legado.app.ui.compose.component.LegadoTopAppBar
import io.legado.app.utils.sendToClip
import io.legado.app.utils.setLayout
import io.legado.app.utils.toastOnUi

class CurlAnalyzeUrlDialog() : BaseComposeDialogFragment(true) {

    companion object {
        private const val ARG_INPUT = "input"
        private const val ARG_CAN_INSERT = "canInsert"
    }

    constructor(input: String, canInsert: Boolean) : this() {
        arguments = Bundle().apply {
            putString(ARG_INPUT, IntentData.put(input))
            putBoolean(ARG_CAN_INSERT, canInsert)
        }
    }

    private var curlToAnalyzeState by mutableStateOf(true)
    private var inputState by mutableStateOf("")
    private var outputState by mutableStateOf("")
    private var insertPending by mutableStateOf(false)

    override fun onStart() {
        super.onStart()
        setLayout(1f, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        if (savedInstanceState == null) {
            val input = IntentData.get<String>(arguments?.getString(ARG_INPUT)).orEmpty()
            inputState = input
            if (input.isNotBlank() && !CurlAnalyzeUrlConverter.looksLikeCurl(input)) {
                curlToAnalyzeState = false
            }
        }
    }

    @Composable
    override fun Content() {
        val canInsert = arguments?.getBoolean(ARG_CAN_INSERT) == true
        val hasOutput = outputState.isNotEmpty()

        Scaffold(
            topBar = {
                LegadoTopAppBar(
                    title = stringResource(R.string.curl_analyze_url_converter),
                    onNavigationClick = { dismiss() },
                    actions = {
                        IconButton(onClick = { convert() }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_swap_horiz),
                                contentDescription = stringResource(R.string.curl_converter_convert)
                            )
                        }
                        IconButton(
                            onClick = { copyOutput() },
                            enabled = hasOutput
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_copy),
                                contentDescription = stringResource(R.string.copy_text)
                            )
                        }
                        if (canInsert) {
                            IconButton(
                                onClick = { insertOutput() },
                                enabled = hasOutput && !insertPending
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = stringResource(R.string.curl_converter_insert)
                                )
                            }
                        }
                    }
                )
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = curlToAnalyzeState,
                        onClick = {
                            if (!curlToAnalyzeState) {
                                curlToAnalyzeState = true
                                outputState = ""
                            }
                        },
                        label = {
                            Text(
                                text = stringResource(R.string.curl_to_analyze_url),
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                    )
                    FilterChip(
                        selected = !curlToAnalyzeState,
                        onClick = {
                            if (curlToAnalyzeState) {
                                curlToAnalyzeState = false
                                outputState = ""
                            }
                        },
                        label = {
                            Text(
                                text = stringResource(R.string.analyze_url_to_curl),
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                    )
                }

                OutlinedTextField(
                    value = inputState,
                    onValueChange = { inputState = it },
                    label = {
                        Text(
                            stringResource(
                                if (curlToAnalyzeState) R.string.curl_input_hint else R.string.analyze_url_input_hint
                            )
                        )
                    },
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )

                OutlinedTextField(
                    value = outputState,
                    onValueChange = {},
                    readOnly = true,
                    label = {
                        Text(
                            stringResource(
                                if (curlToAnalyzeState) R.string.analyze_url_output_hint else R.string.curl_output_hint
                            )
                        )
                    },
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )
            }
        }
    }

    private fun convert() {
        val input = inputState
        try {
            val output = if (curlToAnalyzeState) {
                CurlAnalyzeUrlConverter.curlToAnalyzeUrl(input)
            } else {
                CurlAnalyzeUrlConverter.analyzeUrlToCurl(input)
            }
            outputState = output
        } catch (error: ConversionException) {
            outputState = ""
            toastOnUi(errorMessage(error))
        } catch (_: Exception) {
            outputState = ""
            toastOnUi(R.string.curl_converter_failed)
        }
    }

    private fun copyOutput() {
        val output = outputState
        if (output.isEmpty()) {
            toastOnUi(R.string.curl_converter_no_output)
        } else {
            requireContext().sendToClip(output)
        }
    }

    private fun insertOutput() {
        val output = outputState
        if (output.isEmpty()) {
            toastOnUi(R.string.curl_converter_no_output)
            return
        }
        val callback = parentFragment as? Callback ?: activity as? Callback
        if (callback == null) {
            toastOnUi(R.string.curl_converter_insert_failed)
            return
        }
        insertPending = true
        callback.onCurlAnalyzeUrlInsert(output) { success ->
            if (!isAdded) return@onCurlAnalyzeUrlInsert
            if (success) {
                dismiss()
            } else {
                insertPending = false
                toastOnUi(R.string.curl_converter_insert_failed)
            }
        }
    }

    private fun errorMessage(error: ConversionException): String {
        return when (error.reason) {
            ErrorReason.EMPTY_INPUT -> getString(R.string.curl_converter_empty_input)
            ErrorReason.INVALID_CURL -> getString(R.string.curl_converter_invalid_curl)
            ErrorReason.MISSING_URL -> getString(R.string.curl_converter_missing_url)
            ErrorReason.INVALID_ANALYZE_URL ->
                getString(R.string.curl_converter_invalid_analyze_url)
            ErrorReason.UNSUPPORTED_METHOD ->
                getString(R.string.curl_converter_unsupported_method, error.detail)
            ErrorReason.UNSUPPORTED_OPTION ->
                getString(R.string.curl_converter_unsupported_option, error.detail)
        }
    }

    interface Callback {
        fun onCurlAnalyzeUrlInsert(text: String, onResult: (Boolean) -> Unit)
    }
}
