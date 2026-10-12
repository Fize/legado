package io.legado.app.ui.login

import android.os.CountDownTimer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.script.rhino.runScriptWithContext
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.rule.RowUi
import io.legado.app.lib.dialogs.selector
import io.legado.app.model.login.LoginUiV2
import io.legado.app.utils.GSON
import io.legado.app.utils.resolveExploreFlexBasisPercent
import io.legado.app.utils.shouldUseTwoPane
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SourceLoginV2Delegate(
    private val fragment: SourceLoginDialog,
    private val source: BaseSource,
    private val book: Book?,
    private val chapter: BookChapter?,
) {

    private var stateJson = "{}"
    private var renderJob: Job? = null
    private var actionJob: Job? = null
    private var firstRender = true
    private val sessionValues = linkedMapOf<String, String>()

    var isLoading by mutableStateOf(false)
        private set
    var hasRenderError by mutableStateOf(false)
        private set
    var rowsState by mutableStateOf<List<RowUi>>(emptyList())
        private set

    private val fieldValues = mutableStateMapOf<String, String>()
    private val toggleValues = mutableStateMapOf<String, Boolean>()
    private val fieldErrors = mutableStateMapOf<String, String>()
    private val actionEnabled = mutableStateMapOf<String, Boolean>()
    private var toggleActionsEnabled by mutableStateOf(true)
    private val buttonLabels = mutableStateMapOf<String, String>()
    private val buttonDisplayTexts = mutableStateMapOf<String, String>()
    private val countdownLeft = hashMapOf<String, Int>()
    private val countdownTimers = hashMapOf<String, CountDownTimer>()

    private val scope get() = fragment.viewLifecycleOwner.lifecycleScope

    fun start() {
        render()
    }

    fun destroy() {
        renderJob?.cancel()
        actionJob?.cancel()
        countdownTimers.values.forEach(CountDownTimer::cancel)
        countdownTimers.clear()
        fieldValues.clear()
        toggleValues.clear()
        fieldErrors.clear()
        actionEnabled.clear()
        buttonLabels.clear()
        buttonDisplayTexts.clear()
        isLoading = false
    }

    private fun render(
        candidateState: String = stateJson,
        errors: Map<String, String>? = null,
        restoreActionOnFailure: String? = null,
    ) {
        renderJob?.cancel()
        collectForm()
        renderJob = scope.launch {
            val showLoading = firstRender
            if (showLoading) isLoading = true
            val result = withContext(IO) {
                runCatching {
                    runScriptWithContext {
                        val rows = LoginUiV2.parseRender(
                            source.evalLoginUiV2(candidateState, book, chapter)
                        )
                        rows to source.getLoginInfoMap()
                    }
                }.onFailure { ensureActive() }
            }
            ensureActive()
            if (showLoading) {
                firstRender = false
                isLoading = false
            }
            val rows = result.getOrNull()?.first
            if (rows == null) {
                restoreActionOnFailure?.let { setActionEnabled(it, true) }
                val error = result.exceptionOrNull()
                if (error == null) {
                    AppLog.put("登录UI v2 渲染结果格式错误")
                } else {
                    AppLog.put("登录UI v2 渲染出错", error)
                }
                if (rowsState.isEmpty()) {
                    showRenderError()
                } else {
                    fragment.context?.toastOnUi(R.string.login_ui_v2_render_error)
                    errors?.let(::applyErrors)
                }
                return@launch
            }
            collectForm()
            stateJson = candidateState
            buildViews(rows, result.getOrThrow().second)
            errors?.let(::applyErrors)
        }
    }

    private fun showRenderError() {
        rowsState = emptyList()
        fieldValues.clear()
        toggleValues.clear()
        fieldErrors.clear()
        actionEnabled.clear()
        buttonLabels.clear()
        buttonDisplayTexts.clear()
        hasRenderError = true
    }

    private fun buildViews(rows: List<RowUi>, stored: Map<String, String>) {
        hasRenderError = false
        fieldValues.clear()
        toggleValues.clear()
        fieldErrors.clear()
        actionEnabled.clear()
        buttonLabels.clear()
        buttonDisplayTexts.clear()
        toggleActionsEnabled = true
        rows.forEach { row ->
            when (row.type) {
                RowUi.Type.text -> addField(row, stored, password = false)
                RowUi.Type.password -> addField(row, stored, password = true)
                RowUi.Type.label -> addLabel(row)
                RowUi.Type.select -> addSelect(row, stored)
                RowUi.Type.toggle -> addToggle(row, stored)
                RowUi.Type.button -> addButton(row)
            }
        }
        rowsState = rows
        countdownLeft.forEach { (action, left) ->
            if (left > 0) applyCountdown(action, left)
        }
    }

    private fun addField(row: RowUi, stored: Map<String, String>, password: Boolean) {
        val key = requireNotNull(row.key)
        fieldValues[key] = LoginUiV2.resolveFieldValue(row.value, sessionValues[key], stored[key]).orEmpty()
    }

    private fun addLabel(row: RowUi) = Unit

    private fun addSelect(row: RowUi, stored: Map<String, String>) {
        val key = requireNotNull(row.key)
        val options = requireNotNull(row.options)
        val value = LoginUiV2.resolveFieldValue(row.value, sessionValues[key], stored[key])
            ?.takeIf { it in options }
            ?: options.first()
        fieldValues[key] = value
    }

    private fun addButton(row: RowUi) {
        val action = requireNotNull(row.action)
        buttonLabels[action] = row.name
        buttonDisplayTexts[action] = row.name
        actionEnabled[action] = true
    }

    private fun addToggle(row: RowUi, stored: Map<String, String>) {
        val key = requireNotNull(row.key)
        toggleValues[key] = LoginUiV2.resolveFieldValue(
            row.value,
            sessionValues[key],
            stored[key],
        ) == "true"
    }

    private fun collectForm(): Map<String, String> {
        val form = linkedMapOf<String, String>()
        fieldValues.forEach { (key, value) ->
            form[key] = value
        }
        toggleValues.forEach { (key, isChecked) ->
            form[key] = isChecked.toString()
        }
        sessionValues.putAll(form)
        return form
    }

    private fun clearErrors() {
        fieldErrors.clear()
    }

    private fun applyErrors(errors: Map<String, String>) {
        errors.forEach { (key, message) ->
            if (key !in fieldValues) {
                fragment.context?.toastOnUi(message)
            } else {
                fieldErrors[key] = message
            }
        }
    }

    private fun dispatch(action: String, countdownSeconds: Int?) {
        if (renderJob?.isActive == true ||
            actionJob?.isActive == true ||
            countdownLeft.getOrDefault(action, 0) > 0
        ) {
            return
        }
        clearErrors()
        val formJson = GSON.toJson(collectForm())
        setActionEnabled(action, false)
        actionJob = scope.launch {
            val result = withContext(IO) {
                runCatching {
                    runScriptWithContext {
                        source.evalLoginActionV2(action, stateJson, formJson, book, chapter)
                    }
                }.onFailure { ensureActive() }
            }
            ensureActive()
            val error = result.exceptionOrNull()
            if (error != null) {
                setActionEnabled(action, true)
                AppLog.put("登录UI v2 动作 $action 出错", error)
                fragment.context?.toastOnUi(
                    fragment.getString(
                        R.string.login_ui_v2_action_error,
                        error.localizedMessage ?: error.toString(),
                    )
                )
                return@launch
            }
            val command = LoginUiV2.parseActionResult(result.getOrNull())
            if (command.malformed) {
                setActionEnabled(action, true)
                AppLog.put("登录UI v2 动作 $action 返回了无效命令")
                fragment.context?.toastOnUi(R.string.login_ui_v2_invalid_action)
                return@launch
            }
            command.unknownKeys.forEach {
                AppLog.put("登录UI v2 动作 $action 返回未知命令 $it,已忽略")
            }
            command.loginJson?.let { loginJson ->
                val saved = withContext(IO) { source.putLoginInfo(loginJson) }
                if (!saved) {
                    setActionEnabled(action, true)
                    fragment.context?.toastOnUi(R.string.login_ui_v2_save_error)
                    return@launch
                }
            }
            val errors = command.error.orEmpty()
            if (command.close) {
                fragment.dismissAllowingStateLoss()
                return@launch
            }
            val nextState = command.stateJson
            if (nextState == null) {
                setActionEnabled(action, true)
            }
            if (errors.isEmpty() && countdownSeconds != null && countdownSeconds > 0) {
                startCountdown(action, countdownSeconds)
            }
            if (nextState == null) {
                applyErrors(errors)
            } else {
                render(nextState, errors, action)
            }
        }
    }

    private fun setActionEnabled(action: String, enabled: Boolean) {
        toggleActionsEnabled = enabled
        val buttonEnabled = enabled && countdownLeft.getOrDefault(action, 0) <= 0
        actionEnabled[action] = buttonEnabled
    }

    private fun startCountdown(action: String, seconds: Int) {
        countdownTimers.remove(action)?.cancel()
        countdownLeft[action] = seconds
        applyCountdown(action, seconds)
        countdownTimers[action] = object : CountDownTimer(seconds * 1000L, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                val left = ((millisUntilFinished + 999) / 1000).toInt()
                countdownLeft[action] = left
                applyCountdown(action, left)
            }

            override fun onFinish() {
                countdownLeft.remove(action)
                countdownTimers.remove(action)
                actionEnabled[action] = true
                buttonDisplayTexts[action] = buttonLabels[action].orEmpty()
            }
        }.apply { start() }
    }

    private fun applyCountdown(action: String, left: Int) {
        actionEnabled[action] = false
        buttonDisplayTexts[action] = "${buttonLabels[action]} (${left}s)"
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    fun Content() {
        val cfg = LocalConfiguration.current
        val isTwoPane = shouldUseTwoPane(cfg.screenWidthDp, cfg.screenHeightDp)

        Column(modifier = Modifier.fillMaxWidth()) {
            if (hasRenderError) {
                SelectionContainer {
                    Text(
                        text = stringResource(R.string.login_ui_v2_render_error),
                        fontSize = 14.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            } else {
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    rowsState.forEach { row ->
                        val basis = resolveExploreFlexBasisPercent(
                            configuredBasis = row.style().layout_flexBasisPercent,
                            screenWidthDp = cfg.screenWidthDp,
                            isTwoPane = isTwoPane
                        )
                        val rowModifier = when {
                            basis > 0f -> Modifier.fillMaxWidth(basis.coerceIn(0.1f, 1f))
                            row.style().layout_flexGrow > 0f -> Modifier.weight(row.style().layout_flexGrow)
                            else -> Modifier.fillMaxWidth()
                        }
                        when (row.type) {
                            RowUi.Type.text, RowUi.Type.password -> {
                                val key = row.key.orEmpty()
                                val isPassword = row.type == RowUi.Type.password
                                var passwordVisible by remember(key) { mutableStateOf(false) }
                                val errorMsg = fieldErrors[key]
                                OutlinedTextField(
                                    value = fieldValues[key].orEmpty(),
                                    onValueChange = {
                                        fieldValues[key] = it
                                        fieldErrors.remove(key)
                                    },
                                    label = { Text(row.name) },
                                    placeholder = row.hint?.let { hintText -> { Text(hintText) } },
                                    isError = errorMsg != null,
                                    supportingText = errorMsg?.let { msg -> { Text(msg) } },
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

                            RowUi.Type.label -> {
                                SelectionContainer {
                                    Text(
                                        text = row.name,
                                        fontSize = 14.sp,
                                        modifier = rowModifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                    )
                                }
                            }

                            RowUi.Type.select -> {
                                val key = row.key.orEmpty()
                                val options = row.options.orEmpty()
                                val errorMsg = fieldErrors[key]
                                val selectValue = {
                                    fragment.context?.selector(row.name, options) { _, item, _ ->
                                        fieldValues[key] = item
                                        fieldErrors.remove(key)
                                    }
                                    Unit
                                }
                                Box(modifier = rowModifier) {
                                    OutlinedTextField(
                                        value = fieldValues[key].orEmpty(),
                                        onValueChange = {},
                                        readOnly = true,
                                        label = { Text(row.name) },
                                        isError = errorMsg != null,
                                        supportingText = errorMsg?.let { msg -> { Text(msg) } },
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

                            RowUi.Type.toggle -> {
                                val key = row.key.orEmpty()
                                val hasAction = row.action != null
                                val enabled = if (hasAction) toggleActionsEnabled else true
                                Row(
                                    modifier = rowModifier
                                        .heightIn(min = 48.dp)
                                        .alpha(if (enabled) 1f else 0.5f)
                                        .clickable(enabled = enabled) {
                                            val next = !(toggleValues[key] ?: false)
                                            toggleValues[key] = next
                                            row.action?.let { action -> dispatch(action, null) }
                                        }
                                        .padding(horizontal = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = row.name,
                                        fontSize = 14.sp,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Switch(
                                        checked = toggleValues[key] ?: false,
                                        onCheckedChange = { isChecked ->
                                            toggleValues[key] = isChecked
                                            row.action?.let { action -> dispatch(action, null) }
                                        },
                                        enabled = enabled
                                    )
                                }
                            }

                            RowUi.Type.button -> {
                                val action = row.action.orEmpty()
                                val enabled = actionEnabled[action] ?: true
                                val label = buttonDisplayTexts[action] ?: row.name
                                Surface(
                                    modifier = rowModifier
                                        .alpha(if (enabled) 1f else 0.5f)
                                        .clickable(enabled = enabled) {
                                            dispatch(action, row.countdown)
                                        },
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                                ) {
                                    Text(
                                        text = label,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(36.dp), strokeWidth = 2.dp)
                }
            }
        }
    }
}
