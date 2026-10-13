package io.legado.app.ui.book.read.config

import android.animation.ValueAnimator
import android.content.DialogInterface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.constant.EventBus
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.getPrimaryTextColor
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.postEvent
import io.legado.app.utils.setLayout
import io.legado.app.utils.throttle
import kotlin.math.roundToInt

class PaddingConfigDialog : BaseDialogFragment() {

    private val defaultConfig = ReadBookConfig.Config()
    private var curRegion by mutableStateOf(Region.BODY)
    private var trackingRegion: Region? = null
    private var activeTrackingCount = 0
    private var lockLR by mutableStateOf(false)
    private var showLineState by mutableStateOf(false)
    private var actionsEnabledState by mutableStateOf(true)
    private var topProgress by mutableIntStateOf(0)
    private var bottomProgress by mutableIntStateOf(0)
    private var leftProgress by mutableIntStateOf(0)
    private var rightProgress by mutableIntStateOf(0)
    private var textColor = 0
    private var bgColor = 0
    private var pendingBodyEdit: PaddingEdit? = null
    private var alphaAnimator: ValueAnimator? = null
    private val bodyThrottle = throttle<Unit>(150, leading = false) {
        val edit = pendingBodyEdit
        pendingBodyEdit = null
        if (edit != null) applyEdit(edit)
    }

    private enum class Region(val titleRes: Int) {
        HEADER(R.string.header),
        BODY(R.string.main_body),
        FOOTER(R.string.footer),
    }

    private enum class Side { TOP, BOTTOM, LEFT, RIGHT }

    private data class PaddingEdit(
        val region: Region,
        val side: Side,
        val value: Int,
        val linkSides: Boolean,
    )

    override fun onStart() {
        super.onStart()
        dialog?.window?.run {
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setBackgroundDrawableResource(android.R.color.transparent)
            decorView.setPadding(0, 0, 0, 0)
            attributes = attributes.apply {
                dimAmount = 0.0f
                gravity = Gravity.CENTER
            }
        }
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
                    PaddingConfigScreen()
                }
            }
        }
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        (activity as ReadBookActivity).bottomDialog++
        bgColor = requireContext().bottomBackground
        textColor = requireContext().getPrimaryTextColor(ColorUtils.isColorLight(bgColor))
        bindRegion(Region.BODY)
    }

    override fun onDestroyView() {
        finishPendingBodyEdit()
        activeTrackingCount = 0
        trackingRegion = null
        alphaAnimator?.cancel()
        restoreWindowAlpha()
        super.onDestroyView()
    }

    override fun onDismiss(dialog: DialogInterface) {
        finishPendingBodyEdit()
        ReadBookConfig.save()
        (activity as ReadBookActivity).bottomDialog--
        super.onDismiss(dialog)
    }

    @Composable
    private fun PaddingConfigScreen() {
        val context = requireContext()
        val bg = Color(context.bottomBackground)
        val fg = Color(context.getPrimaryTextColor(ColorUtils.isColorLight(context.bottomBackground)))
        val accent = Color(context.accentColor)
        val radius = 8.dp
        val cornerRadius = radius
        val actionsAlpha = if (actionsEnabledState) 1f else 0.38f

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(cornerRadius))
                .background(bg)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            // Region selector + reset row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Region.entries.forEach { region ->
                        val selected = region == curRegion
                        val btnShape = RoundedCornerShape(8.dp)
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .alpha(actionsAlpha)
                                .clip(btnShape)
                                .background(if (selected) fg else Color.Transparent)
                                .border(1.dp, fg, btnShape)
                                .clickable(enabled = actionsEnabledState) {
                                    bindRegion(region)
                                }
                                .padding(horizontal = 4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(region.titleRes),
                                color = if (selected) bg else fg,
                                fontSize = 14.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
                IconButton(
                    onClick = { askResetRegion() },
                    enabled = actionsEnabledState,
                    modifier = Modifier
                        .size(48.dp)
                        .alpha(actionsAlpha)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_restore),
                        contentDescription = stringResource(R.string.restore_default),
                        tint = fg
                    )
                }
            }

            // 4 Padding SeekBars: Top (max 400), Bottom (max 400), Left (max 100), Right (max 100)
            PaddingSeekBarControl(
                titleRes = R.string.padding_top,
                progress = topProgress,
                max = 400,
                textColor = fg,
                accentColor = accent,
                onTrackingStart = { startTracking() },
                onDragging = { liveApply(Side.TOP, it) },
                onChanged = { finalApply(Side.TOP, it) },
                onTrackingStop = { stopTracking() }
            )
            PaddingSeekBarControl(
                titleRes = R.string.padding_bottom,
                progress = bottomProgress,
                max = 400,
                textColor = fg,
                accentColor = accent,
                onTrackingStart = { startTracking() },
                onDragging = { liveApply(Side.BOTTOM, it) },
                onChanged = { finalApply(Side.BOTTOM, it) },
                onTrackingStop = { stopTracking() }
            )
            PaddingSeekBarControl(
                titleRes = R.string.padding_left,
                progress = leftProgress,
                max = 100,
                textColor = fg,
                accentColor = accent,
                onTrackingStart = { startTracking() },
                onDragging = { liveApply(Side.LEFT, it) },
                onChanged = { finalApply(Side.LEFT, it) },
                onTrackingStop = { stopTracking() }
            )
            PaddingSeekBarControl(
                titleRes = R.string.padding_right,
                progress = rightProgress,
                max = 100,
                textColor = fg,
                accentColor = accent,
                onTrackingStart = { startTracking() },
                onDragging = { liveApply(Side.RIGHT, it) },
                onChanged = { finalApply(Side.RIGHT, it) },
                onTrackingStop = { stopTracking() }
            )

            // Lock LR Switch
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .alpha(actionsAlpha),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.lock_left_right_padding),
                    color = fg,
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = lockLR,
                    enabled = actionsEnabledState,
                    onCheckedChange = { isChecked ->
                        finishPendingBodyEdit()
                        lockLR = isChecked
                    }
                )
            }

            // Show Line Switch
            if (curRegion != Region.BODY) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.showLine),
                        color = fg,
                        fontSize = 14.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = showLineState,
                        onCheckedChange = { isChecked ->
                            showLineState = isChecked
                            when (curRegion) {
                                Region.HEADER -> ReadBookConfig.showHeaderLine = isChecked
                                Region.FOOTER -> ReadBookConfig.showFooterLine = isChecked
                                Region.BODY -> return@Switch
                            }
                            postRegionEvent(curRegion)
                        }
                    )
                }
            } else {
                Spacer(modifier = Modifier.height(48.dp))
            }
        }
    }

    @Composable
    private fun PaddingSeekBarControl(
        titleRes: Int,
        progress: Int,
        max: Int,
        textColor: Color,
        accentColor: Color,
        onTrackingStart: () -> Unit,
        onDragging: (Int) -> Unit,
        onChanged: (Int) -> Unit,
        onTrackingStop: () -> Unit
    ) {
        var isDragging by remember { mutableStateOf(false) }
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
                    text = progress.toString(),
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
                        if (progress > 0) {
                            onChanged(progress - 1)
                        }
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
                    onValueChange = {
                        if (!isDragging) {
                            isDragging = true
                            onTrackingStart()
                        }
                        onDragging(it.roundToInt().coerceIn(0, max))
                    },
                    onValueChangeFinished = {
                        if (isDragging) {
                            isDragging = false
                            onChanged(progress)
                            onTrackingStop()
                        }
                    },
                    valueRange = 0f..max.toFloat(),
                    colors = SliderDefaults.colors(
                        thumbColor = accentColor,
                        activeTrackColor = accentColor
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 4.dp)
                )
                IconButton(
                    onClick = {
                        if (progress < max) {
                            onChanged(progress + 1)
                        }
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

    private fun bindRegion(region: Region) {
        finishPendingBodyEdit()
        curRegion = region
        topProgress = getPadding(region, Side.TOP)
        bottomProgress = getPadding(region, Side.BOTTOM)
        leftProgress = getPadding(region, Side.LEFT)
        rightProgress = getPadding(region, Side.RIGHT)
        when (region) {
            Region.BODY -> showLineState = false
            Region.HEADER -> showLineState = ReadBookConfig.showHeaderLine
            Region.FOOTER -> showLineState = ReadBookConfig.showFooterLine
        }
        lockLR = getPadding(region, Side.LEFT) == getPadding(region, Side.RIGHT)
    }

    private fun startTracking() {
        if (activeTrackingCount++ == 0) {
            trackingRegion = curRegion
            setPanelActionsEnabled(false)
            ghostWindow(true)
        }
    }

    private fun stopTracking() {
        activeTrackingCount = (activeTrackingCount - 1).coerceAtLeast(0)
        if (activeTrackingCount == 0) {
            ghostWindow(false)
            trackingRegion = null
            setPanelActionsEnabled(true)
        }
    }

    private fun setPanelActionsEnabled(enabled: Boolean) {
        actionsEnabledState = enabled
    }

    private fun liveApply(side: Side, value: Int) {
        val edit = paddingEdit(side, value)
        if (edit.region == Region.BODY) {
            finishPendingBodyEditIfDifferent(edit)
            upCurrentSeekBar(edit)
            pendingBodyEdit = edit
            bodyThrottle()
        } else {
            applyEdit(edit)
        }
    }

    private fun finalApply(side: Side, value: Int) {
        val edit = paddingEdit(side, value)
        finishPendingBodyEditIfDifferent(edit)
        cancelPendingBodyEdit()
        applyEdit(edit)
    }

    private fun paddingEdit(side: Side, value: Int): PaddingEdit {
        val region = trackingRegion ?: curRegion
        return PaddingEdit(region, side, value, lockLR && region == curRegion)
    }

    private fun applyEdit(edit: PaddingEdit) {
        upCurrentSeekBar(edit)
        setPadding(edit.region, edit.side, edit.value)
        if (edit.linkSides && (edit.side == Side.LEFT || edit.side == Side.RIGHT)) {
            val other = if (edit.side == Side.LEFT) Side.RIGHT else Side.LEFT
            setPadding(edit.region, other, edit.value)
            if (edit.region == curRegion) {
                if (other == Side.LEFT) {
                    leftProgress = edit.value
                } else {
                    rightProgress = edit.value
                }
            }
        }
        postRegionEvent(edit.region)
    }

    private fun upCurrentSeekBar(edit: PaddingEdit) {
        if (edit.region != curRegion) return
        when (edit.side) {
            Side.TOP -> topProgress = edit.value
            Side.BOTTOM -> bottomProgress = edit.value
            Side.LEFT -> leftProgress = edit.value
            Side.RIGHT -> rightProgress = edit.value
        }
    }

    private fun cancelPendingBodyEdit() {
        bodyThrottle.cancel()
        pendingBodyEdit = null
    }

    private fun finishPendingBodyEdit() {
        val edit = pendingBodyEdit
        cancelPendingBodyEdit()
        if (edit != null) applyEdit(edit)
    }

    private fun finishPendingBodyEditIfDifferent(edit: PaddingEdit) {
        pendingBodyEdit?.let { pending ->
            if (pending.region != edit.region || pending.side != edit.side) {
                finishPendingBodyEdit()
            }
        }
    }

    private fun postRegionEvent(region: Region) {
        postEvent(
            EventBus.UP_CONFIG,
            if (region == Region.BODY) arrayListOf(10, 5) else arrayListOf(2),
        )
    }

    private fun ghostWindow(ghost: Boolean) {
        val window = dialog?.window ?: return
        alphaAnimator?.cancel()
        alphaAnimator = ValueAnimator.ofFloat(window.attributes.alpha, if (ghost) 0.25f else 1f)
            .apply {
                duration = 120
                addUpdateListener { animation ->
                    window.attributes = window.attributes.apply {
                        alpha = animation.animatedValue as Float
                    }
                }
                start()
            }
    }

    private fun restoreWindowAlpha() {
        dialog?.window?.let { window ->
            window.attributes = window.attributes.apply { alpha = 1f }
        }
    }

    private fun askResetRegion() {
        val region = curRegion
        alert(R.string.restore_default) {
            setMessage(getString(R.string.reset_region_padding_confirm, getString(region.titleRes)))
            yesButton {
                cancelPendingBodyEdit()
                Side.entries.forEach { side ->
                    setPadding(region, side, defaultPadding(region, side))
                }
                bindRegion(region)
                postRegionEvent(region)
            }
            noButton()
        }
    }

    private fun getPadding(region: Region, side: Side): Int = when (region) {
        Region.HEADER -> when (side) {
            Side.TOP -> ReadBookConfig.headerPaddingTop
            Side.BOTTOM -> ReadBookConfig.headerPaddingBottom
            Side.LEFT -> ReadBookConfig.headerPaddingLeft
            Side.RIGHT -> ReadBookConfig.headerPaddingRight
        }

        Region.BODY -> when (side) {
            Side.TOP -> ReadBookConfig.paddingTop
            Side.BOTTOM -> ReadBookConfig.paddingBottom
            Side.LEFT -> ReadBookConfig.paddingLeft
            Side.RIGHT -> ReadBookConfig.paddingRight
        }

        Region.FOOTER -> when (side) {
            Side.TOP -> ReadBookConfig.footerPaddingTop
            Side.BOTTOM -> ReadBookConfig.footerPaddingBottom
            Side.LEFT -> ReadBookConfig.footerPaddingLeft
            Side.RIGHT -> ReadBookConfig.footerPaddingRight
        }
    }

    private fun setPadding(region: Region, side: Side, value: Int) {
        when (region) {
            Region.HEADER -> when (side) {
                Side.TOP -> ReadBookConfig.headerPaddingTop = value
                Side.BOTTOM -> ReadBookConfig.headerPaddingBottom = value
                Side.LEFT -> ReadBookConfig.headerPaddingLeft = value
                Side.RIGHT -> ReadBookConfig.headerPaddingRight = value
            }

            Region.BODY -> when (side) {
                Side.TOP -> ReadBookConfig.paddingTop = value
                Side.BOTTOM -> ReadBookConfig.paddingBottom = value
                Side.LEFT -> ReadBookConfig.paddingLeft = value
                Side.RIGHT -> ReadBookConfig.paddingRight = value
            }

            Region.FOOTER -> when (side) {
                Side.TOP -> ReadBookConfig.footerPaddingTop = value
                Side.BOTTOM -> ReadBookConfig.footerPaddingBottom = value
                Side.LEFT -> ReadBookConfig.footerPaddingLeft = value
                Side.RIGHT -> ReadBookConfig.footerPaddingRight = value
            }
        }
    }

    private fun defaultPadding(region: Region, side: Side): Int = when (region) {
        Region.HEADER -> when (side) {
            Side.TOP -> defaultConfig.headerPaddingTop
            Side.BOTTOM -> defaultConfig.headerPaddingBottom
            Side.LEFT -> defaultConfig.headerPaddingLeft
            Side.RIGHT -> defaultConfig.headerPaddingRight
        }

        Region.BODY -> when (side) {
            Side.TOP -> defaultConfig.paddingTop
            Side.BOTTOM -> defaultConfig.paddingBottom
            Side.LEFT -> defaultConfig.paddingLeft
            Side.RIGHT -> defaultConfig.paddingRight
        }

        Region.FOOTER -> when (side) {
            Side.TOP -> defaultConfig.footerPaddingTop
            Side.BOTTOM -> defaultConfig.footerPaddingBottom
            Side.LEFT -> defaultConfig.footerPaddingLeft
            Side.RIGHT -> defaultConfig.footerPaddingRight
        }
    }
}
