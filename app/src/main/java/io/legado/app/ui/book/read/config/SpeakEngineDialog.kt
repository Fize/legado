package io.legado.app.ui.book.read.config

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.viewModels
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.HttpTTS
import io.legado.app.help.DirectLinkUpload
import io.legado.app.help.SourceSharePassphrase
import io.legado.app.help.config.AppConfig
import io.legado.app.help.source.clearSharedGlobalState
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.sourceSharePassphraseButton
import io.legado.app.lib.theme.primaryColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.ui.association.ImportHttpTtsDialog
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.text.AutoCompleteTextView
import io.legado.app.utils.ACache
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.dpToPx
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.isJsonObject
import io.legado.app.utils.sendToClip
import io.legado.app.utils.setLayout
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.splitNotBlank
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import java.io.File

internal fun HttpTTS.hasLoginCapability(): Boolean {
    return !loginUrl.isNullOrBlank() || !loginUi.isNullOrBlank()
}

internal fun HttpTTS.shouldOpenLoginOnSelection(): Boolean {
    return hasLoginCapability()
}

/**
 * tts引擎管理
 */
class SpeakEngineDialog : BaseDialogFragment() {

    private val viewModel: SpeakEngineViewModel by viewModels()
    private val ttsUrlKey = "ttsUrlKey"
    private var ttsEngine by mutableStateOf(ReadAloud.ttsEngine)
    private val callBack: CallBack? get() = parentFragment as? CallBack
    private var currentSelect by mutableIntStateOf(-1)
    private var currentItems: List<HttpTTS> = emptyList()

    private val importDocResult = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            showDialogFragment(ImportHttpTtsDialog(uri.toString()))
        }
    }

    private val exportDirResult = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            val url = uri.toString()
            alert(R.string.export_success) {
                if (url.isAbsUrl()) {
                    setMessage(DirectLinkUpload.getSummary() + "\n\n" + url)
                    sourceSharePassphraseButton(
                        url,
                        SourceSharePassphrase.Type.TTS_RULE,
                    )
                }
                val editView = EditText(requireContext()).apply {
                    hint = getString(R.string.path)
                    setText(url)
                }
                val container = FrameLayout(requireContext()).apply {
                    val pad = 16.dpToPx()
                    setPadding(pad, pad / 2, pad, 0)
                    addView(editView)
                }
                customView { container }
                okButton {
                    requireContext().sendToClip(url)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, 0.9f)
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
                    SpeakEngineScreen()
                }
            }
        }
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) = Unit

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun SpeakEngineScreen() {
        val context = requireContext()
        val httpTtsFlow = remember {
            appDb.httpTTSDao.flowAll().catch {
                AppLog.put("朗读引擎界面获取数据失败\n${it.localizedMessage}", it)
            }.flowOn(IO).conflate()
        }
        val items by httpTtsFlow.collectAsState(initial = emptyList())
        currentItems = items

        var showMenu by remember { mutableStateOf(false) }

        val sysSelectedValue = remember(ttsEngine) {
            if (ttsEngine == null) {
                ""
            } else if (ttsEngine!!.isJsonObject()) {
                GSON.fromJsonObject<SelectItem<String>>(ttsEngine).getOrNull()?.value ?: ""
            } else {
                null
            }
        }

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
                        text = stringResource(R.string.speak_engine),
                        color = Color(context.primaryTextColor),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { clearCache() }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_clear),
                            contentDescription = stringResource(R.string.clear),
                            tint = Color(context.primaryTextColor)
                        )
                    }
                    IconButton(onClick = { showDialogFragment<HttpTtsEditDialog>() }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_add),
                            contentDescription = stringResource(R.string.add),
                            tint = Color(context.primaryTextColor)
                        )
                    }
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
                                text = { Text(stringResource(R.string.import_default_rule)) },
                                onClick = {
                                    showMenu = false
                                    viewModel.importDefault()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.import_local)) },
                                onClick = {
                                    showMenu = false
                                    importDocResult.launch {
                                        mode = HandleFileContract.FILE
                                        allowExtensions = arrayOf("txt", "json")
                                    }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.import_on_line)) },
                                onClick = {
                                    showMenu = false
                                    importAlert()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.export_all)) },
                                onClick = {
                                    showMenu = false
                                    exportDirResult.launch {
                                        mode = HandleFileContract.EXPORT
                                        fileData = HandleFileContract.FileData(
                                            "httpTts.json",
                                            GSON.toJson(currentItems).toByteArray(),
                                            "application/json"
                                        )
                                    }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.export)) },
                                onClick = {
                                    showMenu = false
                                    exportSelected()
                                }
                            )
                        }
                    }
                }

                // Engine list
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    // System default
                    item(key = "sys_default") {
                        val isChecked = sysSelectedValue != null && sysSelectedValue.isEmpty()
                        if (isChecked) currentSelect = -1
                        SysTtsRow(
                            label = "系统默认",
                            selected = isChecked,
                            onClick = { upTts(GSON.toJson(SelectItem("系统默认", ""))) }
                        )
                    }
                    // Other system engines
                    itemsIndexed(
                        items = viewModel.sysEngines,
                        key = { index, engine -> "sys_${engine.name}_$index" }
                    ) { _, engine ->
                        val isChecked = sysSelectedValue == engine.name
                        if (isChecked) currentSelect = -1
                        SysTtsRow(
                            label = engine.label,
                            selected = isChecked,
                            onClick = { upTts(GSON.toJson(SelectItem(engine.label, engine.name))) }
                        )
                    }
                    // HttpTTS items
                    itemsIndexed(
                        items = items,
                        key = { _, item -> item.id }
                    ) { index, httpTTS ->
                        val isChecked = httpTTS.id.toString() == ttsEngine
                        if (isChecked) {
                            currentSelect = index
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = {
                                        val id = httpTTS.id.toString()
                                        upTts(id)
                                        if (httpTTS.shouldOpenLoginOnSelection()) {
                                            startActivity<SourceLoginActivity> {
                                                putExtra("type", "httpTts")
                                                putExtra("key", id)
                                            }
                                        }
                                    },
                                    onLongClick = {
                                        if (httpTTS.hasLoginCapability()) {
                                            val id = httpTTS.id.toString()
                                            startActivity<SourceLoginActivity> {
                                                putExtra("type", "httpTts")
                                                putExtra("key", id)
                                            }
                                        }
                                    }
                                )
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = isChecked,
                                onClick = {
                                    val id = httpTTS.id.toString()
                                    upTts(id)
                                    if (httpTTS.shouldOpenLoginOnSelection()) {
                                        startActivity<SourceLoginActivity> {
                                            putExtra("type", "httpTts")
                                            putExtra("key", id)
                                        }
                                    }
                                }
                            )
                            Text(
                                text = httpTTS.name,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 4.dp)
                            )
                            IconButton(
                                onClick = { showDialogFragment(HttpTtsEditDialog(httpTTS.id)) },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_edit),
                                    contentDescription = stringResource(R.string.edit),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            IconButton(
                                onClick = {
                                    alert(R.string.draw) {
                                        setMessage(getString(R.string.sure_del) + "\n" + httpTTS.name)
                                        noButton()
                                        yesButton {
                                            httpTTS.clearSharedGlobalState()
                                            appDb.httpTTSDao.delete(httpTTS)
                                        }
                                    }
                                },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_outline_delete),
                                    contentDescription = stringResource(R.string.delete),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }

                HorizontalDivider()

                // Bottom footer buttons
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(
                        onClick = {
                            ReadBook.book?.setTtsEngine(ttsEngine)
                            callBack?.upSpeakEngineSummary()
                            ReadAloud.upReadAloudClass()
                            dismissAllowingStateLoss()
                        }
                    ) {
                        Text(stringResource(R.string.book))
                    }
                    Row {
                        TextButton(onClick = { dismissAllowingStateLoss() }) {
                            Text(stringResource(R.string.cancel))
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        TextButton(
                            onClick = {
                                ReadBook.book?.setTtsEngine(null)
                                AppConfig.ttsEngine = ttsEngine
                                callBack?.upSpeakEngineSummary()
                                ReadAloud.upReadAloudClass()
                                dismissAllowingStateLoss()
                            }
                        ) {
                            Text(stringResource(R.string.general))
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun SysTtsRow(
        label: String,
        selected: Boolean,
        onClick: () -> Unit
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = selected,
                onClick = onClick
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp)
            )
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = MaterialTheme.colorScheme.secondaryContainer
            ) {
                Text(
                    text = "SYS",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
    }

    private fun exportSelected() {
        if (currentSelect == -1) {
            toastOnUi(R.string.is_system_tts_no_export)
            return
        }
        val tts = currentItems.getOrNull(currentSelect) ?: return
        exportDirResult.launch {
            mode = HandleFileContract.EXPORT
            fileData = HandleFileContract.FileData(
                "httpTts_${tts.name}.json",
                GSON.toJson(tts).toByteArray(),
                "application/json"
            )
        }
    }

    fun clearCache() {
        execute {
            ReadAloud.upReadAloudClass()
            val ttsFolderPath = "${requireContext().cacheDir.absolutePath}${File.separator}httpTTS${File.separator}"
            FileUtils.listDirsAndFiles(ttsFolderPath)?.forEach {
                FileUtils.delete(it.absolutePath)
            }
            toastOnUi(R.string.clear_cache_success)
        }
    }

    private fun importAlert() {
        val aCache = ACache.get(cacheDir = false)
        val cacheUrls: MutableList<String> = aCache
            .getAsString(ttsUrlKey)
            ?.splitNotBlank(",")
            ?.toMutableList() ?: mutableListOf()
        alert(R.string.import_on_line) {
            val editView = AutoCompleteTextView(requireContext()).apply {
                hint = "url"
                setFilterValues(cacheUrls)
                delCallBack = {
                    cacheUrls.remove(it)
                    aCache.put(ttsUrlKey, cacheUrls.joinToString(","))
                }
            }
            val container = FrameLayout(requireContext()).apply {
                val pad = 16.dpToPx()
                setPadding(pad, pad / 2, pad, 0)
                addView(editView)
            }
            customView { container }
            okButton {
                editView.text?.toString()?.let { url ->
                    if (url.isAbsUrl() && !cacheUrls.contains(url)) {
                        cacheUrls.add(0, url)
                        aCache.put(ttsUrlKey, cacheUrls.joinToString(","))
                    }
                    showDialogFragment(ImportHttpTtsDialog(url))
                }
            }
        }
    }

    private fun upTts(tts: String) {
        ttsEngine = tts
    }

    interface CallBack {
        fun upSpeakEngineSummary()
    }
}
