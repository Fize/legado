package io.legado.app.ui.compose.component

import androidx.activity.compose.BackHandler

import androidx.annotation.DrawableRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.ProvideTextStyle
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
import androidx.compose.material3.TextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.toMutableStateList
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import io.legado.app.R
import io.legado.app.ui.widget.code.CodeView
import io.legado.app.ui.widget.code.addJsonPattern
import kotlin.math.roundToInt

data class LegadoMenuAction(
    val title: String,
    @DrawableRes val iconRes: Int? = null,
    val iconVector: ImageVector? = null,
    val showAsAction: Boolean = false,
    val checked: Boolean? = null,
    val onClick: () -> Unit,
)

/** Popup layout coordinates include the freeform caption, which is drawn above app popups. */
@Composable
fun LegadoDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    // Plain padding deliberately retains the Activity caption even when its content
    // consumed the inset: this popup has its own layout and scrolling viewport.
    val caption = WindowInsets.captionBar.asPaddingValues()
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier.padding(caption),
        content = content,
    )
}

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
    searchExpandedState: MutableState<Boolean>? = null,
    modernSearch: Boolean = false,
    menuActions: List<LegadoMenuAction> = emptyList(),
    actions: @Composable RowScope.() -> Unit = {},
) {
    val internalSearchExpanded = if (modernSearch) rememberSaveable { mutableStateOf(false) } else remember { mutableStateOf(false) }
    val expandedState: MutableState<Boolean> = searchExpandedState ?: internalSearchExpanded
    var searchExpanded by expandedState
    var overflowExpanded by remember { mutableStateOf(false) }
    BackHandler(enabled = modernSearch && searchExpanded && onSearchQueryChange != null) {
        searchExpanded = false
        onSearchQueryChange?.invoke("")
    }

    val visibleActions = remember(menuActions) { menuActions.filter { it.showAsAction } }
    val overflowActions = remember(menuActions) { menuActions.filter { !it.showAsAction } }

    val renderActions: @Composable RowScope.() -> Unit = {
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
                    IconButton(
                        onClick = { overflowExpanded = true },
                        modifier = Modifier.testTag("legadoTopAppBarOverflow"),
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = stringResource(R.string.more_menu),
                        )
                    }
                    LegadoDropdownMenu(
                        expanded = overflowExpanded,
                        onDismissRequest = { overflowExpanded = false },
                    ) {
                        overflowActions.forEachIndexed { index, item ->
                            DropdownMenuItem(
                                text = { Text(item.title) },
                                modifier = Modifier.testTag("legadoTopAppBarMenuAction_$index").then(
                                    item.checked?.let { checked ->
                                        Modifier.semantics {
                                            role = Role.Checkbox
                                            toggleableState = ToggleableState(checked)
                                        }
                                    } ?: Modifier,
                                ),
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
    val renderTopBar: @Composable () -> Unit = {
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
                }, modifier = Modifier.testTag("legadoTopAppBarNavigation")) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.back),
                    )
                }
            } else if (onNavigationClick != null) {
                IconButton(onClick = onNavigationClick, modifier = Modifier.testTag("legadoTopAppBarNavigation")) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.back),
                    )
                }
            }
        },
        title = {
            if (searchExpanded && onSearchQueryChange != null) {
                if (modernSearch) UnifiedSearchField(
                    query = searchQuery.orEmpty(),
                    onQueryChange = onSearchQueryChange,
                    placeholder = searchPlaceholder,
                    modifier = Modifier.fillMaxWidth().testTag("unified_search_field"),
                    showLeadingIcon = false,
                ) else OutlinedTextField(
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
                IconButton(onClick = { searchExpanded = true }, modifier = Modifier.testTag("legadoTopAppBarSearch")) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = stringResource(R.string.search),
                    )
                }
            }
            if (!(modernSearch && searchExpanded)) renderActions()
        }
    )
    }
    if (modernSearch && searchExpanded) {
        Column {
            renderTopBar()
            Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer), horizontalArrangement = Arrangement.End) { renderActions() }
        }
    } else renderTopBar()
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
    compact: Boolean = false,
) {
    var overflowExpanded by remember { mutableStateOf(false) }
    var compactMenu by remember { mutableStateOf(false) }
    val allSelected = totalCount > 0 && selectedCount >= totalCount
    val selectionDescription = stringResource(R.string.select_all_count, selectedCount, totalCount)

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 3.dp,
    ) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            FlowRow(
                modifier = (if (compact) Modifier.width(IntrinsicSize.Max) else Modifier.fillMaxWidth())
                    .heightIn(min = 56.dp)
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(4.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .padding(end = 8.dp),
                ) {
                    Checkbox(
                        checked = allSelected,
                        onCheckedChange = onSelectAllChange,
                        modifier = if (compact) Modifier.semantics {
                            contentDescription = selectionDescription
                        } else Modifier,
                    )
                    Text(
                        text = stringResource(if (compact) R.string.select_count else R.string.select_all_count, selectedCount, totalCount),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                if (!compact) FilledTonalButton(
                    onClick = onRevertSelection,
                    enabled = selectedCount > 0,
                    modifier = Modifier.padding(horizontal = 4.dp),
                ) {
                    Text(stringResource(R.string.revert_selection))
                }

                if (!compact && mainActionText != null && onMainActionClick != null) {
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
                        IconButton(onClick = { compactMenu = compact; overflowExpanded = true },
                            modifier = Modifier.testTag("selectionOverflow")) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = stringResource(R.string.more_menu),
                            )
                        }
                        LegadoDropdownMenu(
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
                            if (compactMenu) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.revert_selection)) },
                                    enabled = selectedCount > 0,
                                    modifier = Modifier.testTag("selectionCompactInverse"),
                                    onClick = { overflowExpanded = false; onRevertSelection() },
                                )
                                if (mainActionText != null && onMainActionClick != null) {
                                    DropdownMenuItem(
                                        text = { Text(mainActionText) },
                                        enabled = selectedCount > 0,
                                        modifier = Modifier.testTag("selectionCompactMainAction"),
                                        onClick = { overflowExpanded = false; onMainActionClick() },
                                    )
                                }
                            }
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
        modifier = Modifier.widthIn(max = 520.dp).testTag("legadoSingleChoiceDialog"),
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LegadoMultiChoiceDialog(
    title: String,
    entries: List<String>,
    entryValues: List<String>,
    selectedValues: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit,
) {
    val currentSelection = rememberSaveable(selectedValues, saver = listSaver(
        save = { it.toList() },
        restore = { it.toMutableStateList() },
    )) { selectedValues.toMutableStateList() }
    BasicAlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        BoxWithConstraints(
            Modifier.windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 16.dp, vertical = 4.dp).widthIn(max = 520.dp),
        ) {
            Surface(shape = AlertDialogDefaults.shape, color = AlertDialogDefaults.containerColor,
                tonalElevation = AlertDialogDefaults.TonalElevation,
                modifier = Modifier.semantics { paneTitle = title }
                    .testTag("legadoMultiChoiceDialog")) {
                Column(Modifier.heightIn(max = maxHeight)) {
                    // Keep the heading in the scrollable area: at large font sizes it must not
                    // consume the only space in which choices can be seen and touched.
                    LazyColumn(
                        modifier = Modifier.weight(1f, fill = false).heightIn(max = 420.dp),
                        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
                    ) {
                        item(key = "heading") {
                            Text(title, style = MaterialTheme.typography.headlineSmall,
                                modifier = Modifier.padding(bottom = 16.dp).semantics { heading() })
                        }
                        itemsIndexed(entries) { index, label ->
                            val value = entryValues.getOrElse(index) { label }
                            val checked = value in currentSelection
                            Row(
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                    .toggleable(value = checked, role = Role.Checkbox, onValueChange = { selected ->
                                        if (selected) currentSelection.add(value)
                                        else currentSelection.remove(value)
                                    })
                                    .padding(vertical = 10.dp, horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = checked, onCheckedChange = null)
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(text = label, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                        TextButton(onClick = {
                            onConfirm(currentSelection.toSet())
                            onDismiss()
                        }) { Text(stringResource(R.string.ok)) }
                    }
                }
            }
        }
    }
}

/** Keep the same editor node and input connection across changes in IME height. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun LegadoEditorDialog(
    title: String,
    maxWidth: androidx.compose.ui.unit.Dp,
    adaptive: Boolean = true,
    minimumEditorHeight: Dp = 56.dp,
    actionsBelowEditor: Boolean = false,
    additionalButton: (@Composable () -> Unit)? = null,
    editorText: () -> String,
    onDismiss: () -> Unit,
    editor: @Composable (compact: Boolean) -> Unit,
    dismissButton: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
) {
    // Keep the established layout for messages, suggestions and extra actions;
    // those flows need their own compact-window acceptance before opting in.
    if (!adaptive) {
        AlertDialog(
            onDismissRequest = onDismiss,
            modifier = Modifier.widthIn(max = maxWidth),
            title = { Text(title) },
            text = { editor(false) },
            dismissButton = dismissButton,
            confirmButton = confirmButton,
        )
        return
    }
    var recoveryHintShown by rememberSaveable { mutableStateOf(false) }
    var closingImeForRecovery by remember { mutableStateOf(false) }
    val currentDismiss by rememberUpdatedState(onDismiss)
    val currentEditorText by rememberUpdatedState(editorText)
    BasicAlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxSize(),
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false,
            dismissOnClickOutside = false),
    ) {
        // Keep the native input origin fixed while IME insets move the surface.
        // Outside taps still cancel; taps inside keep their original event handling.
        val view = LocalView.current
        val keyboardController = LocalSoftwareKeyboardController.current
        var surfaceBounds by remember { mutableStateOf<Rect?>(null) }
        BoxWithConstraints(Modifier.fillMaxSize().pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                if (surfaceBounds?.contains(down.position) == false) {
                    down.consume()
                    waitForUpOrCancellation()?.let { up -> up.consume(); currentDismiss() }
                }
            }
        }, contentAlignment = Alignment.Center) {
            val density = LocalDensity.current
            val safeInsets = WindowInsets.safeDrawing
            val persistentInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout)
            val imeVisible = WindowInsets.isImeVisible
            val imeInsets = WindowInsets.ime
            val imeBottom = imeInsets.getBottom(density)
            var actionsMinimumHeight by remember(density.density, density.fontScale, constraints.maxWidth, title) {
                mutableIntStateOf(0)
            }
            val minimumBodyHeight = if (actionsBelowEditor) {
                with(density) { minimumEditorHeight.roundToPx() + 4.dp.roundToPx() } + actionsMinimumHeight
            } else maxOf(with(density) { minimumEditorHeight.roundToPx() }, actionsMinimumHeight)
            val availableHeight = constraints.maxHeight - safeInsets.getTop(density) -
                safeInsets.getBottom(density) - with(density) { 8.dp.roundToPx() }
            val imeUnderflow = imeBottom > 0 && availableHeight < minimumBodyHeight
            // Keep the editor measured and connected while the keyboard closes.
            // There is no usable input row in this viewport; don't collapse a native
            // EditText to zero and silently discard keys during the transition.
            // Insets change between composition and measurement. Resolve them at
            // the padding read itself so even that first IME frame cannot measure
            // an editor at zero height before the recovery effect runs.
            val windowHeight = constraints.maxHeight
            val layoutInsets = remember(windowHeight, minimumBodyHeight, safeInsets, persistentInsets, imeInsets) {
                object : WindowInsets {
                    private fun selected(density: Density): WindowInsets {
                        val available = windowHeight - safeInsets.getTop(density) - safeInsets.getBottom(density) -
                            with(density) { 8.dp.roundToPx() }
                        return if (closingImeForRecovery || imeInsets.getBottom(density) > 0 && available < minimumBodyHeight)
                            persistentInsets else safeInsets
                    }
                    override fun getLeft(density: Density, layoutDirection: LayoutDirection) =
                        selected(density).getLeft(density, layoutDirection)
                    override fun getTop(density: Density) = selected(density).getTop(density)
                    override fun getRight(density: Density, layoutDirection: LayoutDirection) =
                        selected(density).getRight(density, layoutDirection)
                    override fun getBottom(density: Density) = selected(density).getBottom(density)
                }
            }
            LaunchedEffect(imeUnderflow, imeVisible, imeBottom, closingImeForRecovery) {
                if (imeUnderflow && imeVisible) {
                    recoveryHintShown = true
                    closingImeForRecovery = true
                    // Hiding while a burst is still being relayed through the IME
                    // can mix queued IME key-downs with direct key-downs. Keep the
                    // input connection/layout alive and close after edits settle.
                    snapshotFlow { currentEditorText() }.collectLatest {
                        // IME input and animations follow the native window clock,
                        // independently of Compose's frame/test clock. Cancellation
                        // removes the timer when another edit or window change arrives.
                        suspendCancellableCoroutine<Unit> { continuation ->
                            val quiet = Runnable { continuation.resumeWith(Result.success(Unit)) }
                            view.postDelayed(quiet, 500)
                            continuation.invokeOnCancellation { view.removeCallbacks(quiet) }
                        }
                        val window = (view as? DialogWindowProvider)?.window
                            ?: generateSequence(view.parent) { it.parent }
                                .filterIsInstance<DialogWindowProvider>().firstOrNull()?.window
                        if (view.findFocus() !is android.widget.EditText && keyboardController != null) {
                            // Coordinate with the Compose text-input command queue so a
                            // pending show request cannot reopen the IME while hiding it.
                            keyboardController.hide()
                        } else if (window != null) {
                            WindowCompat.getInsetsController(window, view).hide(WindowInsetsCompat.Type.ime())
                        } else {
                            view.context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                                ?.hideSoftInputFromWindow(view.windowToken, 0)
                        }
                    }
                } else if (closingImeForRecovery && !imeVisible && imeBottom == 0) {
                    closingImeForRecovery = false
                } else if (imeVisible && !imeUnderflow) {
                    closingImeForRecovery = false
                    recoveryHintShown = false
                }
            }
            BoxWithConstraints(
                Modifier.windowInsetsPadding(layoutInsets)
                    .padding(horizontal = 16.dp, vertical = 4.dp).widthIn(max = maxWidth),
            ) {
                // Large text needs the compact editor even when the window is a
                // little taller than the normal-font threshold. Keep its actions
                // alongside the editor while the keyboard and system bars resize.
                val compact = maxHeight < 240.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
                Surface(modifier = Modifier.testTag("legadoEditorSurface")
                    .onGloballyPositioned { surfaceBounds = it.boundsInRoot() },
                    shape = AlertDialogDefaults.shape, color = AlertDialogDefaults.containerColor,
                    tonalElevation = AlertDialogDefaults.TonalElevation) {
                    ProvideTextStyle(MaterialTheme.typography.bodyMedium) {
                        // Changing Row/Column parents can interrupt an AndroidView's
                        // input connection. Keep all three children in fixed slots.
                        androidx.compose.ui.layout.Layout(
                            // The outer safe-area container already has vertical spacing.
                            // A second compact inset can leave less than one native text
                            // line plus its own padding at 200% font scale.
                            modifier = Modifier.padding(
                                horizontal = if (compact) 4.dp else 24.dp,
                                vertical = if (compact) 0.dp else 24.dp,
                            ),
                            content = {
                                Text(
                                    if (recoveryHintShown) {
                                        val hint = stringResource(R.string.editor_ime_recovery)
                                        if (compact) hint else "$title\n$hint"
                                    } else title,
                                    style = if (recoveryHintShown) MaterialTheme.typography.bodySmall
                                        else if (actionsBelowEditor && compact) MaterialTheme.typography.bodyMedium
                                        else MaterialTheme.typography.headlineSmall,
                                    maxLines = if (recoveryHintShown) 3 else Int.MAX_VALUE,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = if (recoveryHintShown) Modifier.testTag("legadoEditorImeRecovery")
                                        .semantics { liveRegion = LiveRegionMode.Polite } else Modifier,
                                )
                                Box { editor(compact) }
                                val actionsModifier = Modifier.onSizeChanged {
                                    if (it.height > actionsMinimumHeight) actionsMinimumHeight = it.height
                                }
                                if (actionsBelowEditor) {
                                    FlowRow(modifier = actionsModifier,
                                        horizontalArrangement = Arrangement.End,
                                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        additionalButton?.invoke()
                                        dismissButton()
                                        confirmButton()
                                    }
                                } else {
                                    Row(modifier = actionsModifier, verticalAlignment = Alignment.CenterVertically) {
                                        dismissButton()
                                        confirmButton()
                                    }
                                }
                            },
                        ) { children, constraints ->
                            val width = constraints.maxWidth
                            val height = constraints.maxHeight
                            val loose = constraints.copy(minWidth = 0, minHeight = 0)
                            val actions = children[2].measure(loose.copy(maxHeight = Constraints.Infinity))
                            if (compact && !actionsBelowEditor) {
                                val gap = 4.dp.roundToPx()
                                val heading = children[0].measure(if (recoveryHintShown) loose.copy(maxHeight =
                                    (height - maxOf(with(density) { minimumEditorHeight.roundToPx() }, actions.height) - gap).coerceAtLeast(0))
                                else loose.copy(maxWidth = 0, maxHeight = 0))
                                val headingGap = if (heading.height > 0) gap else 0
                                val rowHeight = (height - heading.height - headingGap).coerceAtLeast(0)
                                val input = children[1].measure(loose.copy(
                                    maxWidth = (width - actions.width - gap).coerceAtLeast(0),
                                    maxHeight = rowHeight,
                                ))
                                val usedHeight = (heading.height + headingGap + maxOf(input.height, actions.height))
                                    .coerceIn(constraints.minHeight, height)
                                layout(width, usedHeight) {
                                    if (heading.height > 0) heading.placeRelative(0, 0)
                                    val top = heading.height + headingGap
                                    val bodyHeight = (usedHeight - top).coerceAtLeast(0)
                                    input.placeRelative(0, top + (bodyHeight - input.height) / 2)
                                    actions.placeRelative(width - actions.width, top + (bodyHeight - actions.height) / 2)
                                }
                            } else {
                                val gap = (if (compact) 4.dp else 16.dp).roundToPx()
                                val heading = children[0].measure(loose.copy(maxHeight =
                                    (height - actions.height - gap * 2 - 48.dp.roundToPx()).coerceAtLeast(0)))
                                val input = children[1].measure(loose.copy(maxHeight =
                                    (height - heading.height - actions.height - gap * 2).coerceAtLeast(0)))
                                val usedHeight = (heading.height + input.height + actions.height + gap * 2)
                                    .coerceIn(constraints.minHeight, height)
                                layout(width, usedHeight) {
                                    heading.placeRelative(0, 0)
                                    input.placeRelative(0, heading.height + gap)
                                    actions.placeRelative(width - actions.width, usedHeight - actions.height)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LegadoTextInputDialog(
    title: String,
    initialValue: String,
    message: String? = null,
    hint: String = "",
    isPassword: Boolean = false,
    singleLine: Boolean = true,
    suggestions: List<String> = emptyList(),
    onDeleteSuggestion: ((String) -> Unit)? = null,
    neutralText: String? = null,
    onNeutral: (() -> Unit)? = null,
    actionsBelowEditor: Boolean = false,
    keyboardOptions: KeyboardOptions? = null,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    // History deletion must not replace the dialog window or input session.
    // In particular, Android 17 can drop native focus when that happens.
    val startedWithSuggestions = rememberSaveable(initialValue) { suggestions.isNotEmpty() }
    // Ordinary history fields need the same saved selection/input session as
    // plain fields. The initial history still determines the window layout.
    val stateEditor = singleLine && !isPassword &&
        (actionsBelowEditor || message.isNullOrBlank() && neutralText == null)
    val inputState = rememberSaveable(initialValue, saver = TextFieldState.Saver) { TextFieldState(initialValue) }
    // Suggestions can become empty (or be added) while this dialog is open.
    // Both field variants must edit the same saved draft across that switch.
    val text = inputState.text.toString()
    val inputKeyboardOptions = keyboardOptions ?: if (isPassword) {
        KeyboardOptions(keyboardType = KeyboardType.Password)
    } else {
        KeyboardOptions.Default
    }
    val density = LocalDensity.current
    // Compact fields omit the floating label and reserve the actual input line.
    val minimumInputHeight = if (stateEditor) with(density) {
        maxOf(56.dp, MaterialTheme.typography.bodyLarge.lineHeight.toDp() + 8.dp)
    } else 56.dp
    LegadoEditorDialog(
        title = title,
        maxWidth = 520.dp,
        minimumEditorHeight = minimumInputHeight,
        actionsBelowEditor = actionsBelowEditor,
        additionalButton = if (actionsBelowEditor && neutralText != null && onNeutral != null) ({
            TextButton(onClick = { onNeutral(); onDismiss() }) { Text(neutralText) }
        }) else null,
        editorText = { text },
        adaptive = actionsBelowEditor || singleLine && message.isNullOrBlank() && !startedWithSuggestions && neutralText == null,
        onDismiss = onDismiss,
        editor = { compact ->
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!message.isNullOrBlank()) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (stateEditor) {
                    // Keep edits in the input session itself. Returning an older
                    // String during an IME/layout transition can overwrite keys
                    // already accepted by the field before recomposition.
                    OutlinedTextField(
                        state = inputState,
                        label = if (!compact && hint.isNotBlank()) ({ Text(hint) }) else null,
                        lineLimits = TextFieldLineLimits.SingleLine,
                        keyboardOptions = inputKeyboardOptions,
                        contentPadding = if (compact) PaddingValues(horizontal = 16.dp, vertical = 4.dp)
                            else OutlinedTextFieldDefaults.contentPadding(),
                        modifier = Modifier.fillMaxWidth().then(if (compact) Modifier.semantics {
                            contentDescription = hint.ifBlank { title }
                        } else Modifier),
                    )
                } else OutlinedTextField(
                    value = text,
                    onValueChange = { value -> inputState.edit { replace(0, length, value) } },
                    label = if (hint.isNotBlank()) ({ Text(hint) }) else null,
                    singleLine = singleLine,
                    visualTransformation = if (isPassword) {
                        PasswordVisualTransformation()
                    } else {
                        VisualTransformation.None
                    },
                    keyboardOptions = inputKeyboardOptions,
                    modifier = Modifier.fillMaxWidth(),
                )
                suggestions
                    .asSequence()
                    .filter { text.isBlank() || it.contains(text, ignoreCase = true) }
                    .take(6)
                    .forEach { suggestion ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickableCompat(onClick = {
                                    inputState.edit { replace(0, length, suggestion) }
                                })
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = suggestion,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (onDeleteSuggestion != null) {
                                IconButton(onClick = { onDeleteSuggestion(suggestion) }) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = stringResource(R.string.delete),
                                    )
                                }
                            }
                        }
                    }
            }
        },
        dismissButton = {
            if (actionsBelowEditor) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            } else {
                Row {
                    if (neutralText != null && onNeutral != null) {
                        TextButton(onClick = {
                            onNeutral()
                            onDismiss()
                        }) {
                            Text(neutralText)
                        }
                    }
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm(inputState.text.toString())
                onDismiss()
            }) {
                Text(stringResource(R.string.ok))
            }
        },
    )
}

@Composable
fun LegadoCodeInputDialog(
    title: String,
    initialValue: String,
    hint: String = "",
    jsonSyntax: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by rememberSaveable(initialValue) { mutableStateOf(initialValue) }
    val density = LocalDensity.current
    var minimumNativeHeight by remember(density.density, density.fontScale) { mutableStateOf(48.dp) }
    LegadoEditorDialog(
        title = title,
        maxWidth = 720.dp,
        minimumEditorHeight = minimumNativeHeight,
        editorText = { text },
        onDismiss = onDismiss,
        editor = { compact ->
            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = if (compact) 48.dp else 180.dp, max = 520.dp),
                factory = { context ->
                    CodeView(context).apply {
                        this.hint = if (compact) title else hint
                        keepSelectionVisible = true
                        // IME resizing can finish after the selection's own scroll
                        // request. Recheck it against the final native viewport.
                        addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                                requestSelectionVisible()
                            }
                        }
                        // Keep the structured editor and its actions together on
                        // older landscape IMEs instead of entering extract mode.
                        imeOptions = imeOptions or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
                        minLines = if (compact) 1 else 8
                        if (jsonSyntax) addJsonPattern()
                        setText(text)
                        doAfterTextChanged { text = it?.toString().orEmpty() }
                    }
                },
                update = { view ->
                    view.minLines = if (compact) 1 else 8
                    view.hint = if (compact) title else hint
                    val metrics = view.paint.fontMetricsInt
                    val pixels = metrics.descent - metrics.ascent + view.compoundPaddingTop + view.compoundPaddingBottom
                    val required = with(density) { pixels.toDp() }.coerceAtLeast(48.dp)
                    if (minimumNativeHeight != required) minimumNativeHeight = required
                },
            )
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
        LegadoDropdownMenu(
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
fun UnifiedSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    showLeadingIcon: Boolean = true,
) {
    val focusManager = LocalFocusManager.current
    CompactSearchTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text(placeholder, maxLines = 1) },
        leadingIcon = if (showLeadingIcon) ({ Icon(Icons.Default.Search, contentDescription = null) }) else null,
        trailingIcon = if (query.isNotEmpty()) ({
            IconButton(onClick = { onQueryChange("") }) { Icon(Icons.Default.Close, contentDescription = stringResource(R.string.clear)) }
        }) else null,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
        modifier = modifier.compactSearchHeight().unifiedSearchOutline(),
    )
}

@Composable
fun LegadoSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String = stringResource(R.string.search),
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    modernSearch: Boolean = false,
) {
    if (modernSearch) {
        UnifiedSearchField(query, onQueryChange, placeholder,
            modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = if (compact) 0.dp else 4.dp).testTag("legadoSearchField"),
            showLeadingIcon = !compact)
        return
    }
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text(placeholder, maxLines = 1) },
        singleLine = true,
        leadingIcon = if (compact) null else {
            {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = stringResource(R.string.search),
            )
            }
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
            .padding(horizontal = 12.dp, vertical = if (compact) 0.dp else 4.dp)
            .testTag("legadoSearchField"),
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

