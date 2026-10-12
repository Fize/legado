package io.legado.app.ui.book.manga.config

import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.setLayout
import kotlin.math.roundToInt

class MangaColorFilterDialog : BaseDialogFragment() {
    private val mConfig =
        GSON.fromJsonObject<MangaColorFilterConfig>(AppConfig.mangaColorFilter).getOrNull()
            ?: MangaColorFilterConfig()
    private val callback get() = activity as? Callback

    override fun onStart() {
        super.onStart()
        dialog?.window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
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
                    var l by remember { mutableIntStateOf(mConfig.l) }
                    var r by remember { mutableIntStateOf(mConfig.r) }
                    var g by remember { mutableIntStateOf(mConfig.g) }
                    var b by remember { mutableIntStateOf(mConfig.b) }
                    var a by remember { mutableIntStateOf(mConfig.a) }

                    Surface(color = MaterialTheme.colorScheme.surface) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                                .padding(16.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.manga_color_filter),
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 18.sp,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                            MangaSliderRow(
                                title = stringResource(R.string.brightness),
                                value = l,
                                onValueChange = {
                                    l = it
                                    mConfig.l = it
                                    callback?.updateColorFilter(mConfig)
                                }
                            )
                            MangaSliderRow(
                                title = "R",
                                value = r,
                                onValueChange = {
                                    r = it
                                    mConfig.r = it
                                    callback?.updateColorFilter(mConfig)
                                }
                            )
                            MangaSliderRow(
                                title = "G",
                                value = g,
                                onValueChange = {
                                    g = it
                                    mConfig.g = it
                                    callback?.updateColorFilter(mConfig)
                                }
                            )
                            MangaSliderRow(
                                title = "B",
                                value = b,
                                onValueChange = {
                                    b = it
                                    mConfig.b = it
                                    callback?.updateColorFilter(mConfig)
                                }
                            )
                            MangaSliderRow(
                                title = "A",
                                value = a,
                                onValueChange = {
                                    a = it
                                    mConfig.a = it
                                    callback?.updateColorFilter(mConfig)
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) = Unit

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        AppConfig.mangaColorFilter = mConfig.toJson()
    }

    interface Callback {
        fun updateColorFilter(config: MangaColorFilterConfig)
    }
}

@Composable
internal fun MangaSliderRow(
    title: String,
    value: Int,
    max: Int = 255,
    onValueChange: (Int) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = value.toString(),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.End,
                modifier = Modifier.width(48.dp)
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { onValueChange((value - 1).coerceAtLeast(0)) }) {
                Text("-", style = MaterialTheme.typography.titleMedium)
            }
            Slider(
                value = value.toFloat(),
                onValueChange = { onValueChange(it.roundToInt().coerceIn(0, max)) },
                valueRange = 0f..max.toFloat(),
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { onValueChange((value + 1).coerceAtMost(max)) }) {
                Text("+", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}