package io.legado.app.ui.widget.recycler

import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.annotation.ColorRes
import io.legado.app.R
import io.legado.app.lib.dialogs.alert
import io.legado.app.ui.widget.anima.RotateLoading
import io.legado.app.utils.dpToPx
import io.legado.app.utils.getCompatColor
import io.legado.app.utils.invisible
import io.legado.app.utils.visible

@Suppress("unused")
class LoadMoreView(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {
    private val rotateLoading = RotateLoading(context).apply {
        visibility = View.INVISIBLE
    }
    internal val tvText = TextView(context).apply {
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setSingleLine(true)
        val pad = 10.dpToPx()
        setPadding(pad, pad, pad, pad)
        visibility = View.INVISIBLE
    }

    private var errorMsg = ""

    private var onClickListener: OnClickListener? = null

    var isLoading = false
        private set

    var hasMore = true
        private set

    init {
        val loadingSize = 36.dpToPx()
        val loadingMargin = 6.dpToPx()
        addView(
            rotateLoading,
            LayoutParams(loadingSize, loadingSize, Gravity.CENTER).apply {
                setMargins(loadingMargin, loadingMargin, loadingMargin, loadingMargin)
            }
        )
        addView(
            tvText,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        )
        super.setOnClickListener {
            if (!showErrorDialog(it)) {
                onClickListener?.onClick(it)
            }
        }
    }

    override fun setOnClickListener(l: OnClickListener?) {
        this.onClickListener = l
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        layoutParams?.width = LayoutParams.MATCH_PARENT
    }

    fun startLoad() {
        isLoading = true
        tvText.invisible()
        rotateLoading.visible()
    }

    fun stopLoad() {
        isLoading = false
        rotateLoading.inVisible()
    }

    fun hasMore() {
        errorMsg = ""
        hasMore = true
        startLoad()
    }

    fun noMore(msg: String? = null) {
        stopLoad()
        errorMsg = ""
        hasMore = false
        if (msg != null) {
            tvText.text = msg
        } else {
            tvText.setText(R.string.bottom_line)
        }
        tvText.visible()
    }

    fun error(msg: String?, text: String = "") {
        stopLoad()
        hasMore = false
        errorMsg = msg ?: ""
        tvText.text =
            text.ifEmpty { context.getString(R.string.error_load_msg, "点击查看详情") }
        tvText.visible()
    }

    fun setLoadingColor(@ColorRes color: Int) {
        rotateLoading.loadingColor = context.getCompatColor(color)
    }

    fun setLoadingTextColor(@ColorRes color: Int) {
        tvText.setTextColor(context.getCompatColor(color))
    }

    private fun showErrorDialog(view: View): Boolean {
        if (errorMsg.isBlank()) {
            return false
        }
        context.alert(R.string.error) {
            setMessage(errorMsg)
            if (onClickListener != null) {
                neutralButton(R.string.retry) {
                    onClickListener?.onClick(view)
                }
            }
        }
        return true
    }

}
