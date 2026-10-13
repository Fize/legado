package io.legado.app.databinding

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.TooltipCompat
import androidx.cardview.widget.CardView
import androidx.core.widget.NestedScrollView
import androidx.viewbinding.ViewBinding
import io.legado.app.R
import io.legado.app.lib.theme.view.ThemeCheckBox
import io.legado.app.lib.theme.view.ThemeSeekBar
import io.legado.app.ui.widget.code.CodeView
import io.legado.app.ui.widget.text.AccentStrokeTextView
import io.legado.app.ui.widget.text.AutoCompleteTextView
import io.legado.app.ui.widget.text.TextInputLayout
import io.legado.app.utils.dpToPx
import io.legado.app.utils.getCompatColor

class DialogEditTextBinding(context: Context) : ViewBinding {
    val editView = AutoCompleteTextView(context).apply {
        id = R.id.edit_view
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        minHeight = 48.dpToPx()
        setPaddingRelative(12.dpToPx(), 6.dpToPx(), 12.dpToPx(), 6.dpToPx())
    }
    val textInputLayout = TextInputLayout(context).apply {
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        addView(editView)
    }
    val root = NestedScrollView(context).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        setPadding(16.dpToPx(), 0, 16.dpToPx(), 0)
        addView(textInputLayout)
    }

    override fun getRoot(): View = root

    companion object {
        @JvmStatic
        @JvmOverloads
        fun inflate(
            inflater: LayoutInflater,
            parent: ViewGroup? = null,
            attachToParent: Boolean = false
        ): DialogEditTextBinding {
            val binding = DialogEditTextBinding(inflater.context)
            if (attachToParent && parent != null) {
                parent.addView(binding.root)
            }
            return binding
        }
    }
}

class DialogEditCodeBinding(context: Context) : ViewBinding {
    val editView = CodeView(context).apply {
        id = R.id.edit_view
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }
    val editViewC = TextInputLayout(context).apply {
        id = R.id.edit_view_c
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        addView(editView)
    }
    val root = NestedScrollView(context).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        setPadding(16.dpToPx(), 0, 16.dpToPx(), 0)
        addView(editViewC)
    }

    override fun getRoot(): View = root

    companion object {
        @JvmStatic
        @JvmOverloads
        fun inflate(
            inflater: LayoutInflater,
            parent: ViewGroup? = null,
            attachToParent: Boolean = false
        ): DialogEditCodeBinding {
            val binding = DialogEditCodeBinding(inflater.context)
            if (attachToParent && parent != null) {
                parent.addView(binding.root)
            }
            return binding
        }
    }
}

class ViewToastBinding(context: Context) : ViewBinding {
    val tvText = TextView(context).apply {
        id = R.id.tv_text
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(16.dpToPx(), 8.dpToPx(), 16.dpToPx(), 10.dpToPx())
    }
    val cvToast = CardView(context).apply {
        id = R.id.cv_toast
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        radius = 10.dpToPx().toFloat()
        cardElevation = 2.dpToPx().toFloat()
        useCompatPadding = true
        addView(tvText)
    }
    val root = LinearLayout(context).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        orientation = LinearLayout.VERTICAL
        addView(cvToast)
    }

    override fun getRoot(): View = root

    companion object {
        @JvmStatic
        @JvmOverloads
        fun inflate(
            inflater: LayoutInflater,
            parent: ViewGroup? = null,
            attachToParent: Boolean = false
        ): ViewToastBinding {
            val binding = ViewToastBinding(inflater.context)
            if (attachToParent && parent != null) {
                parent.addView(binding.root)
            }
            return binding
        }
    }
}

class ViewSelectActionBarBinding(context: Context) : ViewBinding {
    val cbSelectedAll = ThemeCheckBox(context).apply {
        id = R.id.cb_selected_all
        layoutParams = LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1f
        )
        val pad = 5.dpToPx()
        setPadding(pad, pad, pad, pad)
        setText(R.string.select_all)
    }
    val btnRevertSelection = AccentStrokeTextView(context, null, true).apply {
        id = R.id.btn_revert_selection
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            val m = 5.dpToPx()
            setMargins(m, m, m, m)
        }
        gravity = Gravity.CENTER
        minWidth = 72.dpToPx()
        val pad = 5.dpToPx()
        setPadding(pad, pad, pad, pad)
        setText(R.string.revert_selection)
    }
    val btnSelectActionMain = AccentStrokeTextView(context, null, true).apply {
        id = R.id.btn_select_action_main
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            val m = 5.dpToPx()
            setMargins(m, m, m, m)
        }
        gravity = Gravity.CENTER
        minWidth = 72.dpToPx()
        val pad = 5.dpToPx()
        setPadding(pad, pad, pad, pad)
        setText(R.string.app_name)
        visibility = View.GONE
    }
    val ivMenuMore = AppCompatImageView(context).apply {
        id = R.id.iv_menu_more
        layoutParams = LinearLayout.LayoutParams(36.dpToPx(), 36.dpToPx())
        val value = TypedValue()
        context.theme.resolveAttribute(
            androidx.appcompat.R.attr.selectableItemBackgroundBorderless,
            value,
            true
        )
        setBackgroundResource(value.resourceId)
        contentDescription = context.getString(R.string.more_menu)
        val pad = 6.dpToPx()
        setPadding(pad, pad, pad, pad)
        setImageResource(R.drawable.ic_more_vert)
        setColorFilter(context.getCompatColor(R.color.primaryText))
        TooltipCompat.setTooltipText(this, context.getString(R.string.more_menu))
        visibility = View.GONE
    }
    val root = LinearLayout(context).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        elevation = 2.dpToPx().toFloat()
        gravity = Gravity.CENTER_VERTICAL
        orientation = LinearLayout.HORIZONTAL
        setPadding(16.dpToPx(), 6.dpToPx(), 8.dpToPx(), 6.dpToPx())
        addView(cbSelectedAll)
        addView(btnRevertSelection)
        addView(btnSelectActionMain)
        addView(ivMenuMore)
    }

    override fun getRoot(): View = root

    companion object {
        @JvmStatic
        @JvmOverloads
        fun inflate(
            inflater: LayoutInflater,
            parent: ViewGroup? = null,
            attachToParent: Boolean = false
        ): ViewSelectActionBarBinding {
            val binding = ViewSelectActionBarBinding(inflater.context)
            if (attachToParent && parent != null) {
                parent.addView(binding.root)
            }
            return binding
        }
    }
}

class ViewDetailSeekBarBinding(context: Context) : ViewBinding {
    val tvSeekTitle = TextView(context).apply {
        id = R.id.tv_seek_title
        layoutParams = LinearLayout.LayoutParams(
            60.dpToPx(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        isSingleLine = true
        text = "seek"
        setTextColor(context.getCompatColor(R.color.primaryText))
    }
    val ivSeekReduce = AppCompatImageView(context).apply {
        id = R.id.iv_seek_reduce
        layoutParams = LinearLayout.LayoutParams(24.dpToPx(), 24.dpToPx())
        contentDescription = context.getString(R.string.reduce)
        setImageResource(R.drawable.ic_reduce)
        val value = TypedValue()
        context.theme.resolveAttribute(
            android.R.attr.selectableItemBackgroundBorderless,
            value,
            true
        )
        setBackgroundResource(value.resourceId)
        setColorFilter(context.getCompatColor(R.color.primaryText))
    }
    val seekBar = ThemeSeekBar(context).apply {
        id = R.id.seek_bar
        layoutParams = LinearLayout.LayoutParams(
            0,
            25.dpToPx(),
            4f
        ).apply {
            gravity = Gravity.CENTER_VERTICAL
        }
        max = 10
    }
    val ivSeekPlus = AppCompatImageView(context).apply {
        id = R.id.iv_seek_plus
        layoutParams = LinearLayout.LayoutParams(24.dpToPx(), 24.dpToPx())
        contentDescription = context.getString(R.string.plus)
        setImageResource(R.drawable.ic_add)
        val value = TypedValue()
        context.theme.resolveAttribute(
            android.R.attr.selectableItemBackgroundBorderless,
            value,
            true
        )
        setBackgroundResource(value.resourceId)
        setColorFilter(context.getCompatColor(R.color.primaryText))
    }
    val tvSeekValue = TextView(context).apply {
        id = R.id.tv_seek_value
        layoutParams = LinearLayout.LayoutParams(
            60.dpToPx(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        gravity = Gravity.RIGHT
        isSingleLine = true
        text = "0"
        setTextColor(context.getCompatColor(R.color.primaryText))
    }
    val root = LinearLayout(context).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        addView(tvSeekTitle)
        addView(ivSeekReduce)
        addView(seekBar)
        addView(ivSeekPlus)
        addView(tvSeekValue)
    }

    override fun getRoot(): View = root

    companion object {
        @JvmStatic
        @JvmOverloads
        fun inflate(
            inflater: LayoutInflater,
            parent: ViewGroup? = null,
            attachToParent: Boolean = false
        ): ViewDetailSeekBarBinding {
            val binding = ViewDetailSeekBarBinding(inflater.context)
            if (attachToParent && parent != null) {
                parent.addView(binding.root)
            }
            return binding
        }
    }
}
