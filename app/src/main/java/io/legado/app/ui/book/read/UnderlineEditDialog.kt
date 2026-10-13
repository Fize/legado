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
import io.legado.app.help.HighlightStyle.Kind
import io.legado.app.help.HighlightStyle.Underline
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.utils.setLayout
import java.util.Locale
import kotlin.math.roundToInt

class UnderlineEditDialog : BaseDialogFragment() {

    interface Callback {
        fun onUnderlineChanged(underline: Underline)
    }

    private val initialUnderline: Underline
        get() = Underline(
            kind = arguments?.getString(ARG_KIND)?.let { runCatching { Kind.valueOf(it) }.getOrNull() }
                ?: Underline().kind,
            color = arguments?.getInt(ARG_COLOR) ?: Underline().color,
            width = arguments?.getFloat(ARG_WIDTH) ?: Underline().width,
            distance = arguments?.getFloat(ARG_DISTANCE) ?: Underline().distance
        ).normalized()

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
                    UnderlineEditScreen(initialUnderline)
                }
            }
        }
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) = Unit

    @Composable
    private fun UnderlineEditScreen(underline: Underline) {
        val context = requireContext()
        val bgColor = Color(context.backgroundColor)
        val textColor = Color(context.primaryTextColor)
        val accent = Color(context.accentColor)

        var widthProgress by remember(underline) {
            mutableIntStateOf(progressOf(underline.width, Underline.MIN_WIDTH, Underline.MAX_WIDTH))
        }
        var distanceProgress by remember(underline) {
            mutableIntStateOf(
                progressOf(underline.distance, Underline.MIN_DISTANCE, Underline.MAX_DISTANCE)
            )
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
                    text = stringResource(R.string.highlight_underline),
                    color = accent,
                    fontSize = 18.sp,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                // Width control: max = 20
                HalfDpSeekBarRow(
                    titleRes = R.string.highlight_underline_width,
                    progress = widthProgress,
                    max = 20,
                    displayValue = formatHalf(widthProgress),
                    textColor = textColor,
                    accent = accent,
                    onProgressChange = { widthProgress = it }
                )

                // Distance control: max = 60
                HalfDpSeekBarRow(
                    titleRes = R.string.highlight_underline_distance,
                    progress = distanceProgress,
                    max = 60,
                    displayValue = formatHalf(distanceProgress),
                    textColor = textColor,
                    accent = accent,
                    onProgressChange = { distanceProgress = it }
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
                                (parentFragment as? Callback)?.onUnderlineChanged(
                                    underline.copy(
                                        width = widthProgress / 2f,
                                        distance = distanceProgress / 2f
                                    ).normalized()
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

    private fun formatHalf(progress: Int): String {
        return String.format(Locale.getDefault(), "%.1f", progress / 2f)
    }

    companion object {
        private const val ARG_KIND = "kind"
        private const val ARG_COLOR = "color"
        private const val ARG_WIDTH = "width"
        private const val ARG_DISTANCE = "distance"

        fun show(fragmentManager: androidx.fragment.app.FragmentManager, underline: Underline) {
            UnderlineEditDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_KIND, underline.kind.name)
                    putInt(ARG_COLOR, underline.color)
                    putFloat(ARG_WIDTH, underline.width)
                    putFloat(ARG_DISTANCE, underline.distance)
                }
            }.show(fragmentManager, UnderlineEditDialog::class.simpleName)
        }
    }
}
