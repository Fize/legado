package io.legado.app.ui.code

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.activity.addCallback
import androidx.activity.viewModels
import androidx.annotation.Keep
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import io.github.rosemoe.sora.event.ColorSchemeUpdateEvent
import io.github.rosemoe.sora.event.PublishSearchResultEvent
import io.github.rosemoe.sora.event.SelectionChangeEvent
import io.github.rosemoe.sora.event.SubscriptionReceipt
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.util.regex.RegexBackrefGrammar
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.EditorSearcher
import io.github.rosemoe.sora.widget.EditorSearcher.SearchOptions
import io.github.rosemoe.sora.widget.component.EditorTextActionWindow
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import io.legado.app.R
import io.legado.app.base.VMBaseComposeActivity
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.ui.about.openAppLogs
import io.legado.app.ui.code.config.ChangeThemeDialog
import io.legado.app.ui.code.config.SettingsDialog
import io.legado.app.ui.compose.component.LegadoTopAppBar
import io.legado.app.ui.widget.code.EditSafety
import io.legado.app.ui.widget.keyboard.KeyboardToolPop
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.imeHeight
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.setOnApplyWindowInsetsListenerCompat
import io.legado.app.utils.share
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi

class CodeEditActivity :
    VMBaseComposeActivity<CodeEditViewModel>(),
    KeyboardToolPop.CallBack, ChangeThemeDialog.CallBack, SettingsDialog.CallBack,
    CurlAnalyzeUrlDialog.Callback {
    companion object {
        const val EXTRA_SHOW_LOGIN_SOURCE = "showLoginSourceAction"
        const val EXTRA_CHECK_JAVASCRIPT_SYNTAX = "checkJavaScriptSyntax"
        const val EXTRA_RESULT_ACTION = "resultAction"
        const val RESULT_ACTION_LOGIN_SOURCE = "loginSource"

        private var isInitialized = false
        private var findText = ""
        private var replaceText = ""
        private var isRegex = true
        private const val SAFE_EDITOR_LOAD_TIMEOUT_MILLIS = 15_000L
        private const val SAFE_EDITOR_READ_TIMEOUT_MILLIS = 5_000L
    }

    override val guardsRestoreRecovery = true

    override val viewModel by viewModels<CodeEditViewModel>()
    private val softKeyboardTool by lazy {
        KeyboardToolPop(this, lifecycleScope, window.decorView, this)
    }
    private val editor: CodeEditor by lazy {
        CodeEditor(this).apply {
            setTextSize(18f)
            contentDescription = "codeEditorNative"
        }
    }
    private val editorContainer: FrameLayout by lazy {
        FrameLayout(this).apply {
            addView(
                editor,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }
    }
    private val editorSearcher: EditorSearcher by lazy { editor.searcher }
    private var searchOptions: SearchOptions? = null
    private var useSafeEditor by mutableStateOf(false)
    private var safeEditor: WebView? = null
    private var safeEditorStatus by mutableStateOf(SafeEditorStatus.IDLE)
    private var safeEditorReadPending by mutableStateOf(false)
    private var safeEditorReadGeneration = 0
    private var safeEditorLoadTimeout: Runnable? = null
    private var safeEditorReadTimeout: Runnable? = null
    private var editorReady by mutableStateOf(false)
    private var textActions: CodeTextActions? = null

    private var titleState by mutableStateOf("")
    private var autoWrapState by mutableStateOf(true)
    private var showSearchGroup by mutableStateOf(false)
    private var showReplaceGroup by mutableStateOf(false)
    private var findTextState by mutableStateOf(findText)
    private var replaceTextState by mutableStateOf(replaceText)
    private var isRegexState by mutableStateOf(isRegex)
    private var searchResultTextState by mutableStateOf("0")
    private var focusedSearchField by mutableStateOf<String?>(null)
    private var receiptSearch: SubscriptionReceipt<PublishSearchResultEvent>? = null
    private var receiptChange: SubscriptionReceipt<SelectionChangeEvent>? = null

    private enum class SafeEditorStatus {
        IDLE,
        LOADING,
        READY,
        FAILED
    }

    private val isDark
        get() = AppConfig.editTemeAuto && ThemeConfig.isDarkTheme()
    private var themeIndex = -1

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        autoWrapState = AppConfig.editAutoWrap
        titleState = getString(R.string.edit_code)
        // SavedStateHandle is available only after BaseActivity has called super.onCreate.
        if (!isInitialized) {
            viewModel.initSora()
            isInitialized = true
        }
        upTheme(if (isDark) AppConfig.editThemeDark else AppConfig.editTheme)
        onBackPressedDispatcher.addCallback(this) {
            if (textActions?.dismiss() != true) finish()
        }
        softKeyboardTool.attachToWindow(window)
        editor.colorScheme = TextMateColorScheme2.create(ThemeRegistry.getInstance()) //先设置颜色,避免一开始的白屏
        viewModel.initData(intent) {
            if (isDestroyed || isRestoreRecoveryBlocked) return@initData
            viewModel.title?.let {
                titleState = it
            }
            val text = viewModel.editorDraft?.text ?: viewModel.initialText
            viewModel.editorDraft?.let { viewModel.cursorPosition = it.cursorPosition }
            useSafeEditor = EditSafety.isCombiningHeavy(text)
            if (useSafeEditor) {
                setupSafeEditor(text)
            } else {
                setupCodeEditor(text)
            }
            editorReady = true
        }
        initView()
    }

    private fun initView() {
        window.decorView.setOnApplyWindowInsetsListenerCompat { _, windowInsets ->
            softKeyboardTool.initialPadding = windowInsets.imeHeight
            windowInsets
        }
    }

    internal fun codeEditorForTest(): CodeEditor = editor

    internal fun editorContainerForTest(): ViewGroup = editorContainer

    internal fun saveForTest() = save(false)

    internal fun showNativeTextActionsForTest() = textActions?.showForTest()

    @Composable
    override fun Content() {
        var showMenu by remember { mutableStateOf(false) }
        val showSoraActions = !useSafeEditor
        val canReturnText = viewModel.writable &&
            (!useSafeEditor ||
                (safeEditorStatus == SafeEditorStatus.READY && !safeEditorReadPending))
        val showLoginAction = shouldShowLoginSourceAction(
            viewModel.writable,
            isLoginSourceActionEnabled()
        )
        val showJsSyntaxAction = shouldShowJavaScriptSyntaxAction(
            useSafeEditor,
            viewModel.canCheckJavaScriptSyntax
        )

        Scaffold(
            topBar = {
                LegadoTopAppBar(
                    title = titleState,
                    onNavigationClick = { finish() },
                    actions = {
                        if (showSoraActions) {
                            IconButton(onClick = { search() }) {
                                Icon(
                                    imageVector = Icons.Default.Search,
                                    contentDescription = stringResource(R.string.search)
                                )
                            }
                        }
                        if (viewModel.writable) {
                            IconButton(
                                onClick = { save(false) },
                                enabled = canReturnText,
                                modifier = Modifier.testTag("codeEditorSave"),
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_save),
                                    contentDescription = stringResource(R.string.action_save)
                                )
                            }
                        }
                        Box {
                            IconButton(onClick = { showMenu = true }) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = stringResource(R.string.more_menu)
                                )
                            }
                            DropdownMenu(
                                expanded = showMenu,
                                onDismissRequest = { showMenu = false }
                            ) {
                                if (showLoginAction) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.login)) },
                                        enabled = canReturnText,
                                        onClick = {
                                            showMenu = false
                                            returnText(RESULT_ACTION_LOGIN_SOURCE)
                                        }
                                    )
                                }
                                if (showSoraActions) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.change_theme)) },
                                        onClick = {
                                            showMenu = false
                                            showDialogFragment(ChangeThemeDialog())
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.select_all)) },
                                        onClick = {
                                            showMenu = false
                                            if (!useSafeEditor) editor.selectAll()
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.format_code)) },
                                        onClick = {
                                            showMenu = false
                                            if (!useSafeEditor) viewModel.formatCode(editor)
                                        }
                                    )
                                }
                                if (showJsSyntaxAction) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.check_javascript_syntax)) },
                                        onClick = {
                                            showMenu = false
                                            if (!useSafeEditor) {
                                                viewModel.checkJavaScriptSyntax(editor)
                                            }
                                        }
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.curl_analyze_url_converter)) },
                                    onClick = {
                                        showMenu = false
                                        showCurlAnalyzeUrlConverter()
                                    }
                                )
                                if (showSoraActions) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.config_settings)) },
                                        onClick = {
                                            showMenu = false
                                            showDialogFragment(SettingsDialog())
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.auto_wrap)) },
                                        trailingIcon = {
                                            Checkbox(
                                                checked = autoWrapState,
                                                onCheckedChange = null
                                            )
                                        },
                                        onClick = {
                                            showMenu = false
                                            val next = !AppConfig.editAutoWrap
                                            autoWrapState = next
                                            upEdit(autoWarp = next)
                                            putPrefBoolean(PreferKey.editAutoWrap, next)
                                        }
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.log)) },
                                    onClick = {
                                        showMenu = false
                                        this@CodeEditActivity.openAppLogs()
                                    }
                                )
                            }
                        }
                    }
                )
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                AndroidView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("codeEditorContainer")
                        .weight(1f),
                    factory = {
                        (editorContainer.parent as? ViewGroup)?.removeView(editorContainer)
                        editorContainer
                    }
                )

                if (showSearchGroup && !useSafeEditor) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("codeEditorSearchTools"),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        tonalElevation = 2.dp
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(R.string.search_result),
                                    fontSize = 14.sp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = searchResultTextState,
                                    fontSize = 14.sp
                                )
                                Spacer(modifier = Modifier.weight(1f))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = stringResource(R.string.regex),
                                        fontSize = 14.sp
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Switch(
                                        checked = isRegexState,
                                        onCheckedChange = { checked ->
                                            isRegex = checked
                                            isRegexState = checked
                                            setSearchOptions()
                                            searchTxt(findTextState)
                                        }
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(R.string.find),
                                    fontSize = 14.sp,
                                    modifier = Modifier.padding(end = 8.dp)
                                )
                                OutlinedTextField(
                                    value = findTextState,
                                    onValueChange = { text ->
                                        findTextState = text
                                        if (text.isNotEmpty()) {
                                            findText = text
                                            searchTxt(findText)
                                        } else {
                                            editorSearcher.stopSearch()
                                            editor.invalidate()
                                        }
                                    },
                                    singleLine = true,
                                    modifier = Modifier
                                        .weight(1f)
                                        .onFocusChanged { state ->
                                            if (state.isFocused) focusedSearchField = "find"
                                            else if (focusedSearchField == "find") focusedSearchField = null
                                        }
                                )
                                IconButton(
                                    onClick = { closeSearchGroup() }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = stringResource(R.string.close)
                                    )
                                }
                            }

                            if (showReplaceGroup) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = stringResource(R.string.replace),
                                        fontSize = 14.sp,
                                        modifier = Modifier.padding(end = 8.dp)
                                    )
                                    OutlinedTextField(
                                        value = replaceTextState,
                                        onValueChange = { text ->
                                            replaceTextState = text
                                            if (text.isNotEmpty()) {
                                                replaceText = text
                                            }
                                        },
                                        singleLine = true,
                                        modifier = Modifier
                                            .weight(1f)
                                            .onFocusChanged { state ->
                                                if (state.isFocused) focusedSearchField = "replace"
                                                else if (focusedSearchField == "replace") focusedSearchField = null
                                            }
                                    )
                                    IconButton(
                                        onClick = {
                                            showReplaceGroup = false
                                        }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = stringResource(R.string.close)
                                        )
                                    }
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly
                            ) {
                                TextButton(
                                    onClick = {
                                        if (editorSearcher.hasQuery()) {
                                            editorSearcher.gotoPrevious()
                                        }
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(stringResource(R.string.btn_previous), fontSize = 14.sp)
                                }
                                TextButton(
                                    onClick = {
                                        if (editorSearcher.hasQuery()) {
                                            editorSearcher.gotoNext()
                                        }
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(stringResource(R.string.btn_next), fontSize = 14.sp)
                                }
                                TextButton(
                                    onClick = {
                                        if (!showReplaceGroup) {
                                            showReplaceGroup = true
                                        } else if (editorSearcher.hasQuery()) {
                                            editorSearcher.replaceCurrentMatch(replaceTextState)
                                        }
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(stringResource(R.string.replace), fontSize = 14.sp)
                                }
                                TextButton(
                                    onClick = {
                                        if (editorSearcher.hasQuery()) {
                                            editorSearcher.replaceAll(replaceTextState)
                                        }
                                    },
                                    enabled = showReplaceGroup,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(stringResource(R.string.replace_all), fontSize = 14.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun setupCodeEditor(text: String) {
        safeEditor?.visibility = View.GONE
        editor.visibility = View.VISIBLE
        editor.apply {
            nonPrintablePaintingFlags = AppConfig.editNonPrintable
            setEditorLanguage(viewModel.language)
            upEdit(AppConfig.editFontScale, null, AppConfig.editAutoWrap)
            props.maxIPCTextLength = 64 * 1024
            setupTextActions()
            setText(text)
            editable = viewModel.writable
            requestFocus()
            // Restore before accepting input; a delayed restore can overwrite a new selection.
            val pos = cursor.indexer.getCharPosition(viewModel.cursorPosition.coerceIn(0, text.length))
            setSelection(pos.line, pos.column, true)
        }
    }

    private fun setupTextActions() {
        val actions = editor.getComponent(EditorTextActionWindow::class.java)
        val copy = actions.view.findViewById<ImageButton>(io.github.rosemoe.sora.R.id.panel_btn_copy)
        val buttons = copy.parent as ViewGroup
        val shareButton = ImageButton(this).apply {
            id = R.id.code_share_selection
            contentDescription = getString(R.string.share)
            setImageResource(R.drawable.ic_share)
            background = copy.background?.constantState?.newDrawable()?.mutate()
            setPadding(copy.paddingLeft, copy.paddingTop, copy.paddingRight, copy.paddingBottom)
            layoutParams = LinearLayout.LayoutParams(copy.layoutParams)
            setOnClickListener {
                val cursor = editor.cursor
                if (cursor.isSelected) {
                    share(editor.text.subSequence(cursor.left, cursor.right).toString())
                    actions.dismiss()
                }
            }
        }
        buttons.addView(shareButton, buttons.indexOfChild(copy) + 1)
        fun updateShareButton() {
            shareButton.isVisible = editor.cursor.isSelected
            shareButton.setColorFilter(editor.colorScheme.getColor(EditorColorScheme.TEXT_ACTION_WINDOW_ICON_COLOR))
        }
        updateShareButton()
        editor.subscribeEvent(SelectionChangeEvent::class.java) { _, _ -> updateShareButton() }
        editor.subscribeEvent(ColorSchemeUpdateEvent::class.java) { _, _ -> updateShareButton() }
        textActions = CodeTextActions(editor)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupSafeEditor(text: String) {
        safeEditorStatus = SafeEditorStatus.LOADING
        showSearchGroup = false
        editorSearcher.stopSearch()
        editor.visibility = View.GONE
        val webView = getOrCreateSafeEditor()
        webView.apply {
            visibility = View.VISIBLE
            setBackgroundColor(backgroundColor)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = false
                allowFileAccess = false
                allowContentAccess = false
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false)
                blockNetworkLoads = true
            }
            // This WebView only loads our network-disabled, locally generated editor document.
            addJavascriptInterface(object {
                @Keep
                @JavascriptInterface
                fun changed(text: String, cursor: Int, dirty: Boolean) {
                    if (!isDestroyed) {
                        viewModel.editorDraft = SafeEditorContent(text, cursor, dirty)
                            .resolveAgainst(viewModel.initialText)
                    }
                }

                @Keep
                @JavascriptInterface
                fun selected(cursor: Int) {
                    if (!isDestroyed) {
                        viewModel.editorDraft = viewModel.editorDraft?.let {
                            // A clean textarea normalizes CRLF; preserve the source offset contract.
                            if (it.dirty) it.copy(cursorPosition = cursor.coerceIn(0, it.text.length))
                            else SafeEditorContent(it.text, cursor, false).resolveAgainst(viewModel.initialText)
                        }
                    }
                }
            }, "EditorDraft")
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) {
                    if (safeEditor !== view) return
                    safeEditorLoadTimeout?.let(view::removeCallbacks)
                    safeEditorLoadTimeout = null
                    safeEditorStatus = SafeEditorStatus.READY
                    updateSafeEditorActionButtons()
                    view.requestFocus()
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError
                ) {
                    if (safeEditor === view && request.isForMainFrame) {
                        markSafeEditorLoadFailed(view)
                    }
                }

                override fun onRenderProcessGone(
                    view: WebView,
                    detail: RenderProcessGoneDetail
                ): Boolean {
                    if (safeEditor !== view) return false
                    handleSafeEditorRenderProcessGone(view)
                    return true
                }
            }
            scheduleSafeEditorLoadTimeout(this)
            loadDataWithBaseURL(
                null,
                buildSafeEditorHtml(text),
                "text/html",
                "utf-8",
                null
            )
        }
        updateSafeEditorActionButtons()
    }

    private fun getOrCreateSafeEditor(): WebView {
        return safeEditor ?: WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            contentDescription = getString(R.string.safe_code_editor)
            visibility = View.GONE
        }.also {
            editorContainer.addView(it)
            safeEditor = it
        }
    }

    private fun scheduleSafeEditorLoadTimeout(webView: WebView) {
        safeEditorLoadTimeout?.let(webView::removeCallbacks)
        safeEditorLoadTimeout = Runnable {
            if (safeEditor === webView && safeEditorStatus == SafeEditorStatus.LOADING) {
                safeEditorStatus = SafeEditorStatus.FAILED
                updateSafeEditorActionButtons()
                toastOnUi(R.string.safe_code_editor_load_failed)
            }
        }.also {
            webView.postDelayed(it, SAFE_EDITOR_LOAD_TIMEOUT_MILLIS)
        }
    }

    private fun markSafeEditorLoadFailed(webView: WebView) {
        if (safeEditorStatus == SafeEditorStatus.FAILED) return
        safeEditorLoadTimeout?.let(webView::removeCallbacks)
        safeEditorLoadTimeout = null
        safeEditorStatus = SafeEditorStatus.FAILED
        updateSafeEditorActionButtons()
        toastOnUi(R.string.safe_code_editor_load_failed)
    }

    private fun handleSafeEditorRenderProcessGone(webView: WebView) {
        safeEditorStatus = SafeEditorStatus.FAILED
        cancelSafeEditorRead(restoreEditing = false)
        safeEditorLoadTimeout?.let(webView::removeCallbacks)
        safeEditorLoadTimeout = null
        editorContainer.removeView(webView)
        safeEditor = null
        webView.destroy()
        updateSafeEditorActionButtons()
        toastOnUi(R.string.safe_code_editor_load_failed)
    }

    private fun updateSafeEditorActionButtons() = Unit

    private fun buildSafeEditorHtml(text: String): String {
        val encodedText = Base64.encodeToString(
            text.toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP
        )
        val background = ColorUtils.intToString(backgroundColor)
        val foreground = ColorUtils.intToString(primaryTextColor)
        val readOnly = if (viewModel.writable) "" else " readonly"
        val writable = viewModel.writable
        val wrap = if (AppConfig.editAutoWrap) "soft" else "off"
        val cursorPosition = text.take(viewModel.cursorPosition.coerceIn(0, text.length))
            .replace("\r\n", "\n").replace('\r', '\n').length
        return """
            <!doctype html>
            <html>
            <head>
                <meta charset="utf-8" />
                <meta name="viewport" content="width=device-width, initial-scale=1" />
                <meta http-equiv="Content-Security-Policy"
                    content="default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'" />
                <style>
                    html, body {
                        width: 100%;
                        height: 100%;
                        margin: 0;
                        overflow: hidden;
                        background: $background;
                    }
                    textarea {
                        box-sizing: border-box;
                        width: 100%;
                        height: 100%;
                        border: 0;
                        outline: 0;
                        resize: none;
                        padding: 12px;
                        color: $foreground;
                        background: $background;
                        font-family: monospace;
                        font-size: ${AppConfig.editFontScale}px;
                        line-height: 1.4;
                    }
                </style>
            </head>
            <body>
                <textarea id="code" wrap="$wrap" spellcheck="false" autocomplete="off"
                    autocorrect="off" autocapitalize="off"$readOnly></textarea>
                <script>
                    function decodeBase64(value) {
                        var binary = atob(value);
                        var bytes = new Uint8Array(binary.length);
                        for (var i = 0; i < binary.length; i++) {
                            bytes[i] = binary.charCodeAt(i);
                        }
                        if (window.TextDecoder) {
                            return new TextDecoder("utf-8", { ignoreBOM: true }).decode(bytes);
                        }
                        var escaped = "";
                        for (var j = 0; j < bytes.length; j++) {
                            escaped += "%" + ("00" + bytes[j].toString(16)).slice(-2);
                        }
                        return decodeURIComponent(escaped);
                    }

                    var editor = document.getElementById("code");
                    editor.value = decodeBase64("$encodedText");
                    var initialValue = editor.value;
                    var initialDraftDirty = ${viewModel.editorDraft?.dirty == true};
                    var editorWritable = $writable;
                    var initialCursor = Math.min(editor.value.length, $cursorPosition);
                    editor.setSelectionRange(initialCursor, initialCursor);
                    editor.focus();
                    function rememberDraft() {
                        EditorDraft.changed(editor.value, editor.selectionStart || 0,
                            editor.value !== initialValue || initialDraftDirty);
                    }
                    editor.addEventListener("input", rememberDraft);
                    document.addEventListener("selectionchange", function() {
                        EditorDraft.selected(editor.selectionStart || 0);
                    });
                    rememberDraft();

                    window.__setEditorReadOnly = function(readOnly) {
                        if (readOnly) {
                            editor.blur();
                            editor.readOnly = true;
                        } else if (editorWritable) {
                            editor.readOnly = false;
                            editor.focus();
                        } else {
                            editor.readOnly = true;
                        }
                    };

                    window.__getEditorState = function() {
                        window.__setEditorReadOnly(true);
                        return JSON.stringify({
                            text: editor.value,
                            cursorPosition: editor.selectionStart || 0,
                            dirty: editor.value !== initialValue || initialDraftDirty
                        });
                    };

                    window.__insertEditorText = function(encodedValue) {
                        if (editor.readOnly) return false;
                        var value = decodeBase64(encodedValue);
                        var start = editor.selectionStart || 0;
                        var end = editor.selectionEnd || start;
                        if (editor.setRangeText) {
                            editor.setRangeText(value, start, end, "end");
                        } else {
                            editor.value = editor.value.slice(0, start) + value + editor.value.slice(end);
                            var cursor = start + value.length;
                            editor.setSelectionRange(cursor, cursor);
                        }
                        rememberDraft();
                        return true;
                    };
                </script>
            </body>
            </html>
        """.trimIndent()
    }

    private fun readSafeEditorState(onResult: (SafeEditorContent) -> Unit) {
        if (safeEditorReadPending) return
        val webView = safeEditor ?: run {
            toastOnUi(R.string.safe_code_editor_read_failed)
            return
        }
        val generation = ++safeEditorReadGeneration
        safeEditorReadPending = true
        updateSafeEditorActionButtons()
        safeEditorReadTimeout = Runnable {
            if (safeEditorReadGeneration == generation) {
                safeEditorReadGeneration++
                safeEditorReadPending = false
                safeEditorReadTimeout = null
                restoreSafeEditorEditing()
                updateSafeEditorActionButtons()
                toastOnUi(R.string.safe_code_editor_read_failed)
            }
        }.also {
            webView.postDelayed(it, SAFE_EDITOR_READ_TIMEOUT_MILLIS)
        }
        webView.evaluateJavascript(
            "window.__getEditorState && window.__getEditorState();"
        ) { value ->
            if (safeEditorReadGeneration != generation) return@evaluateJavascript
            safeEditorReadTimeout?.let(webView::removeCallbacks)
            safeEditorReadTimeout = null
            safeEditorReadPending = false
            updateSafeEditorActionButtons()
            val state = SafeEditorResultCodec.decode(value)
            if (state == null) {
                restoreSafeEditorEditing()
                toastOnUi(R.string.safe_code_editor_read_failed)
                return@evaluateJavascript
            }
            onResult(state)
        }
    }

    private fun cancelSafeEditorRead(restoreEditing: Boolean = true) {
        safeEditorReadGeneration++
        safeEditorReadTimeout?.let { timeout ->
            safeEditor?.removeCallbacks(timeout)
        }
        safeEditorReadTimeout = null
        safeEditorReadPending = false
        if (restoreEditing) restoreSafeEditorEditing()
        updateSafeEditorActionButtons()
    }

    private fun restoreSafeEditorEditing() {
        if (safeEditorStatus != SafeEditorStatus.READY || !viewModel.writable) return
        safeEditor?.evaluateJavascript(
            "window.__setEditorReadOnly && window.__setEditorReadOnly(false);",
            null
        )
    }

    private fun insertSafeEditorText(
        text: String,
        onResult: (Boolean) -> Unit = {},
    ) {
        val webView = safeEditor ?: run {
            onResult(false)
            return
        }
        if (
            safeEditorStatus != SafeEditorStatus.READY ||
            !viewModel.writable ||
            safeEditorReadPending
        ) {
            onResult(false)
            return
        }
        val encodedText = Base64.encodeToString(
            text.toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP
        )
        webView.evaluateJavascript(
            "window.__insertEditorText && window.__insertEditorText('$encodedText');",
        ) { result ->
            onResult(result == "true")
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (isRestoreRecoveryBlocked) { super.onSaveInstanceState(outState); return }
        if (editorReady) {
            if (!useSafeEditor) {
                val text = editor.text.toString()
                viewModel.editorDraft = SafeEditorContent(text, editor.cursor.left, text != viewModel.initialText)
            }
            runCatching { viewModel.persistEditorDraft() }
                .onFailure { toastOnUi(it.localizedMessage) }
        }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        if (!wasActivityContentCreated) { super.onDestroy(); return }
        receiptSearch?.unsubscribe()
        receiptChange?.unsubscribe()
        textActions?.dismiss()
        editorSearcher.stopSearch()
        editor.release()
        cancelSafeEditorRead(restoreEditing = false)
        safeEditorStatus = SafeEditorStatus.IDLE
        safeEditor?.apply {
            safeEditorLoadTimeout?.let(::removeCallbacks)
            safeEditorLoadTimeout = null
            webViewClient = WebViewClient()
            stopLoading()
            loadUrl("about:blank")
            clearHistory()
            removeAllViews()
            editorContainer.removeView(this)
            destroy()
        }
        safeEditor = null
        super.onDestroy()
    }

    /**
     * 使用super.finish(),防止循环回调
     * */
    private fun save(check: Boolean) {
        if (isRestoreRecoveryBlocked) return super.finish()
        if (!viewModel.writable) return super.finish()
        if (useSafeEditor) {
            if (safeEditorReadPending) {
                cancelSafeEditorRead()
                if (check) confirmSafeEditorExit()
                return
            }
            if (safeEditorStatus != SafeEditorStatus.READY) {
                if (check) {
                    confirmSafeEditorExit()
                } else {
                    toastOnUi(R.string.safe_code_editor_load_failed)
                }
                return
            }
            readSafeEditorState { state ->
                val resolvedState = state.resolveAgainst(viewModel.initialText)
                if (check && resolvedState.text != viewModel.initialText) {
                    restoreSafeEditorEditing()
                }
                saveText(check, resolvedState.text, resolvedState.cursorPosition)
            }
            return
        }
        saveText(check, editor.text.toString(), editor.cursor?.left ?: 0)
    }

    private fun confirmSafeEditorExit() {
        alert(R.string.exit) {
            setMessage(R.string.exit_no_save)
            positiveButton(R.string.yes)
            negativeButton(R.string.no) {
                cancelSafeEditorRead()
                super.finish()
            }
        }
    }

    private fun saveText(check: Boolean, text: String, cursorPos: Int) {
        if (isRestoreRecoveryBlocked) return super.finish()
        when {
            text == viewModel.initialText -> {
                val returnText = !check &&
                    intent.getBooleanExtra("returnUnchangedText", false)
                if (returnText || cursorPos > 0) {
                    val result = Intent().apply {
                        if (returnText) putEditorText(this, text)
                        putExtra("cursorPosition", cursorPos)
                    }
                    setResult(RESULT_OK, result)
                }
                super.finish()
            }
            check -> {
                alert(R.string.exit) {
                    setMessage(R.string.exit_no_save)
                    positiveButton(R.string.yes)
                    negativeButton(R.string.no) {
                        if (cursorPos > 0) {
                            val result = Intent().apply {
                                putExtra("cursorPosition", cursorPos)
                            }
                            setResult(RESULT_OK, result)
                        }
                        super.finish()
                    }
                }
            }
            else -> {
                val result = Intent().apply {
                    putEditorText(this, text)
                    putExtra("cursorPosition", cursorPos)
                }
                setResult(RESULT_OK, result)
                super.finish()
            }
        }
    }

    private fun putEditorText(intent: Intent, text: String) {
        if (this.intent.getBooleanExtra("useTextFile", false) &&
            text.length > EditSafety.MAX_INLINE_TEXT_LENGTH
        ) {
            intent.putExtra("textFile", CodeTextTransfer.write(this, text))
        } else {
            intent.putExtra("text", text)
        }
    }

    private fun returnText(action: String) {
        if (isRestoreRecoveryBlocked) return super.finish()
        if (!viewModel.writable) return
        if (useSafeEditor) {
            if (safeEditorReadPending) return
            if (safeEditorStatus != SafeEditorStatus.READY) {
                toastOnUi(R.string.safe_code_editor_load_failed)
                return
            }
            readSafeEditorState { state ->
                val resolvedState = state.resolveAgainst(viewModel.initialText)
                returnText(action, resolvedState.text, resolvedState.cursorPosition)
            }
            return
        }
        returnText(action, editor.text.toString(), editor.cursor?.left ?: 0)
    }

    private fun returnText(action: String, text: String, cursorPosition: Int) {
        if (isRestoreRecoveryBlocked) return super.finish()
        val result = Intent().apply {
            putEditorText(this, text)
            putExtra("cursorPosition", cursorPosition)
            putExtra(EXTRA_RESULT_ACTION, action)
        }
        setResult(RESULT_OK, result)
        super.finish()
    }

    private fun isLoginSourceActionEnabled(): Boolean {
        return intent.getBooleanExtra(EXTRA_SHOW_LOGIN_SOURCE, false)
    }

    override fun upEdit(fontSize: Int?, autoComplete: Boolean?, autoWarp: Boolean?, editNonPrintable: Int?) {
        if (useSafeEditor) return
        if (fontSize != null) {
            editor.setTextSize(fontSize.toFloat())
        }
        if (autoComplete != null) {
            viewModel.language?.isAutoCompleteEnabled = autoComplete
            editor.setEditorLanguage(viewModel.language)
        }
        if (autoWarp != null) {
            editor.isWordwrap = autoWarp
        }
        if (editNonPrintable != null) {
            editor.nonPrintablePaintingFlags = editNonPrintable
        }
    }

    override fun upTheme(index: Int) {
        if (useSafeEditor) return
        if (themeIndex != index) {
            viewModel.loadTextMateThemes(index)
            editor.setEditorLanguage(viewModel.language) //每次更改颜色后需要再执行一次语言设置,防止切换主题后高亮颜色不正确
            themeIndex = index
        }
    }

    private fun setSearchOptions() {
        searchOptions = SearchOptions(
            if (isRegex) SearchOptions.TYPE_REGULAR_EXPRESSION else SearchOptions.TYPE_NORMAL,
            !isRegex,
            RegexBackrefGrammar.DEFAULT
        )
    }

    private fun closeSearchGroup() {
        showSearchGroup = false
        showReplaceGroup = false
        focusedSearchField = null
        editorSearcher.stopSearch()
        receiptSearch?.unsubscribe()
        receiptChange?.unsubscribe()
        receiptSearch = null
        receiptChange = null
        editor.requestFocus()
        editor.invalidate()
    }

    private fun search() {
        if (useSafeEditor) return
        if (showSearchGroup) {
            closeSearchGroup()
            return
        }
        isRegexState = isRegex
        findTextState = findText
        replaceTextState = replaceText
        setSearchOptions()
        receiptSearch?.unsubscribe()
        receiptChange?.unsubscribe()
        receiptSearch =
            editor.subscribeEvent(PublishSearchResultEvent::class.java) { event, _ ->
                if (event.editor == editor) {
                    updateSearchResults()
                }
            }
        receiptChange = editor.subscribeEvent(SelectionChangeEvent::class.java) { event, _ ->
            if (event.cause == SelectionChangeEvent.CAUSE_SEARCH) {
                updateSearchResults()
            }
        }
        showSearchGroup = true
        searchTxt(findText)
    }

    private fun searchTxt(txt: String) {
        if (txt.isNotEmpty()) {
            try {
                searchOptions?.let {
                    editorSearcher.search(txt, it)
                }
            } catch (_: java.util.regex.PatternSyntaxException) {
                // 忽略正则表达式语法错误
                editorSearcher.stopSearch()
                editor.invalidate()
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun updateSearchResults() {
        if (editorSearcher.hasQuery()) {
            val totalResults = editorSearcher.matchedPositionCount
            val currentPosition = editorSearcher.currentMatchedPositionIndex + 1
            searchResultTextState =
                "${if (currentPosition > 0) "$currentPosition/" else ""}$totalResults"
        }
    }

    private fun showCurlAnalyzeUrlConverter() {
        val input = if (!useSafeEditor && editor.cursor.isSelected) {
            editor.text.substring(editor.cursor.left, editor.cursor.right)
        } else {
            ""
        }
        showDialogFragment(CurlAnalyzeUrlDialog(input, viewModel.writable))
    }

    override fun finish() {
        if (isRestoreRecoveryBlocked) return super.finish()
        if (isFinishing || isDestroyed) {
            super.finish()
            return
        }
        save(true)
    }

    override fun helpActions(): List<SelectItem<String>> {
        return emptyList()
    }

    override fun onHelpActionSelect(action: String) {
    }

    override fun sendText(text: String) {
        if (useSafeEditor) {
            insertSafeEditorText(text)
            return
        }
        when (focusedSearchField) {
            "find" -> {
                findTextState += text
                findText = findTextState
                searchTxt(findText)
            }
            "replace" -> {
                replaceTextState += text
                replaceText = replaceTextState
            }
            else -> {
                editor.insertText(text, text.length)
            }
        }
    }

    override fun onCurlAnalyzeUrlInsert(text: String, onResult: (Boolean) -> Unit) {
        if (!viewModel.writable) {
            onResult(false)
        } else if (useSafeEditor) {
            insertSafeEditorText(text, onResult)
        } else {
            editor.insertText(text, text.length)
            onResult(true)
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    override fun onUndoClicked() {
        if (useSafeEditor) {
            if (
                safeEditorStatus == SafeEditorStatus.READY &&
                viewModel.writable &&
                !safeEditorReadPending
            ) {
                safeEditor?.evaluateJavascript("document.execCommand('undo');", null)
            }
        } else {
            editor.undo()
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    override fun onRedoClicked() {
        if (useSafeEditor) {
            if (
                safeEditorStatus == SafeEditorStatus.READY &&
                viewModel.writable &&
                !safeEditorReadPending
            ) {
                safeEditor?.evaluateJavascript("document.execCommand('redo');", null)
            }
        } else {
            editor.redo()
        }
    }
}

internal fun shouldShowLoginSourceAction(writable: Boolean, requested: Boolean): Boolean {
    return writable && requested
}

internal fun shouldShowJavaScriptSyntaxAction(
    useSafeEditor: Boolean,
    requested: Boolean,
): Boolean {
    return !useSafeEditor && requested
}
