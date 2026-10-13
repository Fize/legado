package io.legado.app.help.gsyVideo

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import io.legado.app.R

class SwitchVideoAdapter<T>(
    context: Context,
    private val dataList: List<T>,
    private val titleProvider: (T) -> String = { it.toString() }
) : ArrayAdapter<T>(context, 0, dataList) {

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val density = context.resources.displayMetrics.density
        val padH = (15 * density).toInt()
        val padV = (10 * density).toInt()
        val view = convertView ?: TextView(context).apply {
            id = R.id.text1
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setBackgroundResource(R.drawable.card_video_background)
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(padH, padV, padH, padV)
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 15f
        }
        val textView = view.findViewById<TextView>(R.id.text1)
        val item = dataList[position]
        textView.text = titleProvider(item)
        return view
    }
}