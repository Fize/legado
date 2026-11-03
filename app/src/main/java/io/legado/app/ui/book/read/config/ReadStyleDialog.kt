package io.legado.app.ui.book.read.config

import android.content.DialogInterface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.github.liuyueyi.quick.transfer.constants.TransType
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.constant.EventBus
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.getPrimaryTextColor
import io.legado.app.model.ReadBook
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.font.FontSelectDialog
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.utils.ChineseUtils
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.postEvent
import io.legado.app.utils.showDialogFragment
import kotlin.math.roundToInt

class ReadStyleDialog : BaseDialogFragment(), FontSelectDialog.CallBack {

    private val callBack get() = activity as? ReadBookActivity
    private var updatingPageAnim = false

    private var textBoldState by mutableIntStateOf(ReadBookConfig.textBold)
    private var chineseTypeState by mutableIntStateOf(AppConfig.chineseConverterType)
    private var pageAnimState by mutableIntStateOf(ReadBook.pageAnim())
    private var shareLayoutState by mutableStateOf(ReadBookConfig.shareLayout)
    private var styleSelectState by mutableIntStateOf(ReadBookConfig.styleSelect)
    private var textSizeProgress by mutableIntStateOf(ReadBookConfig.textSize - 5)
    private var letterSpacingProgress by mutableIntStateOf((ReadBookConfig.letterSpacing * 100).toInt() + 50)
    private var lineSpacingProgress by mutableIntStateOf(lineSpacingToProgress(ReadBookConfig.lineSpacingExtra))
    private var paragraphSpacingProgress by mutableIntStateOf(ReadBookConfig.paragraphSpacing)

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

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LegadoTheme {
                    ReadStyleScreen()
                }
            }
        }
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        (activity as ReadBookActivity).bottomDialog++
        initData()
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        ReadBookConfig.save()
        (activity as ReadBookActivity).bottomDialog--
    }

    private fun initData() {
        shareLayoutState = ReadBookConfig.shareLayout
        chineseTypeState = AppConfig.chineseConverterType
        styleSelectState = ReadBookConfig.styleSelect
        upView()
    }

    private fun onPageAnimChecked(index: Int) {
        if (updatingPageAnim) return
        pageAnimState = index
        ReadBook.book?.setPageAnim(-1)
        ReadBookConfig.pageAnim = index
        callBack?.upPageAnim()
        ReadBook.loadContent(false)
    }

    private fun changeBgTextConfig(index: Int) {
        val oldIndex = ReadBookConfig.styleSelect
        if (index != oldIndex) {
            val oldPageAnim = ReadBook.pageAnim()
            ReadBookConfig.styleSelect = index
            styleSelectState = index
            if (ReadBook.pageAnim() != oldPageAnim) callBack?.upPageAnim()
            upView()
            postEvent(EventBus.UP_CONFIG, arrayListOf(1, 2, 5))
            if (AppConfig.readBarStyleFollowPage) {
                postEvent(EventBus.UPDATE_READ_ACTION_BAR, true)
            }
        }
    }

    private fun showBgTextConfig(index: Int): Boolean {
        dismissAllowingStateLoss()
        changeBgTextConfig(index)
        callBack?.showBgTextConfig()
        return true
    }

    private fun upView() {
        textBoldState = ReadBookConfig.textBold
        // Reflect the selected preset without replaying radio-button change callbacks.
        // Those callbacks can apply new padding while the old scroll page is still bound.
        updatingPageAnim = true
        try {
            ReadBook.pageAnim().let {
                if (it in 0..4) {
                    pageAnimState = it
                }
            }
        } finally {
            updatingPageAnim = false
        }
        ReadBookConfig.let {
            textSizeProgress = it.textSize - 5
            letterSpacingProgress = (it.letterSpacing * 100).toInt() + 50
            lineSpacingProgress = lineSpacingToProgress(it.lineSpacingExtra)
            paragraphSpacingProgress = it.paragraphSpacing
        }
    }

    override val curFontPath: String
        get() = ReadBookConfig.textFont

    override fun selectFont(path: String) {
        if (path != ReadBookConfig.textFont || path.isEmpty()) {
            ReadBookConfig.textFont = path
            postEvent(EventBus.UP_CONFIG, arrayListOf(2, 5))
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun ReadStyleScreen() {
        val context = requireContext()
        val bgInt = context.bottomBackground
        val isLight = ColorUtils.isColorLight(bgInt)
        val textColor = Color(context.getPrimaryTextColor(isLight))
        val accent = Color(context.accentColor)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(bgInt))
        ) {
            // Top horizontal toolbar of stroke buttons
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Font weight converter
                val weightText = stringResource(R.string.font_weight_text)
                StrokeChipButton(
                    textColor = textColor,
                    onClick = {
                        context.alert(titleResource = R.string.text_font_weight_converter) {
                            items(context.resources.getStringArray(R.array.text_font_weight).toList()) { _, i ->
                                ReadBookConfig.textBold = i
                                textBoldState = i
                                postEvent(EventBus.UP_CONFIG, arrayListOf(8, 9, 6))
                            }
                        }
                    }
                ) {
                    val activeRange = when (textBoldState) {
                        0 -> 0..0
                        1 -> 2..2
                        2 -> 4..4
                        else -> IntRange.EMPTY
                    }
                    Text(
                        text = buildAnnotatedString {
                            weightText.forEachIndexed { idx, ch ->
                                if (idx in activeRange) {
                                    withStyle(SpanStyle(color = accent)) { append(ch) }
                                } else {
                                    withStyle(SpanStyle(color = textColor)) { append(ch) }
                                }
                            }
                        },
                        fontSize = 14.sp
                    )
                }

                // Font selector
                StrokeChipButton(
                    textColor = textColor,
                    onClick = { showDialogFragment<FontSelectDialog>() }
                ) {
                    Text(
                        text = stringResource(R.string.text_font),
                        color = textColor,
                        fontSize = 14.sp
                    )
                }

                // Text indent
                StrokeChipButton(
                    textColor = textColor,
                    onClick = {
                        context.selector(
                            title = getString(R.string.text_indent),
                            items = resources.getStringArray(R.array.indent).toList()
                        ) { _, index ->
                            ReadBookConfig.paragraphIndent = "　".repeat(index)
                            postEvent(EventBus.UP_CONFIG, arrayListOf(8, 5))
                        }
                    }
                ) {
                    Text(
                        text = stringResource(R.string.text_indent),
                        color = textColor,
                        fontSize = 14.sp
                    )
                }

                // Chinese converter
                StrokeChipButton(
                    textColor = textColor,
                    onClick = {
                        context.alert(titleResource = R.string.chinese_converter) {
                            items(context.resources.getStringArray(R.array.chinese_mode).toList()) { _, i ->
                                AppConfig.chineseConverterType = i
                                chineseTypeState = i
                                ChineseUtils.unLoad(*TransType.entries.toTypedArray())
                                postEvent(EventBus.UP_CONFIG, arrayListOf(5))
                            }
                        }
                    }
                ) {
                    val activeIdx = when (chineseTypeState) {
                        1 -> 0
                        2 -> 2
                        else -> -1
                    }
                    Text(
                        text = buildAnnotatedString {
                            "简/繁".forEachIndexed { idx, ch ->
                                if (idx == activeIdx) {
                                    withStyle(SpanStyle(color = accent)) { append(ch) }
                                } else {
                                    withStyle(SpanStyle(color = textColor)) { append(ch) }
                                }
                            }
                        },
                        fontSize = 14.sp
                    )
                }

                // Padding config
                StrokeChipButton(
                    textColor = textColor,
                    onClick = {
                        dismissAllowingStateLoss()
                        callBack?.showPaddingConfig()
                    }
                ) {
                    Text(
                        text = stringResource(R.string.padding),
                        color = textColor,
                        fontSize = 14.sp
                    )
                }

                // Tip config
                StrokeChipButton(
                    textColor = textColor,
                    onClick = {
                        TipConfigDialog().show(childFragmentManager, "tipConfigDialog")
                    }
                ) {
                    Text(
                        text = stringResource(R.string.information),
                        color = textColor,
                        fontSize = 14.sp
                    )
                }
            }

            // 4 SeekBars: textSize (max 45), letterSpacing (max 100), lineSize (max 50), paragraphSpacing (max 20)
            StyleSeekBarRow(
                titleRes = R.string.text_size,
                progress = textSizeProgress,
                max = 45,
                displayValue = (textSizeProgress + 5).toString(),
                textColor = textColor,
                accentColor = accent,
                onProgressChanged = { textSizeProgress = it },
                onValueSettled = {
                    textSizeProgress = it
                    ReadBookConfig.textSize = it + 5
                    postEvent(EventBus.UP_CONFIG, arrayListOf(8, 5))
                }
            )
            StyleSeekBarRow(
                titleRes = R.string.text_letter_spacing,
                progress = letterSpacingProgress,
                max = 100,
                displayValue = ((letterSpacingProgress - 50) / 100f).toString(),
                textColor = textColor,
                accentColor = accent,
                onProgressChanged = { letterSpacingProgress = it },
                onValueSettled = {
                    letterSpacingProgress = it
                    ReadBookConfig.letterSpacing = (it - 50) / 100f
                    postEvent(EventBus.UP_CONFIG, arrayListOf(8, 5))
                }
            )
            StyleSeekBarRow(
                titleRes = R.string.line_size,
                progress = lineSpacingProgress,
                max = 50,
                displayValue = lineSpacingDisplayValue(lineSpacingProgress),
                textColor = textColor,
                accentColor = accent,
                onProgressChanged = { lineSpacingProgress = it },
                onValueSettled = {
                    lineSpacingProgress = it
                    ReadBookConfig.lineSpacingExtra = lineSpacingFromProgress(it)
                    postEvent(EventBus.UP_CONFIG, arrayListOf(8, 5))
                }
            )
            StyleSeekBarRow(
                titleRes = R.string.paragraph_size,
                progress = paragraphSpacingProgress,
                max = 20,
                displayValue = (paragraphSpacingProgress / 10f).toString(),
                textColor = textColor,
                accentColor = accent,
                onProgressChanged = { paragraphSpacingProgress = it },
                onValueSettled = {
                    paragraphSpacingProgress = it
                    ReadBookConfig.paragraphSpacing = it
                    postEvent(EventBus.UP_CONFIG, arrayListOf(8, 5))
                }
            )

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                color = textColor.copy(alpha = 0.2f)
            )

            // Page anim radio group
            Text(
                text = stringResource(R.string.page_anim),
                color = textColor.copy(alpha = 0.75f),
                fontSize = 12.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 4.dp)
            )

            val pageAnimLabels = listOf(
                R.string.page_anim_cover,
                R.string.page_anim_slide,
                R.string.page_anim_simulation,
                R.string.page_anim_scroll,
                R.string.page_anim_none
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 11.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                pageAnimLabels.forEachIndexed { index, labelRes ->
                    val selected = pageAnimState == index
                    val shape = RoundedCornerShape(4.dp)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(4.dp)
                            .clip(shape)
                            .background(if (selected) accent.copy(alpha = 0.2f) else Color.Transparent)
                            .border(1.dp, if (selected) accent else textColor.copy(alpha = 0.4f), shape)
                            .clickable { onPageAnimChecked(index) }
                            .padding(5.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(labelRes),
                            color = if (selected) accent else textColor,
                            fontSize = 13.sp,
                            maxLines = 1,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                color = textColor.copy(alpha = 0.2f)
            )

            // Background/Text style header + Share Layout checkbox
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.text_bg_style),
                    color = textColor.copy(alpha = 0.75f),
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = stringResource(R.string.share_layout),
                    color = textColor,
                    fontSize = 14.sp
                )
                Checkbox(
                    checked = shareLayoutState,
                    onCheckedChange = { isChecked ->
                        shareLayoutState = isChecked
                        val oldPageAnim = ReadBook.pageAnim()
                        ReadBookConfig.shareLayout = isChecked
                        if (ReadBook.pageAnim() != oldPageAnim) callBack?.upPageAnim()
                        upView()
                        postEvent(EventBus.UP_CONFIG, arrayListOf(1, 2, 5))
                    }
                )
            }

            // Style presets LazyRow
            val configs = ReadBookConfig.configList
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 16.dp),
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(configs) { index, item ->
                    val isSelected = styleSelectState == index
                    val itemTextColor = Color(item.curTextColor())
                    val borderColor = if (isSelected) accent else itemTextColor
                    val styleLabel = item.name.ifBlank { "文字" }
                    val styleDescription = "$styleLabel ${index + 1}"
                    Box(
                        modifier = Modifier
                            .size(50.dp)
                            .clip(CircleShape)
                            .border(if (isSelected) 2.dp else 1.dp, borderColor, CircleShape)
                            .semantics {
                                contentDescription = styleDescription
                                role = Role.Button
                            }
                            .combinedClickable(
                                onClick = { changeBgTextConfig(index) },
                                onLongClick = { showBgTextConfig(index) }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        AndroidView(
                            factory = { ctx ->
                                ImageView(ctx).apply {
                                    scaleType = ImageView.ScaleType.CENTER_CROP
                                }
                            },
                            update = { imageView ->
                                imageView.setImageDrawable(item.curBgDrawable(100, 150))
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                        Text(
                            text = styleLabel,
                            color = itemTextColor,
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 2,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(4.dp)
                        )
                    }
                }
                item(key = "add_style") {
                    Box(
                        modifier = Modifier
                            .size(50.dp)
                            .clip(CircleShape)
                            .border(1.dp, textColor, CircleShape)
                            .clickable {
                                ReadBookConfig.configList.add(ReadBookConfig.Config())
                                showBgTextConfig(ReadBookConfig.configList.lastIndex)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_add),
                            contentDescription = stringResource(R.string.add),
                            tint = textColor,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun StrokeChipButton(
        textColor: Color,
        onClick: () -> Unit,
        content: @Composable () -> Unit
    ) {
        val shape = RoundedCornerShape(3.dp)
        Box(
            modifier = Modifier
                .padding(horizontal = 3.dp)
                .clip(shape)
                .border(1.dp, textColor.copy(alpha = 0.5f), shape)
                .clickable(onClick = onClick)
                .padding(horizontal = 6.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            content()
        }
    }

    @Composable
    private fun StyleSeekBarRow(
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
                .padding(horizontal = 16.dp, vertical = 2.dp)
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
}

private const val LINE_SPACING_CONFIG_MIN = -10
private const val LINE_SPACING_CONFIG_MAX = 40
private const val LINE_SPACING_PROGRESS_OFFSET = 10

internal fun lineSpacingToProgress(value: Int): Int {
    return (value + LINE_SPACING_PROGRESS_OFFSET)
        .coerceIn(0, LINE_SPACING_CONFIG_MAX - LINE_SPACING_CONFIG_MIN)
}

internal fun lineSpacingFromProgress(progress: Int): Int {
    return (progress - LINE_SPACING_PROGRESS_OFFSET)
        .coerceIn(LINE_SPACING_CONFIG_MIN, LINE_SPACING_CONFIG_MAX)
}

internal fun lineSpacingDisplayValue(progress: Int): String {
    return ((lineSpacingFromProgress(progress) - 10) / 10f).toString()
}
