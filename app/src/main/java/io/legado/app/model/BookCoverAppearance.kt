package io.legado.app.model

import android.graphics.Typeface
import android.graphics.drawable.Drawable

/** An immutable render input. Drawable consumers must obtain their own instance before drawing. */
data class BookCoverAppearance(
    val defaultDrawable: Drawable,
    val useDefault: Boolean,
    val drawName: Boolean,
    val drawAuthor: Boolean,
    val horizontal: Boolean,
    val adaptive: Boolean,
    val keepPunctuation: Boolean,
    val fontSizes: CoverFontSizes?,
    val typeface: Typeface?,
    val fontKey: String,
    val background: Int,
    val accent: Int,
)
