package io.legado.app.help.gsyVideo

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.widget.TextView
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ListView
import io.legado.app.R
import io.legado.app.data.entities.BookChapter

class ChoiceEpisodeDialog(private val mContext: Context) : Dialog(
    mContext, R.style.dialog_style
) {
    private var listView: ListView? = null

    private var adapter: ArrayAdapter<BookChapter>? = null

    private var onItemClickListener: OnListItemClickListener? = null

    private var data: List<BookChapter>? = null

    interface OnListItemClickListener {
        fun onItemClick(position: Int)
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
        data: List<BookChapter>,
        onItemClickListener: OnListItemClickListener,
        initialSelection: Int = -1
    ) {
        this.onItemClickListener = onItemClickListener
        this.data = data
        val d = mContext.resources.displayMetrics
        val pad8 = (8 * d.density).toInt()
        val pad16 = (16 * d.density).toInt()
        val div4 = (4 * d.density).toInt()
        val view = android.widget.LinearLayout(mContext).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(0x80121212.toInt())
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(0, 0, 0, pad8)
            addView(
                TextView(mContext).apply {
                    id = R.id.listCount
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(pad8, pad8, pad8, pad8)
                    }
                    gravity = Gravity.CENTER
                    minEms = 2
                    text = "选集（${data.size}）"
                    setTextColor(0x88FFFFFF.toInt())
                }
            )
            addView(
                ListView(mContext).apply {
                    id = R.id.switch_dialog_list
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT
                    ).apply {
                        marginStart = pad8
                        marginEnd = pad16
                    }
                    background = null
                    cacheColorHint = 0x00000000
                    divider = android.graphics.drawable.ColorDrawable(0x00000000)
                    dividerHeight = div4
                    setSelector(android.R.color.transparent)
                }.also { listView = it }
            )
        }
        setContentView(view)
        adapter = SwitchVideoAdapter(mContext, data) { item -> item.title }
        listView!!.setAdapter(adapter)
        if (initialSelection >= 0 && initialSelection < data.size) {
            listView!!.setSelectionFromTop(initialSelection, 0)
        }
        listView!!.onItemClickListener = this@ChoiceEpisodeDialog.OnItemClickListener()
        val dialogWindow = window
        val lp = dialogWindow!!.attributes
        lp.width = (d.widthPixels * 0.4).toInt() // 宽度设置为屏幕的0.4
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
            onItemClickListener!!.onItemClick(position)
        }
    }
}