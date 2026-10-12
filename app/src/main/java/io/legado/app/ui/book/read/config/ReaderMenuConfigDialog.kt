package io.legado.app.ui.book.read.config

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import io.legado.app.help.ReaderMenuConfig
import io.legado.app.lib.theme.primaryColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.secondaryTextColor
import io.legado.app.ui.book.read.ReaderMenuItem
import io.legado.app.ui.book.read.loadReaderMenuConfig
import io.legado.app.ui.book.read.saveReaderMenuConfig
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.utils.setLayout

/** Configure which reader overflow actions stay in the first-level menu. */
class ReaderMenuConfigDialog : BaseDialogFragment() {

    private data class Entry(
        val key: String,
        val titleRes: Int,
        val primary: Boolean
    )

    private val entries = mutableStateListOf<Entry>()

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
                    ReaderMenuConfigScreen()
                }
            }
        }
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        initData()
    }

    private fun initData() {
        val config = loadReaderMenuConfig(requireContext()).normalized()
        val primaryKeys = config.primary.toHashSet()
        val orderedKeys = config.primary + config.more
        entries.clear()
        entries.addAll(
            orderedKeys.mapNotNull { key ->
                ReaderMenuItem.byKey[key]?.let { item ->
                    Entry(item.key, item.titleRes, key in primaryKeys)
                }
            }
        )
    }

    fun setAllPrimary(primary: Boolean) {
        val updated = entries.map { it.copy(primary = primary) }
        entries.clear()
        entries.addAll(updated)
        persist()
    }

    fun reset() {
        entries.clear()
        entries.addAll(
            ReaderMenuConfig.ALL_KEYS.mapNotNull { key ->
                ReaderMenuItem.byKey[key]?.let {
                    Entry(it.key, it.titleRes, true)
                }
            }
        )
        persist()
    }

    fun setPrimary(position: Int, primary: Boolean, persistNow: Boolean) {
        val entry = entries.getOrNull(position) ?: return
        if (entry.primary == primary) return
        if (!persistNow) {
            entries[position] = entry.copy(primary = primary)
            return
        }
        val items = entries.toMutableList().apply {
            removeAt(position)
        }
        val updated = entry.copy(primary = primary)
        val insertion = if (primary) {
            items.indexOfLast { it.primary } + 1
        } else {
            items.size
        }
        items.add(insertion, updated)
        entries.clear()
        entries.addAll(items)
        persist()
    }

    private fun grouped(items: List<Entry>): List<Entry> {
        return items.filter { it.primary } + items.filterNot { it.primary }
    }

    fun regroupAndPersist() {
        val grouped = grouped(entries)
        if (grouped != entries.toList()) {
            entries.clear()
            entries.addAll(grouped)
        }
        persist()
    }

    fun swapWithinGroup(srcPosition: Int, targetPosition: Int): Boolean {
        if (!canSwapWithinGroup(srcPosition, targetPosition)) return false
        val src = entries[srcPosition]
        entries[srcPosition] = entries[targetPosition]
        entries[targetPosition] = src
        return true
    }

    fun canSwapWithinGroup(srcPosition: Int, targetPosition: Int): Boolean {
        val source = entries.getOrNull(srcPosition) ?: return false
        val target = entries.getOrNull(targetPosition) ?: return false
        return source.primary == target.primary
    }

    fun persist() {
        val items = entries.toList()
        saveReaderMenuConfig(
            requireContext(),
            ReaderMenuConfig(
                primary = items.filter { it.primary }.map(Entry::key),
                more = items.filterNot { it.primary }.map(Entry::key)
            )
        )
        (activity as? io.legado.app.ui.book.read.ReadBookActivity)
            ?.refreshReaderMenu()
    }

    @Composable
    private fun ReaderMenuConfigScreen() {
        val context = requireContext()
        var showMenu by remember { mutableStateOf(false) }
        val rowHeightPx = with(LocalDensity.current) { 48.dp.toPx() }

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
                        text = stringResource(R.string.reader_menu_config),
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
                                text = { Text(stringResource(R.string.reader_menu_select_all)) },
                                onClick = {
                                    showMenu = false
                                    setAllPrimary(true)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.reader_menu_select_none)) },
                                onClick = {
                                    showMenu = false
                                    setAllPrimary(false)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.restore_default)) },
                                onClick = {
                                    showMenu = false
                                    reset()
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
                        items = entries,
                        key = { _, item -> item.key }
                    ) { index, item ->
                        val previous = entries.getOrNull(index - 1)
                        val isFirstInGroup = previous == null || previous.primary != item.primary
                        var dragAccumulatedY by remember { mutableFloatStateOf(0f) }

                        Column(modifier = Modifier.fillMaxWidth()) {
                            if (isFirstInGroup) {
                                Text(
                                    text = stringResource(
                                        if (item.primary) {
                                            R.string.reader_menu_zone_primary
                                        } else {
                                            R.string.reader_menu_zone_more
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

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .clickable {
                                        val currentIdx = entries.indexOfFirst { it.key == item.key }
                                        if (currentIdx >= 0) {
                                            setPrimary(currentIdx, !item.primary, persistNow = true)
                                        }
                                    }
                                    .padding(horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = item.primary,
                                    onCheckedChange = { isChecked ->
                                        val currentIdx = entries.indexOfFirst { it.key == item.key }
                                        if (currentIdx >= 0) {
                                            setPrimary(currentIdx, isChecked, persistNow = true)
                                        }
                                    }
                                )
                                Text(
                                    text = stringResource(item.titleRes),
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.weight(1f)
                                )
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .pointerInput(item.key) {
                                            detectDragGestures(
                                                onDragStart = { dragAccumulatedY = 0f },
                                                onDragEnd = { persist() },
                                                onDragCancel = { persist() },
                                                onDrag = { change, dragAmount ->
                                                    change.consume()
                                                    dragAccumulatedY += dragAmount.y
                                                    val currentIdx =
                                                        entries.indexOfFirst { it.key == item.key }
                                                    if (currentIdx >= 0) {
                                                        if (dragAccumulatedY >= rowHeightPx && currentIdx < entries.lastIndex) {
                                                            if (swapWithinGroup(
                                                                    currentIdx,
                                                                    currentIdx + 1
                                                                )
                                                            ) {
                                                                dragAccumulatedY -= rowHeightPx
                                                            }
                                                        } else if (dragAccumulatedY <= -rowHeightPx && currentIdx > 0) {
                                                            if (swapWithinGroup(
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
                            }
                        }
                    }
                }
            }
        }
    }
}
