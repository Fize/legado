package io.legado.app.ui.widget.dynamiclayout

import android.content.Context
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import android.view.ViewStub
import android.widget.FrameLayout
import android.widget.ProgressBar
import androidx.appcompat.widget.AppCompatButton
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import io.legado.app.R

@Suppress("unused")
class DynamicFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs), ViewSwitcher {

    private var errorView: View? = null
    private var errorImage: AppCompatImageView? = null
    private var errorTextView: AppCompatTextView? = null
    private var actionBtn: AppCompatButton? = null

    private var progressView: View? = null
    private var progressBar: ProgressBar? = null

    private var contentView: View? = null

    private var errorIcon: Drawable? = null
    private var emptyIcon: Drawable? = null

    private var errorActionDescription: CharSequence? = null
    private var emptyActionDescription: CharSequence? = null
    private var emptyDescription: CharSequence? = null

    private var errorAction: Action? = null
    private var emptyAction: Action? = null

    private var changeListener: OnVisibilityChangeListener? = null

    init {
        addView(View(context).apply { id = R.id.error_view_stub; visibility = View.GONE })
        addView(View(context).apply { id = R.id.progress_view_stub; visibility = View.GONE })

        val a = context.obtainStyledAttributes(attrs, R.styleable.DynamicFrameLayout)
        errorIcon = a.getDrawable(R.styleable.DynamicFrameLayout_errorSrc)
        emptyIcon = a.getDrawable(R.styleable.DynamicFrameLayout_emptySrc)

        emptyActionDescription = a.getText(R.styleable.DynamicFrameLayout_emptyActionDescription)
        emptyDescription = a.getText(R.styleable.DynamicFrameLayout_emptyDescription)

        errorActionDescription = a.getText(R.styleable.DynamicFrameLayout_errorActionDescription)
        if (errorActionDescription == null) {
            errorActionDescription = context.getString(R.string.dynamic_click_retry)
        }
        a.recycle()
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        if (childCount > 2) {
            contentView = getChildAt(2)
        }
    }

    override fun showErrorView(message: CharSequence) {
        ensureErrorView()

        setViewVisible(errorView, true)
        setViewVisible(contentView, false)
        setViewVisible(progressView, false)

        errorTextView?.text = message
        errorImage?.setImageDrawable(errorIcon)

        actionBtn?.let {
            it.tag = ACTION_WHEN_ERROR
            it.visibility = View.VISIBLE
            if (errorActionDescription != null) {
                it.text = errorActionDescription
            }
        }

        dispatchVisibilityChanged(ViewSwitcher.SHOW_ERROR_VIEW)
    }

    override fun showErrorView(messageId: Int) {
        showErrorView(resources.getText(messageId))
    }

    override fun showEmptyView() {
        ensureErrorView()

        setViewVisible(errorView, true)
        setViewVisible(contentView, false)
        setViewVisible(progressView, false)

        errorTextView?.text = emptyDescription
        errorImage?.setImageDrawable(emptyIcon)

        actionBtn?.let {
            it.tag = ACTION_WHEN_EMPTY
            if (errorActionDescription != null) {
                it.visibility = View.VISIBLE
                it.text = errorActionDescription
            } else {
                it.visibility = View.INVISIBLE
            }
        }

        dispatchVisibilityChanged(ViewSwitcher.SHOW_EMPTY_VIEW)
    }

    override fun showProgressView() {
        ensureProgressView()

        setViewVisible(errorView, false)
        setViewVisible(contentView, false)
        setViewVisible(progressView, true)

        dispatchVisibilityChanged(ViewSwitcher.SHOW_PROGRESS_VIEW)
    }

    override fun showContentView() {
        setViewVisible(errorView, false)
        setViewVisible(contentView, true)
        setViewVisible(progressView, false)

        dispatchVisibilityChanged(ViewSwitcher.SHOW_CONTENT_VIEW)
    }

    fun setOnVisibilityChangeListener(listener: OnVisibilityChangeListener) {
        changeListener = listener
    }

    fun setErrorAction(action: Action) {
        errorAction = action
    }

    fun setEmptyAction(action: Action) {
        emptyAction = action
    }

    private fun setViewVisible(view: View?, visible: Boolean) {
        view?.let {
            it.visibility = if (visible) View.VISIBLE else View.INVISIBLE
        }
    }

    private fun ensureErrorView() {
        if (errorView == null) {
            val density = resources.displayMetrics.density
            val pad16 = (16 * density).toInt()
            val minW96 = (96 * density).toInt()
            errorImage = AppCompatImageView(context).apply {
                id = R.id.iv_error_image
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    LayoutParams.WRAP_CONTENT,
                    LayoutParams.WRAP_CONTENT
                )
            }
            errorTextView = AppCompatTextView(context).apply {
                id = R.id.tv_error_message
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    LayoutParams.WRAP_CONTENT,
                    LayoutParams.WRAP_CONTENT
                )
                setTextAppearance(context, R.style.Style_Text_Primary_Normal)
                setPadding(0, pad16, 0, pad16)
            }
            actionBtn = AppCompatButton(context).apply {
                id = R.id.btn_error_retry
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    LayoutParams.MATCH_PARENT,
                    LayoutParams.WRAP_CONTENT
                )
                backgroundTintList = androidx.core.content.ContextCompat.getColorStateList(context, R.color.accent)
                minWidth = minW96
                setText(R.string.dynamic_click_retry)
            }
            errorView = android.widget.LinearLayout(context).apply {
                layoutParams = LayoutParams(
                    LayoutParams.WRAP_CONTENT,
                    LayoutParams.WRAP_CONTENT,
                    android.view.Gravity.CENTER
                )
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                orientation = android.widget.LinearLayout.VERTICAL
                addView(errorImage)
                addView(errorTextView)
                addView(actionBtn)
            }
            addView(errorView)

            actionBtn?.setOnClickListener {
                when (it.tag) {
                    ACTION_WHEN_EMPTY -> emptyAction?.onAction(this@DynamicFrameLayout)
                    ACTION_WHEN_ERROR -> errorAction?.onAction(this@DynamicFrameLayout)
                }
            }
        }
    }

    private fun ensureProgressView() {
        if (progressView == null) {
            val density = resources.displayMetrics.density
            val size48 = (48 * density).toInt()
            val pad16 = (16 * density).toInt()
            progressBar = androidx.core.widget.ContentLoadingProgressBar(
                androidx.appcompat.view.ContextThemeWrapper(context, androidx.appcompat.R.style.Widget_AppCompat_ProgressBar)
            ).apply {
                id = R.id.loading_progress
                layoutParams = android.widget.LinearLayout.LayoutParams(size48, size48)
            }
            val tvLoading = AppCompatTextView(context).apply {
                id = R.id.tv_loading_message
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    LayoutParams.WRAP_CONTENT,
                    LayoutParams.WRAP_CONTENT
                )
                setTextAppearance(context, R.style.Style_Text_Second_Normal_Wrap)
                setPadding(0, pad16, 0, 0)
                setText(R.string.dynamic_loading)
            }
            progressView = android.widget.LinearLayout(context).apply {
                layoutParams = LayoutParams(
                    LayoutParams.WRAP_CONTENT,
                    LayoutParams.WRAP_CONTENT,
                    android.view.Gravity.CENTER
                )
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                orientation = android.widget.LinearLayout.VERTICAL
                addView(progressBar)
                addView(tvLoading)
            }
            addView(progressView)
        }
    }

    private fun dispatchVisibilityChanged(@ViewSwitcher.Visibility visibility: Int) {
        changeListener?.onVisibilityChanged(visibility)
    }

    interface Action {
        fun onAction(switcher: ViewSwitcher)
    }


    interface OnVisibilityChangeListener {

        fun onVisibilityChanged(@ViewSwitcher.Visibility visibility: Int)
    }

    companion object {
        private const val ACTION_WHEN_ERROR = "ACTION_WHEN_ERROR"
        private const val ACTION_WHEN_EMPTY = "ACTION_WHEN_EMPTY"
    }
}
