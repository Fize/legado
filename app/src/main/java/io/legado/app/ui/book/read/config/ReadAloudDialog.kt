package io.legado.app.ui.book.read.config

import android.content.DialogInterface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.constant.EventBus
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.getPrimaryTextColor
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.service.BaseReadAloudService
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.dialog.SleepTimerDialog
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.observeEvent
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import kotlin.math.roundToInt

class ReadAloudDialog : BaseDialogFragment(),
    SpeakEngineDialog.CallBack,
    SleepTimerDialog.CallBack {
    private val callBack: CallBack? get() = activity as? CallBack

    private var isPausedState by mutableStateOf(BaseReadAloudService.pause)
    private var playPauseDescription by mutableStateOf("")
    private var engineNameState by mutableStateOf("")
    private var timerTextState by mutableStateOf("")
    private var seekTimerState by mutableIntStateOf(0)
    private var ttsFollowSysState by mutableStateOf(true)
    private var ttsSpeechRateState by mutableIntStateOf(AppConfig.ttsSpeechRate)

    override fun onStart() {
        super.onStart()
        dialog?.window?.run {
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setBackgroundDrawableResource(R.color.background)
            decorView.setPadding(0, 0, 0, 0)
            val attr = attributes
            attr.dimAmount = 0.0f
            attr.gravity = Gravity.BOTTOM
            attributes = attr
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        (activity as ReadBookActivity).bottomDialog--
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
                    ReadAloudScreen()
                }
            }
        }
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        val bottomDialog = (activity as ReadBookActivity).bottomDialog++
        if (bottomDialog > 0) {
            dismiss()
            return
        }
        initData()
    }

    private fun initData() {
        upPlayState()
        upEngineName()
        upStopText()
        ttsFollowSysState = requireContext().getPrefBoolean("ttsFollowSys", true)
        ttsSpeechRateState = AppConfig.ttsSpeechRate
        upSeekTimer()
    }

    private fun onPrevChapter() {
        if (ReadAloud.followReadAloudPosition) {
            ReadAloud.prevChapter(requireContext())
        } else {
            ReadBook.moveToPrevChapter(upContent = true, toLast = false)
        }
    }

    private fun onNextChapter() {
        if (ReadAloud.followReadAloudPosition) {
            ReadAloud.nextChapter(requireContext())
        } else {
            ReadBook.moveToNextChapter(upContent = true)
        }
    }

    private fun onStopClick() {
        ReadAloud.stop(requireContext())
        dismissAllowingStateLoss()
    }

    private fun upPlayState() {
        isPausedState = BaseReadAloudService.pause
        playPauseDescription = if (!BaseReadAloudService.pause) {
            getString(R.string.pause)
        } else {
            getString(R.string.audio_play)
        }
    }

    private fun upSeekTimer() {
        seekTimerState = when {
            BaseReadAloudService.timeMinute > 0 -> BaseReadAloudService.timeMinute
            BaseReadAloudService.chapterToStop > 0 -> 0
            else -> AppConfig.ttsTimer
        }
    }

    private fun upStopText() {
        timerTextState = when {
            BaseReadAloudService.chapterToStop > 0 -> getString(
                R.string.sleep_timer_chapters,
                BaseReadAloudService.chapterToStop,
            )

            BaseReadAloudService.timeMinute > 0 -> getString(
                R.string.timer_m,
                BaseReadAloudService.timeMinute,
            )

            else -> getString(R.string.set_timer)
        }
    }

    private fun upTimerText(timeMinute: Int) {
        timerTextState = if (timeMinute < 0) {
            requireContext().getString(R.string.timer_m, 0)
        } else {
            requireContext().getString(R.string.timer_m, timeMinute)
        }
    }

    private fun upTtsSpeechRate() {
        ReadAloud.upTtsSpeechRate(requireContext())
        if (!BaseReadAloudService.pause) {
            ReadAloud.pause(requireContext())
            ReadAloud.resume(requireContext())
        }
    }

    private fun upEngineName() {
        engineNameState = ReadAloud.getEngineName(requireContext())
    }

    override fun upSpeakEngineSummary() {
        upEngineName()
    }

    override fun onSleepTimerMinute(minute: Int) {
        ReadAloud.setTimer(requireContext(), minute)
    }

    override fun onSleepTimerChapter(count: Int) {
        ReadAloud.setChapterStop(requireContext(), count)
    }

    override fun observeLiveBus() {
        observeEvent<Int>(EventBus.ALOUD_STATE) { upPlayState() }
        observeEvent<Int>(EventBus.READ_ALOUD_DS) {
            seekTimerState = it
            upStopText()
        }
        observeEvent<Int>(EventBus.READ_ALOUD_CHAPTER_STOP) {
            if (it > 0) seekTimerState = 0
            upStopText()
        }
    }

    @Composable
    private fun ReadAloudScreen() {
        val context = requireContext()
        val bgInt = context.bottomBackground
        val isLight = ColorUtils.isColorLight(bgInt)
        val textColor = Color(context.getPrimaryTextColor(isLight))
        val accent = Color(context.accentColor)
        val engineSemanticsDesc = "${stringResource(R.string.speak_engine)}: $engineNameState"

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(bgInt))
                .padding(horizontal = 16.dp, vertical = 6.dp)
        ) {
            // Engine selector row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp)
                    .heightIn(min = 48.dp)
                    .semantics { contentDescription = engineSemanticsDesc }
                    .clickable {
                        SpeakEngineDialog().show(childFragmentManager, "speakEngineDialog")
                    }
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_volume_up),
                    contentDescription = null,
                    tint = textColor,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = engineNameState,
                    color = textColor,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_drop_down),
                    contentDescription = null,
                    tint = textColor,
                    modifier = Modifier.size(20.dp)
                )
            }

            // Playback controls row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.previous_chapter),
                    color = textColor,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .padding(horizontal = 10.dp)
                        .clickable { onPrevChapter() }
                        .padding(vertical = 10.dp)
                )
                Spacer(modifier = Modifier.weight(1f))
                IconButton(
                    onClick = { ReadAloud.prevParagraph(requireContext()) },
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .size(36.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_skip_previous),
                        contentDescription = stringResource(R.string.prev_sentence),
                        tint = textColor,
                        modifier = Modifier.size(30.dp)
                    )
                }
                IconButton(
                    onClick = { callBack?.onClickReadAloud() },
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .size(36.dp)
                        .semantics { contentDescription = playPauseDescription }
                ) {
                    Icon(
                        painter = painterResource(
                            if (!isPausedState) R.drawable.ic_pause_24dp else R.drawable.ic_play_24dp
                        ),
                        contentDescription = playPauseDescription,
                        tint = textColor,
                        modifier = Modifier.size(30.dp)
                    )
                }
                IconButton(
                    onClick = { onStopClick() },
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .size(36.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_stop_black_24dp),
                        contentDescription = stringResource(R.string.stop),
                        tint = textColor,
                        modifier = Modifier.size(30.dp)
                    )
                }
                IconButton(
                    onClick = { ReadAloud.nextParagraph(requireContext()) },
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .size(36.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_skip_next),
                        contentDescription = stringResource(R.string.next_sentence),
                        tint = textColor,
                        modifier = Modifier.size(30.dp)
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = stringResource(R.string.next_chapter),
                    color = textColor,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .padding(horizontal = 10.dp)
                        .clickable { onNextChapter() }
                        .padding(vertical = 10.dp)
                )
            }

            // Sleep timer row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = {
                        AppConfig.ttsTimer = seekTimerState
                        toastOnUi("保存设定时间成功！")
                    },
                    modifier = Modifier.size(30.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_time_add_24dp),
                        contentDescription = stringResource(R.string.set_timer),
                        tint = textColor,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Slider(
                    value = seekTimerState.toFloat(),
                    onValueChange = {
                        val progress = it.roundToInt()
                        seekTimerState = progress
                        upTimerText(progress)
                    },
                    onValueChangeFinished = {
                        ReadAloud.setTimer(requireContext(), seekTimerState)
                    },
                    valueRange = 0f..180f,
                    colors = SliderDefaults.colors(
                        thumbColor = accent,
                        activeTrackColor = accent
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp)
                )
                Text(
                    text = timerTextState,
                    color = textColor,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .clickable {
                            showDialogFragment(
                                SleepTimerDialog.newInstance(
                                    BaseReadAloudService.timeMinute,
                                    BaseReadAloudService.chapterToStop,
                                )
                            )
                        }
                        .padding(4.dp)
                )
            }

            // Speech rate header row
            val speedEnabled = !ttsFollowSysState
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.read_aloud_speed),
                    color = textColor,
                    fontSize = 14.sp
                )
                if (speedEnabled) {
                    Text(
                        text = ((ttsSpeechRateState + 5) / 10f).toString(),
                        color = textColor,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(horizontal = 3.dp)
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = stringResource(R.string.flow_sys),
                    color = textColor,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(end = 6.dp)
                )
                Switch(
                    checked = ttsFollowSysState,
                    onCheckedChange = { isChecked ->
                        ttsFollowSysState = isChecked
                        AppConfig.ttsFlowSys = isChecked
                        upTtsSpeechRate()
                    }
                )
            }

            // Speech rate slider row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = {
                        val next = (AppConfig.ttsSpeechRate - 1).coerceAtLeast(0)
                        ttsSpeechRateState = next
                        AppConfig.ttsSpeechRate = next
                        upTtsSpeechRate()
                    },
                    enabled = speedEnabled,
                    modifier = Modifier.size(30.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_reduce),
                        contentDescription = stringResource(R.string.tts_speech_reduce),
                        tint = textColor
                    )
                }
                Slider(
                    value = ttsSpeechRateState.toFloat(),
                    onValueChange = { ttsSpeechRateState = it.roundToInt() },
                    onValueChangeFinished = {
                        AppConfig.ttsSpeechRate = ttsSpeechRateState
                        upTtsSpeechRate()
                    },
                    enabled = speedEnabled,
                    valueRange = 0f..45f,
                    colors = SliderDefaults.colors(
                        thumbColor = accent,
                        activeTrackColor = accent
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp)
                )
                IconButton(
                    onClick = {
                        val next = (AppConfig.ttsSpeechRate + 1).coerceAtMost(45)
                        ttsSpeechRateState = next
                        AppConfig.ttsSpeechRate = next
                        upTtsSpeechRate()
                    },
                    enabled = speedEnabled,
                    modifier = Modifier.size(30.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_add),
                        contentDescription = stringResource(R.string.tts_speech_add),
                        tint = textColor
                    )
                }
            }

            // Bottom 4 actions row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                ReadAloudActionItem(
                    iconRes = R.drawable.ic_toc,
                    labelRes = R.string.chapter_list,
                    textColor = textColor,
                    onClick = { callBack?.openChapterList() }
                )
                ReadAloudActionItem(
                    iconRes = R.drawable.ic_menu,
                    labelRes = R.string.main_menu,
                    textColor = textColor,
                    onClick = {
                        callBack?.showMenuBar()
                        dismissAllowingStateLoss()
                    }
                )
                ReadAloudActionItem(
                    iconRes = R.drawable.ic_visibility_off,
                    labelRes = R.string.to_backstage,
                    textColor = textColor,
                    onClick = { callBack?.finish() }
                )
                ReadAloudActionItem(
                    iconRes = R.drawable.ic_settings,
                    labelRes = R.string.setting,
                    textColor = textColor,
                    onClick = {
                        ReadAloudConfigDialog().show(childFragmentManager, "readAloudConfigDialog")
                    }
                )
            }
        }
    }

    @Composable
    private fun ReadAloudActionItem(
        iconRes: Int,
        labelRes: Int,
        textColor: Color,
        onClick: () -> Unit
    ) {
        val label = stringResource(labelRes)
        Column(
            modifier = Modifier
                .width(60.dp)
                .semantics { contentDescription = label }
                .clickable(onClick = onClick)
                .padding(bottom = 7.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = label,
                tint = textColor,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = label,
                color = textColor,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }

    interface CallBack {
        fun showMenuBar()
        fun openChapterList()
        fun onClickReadAloud()
        fun backToSpeakingPosition()
        fun finish()
    }
}
