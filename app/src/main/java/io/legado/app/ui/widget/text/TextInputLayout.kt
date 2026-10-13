package io.legado.app.ui.widget.text

import android.content.Context
import android.util.AttributeSet
import com.google.android.material.textfield.TextInputLayout
import io.legado.app.lib.theme.Selector
import io.legado.app.lib.theme.ThemeStore

class TextInputLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : TextInputLayout(context, attrs) {

    init {
        if (!isInEditMode) {
            defaultHintTextColor =
                Selector.colorBuild().setDefaultColor(ThemeStore.accentColor(context)).create()
        }
    }

}
