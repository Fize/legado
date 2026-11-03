package io.legado.app.ui.widget

import android.content.Context
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.core.view.doOnLayout
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.utils.applyMd3PopupStyle
import io.legado.app.utils.activity
import io.legado.app.utils.resolveDropDownYOffset

/** Shared Compose action popup used by toolbars and the reader. */
class PopupAction(private val context: Context) :
    PopupWindow(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT) {

    var onActionClick: ((action: String) -> Unit)? = null
    var onActionLongClick: ((action: String) -> Unit)? = null
    private var verticalLayout by mutableStateOf(false)
    private var dangerousActions by mutableStateOf<Set<String>>(emptySet())
    private var disabledActions by mutableStateOf<Set<String>>(emptySet())
    private var displayedItems by mutableStateOf<List<PopupActionItem>>(emptyList())

    init {
        contentView = ComposeView(context).apply {
            setContent {
                LegadoTheme {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        tonalElevation = 3.dp,
                        shadowElevation = 6.dp,
                    ) {
                        if (verticalLayout) {
                            Column(
                                modifier = Modifier
                                    .widthIn(min = 112.dp, max = 280.dp)
                                    .padding(vertical = 4.dp),
                            ) {
                                displayedItems.forEach { ActionRow(it) }
                            }
                        } else {
                            ActionFlow()
                        }
                    }
                }
            }
        }
        applyMd3PopupStyle()
        isTouchable = true
        isOutsideTouchable = false
        isFocusable = true
    }

    fun setItems(items: List<SelectItem<String>>) {
        setActionItems(items.map { PopupActionItem(it.title, it.value) })
    }

    fun setActionItems(items: List<PopupActionItem>) {
        displayedItems = items
    }

    fun setVertical(vertical: Boolean) {
        verticalLayout = vertical
    }

    fun setDangerValues(values: Set<String>) {
        dangerousActions = values
    }

    fun setDisabledValues(values: Set<String>) {
        disabledActions = values
    }

    @OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
    @Composable
    private fun ActionFlow() {
        FlowRow(
            modifier = Modifier.padding(5.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            displayedItems.forEach { item ->
                val enabled = item.enabled && item.value !in disabledActions
                Text(
                    text = item.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = itemColor(item, enabled),
                    modifier = Modifier
                        .combinedClickable(
                            enabled = enabled,
                            onClick = { onActionClick?.invoke(item.value) },
                            onLongClick = { onActionLongClick?.invoke(item.value) },
                        )
                        .padding(horizontal = 8.dp, vertical = 7.dp),
                )
            }
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun ActionRow(item: PopupActionItem) {
        val enabled = item.enabled && item.value !in disabledActions
        val color = itemColor(item, enabled)
        val reserveIcon = displayedItems.any { it.icon != null }
        val reserveCheck = displayedItems.any { it.checkable }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .semantics(mergeDescendants = true) {
                    if (item.checkable) {
                        role = Role.Checkbox
                        toggleableState = if (item.checked) ToggleableState.On else ToggleableState.Off
                    }
                }
                .combinedClickable(
                    enabled = enabled,
                    onClick = { onActionClick?.invoke(item.value) },
                    onLongClick = { onActionLongClick?.invoke(item.value) },
                )
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (reserveIcon) {
                if (item.icon != null) {
                    Image(
                        bitmap = item.icon.toBitmap(48, 48).asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        colorFilter = ColorFilter.tint(color),
                    )
                } else {
                    Spacer(Modifier.size(24.dp))
                }
                Spacer(Modifier.width(12.dp))
            }
            Text(
                text = item.title,
                color = color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (reserveCheck) {
                Spacer(Modifier.width(8.dp))
                if (item.checked) {
                    Icon(Icons.Rounded.Check, contentDescription = null, tint = color)
                } else {
                    Spacer(Modifier.size(24.dp))
                }
            }
        }
    }

    @Composable
    private fun itemColor(item: PopupActionItem, enabled: Boolean): Color = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        item.value in dangerousActions -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }

    override fun showAsDropDown(anchor: View?, xoff: Int, yoff: Int, gravity: Int) {
        if (anchor == null) {
            super.showAsDropDown(anchor, xoff, yoff, gravity)
            return
        }
        installOwners(anchor)
        // Attach before measuring so Compose can obtain the popup window recomposer.
        super.showAsDropDown(anchor, xoff, yoff, gravity)
        installOwners(anchor)
        contentView.doOnLayout { popupContent ->
            if (!isShowing) return@doOnLayout
            val visibleFrame = Rect()
            anchor.getWindowVisibleDisplayFrame(visibleFrame)
            val location = IntArray(2)
            anchor.getLocationOnScreen(location)
            val resolvedYOff = resolveDropDownYOffset(
                anchorTop = location[1],
                anchorHeight = anchor.height,
                popupHeight = popupContent.height,
                frameTop = visibleFrame.top,
                frameBottom = visibleFrame.bottom,
                gap = yoff,
            )
            if (resolvedYOff != yoff) {
                update(anchor, xoff, resolvedYOff, -1, -1)
            }
        }
    }

    override fun showAtLocation(parent: View, gravity: Int, x: Int, y: Int) {
        installOwners(parent)
        super.showAtLocation(parent, gravity, x, y)
        installOwners(parent)
    }

    private fun installOwners(anchor: View) {
        val owner = requireNotNull(anchor.activity ?: contentView.activity) {
            "Action popup requires an Activity owner"
        }
        listOf(contentView, contentView.rootView).forEach { root ->
            root.setViewTreeLifecycleOwner(owner)
            root.setViewTreeViewModelStoreOwner(owner)
            root.setViewTreeSavedStateRegistryOwner(owner)
        }
    }

    override fun dismiss() {
        super.dismiss()
        (contentView as? ComposeView)?.disposeComposition()
    }

    data class PopupActionItem(
        val title: String,
        val value: String,
        val icon: Drawable? = null,
        val enabled: Boolean = true,
        val checkable: Boolean = false,
        val checked: Boolean = false,
    )
}
