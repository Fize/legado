package io.legado.app.ui.login

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import io.legado.app.R
import io.legado.app.base.VMBaseComposeActivity
import io.legado.app.data.entities.BaseSource
import io.legado.app.help.config.AppConfig
import io.legado.app.help.http.CookieStore
import io.legado.app.help.webView.PooledWebView
import io.legado.app.help.webView.WebViewPool
import io.legado.app.help.webView.toWebViewRequestConfig
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.ui.compose.component.LegadoTopAppBar
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.longSnackbar
import io.legado.app.utils.openUrl
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.snackbar

class SourceLoginActivity : VMBaseComposeActivity<SourceLoginViewModel>() {

    override val viewModel by viewModels<SourceLoginViewModel>()
    private var showWebLogin by mutableStateOf(false)
    private var titleState by mutableStateOf("")
    private var progressState by mutableIntStateOf(100)
    private var pooledWebView: PooledWebView? = null
    private var currentWebView: WebView? = null
    private var checking = false

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        viewModel.initData(intent, success = { source -> initView(source) }, error = ::finish)
    }

    @Composable
    override fun Content() {
        if (showWebLogin) WebLoginContent()
    }

    @Composable
    private fun WebLoginContent() {
        Scaffold(
            topBar = {
                LegadoTopAppBar(
                    title = titleState,
                    onNavigationClick = ::finish,
                    actions = {
                        IconButton(onClick = {
                            if (!checking) {
                                checking = true
                                currentWebView?.snackbar(R.string.check_host_cookie)
                                viewModel.source?.let(::loadUrl)
                            }
                        }) {
                            Icon(Icons.Default.Check, stringResource(R.string.ok))
                        }
                    },
                )
            },
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .imePadding(),
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        FrameLayout(ctx).apply {
                            setBackgroundColor(backgroundColor)
                            attachWebView(this)
                        }
                    },
                    update =(::attachWebView),
                )
                if (progressState < 100) {
                    LinearProgressIndicator(
                        progress = { progressState / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp)
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

    private fun initView(source: BaseSource) {
        if (source.hasLoginForm()) {
            showDialogFragment<SourceLoginDialog>()
        } else {
            titleState = getString(R.string.login_source, source.getTag())
            initWebView(source)
            showWebLogin = true
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initWebView(source: BaseSource) {
        val webView = WebViewPool.acquire(this).let {
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
                if (checking) finish()
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
                    openUrl(url)
                }
                return true
            }

            @SuppressLint("WebViewClientOnReceivedSslError")
            override fun onReceivedSslError(
                view: WebView?, handler: SslErrorHandler?, error: SslError?,
            ) {
                handler?.proceed()
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progressState = newProgress
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

    override fun onDestroy() {
        pooledWebView?.let(WebViewPool::release)
        pooledWebView = null
        currentWebView = null
        super.onDestroy()
    }
}
