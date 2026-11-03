package io.legado.app.ui.book.read.config

import android.app.Activity.RESULT_OK
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.viewModels
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.data.entities.HttpTTS
import io.legado.app.help.http.WebCookieUnavailableException
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.primaryColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.ui.about.openAppLogs
import io.legado.app.ui.code.CodeEditActivity
import io.legado.app.ui.AppActivity
import io.legado.app.ui.navigation.SourceLoginRoute
import io.legado.app.ui.navigation.SourceLoginTarget
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.utils.GSON
import io.legado.app.utils.sendToClip
import io.legado.app.utils.setLayout
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi

class HttpTtsEditDialog() : BaseDialogFragment(adaptationSoftKeyboard = true) {

    constructor(id: Long) : this() {
        arguments = Bundle().apply {
            putLong("id", id)
        }
    }

    private val viewModel by viewModels<HttpTtsEditViewModel>()

    private var nameState by mutableStateOf("")
    private var urlState by mutableStateOf("")
    private var contentTypeState by mutableStateOf("")
    private var pauseDurationState by mutableStateOf("0")
    private var concurrentRateState by mutableStateOf("")
    private var loginUrlState by mutableStateOf("")
    private var loginUiState by mutableStateOf("")
    private var loginCheckJsState by mutableStateOf("")
    private var headersState by mutableStateOf("")
    private var jsLibState by mutableStateOf("")
    private var enabledCookieJarState by mutableStateOf(false)

    private var focusedFieldKey: String? = null
    private var focusedFieldTitle: String? = null

    override fun onStart() {
        super.onStart()
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
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
                    HttpTtsEditScreen()
                }
            }
        }
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        viewModel.initData(arguments) {
            initView(httpTTS = it)
        }
    }

    fun initView(httpTTS: HttpTTS) {
        nameState = httpTTS.name
        urlState = httpTTS.url
        contentTypeState = httpTTS.contentType.orEmpty()
        pauseDurationState = httpTTS.pauseDuration.toString()
        concurrentRateState = httpTTS.concurrentRate.orEmpty()
        loginUrlState = httpTTS.loginUrl.orEmpty()
        loginUiState = httpTTS.loginUi.orEmpty()
        loginCheckJsState = httpTTS.loginCheckJs.orEmpty()
        headersState = httpTTS.header.orEmpty()
        jsLibState = httpTTS.jsLib.orEmpty()
        enabledCookieJarState = httpTTS.enabledCookieJar == true
    }

    private val textEditLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val key = focusedFieldKey
            if (key == null) {
                toastOnUi(R.string.focus_lost_on_textbox)
                return@registerForActivityResult
            }
            result.data?.getStringExtra("text")?.let { text ->
                updateFieldValue(key, text)
            }
        }
    }

    private fun getFieldValue(key: String): String = when (key) {
        "name" -> nameState
        "pauseDuration" -> pauseDurationState
        "url" -> urlState
        "contentType" -> contentTypeState
        "concurrentRate" -> concurrentRateState
        "loginUrl" -> loginUrlState
        "loginUi" -> loginUiState
        "loginCheckJs" -> loginCheckJsState
        "header" -> headersState
        "jsLib" -> jsLibState
        else -> ""
    }

    private fun updateFieldValue(key: String, value: String) {
        when (key) {
            "name" -> nameState = value
            "pauseDuration" -> pauseDurationState = value
            "url" -> urlState = value
            "contentType" -> contentTypeState = value
            "concurrentRate" -> concurrentRateState = value
            "loginUrl" -> loginUrlState = value
            "loginUi" -> loginUiState = value
            "loginCheckJs" -> loginCheckJsState = value
            "header" -> headersState = value
            "jsLib" -> jsLibState = value
        }
    }

    private fun onFullEditClicked() {
        val key = focusedFieldKey
        if (key != null) {
            val currentText = getFieldValue(key)
            val intent = Intent(requireActivity(), CodeEditActivity::class.java).apply {
                putExtra("text", currentText)
                putExtra("title", focusedFieldTitle)
                putExtra("cursorPosition", currentText.length)
            }
            textEditLauncher.launch(intent)
        } else {
            toastOnUi(R.string.please_focus_cursor_on_textbox)
        }
    }

    private fun showLoginHeader() = alert {
        setTitle(R.string.login_header)
        dataFromView().getLoginHeader()?.takeIf { it.isNotBlank() }?.let { loginHeader ->
            setMessage(loginHeader)
        } ?: setMessage(R.string.empty)
    }

    private fun clearLoginHeader() {
        try {
            dataFromView().removeLoginHeader()
        } catch (error: WebCookieUnavailableException) {
            toastOnUi(R.string.web_cookie_unavailable)
        }
    }

    @Composable
    private fun HttpTtsEditScreen() {
        val context = requireContext()
        var showMenu by remember { mutableStateOf(false) }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 4.dp
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
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
                        IconButton(onClick = { onFullEditClicked() }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_code),
                                contentDescription = stringResource(R.string.edit_content),
                                tint = Color(context.primaryTextColor)
                            )
                        }
                        IconButton(
                            onClick = {
                                viewModel.save(dataFromView()) {
                                    dismissAllowingStateLoss()
                                    toastOnUi("保存成功")
                                }
                            }
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_save),
                                contentDescription = stringResource(R.string.action_save),
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
                                    text = { Text(stringResource(R.string.login)) },
                                    onClick = {
                                        showMenu = false
                                        dataFromView().let { httpTts ->
                                            if (httpTts.loginUrl.isNullOrBlank()) {
                                                toastOnUi("登录url不能为空")
                                            } else {
                                                viewModel.save(httpTts) {
                                                    startActivity(
                                                        AppActivity.startIntent(
                                                            requireContext(),
                                                            SourceLoginRoute(
                                                                SourceLoginTarget.HttpTts,
                                                                key = httpTts.id.toString(),
                                                            ),
                                                        ),
                                                    )
                                                }
                                            }
                                        }
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.show_login_header)) },
                                    onClick = {
                                        showMenu = false
                                        showLoginHeader()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.del_login_header)) },
                                    onClick = {
                                        showMenu = false
                                        clearLoginHeader()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.copy_source)) },
                                    onClick = {
                                        showMenu = false
                                        dataFromView().let {
                                            context.sendToClip(GSON.toJson(it))
                                        }
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.paste_source)) },
                                    onClick = {
                                        showMenu = false
                                        viewModel.importFromClip {
                                            initView(it)
                                        }
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.log)) },
                                    onClick = {
                                        showMenu = false
                                        requireContext().openAppLogs()
                                    }
                                )
                            }
                        }
                    }

                    // Form fields
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(12.dp)
                    ) {
                        EditField(
                            key = "name",
                            label = stringResource(R.string.name),
                            value = nameState,
                            onValueChange = { nameState = it }
                        )
                        EditField(
                            key = "pauseDuration",
                            label = stringResource(R.string.http_tts_pause_duration),
                            value = pauseDurationState,
                            onValueChange = { pauseDurationState = it },
                            keyboardType = KeyboardType.Number
                        )
                        EditField(
                            key = "url",
                            label = "url",
                            value = urlState,
                            onValueChange = { urlState = it }
                        )
                        EditField(
                            key = "contentType",
                            label = "Content-Type",
                            value = contentTypeState,
                            onValueChange = { contentTypeState = it }
                        )
                        EditField(
                            key = "concurrentRate",
                            label = stringResource(R.string.concurrent_rate),
                            value = concurrentRateState,
                            onValueChange = { concurrentRateState = it }
                        )
                        EditField(
                            key = "loginUrl",
                            label = stringResource(R.string.login_url),
                            value = loginUrlState,
                            onValueChange = { loginUrlState = it }
                        )
                        EditField(
                            key = "loginUi",
                            label = stringResource(R.string.login_ui),
                            value = loginUiState,
                            onValueChange = { loginUiState = it }
                        )
                        EditField(
                            key = "loginCheckJs",
                            label = stringResource(R.string.login_check_js),
                            value = loginCheckJsState,
                            onValueChange = { loginCheckJsState = it }
                        )
                        EditField(
                            key = "header",
                            label = stringResource(R.string.source_http_header),
                            value = headersState,
                            onValueChange = { headersState = it }
                        )
                        EditField(
                            key = "jsLib",
                            label = "jsLib",
                            value = jsLibState,
                            onValueChange = { jsLibState = it }
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .clickable { enabledCookieJarState = !enabledCookieJarState },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = enabledCookieJarState,
                                onCheckedChange = { enabledCookieJarState = it }
                            )
                            Text(
                                text = stringResource(R.string.auto_save_cookie),
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun EditField(
        key: String,
        label: String,
        value: String,
        onValueChange: (String) -> Unit,
        keyboardType: KeyboardType = KeyboardType.Text
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .onFocusChanged { focusState ->
                    if (focusState.isFocused) {
                        focusedFieldKey = key
                        focusedFieldTitle = label
                    }
                }
        )
    }

    private fun dataFromView(): HttpTTS {
        return HttpTTS(
            id = viewModel.id ?: System.currentTimeMillis(),
            name = nameState,
            url = urlState,
            contentType = contentTypeState,
            pauseDuration = pauseDurationState
                .toIntOrNull()
                ?.coerceIn(0, 10_000)
                ?: 0,
            concurrentRate = concurrentRateState,
            loginUrl = loginUrlState,
            loginUi = loginUiState,
            loginCheckJs = loginCheckJsState,
            header = headersState,
            jsLib = jsLibState,
            enabledCookieJar = enabledCookieJarState
        )
    }

    private fun isSame(): Boolean {
        val httpTTS = viewModel.httpTTS ?: return nameState.isEmpty()
        return dataFromView().equal(httpTTS)
    }

    override fun dismiss() {
        if (!isSame()) {
            alert(R.string.exit) {
                setMessage(R.string.exit_no_save)
                positiveButton(R.string.yes)
                negativeButton(R.string.no) {
                    super.dismiss()
                }
            }
        } else {
            super.dismiss()
        }
    }
}
