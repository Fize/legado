package io.legado.app.ui.book.read.config

import android.annotation.SuppressLint
import android.content.DialogInterface
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.appcompat.app.AlertDialog
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.toColorInt
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.jaredrummler.android.colorpicker.ColorPickerDialog
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.help.DefaultData
import io.legado.app.help.book.isImage
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.glide.ImageLoader
import io.legado.app.help.http.addHeaders
import io.legado.app.help.http.newCallResponse
import io.legado.app.help.http.newCallResponseBody
import io.legado.app.help.http.okHttpClient
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.getPrimaryTextColor
import io.legado.app.lib.theme.getSecondaryTextColor
import io.legado.app.model.ReadBook
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.FileDoc
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.SvgUtils
import io.legado.app.utils.compress.ZipUtils
import io.legado.app.utils.createFileIfNotExist
import io.legado.app.utils.createFileReplace
import io.legado.app.utils.createFolderReplace
import io.legado.app.utils.delete
import io.legado.app.utils.dpToPx
import io.legado.app.utils.externalCache
import io.legado.app.utils.externalFiles
import io.legado.app.utils.find
import io.legado.app.utils.getFile
import io.legado.app.utils.inputStream
import io.legado.app.utils.longToast
import io.legado.app.utils.openInputStream
import io.legado.app.utils.openOutputStream
import io.legado.app.utils.outputStream
import io.legado.app.utils.postEvent
import io.legado.app.utils.printOnDebug
import io.legado.app.utils.readBytes
import io.legado.app.utils.readUri
import io.legado.app.utils.stackTraceStr
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.launch
import splitties.init.appCtx
import java.io.FileOutputStream
import kotlin.math.roundToInt

class BgTextConfigDialog : BaseDialogFragment() {

    companion object {
        const val TEXT_COLOR = 121
        const val BG_COLOR = 122
        const val TEXT_ACCENT_COLOR = 123
        const val REVIEW_ICON_COLOR = 124
        const val UNDERLINE_COLOR = 125
    }

    private val configFileName = "readConfig.zip"
    private var primaryTextColor = 0
    private var secondaryTextColor = 0
    private val importFormNet = "网络导入"

    private var nameState by mutableStateOf("")
    private var darkStatusIconState by mutableStateOf(false)
    private var underlineModeState by mutableIntStateOf(0)
    private var underlineWidthProgress by mutableIntStateOf(0)
    private var underlineDistanceProgress by mutableIntStateOf(0)
    private var underlineBodyState by mutableStateOf(true)
    private var underlineTitleState by mutableStateOf(true)
    private var underlineColorIntState by mutableIntStateOf(0)
    private var bgAlphaProgress by mutableIntStateOf(100)

    private val selectBgImage = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            setBgFromUri(uri)
        }
    }
    private val selectExportDir = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            exportConfig(uri)
        }
    }
    private val selectImportDoc = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            if (uri.path == "/$importFormNet") {
                importNetConfigAlert()
            } else {
                importConfig(uri)
            }
        }
    }

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
                    BgTextConfigScreen()
                }
            }
        }
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        (activity as ReadBookActivity).bottomDialog++
        val bg = requireContext().bottomBackground
        val isLight = ColorUtils.isColorLight(bg)
        primaryTextColor = requireContext().getPrimaryTextColor(isLight)
        secondaryTextColor = requireContext().getSecondaryTextColor(isLight)
        initData()
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        ReadBookConfig.save()
        (activity as ReadBookActivity).bottomDialog--
    }

    private fun initData() = with(ReadBookConfig.durConfig) {
        nameState = name.ifBlank { "文字" }
        darkStatusIconState = curStatusIconDark()
        underlineModeState = ReadBookConfig.underlineMode
        underlineWidthProgress =
            (ReadBookConfig.underlineWidth * 2f).roundToInt().coerceIn(0, 20)
        underlineDistanceProgress =
            (ReadBookConfig.underlineDistance * 2f).roundToInt().coerceIn(0, 60)
        underlineBodyState = ReadBookConfig.underlineBodyEnabled
        underlineTitleState = ReadBookConfig.underlineTitleEnabled
        underlineColorIntState = if (ReadBookConfig.underlineColorSet) {
            ReadBookConfig.underlineColor
        } else {
            ReadBookConfig.textColor
        }
        bgAlphaProgress = bgAlpha
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun BgTextConfigScreen() {
        val context = requireContext()
        val bgInt = context.bottomBackground
        val isLight = ColorUtils.isColorLight(bgInt)
        val fgColor = Color(context.getPrimaryTextColor(isLight))
        val secColor = Color(context.getSecondaryTextColor(isLight))
        val accent = Color(context.accentColor)
        val isImageBook = ReadBook.book?.isImage == true
        val bgAssets = remember {
            context.assets.list("bg")?.toList().orEmpty()
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(bgInt))
                .verticalScroll(rememberScrollState())
                .padding(10.dp)
        ) {
            // Top row: Style name, edit, restore
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.style_name),
                    color = fgColor,
                    fontSize = 16.sp
                )
                Text(
                    text = nameState,
                    color = secColor,
                    modifier = Modifier.padding(horizontal = 6.dp)
                )
                Icon(
                    painter = painterResource(R.drawable.ic_edit),
                    contentDescription = stringResource(R.string.edit),
                    tint = secColor,
                    modifier = Modifier
                        .size(20.dp)
                        .clickable { editStyleName() }
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = stringResource(R.string.restore),
                    color = fgColor,
                    modifier = Modifier.clickable {
                        val defaultConfigs = DefaultData.readConfigs
                        val layoutNames = defaultConfigs.map { it.name }
                        context.selector("选择预设布局", layoutNames) { _, i ->
                            if (i >= 0) {
                                ReadBookConfig.durConfig = defaultConfigs[i].copy()
                                initData()
                                postEvent(EventBus.UP_CONFIG, arrayListOf(1, 2, 5, 13))
                            }
                        }
                    }
                )
            }

            // Dark status icon switch
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.dark_status_icon),
                    color = fgColor,
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = darkStatusIconState,
                    onCheckedChange = { isChecked ->
                        darkStatusIconState = isChecked
                        ReadBookConfig.durConfig.setCurStatusIconDark(isChecked)
                        (activity as? ReadBookActivity)?.upSystemUiVisibility()
                    }
                )
            }

            if (!isImageBook) {
                val underlineStyles = listOf(
                    stringResource(R.string.highlight_action_trigger_off),
                    stringResource(R.string.highlight_underline_solid),
                    stringResource(R.string.highlight_underline_dashed),
                    stringResource(R.string.highlight_underline_dotted),
                    stringResource(R.string.highlight_underline_double),
                    stringResource(R.string.highlight_underline_wavy),
                    stringResource(R.string.underline_double_dashed)
                )
                var showUnderlineDropdown by remember { mutableStateOf(false) }

                // Underline style row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.text_underline),
                        color = fgColor,
                        fontSize = 14.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Box {
                        Row(
                            modifier = Modifier
                                .clickable { showUnderlineDropdown = true }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = underlineStyles.getOrElse(underlineModeState) { underlineStyles.first() },
                                color = fgColor,
                                fontSize = 14.sp
                            )
                            Icon(
                                painter = painterResource(R.drawable.ic_arrow_drop_down),
                                contentDescription = null,
                                tint = fgColor,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        DropdownMenu(
                            expanded = showUnderlineDropdown,
                            onDismissRequest = { showUnderlineDropdown = false }
                        ) {
                            underlineStyles.forEachIndexed { index, label ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = {
                                        showUnderlineDropdown = false
                                        underlineModeState = index
                                        ReadBookConfig.underlineMode = index
                                        postEvent(EventBus.UP_CONFIG, arrayListOf(6, 9, 11))
                                    }
                                )
                            }
                        }
                    }
                }

                // Underline actions row: Underline Color button + Body Switch + Title Switch
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StrokeActionButton(
                        text = stringResource(R.string.underline_color),
                        textColor = Color(underlineColorIntState),
                        borderColor = fgColor.copy(alpha = 0.5f),
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 6.dp),
                        onClick = {
                            ColorPickerDialog.newBuilder()
                                .setColor(
                                    ReadBookConfig.underlineColor.takeIf { ReadBookConfig.underlineColorSet }
                                        ?: ReadBookConfig.textColor
                                )
                                .setShowAlphaSlider(true)
                                .setDialogType(ColorPickerDialog.TYPE_CUSTOM)
                                .setDialogId(UNDERLINE_COLOR)
                                .show(requireActivity())
                        }
                    )
                    Row(
                        modifier = Modifier.padding(start = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.underline_body),
                            color = fgColor,
                            fontSize = 14.sp
                        )
                        Switch(
                            checked = underlineBodyState,
                            onCheckedChange = { checked ->
                                underlineBodyState = checked
                                ReadBookConfig.underlineBodyEnabled = checked
                                postEvent(EventBus.UP_CONFIG, arrayListOf(6, 9, 11))
                            }
                        )
                    }
                    Row(
                        modifier = Modifier.padding(start = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.underline_title),
                            color = fgColor,
                            fontSize = 14.sp
                        )
                        Switch(
                            checked = underlineTitleState,
                            onCheckedChange = { checked ->
                                underlineTitleState = checked
                                ReadBookConfig.underlineTitleEnabled = checked
                                postEvent(EventBus.UP_CONFIG, arrayListOf(6, 9, 11))
                            }
                        )
                    }
                }

                // Underline Width (max = 20)
                BgSeekBarRow(
                    titleRes = R.string.underline_width,
                    progress = underlineWidthProgress,
                    max = 20,
                    displayValue = "${underlineWidthProgress / 2f}dp",
                    textColor = fgColor,
                    accentColor = accent,
                    onProgressChanged = { underlineWidthProgress = it },
                    onValueSettled = { progress ->
                        underlineWidthProgress = progress
                        ReadBookConfig.underlineWidth = progress / 2f
                        postEvent(EventBus.UP_CONFIG, arrayListOf(6, 9, 11))
                    }
                )

                // Underline Distance (max = 60)
                BgSeekBarRow(
                    titleRes = R.string.underline_distance,
                    progress = underlineDistanceProgress,
                    max = 60,
                    displayValue = "${(underlineDistanceProgress / 2f)}dp",
                    textColor = fgColor,
                    accentColor = accent,
                    onProgressChanged = { underlineDistanceProgress = it },
                    onValueSettled = { progress ->
                        underlineDistanceProgress = progress
                        ReadBookConfig.underlineDistance = progress / 2f
                        postEvent(EventBus.UP_CONFIG, arrayListOf(6, 9, 11))
                    }
                )
            }

            // Text Color / Bg Color / Text Accent Color row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                StrokeActionButton(
                    text = stringResource(R.string.text_color),
                    textColor = fgColor,
                    borderColor = fgColor.copy(alpha = 0.5f),
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 6.dp),
                    onClick = {
                        ColorPickerDialog.newBuilder()
                            .setColor(ReadBookConfig.durConfig.curTextColor())
                            .setShowAlphaSlider(false)
                            .setDialogType(ColorPickerDialog.TYPE_CUSTOM)
                            .setDialogId(TEXT_COLOR)
                            .show(requireActivity())
                    }
                )
                StrokeActionButton(
                    text = stringResource(R.string.bg_color),
                    textColor = fgColor,
                    borderColor = fgColor.copy(alpha = 0.5f),
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 6.dp),
                    onClick = {
                        val bgColor =
                            if (ReadBookConfig.durConfig.curBgType() == 0) {
                                ReadBookConfig.durConfig.curBgStr().toColorInt()
                            } else {
                                "#015A86".toColorInt()
                            }
                        ColorPickerDialog.newBuilder()
                            .setColor(bgColor)
                            .setShowAlphaSlider(false)
                            .setDialogType(ColorPickerDialog.TYPE_CUSTOM)
                            .setDialogId(BG_COLOR)
                            .show(requireActivity())
                    }
                )
                StrokeActionButton(
                    text = stringResource(R.string.text_accent_color),
                    textColor = fgColor,
                    borderColor = fgColor.copy(alpha = 0.5f),
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 6.dp),
                    onClick = {
                        ColorPickerDialog.newBuilder()
                            .setColor(ReadBookConfig.durConfig.curTextAccentColor())
                            .setShowAlphaSlider(false)
                            .setDialogType(ColorPickerDialog.TYPE_CUSTOM)
                            .setDialogId(TEXT_ACCENT_COLOR)
                            .show(requireActivity())
                    }
                )
            }

            // Review Icon SVG / Size / Color row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                StrokeActionButton(
                    text = stringResource(R.string.review_icon_templates_title),
                    textColor = fgColor,
                    borderColor = fgColor.copy(alpha = 0.5f),
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 6.dp),
                    onClick = { showReviewIconTemplates() }
                )
                StrokeActionButton(
                    text = stringResource(R.string.review_icon_size_title),
                    textColor = fgColor,
                    borderColor = fgColor.copy(alpha = 0.5f),
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 6.dp),
                    onClick = { showReviewIconSizeEditor() }
                )
                StrokeActionButton(
                    text = stringResource(R.string.review_icon_color_title),
                    textColor = fgColor,
                    borderColor = fgColor.copy(alpha = 0.5f),
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 6.dp),
                    onClick = {
                        ColorPickerDialog.newBuilder()
                            .setColor(
                                ReadBookConfig.reviewIconColor.takeIf { it != 0 }
                                    ?: ChapterProvider.reviewPaint.color
                            )
                            .setShowAlphaSlider(false)
                            .setDialogType(ColorPickerDialog.TYPE_CUSTOM)
                            .setDialogId(REVIEW_ICON_COLOR)
                            .show(requireActivity())
                    },
                    onLongClick = {
                        if (ReadBookConfig.reviewIconColor != 0) {
                            ReadBookConfig.reviewIconColor = 0
                            postEvent(EventBus.UP_CONFIG, arrayListOf(8, 9, 11))
                            toastOnUi(R.string.review_icon_color_reset)
                        }
                    }
                )
            }

            // Import / Export / Delete row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = {
                        selectImportDoc.launch {
                            mode = HandleFileContract.FILE
                            title = getString(R.string.import_str)
                            allowExtensions = arrayOf("zip")
                            otherActions = arrayListOf(SelectItem(importFormNet, -1))
                        }
                    },
                    modifier = Modifier
                        .padding(horizontal = 6.dp)
                        .size(36.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_import),
                        contentDescription = stringResource(R.string.import_str),
                        tint = fgColor
                    )
                }
                IconButton(
                    onClick = {
                        selectExportDir.launch {
                            title = getString(R.string.export_str)
                        }
                    },
                    modifier = Modifier
                        .padding(horizontal = 6.dp)
                        .size(36.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_export),
                        contentDescription = stringResource(R.string.export_str),
                        tint = fgColor
                    )
                }
                IconButton(
                    onClick = {
                        if (ReadBookConfig.deleteDur()) {
                            postEvent(EventBus.UP_CONFIG, arrayListOf(1, 2, 5))
                            dismissAllowingStateLoss()
                        } else {
                            toastOnUi("数量已是最少,不能删除.")
                        }
                    },
                    modifier = Modifier
                        .padding(horizontal = 6.dp)
                        .size(36.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_clear_all),
                        contentDescription = stringResource(R.string.delete),
                        tint = fgColor
                    )
                }
            }

            // Background Alpha
            Text(
                text = stringResource(R.string.bg_alpha),
                color = fgColor,
                fontSize = 14.sp,
                modifier = Modifier.padding(horizontal = 6.dp)
            )
            Slider(
                value = bgAlphaProgress.toFloat(),
                onValueChange = {
                    val progress = it.roundToInt().coerceIn(0, 100)
                    bgAlphaProgress = progress
                    ReadBookConfig.bgAlpha = progress
                    postEvent(EventBus.UP_CONFIG, arrayListOf(3))
                },
                onValueChangeFinished = {
                    postEvent(EventBus.UP_CONFIG, arrayListOf(3))
                },
                valueRange = 0f..100f,
                colors = SliderDefaults.colors(
                    thumbColor = accent,
                    activeTrackColor = accent
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(6.dp)
            )

            // Background Image LazyRow
            Text(
                text = stringResource(R.string.bg_image),
                color = fgColor,
                fontSize = 14.sp,
                modifier = Modifier.padding(horizontal = 6.dp)
            )
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(100.dp)
                    .padding(6.dp),
                contentPadding = PaddingValues(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item(key = "select_bg_image") {
                    Column(
                        modifier = Modifier
                            .width(64.dp)
                            .clickable {
                                selectBgImage.launch {
                                    mode = HandleFileContract.IMAGE
                                }
                            }
                            .padding(5.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_image),
                            contentDescription = stringResource(R.string.select_image),
                            tint = fgColor,
                            modifier = Modifier.size(60.dp)
                        )
                        Text(
                            text = stringResource(R.string.select_image),
                            color = secColor,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center
                        )
                    }
                }
                items(bgAssets, key = { it }) { item ->
                    val displayName = item.substringBeforeLast(".")
                    Column(
                        modifier = Modifier
                            .width(64.dp)
                            .clickable {
                                item.let {
                                    ReadBookConfig.durConfig.setCurBg(1, it)
                                    postEvent(EventBus.UP_CONFIG, arrayListOf(1))
                                }
                            }
                            .padding(5.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        AndroidView(
                            factory = { ctx ->
                                ImageView(ctx).apply {
                                    scaleType = ImageView.ScaleType.CENTER_CROP
                                }
                            },
                            update = { ivBg ->
                                ImageLoader.load(
                                    context,
                                    "file:///android_asset/bg/$item".toUri()
                                )
                                    .centerCrop()
                                    .into(ivBg)
                            },
                            modifier = Modifier.size(60.dp)
                        )
                        Text(
                            text = displayName,
                            color = secColor,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun StrokeActionButton(
        text: String,
        textColor: Color,
        borderColor: Color,
        modifier: Modifier = Modifier,
        onClick: () -> Unit,
        onLongClick: (() -> Unit)? = null
    ) {
        val shape = RoundedCornerShape(4.dp)
        Box(
            modifier = modifier
                .clip(shape)
                .border(1.dp, borderColor, shape)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick
                )
                .padding(6.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                color = textColor,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }

    @Composable
    private fun BgSeekBarRow(
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
                .padding(horizontal = 6.dp, vertical = 2.dp)
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

    private fun editStyleName() {
        alert(R.string.style_name) {
            val editView = EditText(requireContext()).apply {
                hint = "name"
                setText(ReadBookConfig.durConfig.name)
            }
            val container = FrameLayout(requireContext()).apply {
                val pad = 16.dpToPx()
                setPadding(pad, pad / 2, pad, 0)
                addView(editView)
            }
            customView { container }
            okButton {
                editView.text?.toString()?.let {
                    nameState = it.ifBlank { "文字" }
                    ReadBookConfig.durConfig.name = it
                }
            }
            cancelButton()
        }
    }

    private fun showReviewIconSizeEditor() {
        val editView = EditText(requireContext()).apply {
            hint = getString(R.string.review_icon_size_hint)
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            setText(ReadBookConfig.reviewIconScale.toString())
            setSelection(text?.length ?: 0)
        }
        val container = FrameLayout(requireContext()).apply {
            val pad = 16.dpToPx()
            setPadding(pad, pad / 2, pad, 0)
            addView(editView)
        }
        val dialog = alert(R.string.review_icon_size_title) {
            customView { container }
            okButton()
            cancelButton()
        }
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            val scale = editView.text?.toString()?.trim()?.toIntOrNull()
            if (scale == null || scale !in 50..200) {
                toastOnUi(R.string.review_icon_size_invalid)
                return@setOnClickListener
            }
            if (scale != ReadBookConfig.reviewIconScale) {
                ReadBookConfig.reviewIconScale = scale
                notifyReviewIconStyleChanged()
            }
            dialog.dismiss()
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    private fun showReviewIconTemplates() {
        val templates = ReadBookConfig.durConfig.reviewIconSvgTemplates
        val visibleRows = ((templates.size.coerceAtLeast(1) + 2) / 3).coerceAtMost(2)
        val previewSize = 48.dpToPx()
        lateinit var dialog: AlertDialog
        val composeView = ComposeView(requireContext()).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (visibleRows * 88).dpToPx()
            )
            setContent {
                LegadoTheme {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height((visibleRows * 88).dp)
                    ) {
                        items(templates) { item ->
                            val previewSvg = item.svg.replace("{{count}}", "88")
                            val displayName = item.name.ifBlank {
                                requireContext().getString(R.string.review_icon_template_unnamed)
                            }
                            val bitmap = remember(previewSvg) {
                                SvgUtils.createBitmapFromSvgText(previewSvg, previewSize, previewSize)
                            }
                            Column(
                                modifier = Modifier
                                    .padding(5.dp)
                                    .combinedClickable(
                                        onClick = {
                                            if (applyReviewIconTemplate(item)) dialog.dismiss()
                                        },
                                        onLongClick = {
                                            dialog.dismiss()
                                            confirmDeleteReviewIconTemplate(item)
                                        }
                                    ),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                if (bitmap != null) {
                                    Image(
                                        bitmap = bitmap.asImageBitmap(),
                                        contentDescription = displayName,
                                        modifier = Modifier
                                            .size(60.dp)
                                            .semantics { contentDescription = displayName }
                                    )
                                } else {
                                    Spacer(modifier = Modifier.size(60.dp))
                                }
                                Text(
                                    text = displayName,
                                    color = Color(secondaryTextColor),
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }
            }
        }
        dialog = alert(R.string.review_icon_templates_title) {
            customView { composeView }
            positiveButton(R.string.review_icon_template_save)
            neutralButton(R.string.review_icon_svg_edit)
            cancelButton()
        }
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            dialog.dismiss()
            showReviewIconTemplateNameEditor()
        }
        dialog.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener {
            dialog.dismiss()
            showReviewIconSvgEditor()
        }
    }

    private fun showReviewIconSvgEditor() {
        val editView = EditText(requireContext()).apply {
            hint = getString(R.string.review_icon_svg_hint)
            setSingleLine(false)
            maxLines = 8
            setText(ReadBookConfig.reviewIconSvg)
            setSelection(text?.length ?: 0)
        }
        val container = FrameLayout(requireContext()).apply {
            val pad = 16.dpToPx()
            setPadding(pad, pad / 2, pad, 0)
            addView(editView)
        }
        val dialog = alert(R.string.review_icon_svg_title) {
            customView { container }
            okButton()
            cancelButton()
        }
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            val newSvg = editView.text?.toString().orEmpty().trim()
            if (newSvg.isNotBlank() && !isValidReviewIconSvg(newSvg)) {
                toastOnUi(R.string.review_icon_svg_invalid)
                return@setOnClickListener
            }
            if (newSvg != ReadBookConfig.reviewIconSvg) {
                ReadBookConfig.reviewIconSvg = newSvg
                notifyReviewIconStyleChanged()
            }
            dialog.dismiss()
        }
    }

    private fun showReviewIconTemplateNameEditor(
        template: ReadBookConfig.ReviewIconSvgTemplate? = null
    ) {
        val svg = template?.svg?.trim() ?: ReadBookConfig.reviewIconSvg.trim()
        if (svg.isBlank() || (template == null && !isValidReviewIconSvg(svg))) {
            toastOnUi(R.string.review_icon_template_no_svg)
            return
        }
        val existing = template ?: ReadBookConfig.durConfig.reviewIconSvgTemplates
            .firstOrNull { it.svg.trim() == svg }
        val defaultName = existing?.name ?: getString(
            R.string.review_icon_template_default_name,
            ReadBookConfig.durConfig.reviewIconSvgTemplates.size + 1
        )
        val editView = EditText(requireContext()).apply {
            setHint(R.string.review_icon_template_name)
            setSingleLine(true)
            setText(defaultName)
            setSelection(text?.length ?: 0)
        }
        val container = FrameLayout(requireContext()).apply {
            val pad = 16.dpToPx()
            setPadding(pad, pad / 2, pad, 0)
            addView(editView)
        }
        val dialog = alert(R.string.review_icon_template_name) {
            customView { container }
            okButton()
            cancelButton()
        }
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            val name = editView.text?.toString().orEmpty().trim()
            if (name.isEmpty()) {
                toastOnUi(R.string.review_icon_template_name_empty)
                return@setOnClickListener
            }
            ReadBookConfig.durConfig.putReviewIconSvgTemplate(name, svg)
            toastOnUi(
                if (template == null) R.string.review_icon_template_saved
                else R.string.review_icon_template_renamed
            )
            dialog.dismiss()
            showReviewIconTemplates()
        }
    }

    private fun applyReviewIconTemplate(
        template: ReadBookConfig.ReviewIconSvgTemplate
    ): Boolean {
        val svg = template.svg.trim()
        if (!isValidReviewIconSvg(svg)) {
            toastOnUi(R.string.review_icon_svg_invalid)
            return false
        }
        if (svg != ReadBookConfig.reviewIconSvg) {
            ReadBookConfig.reviewIconSvg = svg
            notifyReviewIconStyleChanged()
        }
        return true
    }

    private fun confirmDeleteReviewIconTemplate(
        template: ReadBookConfig.ReviewIconSvgTemplate
    ) {
        val name = template.name.ifBlank { getString(R.string.review_icon_template_unnamed) }
        alert(name, getString(R.string.sure_del)) {
            yesButton { dialog ->
                dialog.dismiss()
                ReadBookConfig.durConfig.removeReviewIconSvgTemplate(template.svg)
                toastOnUi(R.string.review_icon_template_deleted)
                showReviewIconTemplates()
            }
            neutralButton(R.string.edit) { dialog ->
                dialog.dismiss()
                showReviewIconTemplateNameEditor(template)
            }
            noButton()
        }
    }

    private fun notifyReviewIconStyleChanged() {
        ChapterProvider.clearReviewIconCache()
        ChapterProvider.refreshReviewColumnsForStyleChange()
        postEvent(EventBus.UP_CONFIG, arrayListOf(9, 11))
    }

    private fun isValidReviewIconSvg(svg: String): Boolean {
        val resolvedSvg = svg.replace("{{count}}", "88")
        val aspectRatio = SvgUtils.getAspectRatioFromSvgText(resolvedSvg)
            ?: return false
        if (!ChapterProvider.isReviewIconAspectRatioSupported(aspectRatio)) return false
        val bitmap = SvgUtils.createBitmapFromSvgText(resolvedSvg, 48, 48)
        return bitmap?.let {
            it.recycle()
            true
        } ?: false
    }

    private fun exportConfig(uri: Uri) {
        val exportFileName = if (ReadBookConfig.config.name.isBlank()) {
            configFileName
        } else {
            "${ReadBookConfig.config.name}.zip"
        }
        execute {
            val exportFiles = arrayListOf<java.io.File>()
            val configDir = requireContext().externalCache.getFile("readConfig")
            configDir.createFolderReplace()
            val configFile = configDir.getFile("readConfig.json")
            configFile.createFileReplace()
            val config = ReadBookConfig.getExportConfig()
            fun exportFont(fontPath: String, archiveName: String? = null): String? {
                if (fontPath.isEmpty()) return null
                val fontDoc = FileDoc.fromFile(fontPath)
                val fontName = archiveName ?: fontDoc.name
                val fontInputStream = fontDoc.openInputStream().getOrNull()
                return fontInputStream?.use {
                    val fontExportFile = FileUtils.createFileIfNotExist(configDir, fontName)
                    fontExportFile.outputStream().use { out ->
                        it.copyTo(out)
                    }
                    exportFiles.add(fontExportFile)
                    fontName
                }
            }

            val textFontPath = ReadBookConfig.textFont
            val exportedTextFont = exportFont(textFontPath)
            config.textFont = exportedTextFont.orEmpty()

            val titleFontPath = ReadBookConfig.titleFont
            config.titleFont = if (titleFontPath == textFontPath && exportedTextFont != null) {
                exportedTextFont
            } else if (titleFontPath.isNotEmpty()) {
                val titleFontName = FileDoc.fromFile(titleFontPath).name.let {
                    if (it == exportedTextFont) "title_$it" else it
                }
                exportFont(titleFontPath, titleFontName).orEmpty()
            } else ""
            configFile.writeText(GSON.toJson(config))
            exportFiles.add(configFile)
            repeat(3) {
                val path = ReadBookConfig.durConfig.getBgPath(it) ?: return@repeat
                val bgExportFile = copyBgImage(path, configDir) ?: return@repeat
                exportFiles.add(bgExportFile)
            }
            val configZipPath = FileUtils.getPath(requireContext().externalCache, configFileName)
            if (ZipUtils.zipFiles(exportFiles, java.io.File(configZipPath))) {
                val exportDir = FileDoc.fromDir(uri)
                exportDir.find(exportFileName)?.delete()
                val exportFileDoc = exportDir.createFileIfNotExist(exportFileName)
                exportFileDoc.openOutputStream().getOrThrow().use { out ->
                    java.io.File(configZipPath).inputStream().use {
                        it.copyTo(out)
                    }
                }
            }
        }.onSuccess {
            toastOnUi("导出成功, 文件名为 $exportFileName")
        }.onError {
            it.printOnDebug()
            AppLog.put("导出失败:${it.localizedMessage}", it)
            longToast("导出失败:${it.localizedMessage}")
        }
    }

    private fun copyBgImage(path: String, configDir: java.io.File): java.io.File? {
        val bgName = FileUtils.getName(path)
        val bgFile = java.io.File(path)
        if (bgFile.exists()) {
            val bgExportFile = java.io.File(FileUtils.getPath(configDir, bgName))
            if (!bgExportFile.exists()) {
                bgFile.copyTo(bgExportFile)
                return bgExportFile
            }
        }
        return null
    }

    @SuppressLint("InflateParams")
    private fun importNetConfigAlert() {
        alert("输入地址") {
            val editView = EditText(requireContext())
            val container = FrameLayout(requireContext()).apply {
                val pad = 16.dpToPx()
                setPadding(pad, pad / 2, pad, 0)
                addView(editView)
            }
            customView { container }
            okButton {
                editView.text?.toString()?.let { url ->
                    importNetConfig(url)
                }
            }
            cancelButton()
        }
    }

    private fun importNetConfig(url: String) {
        execute {
            okHttpClient.newCallResponseBody {
                url(url)
            }.bytes().let {
                importConfig(it)
            }
        }.onError {
            longToast(it.stackTraceStr)
        }
    }

    private fun importConfig(uri: Uri) {
        execute {
            importConfig(uri.readBytes(requireContext()))
        }.onError {
            it.printOnDebug()
            longToast("导入失败:${it.localizedMessage}")
        }
    }

    private fun importConfig(byteArray: ByteArray) {
        execute {
            ReadBookConfig.import(byteArray)
        }.onSuccess {
            ReadBookConfig.durConfig = it
            initData()
            postEvent(EventBus.UP_CONFIG, arrayListOf(1, 2, 5))
            toastOnUi("导入成功")
        }.onError {
            it.printOnDebug()
            longToast("导入失败:${it.localizedMessage}")
        }
    }

    private fun setBgFromUri(uri: Uri) {
        if (uri.scheme?.lowercase() in listOf("http", "https")) {
            lifecycleScope.launch {
                kotlin.runCatching {
                    appCtx.toastOnUi("下载图片中...")
                    val analyzeUrl = AnalyzeUrl(uri.toString())
                    val url = analyzeUrl.urlNoQuery
                    var file = requireContext().externalFiles
                    val res = okHttpClient.newCallResponse(0) {
                        addHeaders(analyzeUrl.headerMap)
                        url(url)
                    }
                    val contentType = res.header("Content-Type") ?: "image/jpeg"
                    val imageType = when {
                        contentType.contains("png", ignoreCase = true) -> "png"
                        contentType.contains("gif", ignoreCase = true) -> "gif"
                        contentType.contains("webp", ignoreCase = true) -> "webp"
                        else -> "jpg"
                    }
                    val suffix = if (url.contains(".9.png", true)) {
                        ".9.png"
                    } else {
                        ".$imageType"
                    }
                    val fileName = MD5Utils.md5Encode(url) + suffix
                    file = FileUtils.createFileIfNotExist(file, "bg", fileName)
                    res.body.byteStream().use { inputStream ->
                        FileOutputStream(file).use { outputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }
                    ReadBookConfig.durConfig.setCurBg(2, fileName)
                    postEvent(EventBus.UP_CONFIG, arrayListOf(1))
                }.onSuccess {
                    appCtx.toastOnUi("设定成功")
                }.onFailure {
                    appCtx.toastOnUi(it.localizedMessage)
                }
            }
            return
        }
        readUri(uri) { fileDoc, inputStream ->
            kotlin.runCatching {
                var file = requireContext().externalFiles
                val suffix = if (fileDoc.name.contains(".9.png", true)) {
                    ".9.png"
                } else {
                    "." + fileDoc.name.substringAfterLast(".")
                }
                val fileName = uri.inputStream(requireContext()).getOrThrow().use {
                    MD5Utils.md5Encode(it) + suffix
                }
                file = FileUtils.createFileIfNotExist(file, "bg", fileName)
                FileOutputStream(file).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
                ReadBookConfig.durConfig.setCurBg(2, fileName)
                postEvent(EventBus.UP_CONFIG, arrayListOf(1))
            }.onFailure {
                appCtx.toastOnUi(it.localizedMessage)
            }
        }
    }
}
