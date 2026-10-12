package io.legado.app.ui.compose.component

import androidx.annotation.DrawableRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.legado.app.R
import io.legado.app.ui.widget.image.CoverImageView
import kotlin.math.roundToInt

@Composable
fun LegadoBookCover(
    path: String?,
    name: String?,
    author: String?,
    modifier: Modifier = Modifier,
    loadOnlyWifi: Boolean = false,
    sourceOrigin: String? = null,
    onLoadFinish: (() -> Unit)? = null,
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            CoverImageView(context)
        },
        update = { view ->
            view.load(
                path = path,
                name = name,
                author = author,
                loadOnlyWifi = loadOnlyWifi,
                sourceOrigin = sourceOrigin,
                onLoadFinish = onLoadFinish,
            )
        },
    )
}

data class LegadoMenuAction(
    val title: String,
    @DrawableRes val iconRes: Int? = null,
    val iconVector: ImageVector? = null,
    val showAsAction: Boolean = false,
    val checked: Boolean? = null,
    val onClick: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LegadoTopAppBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    onNavigationClick: (() -> Unit)? = onBack,
    searchQuery: String? = null,
    onSearchQueryChange: ((String) -> Unit)? = null,
    searchPlaceholder: String = stringResource(R.string.search),
    menuActions: List<LegadoMenuAction> = emptyList(),
    actions: @Composable RowScope.() -> Unit = {},
) {
    var searchExpanded by remember { mutableStateOf(false) }
    var overflowExpanded by remember { mutableStateOf(false) }

    val visibleActions = remember(menuActions) { menuActions.filter { it.showAsAction } }
    val overflowActions = remember(menuActions) { menuActions.filter { !it.showAsAction } }

    TopAppBar(
        modifier = modifier,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
        ),
        navigationIcon = {
            if (searchExpanded && onSearchQueryChange != null) {
                IconButton(onClick = {
                    searchExpanded = false
                    onSearchQueryChange("")
                }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.back),
                    )
                }
            } else if (onNavigationClick != null) {
                IconButton(onClick = onNavigationClick) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.back),
                    )
                }
            }
        },
        title = {
            if (searchExpanded && onSearchQueryChange != null) {
                OutlinedTextField(
                    value = searchQuery.orEmpty(),
                    onValueChange = onSearchQueryChange,
                    placeholder = { Text(searchPlaceholder, maxLines = 1) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    trailingIcon = {
                        if (!searchQuery.isNullOrEmpty()) {
                            IconButton(onClick = { onSearchQueryChange("") }) {
                                Icon(Icons.Default.Close, contentDescription = null)
                            }
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {}),
                )
            } else {
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!subtitle.isNullOrBlank()) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        },
        actions = {
            if (onSearchQueryChange != null && !searchExpanded) {
                IconButton(onClick = { searchExpanded = true }) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = stringResource(R.string.search),
                    )
                }
            }
            actions()
            visibleActions.forEach { item ->
                IconButton(onClick = item.onClick) {
                    when {
                        item.iconVector != null -> Icon(item.iconVector, contentDescription = item.title)
                        item.iconRes != null -> Icon(
                            painter = painterResource(item.iconRes),
                            contentDescription = item.title,
                            modifier = Modifier.size(22.dp),
                        )
                        else -> Text(item.title, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            if (overflowActions.isNotEmpty()) {
                Box {
                    IconButton(onClick = { overflowExpanded = true }) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = stringResource(R.string.more_menu),
                        )
                    }
                    DropdownMenu(
                        expanded = overflowExpanded,
                        onDismissRequest = { overflowExpanded = false },
                    ) {
                        overflowActions.forEach { item ->
                            DropdownMenuItem(
                                text = { Text(item.title) },
                                leadingIcon = when {
                                    item.iconVector != null -> {
                                        { Icon(item.iconVector, contentDescription = null) }
                                    }
                                    item.iconRes != null -> {
                                        {
                                            Icon(
                                                painter = painterResource(item.iconRes),
                                                contentDescription = null,
                                                modifier = Modifier.size(20.dp),
                                            )
                                        }
                                    }
                                    else -> null
                                },
                                trailingIcon = if (item.checked != null) {
                                    {
                                        Checkbox(
                                            checked = item.checked,
                                            onCheckedChange = null,
                                        )
                                    }
                                } else {
                                    null
                                },
                                onClick = {
                                    overflowExpanded = false
                                    item.onClick()
                                },
                            )
                        }
                    }
                }
            }
        }
    )
}

@Composable
fun LegadoSelectActionBar(
    selectedCount: Int,
    totalCount: Int,
    onSelectAllChange: (Boolean) -> Unit,
    onRevertSelection: () -> Unit,
    mainActionText: String? = null,
    onMainActionClick: (() -> Unit)? = null,
    menuActions: List<LegadoMenuAction> = emptyList(),
    modifier: Modifier = Modifier,
) {
    var overflowExpanded by remember { mutableStateOf(false) }
    val allSelected = totalCount > 0 && selectedCount >= totalCount

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 3.dp,
    ) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp),
                ) {
                    Checkbox(
                        checked = allSelected,
                        onCheckedChange = onSelectAllChange,
                    )
                    Text(
                        text = stringResource(R.string.select_all_count, selectedCount, totalCount),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                FilledTonalButton(
                    onClick = onRevertSelection,
                    enabled = selectedCount > 0,
                    modifier = Modifier.padding(horizontal = 4.dp),
                ) {
                    Text(stringResource(R.string.revert_selection))
                }

                if (mainActionText != null && onMainActionClick != null) {
                    FilledTonalButton(
                        onClick = onMainActionClick,
                        enabled = selectedCount > 0,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    ) {
                        Text(mainActionText)
                    }
                }

                if (menuActions.isNotEmpty()) {
                    Box {
                        IconButton(onClick = { overflowExpanded = true }) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = stringResource(R.string.more_menu),
                            )
                        }
                        DropdownMenu(
                            expanded = overflowExpanded,
                            onDismissRequest = { overflowExpanded = false },
                        ) {
                            menuActions.forEach { action ->
                                DropdownMenuItem(
                                    text = { Text(action.title) },
                                    leadingIcon = action.iconRes?.let { res ->
                                        {
                                            Icon(
                                                painter = painterResource(res),
                                                contentDescription = null,
                                                modifier = Modifier.size(20.dp),
                                            )
                                        }
                                    },
                                    onClick = {
                                        overflowExpanded = false
                                        action.onClick()
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LegadoSingleChoiceDialog(
    title: String,
    entries: List<String>,
    selectedIndex: Int,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.widthIn(max = 520.dp),
        title = { Text(title) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                itemsIndexed(entries) { index, label ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickableCompat(onClick = {
                                onSelect(index)
                                onDismiss()
                            })
                            .padding(vertical = 10.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = index == selectedIndex,
                            onClick = null,
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
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
fun LegadoMultiChoiceDialog(
    title: String,
    entries: List<String>,
    entryValues: List<String>,
    selectedValues: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit,
) {
    val currentSelection = remember(selectedValues) {
        mutableStateListOf<String>().apply { addAll(selectedValues) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.widthIn(max = 520.dp),
        title = { Text(title) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                itemsIndexed(entries) { index, label ->
                    val value = entryValues.getOrElse(index) { label }
                    val checked = value in currentSelection
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickableCompat(onClick = {
                                if (checked) currentSelection.remove(value)
                                else currentSelection.add(value)
                            })
                            .padding(vertical = 10.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = null,
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
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm(currentSelection.toSet())
                onDismiss()
            }) {
                Text(stringResource(R.string.ok))
            }
        },
    )
}

@Composable
fun LegadoTextInputDialog(
    title: String,
    initialValue: String,
    message: String? = null,
    hint: String = "",
    isPassword: Boolean = false,
    singleLine: Boolean = true,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember(initialValue) { mutableStateOf(initialValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.widthIn(max = 520.dp),
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!message.isNullOrBlank()) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = if (hint.isNotBlank()) ({ Text(hint) }) else null,
                    singleLine = singleLine,
                    visualTransformation = if (isPassword) {
                        PasswordVisualTransformation()
                    } else {
                        VisualTransformation.None
                    },
                    keyboardOptions = if (isPassword) {
                        KeyboardOptions(keyboardType = KeyboardType.Password)
                    } else {
                        KeyboardOptions.Default
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm(text)
                onDismiss()
            }) {
                Text(stringResource(R.string.ok))
            }
        },
    )
}

@Composable
fun LegadoOverflowMenuButton(
    content: @Composable (dismiss: () -> Unit) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = stringResource(R.string.more_menu),
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            content { expanded = false }
        }
    }
}

@Composable
fun LegadoDropdownMenuItem(
    text: String,
    @DrawableRes iconRes: Int? = null,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(text) },
        leadingIcon = iconRes?.let { res ->
            {
                Icon(
                    painter = painterResource(res),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
            }
        },
        onClick = onClick,
    )
}

@Composable
fun LegadoNumberPickerDialog(
    title: String,
    currentValue: Int,
    minValue: Int,
    maxValue: Int,
    defaultButtonText: String? = null,
    onDefaultClick: (() -> Unit)? = null,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var textValue by remember(currentValue) { mutableStateOf(currentValue.toString()) }
    var sliderValue by remember(currentValue) {
        mutableIntStateOf(currentValue.coerceIn(minValue, maxValue))
    }
    val useSlider = (maxValue - minValue) in 1..2000

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.widthIn(max = 480.dp),
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = textValue,
                    onValueChange = { input ->
                        textValue = input.filter { it.isDigit() || it == '-' }
                        textValue.toIntOrNull()?.let { parsed ->
                            sliderValue = parsed.coerceIn(minValue, maxValue)
                        }
                    },
                    label = { Text("$minValue ~ $maxValue") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (useSlider) {
                    Slider(
                        value = sliderValue.toFloat(),
                        onValueChange = {
                            sliderValue = it.roundToInt().coerceIn(minValue, maxValue)
                            textValue = sliderValue.toString()
                        },
                        valueRange = minValue.toFloat()..maxValue.toFloat(),
                    )
                }
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (defaultButtonText != null && onDefaultClick != null) {
                    TextButton(onClick = {
                        onDefaultClick()
                        onDismiss()
                    }) {
                        Text(defaultButtonText)
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val finalVal = (textValue.toIntOrNull() ?: sliderValue).coerceIn(minValue, maxValue)
                onConfirm(finalVal)
                onDismiss()
            }) {
                Text(stringResource(R.string.ok))
            }
        },
    )
}

@Composable
fun LegadoSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String = stringResource(R.string.search),
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text(placeholder, maxLines = 1) },
        singleLine = true,
        leadingIcon = {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = stringResource(R.string.search),
            )
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = null,
                    )
                }
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = {}),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Modifier.combinedClickableCompat(
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier = this.combinedClickable(
    enabled = enabled,
    onLongClick = onLongClick,
    onClick = onClick,
)

