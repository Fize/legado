package io.legado.app.ui.widget

import android.content.Context
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import com.google.android.flexbox.FlexDirection
import com.google.android.flexbox.FlexWrap
import com.google.android.flexbox.FlexboxLayoutManager
import io.legado.app.R
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.lib.theme.secondaryDisabledTextColor
import io.legado.app.utils.applyMd3PopupStyle
import io.legado.app.utils.dpToPx
import io.legado.app.utils.getCompatColor
import io.legado.app.utils.resolveDropDownYOffset
import io.legado.app.utils.setTintMutate

class PopupAction(private val context: Context) :
    PopupWindow(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT) {

    class PopupActionViewHolder(context: Context) : ViewBinding {
        val recyclerView = RecyclerView(context).apply {
            id = R.id.recycler_view
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER
            }
            layoutManager = FlexboxLayoutManager(context).apply {
                flexDirection = FlexDirection.ROW
                flexWrap = FlexWrap.WRAP
            }
        }
        val root = LinearLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.shape_card_view)
            val pad = 5.dpToPx()
            setPadding(pad, pad, pad, pad)
            addView(recyclerView)
        }

        override fun getRoot(): View = root
    }

    class ItemPopupActionViewHolder(context: Context) : ViewBinding {
        val ivIcon = AppCompatImageView(context).apply {
            id = R.id.iv_icon
            layoutParams = LinearLayout.LayoutParams(24.dpToPx(), 24.dpToPx()).apply {
                marginEnd = 12.dpToPx()
            }
            visibility = View.GONE
        }
        val textView = TextView(context).apply {
            id = R.id.text_view
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            val value = TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)
            setBackgroundResource(value.resourceId)
            isDuplicateParentStateEnabled = true
            ellipsize = TextUtils.TruncateAt.END
            maxLines = 1
            val pad = 5.dpToPx()
            setPadding(pad, pad, pad, pad)
            setTextColor(context.getCompatColor(R.color.primaryText))
            textSize = 14f
        }
        val ivCheckEnd = AppCompatImageView(context).apply {
            id = R.id.iv_check_end
            layoutParams = LinearLayout.LayoutParams(24.dpToPx(), 24.dpToPx()).apply {
                marginStart = 8.dpToPx()
            }
            setImageResource(R.drawable.ic_check)
            visibility = View.GONE
        }
        val root = LinearLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            isClickable = true
            isFocusable = true
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            addView(ivIcon)
            addView(textView)
            addView(ivCheckEnd)
        }

        override fun getRoot(): View = root
    }

    val binding = PopupActionViewHolder(context)
    val adapter by lazy {
        Adapter(context).apply {
            setHasStableIds(true)
        }
    }
    var onActionClick: ((action: String) -> Unit)? = null
    private var isVertical = false
    private var dangerValues: Set<String> = emptySet()
    private var disabledValues: Set<String> = emptySet()
    private var actionItems: List<PopupActionItem> = emptyList()
    private var reserveIconColumn = false
    private var reserveCheckColumn = false
    private val measureRow by lazy {
        ItemPopupActionViewHolder(context).apply {
            textView.setPadding(0, 0, 0, 0)
            textView.minWidth = 0
            textView.minHeight = 0
        }
    }

    init {
        contentView = binding.root
        applyMd3PopupStyle()

        isTouchable = true
        isOutsideTouchable = false
        isFocusable = true

        binding.recyclerView.adapter = adapter
    }

    fun setItems(items: List<SelectItem<String>>) {
        setActionItems(
            items.map { item ->
                PopupActionItem(
                    title = item.title,
                    value = item.value
                )
            }
        )
    }

    fun setActionItems(items: List<PopupActionItem>) {
        actionItems = items
        updateMenuLayout(items)
        adapter.setItems(items)
    }

    private fun updateMenuLayout(items: List<PopupActionItem>) {
        reserveIconColumn = isVertical && items.any { it.icon != null }
        reserveCheckColumn = isVertical && items.any { it.checkable }
        binding.recyclerView.updateLayoutParams {
            width = if (isVertical) {
                measureVerticalMenuWidth(items)
            } else {
                ViewGroup.LayoutParams.WRAP_CONTENT
            }
        }
    }

    private fun measureVerticalMenuWidth(items: List<PopupActionItem>): Int {
        val unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        val textView = measureRow.textView
        var maxTextWidth = 0
        items.forEach { item ->
            textView.text = item.title
            textView.measure(unspecified, unspecified)
            maxTextWidth = maxOf(maxTextWidth, textView.measuredWidth)
        }
        val horizontalPadding = 32.dpToPx()
        val iconColumn = if (reserveIconColumn) 36.dpToPx() else 0
        val checkColumn = if (reserveCheckColumn) 32.dpToPx() else 0
        return (horizontalPadding + iconColumn + checkColumn + maxTextWidth)
            .coerceIn(112.dpToPx(), 280.dpToPx())
    }

    fun setVertical(vertical: Boolean) {
        if (isVertical == vertical && binding.recyclerView.layoutManager != null) return
        isVertical = vertical
        binding.recyclerView.layoutManager = if (vertical) {
            LinearLayoutManager(context)
        } else {
            FlexboxLayoutManager(context).apply {
                flexDirection = FlexDirection.ROW
                flexWrap = FlexWrap.WRAP
            }
        }
        updateMenuLayout(actionItems)
        if (adapter.itemCount > 0) adapter.notifyDataSetChanged()
    }

    fun setDangerValues(values: Set<String>) {
        if (dangerValues == values) return
        dangerValues = values
        if (adapter.itemCount > 0) adapter.notifyDataSetChanged()
    }

    fun setDisabledValues(values: Set<String>) {
        if (disabledValues == values) return
        disabledValues = values
        if (adapter.itemCount > 0) adapter.notifyDataSetChanged()
    }

    override fun showAsDropDown(anchor: View?, xoff: Int, yoff: Int, gravity: Int) {
        if (anchor == null) {
            super.showAsDropDown(anchor, xoff, yoff, gravity)
            return
        }
        val visibleFrame = Rect()
        anchor.getWindowVisibleDisplayFrame(visibleFrame)
        contentView.measure(
            View.MeasureSpec.makeMeasureSpec(visibleFrame.width(), View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(visibleFrame.height(), View.MeasureSpec.AT_MOST),
        )
        val location = IntArray(2)
        anchor.getLocationOnScreen(location)
        val resolvedYOff = resolveDropDownYOffset(
            anchorTop = location[1],
            anchorHeight = anchor.height,
            popupHeight = contentView.measuredHeight,
            frameTop = visibleFrame.top,
            frameBottom = visibleFrame.bottom,
            gap = yoff,
        )
        super.showAsDropDown(anchor, xoff, resolvedYOff, gravity)
    }

    data class PopupActionItem(
        val title: String,
        val value: String,
        val icon: Drawable? = null,
        val enabled: Boolean = true,
        val checkable: Boolean = false,
        val checked: Boolean = false
    )

    inner class Adapter(context: Context) :
        RecyclerAdapter<PopupActionItem, ItemPopupActionViewHolder>(context) {

        override fun getItemId(position: Int): Long {
            return position.toLong()
        }

        override fun getViewBinding(parent: ViewGroup): ItemPopupActionViewHolder {
            return ItemPopupActionViewHolder(context)
        }

        override fun convert(
            holder: ItemViewHolder,
            binding: ItemPopupActionViewHolder,
            item: PopupActionItem,
            payloads: MutableList<Any>
        ) {
            with(binding) {
                val enabled = isItemEnabled(item)
                root.isEnabled = enabled
                ViewCompat.setAccessibilityDelegate(
                    root,
                    object : AccessibilityDelegateCompat() {
                        override fun onInitializeAccessibilityNodeInfo(
                            host: View,
                            info: AccessibilityNodeInfoCompat
                        ) {
                            super.onInitializeAccessibilityNodeInfo(host, info)
                            info.isCheckable = item.checkable
                            info.setChecked(
                                if (item.checked) {
                                    AccessibilityNodeInfoCompat.CHECKED_STATE_TRUE
                                } else {
                                    AccessibilityNodeInfoCompat.CHECKED_STATE_FALSE
                                }
                            )
                        }
                    }
                )
                textView.text = item.title
                if (isVertical) {
                    root.updateLayoutParams { width = ViewGroup.LayoutParams.MATCH_PARENT }
                    root.minimumHeight = 48.dpToPx()
                    root.setPadding(16.dpToPx(), 0, 16.dpToPx(), 0)
                    root.setBackgroundResource(selectableItemBackgroundResId())
                    textView.updateLayoutParams<LinearLayout.LayoutParams> {
                        width = 0
                        weight = 1f
                    }
                    textView.minHeight = 48.dpToPx()
                    textView.minWidth = 0
                    textView.gravity = Gravity.CENTER_VERTICAL
                    textView.setPadding(0, 0, 0, 0)
                    textView.background = null
                } else {
                    root.updateLayoutParams { width = ViewGroup.LayoutParams.WRAP_CONTENT }
                    root.minimumHeight = 0
                    root.setPadding(0, 0, 0, 0)
                    root.background = null
                    textView.updateLayoutParams<LinearLayout.LayoutParams> {
                        width = ViewGroup.LayoutParams.WRAP_CONTENT
                        weight = 0f
                    }
                    textView.minHeight = 0
                    textView.minWidth = 0
                    textView.gravity = Gravity.CENTER
                    textView.setPadding(5.dpToPx(), 5.dpToPx(), 5.dpToPx(), 5.dpToPx())
                    textView.setBackgroundResource(selectableItemBackgroundResId())
                }
                val textColor = when {
                    !enabled -> context.secondaryDisabledTextColor
                    item.value in dangerValues -> context.getCompatColor(R.color.error)
                    else -> context.getCompatColor(R.color.primaryText)
                }
                textView.setTextColor(textColor)
                bindLeadingIcon(ivIcon, item, textColor)
                bindTrailingCheck(ivCheckEnd, item, textColor)
            }
        }

        private fun bindLeadingIcon(
            imageView: android.widget.ImageView,
            item: PopupActionItem,
            tint: Int
        ) {
            when {
                item.icon != null -> {
                    imageView.setImageDrawable(item.icon)
                    item.icon.setTintMutate(tint)
                    imageView.visibility = View.VISIBLE
                }

                isVertical && reserveIconColumn -> {
                    imageView.setImageDrawable(null)
                    imageView.visibility = View.INVISIBLE
                }

                else -> {
                    imageView.setImageDrawable(null)
                    imageView.visibility = View.GONE
                }
            }
        }

        private fun bindTrailingCheck(
            imageView: android.widget.ImageView,
            item: PopupActionItem,
            tint: Int
        ) {
            when {
                item.checked -> {
                    imageView.setImageResource(R.drawable.ic_check)
                    imageView.drawable?.setTintMutate(tint)
                    imageView.visibility = View.VISIBLE
                }

                isVertical && reserveCheckColumn -> {
                    imageView.setImageDrawable(null)
                    imageView.visibility = View.INVISIBLE
                }

                else -> {
                    imageView.setImageDrawable(null)
                    imageView.visibility = View.GONE
                }
            }
        }

        override fun registerListener(holder: ItemViewHolder, binding: ItemPopupActionViewHolder) {
            holder.itemView.setOnClickListener {
                getItem(holder.layoutPosition)?.takeIf(::isItemEnabled)?.let { item ->
                    onActionClick?.invoke(item.value)
                }
            }
        }

        private fun isItemEnabled(item: PopupActionItem): Boolean {
            return item.enabled && item.value !in disabledValues
        }

        private fun selectableItemBackgroundResId(): Int {
            val value = TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)
            return value.resourceId
        }
    }

}
