package io.legado.app.ui.login

import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentContainerView
import io.legado.app.base.VMBaseComposeActivity
import io.legado.app.data.entities.BaseSource
import io.legado.app.utils.showDialogFragment

class SourceLoginActivity : VMBaseComposeActivity<SourceLoginViewModel>() {

    override val viewModel by viewModels<SourceLoginViewModel>()
    private val containerId = View.generateViewId()
    private var showWebViewFragment by mutableStateOf(false)

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        viewModel.initData(intent, success = { source ->
            initView(source)
        }, error = {
            finish()
        })
    }

    @Composable
    override fun Content() {
        if (showWebViewFragment) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    FragmentContainerView(ctx).apply {
                        id = containerId
                        if (supportFragmentManager.findFragmentByTag("webViewLogin") == null) {
                            supportFragmentManager.beginTransaction()
                                .replace(id, WebViewLoginFragment(), "webViewLogin")
                                .commitAllowingStateLoss()
                        }
                    }
                }
            )
        }
    }

    private fun initView(source: BaseSource) {
        if (!source.hasLoginForm()) {
            showWebViewFragment = true
        } else {
            showDialogFragment<SourceLoginDialog>()
        }
    }

}
