package io.legado.app.ui.book.read.config

import android.content.Context
import android.view.KeyEvent
import android.view.ViewGroup
import androidx.activity.ComponentDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.secondaryTextColor
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.utils.getPrefString
import io.legado.app.utils.hideSoftInput
import io.legado.app.utils.putPrefString
import io.legado.app.utils.setLayout

class PageKeyDialog(context: Context) : ComponentDialog(context) {

    private var prevKeysState by mutableStateOf(context.getPrefString(PreferKey.prevKeys).orEmpty())
    private var nextKeysState by mutableStateOf(context.getPrefString(PreferKey.nextKeys).orEmpty())
    private var focusedField: String? = null

    override fun onStart() {
        super.onStart()
        setLayout(0.9f, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    init {
        val composeView = ComposeView(context).apply {
            setContent {
                LegadoTheme {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(context.backgroundColor))
                            .padding(16.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.custom_page_key),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(context.primaryTextColor),
                            modifier = Modifier.padding(5.dp)
                        )
                        OutlinedTextField(
                            value = prevKeysState,
                            onValueChange = { prevKeysState = it },
                            label = { Text(stringResource(R.string.prev_page_key)) },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(5.dp)
                                .onFocusChanged {
                                    if (it.isFocused) focusedField = "prev"
                                }
                        )
                        OutlinedTextField(
                            value = nextKeysState,
                            onValueChange = { nextKeysState = it },
                            label = { Text(stringResource(R.string.next_page_key)) },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(5.dp)
                                .onFocusChanged {
                                    if (it.isFocused) focusedField = "next"
                                }
                        )
                        Text(
                            text = stringResource(R.string.page_key_set_help),
                            color = Color(context.secondaryTextColor),
                            fontSize = 14.sp,
                            modifier = Modifier.padding(5.dp)
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(5.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            OutlinedButton(
                                onClick = {
                                    prevKeysState = ""
                                    nextKeysState = ""
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = stringResource(R.string.reset),
                                    color = Color(context.primaryTextColor)
                                )
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            OutlinedButton(
                                onClick = {
                                    context.putPrefString(PreferKey.prevKeys, prevKeysState)
                                    context.putPrefString(PreferKey.nextKeys, nextKeysState)
                                    dismiss()
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = stringResource(R.string.ok),
                                    color = Color(context.primaryTextColor)
                                )
                            }
                        }
                    }
                }
            }
        }
        setContentView(composeView)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode != KeyEvent.KEYCODE_BACK && keyCode != KeyEvent.KEYCODE_DEL) {
            if (focusedField == "prev") {
                prevKeysState = if (prevKeysState.isEmpty() || prevKeysState.endsWith(",")) {
                    "$prevKeysState$keyCode"
                } else {
                    "$prevKeysState,$keyCode"
                }
                return true
            } else if (focusedField == "next") {
                nextKeysState = if (nextKeysState.isEmpty() || nextKeysState.endsWith(",")) {
                    "$nextKeysState$keyCode"
                } else {
                    "$nextKeysState,$keyCode"
                }
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun dismiss() {
        super.dismiss()
        currentFocus?.hideSoftInput()
    }
}