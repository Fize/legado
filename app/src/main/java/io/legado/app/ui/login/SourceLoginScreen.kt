package io.legado.app.ui.login

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.core.net.toUri
import androidx.lifecycle.viewmodel.compose.viewModel
import io.legado.app.R
import io.legado.app.data.entities.BaseSource
import io.legado.app.help.config.AppConfig
import io.legado.app.help.http.CookieStore
import io.legado.app.help.webView.PooledWebView
import io.legado.app.help.webView.WebViewPool
import io.legado.app.help.webView.toWebViewRequestConfig
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.ui.compose.component.LegadoTopAppBar
import io.legado.app.ui.navigation.SourceLoginRoute
import io.legado.app.ui.navigation.SourceLoginTarget
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.longSnackbar
import io.legado.app.utils.openUrl
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.snackbar

@Composable
fun SourceLoginScreen(
    route: SourceLoginRoute,
    onBack: () -> Unit,
) {
    val activity = checkNotNull(LocalActivity.current as? AppCompatActivity)
    // The form dialog uses activityViewModels; both hosts must initialize and read
    // the same source instead of creating a separate navigation-entry model.
    val viewModel: SourceLoginViewModel = viewModel(viewModelStoreOwner = activity)
    val state = remember(activity, viewModel) {
        SourceLoginScreenState(activity, viewModel, onBack)
    }
    LaunchedEffect(route) { state.initialize(route) }
    DisposableEffect(state) { onDispose(state::release) }
    state.Content()
}

private class SourceLoginScreenState(
    private val activity: AppCompatActivity,
    private val viewModel: SourceLoginViewModel,
    private val onBack: () -> Unit,
) {
    private var showWebLogin by mutableStateOf(false)
    private var webViewUnavailable by mutableStateOf(false)
    private var title by mutableStateOf("")
    private var progress by mutableIntStateOf(100)
    private var pooledWebView: PooledWebView? = null
    private var currentWebView: WebView? = null
    private var checking = false
    private var initializedRoute: SourceLoginRoute? = null

    fun initialize(route: SourceLoginRoute) {
        if (initializedRoute == route) return
        initializedRoute = route
        if (viewModel.readyRoute == route && viewModel.source != null) {
            showLogin(requireNotNull(viewModel.source))
            return
        }
        viewModel.readyRoute = null
        viewModel.loginEditorValues.clear()
        viewModel.loginV2Snapshot = null
        viewModel.initData(
            route.toLegacyIntent(),
            success = { source ->
                viewModel.readyRoute = route
                showLogin(source)
            },
            error = onBack,
        )
    }

    @Composable
    fun Content() {
        if (!showWebLogin) return
        Scaffold(
            topBar = {
                LegadoTopAppBar(
                    title = title,
                    onNavigationClick = onBack,
                    actions = {
                        if (!webViewUnavailable) {
                            IconButton(
                                onClick = {
                                    if (!checking) {
                                        checking = true
                                        currentWebView?.snackbar(R.string.check_host_cookie)
                                        viewModel.source?.let(::loadUrl)
                                    }
                                },
                            ) {
                                Icon(Icons.Default.Check, stringResource(R.string.ok))
                            }
                        }
                    },
                )
            },
        ) { innerPadding ->
            Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding).imePadding(),
            ) {
                if (webViewUnavailable) {
                    Text(
                        text = stringResource(R.string.web_login_unavailable),
                        modifier = Modifier.verticalScroll(rememberScrollState()).padding(24.dp),
                    )
                } else AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        FrameLayout(context).apply {
                            setBackgroundColor(activity.backgroundColor)
                            attachWebView(this)
                        }
                    },
                    update = ::attachWebView,
                )
                if (progress < 100) {
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier.fillMaxWidth().height(2.dp)
                            .align(Alignment.TopCenter),
                    )
                }
            }
        }
    }

    private fun attachWebView(container: FrameLayout) {
        currentWebView?.let { webView ->
            if (webView.parent !== container) {
                (webView.parent as? ViewGroup)?.removeView(webView)
                container.removeAllViews()
                container.addView(
                    webView,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    ),
                )
            }
        }
    }

    private fun showLogin(source: BaseSource) {
        if (source.hasLoginForm()) {
            val existing = activity.supportFragmentManager.findFragmentByTag(SourceLoginDialog::class.simpleName)
                as? SourceLoginDialog
            if (existing != null) existing.onSourceAvailable()
            else activity.showDialogFragment<SourceLoginDialog>()
        } else {
            title = activity.getString(R.string.login_source, source.getTag())
            webViewUnavailable = WebView.getCurrentWebViewPackage() == null
            if (!webViewUnavailable) {
                try {
                    initWebView(source)
                } catch (error: RuntimeException) {
                    if (WebView.getCurrentWebViewPackage() != null) throw error
                    release()
                    webViewUnavailable = true
                }
            }
            showWebLogin = true
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initWebView(source: BaseSource) {
        val webView = WebViewPool.acquire(activity).let {
            pooledWebView = it
            it.realWebView
        }
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.onResume()
        currentWebView = webView
        val requestConfig = viewModel.headerMap.toWebViewRequestConfig(AppConfig.userAgent)
        webView.settings.apply {
            useWideViewPort = true
            loadWithOverviewMode = true
            userAgentString = requestConfig.userAgent
        }
        val cookieManager = CookieManager.getInstance()
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                CookieStore.setCookie(source.getKey(), cookieManager.getCookie(url))
                super.onPageStarted(view, url, favicon)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                CookieStore.setCookie(source.getKey(), cookieManager.getCookie(url))
                if (checking) onBack()
                super.onPageFinished(view, url)
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) =
                shouldOpenExternally(request.url)

            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView, url: String) =
                shouldOpenExternally(url.toUri())

            private fun shouldOpenExternally(url: Uri): Boolean {
                if (url.scheme == "http" || url.scheme == "https") return false
                currentWebView?.longSnackbar(R.string.jump_to_another_app, R.string.confirm) {
                    activity.openUrl(url)
                }
                return true
            }

            @SuppressLint("WebViewClientOnReceivedSslError")
            override fun onReceivedSslError(
                view: WebView?,
                handler: SslErrorHandler?,
                error: SslError?,
            ) {
                handler?.proceed()
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progress = newProgress
            }
        }
        loadUrl(source)
    }

    private fun loadUrl(source: BaseSource) {
        val loginUrl = source.loginUrl ?: return
        val absoluteUrl = NetworkUtils.getAbsoluteURL(source.getKey(), loginUrl)
        val requestConfig = viewModel.headerMap.toWebViewRequestConfig(AppConfig.userAgent)
        currentWebView?.apply {
            settings.userAgentString = requestConfig.userAgent
            loadUrl(absoluteUrl, requestConfig.additionalHeaders)
        }
    }

    fun release() {
        pooledWebView?.let(WebViewPool::release)
        pooledWebView = null
        currentWebView = null
    }
}

private fun SourceLoginRoute.toLegacyIntent(): Intent = Intent().apply {
    when (target) {
        SourceLoginTarget.CurrentReading -> putExtra("bookType", bookType)
        SourceLoginTarget.BookSource -> {
            putExtra("type", "bookSource")
            putExtra("key", key)
        }
        SourceLoginTarget.HttpTts -> {
            putExtra("type", "httpTts")
            putExtra("key", key)
        }
    }
    putExtra("bookUrl", bookUrl)
}
