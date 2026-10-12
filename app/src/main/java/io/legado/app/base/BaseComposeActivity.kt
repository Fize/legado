package io.legado.app.base

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.activity.compose.setContent
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.Composable
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.Theme
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.lib.theme.ThemeStore
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.lib.theme.primaryColor
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.applyBackgroundTint
import io.legado.app.utils.disableAutoFill
import io.legado.app.utils.fullScreen
import io.legado.app.utils.hideSoftInput
import io.legado.app.utils.setLightStatusBar
import io.legado.app.utils.setNavigationBarColorAuto
import io.legado.app.utils.setStatusBarColorAuto
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.windowSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

abstract class BaseComposeActivity(
    val fullScreen: Boolean = true,
    private val theme: Theme = Theme.Auto,
    private val toolBarTheme: Theme = Theme.Auto,
    private val transparent: Boolean = false,
    private val imageBg: Boolean = true
) : AppCompatActivity() {

    val isInMultiWindow: Boolean
        @SuppressLint("ObsoleteSdkInt")
        get() {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                isInMultiWindowMode
            } else {
                false
            }
        }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppContextWrapper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        window.decorView.disableAutoFill()
        initTheme()
        super.onCreate(savedInstanceState)
        if (!shouldCreateContentView()) return
        setupSystemBar()
        setupPredictiveBack()
        setContent {
            LegadoTheme {
                Content()
            }
        }
        upBackgroundImage()
        observeLiveBus()
        onActivityCreated(savedInstanceState)
    }

    @Composable
    protected abstract fun Content()

    open fun onActivityCreated(savedInstanceState: Bundle?) {}

    protected open fun shouldCreateContentView(): Boolean = true

    protected open fun shouldShowWindowBackground(): Boolean = true

    private fun setupPredictiveBack() {
        if (!AppConfig.disablePredictiveBack
            || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
        ) {
            return
        }
        onBackInvokedDispatcher.registerOnBackInvokedCallback(
            OnBackInvokedDispatcher.PRIORITY_DEFAULT,
            OnBackInvokedCallback { onBackPressedDispatcher.onBackPressed() }
        )
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        setupSystemBar()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        setupSystemBar()
    }

    open fun initTheme() {
        when (theme) {
            Theme.Transparent -> setTheme(R.style.AppTheme_Transparent)
            Theme.Dark -> {
                setTheme(R.style.AppTheme_Dark)
                applyWindowBackground()
            }
            Theme.Light -> {
                setTheme(R.style.AppTheme_Light)
                applyWindowBackground()
            }
            else -> {
                if (ColorUtils.isColorLight(primaryColor)) {
                    setTheme(R.style.AppTheme_Light)
                } else {
                    setTheme(R.style.AppTheme_Dark)
                }
                applyWindowBackground()
            }
        }
    }

    private fun applyWindowBackground() {
        if (shouldShowWindowBackground()) {
            window.decorView.applyBackgroundTint(backgroundColor)
        } else {
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
    }

    open fun upBackgroundImage() {
        if (!imageBg) return
        val metrics = windowManager.windowSize
        lifecycleScope.launch(Dispatchers.IO) {
            val drawable = try {
                ThemeConfig.getBgImage(this@BaseComposeActivity, metrics)
            } catch (_: OutOfMemoryError) {
                toastOnUi("背景图片太大,内存溢出")
                null
            } catch (e: Exception) {
                AppLog.put("加载背景出错\n${e.localizedMessage}", e)
                null
            } ?: return@launch
            withContext(Dispatchers.Main) {
                if (!isFinishing && !isDestroyed) {
                    window.decorView.background = drawable
                }
            }
        }
    }

    open fun setupSystemBar() {
        if (fullScreen && !isInMultiWindow) {
            fullScreen()
        }
        val isTransparentStatusBar = AppConfig.isTransparentStatusBar
        val statusBarColor = ThemeStore.statusBarColor(this, isTransparentStatusBar)
        setStatusBarColorAuto(statusBarColor, isTransparentStatusBar, fullScreen)
        if (toolBarTheme == Theme.Dark) {
            setLightStatusBar(false)
        } else if (toolBarTheme == Theme.Light) {
            setLightStatusBar(true)
        }
        upNavigationBarColor()
    }

    open fun upNavigationBarColor() {
        if (AppConfig.immNavigationBar) {
            setNavigationBarColorAuto(ThemeStore.navigationBarColor(this))
        } else {
            val nbColor = ColorUtils.darkenColor(ThemeStore.navigationBarColor(this))
            setNavigationBarColorAuto(nbColor)
        }
    }

    open fun observeLiveBus() {}

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        return try {
            super.dispatchTouchEvent(ev)
        } catch (e: IllegalArgumentException) {
            e.printStackTrace()
            false
        }
    }

    override fun finish() {
        currentFocus?.hideSoftInput()
        super.finish()
    }
}

abstract class VMBaseComposeActivity<VM : androidx.lifecycle.ViewModel>(
    fullScreen: Boolean = true,
    theme: Theme = Theme.Auto,
    toolBarTheme: Theme = Theme.Auto,
    transparent: Boolean = false,
    imageBg: Boolean = true
) : BaseComposeActivity(fullScreen, theme, toolBarTheme, transparent, imageBg) {

    protected abstract val viewModel: VM

}

