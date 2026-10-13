package io.legado.app.ui.widget.text

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.AppCompatAutoCompleteTextView
import com.google.android.material.textfield.TextInputLayout as MaterialTextInputLayout
import io.legado.app.R
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.popupBackground
import io.legado.app.utils.applyTint
import io.legado.app.utils.gone
import io.legado.app.utils.visible


@Suppress("unused")
class AutoCompleteTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatAutoCompleteTextView(context, attrs) {

    var delCallBack: ((value: String) -> Unit)? = null

    init {
        applyTint(context.accentColor)
        setDropDownBackgroundDrawable(context.popupBackground)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            isLocalePreferredLineHeightForMinimumUsed = false
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val currentHint = hint ?: return
        var ancestor: ViewParent? = parent
        while (ancestor != null && ancestor !is MaterialTextInputLayout) {
            ancestor = ancestor.parent
        }
        if (ancestor is MaterialTextInputLayout) {
            ancestor.hint = currentHint
            hint = null
        }
    }

    override fun enoughToFilter(): Boolean {
        return true
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent?): Boolean {
        if (event?.action == MotionEvent.ACTION_DOWN) {
            showDropDown()
        }
        return super.onTouchEvent(event)
    }

    fun setFilterValues(values: List<String>?) {
        values?.let {
            setAdapter(MyAdapter(context, values))
        }
    }

    fun setFilterValues(vararg value: String) {
        setAdapter(MyAdapter(context, value.toMutableList()))
    }

    inner class MyAdapter(context: Context, values: List<String>) :
        ArrayAdapter<String>(context, android.R.layout.simple_dropdown_item_1line, values) {

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: create1LineTextAndDelView(context)
            val textView = view.findViewById<TextView>(R.id.text_view)
            textView.text = getItem(position)
            val ivDelete = view.findViewById<ImageView>(R.id.iv_delete)
            if (delCallBack != null) ivDelete.visible() else ivDelete.gone()
            ivDelete.setOnClickListener {
                getItem(position)?.let {
                    remove(it)
                    delCallBack?.invoke(it)
                    showDropDown()
                }
            }
            return view
        }
    }

}

fun create1LineTextAndDelView(context: Context): View {
    val pad = (6 * context.resources.displayMetrics.density).toInt()
    val iconSize = (24 * context.resources.displayMetrics.density).toInt()
    val primaryColor = androidx.core.content.ContextCompat.getColor(context, R.color.primaryText)
    return android.widget.LinearLayout(context).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        gravity = android.view.Gravity.CENTER_VERTICAL
        orientation = android.widget.LinearLayout.HORIZONTAL
        setPadding(pad, pad, pad, pad)
        addView(
            TextView(context).apply {
                id = R.id.text_view
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
                )
                isSingleLine = true
                setTextColor(primaryColor)
            }
        )
        addView(
            ImageView(context).apply {
                id = R.id.iv_delete
                layoutParams = android.widget.LinearLayout.LayoutParams(iconSize, iconSize)
                contentDescription = context.getString(R.string.delete)
                setImageResource(R.drawable.ic_clear_all)
                visibility = View.GONE
                setColorFilter(primaryColor)
            }
        )
    }
}
