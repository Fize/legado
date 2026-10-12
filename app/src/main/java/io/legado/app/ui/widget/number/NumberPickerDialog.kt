package io.legado.app.ui.widget.number

import android.content.Context
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.NumberPicker
import androidx.appcompat.app.AlertDialog
import io.legado.app.R
import io.legado.app.utils.applyTint
import io.legado.app.utils.hideSoftInput

class NumberPickerDialog(private val context: Context, private val isDecimalMode: Boolean = false) {
    private val builder = AlertDialog.Builder(context)
    private var numberPicker: NumberPicker? = null
    private var maxValue: Int? = null
    private var minValue: Int? = null
    private var value: Int? = null
    private var displayedValues: Array<String>? = null

    fun setTitle(title: String): NumberPickerDialog {
        builder.setTitle(title)
        return this
    }

    fun setMaxValue(value: Int): NumberPickerDialog {
        maxValue = value
        return this
    }

    fun setMinValue(value: Int): NumberPickerDialog {
        minValue = value
        return this
    }

    fun setValue(value: Int): NumberPickerDialog {
        this.value = value
        return this
    }

    fun setDisplayedValues(values: Array<String>): NumberPickerDialog {
        displayedValues = values
        return this
    }

    fun setCustomButton(textId: Int, listener: (() -> Unit)?): NumberPickerDialog {
        builder.setNeutralButton(textId) { _, _ ->
            numberPicker?.let {
                it.clearFocus()
                it.hideSoftInput()
                listener?.invoke()
            }
        }
        return this
    }

    fun show(callBack: ((value: Int) -> Unit)?) {
        val picker = NumberPicker(context).apply {
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            this@NumberPickerDialog.minValue?.let { minValue = it }
            this@NumberPickerDialog.maxValue?.let { maxValue = it }
            this@NumberPickerDialog.value?.let { value = it }
            if (isDecimalMode) {
                val min = this@NumberPickerDialog.minValue ?: 0
                val max = this@NumberPickerDialog.maxValue ?: 0
                displayedValues = Array(max - min + 1) { i ->
                    ((min + i) / 10.0).toString()
                }
            }
            this@NumberPickerDialog.displayedValues?.let { displayedValues = it }
        }
        numberPicker = picker
        val container = FrameLayout(context).apply {
            addView(
                picker,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                )
            )
        }
        builder.setView(container)
        builder.setPositiveButton(R.string.ok) { _, _ ->
            numberPicker?.let { np ->
                np.clearFocus()
                np.hideSoftInput()
                callBack?.invoke(np.value)
            }
        }
        builder.setNegativeButton(R.string.cancel, null)
        builder.show().applyTint()
    }
}
