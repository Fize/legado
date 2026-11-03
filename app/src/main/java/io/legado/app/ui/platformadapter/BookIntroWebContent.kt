package io.legado.app.ui.platformadapter

import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.BookSource
import io.legado.app.help.webView.PooledWebView
import io.legado.app.help.WebCacheManager
import io.legado.app.help.webView.WebJsExtensions
import io.legado.app.help.webView.WebViewPool

private class IntroWebResource {
    var pooled: PooledWebView? = null
    var documentScript: ScriptHandler? = null
}

/** Native document engine for the source's explicitly requested Web mode. */
@Composable
internal fun BookIntroWebContent(html: String, bookUrl: String, source: BookSource?, onLink: (String) -> Unit) {
    val resource = remember { IntroWebResource() }
    val owner = LocalLifecycleOwner.current
    val currentLink = rememberUpdatedState(onLink)
    DisposableEffect(owner, resource) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> resource.pooled?.realWebView?.onResume()
                Lifecycle.Event.ON_PAUSE -> resource.pooled?.realWebView?.onPause()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    AndroidView(modifier = Modifier.fillMaxWidth().wrapContentHeight().testTag("book_intro_web"), factory = { context ->
        val pooled = WebViewPool.acquire(context)
        resource.pooled = pooled
        pooled.realWebView.apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) onResume()
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val uri = request?.url ?: return true
                    if (uri.scheme in setOf("http", "https")) return false
                    currentLink.value(uri.toString())
                    return true
                }
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    if (resource.documentScript == null) view?.evaluateJavascript(WebJsExtensions.getInjectionString, null)
                }
                override fun onPageFinished(view: WebView?, url: String?) { view?.requestLayout() }
            }
            addJavascriptInterface(WebCacheManager, WebJsExtensions.nameCache)
            source?.let {
                addJavascriptInterface(it as BaseSource, WebJsExtensions.nameSource)
                addJavascriptInterface(WebJsExtensions(it, null, this), WebJsExtensions.nameJava)
            }
            val documentStart = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
            if (documentStart) resource.documentScript = WebViewCompat.addDocumentStartJavaScript(this, WebJsExtensions.getInjectionString, setOf("*"))
            val content = if (documentStart) html else htmlWithDocumentBridge(html, WebJsExtensions.getInjectionString)
            val base = bookUrl.takeIf { it.startsWith("http", true) }?.substringBefore(',')
            loadDataWithBaseURL(base, content, "text/html", "utf-8", base)
        }
    }, onRelease = {
        resource.documentScript?.remove()
        resource.documentScript = null
        resource.pooled?.let { pooled -> pooled.realWebView.onPause(); WebViewPool.release(pooled) }
        resource.pooled = null
    })
}
