package io.legado.app.ui.recovery

import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Process
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.legado.app.R
import io.legado.app.help.storage.RestoreRecoveryGate
import io.legado.app.help.storage.RestoreRecoveryStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.min

/** Uses system resources and widgets so damaged app preferences cannot prevent recovery. */
class RestoreRecoveryActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var heading: TextView
    private lateinit var message: TextView
    private lateinit var retry: Button
    private lateinit var close: Button
    private lateinit var progress: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!RestoreRecoveryGate.isQuarantined) { finish(); return }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION")
        if (android.os.Build.VERSION.SDK_INT < 35) {
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT
        }
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        val root = FrameLayout(this).apply { setBackgroundColor(Color.rgb(250, 250, 250)) }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        val scroll = object : ScrollView(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val width = min(MeasureSpec.getSize(widthMeasureSpec), dp(720))
                super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), heightMeasureSpec)
            }
        }.apply { isFillViewport = true }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        heading = TextView(this).apply {
            id = R.id.restore_recovery_title
            textSize = 24f
            setTextColor(Color.BLACK)
            ViewCompat.setAccessibilityHeading(this, true)
        }
        message = TextView(this).apply {
            id = R.id.restore_recovery_message
            textSize = 18f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(20), 0, dp(20))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        progress = ProgressBar(this)
        retry = Button(this).apply {
            id = R.id.restore_recovery_retry
            setText(R.string.restore_recovery_retry)
            minHeight = dp(48)
            setOnClickListener { RestoreRecoveryGate.retry(applicationContext) }
        }
        close = Button(this).apply {
            id = R.id.restore_recovery_close
            setText(R.string.restore_recovery_close)
            minHeight = dp(48)
            setOnClickListener { closeApplication() }
        }
        content.addView(heading); content.addView(message)
        content.addView(progress, LinearLayout.LayoutParams(dp(48), dp(48)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        content.addView(retry, LinearLayout.LayoutParams(-1, -2))
        content.addView(close, LinearLayout.LayoutParams(-1, -2))
        scroll.addView(content, FrameLayout.LayoutParams(-1, -1))
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER_HORIZONTAL))
        setContentView(root)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { closeApplication() }
        })
        scope.launch { RestoreRecoveryGate.status.collect(::render) }
    }

    private fun render(state: RestoreRecoveryStatus) {
        val complete = state == RestoreRecoveryStatus.ReopenRequired
        val working = state == RestoreRecoveryStatus.Retrying
        heading.setText(if (complete) R.string.restore_recovery_complete_title else R.string.restore_recovery_title)
        message.setText(when {
            complete -> R.string.restore_recovery_complete_message
            working -> R.string.restore_recovery_working
            state == RestoreRecoveryStatus.RetryFailed -> R.string.restore_recovery_retry_failed
            else -> R.string.restore_recovery_message
        })
        retry.visibility = if (complete) View.GONE else View.VISIBLE
        retry.isEnabled = !working
        close.isEnabled = !working
        progress.visibility = if (working) View.VISIBLE else View.GONE
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun closeApplication() {
        if (RestoreRecoveryGate.status.value == RestoreRecoveryStatus.Retrying) return
        finishAffinity()
        Process.killProcess(Process.myPid())
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    companion object {
        fun intent(context: Context) = Intent(context, RestoreRecoveryActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
}
