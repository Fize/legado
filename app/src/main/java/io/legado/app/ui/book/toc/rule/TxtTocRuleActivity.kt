package io.legado.app.ui.book.toc.rule

import android.annotation.SuppressLint
import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.VMBaseComposeActivity
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.TxtTocRule
import io.legado.app.databinding.DialogEditTextBinding
import io.legado.app.help.DirectLinkUpload
import io.legado.app.help.SourceSharePassphrase
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.sourceSharePassphraseButton
import io.legado.app.ui.association.ImportTxtTocRuleDialog
import io.legado.app.ui.compose.component.LegadoMenuAction
import io.legado.app.ui.compose.component.LegadoSelectActionBar
import io.legado.app.ui.compose.component.LegadoTopAppBar
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.qrcode.QrCodeResult
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
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import java.io.File

class TxtTocRuleActivity : VMBaseComposeActivity<TxtTocRuleViewModel>(),
    TxtTocRuleEditDialog.Callback {

    override val viewModel by viewModels<TxtTocRuleViewModel>()
    private val importTocRuleKey = "tocRuleUrl"

    private var allRulesState by mutableStateOf<List<TxtTocRule>>(emptyList())
    private var searchKeyState by mutableStateOf("")
    private var selectedIdsState by mutableStateOf<Set<Long>>(emptySet())

    private val qrCodeResult = registerForActivityResult(QrCodeResult()) {
        it ?: return@registerForActivityResult
        showDialogFragment(ImportTxtTocRuleDialog(it))
    }
    private val importDoc = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            showDialogFragment(ImportTxtTocRuleDialog(uri.toString()))
        }
    }
    private val exportResult = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            val url = uri.toString()
            alert(R.string.export_success) {
                if (url.isAbsUrl()) {
                    setMessage(DirectLinkUpload.getSummary())
                    sourceSharePassphraseButton(
                        layoutInflater,
                        url,
                        SourceSharePassphrase.Type.TOC_RULE,
                    )
                }
                val alertBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
                    editView.hint = getString(R.string.path)
                    editView.setText(url)
                }
                customView { alertBinding.root }
                okButton {
                    sendToClip(url)
                }
            }
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        initData()
    }

    private fun initData() {
        lifecycleScope.launch {
            appDb.txtTocRuleDao.observeAll().catch {
                AppLog.put("TXT目录规则界面获取数据失败\n${it.localizedMessage}", it)
            }.flowOn(IO).conflate().collect { tocRules ->
                allRulesState = tocRules
                val validIds = tocRules.mapTo(hashSetOf()) { it.id }
                selectedIdsState = selectedIdsState.intersect(validIds)
            }
        }
    }

    @Composable
    override fun Content() {
        val displayedRules = remember(allRulesState, searchKeyState) {
            allRulesState.filterByKeyword(searchKeyState)
        }
        val selectedRules = remember(displayedRules, selectedIdsState) {
            displayedRules.filter { it.id in selectedIdsState }
        }

        Scaffold(
            topBar = {
                LegadoTopAppBar(
                    title = stringResource(R.string.txt_toc_rule),
                    onBack = { finish() },
                    searchQuery = searchKeyState,
                    onSearchQueryChange = { newText ->
                        if (newText != searchKeyState) {
                            selectedIdsState = emptySet()
                        }
                        searchKeyState = newText
                    },
                    menuActions = listOf(
                        LegadoMenuAction(
                            title = stringResource(R.string.add),
                            iconRes = R.drawable.ic_add,
                            showAsAction = true,
                            onClick = { showDialogFragment(TxtTocRuleEditDialog()) },
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
                    totalCount = displayedRules.size,
                    onSelectAllChange = { selectAll ->
                        selectedIdsState = if (selectAll) {
                            displayedRules.mapTo(linkedSetOf()) { it.id }
                        } else {
                            emptySet()
                        }
                    },
                    onRevertSelection = {
                        selectedIdsState = displayedRules.mapNotNullTo(linkedSetOf()) {
                            if (it.id in selectedIdsState) null else it.id
                        }
                    },
                    mainActionText = stringResource(R.string.delete),
                    onMainActionClick = { delSourceDialog(selectedRules) },
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
                                        "exportTxtTocRule.json",
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
                    items = displayedRules,
                    key = { it.id },
                ) { source ->
                    val isSelected = source.id in selectedIdsState
                    var menuExpanded by remember { mutableStateOf(false) }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selectedIdsState = if (isSelected) {
                                    selectedIdsState - source.id
                                } else {
                                    selectedIdsState + source.id
                                }
                            }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = isSelected,
                                onCheckedChange = { checked ->
                                    selectedIdsState = if (checked) {
                                        selectedIdsState + source.id
                                    } else {
                                        selectedIdsState - source.id
                                    }
                                },
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = source.name,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Switch(
                                checked = source.enable,
                                onCheckedChange = { checked ->
                                    viewModel.update(source.copy(enable = checked))
                                },
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            IconButton(
                                onClick = { edit(source) },
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
                                        text = { Text(stringResource(R.string.to_top)) },
                                        onClick = {
                                            menuExpanded = false
                                            toTop(source)
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.to_bottom)) },
                                        onClick = {
                                            menuExpanded = false
                                            toBottom(source)
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = stringResource(R.string.delete),
                                                color = MaterialTheme.colorScheme.error,
                                            )
                                        },
                                        onClick = {
                                            menuExpanded = false
                                            del(source)
                                            selectedIdsState = selectedIdsState - source.id
                                        },
                                    )
                                }
                            }
                        }
                        if (!source.example.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = source.example.orEmpty(),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 12.dp),
                            )
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }
            }
        }
    }

    private fun del(source: TxtTocRule) {
        alert(R.string.draw) {
            setMessage(getString(R.string.sure_del) + "\n" + source.name)
            noButton()
            yesButton {
                viewModel.del(source)
            }
        }
    }

    private fun edit(source: TxtTocRule) {
        showDialogFragment(TxtTocRuleEditDialog(source.id))
    }

    override fun saveTxtTocRule(txtTocRule: TxtTocRule) {
        viewModel.save(txtTocRule)
    }

    private fun toTop(source: TxtTocRule) {
        viewModel.toTop(source)
    }

    private fun toBottom(source: TxtTocRule) {
        viewModel.toBottom(source)
    }

    private fun delSourceDialog(selectedRules: List<TxtTocRule>) {
        alert(titleResource = R.string.draw, messageResource = R.string.sure_del) {
            yesButton { viewModel.del(*selectedRules.toTypedArray()) }
            noButton()
        }
    }

    @SuppressLint("InflateParams")
    private fun showImportDialog() {
        val aCache = ACache.get(cacheDir = false)
        val defaultUrl = "https://gitee.com/fisher52/YueDuJson/raw/master/myTxtChapterRule.json"
        val cacheUrls: MutableList<String> = aCache
            .getAsString(importTocRuleKey)
            ?.splitNotBlank(",")
            ?.toMutableList()
            ?: mutableListOf()
        if (!cacheUrls.contains(defaultUrl)) {
            cacheUrls.add(0, defaultUrl)
        }
        alert(titleResource = R.string.import_on_line) {
            val alertBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
                editView.hint = "url"
                editView.setFilterValues(cacheUrls)
                editView.delCallBack = {
                    cacheUrls.remove(it)
                    aCache.put(importTocRuleKey, cacheUrls.joinToString(","))
                }
            }
            customView { alertBinding.root }
            okButton {
                val text = alertBinding.editView.text?.toString()
                text?.let {
                    if (it.isAbsUrl() && !cacheUrls.contains(it)) {
                        cacheUrls.add(0, it)
                        aCache.put(importTocRuleKey, cacheUrls.joinToString(","))
                    }
                    showDialogFragment(ImportTxtTocRuleDialog(it))
                }
            }
            cancelButton()
        }
    }

    private fun shareSelection(rules: List<TxtTocRule>) {
        if (rules.isEmpty()) return
        viewModel.execute(scope = lifecycleScope) {
            File.createTempFile("txtTocRule_", ".json", cacheDir).apply {
                writeText(GSON.toJson(rules))
            }
        }.onSuccess {
            share(it)
        }.onError {
            toastOnUi(it.stackTraceStr)
        }
    }

}
