package io.legado.app.ui.book.read.config

import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import io.legado.app.R
import io.legado.app.base.BasePrefDialogFragment
import io.legado.app.constant.PreferKey
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.book.read.readAloudControlWidth
import io.legado.app.ui.compose.prefs.PreferenceCategoryHeader
import io.legado.app.ui.compose.prefs.PreferenceItem
import io.legado.app.ui.compose.prefs.SeekBarPreferenceItem
import io.legado.app.ui.compose.prefs.SwitchPreferenceItem
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.setLayout

class ReadAloudControlsDialog : BasePrefDialogFragment() {
    override fun onStart() {
        super.onStart()
        dialog?.window?.run {
            setBackgroundDrawableResource(R.color.transparent)
            setLayout(.9f, .8f)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        (activity as? ReadBookActivity)?.let { it.bottomDialog++ }
        return LinearLayout(requireContext()).apply {
            id = R.id.tag1
            setBackgroundColor(context.backgroundColor)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (childFragmentManager.findFragmentByTag("controls") == null) {
            childFragmentManager.beginTransaction()
                .replace(view.id, ControlsPreferenceFragment(), "controls").commit()
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        (activity as? ReadBookActivity)?.let { it.bottomDialog-- }
    }

    class ControlsPreferenceFragment : Fragment() {

        override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?,
        ): View {
            return ComposeView(requireContext()).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                setContent {
                    LegadoTheme {
                        ControlsConfigScreen()
                    }
                }
            }
        }

        @Composable
        private fun ControlsConfigScreen() {
            val defaultWidth = remember {
                readAloudControlWidth(requireContext().defaultSharedPreferences)
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                item(key = "controlsCategory") {
                    PreferenceCategoryHeader(title = stringResource(R.string.read_aloud_controls))
                }
                item(key = PreferKey.readAloudControlsRealtime) {
                    SwitchPreferenceItem(
                        key = PreferKey.readAloudControlsRealtime,
                        default = true,
                        title = stringResource(R.string.read_aloud_controls_realtime),
                        onValueChange = {
                            (activity as? ReadBookActivity)?.showReadAloudControls()
                            true
                        }
                    )
                }
                item(key = PreferKey.readAloudControlsPause) {
                    SwitchPreferenceItem(
                        key = PreferKey.readAloudControlsPause,
                        default = true,
                        title = stringResource(R.string.read_aloud_controls_pause),
                    )
                }
                item(key = PreferKey.readAloudControlsPosition) {
                    SwitchPreferenceItem(
                        key = PreferKey.readAloudControlsPosition,
                        default = true,
                        title = stringResource(R.string.read_aloud_controls_position),
                    )
                }
                item(key = PreferKey.readAloudControlsAutoHide) {
                    SwitchPreferenceItem(
                        key = PreferKey.readAloudControlsAutoHide,
                        default = false,
                        title = stringResource(R.string.read_aloud_controls_auto_hide),
                    )
                }
                item(key = PreferKey.readAloudControlsDrag) {
                    SwitchPreferenceItem(
                        key = PreferKey.readAloudControlsDrag,
                        default = false,
                        title = stringResource(R.string.read_aloud_controls_drag),
                    )
                }
                item(key = PreferKey.readAloudControlsDock) {
                    SwitchPreferenceItem(
                        key = PreferKey.readAloudControlsDock,
                        default = false,
                        title = stringResource(R.string.read_aloud_controls_dock),
                    )
                }
                item(key = PreferKey.readAloudControlsWidth) {
                    SeekBarPreferenceItem(
                        key = PreferKey.readAloudControlsWidth,
                        default = defaultWidth,
                        min = 85,
                        max = 432,
                        step = 1,
                        title = stringResource(R.string.read_aloud_controls_size),
                        summary = stringResource(R.string.read_aloud_controls_size_summary),
                    )
                }
                item(key = PreferKey.readAloudControlsOpacity) {
                    SeekBarPreferenceItem(
                        key = PreferKey.readAloudControlsOpacity,
                        default = 90,
                        min = 0,
                        max = 100,
                        step = 5,
                        title = stringResource(R.string.read_aloud_controls_opacity),
                        summary = stringResource(R.string.read_aloud_controls_opacity_summary),
                    )
                }
                item(key = PreferKey.readAloudControlsThreshold) {
                    SeekBarPreferenceItem(
                        key = PreferKey.readAloudControlsThreshold,
                        default = 100,
                        min = 10,
                        max = 500,
                        step = 10,
                        title = stringResource(R.string.read_aloud_controls_threshold),
                        summary = stringResource(R.string.read_aloud_controls_threshold_summary),
                    )
                }
                item(key = "readAloudControlsReveal") {
                    PreferenceItem(
                        title = stringResource(R.string.read_aloud_controls_reveal),
                        summary = stringResource(R.string.read_aloud_controls_reveal_summary),
                        onClick = {
                            (activity as? ReadBookActivity)?.showReadAloudControls(resetPosition = false)
                        }
                    )
                }
                item(key = "readAloudControlsReset") {
                    PreferenceItem(
                        title = stringResource(R.string.read_aloud_controls_reset),
                        summary = stringResource(R.string.read_aloud_controls_reset_summary),
                        onClick = {
                            (activity as? ReadBookActivity)?.showReadAloudControls(resetPosition = true)
                        }
                    )
                }
            }
        }
    }
}
