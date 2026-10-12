package io.legado.app.ui.widget.dialog

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.accentColor
import io.legado.app.service.MAX_CHAPTER_STOP_COUNT
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.putPrefInt
import io.legado.app.utils.setLayout
import io.legado.app.utils.toastOnUi

class SleepTimerDialog : BaseDialogFragment() {

    interface CallBack {
        fun onSleepTimerMinute(minute: Int)
        fun onSleepTimerChapter(count: Int)
    }

    private enum class SelectedOption {
        NONE,
        TIME_P1, TIME_P2, TIME_P3, TIME_P4, TIME_CUSTOM,
        CHAPTER_P1, CHAPTER_P2, CHAPTER_P3, CHAPTER_P4, CHAPTER_CUSTOM
    }

    private val callBack: CallBack?
        get() = (parentFragment as? CallBack) ?: (activity as? CallBack)
    private var minute by mutableIntStateOf(0)
    private var chapter by mutableIntStateOf(0)
    private var useEpisodes by mutableStateOf(false)
    private var customChapterMode by mutableStateOf(false)
    private var customInputVisible by mutableStateOf(false)
    private var customInputText by mutableStateOf("")
    private var selectedOption by mutableStateOf(SelectedOption.NONE)
    private var focusCustomTrigger by mutableIntStateOf(0)

    override fun onStart() {
        super.onStart()
        setLayout(0.9f, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        minute = arguments?.getInt(ARG_MINUTE)?.coerceIn(0, MAX_MINUTES) ?: 0
        chapter = arguments?.getInt(ARG_CHAPTER)?.coerceIn(0, MAX_CHAPTER_STOP_COUNT) ?: 0
        useEpisodes = arguments?.getBoolean(ARG_EPISODES) == true
        bindCurrentState()

        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LegadoTheme {
                    val context = LocalContext.current
                    val accent = Color(context.accentColor)
                    val focusRequester = remember { FocusRequester() }
                    val keyboardController = LocalSoftwareKeyboardController.current

                    LaunchedEffect(customInputVisible, focusCustomTrigger) {
                        if (customInputVisible && focusCustomTrigger > 0) {
                            runCatching { focusRequester.requestFocus() }
                            keyboardController?.show()
                        }
                    }

                    val statusText = when {
                        chapter > 0 -> stringResource(
                            if (useEpisodes) R.string.audio_stop_chapters
                            else R.string.sleep_timer_chapters,
                            chapter,
                        )
                        minute > 0 -> stringResource(R.string.sleep_timer_status_time, minute)
                        else -> stringResource(R.string.sleep_timer_status_none)
                    }
                    val hasActiveTimer = chapter > 0 || minute > 0

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(colorResource(R.color.background_card))
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.sleep_timer_title),
                            color = colorResource(R.color.primaryText),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp)
                                .background(
                                    colorResource(R.color.btn_bg_press),
                                    RoundedCornerShape(4.dp)
                                ),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                                    .padding(horizontal = 12.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                Text(
                                    text = statusText,
                                    color = colorResource(R.color.primaryText)
                                )
                            }
                            IconButton(
                                onClick = { applyMinute(0, save = false) },
                                enabled = hasActiveTimer,
                                modifier = Modifier
                                    .size(48.dp)
                                    .alpha(if (hasActiveTimer) 1f else 0.38f)
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_baseline_close),
                                    contentDescription = stringResource(R.string.sleep_timer_off),
                                    tint = accent
                                )
                            }
                        }

                        Text(
                            text = stringResource(R.string.sleep_timer_by_time),
                            color = colorResource(R.color.secondaryText),
                            fontSize = 14.sp,
                            modifier = Modifier
                                .padding(top = 12.dp)
                                .padding(vertical = 4.dp)
                        )

                        val timeOptionValues = listOf(
                            SelectedOption.TIME_P1 to TIME_PRESETS[0],
                            SelectedOption.TIME_P2 to TIME_PRESETS[1],
                            SelectedOption.TIME_P3 to TIME_PRESETS[2],
                            SelectedOption.TIME_P4 to TIME_PRESETS[3],
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            timeOptionValues.forEach { (opt, value) ->
                                SleepTimerOptionButton(
                                    text = stringResource(R.string.sleep_timer_minute_short, value),
                                    selected = selectedOption == opt,
                                    accent = accent,
                                    onClick = { applyMinute(value, save = false) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }

                        SleepTimerOptionButton(
                            text = stringResource(R.string.sleep_timer_custom),
                            selected = selectedOption == SelectedOption.TIME_CUSTOM,
                            accent = accent,
                            onClick = { showCustomInput(chapterMode = false) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp)
                        )

                        Text(
                            text = stringResource(
                                if (useEpisodes) R.string.sleep_timer_by_episode
                                else R.string.sleep_timer_by_chapter
                            ),
                            color = colorResource(R.color.secondaryText),
                            fontSize = 14.sp,
                            modifier = Modifier
                                .padding(top = 12.dp)
                                .padding(vertical = 4.dp)
                        )

                        val chapterOptionValues = listOf(
                            SelectedOption.CHAPTER_P1 to CHAPTER_PRESETS[0],
                            SelectedOption.CHAPTER_P2 to CHAPTER_PRESETS[1],
                            SelectedOption.CHAPTER_P3 to CHAPTER_PRESETS[2],
                            SelectedOption.CHAPTER_P4 to CHAPTER_PRESETS[3],
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            chapterOptionValues.forEach { (opt, value) ->
                                SleepTimerOptionButton(
                                    text = stringResource(
                                        if (useEpisodes) R.string.sleep_timer_episode_short
                                        else R.string.sleep_timer_chapter_short,
                                        value,
                                    ),
                                    selected = selectedOption == opt,
                                    accent = accent,
                                    onClick = { applyChapter(value, save = false) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }

                        SleepTimerOptionButton(
                            text = stringResource(R.string.sleep_timer_custom),
                            selected = selectedOption == SelectedOption.CHAPTER_CUSTOM,
                            accent = accent,
                            onClick = { showCustomInput(chapterMode = true) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp)
                        )

                        if (customInputVisible) {
                            val hintRes = when {
                                customChapterMode && useEpisodes -> R.string.sleep_timer_episode_hint
                                customChapterMode -> R.string.sleep_timer_chapter_hint
                                else -> R.string.sleep_timer_minute_hint
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = customInputText,
                                    onValueChange = { next ->
                                        if (next.length <= 3 && next.all(Char::isDigit)) {
                                            customInputText = next
                                        }
                                    },
                                    placeholder = { Text(stringResource(hintRes)) },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Number,
                                        imeAction = ImeAction.Done
                                    ),
                                    keyboardActions = KeyboardActions(
                                        onDone = { applyCustomInput() }
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .focusRequester(focusRequester)
                                )
                                TextButton(
                                    onClick = { applyCustomInput() },
                                    modifier = Modifier
                                        .height(48.dp)
                                        .widthIn(min = 72.dp)
                                ) {
                                    Text(
                                        text = stringResource(R.string.confirm),
                                        color = accent
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun SleepTimerOptionButton(
        text: String,
        selected: Boolean,
        accent: Color,
        onClick: () -> Unit,
        modifier: Modifier = Modifier
    ) {
        Box(
            modifier = modifier
                .heightIn(min = 48.dp)
                .background(
                    if (selected) accent.copy(alpha = 0.15f) else colorResource(R.color.btn_bg_press),
                    RoundedCornerShape(4.dp)
                )
                .then(
                    if (selected) Modifier.border(1.dp, accent, RoundedCornerShape(4.dp))
                    else Modifier
                )
                .clickable(onClick = onClick)
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                color = if (selected) accent else colorResource(R.color.primaryText),
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )
        }
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) = Unit

    private fun bindCurrentState() {
        selectedOption = when {
            chapter > 0 -> when (CHAPTER_PRESETS.indexOf(chapter)) {
                0 -> SelectedOption.CHAPTER_P1
                1 -> SelectedOption.CHAPTER_P2
                2 -> SelectedOption.CHAPTER_P3
                3 -> SelectedOption.CHAPTER_P4
                else -> SelectedOption.CHAPTER_CUSTOM
            }

            minute > 0 -> when (TIME_PRESETS.indexOf(minute)) {
                0 -> SelectedOption.TIME_P1
                1 -> SelectedOption.TIME_P2
                2 -> SelectedOption.TIME_P3
                3 -> SelectedOption.TIME_P4
                else -> SelectedOption.TIME_CUSTOM
            }

            else -> SelectedOption.NONE
        }
    }

    private fun showCustomInput(chapterMode: Boolean) {
        if (customInputVisible && customChapterMode == chapterMode) {
            focusCustomTrigger++
            return
        }
        customChapterMode = chapterMode
        selectedOption = if (chapterMode) SelectedOption.CHAPTER_CUSTOM else SelectedOption.TIME_CUSTOM
        customInputVisible = true
        val prefKey = if (chapterMode) PreferKey.lastSleepChapter else PreferKey.lastSleepTimer
        val current = if (chapterMode) chapter else minute
        val value = requireContext().getPrefInt(prefKey, 0).takeIf { it > 0 }
            ?: current.takeIf { it > 0 }
        customInputText = value?.toString().orEmpty()
        focusCustomTrigger++
    }

    private fun applyCustomInput() {
        val value = customInputText.toIntOrNull()
        val max = if (customChapterMode) MAX_CHAPTER_STOP_COUNT else MAX_MINUTES
        if (value == null || value !in 1..max) {
            requireContext().toastOnUi(getString(R.string.sleep_timer_range_hint, max))
            return
        }
        if (customChapterMode) {
            applyChapter(value, save = true)
        } else {
            applyMinute(value, save = true)
        }
    }

    private fun applyMinute(minute: Int, save: Boolean) {
        if (minute > 0) AppConfig.sleepTimerPreferChapter = false
        if (save) requireContext().putPrefInt(PreferKey.lastSleepTimer, minute)
        callBack?.onSleepTimerMinute(minute)
        dismissAllowingStateLoss()
    }

    private fun applyChapter(chapter: Int, save: Boolean) {
        if (chapter > 0) AppConfig.sleepTimerPreferChapter = true
        if (save) requireContext().putPrefInt(PreferKey.lastSleepChapter, chapter)
        callBack?.onSleepTimerChapter(chapter)
        dismissAllowingStateLoss()
    }

    companion object {
        private const val ARG_MINUTE = "minute"
        private const val ARG_CHAPTER = "chapter"
        private const val ARG_EPISODES = "episodes"
        private const val MAX_MINUTES = 180
        private val TIME_PRESETS = intArrayOf(15, 30, 45, 60)
        private val CHAPTER_PRESETS = intArrayOf(1, 2, 3, 5)

        fun newInstance(
            minute: Int,
            chapter: Int,
            useEpisodes: Boolean = false,
        ) = SleepTimerDialog().apply {
            arguments = Bundle().apply {
                putInt(ARG_MINUTE, minute)
                putInt(ARG_CHAPTER, chapter)
                putBoolean(ARG_EPISODES, useEpisodes)
            }
        }
    }
}
