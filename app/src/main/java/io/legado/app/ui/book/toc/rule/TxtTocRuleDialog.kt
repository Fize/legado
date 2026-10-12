package io.legado.app.ui.book.toc.rule

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.TxtTocRule
import io.legado.app.lib.dialogs.alert
import io.legado.app.model.localBook.TextFile
import io.legado.app.ui.association.ImportTxtTocRuleDialog
import io.legado.app.ui.compose.component.LegadoMenuAction
import io.legado.app.ui.compose.component.LegadoTopAppBar
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.qrcode.QrCodeResult
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.utils.ACache
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.launch
import io.legado.app.utils.setLayout
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.splitNotBlank
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

/**
 * txt目录规则
 */
class TxtTocRuleDialog() : BaseDialogFragment(),
    TxtTocRuleEditDialog.Callback {

    constructor(tocRegex: String?) : this() {
        arguments = Bundle().apply {
            putString("tocRegex", tocRegex)
        }
    }

    private val importTocRuleKey = "tocRuleUrl"
    private val viewModel: TxtTocRuleViewModel by viewModels()
    var selectedName: String? by mutableStateOf(null)
    private var durRegex: String? = null
    private var allRulesState by mutableStateOf<List<TxtTocRule>>(emptyList())
    private var searchKeyState by mutableStateOf("")
    private var showOnlineImportDialog by mutableStateOf(false)

    private val qrCodeResult = registerForActivityResult(QrCodeResult()) {
        it ?: return@registerForActivityResult
        showDialogFragment(ImportTxtTocRuleDialog(it))
    }
    private val importDoc = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            showDialogFragment(ImportTxtTocRuleDialog(uri.toString()))
        }
    }

    override fun onStart() {
        super.onStart()
        setLayout(0.9f, 0.8f)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        durRegex = arguments?.getString("tocRegex")
        initData()
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LegadoTheme {
                    DialogContent()
                }
            }
        }
    }

    private fun initData() {
        lifecycleScope.launch {
            appDb.txtTocRuleDao.observeAll().catch {
                AppLog.put("TXT目录规则对话框获取数据失败\n${it.localizedMessage}", it)
            }.flowOn(IO).conflate().collect { tocRules ->
                allRulesState = tocRules
                initSelectedName(tocRules)
            }
        }
    }

    private fun initSelectedName(tocRules: List<TxtTocRule>) {
        if (selectedName == null && durRegex != null) {
            tocRules.forEach {
                if (durRegex == it.rule + TextFile.spaceChars + it.replacement) {
                    selectedName = it.name
                    return@forEach
                }
            }
            if (selectedName == null) {
                selectedName = ""
            }
        }
    }

    override fun saveTxtTocRule(txtTocRule: TxtTocRule) {
        viewModel.save(txtTocRule)
    }

    @Composable
    private fun DialogContent() {
        val displayedRules = remember(allRulesState, searchKeyState) {
            allRulesState.filterByKeyword(searchKeyState)
        }

        Scaffold(
            topBar = {
                LegadoTopAppBar(
                    title = stringResource(R.string.txt_toc_rule),
                    onBack = { dismissAllowingStateLoss() },
                    searchQuery = searchKeyState,
                    onSearchQueryChange = { searchKeyState = it },
                    searchPlaceholder = stringResource(R.string.screen) + " • " + stringResource(R.string.txt_toc_rule),
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
                            onClick = { showOnlineImportDialog = true },
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
                Column {
                    HorizontalDivider()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = { dismissAllowingStateLoss() }) {
                            Text(text = stringResource(R.string.cancel))
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        TextButton(
                            onClick = {
                                allRulesState.forEach { tocRule ->
                                    if (selectedName == tocRule.name) {
                                        val callBack = activity as? CallBack
                                        callBack?.onTocRegexDialogResult(tocRule.rule + TextFile.spaceChars + tocRule.replacement)
                                        dismissAllowingStateLoss()
                                        return@TextButton
                                    }
                                }
                            },
                        ) {
                            Text(text = stringResource(R.string.ok))
                        }
                    }
                }
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
                ) { item ->
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedName = item.name }
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = item.name == selectedName,
                                onClick = { selectedName = item.name },
                            )
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 4.dp),
                            ) {
                                Text(
                                    text = item.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (!item.example.isNullOrBlank()) {
                                    Text(
                                        text = item.example.orEmpty(),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            Switch(
                                checked = item.enable,
                                onCheckedChange = { checked ->
                                    item.enable = checked
                                    viewModel.update(item)
                                },
                            )
                            IconButton(
                                onClick = { showDialogFragment(TxtTocRuleEditDialog(item.id)) },
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Edit,
                                    contentDescription = stringResource(R.string.edit),
                                )
                            }
                            IconButton(
                                onClick = {
                                    alert(R.string.draw) {
                                        setMessage(getString(R.string.sure_del) + "\n" + item.name)
                                        noButton()
                                        yesButton {
                                            viewModel.del(item)
                                        }
                                    }
                                },
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = stringResource(R.string.delete),
                                )
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }

        if (showOnlineImportDialog) {
            val aCache = remember { ACache.get(cacheDir = false) }
            val defaultUrl = "https://gitee.com/fisher52/YueDuJson/raw/master/myTxtChapterRule.json"
            val initialUrls = remember {
                val list = aCache.getAsString(importTocRuleKey)
                    ?.splitNotBlank(",")
                    ?.toMutableList()
                    ?: mutableListOf()
                if (!list.contains(defaultUrl)) {
                    list.add(0, defaultUrl)
                }
                list
            }
            var cacheUrls by remember { mutableStateOf(initialUrls.toList()) }
            var urlInput by remember { mutableStateOf("") }

            AlertDialog(
                onDismissRequest = { showOnlineImportDialog = false },
                title = { Text(text = stringResource(R.string.import_on_line)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = urlInput,
                            onValueChange = { urlInput = it },
                            label = { Text(text = "url") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (cacheUrls.isNotEmpty()) {
                            LazyColumn(modifier = Modifier.heightIn(max = 180.dp)) {
                                items(cacheUrls) { cachedUrl ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { urlInput = cachedUrl }
                                            .padding(vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = cachedUrl,
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.weight(1f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        IconButton(
                                            onClick = {
                                                val updated = cacheUrls.toMutableList().apply { remove(cachedUrl) }
                                                cacheUrls = updated
                                                aCache.put(importTocRuleKey, updated.joinToString(","))
                                            },
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Delete,
                                                contentDescription = stringResource(R.string.delete),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showOnlineImportDialog = false
                            val text = urlInput.trim()
                            if (text.isNotEmpty()) {
                                if (text.isAbsUrl() && !cacheUrls.contains(text)) {
                                    val updated = cacheUrls.toMutableList().apply { add(0, text) }
                                    aCache.put(importTocRuleKey, updated.joinToString(","))
                                }
                                showDialogFragment(ImportTxtTocRuleDialog(text))
                            }
                        },
                    ) {
                        Text(text = stringResource(R.string.ok))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showOnlineImportDialog = false }) {
                        Text(text = stringResource(R.string.cancel))
                    }
                },
            )
        }
    }

    interface CallBack {
        fun onTocRegexDialogResult(tocRegex: String) {}
    }

}
