package io.legado.app.ui.video.config

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.lib.theme.accentColor
import io.legado.app.model.VideoPlay
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.number.NumberPickerDialog
import io.legado.app.utils.setLayout

class SettingsDialog(private val context: Context, private val callBack: CallBack? = null) :
    BaseDialogFragment() {

    override fun onStart() {
        super.onStart()
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
                    val localContext = LocalContext.current
                    val accent = Color(localContext.accentColor)
                    var autoPlay by remember { mutableStateOf(VideoPlay.autoPlay) }
                    var defaultFloatWindow by remember { mutableStateOf(VideoPlay.defaultFloatWindow) }
                    var startFull by remember { mutableStateOf(VideoPlay.startFull) }
                    var fullBottomProgress by remember { mutableStateOf(VideoPlay.fullBottomProgressBar) }
                    var pressSpeed by remember { mutableIntStateOf(VideoPlay.longPressSpeed) }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(colorResource(R.color.background))
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.config_settings),
                            color = accent,
                            fontSize = 18.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 16.dp)
                        )

                        VideoSettingCheckRow(
                            title = stringResource(R.string.auto_play),
                            checked = autoPlay,
                            onCheckedChange = {
                                autoPlay = it
                                VideoPlay.autoPlay = it
                            }
                        )

                        VideoSettingCheckRow(
                            title = stringResource(R.string.default_float_window),
                            checked = defaultFloatWindow,
                            onCheckedChange = {
                                defaultFloatWindow = it
                                VideoPlay.defaultFloatWindow = it
                            }
                        )

                        if (autoPlay) {
                            VideoSettingCheckRow(
                                title = stringResource(R.string.start_full),
                                checked = startFull,
                                onCheckedChange = {
                                    startFull = it
                                    VideoPlay.startFull = it
                                }
                            )
                        }

                        VideoSettingCheckRow(
                            title = stringResource(R.string.full_bottom_progress),
                            checked = fullBottomProgress,
                            onCheckedChange = {
                                fullBottomProgress = it
                                VideoPlay.fullBottomProgressBar = it
                            }
                        )

                        Text(
                            text = (pressSpeed / 10.0f).toPressSpeedStr(),
                            color = colorResource(R.color.primaryText),
                            fontSize = 16.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    NumberPickerDialog(requireContext(), true)
                                        .setTitle(getString(R.string.press_speed))
                                        .setMaxValue(60)
                                        .setMinValue(5)
                                        .setValue(VideoPlay.longPressSpeed)
                                        .setCustomButton(R.string.btn_default_s) {
                                            VideoPlay.longPressSpeed = 30
                                            pressSpeed = 30
                                        }
                                        .show {
                                            VideoPlay.longPressSpeed = it
                                            pressSpeed = it
                                        }
                                }
                                .padding(vertical = 12.dp)
                        )
                    }
                }
            }
        }
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) = Unit

    private fun Float.toPressSpeedStr(): String {
        return context.getString(R.string.press_speed_summary, this)
    }

    interface CallBack
}

@Composable
private fun VideoSettingCheckRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            color = colorResource(R.color.primaryText),
            fontSize = 16.sp,
            modifier = Modifier.weight(1f)
        )
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}
