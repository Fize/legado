package io.legado.app.ui.dict.rule

import android.annotation.SuppressLint
import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.VMBaseComposeActivity
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.DictRule
import io.legado.app.help.DirectLinkUpload
import io.legado.app.help.SourceSharePassphrase
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.sourceSharePassphraseButton
import io.legado.app.ui.association.ImportDictRuleDialog
import io.legado.app.ui.compose.component.LegadoMenuAction
import io.legado.app.ui.compose.component.LegadoSelectActionBar
import io.legado.app.ui.compose.component.LegadoTopAppBar
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.qrcode.QrCodeResult
import io.legado.app.ui.widget.dialog.ComposeTextInputDialog
import io.legado.app.utils.ACache
import io.legado.app.utils.GSON
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.launch
import io.legado.app.utils.sendToClip
import io.legado.app.utils.share
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.splitNotBlank
import io.legado.app.utils.stackTraceStr
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import java.io.File

class DictRuleActivity : VMBaseComposeActivity<DictRuleViewModel>() {

    override val viewModel by viewModels<DictRuleViewModel>()
    private val importRecordKey = "dictRuleUrls"

    private var rulesState by mutableStateOf<List<DictRule>>(emptyList())
    private var selectedNamesState by mutableStateOf<Set<String>>(emptySet())

    private val qrCodeResult = registerForActivityResult(QrCodeResult()) {
        it ?: return@registerForActivityResult
        showDialogFragment(ImportDictRuleDialog(it))
    }
    private val importDoc = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            showDialogFragment(ImportDictRuleDialog(uri.toString()))
        }
    }
    private val exportResult = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            val url = uri.toString()
            alert(R.string.export_success) {
                if (url.isAbsUrl()) {
                    setMessage(DirectLinkUpload.getSummary() + "\n\n" + url)
                    sourceSharePassphraseButton(
                        url,
                        SourceSharePassphrase.Type.DICT_RULE,
                    )
                }
                if (!url.isAbsUrl()) setMessage(url)
                okButton {
                    sendToClip(url)
                }
            }
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        observeDictRuleData()
    }

    private fun observeDictRuleData() {
        lifecycleScope.launch {
            appDb.dictRuleDao.flowAll().catch {
                AppLog.put("字典规则获取数据失败\n${it.localizedMessage}", it)
            }.flowOn(IO).collect { list ->
                rulesState = list
                val validNames = list.mapTo(hashSetOf()) { it.name }
                selectedNamesState = selectedNamesState.intersect(validNames)
            }
        }
    }

    @Composable
    override fun Content() {
        val selectedRules = remember(rulesState, selectedNamesState) {
            rulesState.filter { it.name in selectedNamesState }
        }

        Scaffold(
            topBar = {
                LegadoTopAppBar(
                    title = stringResource(R.string.dict_rule),
                    onBack = { finish() },
                    menuActions = listOf(
                        LegadoMenuAction(
                            title = stringResource(R.string.create),
                            iconRes = R.drawable.ic_add,
                            showAsAction = true,
                            onClick = { showDialogFragment<DictRuleEditDialog>() },
                        ),
                        LegadoMenuAction(
                            title = stringResource(R.string.import_local),
                            iconRes = R.drawable.ic_import,
                            onClick = {
                                importDoc.launch {
                                    mode = HandleFileContract.FILE
                                    allowExtensions = arrayOf("txt", "json")
                                }
                            },
                        ),
                        LegadoMenuAction(
                            title = stringResource(R.string.import_on_line),
                            iconRes = R.drawable.ic_import,
                            onClick = ::showImportDialog,
                        ),
                        LegadoMenuAction(
                            title = stringResource(R.string.import_by_qr_code),
                            iconRes = R.drawable.ic_import,
                            onClick = { qrCodeResult.launch() },
                        ),
                        LegadoMenuAction(
                            title = stringResource(R.string.import_default_rule),
                            iconRes = R.drawable.ic_import,
                            onClick = { viewModel.importDefault() },
                        ),
                    ),
                )
            },
            bottomBar = {
                LegadoSelectActionBar(
                    modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars),
                    selectedCount = selectedRules.size,
                    totalCount = rulesState.size,
                    onSelectAllChange = { selectAll ->
                        selectedNamesState = if (selectAll) {
                            rulesState.mapTo(linkedSetOf()) { it.name }
                        } else {
                            emptySet()
                        }
                    },
                    onRevertSelection = {
                        selectedNamesState = rulesState.mapNotNullTo(linkedSetOf()) {
                            if (it.name in selectedNamesState) null else it.name
                        }
                    },
                    mainActionText = stringResource(R.string.delete),
                    onMainActionClick = {
                        viewModel.delete(*selectedRules.toTypedArray())
                    },
                    menuActions = listOf(
                        LegadoMenuAction(
                            title = stringResource(R.string.enable_selection),
                            onClick = { viewModel.enableSelection(*selectedRules.toTypedArray()) },
                        ),
                        LegadoMenuAction(
                            title = stringResource(R.string.disable_selection),
                            onClick = { viewModel.disableSelection(*selectedRules.toTypedArray()) },
                        ),
                        LegadoMenuAction(
                            title = stringResource(R.string.export_selection),
                            onClick = {
                                exportResult.launch {
                                    mode = HandleFileContract.EXPORT
                                    fileData = HandleFileContract.FileData(
                                        "exportDictRule.json",
                                        GSON.toJson(selectedRules).toByteArray(),
                                        "application/json",
                                    )
                                }
                            },
                        ),
                        LegadoMenuAction(
                            title = stringResource(R.string.share_selected_source),
                            onClick = { shareSelection(selectedRules) },
                        ),
                    ),
                )
            },
        ) { innerPadding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                items(
                    items = rulesState,
                    key = { it.name },
                ) { rule ->
                    val isSelected = rule.name in selectedNamesState
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedNamesState = if (isSelected) {
                                        selectedNamesState - rule.name
                                    } else {
                                        selectedNamesState + rule.name
                                    }
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = isSelected,
                                onCheckedChange = { checked ->
                                    selectedNamesState = if (checked) {
                                        selectedNamesState + rule.name
                                    } else {
                                        selectedNamesState - rule.name
                                    }
                                },
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = rule.name,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Switch(
                                checked = rule.enabled,
                                onCheckedChange = { checked ->
                                    viewModel.update(rule.copy(enabled = checked))
                                },
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            IconButton(
                                onClick = { edit(rule) },
                                modifier = Modifier.size(36.dp),
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_edit),
                                    contentDescription = stringResource(R.string.edit),
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                            IconButton(
                                onClick = { delete(rule) },
                                modifier = Modifier.size(36.dp),
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_outline_delete),
                                    contentDescription = stringResource(R.string.delete),
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    }
                }
            }
        }
    }

    private fun shareSelection(rules: List<DictRule>) {
        if (rules.isEmpty()) return
        viewModel.execute(scope = lifecycleScope) {
            File.createTempFile("dictRule_", ".json", cacheDir).apply {
                writeText(GSON.toJson(rules))
            }
        }.onSuccess {
            share(it)
        }.onError {
            toastOnUi(it.stackTraceStr)
        }
    }

    private fun delete(rule: DictRule) {
        alert(R.string.draw) {
            setMessage(getString(R.string.sure_del) + "\n" + rule.name)
            noButton()
            yesButton {
                viewModel.delete(rule)
            }
        }
    }

    private fun edit(rule: DictRule) {
        showDialogFragment(DictRuleEditDialog(rule.name))
    }

    private fun showImportDialog() {
        val aCache = ACache.get(cacheDir = false)
        val cacheUrls: MutableList<String> = aCache
            .getAsString(importRecordKey)
            ?.splitNotBlank(",")
            ?.toMutableList() ?: mutableListOf()
        showDialogFragment(
            ComposeTextInputDialog(
                title = getString(R.string.import_on_line),
                hint = "url",
                suggestions = cacheUrls,
                onDeleteSuggestion = {
                    cacheUrls.remove(it)
                    aCache.put(importRecordKey, cacheUrls.joinToString(","))
                },
                onConfirm = { text ->
                    if (text.isAbsUrl() && !cacheUrls.contains(text)) {
                        cacheUrls.add(0, text)
                        aCache.put(importRecordKey, cacheUrls.joinToString(","))
                    }
                    showDialogFragment(ImportDictRuleDialog(text))
                }
            )
        )
    }
}
