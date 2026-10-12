package io.legado.app.ui.compose.prefs

import android.content.SharedPreferences
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.edit
import io.legado.app.R
import io.legado.app.ui.compose.component.LegadoSingleChoiceDialog
import io.legado.app.ui.compose.component.LegadoTextInputDialog
import io.legado.app.ui.compose.component.combinedClickableCompat
import io.legado.app.ui.widget.image.CoverImageView
import io.legado.app.utils.defaultSharedPreferences
import kotlin.math.roundToInt

@Composable
fun rememberPrefBoolean(key: String, default: Boolean = false): MutableState<Boolean> {
    val context = LocalContext.current
    val prefs = remember(context) { context.defaultSharedPreferences }
    val state = remember(key) { mutableStateOf(prefs.getBoolean(key, default)) }

    DisposableEffect(prefs, key) {
        state.value = prefs.getBoolean(key, default)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { sp, changedKey ->
            if (changedKey == key) {
                state.value = sp.getBoolean(key, default)
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    return remember(state, prefs, key) {
        object : MutableState<Boolean> {
            override var value: Boolean
                get() = state.value
                set(newValue) {
                    state.value = newValue
                    prefs.edit { putBoolean(key, newValue) }
                }

            override fun component1(): Boolean = value
            override fun component2(): (Boolean) -> Unit = { value = it }
        }
    }
}

@Composable
fun rememberPrefString(key: String, default: String = ""): MutableState<String> {
    val context = LocalContext.current
    val prefs = remember(context) { context.defaultSharedPreferences }
    val state = remember(key) { mutableStateOf(prefs.getString(key, default) ?: default) }

    DisposableEffect(prefs, key) {
        state.value = prefs.getString(key, default) ?: default
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { sp, changedKey ->
            if (changedKey == key) {
                state.value = sp.getString(key, default) ?: default
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    return remember(state, prefs, key) {
        object : MutableState<String> {
            override var value: String
                get() = state.value
                set(newValue) {
                    state.value = newValue
                    prefs.edit { putString(key, newValue) }
                }

            override fun component1(): String = value
            override fun component2(): (String) -> Unit = { value = it }
        }
    }
}

@Composable
fun rememberPrefInt(key: String, default: Int = 0): MutableState<Int> {
    val context = LocalContext.current
    val prefs = remember(context) { context.defaultSharedPreferences }
    val state = remember(key) { mutableIntStateOf(prefs.getInt(key, default)) }

    DisposableEffect(prefs, key) {
        state.intValue = prefs.getInt(key, default)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { sp, changedKey ->
            if (changedKey == key) {
                state.intValue = sp.getInt(key, default)
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    return remember(state, prefs, key) {
        object : MutableState<Int> {
            override var value: Int
                get() = state.intValue
                set(newValue) {
                    state.intValue = newValue
                    prefs.edit { putInt(key, newValue) }
                }

            override fun component1(): Int = value
            override fun component2(): (Int) -> Unit = { value = it }
        }
    }
}

@Composable
fun rememberPrefStringSet(key: String, default: Set<String> = emptySet()): MutableState<Set<String>> {
    val context = LocalContext.current
    val prefs = remember(context) { context.defaultSharedPreferences }
    val state = remember(key) {
        mutableStateOf(prefs.getStringSet(key, default)?.toSet() ?: default)
    }

    DisposableEffect(prefs, key) {
        state.value = prefs.getStringSet(key, default)?.toSet() ?: default
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { sp, changedKey ->
            if (changedKey == key) {
                state.value = sp.getStringSet(key, default)?.toSet() ?: default
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    return remember(state, prefs, key) {
        object : MutableState<Set<String>> {
            override var value: Set<String>
                get() = state.value
                set(newValue) {
                    state.value = newValue
                    prefs.edit { putStringSet(key, newValue) }
                }

            override fun component1(): Set<String> = value
            override fun component2(): (Set<String>) -> Unit = { value = it }
        }
    }
}

@Composable
fun PreferenceCategoryHeader(
    title: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 6.dp),
    )
}

@Composable
fun PreferenceItem(
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    @DrawableRes iconRes: Int? = null,
    enabled: Boolean = true,
    selected: Boolean = false,
    trailingContent: (@Composable () -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit = {},
) {
    val containerColor = if (selected) {
        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
    } else {
        Color.Transparent
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(containerColor)
            .combinedClickableCompat(
                enabled = enabled,
                onLongClick = onLongClick,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 12.dp)
            .alpha(if (enabled) 1f else 0.45f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (iconRes != null) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
            Spacer(modifier = Modifier.width(16.dp))
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!summary.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (trailingContent != null) {
            Spacer(modifier = Modifier.width(12.dp))
            Box(contentAlignment = Alignment.CenterEnd) {
                trailingContent()
            }
        }
    }
}

@Composable
fun SwitchPreferenceItem(
    key: String,
    default: Boolean,
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    @DrawableRes iconRes: Int? = null,
    enabled: Boolean = true,
    onValueChange: ((Boolean) -> Boolean)? = null,
) {
    var checked by rememberPrefBoolean(key, default)

    PreferenceItem(
        title = title,
        summary = summary,
        iconRes = iconRes,
        enabled = enabled,
        modifier = modifier,
        onClick = {
            val next = !checked
            val allow = onValueChange?.invoke(next) ?: true
            if (allow) {
                checked = next
            }
        },
        trailingContent = {
            Switch(
                checked = checked,
                enabled = enabled,
                onCheckedChange = { next ->
                    val allow = onValueChange?.invoke(next) ?: true
                    if (allow) {
                        checked = next
                    }
                },
            )
        }
    )
}

@Composable
fun ListPreferenceItem(
    key: String,
    default: String,
    title: String,
    entries: Array<String>,
    entryValues: Array<String>,
    modifier: Modifier = Modifier,
    summaryOverride: String? = null,
    @DrawableRes iconRes: Int? = null,
    enabled: Boolean = true,
    onValueChange: ((String) -> Unit)? = null,
) {
    var currentValue by rememberPrefString(key, default)
    var showDialog by remember { mutableStateOf(false) }

    val selectedIndex = remember(currentValue, entryValues) {
        entryValues.indexOf(currentValue).coerceAtLeast(0)
    }
    val currentLabel = remember(selectedIndex, entries) {
        entries.getOrNull(selectedIndex).orEmpty()
    }

    PreferenceItem(
        title = title,
        summary = summaryOverride ?: currentLabel,
        iconRes = iconRes,
        enabled = enabled,
        modifier = modifier,
        onClick = { showDialog = true },
        trailingContent = if (summaryOverride != null && currentLabel.isNotBlank()) {
            {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Text(
                        text = currentLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
        } else null,
    )

    if (showDialog) {
        LegadoSingleChoiceDialog(
            title = title,
            entries = entries.toList(),
            selectedIndex = selectedIndex,
            onDismiss = { showDialog = false },
            onSelect = { index ->
                val newValue = entryValues.getOrElse(index) { default }
                currentValue = newValue
                onValueChange?.invoke(newValue)
            },
        )
    }
}

@Composable
fun IconListPreferenceItem(
    key: String,
    default: String,
    title: String,
    entries: Array<String>,
    entryValues: Array<String>,
    iconNames: Array<String>,
    modifier: Modifier = Modifier,
    summary: String? = null,
    onValueChange: ((String) -> Unit)? = null,
) {
    val context = LocalContext.current
    var currentValue by rememberPrefString(key, default)
    var showDialog by remember { mutableStateOf(false) }

    val selectedIndex = remember(currentValue, entryValues) {
        entryValues.indexOf(currentValue).coerceAtLeast(0)
    }
    val currentIconRes = remember(currentValue, iconNames) {
        val iconName = iconNames.getOrNull(entryValues.indexOf(currentValue)) ?: default
        context.resources.getIdentifier(iconName, "mipmap", context.packageName)
            .takeIf { it != 0 }
            ?: R.mipmap.ic_launcher
    }

    PreferenceItem(
        title = title,
        summary = summary ?: entries.getOrNull(selectedIndex),
        modifier = modifier,
        onClick = { showDialog = true },
        trailingContent = {
            Icon(
                painter = painterResource(currentIconRes),
                contentDescription = null,
                tint = Color.Unspecified,
                modifier = Modifier.size(36.dp),
            )
        }
    )

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            modifier = Modifier.widthIn(max = 520.dp),
            title = { Text(title) },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    itemsIndexed(entries) { index, label ->
                        val iconName = iconNames.getOrElse(index) { default }
                        val iconRes = context.resources.getIdentifier(
                            iconName,
                            "mipmap",
                            context.packageName
                        ).takeIf { it != 0 } ?: R.mipmap.ic_launcher

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickableCompat(onClick = {
                                    val newValue = entryValues.getOrElse(index) { default }
                                    currentValue = newValue
                                    onValueChange?.invoke(newValue)
                                    showDialog = false
                                })
                                .padding(vertical = 8.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = index == selectedIndex,
                                onClick = null,
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Icon(
                                painter = painterResource(iconRes),
                                contentDescription = null,
                                tint = Color.Unspecified,
                                modifier = Modifier.size(36.dp),
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
fun EditTextPreferenceItem(
    key: String,
    title: String,
    modifier: Modifier = Modifier,
    default: String = "",
    summary: String? = null,
    hint: String = "",
    isPassword: Boolean = false,
    enabled: Boolean = true,
    onValueChange: ((String) -> Unit)? = null,
) {
    var currentValue by rememberPrefString(key, default)
    var showDialog by remember { mutableStateOf(false) }

    val displaySummary = remember(summary, currentValue, isPassword) {
        when {
            summary != null -> summary
            currentValue.isEmpty() -> ""
            isPassword -> "*".repeat(currentValue.length)
            else -> currentValue
        }
    }

    PreferenceItem(
        title = title,
        summary = displaySummary,
        enabled = enabled,
        modifier = modifier,
        onClick = { showDialog = true },
    )

    if (showDialog) {
        LegadoTextInputDialog(
            title = title,
            initialValue = currentValue,
            hint = hint.ifBlank { title },
            isPassword = isPassword,
            onDismiss = { showDialog = false },
            onConfirm = { newValue ->
                currentValue = newValue
                onValueChange?.invoke(newValue)
            }
        )
    }
}

@Composable
fun SeekBarPreferenceItem(
    key: String,
    default: Int,
    min: Int,
    max: Int,
    title: String,
    modifier: Modifier = Modifier,
    step: Int = 1,
    summary: String? = null,
    enabled: Boolean = true,
    onValueChange: ((Int) -> Unit)? = null,
) {
    var currentValue by rememberPrefInt(key, default)
    var sliderPosition by remember(currentValue) {
        mutableIntStateOf(currentValue.coerceIn(min, max))
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp)
            .alpha(if (enabled) 1f else 0.45f),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (!summary.isNullOrBlank()) {
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = sliderPosition.toString(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = sliderPosition.toFloat(),
            enabled = enabled,
            onValueChange = { raw ->
                val stepped = if (step > 1) {
                    val offset = ((raw.roundToInt() - min + step / 2) / step) * step
                    (min + offset).coerceIn(min, max)
                } else {
                    raw.roundToInt().coerceIn(min, max)
                }
                sliderPosition = stepped
            },
            onValueChangeFinished = {
                currentValue = sliderPosition
                onValueChange?.invoke(sliderPosition)
            },
            valueRange = min.toFloat()..max.toFloat(),
        )
    }
}

@Composable
fun CoverPreviewItem(
    refreshTrigger: Any?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterHorizontally),
    ) {
        AndroidView(
            factory = { ctx ->
                CoverImageView(ctx).apply {
                    load(name = "开源阅读", author = "开源阅读LegadoTeam")
                }
            },
            update = { view ->
                refreshTrigger.hashCode()
                view.load(name = "开源阅读", author = "开源阅读LegadoTeam")
            },
            modifier = Modifier.width(108.dp),
        )
        AndroidView(
            factory = { ctx ->
                CoverImageView(ctx).apply {
                    load(name = "开源阅读可以看小说、看漫画、听书", author = "开源阅读")
                }
            },
            update = { view ->
                refreshTrigger.hashCode()
                view.load(name = "开源阅读可以看小说、看漫画、听书", author = "开源阅读")
            },
            modifier = Modifier.width(108.dp),
        )
    }
}
