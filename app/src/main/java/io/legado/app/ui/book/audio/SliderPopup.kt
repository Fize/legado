package io.legado.app.ui.book.audio

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import io.legado.app.R
import io.legado.app.lib.theme.view.ThemeSeekBar
import io.legado.app.model.AudioPlay
import io.legado.app.service.AudioPlayService
import io.legado.app.ui.widget.seekbar.SeekBarChangeListener
import io.legado.app.utils.applyMd3PopupStyle
import io.legado.app.utils.dpToPx
import kotlin.math.roundToInt

class SliderPopup(private val context: Context, private val name: Int) :
    PopupWindow(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) {
    companion object {
        const val TIMER = 1
        const val SPEED = 2
    }

    private val tvSeekValue = TextView(context).apply {
        setTextColor(ContextCompat.getColor(context, R.color.secondaryText))
    }
    private val seekBar = ThemeSeekBar(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    init {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val pad = 8.dpToPx()
            setPadding(pad, pad, pad, pad)
            addView(
                tvSeekValue,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            addView(seekBar)
        }
        contentView = root
        applyMd3PopupStyle()
        isTouchable = true
        isOutsideTouchable = false
        isFocusable = true
        setProcess()
        seekBar.setOnSeekBarChangeListener(object : SeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (name == TIMER) {
                    setProcessTimerText(progress)
                    if (fromUser) {
                        AudioPlay.setTimer(progress)
                    }
                    return
                }
                val speed = (progress / 10f).roundToInt() / 10f
                setProcessSpeedText(speed)
                if (fromUser) {
                    // 设置播放速度 (转换为0.5-3.0范围)
                    AudioPlay.setSpeed(speed)
                }
            }
        })
    }

    override fun showAsDropDown(anchor: View?, xoff: Int, yoff: Int, gravity: Int) {
        super.showAsDropDown(anchor, xoff, yoff, gravity)
        if (name == TIMER) {
            seekBar.progress = AudioPlayService.timeMinute
        } else {
            seekBar.progress = (AudioPlayService.playSpeed * 100).toInt()
        }
    }

    override fun showAtLocation(parent: View?, gravity: Int, x: Int, y: Int) {
        super.showAtLocation(parent, gravity, x, y)
        if (name == TIMER) {
            seekBar.progress = AudioPlayService.timeMinute
        } else {
            seekBar.progress = (AudioPlayService.playSpeed * 100).toInt()
        }
    }

    private fun setProcessTimerText(process: Int) {
        tvSeekValue.text = context.getString(R.string.timer_m, process)
    }

    @SuppressLint("SetTextI18n")
    private fun setProcessSpeedText(speed: Float) {
        tvSeekValue.text = "%.1fX".format(speed)
    }

    private fun setProcess() {
        if (name == TIMER) {
            seekBar.max = 180
            setProcessTimerText(0)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            seekBar.min = 50
        }
        seekBar.max = 300
        seekBar.progress = (AudioPlayService.playSpeed * 100).toInt()
        setProcessSpeedText(AudioPlayService.playSpeed)
    }
}
