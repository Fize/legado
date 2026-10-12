package io.legado.app.ui.rulesub

import android.os.Bundle
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseComposeActivity
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.RuleSub
import io.legado.app.ui.association.ImportBookSourceDialog
import io.legado.app.ui.association.ImportReplaceRuleDialog
import io.legado.app.ui.compose.component.LegadoMenuAction
import io.legado.app.ui.compose.component.LegadoTopAppBar
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 规则订阅界面
 */
class RuleSubActivity : BaseComposeActivity() {

    private var ruleSubsState by mutableStateOf<List<RuleSub>>(emptyList())
    private var editingRuleSubState by mutableStateOf<RuleSub?>(null)

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        initData()
    }

    private fun initData() {
        lifecycleScope.launch {
            appDb.ruleSubDao.flowAll().catch {
                AppLog.put("规则订阅界面获取数据失败\n${it.localizedMessage}", it)
            }.flowOn(IO).conflate().collect {
                ruleSubsState = it
            }
        }
    }

    @Composable
    override fun Content() {
        val typeArray = stringArrayResource(R.array.rule_type)

        Scaffold(
            topBar = {
                LegadoTopAppBar(
                    title = stringResource(R.string.rule_subscription),
                    onBack = { finish() },
                    menuActions = listOf(
                        LegadoMenuAction(
                            title = stringResource(R.string.add),
                            iconRes = R.drawable.ic_add,
                            showAsAction = true,
                            onClick = {
                                lifecycleScope.launch {
                                    val order = withContext(IO) { appDb.ruleSubDao.maxOrder + 1 }
                                    editSubscription(RuleSub(customOrder = order))
                                }
                            },
                        ),
                    ),
                )
            },
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .windowInsetsPadding(WindowInsets.navigationBars),
            ) {
                if (ruleSubsState.isEmpty()) {
                    Text(
                        text = stringResource(R.string.rule_sub_empty_msg),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                    )
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(
                            items = ruleSubsState,
                            key = { it.id },
                        ) { item ->
                            var menuExpanded by remember { mutableStateOf(false) }

                            Column {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { openSubscription(item) }
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = item.name,
                                            fontSize = 16.sp,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = typeArray.getOrElse(item.type) { typeArray.firstOrNull().orEmpty() },
                                                fontSize = 12.sp,
                                                color = MaterialTheme.colorScheme.onPrimary,
                                                modifier = Modifier
                                                    .background(
                                                        color = MaterialTheme.colorScheme.primary,
                                                        shape = RoundedCornerShape(4.dp),
                                                    )
                                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = item.url,
                                                fontSize = 12.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.MiddleEllipsis,
                                            )
                                        }
                                    }
                                    IconButton(
                                        onClick = { editSubscription(item) },
                                        modifier = Modifier.size(36.dp),
                                    ) {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_edit),
                                            contentDescription = stringResource(R.string.edit),
                                            modifier = Modifier.size(20.dp),
                                        )
                                    }
                                    Box {
                                        IconButton(
                                            onClick = { menuExpanded = true },
                                            modifier = Modifier.size(36.dp),
                                        ) {
                                            Icon(
                                                painter = painterResource(R.drawable.ic_more_vert),
                                                contentDescription = stringResource(R.string.more_menu),
                                                modifier = Modifier.size(20.dp),
                                            )
                                        }
                                        DropdownMenu(
                                            expanded = menuExpanded,
                                            onDismissRequest = { menuExpanded = false },
                                        ) {
                                            DropdownMenuItem(
                                                text = {
                                                    Text(
                                                        text = stringResource(R.string.delete),
                                                        color = MaterialTheme.colorScheme.error,
                                                    )
                                                },
                                                onClick = {
                                                    menuExpanded = false
                                                    delSubscription(item)
                                                },
                                            )
                                        }
                                    }
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            }
                        }
                    }
                }
            }
        }

        editingRuleSubState?.let { ruleSub ->
            RuleSubEditDialog(
                ruleSub = ruleSub,
                typeArray = typeArray,
                onDismiss = { editingRuleSubState = null },
                onConfirm = { updated ->
                    editingRuleSubState = null
                    saveSubscription(updated)
                },
            )
        }
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun RuleSubEditDialog(
        ruleSub: RuleSub,
        typeArray: Array<String>,
        onDismiss: () -> Unit,
        onConfirm: (RuleSub) -> Unit,
    ) {
        var selectedType by remember(ruleSub) {
            mutableIntStateOf(if (ruleSub.type in typeArray.indices) ruleSub.type else 0)
        }
        var name by remember(ruleSub) { mutableStateOf(ruleSub.name) }
        var url by remember(ruleSub) { mutableStateOf(ruleSub.url) }
        var autoUpdate by remember(ruleSub) { mutableStateOf(ruleSub.autoUpdate) }
        var silentUpdate by remember(ruleSub) { mutableStateOf(ruleSub.silentUpdate) }
        var intervalText by remember(ruleSub) { mutableStateOf(ruleSub.updateInterval.toString()) }

        AlertDialog(
            onDismissRequest = onDismiss,
            modifier = Modifier.widthIn(max = 520.dp),
            title = { Text(stringResource(R.string.rule_subscription)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.book_type),
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        typeArray.forEachIndexed { index, label ->
                            FilterChip(
                                selected = selectedType == index,
                                onClick = { selectedType = index },
                                label = { Text(label) },
                            )
                        }
                    }
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(stringResource(R.string.name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text("Url") },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 3,
                    )
                    FlowRow(
                        verticalArrangement = Arrangement.Center,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = autoUpdate,
                                onCheckedChange = { isChecked ->
                                    autoUpdate = isChecked
                                    if (isChecked && (intervalText.toIntOrNull() ?: 0) == 0) {
                                        intervalText = "24"
                                    } else if (!isChecked) {
                                        intervalText = "0"
                                        silentUpdate = false
                                    }
                                },
                            )
                            Text(stringResource(R.string.auto_update))
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = silentUpdate,
                                enabled = autoUpdate && (intervalText.toIntOrNull() ?: 0) > 0,
                                onCheckedChange = { silentUpdate = it },
                            )
                            Text(stringResource(R.string.silent_update))
                        }
                    }
                    if (autoUpdate) {
                        OutlinedTextField(
                            value = intervalText,
                            onValueChange = { input ->
                                val filtered = input.filter { it.isDigit() }
                                intervalText = filtered
                                if ((filtered.toIntOrNull() ?: 0) == 0) {
                                    silentUpdate = false
                                    autoUpdate = false
                                }
                            },
                            label = {
                                Text(
                                    "${stringResource(R.string.update_interval)} (${stringResource(R.string.time_hour)})"
                                )
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val updated = ruleSub.copy(
                            type = selectedType,
                            name = name,
                            url = url,
                            autoUpdate = autoUpdate,
                            silentUpdate = silentUpdate,
                            updateInterval = intervalText.toIntOrNull() ?: 0,
                        )
                        onConfirm(updated)
                    },
                ) {
                    Text(stringResource(R.string.ok))
                }
            },
        )
    }

    private fun openSubscription(ruleSub: RuleSub) {
        when (ruleSub.type) {
            0 -> showDialogFragment(
                ImportBookSourceDialog(ruleSub.url)
            )
            1 -> toastOnUi("RSS 功能已被精简")
            2 -> showDialogFragment(
                ImportReplaceRuleDialog(ruleSub.url)
            )
        }
    }

    private fun editSubscription(ruleSub: RuleSub) {
        editingRuleSubState = ruleSub
    }

    private fun saveSubscription(ruleSub: RuleSub) {
        lifecycleScope.launch {
            if (ruleSub.url.isBlank()) {
                toastOnUi(getString(R.string.null_url))
                return@launch
            }
            val rs = withContext(IO) {
                appDb.ruleSubDao.findByUrl(ruleSub.url)
            }
            if (rs != null && rs.id != ruleSub.id) {
                toastOnUi("${getString(R.string.url_already)}(${rs.name})")
                return@launch
            }
            withContext(IO) {
                appDb.ruleSubDao.insert(ruleSub)
            }
        }
    }

    private fun delSubscription(ruleSub: RuleSub) {
        lifecycleScope.launch(IO) {
            appDb.ruleSubDao.delete(ruleSub)
        }
    }

}