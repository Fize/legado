package io.legado.app.ui.login

import android.annotation.SuppressLint
import android.content.DialogInterface
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.script.rhino.runScriptWithContext
import io.legado.app.R
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.rule.RowUi
import io.legado.app.data.entities.rule.RowUi.Type
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.selector
import io.legado.app.model.jsSource.JsSourceEngine
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.compose.component.LegadoTopAppBar
import io.legado.app.utils.GSON
import io.legado.app.utils.buildMainHandler
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.indexOf
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.openUrl
import io.legado.app.utils.printOnDebug
import io.legado.app.utils.resolveExploreFlexBasisPercent
import io.legado.app.utils.sendToClip
import io.legado.app.utils.setLayout
import io.legado.app.utils.shouldUseTwoPane
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.init.appCtx

class SourceLoginDialog : BaseComposeDialogFragment(true),
    SourceLoginJsExtensions.Callback {

    private val viewModel by activityViewModels<SourceLoginViewModel>()
    private var lastClickTime: Long = 0
    private var oKToClose = false
    private var rowUis: List<RowUi>? = null
    private var rowUisState by mutableStateOf<List<RowUi>>(emptyList())
    private var rowUiName = arrayListOf<String>()
    private val fieldValues = mutableStateMapOf<String, String>()
    private val resolvedLabels = mutableStateMapOf<String, String>()
    private val textDebounceRunnables = hashMapOf<String, Runnable>()
    private var hasChange = false
    private var loginUrl: String? = null
    private var renderJob: Job? = null
    private var isRenderingLoginUi by mutableStateOf(false)
    private var isLoginUiV2 by mutableStateOf(false)
    private var v2Delegate by mutableStateOf<SourceLoginV2Delegate?>(null)
    private val sourceLoginJsExtensions by lazy {
        SourceLoginJsExtensions(
            activity as AppCompatActivity,
            viewModel.source,
            viewModel.bookType,
            this
        )
    }

    private var initHandler = false
    private val handler by lazy {
        initHandler = true
        buildMainHandler()
    }

    override fun upUiData(data: Map<String, Any?>?) {
        try {
            activity?.runOnUiThread { // 在主线程中更新 UI
                handleUpUiData(data)
            }
        } catch (e: Exception) {
            AppLog.put("upLoginData Error: " + e.localizedMessage, e)
        }
    }

    override fun reUiView(deltaUp: Boolean) {
        activity?.runOnUiThread {
            handleReUiView(deltaUp)
        }
    }

    private fun handleReUiView(deltaUp: Boolean) {
        if (view == null) return
        val source = viewModel.source ?: return
        val loginUiStr = source.loginUi ?: return
        val codeStr = source.getLoginUiJs()
        if (codeStr != null) {
            hasChange = true
            renderLoginUi(source, codeStr, deltaUp, installMenu = false)
        } else {
            cancelLoginUiRender()
            rowUis = loginUi(loginUiStr)
            rowUiBuilder(source, rowUis, deltaUp)
        }
    }

    @SuppressLint("SetTextI18n")
    private fun handleUpUiData(data: Map<String, Any?>?) {
        if (view == null) return
        hasChange = true
        if (data == null) {
            val newLoginInfo: MutableMap<String, String> = mutableMapOf()
            rowUis?.forEach { rowUi ->
                val default = rowUi.default
                when (rowUi.type) {
                    Type.text, Type.password -> {
                        val value = default ?: ""
                        newLoginInfo[rowUi.name] = value
                        fieldValues[rowUi.name] = value
                    }

                    Type.button -> {
                        resolvedLabels[rowUi.name] = rowUi.viewName ?: rowUi.name
                    }

                    Type.toggle -> {
                        val char = default ?: run {
                            val chars = rowUi.chars?.filterNotNull() ?: listOf("chars is null")
                            chars.getOrNull(0) ?: ""
                        }
                        newLoginInfo[rowUi.name] = char
                        fieldValues[rowUi.name] = char
                    }

                    Type.select -> {
                        val chars = rowUi.chars?.filterNotNull() ?: listOf("chars", "is null")
                        val value = default ?: (chars.getOrNull(0) ?: "")
                        newLoginInfo[rowUi.name] = value
                        fieldValues[rowUi.name] = value
                    }
                }
            }
            viewModel.loginInfo = newLoginInfo
            return
        }
        val loginInfo = viewModel.loginInfo
        data.forEach { (key, rawValue) ->
            val strValue = rawValue?.toString()
            val index = rowUiName.indexOf(key)
            if (index != -1) {
                val rowUi = rowUis?.getOrNull(index) ?: return@forEach
                val value = strValue ?: rowUi.default
                when (rowUi.type) {
                    Type.text, Type.password -> {
                        val v = value ?: ""
                        loginInfo[rowUi.name] = v
                        fieldValues[rowUi.name] = v
                    }

                    Type.button -> {
                        resolvedLabels[rowUi.name] = value ?: rowUi.viewName ?: key
                    }

                    Type.toggle -> {
                        val char = value ?: run {
                            val chars = rowUi.chars?.filterNotNull() ?: listOf("chars is null")
                            chars.getOrNull(0) ?: ""
                        }
                        loginInfo[rowUi.name] = char
                        fieldValues[rowUi.name] = char
                    }

                    Type.select -> {
                        val items = rowUi.chars?.filterNotNull() ?: listOf("chars", "is null")
                        val selected = value?.takeIf { it in items } ?: items.firstOrNull().orEmpty()
                        loginInfo[rowUi.name] = selected
                        fieldValues[rowUi.name] = selected
                    }
                }
            } else {
                loginInfo[key] = strValue ?: ""
            }
        }
    }

    override fun onStart() {
        super.onStart()
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    suspend fun evalUiJs(jsStr: String): String? {
        return evalUiJsResult(jsStr).getOrNull()
    }

    private suspend fun evalUiJsResult(jsStr: String): Result<String> {
        val source = viewModel.source
            ?: return Result.failure(IllegalStateException("Missing login source"))
        val loginJS = loginUrl ?: ""
        val result = rowUis?.let {
            getLoginData(it)
        } ?: viewModel.loginInfo.toMutableMap()
        return evaluateLoginUiScript(
            block = {
                runScriptWithContext {
                    JsSourceEngine.normalizeJsResult(
                        source.evalJS("$loginJS\n$jsStr") {
                            put("result", result)
                            put("book", viewModel.book)
                            put("chapter", viewModel.chapter)
                        }
                    ).orEmpty()
                }
            },
            onFailure = { error ->
                AppLog.put(
                    source.getTag() + " loginUi err:" +
                        (error.localizedMessage ?: error.toString()),
                    error,
                )
            },
        )
    }

    fun loginUi(json: String?): List<RowUi>? {
        return parseLoginUi(json).getOrNull()
    }

    private fun parseLoginUi(json: String?): Result<List<RowUi>> {
        return GSON.fromJsonArray<RowUi>(json).onFailure {
            AppLog.put("loginUi json parse err:" + it.localizedMessage, it)
        }
    }

    @SuppressLint("SetTextI18n")
    private fun rowUiBuilder(source: BaseSource, rowUis: List<RowUi>?, deltaUp: Boolean) {
        val loginInfo = viewModel.loginInfo
        if (!deltaUp) {
            rowUiName.clear()
            fieldValues.clear()
            resolvedLabels.clear()
        }
        rowUis?.forEachIndexed { index, rowUi ->
            val type = rowUi.type
            val name = rowUi.name
            val viewName = rowUi.viewName
            val default = rowUi.default
            if (deltaUp) {
                val oldIndex = rowUiName.indexOf(name, index)
                if (oldIndex == index) {
                    return@forEachIndexed
                } else if (oldIndex >= 0) {
                    rowUiName.removeAt(oldIndex)
                    rowUiName.add(index, name)
                    return@forEachIndexed
                }
                if (oldIndex == -2) {
                    rowUiName.add(name)
                } else {
                    rowUiName.add(index, name)
                }
            } else {
                rowUiName.add(name)
            }

            if (viewName == null) {
                resolvedLabels[name] = name
            } else if (viewName.length in 3..19 && viewName.first() == '\'' && viewName.last() == '\'') {
                val n = viewName.substring(1, viewName.length - 1)
                rowUi.viewName = n
                resolvedLabels[name] = n
            } else {
                resolvedLabels[name] = name
                execute {
                    evalUiJs(viewName)
                }.onSuccess { n ->
                    if (n.isNullOrEmpty()) {
                        resolvedLabels[name] = "null"
                    } else {
                        rowUi.viewName = n
                        resolvedLabels[name] = n
                    }
                }.onError { _ ->
                    resolvedLabels[name] = "err"
                }
            }

            when (type) {
                Type.text, Type.password -> {
                    fieldValues[name] = loginInfo[name] ?: default ?: ""
                }

                Type.select -> {
                    val chars = rowUi.chars?.filterNotNull() ?: listOf("chars", "is null")
                    val infoV = loginInfo[name]
                    val char = if (infoV.isNullOrEmpty()) {
                        hasChange = true
                        default ?: chars.firstOrNull().orEmpty()
                    } else {
                        infoV
                    }
                    loginInfo[name] = char
                    fieldValues[name] = char
                }

                Type.button -> Unit

                Type.toggle -> {
                    val chars = rowUi.chars?.filterNotNull() ?: listOf("chars is null")
                    val infoV = loginInfo[name]
                    val char = if (infoV.isNullOrEmpty()) {
                        hasChange = true
                        default ?: chars.firstOrNull().orEmpty()
                    } else {
                        infoV
                    }
                    loginInfo[name] = char
                    fieldValues[name] = char
                }
            }
        }
        if (deltaUp && rowUis != null) {
            val newRowUiSize = rowUis.size
            if (rowUiName.size > newRowUiSize) {
                rowUiName.subList(newRowUiSize, rowUiName.size).clear()
            }
        }
        rowUisState = rowUis.orEmpty()
    }

    private fun buttonUi(source: BaseSource, rowUis: List<RowUi>?) {
        rowUiBuilder(source, rowUis, false)
    }

    private fun showLoginHeader(source: BaseSource) {
        alert {
            setTitle(R.string.login_header)
            source.getLoginHeader()?.takeIf { it.isNotBlank() }?.let { loginHeader ->
                setMessage(loginHeader)
                positiveButton(R.string.copy_text) {
                    appCtx.sendToClip(loginHeader)
                }
            } ?: setMessage(R.string.empty)
        }
    }

    private fun clearLoginInfo(source: BaseSource) {
        alert(
            R.string.clear_login_info,
            R.string.sure_del
        ) {
            yesButton {
                source.removeLoginInfo()
                source.removeLoginHeader()
                viewModel.loginInfo.clear()
                oKToClose = true
                context?.toastOnUi(R.string.success)
                dismiss()
            }
            noButton()
        }
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        val source = viewModel.source ?: return
        loginUrl = source.getLoginJs()
        val loginUiStr = source.loginUi ?: return
        isLoginUiV2 = source.isLoginUiV2()
        if (isLoginUiV2) {
            v2Delegate = SourceLoginV2Delegate(
                this,
                source,
                viewModel.book,
                viewModel.chapter,
            )
        } else {
            val codeStr = source.getLoginUiJs()
            if (codeStr != null) {
                renderLoginUi(source, codeStr, deltaUp = false, installMenu = true)
            } else {
                rowUis = loginUi(loginUiStr)
                buttonUi(source, rowUis)
            }
        }
        v2Delegate?.start()
    }

    @OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
    @Composable
    override fun Content() {
        val source = viewModel.source ?: return
        var showMenu by remember { mutableStateOf(false) }
        val cfg = LocalConfiguration.current
        val isTwoPane = shouldUseTwoPane(cfg.screenWidthDp, cfg.screenHeightDp)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 4.dp
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    LegadoTopAppBar(
                        title = stringResource(R.string.login_source, source.getTag()),
                        actions = {
                            if (!isLoginUiV2) {
                                IconButton(
                                    onClick = {
                                        if (!isRenderingLoginUi && rowUis != null) {
                                            oKToClose = true
                                            login(source)
                                        }
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = stringResource(R.string.ok)
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
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.show_login_header)) },
                                        leadingIcon = {
                                            Icon(
                                                painter = painterResource(R.drawable.ic_add_online),
                                                contentDescription = null
                                            )
                                        },
                                        onClick = {
                                            showMenu = false
                                            showLoginHeader(source)
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.del_login_header)) },
                                        leadingIcon = {
                                            Icon(
                                                painter = painterResource(R.drawable.ic_clear),
                                                contentDescription = null
                                            )
                                        },
                                        onClick = {
                                            showMenu = false
                                            source.removeLoginHeader()
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.clear_login_info)) },
                                        leadingIcon = {
                                            Icon(
                                                painter = painterResource(R.drawable.ic_clear_all),
                                                contentDescription = null
                                            )
                                        },
                                        onClick = {
                                            showMenu = false
                                            clearLoginInfo(source)
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.log)) },
                                        leadingIcon = {
                                            Icon(
                                                painter = painterResource(R.drawable.ic_history),
                                                contentDescription = null
                                            )
                                        },
                                        onClick = {
                                            showMenu = false
                                            showDialogFragment<AppLogDialog>()
                                        }
                                    )
                                }
                            }
                        }
                    )

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(8.dp)
                    ) {
                        val delegate = v2Delegate
                        if (isLoginUiV2 && delegate != null) {
                            delegate.Content()
                        } else {
                            FlowRow(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(4.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                rowUisState.forEach { rowUi ->
                                    val name = rowUi.name
                                    val label = resolvedLabels[name] ?: rowUi.viewName ?: name
                                    val basis = resolveExploreFlexBasisPercent(
                                        configuredBasis = rowUi.style().layout_flexBasisPercent,
                                        screenWidthDp = cfg.screenWidthDp,
                                        isTwoPane = isTwoPane
                                    )
                                    val rowModifier = when {
                                        basis > 0f -> Modifier.fillMaxWidth(basis.coerceIn(0.1f, 1f))
                                        rowUi.style().layout_flexGrow > 0f -> Modifier.weight(rowUi.style().layout_flexGrow)
                                        else -> Modifier.fillMaxWidth()
                                    }

                                    when (rowUi.type) {
                                        Type.text, Type.password -> {
                                            val isPassword = rowUi.type == Type.password
                                            var passwordVisible by remember(name) { mutableStateOf(false) }
                                            OutlinedTextField(
                                                value = fieldValues[name].orEmpty(),
                                                onValueChange = { newText ->
                                                    val oldText = fieldValues[name]
                                                    fieldValues[name] = newText
                                                    viewModel.loginInfo[name] = newText
                                                    hasChange = true
                                                    rowUi.action?.let { jsStr ->
                                                        if (newText != oldText) {
                                                            textDebounceRunnables[name]?.let(handler::removeCallbacks)
                                                            val runnable = Runnable {
                                                                handleButtonClick(source, jsStr, name, false)
                                                            }
                                                            textDebounceRunnables[name] = runnable
                                                            handler.postDelayed(runnable, 600)
                                                        }
                                                    }
                                                },
                                                label = { Text(label) },
                                                visualTransformation = if (isPassword && !passwordVisible) {
                                                    PasswordVisualTransformation()
                                                } else {
                                                    VisualTransformation.None
                                                },
                                                trailingIcon = if (isPassword) {
                                                    {
                                                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                                            Icon(
                                                                imageVector = if (passwordVisible) {
                                                                    Icons.Default.VisibilityOff
                                                                } else {
                                                                    Icons.Default.Visibility
                                                                },
                                                                contentDescription = null
                                                            )
                                                        }
                                                    }
                                                } else {
                                                    null
                                                },
                                                modifier = rowModifier.heightIn(min = 48.dp)
                                            )
                                        }

                                        Type.select -> {
                                            val chars = rowUi.chars?.filterNotNull() ?: listOf("chars", "is null")
                                            val selectValue = {
                                                context?.selector(label, chars) { _, item, _ ->
                                                    hasChange = true
                                                    viewModel.loginInfo[name] = item
                                                    fieldValues[name] = item
                                                    if (rowUi.action != null) {
                                                        handleButtonClick(source, rowUi.action, name, false)
                                                    }
                                                }
                                                Unit
                                            }
                                            Box(modifier = rowModifier) {
                                                OutlinedTextField(
                                                    value = fieldValues[name].orEmpty(),
                                                    onValueChange = {},
                                                    readOnly = true,
                                                    label = { Text(label) },
                                                    trailingIcon = {
                                                        IconButton(onClick = { selectValue() }) {
                                                            Icon(
                                                                imageVector = Icons.Default.ArrowDropDown,
                                                                contentDescription = null
                                                            )
                                                        }
                                                    },
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .heightIn(min = 48.dp)
                                                )
                                                Box(
                                                    modifier = Modifier
                                                        .matchParentSize()
                                                        .clickable { selectValue() }
                                                )
                                            }
                                        }

                                        Type.button -> {
                                            val textAlign = when (rowUi.style().layout_justifySelf) {
                                                "flex_start" -> TextAlign.Start
                                                "flex_end" -> TextAlign.End
                                                else -> TextAlign.Center
                                            }
                                            Surface(
                                                modifier = rowModifier.combinedClickable(
                                                    onClick = {
                                                        val now = System.currentTimeMillis()
                                                        if (now - lastClickTime >= 200) {
                                                            lastClickTime = now
                                                            handleButtonClick(source, rowUi.action, name, false)
                                                        }
                                                    },
                                                    onLongClick = {
                                                        val now = System.currentTimeMillis()
                                                        if (now - lastClickTime >= 200) {
                                                            lastClickTime = now
                                                            handleButtonClick(source, rowUi.action, name, true)
                                                        }
                                                    }
                                                ),
                                                shape = RoundedCornerShape(8.dp),
                                                color = MaterialTheme.colorScheme.secondaryContainer,
                                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                                            ) {
                                                Text(
                                                    text = label,
                                                    textAlign = textAlign,
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(16.dp)
                                                )
                                            }
                                        }

                                        Type.toggle -> {
                                            val chars = rowUi.chars?.filterNotNull() ?: listOf("chars is null")
                                            val left = rowUi.style().layout_justifySelf != "right"
                                            val currentChar = fieldValues[name].orEmpty()
                                            val display = if (left) currentChar + label else label + currentChar
                                            val textAlign = when (rowUi.style().layout_justifySelf) {
                                                "flex_start" -> TextAlign.Start
                                                "flex_end" -> TextAlign.End
                                                else -> TextAlign.Center
                                            }
                                            val onToggle = { isLong: Boolean ->
                                                val currentIndex = chars.indexOf(currentChar)
                                                val nextIndex = (currentIndex + 1) % chars.size
                                                val nextChar = chars.getOrNull(nextIndex) ?: ""
                                                hasChange = true
                                                viewModel.loginInfo[name] = nextChar
                                                fieldValues[name] = nextChar
                                                handleButtonClick(source, rowUi.action, name, isLong)
                                            }
                                            Surface(
                                                modifier = rowModifier.combinedClickable(
                                                    onClick = {
                                                        val now = System.currentTimeMillis()
                                                        if (now - lastClickTime >= 200) {
                                                            lastClickTime = now
                                                            onToggle(false)
                                                        }
                                                    },
                                                    onLongClick = {
                                                        val now = System.currentTimeMillis()
                                                        if (now - lastClickTime >= 200) {
                                                            lastClickTime = now
                                                            onToggle(true)
                                                        }
                                                    }
                                                ),
                                                shape = RoundedCornerShape(8.dp),
                                                color = MaterialTheme.colorScheme.secondaryContainer,
                                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                                            ) {
                                                Text(
                                                    text = display,
                                                    textAlign = textAlign,
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(16.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            if (isRenderingLoginUi) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(36.dp),
                                        strokeWidth = 2.dp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun renderLoginUi(
        source: BaseSource,
        codeStr: String,
        deltaUp: Boolean,
        installMenu: Boolean,
    ) {
        renderJob?.cancel()
        isRenderingLoginUi = true
        renderJob = viewLifecycleOwner.lifecycleScope.launch(Main) {
            val renderedRowsResult = withContext(IO) {
                evalUiJsResult(codeStr).fold(
                    onSuccess = ::parseLoginUi,
                    onFailure = { Result.failure(it) },
                )
            }
            ensureActive()
            isRenderingLoginUi = false
            renderJob = null
            val renderDecision = resolveLoginUiRender(rowUis, renderedRowsResult)
            if (!renderDecision.shouldApply) return@launch
            val renderedRows = requireNotNull(renderDecision.value)
            rowUis = renderedRows
            if (installMenu) {
                buttonUi(source, renderedRows)
            } else {
                rowUiBuilder(source, renderedRows, deltaUp)
            }
        }
    }

    private fun cancelLoginUiRender() {
        renderJob?.cancel()
        renderJob = null
        isRenderingLoginUi = false
    }

    override fun onDestroyView() {
        v2Delegate?.destroy()
        v2Delegate = null
        renderJob?.cancel()
        renderJob = null
        isRenderingLoginUi = false
        super.onDestroyView()
    }

    private fun handleButtonClick(source: BaseSource, action: String?, name: String, isLongClick: Boolean) {
        lifecycleScope.launch(IO) {
            if (action.isAbsUrl()) {
                context?.openUrl(action!!)
            } else if (action != null) {
                // JavaScript
                val buttonFunctionJS = action
                val loginJS = loginUrl ?: return@launch
                kotlin.runCatching {
                    runScriptWithContext {
                        source.evalJS("$loginJS\n$buttonFunctionJS") {
                            put("java", sourceLoginJsExtensions)
                            put("result", getLoginData(rowUis))
                            put("book", viewModel.book)
                            put("chapter", viewModel.chapter)
                            put("isLongClick", isLongClick)
                        }
                    }
                }.onFailure { e ->
                    ensureActive()
                    AppLog.put("LoginUI Button $name JavaScript error", e)
                }
            }
        }
    }

    private fun getLoginData(rowUis: List<RowUi>?): MutableMap<String, String> {
        val loginData = hashMapOf<String, String>()
        rowUis?.forEach { rowUi ->
            when (rowUi.type) {
                Type.text, Type.password -> {
                    loginData[rowUi.name] = fieldValues[rowUi.name] ?: rowUi.default ?: ""
                }
            }
        }
        return viewModel.loginInfo.toMutableMap().apply { putAll(loginData) }
    }

    private fun login(source: BaseSource) {
        val loginData = getLoginData(rowUis)
        lifecycleScope.launch(IO) {
            if (loginData.isEmpty()) {
                source.removeLoginInfo()
                withContext(Main) {
                    dismiss()
                }
            } else if (source.putLoginInfo(GSON.toJson(loginData))) {
                try {
                    val loginJS = loginUrl
                    if (!loginJS.isNullOrBlank()) {
                        val buttonFunctionJS = "if (typeof login=='function'){ login.apply(this); } else { throw('Function login not implements!!!') }"
                        runScriptWithContext {
                            source.evalJS("$loginJS\n$buttonFunctionJS") {
                                put("java", sourceLoginJsExtensions)
                                put("result", loginData)
                                put("book", viewModel.book)
                                put("chapter", viewModel.chapter)
                                put("isLongClick", false)
                            }
                        }
                    }
                    context?.toastOnUi(R.string.success)
                    withContext(Main) {
                        dismiss()
                    }
                } catch (e: Exception) {
                    AppLog.put("登录出错\n${e.localizedMessage}", e)
                    context?.toastOnUi("登录出错\n${e.localizedMessage}")
                    e.printOnDebug()
                }
            }
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        if (!isLoginUiV2 && !oKToClose && hasChange) {
            val loginInfo = viewModel.loginInfo
            if (loginInfo.isEmpty()) {
                viewModel.source?.removeLoginInfo()
            } else {
                viewModel.source?.putLoginInfo(GSON.toJson(loginInfo))
            }
        }
        if (initHandler) {
            handler.removeCallbacksAndMessages(null)
        }
        super.onDismiss(dialog)
        activity?.finish()
    }

}
