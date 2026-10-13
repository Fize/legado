package io.legado.app.ui.widget.keyboard

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.KeyboardAssist
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.ThemeStore
import io.legado.app.lib.theme.primaryColor
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.number.NumberPickerDialog
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.postEvent
import io.legado.app.utils.putPrefInt
import io.legado.app.utils.setLayout
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

/**
 * 辅助按键配置
 */
class KeyboardAssistsConfig : BaseDialogFragment() {

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
                    KeyboardAssistsConfigScreen()
                }
            }
        }
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) = Unit

    @Composable
    private fun KeyboardAssistsConfigScreen() {
        val context = requireContext()
        val itemsFlow = remember {
            appDb.keyboardAssistsDao.flowAll.catch {
                AppLog.put("辅助按键配置获取数据失败\n${it.localizedMessage}", it)
            }.flowOn(IO)
        }
        val items by itemsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
        var showBoardLine by remember { mutableIntStateOf(AppConfig.showBoardLine) }
        var editingItem by remember { mutableStateOf<KeyboardAssist?>(null) }
        var showEditDialog by remember { mutableStateOf(false) }

        val topBarBg = Color(primaryColor)
        val topBarContentColor = if (ColorUtils.isColorLight(primaryColor)) {
            Color.Black
        } else {
            Color.White
        }

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color(ThemeStore.backgroundColor(context))
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(topBarBg)
                        .clickable {
                            NumberPickerDialog(context)
                                .setTitle(getString(R.string.setting_show_line_number))
                                .setMaxValue(5)
                                .setMinValue(1)
                                .setValue(showBoardLine)
                                .show { value ->
                                    putPrefInt(PreferKey.showBoardLine, value)
                                    showBoardLine = value
                                    postEvent(PreferKey.showBoardLine, value)
                                }
                        }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.assists_key_config),
                            color = topBarContentColor,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = showBoardLine.toLineStr(context),
                            color = topBarContentColor.copy(alpha = 0.8f),
                            fontSize = 13.sp
                        )
                    }
                    IconButton(
                        onClick = {
                            editingItem = null
                            showEditDialog = true
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = stringResource(R.string.add),
                            tint = topBarContentColor
                        )
                    }
                }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    itemsIndexed(
                        items = items,
                        key = { _, item -> "${item.type}_${item.key}" }
                    ) { index, item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    editingItem = item
                                    showEditDialog = true
                                }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.key,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontSize = 15.sp
                                )
                                if (item.value.isNotEmpty() && item.value != item.key) {
                                    Text(
                                        text = item.value,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                            if (index > 0) {
                                IconButton(
                                    onClick = { swapItems(items, index, index - 1) }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.KeyboardArrowUp,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            if (index < items.lastIndex) {
                                IconButton(
                                    onClick = { swapItems(items, index, index + 1) }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.KeyboardArrowDown,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            IconButton(
                                onClick = {
                                    lifecycleScope.launch(IO) {
                                        appDb.keyboardAssistsDao.delete(item)
                                    }
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = stringResource(R.string.delete),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }

        if (showEditDialog) {
            val target = editingItem
            var keyText by remember(target) { mutableStateOf(target?.key.orEmpty()) }
            var valueText by remember(target) { mutableStateOf(target?.value.orEmpty()) }
            AlertDialog(
                onDismissRequest = { showEditDialog = false },
                title = { Text(text = "辅助按键") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = keyText,
                            onValueChange = { keyText = it },
                            label = { Text("key") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(0.dp))
                        OutlinedTextField(
                            value = valueText,
                            onValueChange = { valueText = it },
                            label = { Text("value") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val newKey = keyText
                            val newValue = valueText
                            showEditDialog = false
                            lifecycleScope.launch(IO) {
                                val newKeyboardAssist = KeyboardAssist(
                                    key = newKey,
                                    value = newValue
                                )
                                if (target == null) {
                                    newKeyboardAssist.serialNo =
                                        appDb.keyboardAssistsDao.maxSerialNo + 1
                                    appDb.keyboardAssistsDao.insert(newKeyboardAssist)
                                } else {
                                    newKeyboardAssist.serialNo = target.serialNo
                                    appDb.keyboardAssistsDao.delete(target)
                                    appDb.keyboardAssistsDao.insert(newKeyboardAssist)
                                }
                            }
                        }
                    ) {
                        Text(text = stringResource(R.string.ok))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showEditDialog = false }) {
                        Text(text = stringResource(R.string.cancel))
                    }
                }
            )
        }
    }

    private fun swapItems(items: List<KeyboardAssist>, fromIndex: Int, toIndex: Int) {
        if (fromIndex !in items.indices || toIndex !in items.indices) return
        val mutable = items.map { it.copy() }.toMutableList()
        val moved = mutable.removeAt(fromIndex)
        mutable.add(toIndex, moved)
        mutable.forEachIndexed { idx, item ->
            item.serialNo = idx + 1
        }
        lifecycleScope.launch(IO) {
            appDb.keyboardAssistsDao.update(*mutable.toTypedArray())
        }
    }

    private fun Int.toLineStr(context: Context): String {
        return context.getString(R.string.show_line_number, this)
    }
}
