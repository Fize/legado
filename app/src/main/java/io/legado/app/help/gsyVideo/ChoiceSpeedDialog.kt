package io.legado.app.help.gsyVideo

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ListView
import io.legado.app.R

class ChoiceSpeedDialog(private val mContext: Context) : Dialog(
    mContext, R.style.dialog_style
) {
    private var listView: ListView? = null

    private var adapter: ArrayAdapter<Float>? = null

    private var onItemClickListener: OnListItemClickListener? = null

    private var data: List<Float>? = null

    interface OnListItemClickListener {
        fun onItemClick(value: Float)
        fun finishDialog()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun onStop() {
        onItemClickListener!!.finishDialog()
        super.onStop()
    }

    @SuppressLint("SetTextI18n")
    fun initList(
        data: List<Float>,
        onItemClickListener: OnListItemClickListener
    ) {
        this.onItemClickListener = onItemClickListener
        this.data = data
        val d = mContext.resources.displayMetrics
        val pad4 = (4 * d.density).toInt()
        val pad8 = (8 * d.density).toInt()
        val pad16 = (16 * d.density).toInt()
        val view = android.widget.LinearLayout(mContext).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(0x80121212.toInt())
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(0, pad8, 0, pad8)
            addView(
                View(mContext).apply {
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        0,
                        1f
                    )
                }
            )
            addView(
                ListView(mContext).apply {
                    id = R.id.switch_dialog_list
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        marginStart = pad4
                        marginEnd = pad16
                    }
                    background = null
                    cacheColorHint = 0x00000000
                    divider = android.graphics.drawable.ColorDrawable(0x00000000)
                    dividerHeight = pad4
                    setSelector(android.R.color.transparent)
                }.also { listView = it }
            )
        }
        setContentView(view)
        adapter = SwitchVideoAdapter(mContext, data) { item -> item.toString() + "X" }
        listView!!.setAdapter(adapter)
        listView!!.onItemClickListener = this@ChoiceSpeedDialog.OnItemClickListener()
        val dialogWindow = window
        val lp = dialogWindow!!.attributes
        lp.width = (d.widthPixels * 0.3).toInt() // 宽度设置为屏幕的0.3
        lp.height = d.heightPixels
        lp.gravity = Gravity.END // 设置靠右对齐
        dialogWindow.setAttributes(lp)
    }

    private inner class OnItemClickListener : AdapterView.OnItemClickListener {
        override fun onItemClick(
            adapterView: AdapterView<*>?,
            view: View?,
            position: Int,
            id: Long
        ) {
            dismiss()
            onItemClickListener!!.onItemClick(data?.get(position) ?: 1.0f)
        }
    }
}