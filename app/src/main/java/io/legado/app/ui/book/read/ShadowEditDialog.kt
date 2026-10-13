package io.legado.app.ui.book.read

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.help.HighlightStyle.Shadow
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.utils.setLayout
import java.util.Locale
import kotlin.math.roundToInt

class ShadowEditDialog : BaseDialogFragment() {

    interface Callback {
        fun onShadowChanged(shadow: Shadow)
    }

    private val initialShadow: Shadow
        get() = Shadow(
            radius = arguments?.getFloat(ARG_RADIUS) ?: Shadow().radius,
            dx = arguments?.getFloat(ARG_DX) ?: Shadow().dx,
            dy = arguments?.getFloat(ARG_DY) ?: Shadow().dy,
            color = arguments?.getInt(ARG_COLOR) ?: Shadow().color
        )

    override fun onStart() {
        super.onStart()
        setLayout(0.9f, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LegadoTheme {
                    ShadowEditScreen(initialShadow)
                }
            }
        }
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) = Unit

    @Composable
    private fun ShadowEditScreen(shadow: Shadow) {
        val context = requireContext()
        val bgColor = Color(context.backgroundColor)
        val textColor = Color(context.primaryTextColor)
        val accent = Color(context.accentColor)

        var radiusProgress by remember(shadow) {
            mutableIntStateOf(progressOf(shadow.radius, 0f, 10f))
        }
        var dxProgress by remember(shadow) {
            mutableIntStateOf(progressOf(shadow.dx, -10f, 10f))
        }
        var dyProgress by remember(shadow) {
            mutableIntStateOf(progressOf(shadow.dy, -10f, 10f))
        }

        Surface(
            modifier = Modifier
                .widthIn(max = 520.dp)
                .fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            color = bgColor
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.highlight_shadow),
                    color = accent,
                    fontSize = 18.sp,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                HalfDpSeekBarRow(
                    titleRes = R.string.highlight_shadow_radius,
                    progress = radiusProgress,
                    max = 20,
                    displayValue = formatHalf(radiusProgress),
                    textColor = textColor,
                    accent = accent,
                    onProgressChange = { radiusProgress = it }
                )

                HalfDpSeekBarRow(
                    titleRes = R.string.highlight_shadow_offset_x,
                    progress = dxProgress,
                    max = 40,
                    displayValue = formatHalf(dxProgress - OFFSET_PROGRESS, signed = true),
                    textColor = textColor,
                    accent = accent,
                    onProgressChange = { dxProgress = it }
                )

                HalfDpSeekBarRow(
                    titleRes = R.string.highlight_shadow_offset_y,
                    progress = dyProgress,
                    max = 40,
                    displayValue = formatHalf(dyProgress - OFFSET_PROGRESS, signed = true),
                    textColor = textColor,
                    accent = accent,
                    onProgressChange = { dyProgress = it }
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .widthIn(min = 48.dp)
                            .clickable { dismiss() }
                            .padding(12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.cancel),
                            color = accent
                        )
                    }
                    Box(
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .widthIn(min = 48.dp)
                            .clickable {
                                (parentFragment as? Callback)?.onShadowChanged(
                                    shadow.copy(
                                        radius = radiusProgress / 2f,
                                        dx = dxProgress / 2f - 10f,
                                        dy = dyProgress / 2f - 10f
                                    )
                                )
                                dismiss()
                            }
                            .padding(12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.ok),
                            color = accent
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun HalfDpSeekBarRow(
        titleRes: Int,
        progress: Int,
        max: Int,
        displayValue: String,
        textColor: Color,
        accent: Color,
        onProgressChange: (Int) -> Unit
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(titleRes),
                    color = textColor,
                    fontSize = 14.sp
                )
                Text(
                    text = displayValue,
                    color = textColor,
                    fontSize = 14.sp
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = {
                        if (progress > 0) onProgressChange(progress - 1)
                    },
                    modifier = Modifier.size(30.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_reduce),
                        contentDescription = stringResource(R.string.reduce),
                        tint = textColor
                    )
                }
                Slider(
                    value = progress.toFloat(),
                    onValueChange = { onProgressChange(it.roundToInt().coerceIn(0, max)) },
                    valueRange = 0f..max.toFloat(),
                    colors = SliderDefaults.colors(
                        thumbColor = accent,
                        activeTrackColor = accent
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 4.dp)
                )
                IconButton(
                    onClick = {
                        if (progress < max) onProgressChange(progress + 1)
                    },
                    modifier = Modifier.size(30.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_add),
                        contentDescription = stringResource(R.string.plus),
                        tint = textColor
                    )
                }
            }
        }
    }

    private fun progressOf(value: Float, min: Float, max: Float): Int {
        val safeValue = value.takeIf { it.isFinite() } ?: min
        return ((safeValue.coerceIn(min, max) - min) * 2).roundToInt()
    }

    private fun formatHalf(progress: Int, signed: Boolean = false): String {
        val format = if (signed) "%+.1f" else "%.1f"
        return String.format(Locale.getDefault(), format, progress / 2f)
    }

    companion object {
        private const val ARG_RADIUS = "radius"
        private const val ARG_DX = "dx"
        private const val ARG_DY = "dy"
        private const val ARG_COLOR = "color"
        private const val OFFSET_PROGRESS = 20

        fun show(fragmentManager: androidx.fragment.app.FragmentManager, shadow: Shadow) {
            ShadowEditDialog().apply {
                arguments = Bundle().apply {
                    putFloat(ARG_RADIUS, shadow.radius)
                    putFloat(ARG_DX, shadow.dx)
                    putFloat(ARG_DY, shadow.dy)
                    putInt(ARG_COLOR, shadow.color)
                }
            }.show(fragmentManager, ShadowEditDialog::class.simpleName)
        }
    }
}
