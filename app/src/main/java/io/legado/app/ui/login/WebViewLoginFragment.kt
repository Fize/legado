package io.legado.app.ui.login

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
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
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.activityViewModels
import io.legado.app.R
import io.legado.app.base.BaseComposeFragment
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
import io.legado.app.utils.setOnApplyWindowInsetsListenerCompat
import io.legado.app.utils.snackbar
import splitties.views.bottomPadding

class WebViewLoginFragment : BaseComposeFragment() {

    private val viewModel by activityViewModels<SourceLoginViewModel>()
    private var pooledWebView: PooledWebView? = null
    private var currentWebView: WebView? = null
    private var titleState by mutableStateOf("")
    private var progressState by mutableIntStateOf(100)

    private var checking = false

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        view.setBackgroundColor(requireContext().backgroundColor)
        view.setOnApplyWindowInsetsListenerCompat { root, windowInsets ->
            val typeMask = WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            root.bottomPadding = windowInsets.getInsets(typeMask).bottom
            windowInsets
        }
        viewModel.source?.let {
            titleState = getString(R.string.login_source, it.getTag())
            initWebView(it)
        }
    }

    @Composable
    override fun Content() {
        Scaffold(
            topBar = {
                LegadoTopAppBar(
                    title = titleState,
                    onNavigationClick = { activity?.finish() },
                    actions = {
                        IconButton(
                            onClick = {
                                if (!checking) {
                                    checking = true
                                    view?.snackbar(R.string.check_host_cookie)
                                    viewModel.source?.let {
                                        loadUrl(it)
                                    }
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = stringResource(R.string.ok)
                            )
                        }
                    }
                )
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .imePadding()
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        FrameLayout(ctx).apply {
                            setBackgroundColor(requireContext().backgroundColor)
                            currentWebView?.let { webView ->
                                (webView.parent as? ViewGroup)?.removeView(webView)
                                addView(
                                    webView,
                                    FrameLayout.LayoutParams(
                                        FrameLayout.LayoutParams.MATCH_PARENT,
                                        FrameLayout.LayoutParams.MATCH_PARENT
                                    )
                                )
                            }
                        }
                    },
                    update = { container ->
                        currentWebView?.let { webView ->
                            if (webView.parent !== container) {
                                (webView.parent as? ViewGroup)?.removeView(webView)
                                container.removeAllViews()
                                container.addView(
                                    webView,
                                    FrameLayout.LayoutParams(
                                        FrameLayout.LayoutParams.MATCH_PARENT,
                                        FrameLayout.LayoutParams.MATCH_PARENT
                                    )
                                )
                            }
                        }
                    }
                )
                if (progressState < 100) {
                    LinearProgressIndicator(
                        progress = { progressState / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                            .align(Alignment.TopCenter)
                    )
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initWebView(source: BaseSource) {
        val webView = WebViewPool.acquire(requireContext()).let {
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
                val cookie = cookieManager.getCookie(url)
                CookieStore.setCookie(source.getKey(), cookie)
                super.onPageStarted(view, url, favicon)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                val cookie = cookieManager.getCookie(url)
                CookieStore.setCookie(source.getKey(), cookie)
                if (checking) {
                    activity?.finish()
                }
                super.onPageFinished(view, url)
            }

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                return shouldOverrideUrlLoading(request.url)
            }

            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION", "KotlinRedundantDiagnosticSuppress")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                return shouldOverrideUrlLoading(url.toUri())
            }

            private fun shouldOverrideUrlLoading(url: Uri): Boolean {
                when (url.scheme) {
                    "http", "https" -> {
                        return false
                    }

                    else -> {
                        view?.longSnackbar(R.string.jump_to_another_app, R.string.confirm) {
                            context?.openUrl(url)
                        }
                        return true
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
        webView.webChromeClient = object : WebChromeClient() {

            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
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
        super.onDestroy()
        pooledWebView?.let { WebViewPool.release(it) }
        pooledWebView = null
        currentWebView = null
    }

}
