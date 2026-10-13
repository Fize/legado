package io.legado.app.ui.book.read.config

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.widget.NestedScrollView
import com.google.android.material.chip.ChipGroup
import com.jaredrummler.android.colorpicker.ColorPickerDialog
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.constant.EventBus
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.ReadTipConfig
import io.legado.app.help.config.ReaderInfoTemplate
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.view.ThemeEditText
import io.legado.app.ui.font.FontSelectDialog
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.text.AccentBgTextView
import io.legado.app.utils.dpToPx
import io.legado.app.utils.hexString
import io.legado.app.utils.observeEvent
import io.legado.app.utils.postEvent
import io.legado.app.utils.setLayout
import io.legado.app.utils.showDialogFragment
import kotlin.math.roundToInt

class TipConfigDialog : BaseDialogFragment(),
    FontSelectDialog.CallBack {

    companion object {
        const val TIP_COLOR = 7897
        const val TIP_DIVIDER_COLOR = 7898
        const val TITLE_NUMBER_COLOR = 7899
        const val TITLE_COLOR = 7900
    }

    private var titleModeState by mutableIntStateOf(0)
    private var titleSizeProgress by mutableIntStateOf(0)
    private var titleLineSpacingProgress by mutableIntStateOf(0)
    private var tvTitleFont by mutableStateOf("")
    private var tvTitleFontWeight by mutableStateOf("")
    private var tvTitleColor by mutableStateOf("")
    private var splitChapterTitleState by mutableStateOf(false)
    private var titleNumberSizeProgress by mutableIntStateOf(0)
    private var titleNumberSpacingProgress by mutableIntStateOf(0)
    private var tvTitleNumberColor by mutableStateOf("")
    private var titleTopProgress by mutableIntStateOf(0)
    private var titleBottomProgress by mutableIntStateOf(0)
    private var tvHeaderShow by mutableStateOf("")
    private var tvFooterShow by mutableStateOf("")
    private var tipTextSizeProgress by mutableIntStateOf(0)
    private var tvHeaderLeft by mutableStateOf("")
    private var tvHeaderMiddle by mutableStateOf("")
    private var tvHeaderRight by mutableStateOf("")
    private var tvFooterLeft by mutableStateOf("")
    private var tvFooterMiddle by mutableStateOf("")
    private var tvFooterRight by mutableStateOf("")
    private var tvTipColor by mutableStateOf("")
    private var tvTipDividerColor by mutableStateOf("")

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
                    TipConfigScreen()
                }
            }
        }
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        initView()
        observeEvent<String>(EventBus.TIP_COLOR) {
            upTitleColor()
            upTitleNumberColor()
            upTvTipColor()
            upTvTipDividerColor()
        }
    }

    private fun initView() {
        if (ReadBookConfig.titleMode !in 0..3) {
            ReadBookConfig.titleMode = 0
        }
        titleModeState = ReadBookConfig.titleMode
        titleSizeProgress = ReadBookConfig.titleSize
        titleLineSpacingProgress =
            titleLineSpacingToProgress(ReadBookConfig.titleLineSpacingExtra)
        upTitleFont()
        upTitleFontWeight()
        upTitleColor()
        splitChapterTitleState = ReadBookConfig.splitChapterTitle
        titleNumberSizeProgress = ReadBookConfig.titleNumberSize
        titleNumberSpacingProgress =
            titleNumberSpacingToProgress(ReadBookConfig.titleNumberSpacing)
        upTitleNumberColor()
        titleTopProgress = ReadBookConfig.titleTopSpacing
        titleBottomProgress = ReadBookConfig.titleBottomSpacing

        tvHeaderShow =
            ReadTipConfig.getHeaderModes(requireContext())[ReadTipConfig.headerMode].orEmpty()
        tvFooterShow =
            ReadTipConfig.getFooterModes(requireContext())[ReadTipConfig.footerMode].orEmpty()
        tipTextSizeProgress =
            tipTextSizeToProgress(ReadTipConfig.tipTextSize)

        initTipValues()
        upTvTipColor()
        upTvTipDividerColor()
    }

    private fun initTipValues() {
        ReadTipConfig.run {
            tvHeaderLeft = effectiveTemplate(tipHeaderLeftTemplate, tipHeaderLeft)
            tvHeaderMiddle = effectiveTemplate(tipHeaderMiddleTemplate, tipHeaderMiddle)
            tvHeaderRight = effectiveTemplate(tipHeaderRightTemplate, tipHeaderRight)
            tvFooterLeft = effectiveTemplate(tipFooterLeftTemplate, tipFooterLeft)
            tvFooterMiddle = effectiveTemplate(tipFooterMiddleTemplate, tipFooterMiddle)
            tvFooterRight = effectiveTemplate(tipFooterRightTemplate, tipFooterRight)
        }
    }

    private fun upTvTipColor() {
        val tipColorNames = ReadTipConfig.tipColorNames
        val tipColor = ReadTipConfig.tipColor
        tvTipColor = if (tipColor == 0) {
            tipColorNames.first()
        } else {
            "#${tipColor.hexString}"
        }
    }

    private fun upTvTipDividerColor() {
        val tipDividerColorNames = ReadTipConfig.tipDividerColorNames
        val tipDividerColor = ReadTipConfig.tipDividerColor
        tvTipDividerColor = when (tipDividerColor) {
            -1, 0 -> tipDividerColorNames[tipDividerColor + 1]
            else -> "#${tipDividerColor.hexString}"
        }
    }

    private fun upTitleColor() {
        val color = ReadBookConfig.titleColor
        tvTitleColor = if (color == 0) {
            ReadTipConfig.tipColorNames.first()
        } else {
            "#${color.hexString}"
        }
    }

    private fun upTitleFont() {
        tvTitleFont = ReadBookConfig.titleFont.takeIf { it.isNotEmpty() }
            ?.let { path ->
                Uri.decode(path)
                    .substringAfterLast('/')
                    .substringAfterLast('\\')
                    .ifBlank { path }
            }
            ?: getString(R.string.follow_text_font)
    }

    private fun upTitleNumberColor() {
        val color = ReadBookConfig.titleNumberColor
        tvTitleNumberColor = if (color == 0) {
            ReadTipConfig.tipColorNames.first()
        } else {
            "#${color.hexString}"
        }
    }

    private fun titleFontWeightOptions(): List<String> =
        listOf(getString(R.string.title_font_weight_follow)) +
            resources.getStringArray(R.array.text_font_weight)

    private fun upTitleFontWeight() {
        tvTitleFontWeight = titleFontWeightOptions()[ReadBookConfig.titleBold + 1]
    }

    @Composable
    private fun TipConfigScreen() {
        val context = requireContext()
        val textColor = Color(context.primaryTextColor)
        val accent = Color(context.accentColor)

        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                // Section: Body Title
                Text(
                    text = stringResource(R.string.body_title),
                    color = accent,
                    fontSize = 18.sp
                )

                // Title mode radio group: Left (0), Center (1), Right (3), Hide (2)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val modes = listOf(
                        0 to R.string.title_left,
                        1 to R.string.title_center,
                        3 to R.string.right,
                        2 to R.string.title_hide
                    )
                    modes.forEach { (modeVal, labelRes) ->
                        Row(
                            modifier = Modifier
                                .clickable {
                                    titleModeState = modeVal
                                    ReadBookConfig.titleMode = modeVal
                                    postEvent(EventBus.UP_CONFIG, arrayListOf(5))
                                }
                                .padding(3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = titleModeState == modeVal,
                                onClick = {
                                    titleModeState = modeVal
                                    ReadBookConfig.titleMode = modeVal
                                    postEvent(EventBus.UP_CONFIG, arrayListOf(5))
                                }
                            )
                            Text(
                                text = stringResource(labelRes),
                                color = textColor,
                                fontSize = 14.sp
                            )
                        }
                    }
                }

                // Title Size (max = 20)
                TipSeekBarRow(
                    titleRes = R.string.title_font_size,
                    progress = titleSizeProgress,
                    max = 20,
                    displayValue = titleSizeProgress.toString(),
                    textColor = textColor,
                    accentColor = accent,
                    onProgressChanged = { titleSizeProgress = it },
                    onValueSettled = {
                        titleSizeProgress = it
                        ReadBookConfig.titleSize = it
                        postEvent(EventBus.UP_CONFIG, arrayListOf(8, 5))
                    }
                )

                // Title Line Spacing (max = 50)
                TipSeekBarRow(
                    titleRes = R.string.title_line_spacing,
                    progress = titleLineSpacingProgress,
                    max = 50,
                    displayValue = titleLineSpacingDisplayValue(titleLineSpacingProgress),
                    textColor = textColor,
                    accentColor = accent,
                    onProgressChanged = { titleLineSpacingProgress = it },
                    onValueSettled = {
                        titleLineSpacingProgress = it
                        ReadBookConfig.titleLineSpacingExtra = titleLineSpacingFromProgress(it)
                        postEvent(EventBus.UP_CONFIG, arrayListOf(8, 5))
                    }
                )

                // Title Font
                TipOptionRow(
                    labelRes = R.string.title_font,
                    value = tvTitleFont,
                    textColor = textColor,
                    minHeightDp = 48,
                    onClick = { showDialogFragment<FontSelectDialog>() }
                )

                // Title Font Weight
                TipOptionRow(
                    labelRes = R.string.title_font_weight,
                    value = tvTitleFontWeight,
                    textColor = textColor,
                    minHeightDp = 48,
                    onClick = {
                        context.selector(
                            title = getString(R.string.title_font_weight),
                            items = titleFontWeightOptions(),
                        ) { _, index ->
                            ReadBookConfig.titleBold = index - 1
                            upTitleFontWeight()
                            postEvent(EventBus.UP_CONFIG, arrayListOf(8, 5))
                        }
                    }
                )

                // Title Color
                TipOptionRow(
                    labelRes = R.string.title_color,
                    value = tvTitleColor,
                    textColor = textColor,
                    onClick = {
                        context.selector(items = ReadTipConfig.tipColorNames) { _, i ->
                            when (i) {
                                0 -> {
                                    ReadBookConfig.titleColor = 0
                                    upTitleColor()
                                    postEvent(EventBus.UP_CONFIG, arrayListOf(8, 5))
                                }

                                1 -> ColorPickerDialog.newBuilder()
                                    .setColor(ReadBookConfig.titleTextColor)
                                    .setShowAlphaSlider(false)
                                    .setDialogType(ColorPickerDialog.TYPE_CUSTOM)
                                    .setDialogId(TITLE_COLOR)
                                    .show(requireActivity())
                            }
                        }
                    }
                )

                // Split Chapter Title Switch
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.split_chapter_title),
                        color = textColor,
                        fontSize = 14.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = splitChapterTitleState,
                        onCheckedChange = { isChecked ->
                            splitChapterTitleState = isChecked
                            ReadBookConfig.splitChapterTitle = isChecked
                            postEvent(EventBus.UP_CONFIG, arrayListOf(5))
                        }
                    )
                }

                // Title Number Options (visible when splitChapterTitleState is true)
                if (splitChapterTitleState) {
                    // Title Number Size (max = 20)
                    TipSeekBarRow(
                        titleRes = R.string.title_number_font_size,
                        progress = titleNumberSizeProgress,
                        max = 20,
                        displayValue = titleNumberSizeProgress.toString(),
                        textColor = textColor,
                        accentColor = accent,
                        onProgressChanged = { titleNumberSizeProgress = it },
                        onValueSettled = {
                            titleNumberSizeProgress = it
                            ReadBookConfig.titleNumberSize = it
                            postEvent(EventBus.UP_CONFIG, arrayListOf(8, 5))
                        }
                    )

                    // Title Number Spacing (max = 150)
                    TipSeekBarRow(
                        titleRes = R.string.title_number_spacing,
                        progress = titleNumberSpacingProgress,
                        max = 150,
                        displayValue = titleNumberSpacingFromProgress(titleNumberSpacingProgress).toString(),
                        textColor = textColor,
                        accentColor = accent,
                        onProgressChanged = { titleNumberSpacingProgress = it },
                        onValueSettled = {
                            titleNumberSpacingProgress = it
                            ReadBookConfig.titleNumberSpacing = titleNumberSpacingFromProgress(it)
                            postEvent(EventBus.UP_CONFIG, arrayListOf(5))
                        }
                    )

                    // Title Number Color
                    TipOptionRow(
                        labelRes = R.string.title_number_color,
                        value = tvTitleNumberColor,
                        textColor = textColor,
                        onClick = {
                            context.selector(items = ReadTipConfig.tipColorNames) { _, i ->
                                when (i) {
                                    0 -> {
                                        ReadBookConfig.titleNumberColor = 0
                                        upTitleNumberColor()
                                        postEvent(EventBus.UP_CONFIG, arrayListOf(8, 5))
                                    }

                                    1 -> ColorPickerDialog.newBuilder()
                                        .setColor(ReadBookConfig.titleNumberTextColor)
                                        .setShowAlphaSlider(false)
                                        .setDialogType(ColorPickerDialog.TYPE_CUSTOM)
                                        .setDialogId(TITLE_NUMBER_COLOR)
                                        .show(requireActivity())
                                }
                            }
                        }
                    )
                }

                // Title Margin Top (max = 100)
                TipSeekBarRow(
                    titleRes = R.string.title_margin_top,
                    progress = titleTopProgress,
                    max = 100,
                    displayValue = titleTopProgress.toString(),
                    textColor = textColor,
                    accentColor = accent,
                    onProgressChanged = { titleTopProgress = it },
                    onValueSettled = {
                        titleTopProgress = it
                        ReadBookConfig.titleTopSpacing = it
                        postEvent(EventBus.UP_CONFIG, arrayListOf(8, 5))
                    }
                )

                // Title Margin Bottom (max = 100)
                TipSeekBarRow(
                    titleRes = R.string.title_margin_bottom,
                    progress = titleBottomProgress,
                    max = 100,
                    displayValue = titleBottomProgress.toString(),
                    textColor = textColor,
                    accentColor = accent,
                    onProgressChanged = { titleBottomProgress = it },
                    onValueSettled = {
                        titleBottomProgress = it
                        ReadBookConfig.titleBottomSpacing = it
                        postEvent(EventBus.UP_CONFIG, arrayListOf(8, 5))
                    }
                )

                // Section: Header
                Text(
                    text = stringResource(R.string.header),
                    color = accent,
                    fontSize = 18.sp
                )
                TipOptionRow(
                    labelRes = R.string.show_hide,
                    value = tvHeaderShow,
                    textColor = textColor,
                    onClick = {
                        val headerModes = ReadTipConfig.getHeaderModes(requireContext())
                        context.selector(items = headerModes.values.toList()) { _, i ->
                            ReadTipConfig.headerMode = headerModes.keys.toList()[i]
                            tvHeaderShow = headerModes[ReadTipConfig.headerMode].orEmpty()
                            postEvent(EventBus.UP_CONFIG, arrayListOf(2))
                        }
                    }
                )
                TipTemplateRow(
                    labelRes = R.string.left,
                    value = tvHeaderLeft,
                    textColor = textColor,
                    onClick = {
                        ReadTipConfig.run {
                            editTemplate(
                                title = getString(R.string.reader_info_template),
                                current = effectiveTemplate(tipHeaderLeftTemplate, tipHeaderLeft),
                            ) { tipHeaderLeftTemplate = it }
                        }
                    }
                )
                TipTemplateRow(
                    labelRes = R.string.middle,
                    value = tvHeaderMiddle,
                    textColor = textColor,
                    onClick = {
                        ReadTipConfig.run {
                            editTemplate(
                                title = getString(R.string.reader_info_template),
                                current = effectiveTemplate(tipHeaderMiddleTemplate, tipHeaderMiddle),
                            ) { tipHeaderMiddleTemplate = it }
                        }
                    }
                )
                TipTemplateRow(
                    labelRes = R.string.right,
                    value = tvHeaderRight,
                    textColor = textColor,
                    onClick = {
                        ReadTipConfig.run {
                            editTemplate(
                                title = getString(R.string.reader_info_template),
                                current = effectiveTemplate(tipHeaderRightTemplate, tipHeaderRight),
                            ) { tipHeaderRightTemplate = it }
                        }
                    }
                )

                // Section: Footer
                Text(
                    text = stringResource(R.string.footer),
                    color = accent,
                    fontSize = 18.sp
                )
                TipOptionRow(
                    labelRes = R.string.show_hide,
                    value = tvFooterShow,
                    textColor = textColor,
                    onClick = {
                        val footerModes = ReadTipConfig.getFooterModes(requireContext())
                        context.selector(items = footerModes.values.toList()) { _, i ->
                            ReadTipConfig.footerMode = footerModes.keys.toList()[i]
                            tvFooterShow = footerModes[ReadTipConfig.footerMode].orEmpty()
                            postEvent(EventBus.UP_CONFIG, arrayListOf(2))
                        }
                    }
                )
                TipTemplateRow(
                    labelRes = R.string.left,
                    value = tvFooterLeft,
                    textColor = textColor,
                    onClick = {
                        ReadTipConfig.run {
                            editTemplate(
                                title = getString(R.string.reader_info_template),
                                current = effectiveTemplate(tipFooterLeftTemplate, tipFooterLeft),
                            ) { tipFooterLeftTemplate = it }
                        }
                    }
                )
                TipTemplateRow(
                    labelRes = R.string.middle,
                    value = tvFooterMiddle,
                    textColor = textColor,
                    onClick = {
                        ReadTipConfig.run {
                            editTemplate(
                                title = getString(R.string.reader_info_template),
                                current = effectiveTemplate(tipFooterMiddleTemplate, tipFooterMiddle),
                            ) { tipFooterMiddleTemplate = it }
                        }
                    }
                )
                TipTemplateRow(
                    labelRes = R.string.right,
                    value = tvFooterRight,
                    textColor = textColor,
                    onClick = {
                        ReadTipConfig.run {
                            editTemplate(
                                title = getString(R.string.reader_info_template),
                                current = effectiveTemplate(tipFooterRightTemplate, tipFooterRight),
                            ) { tipFooterRightTemplate = it }
                        }
                    }
                )

                // Section: Header & Footer Common
                Text(
                    text = stringResource(R.string.header_footer),
                    color = accent,
                    fontSize = 18.sp
                )

                // Tip Text Size (max = 45)
                TipSeekBarRow(
                    titleRes = R.string.text_size,
                    progress = tipTextSizeProgress,
                    max = 45,
                    displayValue = tipTextSizeFromProgress(tipTextSizeProgress).toString(),
                    textColor = textColor,
                    accentColor = accent,
                    onProgressChanged = { tipTextSizeProgress = it },
                    onValueSettled = {
                        tipTextSizeProgress = it
                        ReadTipConfig.tipTextSize = tipTextSizeFromProgress(it)
                        postEvent(EventBus.UP_CONFIG, arrayListOf(2))
                    }
                )

                // Tip Color
                TipOptionRow(
                    labelRes = R.string.text_color,
                    value = tvTipColor,
                    textColor = textColor,
                    onClick = {
                        context.selector(items = ReadTipConfig.tipColorNames) { _, i ->
                            when (i) {
                                0 -> {
                                    ReadTipConfig.tipColor = 0
                                    upTvTipColor()
                                    postEvent(EventBus.UP_CONFIG, arrayListOf(2))
                                }

                                1 -> ColorPickerDialog.newBuilder()
                                    .setShowAlphaSlider(false)
                                    .setDialogType(ColorPickerDialog.TYPE_CUSTOM)
                                    .setDialogId(TIP_COLOR)
                                    .show(requireActivity())
                            }
                        }
                    }
                )

                // Tip Divider Color
                TipOptionRow(
                    labelRes = R.string.tip_divider_color,
                    value = tvTipDividerColor,
                    textColor = textColor,
                    onClick = {
                        context.selector(items = ReadTipConfig.tipDividerColorNames) { _, i ->
                            when (i) {
                                0, 1 -> {
                                    ReadTipConfig.tipDividerColor = i - 1
                                    upTvTipDividerColor()
                                    postEvent(EventBus.UP_CONFIG, arrayListOf(2))
                                }

                                2 -> ColorPickerDialog.newBuilder()
                                    .setShowAlphaSlider(false)
                                    .setDialogType(ColorPickerDialog.TYPE_CUSTOM)
                                    .setDialogId(TIP_DIVIDER_COLOR)
                                    .show(requireActivity())
                            }
                        }
                    }
                )
            }
        }
    }

    @Composable
    private fun TipOptionRow(
        labelRes: Int,
        value: String,
        textColor: Color,
        minHeightDp: Int = 0,
        onClick: () -> Unit
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (minHeightDp > 0) Modifier.heightIn(min = minHeightDp.dp) else Modifier)
                .clickable(onClick = onClick),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(labelRes),
                color = textColor,
                fontSize = 14.sp,
                modifier = Modifier
                    .weight(1f)
                    .padding(6.dp)
            )
            Text(
                text = value,
                color = textColor,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .widthIn(max = 180.dp)
                    .padding(6.dp)
            )
        }
    }

    @Composable
    private fun TipTemplateRow(
        labelRes: Int,
        value: String,
        textColor: Color,
        onClick: () -> Unit
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(labelRes),
                color = textColor,
                fontSize = 14.sp,
                modifier = Modifier
                    .weight(1f)
                    .padding(6.dp)
            )
            Text(
                text = value,
                color = textColor,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
                modifier = Modifier
                    .weight(1f)
                    .padding(6.dp)
            )
        }
    }

    @Composable
    private fun TipSeekBarRow(
        titleRes: Int,
        progress: Int,
        max: Int,
        displayValue: String,
        textColor: Color,
        accentColor: Color,
        onProgressChanged: (Int) -> Unit,
        onValueSettled: (Int) -> Unit
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(3.dp)
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
                        if (progress > 0) {
                            onValueSettled(progress - 1)
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
                    onValueChange = { onProgressChanged(it.roundToInt().coerceIn(0, max)) },
                    onValueChangeFinished = { onValueSettled(progress) },
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
                            onValueSettled(progress + 1)
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

    override val curFontPath: String
        get() = ReadBookConfig.titleFont

    override val selectSystemTypefaceOnDefault = false

    override fun selectFont(path: String) {
        if (path != ReadBookConfig.titleFont || path.isEmpty()) {
            ReadBookConfig.titleFont = path
            upTitleFont()
            postEvent(EventBus.UP_CONFIG, arrayListOf(8, 5))
        }
    }

    private fun editTemplate(
        title: String,
        current: String,
        save: (String) -> Unit,
    ) {
        val context = requireContext()
        val editTemplate = ThemeEditText(context).apply {
            setHint(R.string.reader_info_template_hint)
            maxLines = 1
            isSingleLine = true
            setText(current)
            setSelection(current.length)
        }
        val chipPlaceholders = ChipGroup(context).apply {
            chipSpacingHorizontal = 6.dpToPx()
            chipSpacingVertical = 6.dpToPx()
            setPadding(0, 8.dpToPx(), 0, 0)
        }
        ReaderInfoTemplate.placeholders.forEach { placeholder ->
            val placeholderView = AccentBgTextView(requireContext()).apply {
                text = placeholder
                setRadius(4)
                includeFontPadding = false
                setPadding(12.dpToPx(), 6.dpToPx(), 12.dpToPx(), 6.dpToPx())
                setOnClickListener {
                    val edit = editTemplate
                    val editable = edit.editableText
                    val start = minOf(edit.selectionStart, edit.selectionEnd)
                        .coerceIn(0, editable.length)
                    val end = maxOf(edit.selectionStart, edit.selectionEnd)
                        .coerceIn(0, editable.length)
                    editable.replace(start, end, placeholder)
                }
            }
            chipPlaceholders.addView(placeholderView)
        }
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val hPad = 20.dpToPx()
            val vPad = 8.dpToPx()
            setPadding(hPad, vPad, hPad, vPad)
            addView(editTemplate)
            addView(chipPlaceholders)
        }
        val scrollView = NestedScrollView(context).apply {
            addView(container)
        }
        alert(title) {
            customView { scrollView }
            okButton {
                save(editTemplate.editableText.toString())
                initTipValues()
                postEvent(EventBus.UP_CONFIG, arrayListOf(2, 6))
            }
            cancelButton()
        }
    }

}

private const val TITLE_LINE_SPACING_MIN = -20
private const val TITLE_LINE_SPACING_MAX = 30

internal fun titleLineSpacingToProgress(spacing: Int): Int {
    return (spacing.coerceIn(TITLE_LINE_SPACING_MIN, TITLE_LINE_SPACING_MAX) -
        TITLE_LINE_SPACING_MIN)
}

internal fun titleLineSpacingFromProgress(progress: Int): Int {
    return (progress + TITLE_LINE_SPACING_MIN)
        .coerceIn(TITLE_LINE_SPACING_MIN, TITLE_LINE_SPACING_MAX)
}

internal fun titleLineSpacingDisplayValue(progress: Int): String {
    return (titleLineSpacingFromProgress(progress) / 10f).toString()
}

private const val TITLE_NUMBER_SPACING_MIN = -50
private const val TITLE_NUMBER_SPACING_MAX = 100

internal fun titleNumberSpacingToProgress(spacing: Int): Int {
    return spacing.coerceIn(TITLE_NUMBER_SPACING_MIN, TITLE_NUMBER_SPACING_MAX) -
        TITLE_NUMBER_SPACING_MIN
}

internal fun titleNumberSpacingFromProgress(progress: Int): Int {
    return (progress + TITLE_NUMBER_SPACING_MIN)
        .coerceIn(TITLE_NUMBER_SPACING_MIN, TITLE_NUMBER_SPACING_MAX)
}

internal fun tipTextSizeToProgress(textSize: Int): Int {
    return textSize.coerceIn(ReadTipConfig.minTextSize, ReadTipConfig.maxTextSize) -
        ReadTipConfig.minTextSize
}

internal fun tipTextSizeFromProgress(progress: Int): Int {
    return (progress + ReadTipConfig.minTextSize)
        .coerceIn(ReadTipConfig.minTextSize, ReadTipConfig.maxTextSize)
}
