package io.legado.app.ui.book.source.edit

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.EditText
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import io.legado.app.R
import io.legado.app.base.VMBaseComposeActivity
import io.legado.app.constant.BookSourceType
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.rule.BookInfoRule
import io.legado.app.data.entities.rule.ContentRule
import io.legado.app.data.entities.rule.ExploreRule
import io.legado.app.data.entities.rule.ReviewRule
import io.legado.app.data.entities.rule.SearchRule
import io.legado.app.data.entities.rule.TocRule
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.selector
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.book.search.SearchActivity
import io.legado.app.ui.code.CodeEditActivity
import io.legado.app.ui.compose.component.LegadoMenuAction
import io.legado.app.ui.compose.component.LegadoTopAppBar
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.ui.qrcode.QrCodeResult
import io.legado.app.ui.widget.code.EditSafety
import io.legado.app.ui.widget.dialog.UrlOptionDialog
import io.legado.app.ui.widget.dialog.VariableDialog
import io.legado.app.ui.widget.keyboard.KeyboardToolPop
import io.legado.app.ui.widget.text.EditEntity
import io.legado.app.utils.GSON
import io.legado.app.utils.StartActivityContract
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.launch
import io.legado.app.utils.sendToClip
import io.legado.app.utils.share
import io.legado.app.utils.shareWithQr
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BookSourceEditActivity :
    VMBaseComposeActivity<BookSourceEditViewModel>(),
    KeyboardToolPop.CallBack,
    VariableDialog.Callback {

    override val viewModel by viewModels<BookSourceEditViewModel>()

    private val sourceEntities: ArrayList<EditEntity> = ArrayList()
    private val searchEntities: ArrayList<EditEntity> = ArrayList()
    private val exploreEntities: ArrayList<EditEntity> = ArrayList()
    private val infoEntities: ArrayList<EditEntity> = ArrayList()
    private val tocEntities: ArrayList<EditEntity> = ArrayList()
    private val contentEntities: ArrayList<EditEntity> = ArrayList()
    private val reviewEntities: ArrayList<EditEntity> = ArrayList()
    private var redirectJsSourceUrl: String? = null
    private var pendingEditKey: String? = null
    private var pendingEditTabPosition = 0
    private var pendingResultAvailable = false
    private var pendingResultText: String? = null
    private var pendingResultCursor = -1

    private var optionsExpandedState by mutableStateOf(false)
    private var isEnableState by mutableStateOf(true)
    private var isEnableExploreState by mutableStateOf(true)
    private var isEnableCookieState by mutableStateOf(true)
    private var isEnableReviewState by mutableStateOf(false)
    private var isEventListenerState by mutableStateOf(false)
    private var isCustomButtonState by mutableStateOf(false)
    private var selectedTypePositionState by mutableIntStateOf(0)
    private var selectedTabPositionState by mutableIntStateOf(0)
    private var focusedFieldKeyState by mutableStateOf<String?>(null)
    private var editRefreshVersionState by mutableIntStateOf(0)
    private var autoCompleteState by mutableStateOf(false)

    private val fieldSelections = mutableStateMapOf<String, TextRange>()
    private val undoStacks = hashMapOf<String, ArrayDeque<String>>()
    private val redoStacks = hashMapOf<String, ArrayDeque<String>>()

    private val jsSourceEdit = registerForActivityResult(
        StartActivityContract(JsSourceEditActivity::class.java)
    ) { result ->
        setResult(result.resultCode, result.data)
        super.finish()
    }

    private val qrCodeResult = registerForActivityResult(QrCodeResult()) {
        it ?: return@registerForActivityResult
        viewModel.importSource(it) { source ->
            upSourceView(source)
        }
    }
    private val selectDoc = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            if (uri.isContentScheme()) {
                sendText(uri.toString())
            } else {
                sendText(uri.path.toString())
            }
        }
    }

    private val softKeyboardTool by lazy {
        KeyboardToolPop(this, lifecycleScope, window.decorView, this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        pendingEditKey = savedInstanceState?.getString(STATE_PENDING_EDIT_KEY)
        pendingEditTabPosition = savedInstanceState?.getInt(STATE_PENDING_EDIT_TAB) ?: 0
        redirectJsSourceUrl = intent.getStringExtra("sourceUrl")
            ?.takeIf { appDb.bookSourceDao.hasJsSource(it) }
        super.onCreate(savedInstanceState)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_PENDING_EDIT_KEY, pendingEditKey)
        outState.putInt(STATE_PENDING_EDIT_TAB, pendingEditTabPosition)
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        redirectJsSourceUrl?.let { sourceUrl ->
            if (savedInstanceState == null) {
                jsSourceEdit.launch {
                    putExtra("sourceUrl", sourceUrl)
                }
            }
            return
        }
        onBackPressedDispatcher.addCallback(this) { finish() }
        softKeyboardTool.attachToWindow(window)
        initView()
        viewModel.initData(intent) {
            viewModel.bookSource?.takeIf { it.isJsSource() }?.let { source ->
                jsSourceEdit.launch {
                    putExtra("sourceUrl", source.bookSourceUrl)
                }
                return@initData
            }
            autoCompleteState = viewModel.autoComplete
            upSourceView(viewModel.bookSource)
        }
    }

    private val textEditLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                pendingResultAvailable = true
                pendingResultText = result.data?.getStringExtra("text")
                pendingResultCursor = result.data?.getIntExtra("cursorPosition", -1) ?: -1
                applyPendingEditResult()
            } else {
                clearPendingEditResult()
            }
        }

    private fun onFullEditClicked() {
        val key = focusedFieldKeyState
            ?: (window.decorView.findFocus() as? EditText)?.getTag(R.id.tag) as? String
        val editEntity = key?.let {
            findEditEntity(selectedTabPositionState, it)
        }
        if (editEntity != null) {
            val selectionKey = "${selectedTabPositionState}:${editEntity.key}"
            val cursor = fieldSelections[selectionKey]?.start ?: 0
            openTextEditor(editEntity, cursor)
            return
        }
        toastOnUi(R.string.please_focus_cursor_on_textbox)
    }

    private fun openUnsafeTextEditor(editEntity: EditEntity) {
        openTextEditor(editEntity, 0)
    }

    private fun openTextEditor(editEntity: EditEntity, cursorPosition: Int) {
        pendingEditTabPosition = selectedTabPositionState
        pendingEditKey = editEntity.key
        val intent = Intent(this, CodeEditActivity::class.java).apply {
            putExtra("text", editEntity.value.orEmpty())
            putExtra("title", editEntity.hint)
            putExtra("cursorPosition", cursorPosition)
        }
        textEditLauncher.launch(intent)
    }

    private fun findEditEntity(tabPosition: Int, key: String): EditEntity? {
        val entities = entitiesForTab(tabPosition)
        return entities.find { it.key == key }
    }

    private fun entitiesForTab(tabPosition: Int?): ArrayList<EditEntity> {
        return when (tabPosition) {
            1 -> searchEntities
            2 -> exploreEntities
            3 -> infoEntities
            4 -> tocEntities
            5 -> contentEntities
            6 -> reviewEntities
            else -> sourceEntities
        }
    }

    private fun refreshEditedEntity(editEntity: EditEntity, cursorPosition: Int) {
        val selectionKey = "${selectedTabPositionState}:${editEntity.key}"
        if (
            cursorPosition >= 0 &&
            !EditSafety.isCombiningHeavy(editEntity.value.orEmpty()) &&
            !EditSafety.isTooLongForInline(editEntity.value.orEmpty())
        ) {
            val safeCursor = cursorPosition.coerceIn(0, editEntity.value.orEmpty().length)
            fieldSelections[selectionKey] = TextRange(safeCursor)
        }
        editRefreshVersionState++
    }

    private fun saveSource(onSuccess: (BookSource) -> Unit) {
        viewModel.save(getSource()) { source ->
            setResult(RESULT_OK, Intent().putExtra("origin", source.bookSourceUrl))
            onSuccess(source)
        }
    }

    private fun initView() {
        updateOptionPanel(false)
    }

    private fun updateOptionPanel(expanded: Boolean = optionsExpandedState) {
        optionsExpandedState = expanded
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    override fun Content() {
        @Suppress("UNUSED_VARIABLE")
        val refreshVer = editRefreshVersionState
        val currentTab = selectedTabPositionState
        val entities = remember(currentTab, refreshVer) {
            entitiesForTab(currentTab).toList()
        }
        val listState = rememberLazyListState()
        val coroutineScope = rememberCoroutineScope()

        LaunchedEffect(currentTab) {
            listState.scrollToItem(0)
        }

        val hasLogin = remember(refreshVer, currentTab) {
            getSource().hasLogin()
        }

        Scaffold(
            topBar = {
                LegadoTopAppBar(
                    title = stringResource(R.string.edit_book_source),
                    onBack = { finish() },
                    menuActions = buildList {
                        add(
                            LegadoMenuAction(
                                title = stringResource(R.string.edit_content),
                                iconRes = R.drawable.ic_code,
                                showAsAction = true,
                                onClick = { onFullEditClicked() },
                            )
                        )
                        add(
                            LegadoMenuAction(
                                title = stringResource(R.string.action_save),
                                iconRes = R.drawable.ic_save,
                                showAsAction = true,
                                onClick = {
                                    saveSource {
                                        finish()
                                    }
                                },
                            )
                        )
                        if (hasLogin) {
                            add(
                                LegadoMenuAction(
                                    title = stringResource(R.string.login),
                                    onClick = {
                                        saveSource { source ->
                                            startActivity<SourceLoginActivity> {
                                                putExtra("type", "bookSource")
                                                putExtra("key", source.bookSourceUrl)
                                            }
                                        }
                                    },
                                )
                            )
                        }
                        add(
                            LegadoMenuAction(
                                title = stringResource(R.string.search),
                                onClick = {
                                    saveSource { source ->
                                        SearchActivity.start(this@BookSourceEditActivity, source)
                                    }
                                },
                            )
                        )
                        add(
                            LegadoMenuAction(
                                title = stringResource(R.string.cookie),
                                onClick = { viewModel.clearCookie(getSource().bookSourceUrl) },
                            )
                        )
                        add(
                            LegadoMenuAction(
                                title = stringResource(R.string.auto_complete),
                                checked = autoCompleteState,
                                onClick = {
                                    viewModel.autoComplete = !viewModel.autoComplete
                                    autoCompleteState = viewModel.autoComplete
                                },
                            )
                        )
                        add(
                            LegadoMenuAction(
                                title = stringResource(R.string.copy_source),
                                onClick = { sendToClip(GSON.toJson(getSource())) },
                            )
                        )
                        add(
                            LegadoMenuAction(
                                title = stringResource(R.string.paste_source),
                                onClick = { viewModel.pasteSource { upSourceView(it) } },
                            )
                        )
                        add(
                            LegadoMenuAction(
                                title = stringResource(R.string.set_source_variable),
                                onClick = { setSourceVariable() },
                            )
                        )
                        add(
                            LegadoMenuAction(
                                title = stringResource(R.string.import_by_qr_code),
                                onClick = { qrCodeResult.launch() },
                            )
                        )
                        add(
                            LegadoMenuAction(
                                title = stringResource(R.string.qr_share),
                                onClick = {
                                    shareWithQr(
                                        GSON.toJson(getSource()),
                                        getString(R.string.share_book_source),
                                        ErrorCorrectionLevel.L,
                                    )
                                },
                            )
                        )
                        add(
                            LegadoMenuAction(
                                title = stringResource(R.string.str_share),
                                onClick = { share(GSON.toJson(getSource())) },
                            )
                        )
                        add(
                            LegadoMenuAction(
                                title = stringResource(R.string.log),
                                onClick = { showDialogFragment<AppLogDialog>() },
                            )
                        )
                    },
                )
            },
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .imePadding(),
            ) {
                SourceOptionsCardComposable()

                val mainTabs = listOf(
                    R.string.source_tab_base,
                    R.string.source_tab_search,
                    R.string.source_tab_find,
                    R.string.source_tab_info,
                    R.string.source_tab_toc,
                    R.string.source_tab_content,
                    R.string.source_tab_review,
                )
                ScrollableTabRow(
                    selectedTabIndex = currentTab.coerceIn(0, mainTabs.lastIndex),
                    edgePadding = 8.dp,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    mainTabs.forEachIndexed { index, titleRes ->
                        Tab(
                            selected = currentTab == index,
                            onClick = {
                                selectedTabPositionState = index
                                setEditEntities(index)
                            },
                            text = { Text(text = stringResource(titleRes)) },
                        )
                    }
                }

                if (entities.isNotEmpty()) {
                    val activeFieldIndex = listState.firstVisibleItemIndex.coerceIn(0, entities.lastIndex)
                    ScrollableTabRow(
                        selectedTabIndex = activeFieldIndex,
                        edgePadding = 8.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                    ) {
                        entities.forEachIndexed { index, entity ->
                            Tab(
                                selected = activeFieldIndex == index,
                                onClick = {
                                    coroutineScope.launch {
                                        listState.animateScrollToItem(index)
                                    }
                                },
                                text = {
                                    Text(
                                        text = entity.hint,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                            )
                        }
                    }
                }

                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    itemsIndexed(
                        items = entities,
                        key = { _, entity -> "$currentTab:${entity.key}" },
                    ) { _, entity ->
                        EditEntityFieldRow(
                            tabPosition = currentTab,
                            editEntity = entity,
                            refreshVer = refreshVer,
                        )
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun SourceOptionsCardComposable() {
        val typeEntries = stringArrayResource(R.array.book_type)
        val selectedTypeLabel = typeEntries.getOrNull(selectedTypePositionState)
            ?: stringResource(R.string.book_type)
        val summaryParts = buildList {
            add(selectedTypeLabel)
            if (isEnableState) add(stringResource(R.string.is_enable))
            if (isEnableExploreState) add(stringResource(R.string.discovery))
            if (isEnableCookieState) add(stringResource(R.string.auto_save_cookie))
            if (isEnableReviewState) add(stringResource(R.string.review))
            if (isEventListenerState) add(stringResource(R.string.is_event_listener))
            if (isCustomButtonState) add(stringResource(R.string.custom_button))
        }
        val summaryText = summaryParts.joinToString(" | ")
        val actionLabel = stringResource(
            if (optionsExpandedState) R.string.book_intro_collapse else R.string.book_intro_expand
        )
        val headerContentDesc = "${stringResource(R.string.setting)}, $summaryText, $actionLabel"

        var showTypeMenu by remember { mutableStateOf(false) }

        Card(
            shape = RoundedCornerShape(8.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .semantics { contentDescription = headerContentDesc }
                        .clickable { updateOptionPanel(!optionsExpandedState) }
                        .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.setting),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = summaryText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Icon(
                        painter = painterResource(
                            if (optionsExpandedState) R.drawable.ic_expand_less else R.drawable.ic_expand_more
                        ),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(8.dp),
                    )
                }

                if (optionsExpandedState) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.book_type),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Box {
                            OutlinedButton(onClick = { showTypeMenu = true }) {
                                Text(text = selectedTypeLabel)
                            }
                            DropdownMenu(
                                expanded = showTypeMenu,
                                onDismissRequest = { showTypeMenu = false },
                            ) {
                                typeEntries.forEachIndexed { index, entry ->
                                    DropdownMenuItem(
                                        text = { Text(entry) },
                                        onClick = {
                                            selectedTypePositionState = index
                                            showTypeMenu = false
                                        },
                                    )
                                }
                            }
                        }
                    }

                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OptionCheckBoxItem(
                            label = stringResource(R.string.is_enable),
                            checked = isEnableState,
                            onCheckedChange = { isEnableState = it },
                        )
                        OptionCheckBoxItem(
                            label = stringResource(R.string.discovery),
                            checked = isEnableExploreState,
                            onCheckedChange = { isEnableExploreState = it },
                        )
                        OptionCheckBoxItem(
                            label = stringResource(R.string.auto_save_cookie),
                            checked = isEnableCookieState,
                            onCheckedChange = { isEnableCookieState = it },
                        )
                        OptionCheckBoxItem(
                            label = stringResource(R.string.review),
                            checked = isEnableReviewState,
                            onCheckedChange = { isEnableReviewState = it },
                        )
                        OptionCheckBoxItem(
                            label = stringResource(R.string.is_event_listener),
                            checked = isEventListenerState,
                            onCheckedChange = { isEventListenerState = it },
                        )
                        OptionCheckBoxItem(
                            label = stringResource(R.string.custom_button),
                            checked = isCustomButtonState,
                            onCheckedChange = { isCustomButtonState = it },
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun OptionCheckBoxItem(
        label: String,
        checked: Boolean,
        onCheckedChange: (Boolean) -> Unit,
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 48.dp)
                .clickable { onCheckedChange(!checked) }
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = checked,
                onCheckedChange = onCheckedChange,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }

    @Composable
    private fun EditEntityFieldRow(
        tabPosition: Int,
        editEntity: EditEntity,
        refreshVer: Int,
    ) {
        val rawText = remember(editEntity, refreshVer) { editEntity.value.orEmpty() }
        val combiningPlaceholder = stringResource(R.string.combining_text_placeholder)
        val largePlaceholder = stringResource(R.string.large_text_placeholder, rawText.length)
        val presentation = remember(rawText, combiningPlaceholder, largePlaceholder) {
            if (EditSafety.isTooLongForInline(rawText)) {
                EditSafety.Presentation(
                    text = largePlaceholder,
                    isInlineEditable = false,
                )
            } else {
                EditSafety.presentation(rawText, combiningPlaceholder)
            }
        }
        val isUnsafeText = !presentation.isInlineEditable
        val selectionKey = "$tabPosition:${editEntity.key}"

        if (isUnsafeText) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { openUnsafeTextEditor(editEntity) }
            ) {
                OutlinedTextField(
                    value = presentation.text,
                    onValueChange = {},
                    readOnly = true,
                    enabled = false,
                    label = { Text(editEntity.hint) },
                    maxLines = EditSafety.PREVIEW_LINES,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else {
            val savedSelection = fieldSelections[selectionKey]
            var textFieldValue by remember(editEntity, refreshVer) {
                val safeRange = savedSelection?.let {
                    TextRange(
                        it.start.coerceIn(0, rawText.length),
                        it.end.coerceIn(0, rawText.length),
                    )
                } ?: TextRange(rawText.length)
                mutableStateOf(
                    TextFieldValue(
                        text = rawText,
                        selection = safeRange,
                    )
                )
            }

            OutlinedTextField(
                value = textFieldValue,
                onValueChange = { newValue ->
                    val oldText = textFieldValue.text
                    if (newValue.text != oldText) {
                        val stack = undoStacks.getOrPut(selectionKey) { ArrayDeque() }
                        if (stack.lastOrNull() != oldText) {
                            stack.addLast(oldText)
                            if (stack.size > 50) stack.removeFirst()
                        }
                        redoStacks[selectionKey]?.clear()
                    }
                    textFieldValue = newValue
                    editEntity.value = newValue.text
                    fieldSelections[selectionKey] = newValue.selection
                },
                label = { Text(editEntity.hint) },
                maxLines = AppConfig.sourceEditMaxLine.coerceAtLeast(1),
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { focusState ->
                        if (focusState.isFocused) {
                            focusedFieldKeyState = editEntity.key
                        }
                    },
            )
        }
    }

    override fun finish() {
        val source = getSource()
        if (!source.equal(viewModel.bookSource ?: BookSource())) {
            alert(R.string.exit) {
                setMessage(R.string.exit_no_save)
                positiveButton(R.string.yes)
                negativeButton(R.string.no) {
                    super.finish()
                }
            }
        } else {
            super.finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        softKeyboardTool.dismiss()
    }

    private fun setEditEntities(tabPosition: Int?) {
        selectedTabPositionState = tabPosition ?: 0
        focusedFieldKeyState = null
        editRefreshVersionState++
    }

    private fun upSourceView(bookSource: BookSource?) {
        val bs = bookSource ?: BookSource()
        bs.let {
            isEnableState = it.enabled
            isEnableExploreState = it.enabledExplore
            isEnableCookieState = it.enabledCookieJar ?: false
            isEnableReviewState = it.ruleReview?.enabled ?: false
            selectedTypePositionState = when (it.bookSourceType) {
                BookSourceType.video -> 4
                BookSourceType.file -> 3
                BookSourceType.image -> 2
                BookSourceType.audio -> 1
                else -> 0
            }
            isEventListenerState = it.eventListener
            isCustomButtonState = it.customButton
        }
        updateOptionPanel()
        // 基本信息
        sourceEntities.clear()
        sourceEntities.apply {
            add(EditEntity("bookSourceUrl", bs.bookSourceUrl, R.string.source_url))
            add(EditEntity("bookSourceName", bs.bookSourceName, R.string.source_name))
            add(EditEntity("bookSourceGroup", bs.bookSourceGroup, R.string.source_group))
            add(EditEntity("bookSourceComment", bs.bookSourceComment, R.string.comment))
            add(EditEntity("loginUrl", bs.loginUrl, R.string.login_url))
            add(EditEntity("loginUi", bs.loginUi, R.string.login_ui))
            add(EditEntity("loginCheckJs", bs.loginCheckJs, R.string.login_check_js))
            add(EditEntity("coverDecodeJs", bs.coverDecodeJs, R.string.cover_decode_js))
            add(EditEntity("bookUrlPattern", bs.bookUrlPattern, R.string.book_url_pattern))
            add(EditEntity("header", bs.header, R.string.source_http_header))
            add(EditEntity("variableComment", bs.variableComment, R.string.variable_comment))
            add(EditEntity("concurrentRate", bs.concurrentRate, R.string.concurrent_rate))
            add(EditEntity("jsLib", bs.jsLib, "jsLib"))
        }
        // 搜索
        val sr = bs.getSearchRule()
        searchEntities.clear()
        searchEntities.apply {
            add(EditEntity("searchUrl", bs.searchUrl, R.string.r_search_url))
            add(EditEntity("checkKeyWord", sr.checkKeyWord, R.string.check_key_word))
            add(EditEntity("bookList", sr.bookList, R.string.r_book_list))
            add(EditEntity("name", sr.name, R.string.r_book_name))
            add(EditEntity("author", sr.author, R.string.r_author))
            add(EditEntity("kind", sr.kind, R.string.rule_book_kind))
            add(EditEntity("wordCount", sr.wordCount, R.string.rule_word_count))
            add(EditEntity("lastChapter", sr.lastChapter, R.string.rule_last_chapter))
            add(EditEntity("intro", sr.intro, R.string.rule_book_intro))
            add(EditEntity("coverUrl", sr.coverUrl, R.string.rule_cover_url))
            add(EditEntity("bookUrl", sr.bookUrl, R.string.r_book_url))
        }
        // 发现
        val er = bs.getExploreRule()
        exploreEntities.clear()
        exploreEntities.apply {
            add(EditEntity("exploreUrl", bs.exploreUrl, R.string.r_find_url))
            add(EditEntity("bookList", er.bookList, R.string.r_book_list))
            add(EditEntity("name", er.name, R.string.r_book_name))
            add(EditEntity("author", er.author, R.string.r_author))
            add(EditEntity("kind", er.kind, R.string.rule_book_kind))
            add(EditEntity("wordCount", er.wordCount, R.string.rule_word_count))
            add(EditEntity("lastChapter", er.lastChapter, R.string.rule_last_chapter))
            add(EditEntity("intro", er.intro, R.string.rule_book_intro))
            add(EditEntity("coverUrl", er.coverUrl, R.string.rule_cover_url))
            add(EditEntity("bookUrl", er.bookUrl, R.string.r_book_url))
        }
        // 详情页
        val ir = bs.getBookInfoRule()
        infoEntities.clear()
        infoEntities.apply {
            add(EditEntity("init", ir.init, R.string.rule_book_info_init))
            add(EditEntity("name", ir.name, R.string.r_book_name))
            add(EditEntity("author", ir.author, R.string.r_author))
            add(EditEntity("kind", ir.kind, R.string.rule_book_kind))
            add(EditEntity("wordCount", ir.wordCount, R.string.rule_word_count))
            add(EditEntity("lastChapter", ir.lastChapter, R.string.rule_last_chapter))
            add(EditEntity("intro", ir.intro, R.string.rule_book_intro))
            add(EditEntity("coverUrl", ir.coverUrl, R.string.rule_cover_url))
            add(EditEntity("tocUrl", ir.tocUrl, R.string.rule_toc_url))
            add(EditEntity("canReName", ir.canReName, R.string.rule_can_re_name))
            add(EditEntity("downloadUrls", ir.downloadUrls, R.string.download_url_rule))
        }
        // 目录页
        val tr = bs.getTocRule()
        tocEntities.clear()
        tocEntities.apply {
            add(EditEntity("preUpdateJs", tr.preUpdateJs, R.string.pre_update_js))
            add(EditEntity("chapterList", tr.chapterList, R.string.rule_chapter_list))
            add(EditEntity("chapterName", tr.chapterName, R.string.rule_chapter_name))
            add(EditEntity("chapterUrl", tr.chapterUrl, R.string.rule_chapter_url))
            add(EditEntity("formatJs", tr.formatJs, R.string.format_js_rule))
            add(EditEntity("isVolume", tr.isVolume, R.string.rule_is_volume))
            add(EditEntity("updateTime", tr.updateTime, R.string.rule_update_time))
            add(EditEntity("isVip", tr.isVip, R.string.rule_is_vip))
            add(EditEntity("isPay", tr.isPay, R.string.rule_is_pay))
            add(EditEntity("nextTocUrl", tr.nextTocUrl, R.string.rule_next_toc_url))
        }
        // 正文页
        val cr = bs.getContentRule()
        contentEntities.clear()
        contentEntities.apply {
            add(EditEntity("content", cr.content, R.string.rule_book_content))
            add(EditEntity("nextContentUrl", cr.nextContentUrl, R.string.rule_next_content))
            add(EditEntity("subContent", cr.subContent, R.string.rule_sub_content))
            add(EditEntity("replaceRegex", cr.replaceRegex, R.string.rule_replace_regex))
            add(EditEntity("title", cr.title, R.string.rule_chapter_name))
            add(EditEntity("sourceRegex", cr.sourceRegex, R.string.rule_source_regex))
            add(EditEntity("imageStyle", cr.imageStyle, R.string.rule_image_style))
            add(EditEntity("imageDecode", cr.imageDecode, R.string.rule_image_decode))
            add(EditEntity("webJs", cr.webJs, R.string.rule_web_js))
            add(EditEntity("payAction", cr.payAction, R.string.rule_pay_action))
            add(EditEntity("callBackJs", cr.callBackJs, R.string.rule_call_back))
            add(EditEntity("contentBatch", cr.contentBatch, R.string.rule_content_batch))
            add(EditEntity("maxBatchSize", cr.maxBatchSize?.toString(), R.string.rule_max_batch_size))
        }
        // 段评
        val rr = bs.ruleReview ?: ReviewRule()
        reviewEntities.clear()
        reviewEntities.apply {
            add(EditEntity("reviewSummaryUrl", rr.reviewSummaryUrl, R.string.rule_review_summary_url))
            add(EditEntity("summaryListRule", rr.summaryListRule, R.string.rule_review_summary_list))
            add(EditEntity("summaryParagraphIndexRule", rr.summaryParagraphIndexRule, R.string.rule_review_summary_id))
            add(EditEntity("summaryCountRule", rr.summaryCountRule, R.string.rule_review_summary_count))
            add(EditEntity("summaryParagraphDataRule", rr.summaryParagraphDataRule, R.string.rule_review_summary_key))

            add(EditEntity("reviewDetailUrl", rr.reviewDetailUrl, R.string.rule_review_detail_url))
            add(EditEntity("reviewDetailNextPageUrl", rr.reviewDetailNextPageUrl, R.string.rule_review_detail_next_url))
            add(EditEntity("detailListRule", rr.detailListRule, R.string.rule_review_detail_list))
            add(EditEntity("detailIdRule", rr.detailIdRule, R.string.rule_review_detail_id))
            add(EditEntity("detailAvatarRule", rr.detailAvatarRule, R.string.rule_review_detail_avatar))
            add(EditEntity("detailNameRule", rr.detailNameRule, R.string.rule_review_detail_name))
            add(EditEntity("detailBadgeRule", rr.detailBadgeRule, R.string.rule_review_detail_badge))
            add(EditEntity("detailContentRule", rr.detailContentRule, R.string.rule_review_detail_content))

            add(EditEntity("reviewQuoteUrl", rr.reviewQuoteUrl, R.string.rule_review_quote))
            add(EditEntity("replyListRule", rr.replyListRule, R.string.rule_review_reply_list))
            add(EditEntity("replyIdRule", rr.replyIdRule, R.string.rule_review_reply_id))
            add(EditEntity("replyAvatarRule", rr.replyAvatarRule, R.string.rule_review_reply_avatar))
            add(EditEntity("replyNameRule", rr.replyNameRule, R.string.rule_review_reply_name))
            add(EditEntity("replyBadgeRule", rr.replyBadgeRule, R.string.rule_review_reply_badge))
            add(EditEntity("replyContentRule", rr.replyContentRule, R.string.rule_review_reply_content))
        }
        setEditEntities(0)
        applyPendingEditResult()
    }

    private fun applyPendingEditResult() {
        if (!pendingResultAvailable) return
        val editEntity = pendingEditKey?.let {
            findEditEntity(pendingEditTabPosition, it)
        } ?: return
        pendingResultText?.let { editEntity.value = it }
        refreshEditedEntity(editEntity, pendingResultCursor)
        clearPendingEditResult()
    }

    private fun clearPendingEditResult() {
        pendingEditKey = null
        pendingResultAvailable = false
        pendingResultText = null
        pendingResultCursor = -1
    }

    private fun getSource(): BookSource {
        val source = viewModel.bookSource?.copy() ?: BookSource()
        source.enabled = isEnableState
        source.enabledExplore = isEnableExploreState
        source.enabledCookieJar = isEnableCookieState
        source.bookSourceType = when (selectedTypePositionState) {
            4 -> BookSourceType.video
            3 -> BookSourceType.file
            2 -> BookSourceType.image
            1 -> BookSourceType.audio
            else -> BookSourceType.default
        }
        source.eventListener = isEventListenerState
        source.customButton = isCustomButtonState
        val searchRule = SearchRule()
        val exploreRule = ExploreRule()
        val bookInfoRule = BookInfoRule()
        val tocRule = TocRule()
        val contentRule = ContentRule()
        val reviewRule = source.ruleReview?.copy() ?: ReviewRule()
        reviewRule.enabled = isEnableReviewState
        sourceEntities.forEach {
            it.value = it.value?.takeIf { s -> s.isNotBlank() }
            when (it.key) {
                "bookSourceUrl" -> source.bookSourceUrl = it.value ?: ""
                "bookSourceName" -> source.bookSourceName = it.value ?: ""
                "bookSourceGroup" -> source.bookSourceGroup = it.value
                "loginUrl" -> source.loginUrl = it.value
                "loginUi" -> source.loginUi = it.value
                "loginCheckJs" -> source.loginCheckJs = it.value
                "coverDecodeJs" -> source.coverDecodeJs = it.value
                "bookUrlPattern" -> source.bookUrlPattern = it.value
                "header" -> source.header = it.value
                "bookSourceComment" -> source.bookSourceComment = it.value
                "concurrentRate" -> source.concurrentRate = it.value
                "variableComment" -> source.variableComment = it.value
                "jsLib" -> source.jsLib = it.value
            }
        }
        searchEntities.forEach {
            it.value = it.value?.takeIf { s -> s.isNotBlank() }
            when (it.key) {
                "searchUrl" -> source.searchUrl = it.value
                "checkKeyWord" -> searchRule.checkKeyWord = it.value
                "bookList" -> searchRule.bookList = it.value
                "name" -> searchRule.name =
                    viewModel.ruleComplete(it.value, searchRule.bookList)

                "author" -> searchRule.author =
                    viewModel.ruleComplete(it.value, searchRule.bookList)

                "kind" -> searchRule.kind =
                    viewModel.ruleComplete(it.value, searchRule.bookList)

                "intro" -> searchRule.intro =
                    viewModel.ruleComplete(it.value, searchRule.bookList)

                "wordCount" -> searchRule.wordCount =
                    viewModel.ruleComplete(it.value, searchRule.bookList)

                "lastChapter" -> searchRule.lastChapter =
                    viewModel.ruleComplete(it.value, searchRule.bookList)

                "coverUrl" -> searchRule.coverUrl =
                    viewModel.ruleComplete(it.value, searchRule.bookList, 3)

                "bookUrl" -> searchRule.bookUrl =
                    viewModel.ruleComplete(it.value, searchRule.bookList, 2)
            }
        }
        exploreEntities.forEach {
            it.value = it.value?.takeIf { s -> s.isNotBlank() }
            when (it.key) {
                "exploreUrl" -> source.exploreUrl = it.value
                "bookList" -> exploreRule.bookList = it.value
                "name" -> exploreRule.name =
                    viewModel.ruleComplete(it.value, exploreRule.bookList)

                "author" -> exploreRule.author =
                    viewModel.ruleComplete(it.value, exploreRule.bookList)

                "kind" -> exploreRule.kind =
                    viewModel.ruleComplete(it.value, exploreRule.bookList)

                "intro" -> exploreRule.intro =
                    viewModel.ruleComplete(it.value, exploreRule.bookList)

                "wordCount" -> exploreRule.wordCount =
                    viewModel.ruleComplete(it.value, exploreRule.bookList)

                "lastChapter" -> exploreRule.lastChapter =
                    viewModel.ruleComplete(it.value, exploreRule.bookList)

                "coverUrl" -> exploreRule.coverUrl =
                    viewModel.ruleComplete(it.value, exploreRule.bookList, 3)

                "bookUrl" -> exploreRule.bookUrl =
                    viewModel.ruleComplete(it.value, exploreRule.bookList, 2)
            }
        }
        infoEntities.forEach {
            it.value = it.value?.takeIf { s -> s.isNotBlank() }
            when (it.key) {
                "init" -> bookInfoRule.init = it.value
                "name" -> bookInfoRule.name = viewModel.ruleComplete(it.value, bookInfoRule.init)
                "author" -> bookInfoRule.author =
                    viewModel.ruleComplete(it.value, bookInfoRule.init)

                "kind" -> bookInfoRule.kind =
                    viewModel.ruleComplete(it.value, bookInfoRule.init)

                "intro" -> bookInfoRule.intro =
                    viewModel.ruleComplete(it.value, bookInfoRule.init)

                "wordCount" -> bookInfoRule.wordCount =
                    viewModel.ruleComplete(it.value, bookInfoRule.init)

                "lastChapter" -> bookInfoRule.lastChapter =
                    viewModel.ruleComplete(it.value, bookInfoRule.init)

                "coverUrl" -> bookInfoRule.coverUrl =
                    viewModel.ruleComplete(it.value, bookInfoRule.init, 3)

                "tocUrl" -> bookInfoRule.tocUrl =
                    viewModel.ruleComplete(it.value, bookInfoRule.init, 2)

                "canReName" -> bookInfoRule.canReName = it.value
                "downloadUrls" -> bookInfoRule.downloadUrls =
                    viewModel.ruleComplete(it.value, bookInfoRule.init)
            }
        }
        tocEntities.forEach {
            it.value = it.value?.takeIf { s -> s.isNotBlank() }
            when (it.key) {
                "preUpdateJs" -> tocRule.preUpdateJs = it.value
                "chapterList" -> tocRule.chapterList = it.value
                "chapterName" -> tocRule.chapterName =
                    viewModel.ruleComplete(it.value, tocRule.chapterList)

                "chapterUrl" -> tocRule.chapterUrl =
                    viewModel.ruleComplete(it.value, tocRule.chapterList, 2)

                "formatJs" -> tocRule.formatJs = it.value
                "isVolume" -> tocRule.isVolume = it.value
                "updateTime" -> tocRule.updateTime = it.value
                "isVip" -> tocRule.isVip = it.value
                "isPay" -> tocRule.isPay = it.value
                "nextTocUrl" -> tocRule.nextTocUrl =
                    viewModel.ruleComplete(it.value, tocRule.chapterList, 2)
            }
        }
        contentEntities.forEach {
            it.value = it.value?.takeIf { s -> s.isNotBlank() }
            when (it.key) {
                "content" -> contentRule.content = viewModel.ruleComplete(it.value)
                "nextContentUrl" -> contentRule.nextContentUrl =
                    viewModel.ruleComplete(it.value, type = 2)
                "subContent" -> contentRule.subContent = viewModel.ruleComplete(it.value)
                "title" -> contentRule.title = viewModel.ruleComplete(it.value)

                "webJs" -> contentRule.webJs = it.value
                "sourceRegex" -> contentRule.sourceRegex = it.value
                "replaceRegex" -> contentRule.replaceRegex = it.value
                "imageStyle" -> contentRule.imageStyle = it.value
                "imageDecode" -> contentRule.imageDecode = it.value
                "payAction" -> contentRule.payAction = it.value
                "callBackJs" -> contentRule.callBackJs = it.value
                "contentBatch" -> contentRule.contentBatch = it.value
                "maxBatchSize" -> contentRule.maxBatchSize = it.value?.toIntOrNull()
            }
        }
        reviewEntities.forEach {
            it.value = it.value?.takeIf { value -> value.isNotBlank() }
            when (it.key) {
                "reviewSummaryUrl" -> reviewRule.reviewSummaryUrl = it.value
                "summaryListRule" -> reviewRule.summaryListRule = it.value
                "summaryParagraphIndexRule" -> reviewRule.summaryParagraphIndexRule = it.value
                "summaryCountRule" -> reviewRule.summaryCountRule = it.value
                "summaryParagraphDataRule" -> reviewRule.summaryParagraphDataRule = it.value
                "reviewDetailUrl" -> reviewRule.reviewDetailUrl = it.value
                "reviewDetailNextPageUrl" -> reviewRule.reviewDetailNextPageUrl = it.value
                "detailListRule" -> reviewRule.detailListRule = it.value
                "detailIdRule" -> reviewRule.detailIdRule = it.value
                "detailAvatarRule" -> reviewRule.detailAvatarRule = it.value
                "detailNameRule" -> reviewRule.detailNameRule = it.value
                "detailBadgeRule" -> reviewRule.detailBadgeRule = it.value
                "detailContentRule" -> reviewRule.detailContentRule = it.value
                "reviewQuoteUrl" -> reviewRule.reviewQuoteUrl = it.value
                "replyListRule" -> reviewRule.replyListRule = it.value
                "replyIdRule" -> reviewRule.replyIdRule = it.value
                "replyAvatarRule" -> reviewRule.replyAvatarRule = it.value
                "replyNameRule" -> reviewRule.replyNameRule = it.value
                "replyBadgeRule" -> reviewRule.replyBadgeRule = it.value
                "replyContentRule" -> reviewRule.replyContentRule = it.value
            }
        }
        source.ruleSearch = searchRule
        source.ruleExplore = exploreRule
        source.ruleBookInfo = bookInfoRule
        source.ruleToc = tocRule
        source.ruleContent = contentRule
        source.ruleReview = reviewRule.takeIf {
            source.ruleReview != null || it.enabled || reviewEntities.any { entity ->
                !entity.value.isNullOrBlank()
            }
        }
        return source
    }

    private fun alertGroups() {
        lifecycleScope.launch {
            val groups = withContext(IO) {
                appDb.bookSourceDao.allGroups()
            }
            selector(groups) { _, s, _ ->
                sendText(s)
            }
        }
    }

    override fun helpActions(): List<SelectItem<String>> {
        val helpActions = arrayListOf(
            SelectItem("插入URL参数", "urlOption"),
        )
        val activeKey = focusedFieldKeyState
            ?: (window.decorView.findFocus() as? EditText)?.getTag(R.id.tag) as? String
        if (activeKey == "bookSourceGroup") {
            helpActions.add(SelectItem("插入分组", "addGroup"))
        } else if (activeKey != null) {
            helpActions.add(SelectItem("选择文件", "selectFile"))
        }
        return helpActions
    }

    override fun onHelpActionSelect(action: String) {
        when (action) {
            "addGroup" -> alertGroups()
            "urlOption" -> UrlOptionDialog(this) { sendText(it) }.show()
            "selectFile" -> selectDoc.launch {
                mode = HandleFileContract.FILE
            }
        }
    }

    override fun sendText(text: String) {
        if (text.isEmpty()) return
        val key = focusedFieldKeyState
        val entity = key?.let { findEditEntity(selectedTabPositionState, it) }
        if (entity != null) {
            val selectionKey = "${selectedTabPositionState}:${entity.key}"
            val current = entity.value.orEmpty()
            val stack = undoStacks.getOrPut(selectionKey) { ArrayDeque() }
            stack.addLast(current)
            redoStacks[selectionKey]?.clear()

            val range = fieldSelections[selectionKey] ?: TextRange(current.length)
            val start = minOf(range.start, range.end).coerceIn(0, current.length)
            val end = maxOf(range.start, range.end).coerceIn(0, current.length)
            val updated = buildString {
                append(current.substring(0, start))
                append(text)
                append(current.substring(end))
            }
            entity.value = updated
            fieldSelections[selectionKey] = TextRange(start + text.length)
            editRefreshVersionState++
            return
        }
        val view = window.decorView.findFocus()
        if (view is EditText) {
            val start = minOf(view.selectionStart, view.selectionEnd)
            val end = maxOf(view.selectionStart, view.selectionEnd)
            val edit = view.editableText
            if (start < 0 || start >= edit.length) {
                edit.append(text)
            } else {
                edit.replace(start, end, text)
            }
        }
    }

    private fun setSourceVariable() {
        saveSource { source ->
            lifecycleScope.launch {
                val comment =
                    source.getDisplayVariableComment("源变量可在js中通过source.getVariable()获取")
                val variable = withContext(IO) { source.getVariable() }
                showDialogFragment(
                    VariableDialog(
                        getString(R.string.set_source_variable),
                        source.getKey(),
                        variable,
                        comment
                    )
                )
            }
        }
    }

    override fun setVariable(key: String, variable: String?) {
        viewModel.bookSource?.setVariable(variable)
    }

    @RequiresApi(Build.VERSION_CODES.M)
    override fun onUndoClicked() {
        val key = focusedFieldKeyState
        val entity = key?.let { findEditEntity(selectedTabPositionState, it) }
        if (entity != null) {
            val selectionKey = "${selectedTabPositionState}:${entity.key}"
            val undoStack = undoStacks[selectionKey]
            if (!undoStack.isNullOrEmpty()) {
                val prev = undoStack.removeLast()
                redoStacks.getOrPut(selectionKey) { ArrayDeque() }.addLast(entity.value.orEmpty())
                entity.value = prev
                fieldSelections[selectionKey] = TextRange(prev.length)
                editRefreshVersionState++
            }
            return
        }
        val editText = window.decorView.findFocus()
        if (editText is EditText) {
            editText.onTextContextMenuItem(android.R.id.undo)
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    override fun onRedoClicked() {
        val key = focusedFieldKeyState
        val entity = key?.let { findEditEntity(selectedTabPositionState, it) }
        if (entity != null) {
            val selectionKey = "${selectedTabPositionState}:${entity.key}"
            val redoStack = redoStacks[selectionKey]
            if (!redoStack.isNullOrEmpty()) {
                val next = redoStack.removeLast()
                undoStacks.getOrPut(selectionKey) { ArrayDeque() }.addLast(entity.value.orEmpty())
                entity.value = next
                fieldSelections[selectionKey] = TextRange(next.length)
                editRefreshVersionState++
            }
            return
        }
        val editText = window.decorView.findFocus()
        if (editText is EditText) {
            editText.onTextContextMenuItem(android.R.id.redo)
        }
    }

    private companion object {
        const val STATE_PENDING_EDIT_KEY = "pendingEditKey"
        const val STATE_PENDING_EDIT_TAB = "pendingEditTab"
    }

}
