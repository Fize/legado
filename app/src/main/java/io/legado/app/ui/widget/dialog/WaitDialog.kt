package io.legado.app.ui.widget.dialog

import android.content.Context
import androidx.activity.ComponentDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.lib.theme.accentColor
import io.legado.app.ui.theme.LegadoTheme

@Suppress("unused")
class WaitDialog(context: Context) : ComponentDialog(context) {

    private var messageText by mutableStateOf(context.getString(R.string.loading))

    init {
        setCanceledOnTouchOutside(false)
        setContentView(
            ComposeView(context).apply {
                setContent {
                    LegadoTheme {
                        Row(
                            modifier = Modifier
                                .background(colorResource(R.color.background))
                                .padding(20.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(30.dp),
                                color = Color(context.accentColor),
                                strokeWidth = 3.dp
                            )
                            Text(
                                text = messageText,
                                color = colorResource(R.color.primaryText),
                                modifier = Modifier.padding(6.dp)
                            )
                        }
                    }
                }
            }
        )
    }

    fun setText(text: String): WaitDialog {
        messageText = text
        return this
    }

    fun setText(res: Int): WaitDialog {
        messageText = context.getString(res)
        return this
    }

}