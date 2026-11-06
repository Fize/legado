package io.legado.app.ui.compose.component

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.layout.PaddingValues
import io.legado.app.help.config.AppConfig

val unifiedSearchTextStyle = TextStyle(fontSize = 16.sp)
val unifiedSearchPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)

val unifiedSearchShape = RoundedCornerShape(24.dp)

@Composable
fun unifiedSearchColors(): TextFieldColors = TextFieldDefaults.colors(
    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent,
    disabledIndicatorColor = Color.Transparent,
    cursorColor = MaterialTheme.colorScheme.primary,
)

@Composable
fun Modifier.compactSearchHeight(): Modifier {
    val scale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    return heightIn(min = 48.dp, max = (48 * scale).dp)
}

@Composable
fun Modifier.unifiedSearchOutline(): Modifier = if (AppConfig.isEInkMode) {
    border(1.dp, MaterialTheme.colorScheme.outline, unifiedSearchShape)
} else this

@Composable
fun SearchEmptyState(onClear: () -> Unit, modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.Column(
        modifier = modifier,
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
    ) {
        androidx.compose.material3.Text(
            androidx.compose.ui.res.stringResource(io.legado.app.R.string.search_session_no_results),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        androidx.compose.material3.TextButton(onClick = onClear) {
            androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(io.legado.app.R.string.clear))
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun CompactSearchTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: @Composable (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    keyboardOptions: androidx.compose.foundation.text.KeyboardOptions = androidx.compose.foundation.text.KeyboardOptions.Default,
    keyboardActions: androidx.compose.foundation.text.KeyboardActions = androidx.compose.foundation.text.KeyboardActions.Default,
) {
    val interaction = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    androidx.compose.foundation.text.BasicTextField(
        value = value, onValueChange = onValueChange, modifier = modifier,
        singleLine = true, textStyle = unifiedSearchTextStyle.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
        interactionSource = interaction, keyboardOptions = keyboardOptions, keyboardActions = keyboardActions,
        decorationBox = { inner ->
            TextFieldDefaults.DecorationBox(
                value = value, innerTextField = inner, enabled = true, singleLine = true,
                visualTransformation = androidx.compose.ui.text.input.VisualTransformation.None,
                interactionSource = interaction, placeholder = placeholder, leadingIcon = leadingIcon,
                trailingIcon = trailingIcon, shape = unifiedSearchShape, colors = unifiedSearchColors(),
                contentPadding = unifiedSearchPadding,
            )
        },
    )
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun CompactSearchTextField(
    value: androidx.compose.ui.text.input.TextFieldValue,
    onValueChange: (androidx.compose.ui.text.input.TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: @Composable (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
) {
    val interaction = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    androidx.compose.foundation.text.BasicTextField(
        value = value, onValueChange = onValueChange, modifier = modifier,
        singleLine = true, textStyle = unifiedSearchTextStyle.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary), interactionSource = interaction,
        decorationBox = { inner ->
            TextFieldDefaults.DecorationBox(
                value = value.text, innerTextField = inner, enabled = true, singleLine = true,
                visualTransformation = androidx.compose.ui.text.input.VisualTransformation.None,
                interactionSource = interaction, placeholder = placeholder, leadingIcon = leadingIcon,
                trailingIcon = trailingIcon, shape = unifiedSearchShape, colors = unifiedSearchColors(),
                contentPadding = unifiedSearchPadding,
            )
        },
    )
}
