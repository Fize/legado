package io.legado.app.ui.browser

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.addCallback
import androidx.activity.viewModels
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.size
import io.legado.app.R
import io.legado.app.base.VMBaseComposeActivity
import io.legado.app.constant.AppConst.imagePathKey
import io.legado.app.constant.AppLog
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.WebCacheManager
import io.legado.app.help.config.AppConfig
import io.legado.app.help.http.CookieManager as AppCookieManager
import io.legado.app.help.http.CookieStore
import io.legado.app.help.source.SourceVerificationHelp
import io.legado.app.help.webView.PooledWebView
import io.legado.app.help.webView.WebJsExtensions
import io.legado.app.help.webView.WebJsExtensions.Companion.basicJs
import io.legado.app.help.webView.WebJsExtensions.Companion.nameBasic
import io.legado.app.help.webView.WebJsExtensions.Companion.nameCache
import io.legado.app.help.webView.WebJsExtensions.Companion.nameJava
import io.legado.app.help.webView.WebViewPool
import io.legado.app.help.webView.WebViewPool.BLANK_HTML
import io.legado.app.help.webView.WebViewPool.DATA_HTML
import io.legado.app.help.webView.toWebViewRequestConfig
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.selector
import io.legado.app.model.Download
import io.legado.app.ui.AppActivity
import io.legado.app.ui.navigation.OnlineImportRoute
import io.legado.app.ui.compose.component.LegadoTopAppBar
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.utils.ACache
import io.legado.app.utils.keepScreenOn
import io.legado.app.utils.longSnackbar
import io.legado.app.utils.openUrl
import io.legado.app.utils.sendToClip
import io.legado.app.utils.setOnApplyWindowInsetsListenerCompat
import io.legado.app.utils.startActivity
import io.legado.app.utils.toggleSystemBar
import splitties.systemservices.powerManager
import splitties.views.bottomPadding
import java.lang.ref.WeakReference
import java.net.URLDecoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS

class WebViewActivity : VMBaseComposeActivity<WebViewModel>() {
    companion object {
        // 是否输出日志
        var sessionShowWebLog = false
    }

    private lateinit var pooledWebView: PooledWebView
    private lateinit var currentWebView: WebView

    override val guardsRestoreRecovery = true

    override val viewModel by viewModels<WebViewModel>()
    private var customWebViewCallback: WebChromeClient.CustomViewCallback? = null
    private var customWebViewContainer: FrameLayout? = null
    private var customViewState by mutableStateOf<View?>(null)
    private var webPic: String? = null
    private var isCloudflareChallenge = false
    private var isFullScreen by mutableStateOf(false)
    private var isfullscreen by mutableStateOf(false)
    private var wasScreenOff = false
    private var needClearHistory = true
    private var titleState by mutableStateOf("")
    private var subtitleState by mutableStateOf<String?>(null)
    private var progressState by mutableIntStateOf(100)
    private var showWebLogState by mutableStateOf(sessionShowWebLog)
    private var hasSourceOriginState by mutableStateOf(false)
    private var webViewUnavailable by mutableStateOf(false)

    private val saveImage = registerForActivityResult(HandleFileContract()) {
        if (redirectIfRestoreRecoveryRequired()) return@registerForActivityResult
        it.uri?.let { uri ->
            ACache.get().put(imagePathKey, uri.toString())
            viewModel.saveImage(webPic, uri.toString())
        }
    }

    private fun refresh() {
        currentWebView.reload()
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        if (!SourceVerificationHelp.attachVerificationUi(
                intent.getStringExtra("verificationResultKey"),
                ::finishVerificationUi,
            )
        ) {
            finish()
            return
        }
        titleState = intent.getStringExtra("title") ?: getString(R.string.loading)
        subtitleState = intent.getStringExtra("sourceName")
        if (WebView.getCurrentWebViewPackage() == null) {
            webViewUnavailable = true
            return
        }
        try {
            pooledWebView = WebViewPool.acquire(this)
        } catch (error: RuntimeException) {
            if (WebView.getCurrentWebViewPackage() != null) throw error
            webViewUnavailable = true
            return
        }
        currentWebView = pooledWebView.realWebView
        (currentWebView.parent as? ViewGroup)?.removeView(currentWebView)
        currentWebView.post {
            currentWebView.clearHistory()
        }
        viewModel.initData(intent) {
            if (isRestoreRecoveryBlocked || isDestroyed) return@initData
            hasSourceOriginState = viewModel.sourceOrigin.isNotEmpty()
            val url = viewModel.baseUrl
            val headerMap = viewModel.headerMap
            val requestConfig = headerMap.toWebViewRequestConfig(AppConfig.userAgent)
            initWebView(url, requestConfig.userAgent)
            val html = viewModel.html
            if (html.isNullOrEmpty()) {
                currentWebView.loadUrl(url, requestConfig.additionalHeaders)
            } else {
                if (viewModel.localHtml) {
                    viewModel.source?.let {
                        val webJsExtensions = WebJsExtensions(it, this, currentWebView)
                        currentWebView.addJavascriptInterface(webJsExtensions, nameJava)
                    }
                    currentWebView.addJavascriptInterface(WebCacheManager, nameCache)
                }
                currentWebView.loadDataWithBaseURL(url, html, "text/html", "utf-8", url)
            }
        }
        currentWebView.clearHistory()
        onBackPressedDispatcher.addCallback(this) {
            if ((customWebViewContainer?.size ?: 0) > 0 || customViewState != null) { //网页全屏
                customWebViewCallback?.onCustomViewHidden()
                return@addCallback
            }
            if (isFullScreen) { //按钮全屏
                toggleFullScreen()
                return@addCallback
            }
            if (currentWebView.canGoBack()) {
                val list = currentWebView.copyBackForwardList()
                val size = list.size
                if (size == 1) {
                    finish()
                    return@addCallback
                }
                val currentIndex = list.currentIndex
                val currentItem = list.currentItem
                val currentUrl = currentItem?.originalUrl ?: BLANK_HTML
                val currentTitle = currentItem?.title
                var steps = 1
                for (i in currentIndex - 1 downTo 0) {
                    val item = list.getItemAtIndex(i)
                    val itemUrl = item.originalUrl
                    if (itemUrl == BLANK_HTML) {
                        finish()
                        return@addCallback
                    }
                    if (itemUrl != currentUrl || currentTitle != item.title) {
                        break
                    }
                    if (currentUrl == DATA_HTML) {
                        break
                    }
                    steps++
                }
                if (steps == size) {
                    finish()
                    return@addCallback
                }
                currentWebView.goBackOrForward(-steps)
                return@addCallback
            }
            finish()
        }
    }

    @Composable
    override fun Content() {
        if (webViewUnavailable) {
            Scaffold(topBar = {
                LegadoTopAppBar(title = titleState, subtitle = subtitleState, onNavigationClick = { finish() })
            }) { innerPadding ->
                Text(
                    text = stringResource(R.string.web_view_unavailable),
                    modifier = Modifier.padding(innerPadding).verticalScroll(rememberScrollState()).padding(24.dp),
                )
            }
            return
        }
        var showMenu by remember { mutableStateOf(false) }

        Scaffold(
            topBar = {
                if (!isFullScreen && !isfullscreen) {
                    LegadoTopAppBar(
                        title = titleState,
                        subtitle = subtitleState,
                        onNavigationClick = { finish() },
                        actions = {
                            IconButton(onClick = { refresh() }) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = stringResource(R.string.refresh)
                                )
                            }
                            IconButton(
                                onClick = {
                                    if (viewModel.sourceVerificationEnable) {
                                        viewModel.saveVerificationResult(currentWebView) {
                                            finish()
                                        }
                                    } else {
                                        finish()
                                    }
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = stringResource(R.string.ok)
                                )
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
                                        text = { Text(stringResource(R.string.open_in_browser)) },
                                        onClick = {
                                            showMenu = false
                                            openUrl(viewModel.baseUrl)
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.copy_url)) },
                                        onClick = {
                                            showMenu = false
                                            sendToClip(viewModel.baseUrl)
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.full_screen)) },
                                        onClick = {
                                            showMenu = false
                                            toggleFullScreen()
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.show_web_log)) },
                                        trailingIcon = {
                                            Checkbox(
                                                checked = showWebLogState,
                                                onCheckedChange = null
                                            )
                                        },
                                        onClick = {
                                            sessionShowWebLog = !sessionShowWebLog
                                            showWebLogState = sessionShowWebLog
                                            showMenu = false
                                        }
                                    )
                                    if (hasSourceOriginState) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.disable_source)) },
                                            onClick = {
                                                showMenu = false
                                                viewModel.disableSource {
                                                    finish()
                                                }
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    text = stringResource(R.string.delete_source),
                                                    color = MaterialTheme.colorScheme.error
                                                )
                                            },
                                            onClick = {
                                                showMenu = false
                                                alert(R.string.draw) {
                                                    setMessage(getString(R.string.sure_del) + "\n" + viewModel.sourceName)
                                                    noButton()
                                                    yesButton {
                                                        viewModel.deleteSource {
                                                            finish()
                                                        }
                                                    }
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    )
                }
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .imePadding()
            ) {
                if (::currentWebView.isInitialized) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            FrameLayout(ctx).apply {
                                setOnApplyWindowInsetsListenerCompat { view, windowInsets ->
                                    val typeMask = WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
                                    view.bottomPadding = windowInsets.getInsets(typeMask).bottom
                                    windowInsets
                                }
                                (currentWebView.parent as? ViewGroup)?.removeView(currentWebView)
                                addView(
                                    currentWebView,
                                    FrameLayout.LayoutParams(
                                        FrameLayout.LayoutParams.MATCH_PARENT,
                                        FrameLayout.LayoutParams.MATCH_PARENT
                                    )
                                )
                            }
                        },
                        update = { container ->
                            container.visibility = if (isfullscreen) View.INVISIBLE else View.VISIBLE
                        }
                    )
                }

                if (progressState < 100 && !isfullscreen) {
                    LinearProgressIndicator(
                        progress = { progressState / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                            .align(Alignment.TopCenter)
                    )
                }

                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        FrameLayout(ctx).also {
                            customWebViewContainer = it
                        }
                    },
                    update = { container ->
                        val customView = customViewState
                        if (customView != null) {
                            if (customView.parent !== container) {
                                (customView.parent as? ViewGroup)?.removeView(customView)
                                container.removeAllViews()
                                container.addView(customView)
                            }
                            container.visibility = View.VISIBLE
                        } else {
                            container.removeAllViews()
                            container.visibility = View.GONE
                        }
                    }
                )
            }
        }
    }

    //实现starBrowser调起页面全屏
    private fun toggleFullScreen() {
        isFullScreen = !isFullScreen
        toggleSystemBar(!isFullScreen)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initWebView(url: String, userAgent: String) {
        currentWebView.webChromeClient = CustomWebChromeClient()
        // 添加 JavaScript 接口
        currentWebView.addJavascriptInterface(JSInterface(this), nameBasic)
        currentWebView.webViewClient = CustomWebViewClient()
        currentWebView.settings.apply {
            useWideViewPort = true
            loadWithOverviewMode = true
            userAgentString = userAgent
        }
        AppCookieManager.applyToWebView(url)
        currentWebView.setOnLongClickListener {
            val hitTestResult = currentWebView.hitTestResult
            if (hitTestResult.type == WebView.HitTestResult.IMAGE_TYPE ||
                hitTestResult.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
                hitTestResult.extra?.let { webPic ->
                    selector(
                        arrayListOf(
                            SelectItem(getString(R.string.action_save), "save"),
                            SelectItem(getString(R.string.select_folder), "selectFolder")
                        )
                    ) { _, charSequence, _ ->
                        when (charSequence.value) {
                            "save" -> saveImage(webPic)
                            "selectFolder" -> selectSaveFolder()
                        }
                    }
                    return@setOnLongClickListener true
                }
            }
            return@setOnLongClickListener false
        }
        currentWebView.setDownloadListener { url, _, contentDisposition, _, _ ->
            var fileName = URLUtil.guessFileName(url, contentDisposition, null)
            fileName = URLDecoder.decode(fileName, "UTF-8")
            currentWebView.longSnackbar(fileName, getString(R.string.action_download)) {
                Download.start(this, url, fileName)
            }
        }
    }

    private fun saveImage(webPic: String) {
        this.webPic = webPic
        val path = ACache.get().getAsString(imagePathKey)
        if (path.isNullOrEmpty()) {
            selectSaveFolder()
        } else {
            viewModel.saveImage(webPic, path)
        }
    }

    private fun selectSaveFolder() {
        val default = arrayListOf<SelectItem<Int>>()
        val path = ACache.get().getAsString(imagePathKey)
        if (!path.isNullOrEmpty()) {
            default.add(SelectItem(path, -1))
        }
        saveImage.launch {
            otherActions = default
        }
    }

    override fun finish() {
        if (isRestoreRecoveryBlocked) return super.finish()
        SourceVerificationHelp.checkResult(intent.getStringExtra("verificationResultKey"))
        super.finish()
    }

    private fun finishVerificationUi() {
        val finished = CountDownLatch(1)
        runOnUiThread {
            try {
                finish()
            } finally {
                finished.countDown()
            }
        }
        finished.await(5, SECONDS)
    }

    private fun close() {
        if (redirectIfRestoreRecoveryRequired()) return
        if (!isCloudflareChallenge) {
            if (viewModel.sourceVerificationEnable) {
                viewModel.saveVerificationResult(currentWebView) {
                    finish()
                }
            } else {
                finish()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        if (!wasActivityContentCreated) return
        if (powerManager.isInteractive) {
            wasScreenOff = false
            if (::currentWebView.isInitialized) {
                currentWebView.onPause()
            }
        } else {
            wasScreenOff = true
        }
    }

    override fun onResume() {
        super.onResume()
        if (redirectIfRestoreRecoveryRequired()) return
        if (!wasScreenOff && ::currentWebView.isInitialized) {
            currentWebView.onResume()
        }
    }

    override fun onDestroy() {
        if (::pooledWebView.isInitialized) {
            WebViewPool.release(pooledWebView)
        }
        super.onDestroy()
    }

    @Suppress("unused")
    private class JSInterface(activity: WebViewActivity) {
        private val activityRef: WeakReference<WebViewActivity> = WeakReference(activity)
        @JavascriptInterface
        fun lockOrientation(orientation: String) {
            val ctx = activityRef.get()
            if (ctx != null && ctx.isfullscreen && !ctx.isFinishing && !ctx.isDestroyed) {
                ctx.runOnUiThread {
                    ctx.requestedOrientation = when (orientation) {
                        "portrait", "portrait-primary" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                        "portrait-secondary" -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
                        "landscape" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE //横屏且受重力控制正反
                        "landscape-primary" -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE //正向横屏
                        "landscape-secondary" -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE //反向横屏
                        "any", "unspecified" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR
                        else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    }
                }
            }
        }

        @JavascriptInterface
        fun onCloseRequested() {
            val ctx = activityRef.get()
            if (ctx != null && !ctx.isFinishing && !ctx.isDestroyed) {
                ctx.runOnUiThread {
                    ctx.close()
                }
            }
        }
    }

    inner class CustomWebChromeClient : WebChromeClient() {
        override fun getDefaultVideoPoster(): Bitmap {
            return super.getDefaultVideoPoster() ?: createBitmap(100, 100)
        }

        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            super.onProgressChanged(view, newProgress)
            progressState = newProgress
        }

        override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
            isfullscreen = true
            customViewState = view
            customWebViewCallback = callback
            keepScreenOn(true)
            toggleSystemBar(false)
        }

        override fun onHideCustomView() {
            isfullscreen = false
            customViewState = null
            customWebViewContainer?.removeAllViews()
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            keepScreenOn(false)
            toggleSystemBar(true)
        }

        /* 覆盖window.close() */
        override fun onCloseWindow(window: WebView?) {
            close()
        }

        /* 监听网页日志 */
        override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
            viewModel.source?.let { source ->
                if (sessionShowWebLog) {
                    val messageLevel = consoleMessage.messageLevel().name
                    val message = consoleMessage.message()
                    AppLog.put(
                        "${source.getTag()}${messageLevel}: $message",
                        NoStackTraceException("\n${message}\n- Line ${consoleMessage.lineNumber()} of ${consoleMessage.sourceId()}")
                    )
                    return true
                }
            }
            return false
        }
    }

    inner class CustomWebViewClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(
            view: WebView?,
            request: WebResourceRequest?
        ): Boolean {
            request?.let {
                return shouldOverrideUrlLoading(it.url)
            }
            return true
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION", "KotlinRedundantDiagnosticSuppress")
        override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
            url?.let {
                return shouldOverrideUrlLoading(it.toUri())
            }
            return true
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            if (isRestoreRecoveryBlocked) { view?.stopLoading(); return }
            if (needClearHistory) {
                needClearHistory = false
                currentWebView.clearHistory() //清除历史
            }
            super.onPageStarted(view, url, favicon)
            currentWebView.evaluateJavascript(basicJs, null)
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            if (isRestoreRecoveryBlocked) { view?.stopLoading(); return }
            super.onPageFinished(view, url)
            val cookieManager = CookieManager.getInstance()
            url?.let {
                CookieStore.setCookie(it, cookieManager.getCookie(it))
            }
            view?.title?.let { title ->
                if (title != url && title != view.url && title.isNotBlank()) {
                    titleState = title
                } else {
                    titleState = intent.getStringExtra("title") ?: ""
                }
                view.evaluateJavascript("!!window._cf_chl_opt") {
                    if (isRestoreRecoveryBlocked) return@evaluateJavascript
                    if (it == "true") {
                        isCloudflareChallenge = true
                    } else if (isCloudflareChallenge && viewModel.sourceVerificationEnable) {
                        viewModel.saveVerificationResult(currentWebView) {
                            finish()
                        }
                    }
                }
            }
        }

        private fun shouldOverrideUrlLoading(url: Uri): Boolean {
            if (isRestoreRecoveryBlocked) return true
            return when (url.scheme) {
                "http", "https" -> false
                "legado", "yuedu" -> {
                    startActivity(AppActivity.startIntent(this@WebViewActivity, OnlineImportRoute(url.toString())))
                    true
                }

                else -> {
                    currentWebView.longSnackbar(R.string.jump_to_another_app, R.string.confirm) {
                        openUrl(url)
                    }
                    true
                }
            }
        }

        @SuppressLint("WebViewClientOnReceivedSslError")
        override fun onReceivedSslError(
            view: WebView?,
            handler: SslErrorHandler?,
            error: SslError?
        ) {
            handler?.proceed()
        }
    }
}
