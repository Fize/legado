package io.legado.app.ui.book.read

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import io.legado.app.R
import io.legado.app.help.HighlightStyle
import io.legado.app.help.HighlightStyle.Deco
import io.legado.app.help.HighlightStyle.FillShape
import io.legado.app.help.HighlightStyle.Kind
import io.legado.app.help.HighlightStyle.Shadow
import io.legado.app.help.HighlightStyle.Underline
import io.legado.app.help.HighlightStyles
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.secondaryTextColor
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import io.legado.app.ui.font.FontSelectDialog
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.number.NumberPickerDialog
import io.legado.app.utils.showDialogFragment
import kotlin.math.roundToInt

class HighlightStyleDialog : BottomSheetDialogFragment(),
    ShadowEditDialog.Callback,
    UnderlineEditDialog.Callback,
    FontSelectDialog.CallBack {

    interface StyleHost {
        fun currentHighlightStyle(): HighlightStyle

        fun onHighlightStyleChanged(style: HighlightStyle)

        fun pickHighlightColor(dialogId: Int, initial: Int, withAlpha: Boolean)
    }

    private val styleHost get() = resolveStyleHost(parentFragment, activity)
    private var refreshVersion by mutableIntStateOf(0)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LegadoTheme {
                    @Suppress("UNUSED_EXPRESSION")
                    refreshVersion
                    HighlightStyleScreen()
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        if (styleHost == null) {
            dismiss()
            return
        }
        refresh()
    }

    private fun currentStyle(): HighlightStyle {
        return styleHost?.currentHighlightStyle() ?: HighlightStyle()
    }

    private fun apply(style: HighlightStyle) {
        styleHost?.onHighlightStyleChanged(style)
        refresh()
    }

    private fun HighlightStyle.previewColor(): Int {
        return fill.takeIf { it != 0 }
            ?: textColor.takeIf { it != 0 }
            ?: underline?.color?.takeIf { it != 0 }
            ?: strike?.color?.takeIf { it != 0 }
            ?: box?.color?.takeIf { it != 0 }
            ?: emphasis?.color?.takeIf { it != 0 }
            ?: shadow?.color?.takeIf { it != 0 }
            ?: DEFAULT_SWATCH_COLOR
    }

    private data class Channel(
        val labelRes: Int,
        val dialogId: Int,
        val withAlpha: Boolean,
        val isEnabled: (HighlightStyle) -> Boolean,
        val color: (HighlightStyle) -> Int,
        val toggle: (HighlightStyle, Boolean) -> HighlightStyle,
        val extra: ((HighlightStyle) -> String)? = null,
        val changeExtra: ((HighlightStyle) -> HighlightStyle)? = null
    )

    private val channels by lazy {
        listOf(
            Channel(
                R.string.highlight_bg_color,
                HL_FILL,
                true,
                { it.fill != 0 },
                { it.fill },
                { style, enabled ->
                    style.copy(
                        fill = if (enabled) {
                            style.fill.takeIf { it != 0 } ?: DEFAULT_FILL_COLOR
                        } else {
                            0
                        }
                    )
                },
                extra = { style -> fillShapeLabel(style.resolvedFillShape) },
                changeExtra = { style ->
                    style.copy(fillShape = nextFillShape(style.resolvedFillShape))
                }
            ),
            Channel(
                R.string.highlight_text_color,
                HL_TEXT,
                false,
                { it.textColor != 0 },
                { it.textColor },
                { style, enabled ->
                    style.copy(
                        textColor = if (enabled) {
                            style.textColor.takeIf { it != 0 } ?: DEFAULT_TEXT_COLOR
                        } else {
                            0
                        }
                    )
                }
            ),
            Channel(
                R.string.highlight_bold,
                NO_COLOR,
                false,
                { it.bold },
                { 0 },
                { style, enabled -> style.copy(bold = enabled) }
            ),
            Channel(
                R.string.highlight_italic,
                NO_COLOR,
                false,
                { it.italic },
                { 0 },
                { style, enabled -> style.copy(italic = enabled) }
            ),
            Channel(
                R.string.highlight_underline,
                HL_UNDERLINE,
                false,
                { it.underline != null },
                { it.underline?.color ?: 0 },
                { style, enabled ->
                    style.copy(underline = if (enabled) style.underline ?: Underline() else null)
                },
                extra = { style -> underlineLabel(style.underline?.kind) },
                changeExtra = { style ->
                    val underline = style.underline ?: Underline()
                    style.copy(underline = underline.copy(kind = nextKind(underline.kind)))
                }
            ),
            Channel(
                R.string.highlight_strike,
                HL_STRIKE,
                false,
                { it.strike != null },
                { it.strike?.color ?: 0 },
                { style, enabled ->
                    style.copy(strike = if (enabled) style.strike ?: Deco() else null)
                }
            ),
            Channel(
                R.string.highlight_box,
                HL_BOX,
                false,
                { it.box != null },
                { it.box?.color ?: 0 },
                { style, enabled ->
                    style.copy(box = if (enabled) style.box ?: Deco() else null)
                }
            ),
            Channel(
                R.string.highlight_emphasis,
                HL_EMPHASIS,
                false,
                { it.emphasis != null },
                { it.emphasis?.color ?: 0 },
                { style, enabled ->
                    style.copy(emphasis = if (enabled) style.emphasis ?: Deco() else null)
                }
            ),
            Channel(
                R.string.highlight_shadow,
                HL_SHADOW,
                true,
                { it.shadow != null },
                { it.shadow?.color ?: 0 },
                { style, enabled ->
                    style.copy(shadow = if (enabled) style.shadow ?: Shadow() else null)
                },
                extra = { style -> shadowLabel(style.shadow ?: Shadow()) }
            )
        )
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun HighlightStyleScreen() {
        val context = requireContext()
        val bgColor = Color(context.backgroundColor)
        val primaryText = Color(context.primaryTextColor)
        val secondaryText = Color(context.secondaryTextColor)
        val accent = Color(context.accentColor)
        val style = currentStyle()

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(bgColor)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 12.dp)
        ) {
            Text(
                text = stringResource(R.string.highlight_presets),
                color = secondaryText,
                fontSize = 13.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 6.dp)
            )

            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
            ) {
                HighlightStyles.presets.forEach { preset ->
                    Text(
                        text = "\u25CF",
                        color = Color(preset.previewColor()),
                        fontSize = 20.sp,
                        modifier = Modifier
                            .clickable { apply(preset) }
                            .padding(horizontal = 18.dp, vertical = 8.dp)
                    )
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 8.dp),
                color = secondaryText.copy(alpha = 0.2f)
            )

            // Channels list
            channels.forEach { channel ->
                val enabled = channel.isEnabled(style)
                val extra = channel.extra?.invoke(style)
                val pill = channel.labelRes == R.string.highlight_bg_color &&
                    style.resolvedFillShape == FillShape.PILL
                val tuneVisible = enabled && (channel.labelRes == R.string.highlight_underline || pill)
                val tuneText = if (tuneVisible) {
                    if (pill) {
                        getString(
                            R.string.highlight_pill_padding_value,
                            (style.resolvedPillPaddingScale * 100).roundToInt()
                        )
                    } else {
                        getString(R.string.highlight_underline_adjust)
                    }
                } else {
                    ""
                }
                val labelText = stringResource(channel.labelRes)

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .clickable {
                                val previousStyle = currentStyle()
                                val newStyle = channel.toggle(previousStyle, !enabled)
                                apply(newStyle)
                                if (channel.labelRes == R.string.highlight_shadow &&
                                    shouldOpenShadowEditor(previousStyle, newStyle)
                                ) {
                                    newStyle.shadow?.let {
                                        ShadowEditDialog.show(childFragmentManager, it)
                                    }
                                }
                            },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = enabled,
                            onCheckedChange = { checked ->
                                val previousStyle = currentStyle()
                                val newStyle = channel.toggle(previousStyle, checked)
                                apply(newStyle)
                                if (channel.labelRes == R.string.highlight_shadow &&
                                    shouldOpenShadowEditor(previousStyle, newStyle)
                                ) {
                                    newStyle.shadow?.let {
                                        ShadowEditDialog.show(childFragmentManager, it)
                                    }
                                }
                            },
                            colors = CheckboxDefaults.colors(checkedColor = accent)
                        )
                        Text(
                            text = labelText,
                            color = primaryText,
                            fontSize = 15.sp
                        )
                    }

                    if (extra != null && enabled) {
                        Box(
                            modifier = Modifier
                                .heightIn(min = 40.dp)
                                .widthIn(min = 48.dp)
                                .clickable {
                                    if (channel.labelRes == R.string.highlight_shadow) {
                                        currentStyle().shadow?.let {
                                            ShadowEditDialog.show(childFragmentManager, it)
                                        }
                                    } else {
                                        channel.changeExtra?.let { apply(it(currentStyle())) }
                                    }
                                }
                                .padding(horizontal = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = extra,
                                color = accent,
                                fontSize = 13.sp
                            )
                        }
                    }

                    if (tuneVisible) {
                        // tv_tune
                        Box(
                            modifier = Modifier
                                .heightIn(min = 40.dp)
                                .widthIn(min = 48.dp)
                                .then(
                                    if (pill) Modifier.testTag("highlightStylePillPadding") else Modifier
                                )
                                .clickable {
                                    if (channel.labelRes == R.string.highlight_underline) {
                                        currentStyle().underline?.let {
                                            UnderlineEditDialog.show(childFragmentManager, it)
                                        }
                                    } else if (channel.labelRes == R.string.highlight_bg_color) {
                                        NumberPickerDialog(requireContext())
                                            .setTitle(getString(R.string.highlight_pill_padding))
                                            .setMinValue(25)
                                            .setMaxValue(200)
                                            .setValue((currentStyle().resolvedPillPaddingScale * 100).roundToInt())
                                            .setCustomButton(R.string.btn_default_s) {
                                                apply(currentStyle().copy(pillPaddingScale = null))
                                            }
                                            .show { apply(currentStyle().copy(pillPaddingScale = it / 100f)) }
                                    }
                                }
                                .padding(horizontal = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = tuneText,
                                color = accent,
                                fontSize = 13.sp
                            )
                        }
                    }

                    if (channel.dialogId != NO_COLOR && enabled) {
                        val swatchColor = channel.color(style).takeIf { it != 0 } ?: DEFAULT_SWATCH_COLOR
                        Box(
                            modifier = Modifier
                                .padding(start = 8.dp)
                                .size(48.dp)
                                .semantics { contentDescription = labelText }
                                .clickable {
                                    styleHost?.pickHighlightColor(
                                        channel.dialogId,
                                        channel.color(currentStyle()),
                                        channel.withAlpha
                                    )
                                }
                                .padding(horizontal = 10.dp, vertical = 14.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 20.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color(swatchColor))
                            )
                        }
                    }
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 8.dp),
                color = secondaryText.copy(alpha = 0.2f)
            )

            // Font Row
            val fontPath = style.resolvedFontPath
            val fontValueText = if (fontPath.isEmpty()) {
                stringResource(R.string.default_font)
            } else {
                Uri.decode(fontPath)
                    .substringAfterLast('/')
                    .substringAfterLast('\\')
                    .ifBlank { fontPath }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable { showDialogFragment<FontSelectDialog>() }
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.text_font),
                    color = primaryText,
                    fontSize = 15.sp,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = fontValueText,
                    color = secondaryText,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 180.dp)
                )
            }

            // Font Size Row
            val fontSizeText = getString(R.string.text_size) + " · " +
                (style.resolvedFontSize?.roundToInt()?.toString() ?: getString(R.string.btn_default_s))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("highlightStyleFontSize")
                    .heightIn(min = 48.dp)
                    .clickable {
                        NumberPickerDialog(requireContext()).setTitle(getString(R.string.text_size))
                            .setMinValue(5).setMaxValue(100)
                            .setValue(currentStyle().resolvedFontSize?.roundToInt() ?: ReadBookConfig.textSize)
                            .setCustomButton(R.string.btn_default_s) { apply(currentStyle().copy(fontSize = null)) }
                            .show { apply(currentStyle().copy(fontSize = it.toFloat())) }
                    }
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Text(
                    text = fontSizeText,
                    color = primaryText,
                    fontSize = 15.sp
                )
            }

            // Letter Spacing Row
            val letterSpacingText = getString(R.string.text_letter_spacing) + " · " +
                (style.resolvedLetterSpacing?.let { "${(it * 100).roundToInt()}%" } ?: getString(R.string.btn_default_s))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("highlightStyleLetterSpacing")
                    .heightIn(min = 48.dp)
                    .clickable {
                        NumberPickerDialog(requireContext()).setTitle(getString(R.string.text_letter_spacing))
                            .setMinValue(0).setMaxValue(150)
                            .setDisplayedValues(Array(151) { "${it - 50}%" })
                            .setValue(((currentStyle().resolvedLetterSpacing ?: ReadBookConfig.letterSpacing) * 100).roundToInt() + 50)
                            .setCustomButton(R.string.btn_default_s) { apply(currentStyle().copy(letterSpacing = null)) }
                            .show { apply(currentStyle().copy(letterSpacing = (it - 50) / 100f)) }
                    }
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Text(
                    text = letterSpacingText,
                    color = primaryText,
                    fontSize = 15.sp
                )
            }
        }
    }

    fun refresh() {
        refreshVersion++
    }

    override val curFontPath: String
        get() = currentStyle().resolvedFontPath

    override val selectSystemTypefaceOnDefault = false

    override fun selectFont(path: String) {
        ChapterProvider.invalidateHighlightTypeface(path)
        apply(currentStyle().copy(fontPath = path))
    }

    private fun underlineLabel(kind: Kind?): String = when (kind) {
        Kind.WAVY -> getString(R.string.highlight_underline_wavy)
        Kind.DASHED -> getString(R.string.highlight_underline_dashed)
        Kind.DOTTED -> getString(R.string.highlight_underline_dotted)
        Kind.DOUBLE -> getString(R.string.highlight_underline_double)
        else -> getString(R.string.highlight_underline_solid)
    }

    private fun nextKind(kind: Kind): Kind {
        val kinds = Kind.entries
        return kinds[(kinds.indexOf(kind) + 1) % kinds.size]
    }

    private fun fillShapeLabel(shape: FillShape): String = when (shape) {
        FillShape.RECTANGLE -> getString(R.string.highlight_fill_rectangle)
        FillShape.ROUNDED -> getString(R.string.highlight_fill_rounded)
        FillShape.MARKER -> getString(R.string.highlight_fill_marker)
        FillShape.HALF -> getString(R.string.highlight_fill_half)
        FillShape.BASELINE -> getString(R.string.highlight_fill_baseline)
        FillShape.PILL -> getString(R.string.highlight_fill_pill)
    }

    private fun nextFillShape(shape: FillShape): FillShape {
        val shapes = FillShape.entries
        return shapes[(shapes.indexOf(shape) + 1) % shapes.size]
    }

    private fun shadowLabel(shadow: Shadow): String =
        getString(R.string.highlight_shadow_values, shadow.radius, shadow.dx, shadow.dy)

    override fun onShadowChanged(shadow: Shadow) {
        apply(currentStyle().copy(shadow = shadow))
    }

    override fun onUnderlineChanged(underline: Underline) {
        apply(currentStyle().copy(underline = underline))
    }

    companion object {
        const val HL_FILL = 8101
        const val HL_TEXT = 8102
        const val HL_UNDERLINE = 8103
        const val HL_STRIKE = 8104
        const val HL_BOX = 8105
        const val HL_EMPHASIS = 8106
        const val HL_SHADOW = 8107

        private const val NO_COLOR = -1
        private val DEFAULT_FILL_COLOR = 0x80FFF176.toInt()
        private val DEFAULT_TEXT_COLOR = 0xFFE53935.toInt()
        private val DEFAULT_SWATCH_COLOR = 0xFF888888.toInt()

        fun resolveStyleHost(parent: Any?, activity: Any?): StyleHost? {
            return (parent as? StyleHost) ?: (activity as? StyleHost)
        }

        fun applyChannelColor(
            style: HighlightStyle,
            dialogId: Int,
            color: Int
        ): HighlightStyle = when (dialogId) {
            HL_FILL -> style.copy(fill = color)
            HL_TEXT -> style.copy(textColor = color)
            HL_UNDERLINE -> style.copy(
                underline = (style.underline ?: Underline()).copy(color = color)
            )
            HL_STRIKE -> style.copy(strike = Deco(color))
            HL_BOX -> style.copy(box = Deco(color))
            HL_EMPHASIS -> style.copy(emphasis = Deco(color))
            HL_SHADOW -> style.copy(
                shadow = (style.shadow ?: Shadow()).copy(color = color)
            )
            else -> style
        }

        internal fun shouldOpenShadowEditor(
            previousStyle: HighlightStyle,
            newStyle: HighlightStyle
        ): Boolean = previousStyle.shadow == null && newStyle.shadow != null
    }
}
