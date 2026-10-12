package io.legado.app.ui.book.read.config

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.help.TextSelectMenuConfig
import io.legado.app.lib.theme.primaryColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.secondaryTextColor
import io.legado.app.ui.book.read.TextSelectMenuItem
import io.legado.app.ui.book.read.loadTextSelectMenuConfig
import io.legado.app.ui.book.read.saveTextSelectMenuConfig
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.utils.setLayout

class TextSelectMenuConfigDialog : BaseDialogFragment() {

    private sealed interface RowItem {
        data class Divider(val zone: Int) : RowItem
        data class Entry(val key: String, val titleRes: Int) : RowItem
    }

    private val rows = mutableStateListOf<RowItem>()

    override fun onStart() {
        super.onStart()
        setLayout(0.9f, 0.9f)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LegadoTheme {
                    TextSelectMenuConfigScreen()
                }
            }
        }
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        initData()
    }

    private fun initData() {
        val config = loadTextSelectMenuConfig(requireContext()).normalized()
        rows.clear()
        rows.addAll(rowsOf(toEntries(config.bar), toEntries(config.more)))
    }

    private fun toEntries(keys: List<String>): MutableList<RowItem.Entry> {
        return keys.mapNotNull { key ->
            TextSelectMenuItem.byKey[key]?.let { RowItem.Entry(it.key, it.titleRes) }
        }.toMutableList()
    }

    private fun rowsOf(bar: List<RowItem.Entry>, more: List<RowItem.Entry>): List<RowItem> {
        return buildList(bar.size + more.size + 2) {
            add(RowItem.Divider(ZONE_BAR))
            addAll(bar)
            add(RowItem.Divider(ZONE_MORE))
            addAll(more)
        }
    }

    private fun splitEntries(): Pair<MutableList<RowItem.Entry>, MutableList<RowItem.Entry>> {
        val items = rows.toList()
        val moreIndex = items.indexOfFirst { it is RowItem.Divider && it.zone == ZONE_MORE }
        if (moreIndex < 0) {
            return items.filterIsInstance<RowItem.Entry>().toMutableList() to mutableListOf()
        }
        val bar = items.subList(0, moreIndex).filterIsInstance<RowItem.Entry>().toMutableList()
        val more = items.subList(moreIndex + 1, items.size)
            .filterIsInstance<RowItem.Entry>()
            .toMutableList()
        return bar to more
    }

    private fun commitRows(bar: List<RowItem.Entry>, more: List<RowItem.Entry>) {
        rows.clear()
        rows.addAll(rowsOf(bar, more))
        saveTextSelectMenuConfig(
            requireContext(),
            TextSelectMenuConfig(bar.map { it.key }, more.map { it.key })
        )
    }

    private fun moveAcrossDivider(entry: RowItem.Entry) {
        val (bar, more) = splitEntries()
        if (bar.removeAll { it.key == entry.key }) {
            more.add(0, entry)
        } else {
            more.removeAll { it.key == entry.key }
            bar.add(entry)
        }
        commitRows(bar, more)
    }

    private fun swapRows(srcPosition: Int, targetPosition: Int): Boolean {
        if (srcPosition !in rows.indices || targetPosition !in rows.indices) return false
        if (targetPosition == 0) return false
        val src = rows[srcPosition]
        rows[srcPosition] = rows[targetPosition]
        rows[targetPosition] = src
        return true
    }

    @Composable
    private fun TextSelectMenuConfigScreen() {
        val context = requireContext()
        var showMenu by remember { mutableStateOf(false) }
        val rowHeightPx = with(LocalDensity.current) { 48.dp.toPx() }
        val moreDividerIndex = rows.indexOfFirst { it is RowItem.Divider && it.zone == ZONE_MORE }

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Toolbar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(context.primaryColor))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.text_select_menu_config),
                        color = Color(context.primaryTextColor),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = stringResource(R.string.more_menu),
                                tint = Color(context.primaryTextColor)
                            )
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.restore_default)) },
                                onClick = {
                                    showMenu = false
                                    val config = TextSelectMenuConfig.default()
                                    commitRows(toEntries(config.bar), toEntries(config.more))
                                }
                            )
                        }
                    }
                }

                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    itemsIndexed(
                        items = rows,
                        key = { _, item ->
                            when (item) {
                                is RowItem.Divider -> "divider_${item.zone}"
                                is RowItem.Entry -> "entry_${item.key}"
                            }
                        }
                    ) { index, item ->
                        when (item) {
                            is RowItem.Divider -> {
                                Text(
                                    text = stringResource(
                                        if (item.zone == ZONE_BAR) {
                                            R.string.text_menu_zone_bar
                                        } else {
                                            R.string.text_menu_zone_more
                                        }
                                    ),
                                    color = Color(context.secondaryTextColor),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(
                                            start = 16.dp,
                                            top = 12.dp,
                                            end = 16.dp,
                                            bottom = 4.dp
                                        )
                                )
                            }

                            is RowItem.Entry -> {
                                val inBar = moreDividerIndex < 0 || index < moreDividerIndex
                                var dragAccumulatedY by remember { mutableFloatStateOf(0f) }

                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = 48.dp)
                                            .padding(horizontal = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(40.dp)
                                                .pointerInput(item.key) {
                                                    detectDragGestures(
                                                        onDragStart = { dragAccumulatedY = 0f },
                                                        onDragEnd = {
                                                            val (bar, more) = splitEntries()
                                                            commitRows(bar, more)
                                                        },
                                                        onDragCancel = {
                                                            val (bar, more) = splitEntries()
                                                            commitRows(bar, more)
                                                        },
                                                        onDrag = { change, dragAmount ->
                                                            change.consume()
                                                            dragAccumulatedY += dragAmount.y
                                                            val currentIdx = rows.indexOfFirst {
                                                                it is RowItem.Entry && it.key == item.key
                                                            }
                                                            if (currentIdx >= 0) {
                                                                if (dragAccumulatedY >= rowHeightPx && currentIdx < rows.lastIndex) {
                                                                    if (swapRows(
                                                                            currentIdx,
                                                                            currentIdx + 1
                                                                        )
                                                                    ) {
                                                                        dragAccumulatedY -= rowHeightPx
                                                                    }
                                                                } else if (dragAccumulatedY <= -rowHeightPx && currentIdx > 1) {
                                                                    if (swapRows(
                                                                            currentIdx,
                                                                            currentIdx - 1
                                                                        )
                                                                    ) {
                                                                        dragAccumulatedY += rowHeightPx
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    )
                                                },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                painter = painterResource(R.drawable.ic_menu),
                                                contentDescription = stringResource(R.string.drag_to_reorder),
                                                tint = Color(context.secondaryTextColor),
                                                modifier = Modifier.size(24.dp)
                                            )
                                        }

                                        Text(
                                            text = stringResource(item.titleRes),
                                            color = Color(context.primaryTextColor),
                                            fontSize = 16.sp,
                                            modifier = Modifier
                                                .weight(1f)
                                                .padding(start = 8.dp)
                                        )

                                        IconButton(
                                            onClick = { moveAcrossDivider(item) },
                                            modifier = Modifier.size(40.dp)
                                        ) {
                                            Icon(
                                                painter = painterResource(
                                                    if (inBar) {
                                                        R.drawable.ic_arrow_drop_down
                                                    } else {
                                                        R.drawable.ic_arrow_drop_up
                                                    }
                                                ),
                                                contentDescription = stringResource(
                                                    if (inBar) {
                                                        R.string.move_to_more
                                                    } else {
                                                        R.string.move_to_floating_bar
                                                    }
                                                ),
                                                tint = Color(context.primaryTextColor),
                                                modifier = Modifier.size(24.dp)
                                            )
                                        }
                                    }
                                    HorizontalDivider()
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    companion object {
        private const val ZONE_BAR = 0
        private const val ZONE_MORE = 1
    }
}
