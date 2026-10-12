package io.legado.app.ui.widget.dialog

import android.content.Context
import android.os.Bundle
import android.view.ViewGroup
import androidx.activity.ComponentDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.constant.AppConst
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.utils.GSON
import io.legado.app.utils.setLayout

class UrlOptionDialog(context: Context, private val success: (String) -> Unit) : ComponentDialog(context) {

    private var useWebViewChecked by mutableStateOf(false)
    private var methodText by mutableStateOf("")
    private var charsetText by mutableStateOf("")
    private var headersText by mutableStateOf("")
    private var bodyText by mutableStateOf("")
    private var typeText by mutableStateOf("")
    private var retryText by mutableStateOf("")
    private var webJsText by mutableStateOf("")
    private var jsText by mutableStateOf("")
    private var bodyJsText by mutableStateOf("")
    private var dnsIpText by mutableStateOf("")

    override fun onStart() {
        super.onStart()
        setLayout(1f, ViewGroup.LayoutParams.MATCH_PARENT)
        window?.setBackgroundDrawableResource(R.color.transparent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(
            ComposeView(context).apply {
                setContent {
                    LegadoTheme {
                        val rootInteraction = remember { MutableInteractionSource() }
                        val cardInteraction = remember { MutableInteractionSource() }

                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clickable(
                                    interactionSource = rootInteraction,
                                    indication = null,
                                    onClick = { dismiss() }
                                )
                                .padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = colorResource(R.color.background_card),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(
                                        interactionSource = cardInteraction,
                                        indication = null,
                                        onClick = {}
                                    )
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(bottom = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = stringResource(R.string.url_option),
                                            fontSize = 18.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = colorResource(R.color.primaryText)
                                        )
                                        Spacer(modifier = Modifier.weight(1f))
                                        TextButton(
                                            onClick = {
                                                success.invoke(GSON.toJson(getUrlOption()))
                                                dismiss()
                                            }
                                        ) {
                                            Text(stringResource(R.string.ok))
                                        }
                                    }

                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .verticalScroll(rememberScrollState())
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier
                                                .clickable { useWebViewChecked = !useWebViewChecked }
                                                .padding(vertical = 4.dp)
                                        ) {
                                            Checkbox(
                                                checked = useWebViewChecked,
                                                onCheckedChange = { useWebViewChecked = it }
                                            )
                                            Text(
                                                text = "useWebView",
                                                color = colorResource(R.color.primaryText)
                                            )
                                        }

                                        UrlOptionAutoCompleteField(
                                            value = methodText,
                                            onValueChange = { methodText = it },
                                            label = "method",
                                            suggestions = listOf("POST", "GET")
                                        )

                                        UrlOptionAutoCompleteField(
                                            value = charsetText,
                                            onValueChange = { charsetText = it },
                                            label = "charset",
                                            suggestions = AppConst.charsets
                                        )

                                        OutlinedTextField(
                                            value = headersText,
                                            onValueChange = { headersText = it },
                                            label = { Text("headers") },
                                            modifier = Modifier.fillMaxWidth()
                                        )

                                        OutlinedTextField(
                                            value = bodyText,
                                            onValueChange = { bodyText = it },
                                            label = { Text("body") },
                                            modifier = Modifier.fillMaxWidth()
                                        )

                                        OutlinedTextField(
                                            value = typeText,
                                            onValueChange = { typeText = it },
                                            label = { Text("type") },
                                            modifier = Modifier.fillMaxWidth()
                                        )

                                        OutlinedTextField(
                                            value = retryText,
                                            onValueChange = { retryText = it },
                                            label = { Text("retry") },
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                            modifier = Modifier.fillMaxWidth()
                                        )

                                        OutlinedTextField(
                                            value = webJsText,
                                            onValueChange = { webJsText = it },
                                            label = { Text("webJs") },
                                            modifier = Modifier.fillMaxWidth()
                                        )

                                        OutlinedTextField(
                                            value = jsText,
                                            onValueChange = { jsText = it },
                                            label = { Text("js") },
                                            modifier = Modifier.fillMaxWidth()
                                        )

                                        OutlinedTextField(
                                            value = bodyJsText,
                                            onValueChange = { bodyJsText = it },
                                            label = { Text("bodyJs") },
                                            modifier = Modifier.fillMaxWidth()
                                        )

                                        OutlinedTextField(
                                            value = dnsIpText,
                                            onValueChange = { dnsIpText = it },
                                            label = { Text("dnsIp") },
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        )
    }

    @Composable
    private fun UrlOptionAutoCompleteField(
        value: String,
        onValueChange: (String) -> Unit,
        label: String,
        suggestions: List<String>
    ) {
        var expanded by remember { mutableStateOf(false) }
        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                label = { Text(label) },
                trailingIcon = {
                    IconButton(onClick = { expanded = !expanded }) {
                        Icon(
                            imageVector = Icons.Default.ArrowDropDown,
                            contentDescription = null
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                suggestions.forEach { item ->
                    DropdownMenuItem(
                        text = { Text(item) },
                        onClick = {
                            onValueChange(item)
                            expanded = false
                        }
                    )
                }
            }
        }
    }

    private fun getUrlOption(): AnalyzeUrl.UrlOption {
        val urlOption = AnalyzeUrl.UrlOption()
        urlOption.useWebView(useWebViewChecked)
        urlOption.setMethod(methodText)
        urlOption.setCharset(charsetText)
        urlOption.setHeaders(headersText)
        urlOption.setBody(bodyText)
        urlOption.setRetry(retryText)
        urlOption.setType(typeText)
        urlOption.setWebJs(webJsText)
        urlOption.setJs(jsText)
        urlOption.setBodyJs(bodyJsText)
        urlOption.setDnsIp(dnsIpText)
        return urlOption
    }

}
