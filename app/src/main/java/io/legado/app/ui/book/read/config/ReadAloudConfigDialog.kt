package io.legado.app.ui.book.read.config

import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import io.legado.app.R
import io.legado.app.base.BasePrefDialogFragment
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.help.IntentHelp
import io.legado.app.model.ReadAloud
import io.legado.app.service.BaseReadAloudService
import io.legado.app.ui.compose.prefs.ListPreferenceItem
import io.legado.app.ui.compose.prefs.PreferenceCategoryHeader
import io.legado.app.ui.compose.prefs.PreferenceItem
import io.legado.app.ui.compose.prefs.SwitchPreferenceItem
import io.legado.app.ui.compose.prefs.rememberPrefBoolean
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.postEvent
import io.legado.app.utils.setLayout
import io.legado.app.utils.showDialogFragment
import io.legado.app.lib.theme.backgroundColor

class ReadAloudConfigDialog : BasePrefDialogFragment() {
    private val readAloudPreferTag = "readAloudPreferTag"

    override fun onStart() {
        super.onStart()
        dialog?.window?.run {
            setBackgroundDrawableResource(R.color.transparent)
            setLayout(0.9f, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = LinearLayout(requireContext())
        view.setBackgroundColor(requireContext().backgroundColor)
        view.id = R.id.tag1
        container?.addView(view)
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        var preferenceFragment = childFragmentManager.findFragmentByTag(readAloudPreferTag)
        if (preferenceFragment == null) preferenceFragment = ReadAloudPreferenceFragment()
        childFragmentManager.beginTransaction()
            .replace(view.id, preferenceFragment, readAloudPreferTag)
            .commit()
    }

    class ReadAloudPreferenceFragment : Fragment(),
        SpeakEngineDialog.CallBack,
        SharedPreferences.OnSharedPreferenceChangeListener {

        private var refreshTick by mutableIntStateOf(0)

        private val speakEngineSummary: String
            get() = ReadAloud.getEngineName(requireContext())

        override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?,
        ): View {
            return ComposeView(requireContext()).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                setContent {
                    LegadoTheme {
                        ReadAloudConfigScreen(refreshTick = refreshTick)
                    }
                }
            }
        }

        override fun onResume() {
            super.onResume()
            requireContext().defaultSharedPreferences.registerOnSharedPreferenceChangeListener(this)
        }

        override fun onPause() {
            requireContext().defaultSharedPreferences.unregisterOnSharedPreferenceChangeListener(this)
            super.onPause()
        }

        private fun onAloudItemClick(key: String) {
            when (key) {
                PreferKey.ttsEngine -> showDialogFragment(SpeakEngineDialog())
                "sysTtsConfig" -> IntentHelp.openTTSSetting()
                "readAloudControls" -> showDialogFragment(ReadAloudControlsDialog())
            }
        }

        override fun onSharedPreferenceChanged(
            sharedPreferences: SharedPreferences?,
            key: String?
        ) {
            refreshTick++
            when (key) {
                PreferKey.readAloudByPage, PreferKey.streamReadAloudAudio -> {
                    if (BaseReadAloudService.isRun) {
                        postEvent(EventBus.MEDIA_BUTTON, false)
                    }
                }
            }
        }

        override fun upSpeakEngineSummary() {
            refreshTick++
        }

        @Composable
        private fun ReadAloudConfigScreen(refreshTick: Int) {
            val ignoreAudioFocus by rememberPrefBoolean(PreferKey.ignoreAudioFocus, false)
            val engineName = remember(refreshTick) { speakEngineSummary }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                item(key = "aloudConfigCategory") {
                    PreferenceCategoryHeader(title = stringResource(R.string.aloud_config))
                }
                item(key = PreferKey.ignoreAudioFocus) {
                    SwitchPreferenceItem(
                        key = PreferKey.ignoreAudioFocus,
                        default = false,
                        title = stringResource(R.string.ignore_audio_focus_title),
                        summary = stringResource(R.string.ignore_audio_focus_summary),
                    )
                }
                item(key = PreferKey.pauseReadAloudWhilePhoneCalls) {
                    SwitchPreferenceItem(
                        key = PreferKey.pauseReadAloudWhilePhoneCalls,
                        default = false,
                        title = stringResource(R.string.pause_read_aloud_while_phone_calls_title),
                        summary = stringResource(R.string.pause_read_aloud_while_phone_calls_summary),
                        enabled = ignoreAudioFocus,
                    )
                }
                item(key = PreferKey.readAloudWakeLock) {
                    SwitchPreferenceItem(
                        key = PreferKey.readAloudWakeLock,
                        default = false,
                        title = stringResource(R.string.read_aloud_wake_lock),
                        summary = stringResource(R.string.read_aloud_wake_lock_summary),
                    )
                }
                item(key = "mediaButtonPerNext") {
                    SwitchPreferenceItem(
                        key = "mediaButtonPerNext",
                        default = false,
                        title = stringResource(R.string.pref_media_button_per_next),
                        summary = stringResource(R.string.pref_media_button_per_next_summary),
                    )
                }
                item(key = PreferKey.readAloudByPage) {
                    SwitchPreferenceItem(
                        key = PreferKey.readAloudByPage,
                        default = false,
                        title = stringResource(R.string.read_aloud_by_page),
                        summary = stringResource(R.string.read_aloud_by_page_summary),
                    )
                }
                item(key = PreferKey.readAloudFollowManualPage) {
                    SwitchPreferenceItem(
                        key = PreferKey.readAloudFollowManualPage,
                        default = false,
                        title = stringResource(R.string.read_aloud_follow_manual_page),
                        summary = stringResource(R.string.read_aloud_follow_manual_page_summary),
                    )
                }
                item(key = PreferKey.streamReadAloudAudio) {
                    SwitchPreferenceItem(
                        key = PreferKey.streamReadAloudAudio,
                        default = false,
                        title = stringResource(R.string.stream_read_aloud_audio),
                        summary = stringResource(R.string.stream_read_aloud_audio_summary),
                    )
                }
                item(key = PreferKey.readAloudStart) {
                    ListPreferenceItem(
                        key = PreferKey.readAloudStart,
                        default = "sentence",
                        title = stringResource(R.string.read_aloud_start),
                        entries = stringArrayResource(R.array.read_aloud_start_entries),
                        entryValues = stringArrayResource(R.array.read_aloud_start_values),
                    )
                }
                item(key = "readAloudControls") {
                    PreferenceItem(
                        title = stringResource(R.string.read_aloud_controls),
                        onClick = { onAloudItemClick("readAloudControls") },
                    )
                }
                item(key = PreferKey.ttsEngine) {
                    PreferenceItem(
                        title = stringResource(R.string.speak_engine),
                        summary = engineName,
                        onClick = { onAloudItemClick(PreferKey.ttsEngine) },
                    )
                }
                item(key = "sysTtsConfig") {
                    PreferenceItem(
                        title = stringResource(R.string.sys_tts_config),
                        summary = stringResource(R.string.sys_tts_config_summary),
                        onClick = { onAloudItemClick("sysTtsConfig") },
                    )
                }
            }
        }
    }
}
