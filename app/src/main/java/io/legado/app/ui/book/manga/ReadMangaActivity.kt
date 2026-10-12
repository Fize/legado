package io.legado.app.ui.book.manga

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import androidx.activity.addCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.core.view.doOnNextLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.integration.recyclerview.RecyclerViewPreloader
import com.bumptech.glide.request.target.Target.SIZE_ORIGINAL
import com.bumptech.glide.util.FixedPreloadSizeProvider
import io.legado.app.BuildConfig
import io.legado.app.R
import io.legado.app.base.BaseComposeActivity
import io.legado.app.constant.AppConst
import io.legado.app.constant.BookType
import io.legado.app.constant.EventBus
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookProgress
import io.legado.app.data.entities.BookSource
import io.legado.app.help.book.isImage
import io.legado.app.help.book.isPdf
import io.legado.app.help.book.removeType
import io.legado.app.help.config.AppConfig
import io.legado.app.help.source.getSourceType
import io.legado.app.help.storage.Backup
import io.legado.app.lib.dialogs.alert
import io.legado.app.model.ReadManga
import io.legado.app.model.localBook.PdfFile
import io.legado.app.receiver.NetworkChangedListener
import io.legado.app.ui.book.changesource.ChangeBookSourceDialog
import io.legado.app.ui.book.info.BookInfoActivity
import io.legado.app.ui.book.manga.config.MangaColorFilterConfig
import io.legado.app.ui.book.manga.config.MangaColorFilterDialog
import io.legado.app.ui.book.manga.config.MangaEpaperDialog
import io.legado.app.ui.book.manga.config.MangaFooterConfig
import io.legado.app.ui.book.manga.config.MangaFooterSettingDialog
import io.legado.app.ui.book.manga.entities.BaseMangaPage
import io.legado.app.ui.book.manga.entities.MangaPage
import io.legado.app.ui.book.manga.recyclerview.MangaAdapter
import io.legado.app.ui.book.manga.recyclerview.MangaLayoutManager
import io.legado.app.ui.book.manga.recyclerview.ScrollTimer
import io.legado.app.ui.book.manga.recyclerview.WebtoonFrame
import io.legado.app.ui.book.manga.recyclerview.WebtoonRecyclerView
import io.legado.app.ui.book.read.ReadBookActivity.Companion.RESULT_DELETED
import io.legado.app.ui.book.read.showBookDownloadDialog
import io.legado.app.ui.book.toc.TocActivityResult
import io.legado.app.ui.browser.WebViewActivity
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.widget.ReaderInfoBarView
import io.legado.app.ui.widget.number.NumberPickerDialog
import io.legado.app.ui.widget.recycler.LoadMoreView
import io.legado.app.utils.ACache
import io.legado.app.utils.GSON
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.StartActivityContract
import io.legado.app.utils.dpToPx
import io.legado.app.utils.fastBinarySearch
import io.legado.app.utils.findCenterViewPosition
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getCompatColor
import io.legado.app.utils.gone
import io.legado.app.utils.observeEvent
import io.legado.app.utils.openUrl
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.toggleSystemBar
import io.legado.app.utils.visible
import kotlinx.coroutines.launch
import java.text.DecimalFormat
import kotlin.math.ceil
import kotlin.math.roundToInt

class ReadMangaActivity : BaseComposeActivity(),
    ReadManga.Callback, ChangeBookSourceDialog.CallBack,
    MangaColorFilterDialog.Callback, ScrollTimer.ScrollCallback, MangaEpaperDialog.Callback {

    val viewModel by viewModels<ReadMangaViewModel>()

    internal val recyclerView: WebtoonRecyclerView by lazy {
        WebtoonRecyclerView(this).apply {
            descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
            isFocusableInTouchMode = true
        }
    }

    internal val webtoonFrame: WebtoonFrame by lazy {
        WebtoonFrame(this).apply {
            addView(
                recyclerView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }
    }

    private val mLayoutManager by lazy {
        MangaLayoutManager(this)
    }
    private val mAdapter: MangaAdapter by lazy {
        MangaAdapter(this).apply {
            onPageBackgroundColorChanged = { color ->
                webtoonFrame.setPageBackgroundColor(color)
            }
        }
    }

    private val isDoublePageMode: Boolean
        get() = io.legado.app.utils.shouldUseMangaDoublePage(
            resources.configuration.screenWidthDp,
            resources.configuration.screenHeightDp,
            AppConfig.enableMangaDoublePage,
        )

    private val isHorizontalMode: Boolean
        get() = AppConfig.enableMangaHorizontalScroll || isDoublePageMode

    private val mPagerSnapHelper: PagerSnapHelper by lazy {
        object : PagerSnapHelper() {
            override fun calculateDistanceToFinalSnap(
                layoutManager: RecyclerView.LayoutManager,
                targetView: View
            ): IntArray? {
                if (isDoublePageMode && layoutManager.canScrollHorizontally() &&
                    targetView.width in 1..(layoutManager.width * 3 / 4)
                ) {
                    val pos = recyclerView.getChildAdapterPosition(targetView)
                    val item = mAdapter.getItem(pos) as? MangaPage
                    if (item != null) {
                        val slot = io.legado.app.utils.resolveMangaSpreadSlot(
                            pageIndex = item.index,
                            imageCount = item.imageCount,
                            isDoublePage = true,
                            rightToLeft = mLayoutManager.reverseLayout,
                        )
                        val dx = when (slot) {
                            io.legado.app.utils.MangaSpreadSlot.LEFT ->
                                targetView.left - layoutManager.paddingLeft
                            io.legado.app.utils.MangaSpreadSlot.RIGHT ->
                                targetView.right - (layoutManager.width - layoutManager.paddingRight)
                            io.legado.app.utils.MangaSpreadSlot.SINGLE ->
                                super.calculateDistanceToFinalSnap(layoutManager, targetView)?.get(0) ?: 0
                        }
                        return intArrayOf(dx, 0)
                    }
                }
                return super.calculateDistanceToFinalSnap(layoutManager, targetView)
            }
        }
    }

    private lateinit var mMangaFooterConfig: MangaFooterConfig
    private val mLabelBuilder by lazy { StringBuilder() }
    private var mRecyclerViewPreloader: RecyclerViewPreloader<Any>? = null

    private val networkChangedListener by lazy {
        NetworkChangedListener(this)
    }

    private var justInitData: Boolean = false
    private var contentUpdateId = 0
    private var syncDialog: AlertDialog? = null
    private val mScrollTimer by lazy {
        ScrollTimer(this, recyclerView, lifecycleScope).apply {
            setSpeed(AppConfig.mangaAutoPageSpeed)
        }
    }
    internal var enableAutoScrollPage by mutableStateOf(false)
    internal var enableAutoScroll by mutableStateOf(false)
    private val mLinearInterpolator by lazy {
        LinearInterpolator()
    }

    internal val loadMoreView by lazy {
        LoadMoreView(this).apply {
            setBackgroundColor(getCompatColor(R.color.book_ant_10))
            setLoadingColor(R.color.white)
            setLoadingTextColor(R.color.white)
        }
    }

    // Compose UI states
    internal var loadingViewVisible by mutableStateOf(true)
    private var loadingErrorMessage by mutableStateOf<String?>(null)
    private var canRetryLoading by mutableStateOf(false)
    internal var isMenuVisible by mutableStateOf(false)
    internal var canShowMenu = false
    internal var isRightToLeftMenu by mutableStateOf(false)

    private var bookTitleState by mutableStateOf("")
    private var chapterNameState by mutableStateOf("")
    private var chapterUrlState by mutableStateOf("")
    private var sourceNameState by mutableStateOf("")
    private var seekPageValue by mutableIntStateOf(0)
    private var seekPageCount by mutableIntStateOf(1)

    private var infoBarVisible by mutableStateOf(false)
    private var infoBarText by mutableStateOf("")
    private var infoBarAlignCenter by mutableStateOf(false)

    // Menu option states
    private var preDownloadNumState by mutableIntStateOf(AppConfig.mangaPreDownloadNum)
    private var disableMangaScaleState by mutableStateOf(AppConfig.disableMangaScale)
    private var mangaLongClickSaveImageState by mutableStateOf(AppConfig.mangaLongClickSaveImage)
    private var disableClickScrollState by mutableStateOf(AppConfig.disableClickScroll)
    private var autoPageSpeedState by mutableIntStateOf(AppConfig.mangaAutoPageSpeed)
    private var enableHorizontalScrollState by mutableStateOf(AppConfig.enableMangaHorizontalScroll)
    private var enableDoublePageState by mutableStateOf(AppConfig.enableMangaDoublePage)
    private var mangaRightToLeftState by mutableStateOf(AppConfig.mangaRightToLeft)
    private var disableHorizontalPageSnapState by mutableStateOf(AppConfig.disableHorizontalPageSnap)
    private var disableMangaPageAnimState by mutableStateOf(AppConfig.disableMangaPageAnim)
    private var hideMangaTitleState by mutableStateOf(AppConfig.hideMangaTitle)
    private var enableMangaEInkState by mutableStateOf(AppConfig.enableMangaEInk)
    private var enableMangaGrayState by mutableStateOf(AppConfig.enableMangaGray)
    private var isHorizontalModeState by mutableStateOf(false)

    // 打开目录返回选择章节返回结果
    private val tocActivity = registerForActivityResult(TocActivityResult()) {
        it?.let {
            val pdfPage = it[TocActivityResult.PDF_PAGE_INDEX] as Int
            val position = if (ReadManga.book?.isPdf == true && pdfPage >= 0) {
                pdfPage % PdfFile.PAGE_SIZE
            } else it[1] as Int
            viewModel.openChapter(it[0] as Int, position)
        }
    }
    private val bookInfoActivity =
        registerForActivityResult(StartActivityContract(BookInfoActivity::class.java)) {
            if (it.resultCode == RESULT_OK) {
                setResult(RESULT_DELETED)
                super.finish()
            } else {
                ReadManga.loadOrUpContent()
            }
        }
    private val selectImageDir = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            ACache.get().put(AppConst.imagePathKey, uri.toString())
            viewModel.saveImage(it.value, uri)
        }
    }

    private val df by lazy {
        DecimalFormat("0.0%")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        upLayoutInDisplayCutoutMode()
        super.onCreate(savedInstanceState)
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        onBackPressedDispatcher.addCallback(this) { finish() }
        ReadManga.register(this)
        upSystemUiVisibility(false)
        syncMenuStatesFromConfig()
        initRecyclerView()
        mAdapter.addFooterView {
            loadMoreView
        }
        loadMoreView.setOnClickListener {
            if (!loadMoreView.isLoading && ReadManga.hasNextChapter) {
                loadMoreView.startLoad()
                ReadManga.loadOrUpContent()
            }
        }
        loadMoreView.gone()
        mMangaFooterConfig =
            GSON.fromJsonObject<MangaFooterConfig>(AppConfig.mangaFooterConfig).getOrNull()
                ?: MangaFooterConfig()
        super.onActivityCreated(savedInstanceState)
    }

    @Composable
    override fun Content() {
        var showOverflowMenu by remember { mutableStateOf(false) }

        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                factory = { webtoonFrame },
                modifier = Modifier.fillMaxSize()
            )

            if (infoBarVisible && infoBarText.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp)
                        .padding(bottom = 10.dp),
                    contentAlignment = if (infoBarAlignCenter) Alignment.Center else Alignment.CenterStart
                ) {
                    Text(
                        text = infoBarText,
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        style = MaterialTheme.typography.labelSmall.copy(
                            shadow = androidx.compose.ui.graphics.Shadow(
                                color = Color.Black,
                                blurRadius = 4f
                            )
                        )
                    )
                }
            }

            if (isMenuVisible) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { showMenu(false) }
                )
            }

            AnimatedVisibility(
                visible = isMenuVisible,
                enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter)
            ) {
                MangaTopMenuBar(
                    bookTitle = bookTitleState,
                    chapterName = chapterNameState,
                    chapterUrl = chapterUrlState,
                    sourceName = sourceNameState,
                    showAddition = AppConfig.showReadTitleBarAddition,
                    showOverflowMenu = showOverflowMenu,
                    onShowOverflowMenuChange = { showOverflowMenu = it },
                    onBack = { finish() },
                    onTitleClick = { openBookInfoActivity() },
                    onChapterClick = { openChapterInBrowserOrWebView() },
                    onChapterLongClick = { promptChapterOpenBrowserMode() },
                    onChangeSource = {
                        showMenu(false)
                        ReadManga.book?.let {
                            showDialogFragment(ChangeBookSourceDialog(it.name, it.author))
                        }
                    },
                    onRefresh = {
                        showLoading()
                        ReadManga.book?.let {
                            viewModel.refreshContentDur(it)
                        }
                    },
                    onOpenCatalog = {
                        ReadManga.book?.let {
                            tocActivity.launch(it.bookUrl)
                        }
                    },
                    overflowMenuContent = {
                        MangaOverflowMenuItems(
                            onDismiss = { showOverflowMenu = false }
                        )
                    }
                )
            }

            AnimatedVisibility(
                visible = isMenuVisible,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                MangaBottomMenuBar(
                    isRightToLeft = isRightToLeftMenu,
                    pageValue = seekPageValue,
                    pageCount = seekPageCount,
                    onPrevChapter = { ReadManga.moveToPrevChapter(true) },
                    onNextChapter = { ReadManga.moveToNextChapter(true) },
                    onSeekPage = { skipToPage(it) }
                )
            }

            if (loadingViewVisible) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val errorMsg = loadingErrorMessage
                    if (errorMsg == null) {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            if (!AppConfig.isEInkMode) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(36.dp),
                                    strokeWidth = 3.dp,
                                    strokeCap = StrokeCap.Round
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                            }
                            Text(
                                text = stringResource(R.string.loading),
                                color = MaterialTheme.colorScheme.onBackground
                            )
                        }
                    } else {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = errorMsg,
                                color = MaterialTheme.colorScheme.onBackground,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 24.dp)
                            )
                            if (canRetryLoading) {
                                TextButton(
                                    onClick = {
                                        loadingErrorMessage = null
                                        canRetryLoading = false
                                        ReadManga.loadOrUpContent()
                                    },
                                    modifier = Modifier.padding(16.dp)
                                ) {
                                    Text(
                                        text = "重新加载",
                                        fontSize = 18.sp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
    @Composable
    private fun MangaTopMenuBar(
        bookTitle: String,
        chapterName: String,
        chapterUrl: String,
        sourceName: String,
        showAddition: Boolean,
        showOverflowMenu: Boolean,
        onShowOverflowMenuChange: (Boolean) -> Unit,
        onBack: () -> Unit,
        onTitleClick: () -> Unit,
        onChapterClick: () -> Unit,
        onChapterLongClick: () -> Unit,
        onChangeSource: () -> Unit,
        onRefresh: () -> Unit,
        onOpenCatalog: () -> Unit,
        overflowMenuContent: @Composable () -> Unit,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
            ) {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                        }
                    },
                    title = {
                        Text(
                            text = bookTitle,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable(onClick = onTitleClick)
                        )
                    },
                    actions = {
                        IconButton(onClick = onChangeSource) {
                            Icon(
                                Icons.Default.SwapHoriz,
                                contentDescription = stringResource(R.string.change_origin)
                            )
                        }
                        IconButton(onClick = onRefresh) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = stringResource(R.string.refresh)
                            )
                        }
                        IconButton(onClick = onOpenCatalog) {
                            Icon(
                                Icons.AutoMirrored.Filled.List,
                                contentDescription = stringResource(R.string.chapter_list)
                            )
                        }
                        Box {
                            IconButton(onClick = { onShowOverflowMenuChange(true) }) {
                                Icon(Icons.Default.MoreVert, contentDescription = null)
                            }
                            DropdownMenu(
                                expanded = showOverflowMenu,
                                onDismissRequest = { onShowOverflowMenuChange(false) }
                            ) {
                                overflowMenuContent()
                            }
                        }
                    }
                )
                if (showAddition && (chapterName.isNotEmpty() || chapterUrl.isNotEmpty() || sourceName.isNotEmpty())) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .combinedClickable(
                                    onClick = onChapterClick,
                                    onLongClick = onChapterLongClick
                                )
                        ) {
                            if (chapterName.isNotEmpty()) {
                                Text(
                                    text = chapterName,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (chapterUrl.isNotEmpty()) {
                                Text(
                                    text = chapterUrl,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        if (sourceName.isNotEmpty()) {
                            Surface(
                                color = MaterialTheme.colorScheme.primary,
                                shape = RoundedCornerShape(2.dp),
                                modifier = Modifier.padding(start = 8.dp)
                            ) {
                                Text(
                                    text = sourceName,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun MangaOverflowMenuItems(
        onDismiss: () -> Unit,
    ) {
        // R.id.menu_download ->
        DropdownMenuItem(
            text = { Text(stringResource(R.string.offline_cache)) },
            onClick = {
                onDismiss()
                ReadManga.book?.let { showBookDownloadDialog(it) }
            }
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.pre_download_m, preDownloadNumState)) },
            onClick = {
                onDismiss()
                showNumberPickerDialog(
                    0,
                    getString(R.string.pre_download),
                    AppConfig.mangaPreDownloadNum
                ) {
                    AppConfig.mangaPreDownloadNum = it
                    preDownloadNumState = it
                    setRecyclerViewPreloader(it)
                }
            }
        )
        MangaCheckableMenuItem(
            title = stringResource(R.string.disable_manga_scale),
            checked = disableMangaScaleState,
            onClick = {
                toggleDisableMangaScale()
            }
        )
        // @+id/menu_manga_long_click_save_image
        MangaCheckableMenuItem(
            title = stringResource(R.string.manga_long_click_save_image),
            checked = mangaLongClickSaveImageState,
            onClick = {
                toggleMangaLongClickSaveImage()
            }
        )
        MangaCheckableMenuItem(
            title = stringResource(R.string.disable_manga_click_scroll),
            checked = disableClickScrollState,
            onClick = {
                toggleDisableClickScroll()
            }
        )
        MangaCheckableMenuItem(
            title = stringResource(R.string.enable_auto_page_scroll),
            checked = enableAutoScrollPage,
            onClick = {
                setAutoPageEnabled(!enableAutoScrollPage)
            }
        )
        MangaCheckableMenuItem(
            title = stringResource(R.string.enable_auto_scroll),
            checked = enableAutoScroll,
            onClick = {
                setAutoScrollEnabled(!enableAutoScroll)
            }
        )
        if (enableAutoScrollPage || enableAutoScroll) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.manga_auto_page_speed, autoPageSpeedState)) },
                onClick = {
                    onDismiss()
                    showNumberPickerDialog(
                        1,
                        getString(R.string.setting_manga_auto_page_speed),
                        AppConfig.mangaAutoPageSpeed
                    ) {
                        AppConfig.mangaAutoPageSpeed = it
                        autoPageSpeedState = it
                        mScrollTimer.setSpeed(it)
                        if (enableAutoScrollPage) {
                            mScrollTimer.isEnabledPage = true
                        }
                    }
                }
            )
        }
        MangaCheckableMenuItem(
            title = stringResource(R.string.enable_manga_horizontal_scroll),
            checked = enableHorizontalScrollState,
            onClick = {
                toggleHorizontalScrollOption()
            }
        )
        MangaCheckableMenuItem(
            title = stringResource(R.string.enable_manga_double_page),
            checked = enableDoublePageState,
            onClick = {
                toggleDoublePageOption()
            }
        )
        if (isHorizontalModeState) {
            MangaCheckableMenuItem(
                title = stringResource(R.string.manga_right_to_left),
                checked = mangaRightToLeftState,
                onClick = {
                    toggleRightToLeftOption()
                }
            )
        }
        if (isHorizontalModeState && !disableMangaPageAnimState) {
            MangaCheckableMenuItem(
                title = stringResource(R.string.disable_horizontal_page_snap),
                checked = disableHorizontalPageSnapState || disableMangaPageAnimState,
                onClick = {
                    toggleDisableHorizontalPageSnapOption()
                }
            )
        }
        MangaCheckableMenuItem(
            title = stringResource(R.string.disable_manga_page_anim),
            checked = disableMangaPageAnimState,
            onClick = {
                toggleDisableMangaPageAnimOption()
            }
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.manga_footer_config)) },
            onClick = {
                onDismiss()
                showDialogFragment(MangaFooterSettingDialog())
            }
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.manga_color_filter)) },
            onClick = {
                onDismiss()
                showMenu(false)
                showDialogFragment(MangaColorFilterDialog())
            }
        )
        MangaCheckableMenuItem(
            title = stringResource(R.string.hide_manga_title),
            checked = hideMangaTitleState,
            onClick = {
                toggleHideMangaTitleOption()
            }
        )
        MangaCheckableMenuItem(
            title = stringResource(R.string.manga_epaper),
            checked = enableMangaEInkState,
            onClick = {
                toggleMangaEInkOption()
            }
        )
        if (enableMangaEInkState) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.manga_epaper_stting)) },
                onClick = {
                    onDismiss()
                    showDialogFragment(MangaEpaperDialog())
                }
            )
        }
        MangaCheckableMenuItem(
            title = stringResource(R.string.enable_manga_gray),
            checked = enableMangaGrayState,
            onClick = {
                toggleMangaGrayOption()
            }
        )
    }

    @Composable
    private fun MangaCheckableMenuItem(
        title: String,
        checked: Boolean,
        onClick: () -> Unit,
    ) {
        DropdownMenuItem(
            text = { Text(title) },
            trailingIcon = {
                Checkbox(checked = checked, onCheckedChange = null)
            },
            onClick = onClick
        )
    }

    @Composable
    private fun MangaBottomMenuBar(
        isRightToLeft: Boolean,
        pageValue: Int,
        pageCount: Int,
        onPrevChapter: () -> Unit,
        onNextChapter: () -> Unit,
        onSeekPage: (Int) -> Unit,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp
        ) {
            CompositionLocalProvider(
                LocalLayoutDirection provides if (isRightToLeft) LayoutDirection.Rtl else LayoutDirection.Ltr
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onPrevChapter) {
                        Text(
                            text = stringResource(R.string.previous_chapter),
                            fontSize = 14.sp
                        )
                    }
                    val maxPage = (pageCount - 1).coerceAtLeast(0)
                    Slider(
                        value = pageValue.coerceIn(0, maxPage).toFloat(),
                        onValueChange = {
                            onSeekPage(it.roundToInt().coerceIn(0, maxPage))
                        },
                        valueRange = 0f..maxPage.coerceAtLeast(1).toFloat(),
                        enabled = maxPage > 0,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp)
                    )
                    TextButton(onClick = onNextChapter) {
                        Text(
                            text = stringResource(R.string.next_chapter),
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }
    }

    private fun openChapterInBrowserOrWebView() {
        val url = chapterUrlState
        if (url.isBlank()) return
        if (AppConfig.readUrlInBrowser) {
            openUrl(url.substringBefore(",{"))
        } else {
            startActivity<WebViewActivity> {
                val bookSource = ReadManga.bookSource
                putExtra("title", chapterNameState)
                putExtra("url", url)
                putExtra("sourceOrigin", bookSource?.bookSourceUrl)
                putExtra("sourceName", bookSource?.bookSourceName)
                putExtra("sourceType", bookSource?.getSourceType())
            }
        }
    }

    private fun promptChapterOpenBrowserMode() {
        alert(R.string.open_fun) {
            setMessage(R.string.use_browser_open)
            okButton {
                AppConfig.readUrlInBrowser = true
            }
            noButton {
                AppConfig.readUrlInBrowser = false
            }
        }
    }

    internal fun showMenu(visible: Boolean) {
        if (isMenuVisible == visible) return
        isMenuVisible = visible
        if (visible) {
            sourceNameState = ReadManga.bookSource?.bookSourceName ?: getString(R.string.book_source)
            upSystemUiVisibility(true)
        } else {
            canShowMenu = false
            upSystemUiVisibility(false)
        }
    }

    internal fun syncMenuStatesFromConfig() {
        preDownloadNumState = AppConfig.mangaPreDownloadNum
        disableMangaScaleState = AppConfig.disableMangaScale
        mangaLongClickSaveImageState = AppConfig.mangaLongClickSaveImage
        disableClickScrollState = AppConfig.disableClickScroll
        autoPageSpeedState = AppConfig.mangaAutoPageSpeed
        enableHorizontalScrollState = AppConfig.enableMangaHorizontalScroll
        enableDoublePageState = AppConfig.enableMangaDoublePage
        mangaRightToLeftState = AppConfig.mangaRightToLeft
        disableHorizontalPageSnapState = AppConfig.disableHorizontalPageSnap
        disableMangaPageAnimState = AppConfig.disableMangaPageAnim
        hideMangaTitleState = AppConfig.hideMangaTitle
        enableMangaEInkState = AppConfig.enableMangaEInk
        enableMangaGrayState = AppConfig.enableMangaGray
        isHorizontalModeState = isHorizontalMode
    }

    internal fun toggleDisableMangaScale() {
        val checked = !disableMangaScaleState
        disableMangaScaleState = checked
        AppConfig.disableMangaScale = checked
        setDisableMangaScale(checked)
    }

    internal fun toggleMangaLongClickSaveImage() {
        val checked = !mangaLongClickSaveImageState
        mangaLongClickSaveImageState = checked
        AppConfig.mangaLongClickSaveImage = checked
    }

    internal fun toggleDisableClickScroll() {
        val checked = !disableClickScrollState
        disableClickScrollState = checked
        AppConfig.disableClickScroll = checked
        setDisableClickScroll(checked)
    }

    internal fun setAutoPageEnabled(enabled: Boolean) {
        enableAutoScrollPage = enabled
        mScrollTimer.isEnabledPage = enabled
        enableAutoScroll = false
        mScrollTimer.isEnabled = false
        updatePageSnap()
    }

    internal fun setAutoScrollEnabled(enabled: Boolean) {
        enableAutoScroll = enabled
        mScrollTimer.isEnabled = enabled
        enableAutoScrollPage = false
        mScrollTimer.isEnabledPage = false
        updatePageSnap()
    }

    @SuppressLint("NotifyDataSetChanged")
    internal fun toggleHorizontalScrollOption() {
        val checked = !enableHorizontalScrollState
        enableHorizontalScrollState = checked
        AppConfig.enableMangaHorizontalScroll = checked
        isHorizontalModeState = isHorizontalMode
        setHorizontalScroll(isHorizontalMode)
        mAdapter.notifyDataSetChanged()
    }

    @SuppressLint("NotifyDataSetChanged")
    internal fun toggleDoublePageOption() {
        val checked = !enableDoublePageState
        enableDoublePageState = checked
        AppConfig.enableMangaDoublePage = checked
        isHorizontalModeState = isHorizontalMode
        setHorizontalScroll(isHorizontalMode)
        mAdapter.notifyDataSetChanged()
    }

    @SuppressLint("NotifyDataSetChanged")
    internal fun toggleRightToLeftOption() {
        val checked = !mangaRightToLeftState
        mangaRightToLeftState = checked
        AppConfig.mangaRightToLeft = checked
        setHorizontalScroll(isHorizontalMode)
        mAdapter.notifyDataSetChanged()
    }

    internal fun toggleDisableHorizontalPageSnapOption() {
        val checked = !disableHorizontalPageSnapState
        disableHorizontalPageSnapState = checked
        AppConfig.disableHorizontalPageSnap = checked
        updatePageSnap()
    }

    internal fun toggleDisableMangaPageAnimOption() {
        val checked = !disableMangaPageAnimState
        disableMangaPageAnimState = checked
        AppConfig.disableMangaPageAnim = checked
        updatePageSnap()
    }

    internal fun toggleHideMangaTitleOption() {
        val checked = !hideMangaTitleState
        hideMangaTitleState = checked
        AppConfig.hideMangaTitle = checked
        ReadManga.loadContent()
    }

    internal fun toggleMangaEInkOption() {
        val checked = !enableMangaEInkState
        enableMangaEInkState = checked
        AppConfig.enableMangaEInk = checked
        enableMangaGrayState = false
        AppConfig.enableMangaGray = false
        mAdapter.enableMangaEInk(checked, AppConfig.mangaEInkThreshold)
    }

    internal fun toggleMangaGrayOption() {
        val checked = !enableMangaGrayState
        enableMangaGrayState = checked
        AppConfig.enableMangaGray = checked
        enableMangaEInkState = false
        AppConfig.enableMangaEInk = false
        mAdapter.enableGray(checked)
    }

    override fun observeLiveBus() {
        observeEvent<MangaFooterConfig>(EventBus.UP_MANGA_CONFIG) {
            mMangaFooterConfig = it
            val item = mAdapter.getItem(recyclerView.findCenterViewPosition())
            upInfoBar(item)
        }
    }

    private fun initRecyclerView() {
        val mangaColorFilter =
            GSON.fromJsonObject<MangaColorFilterConfig>(AppConfig.mangaColorFilter).getOrNull()
                ?: MangaColorFilterConfig()
        mAdapter.run {
            setMangaImageColorFilter(mangaColorFilter)
            enableMangaEInk(AppConfig.enableMangaEInk, AppConfig.mangaEInkThreshold)
            enableGray(AppConfig.enableMangaGray)
        }
        setHorizontalScroll(isHorizontalMode)
        recyclerView.run {
            adapter = mAdapter
            itemAnimator = null
            layoutManager = mLayoutManager
            setHasFixedSize(true)
            setDisableClickScroll(AppConfig.disableClickScroll)
            setDisableMangaScale(AppConfig.disableMangaScale)
            setRecyclerViewPreloader(AppConfig.mangaPreDownloadNum)
            setPreScrollListener { _, _, _, position ->
                // Until the requested page is laid out, callbacks still describe the old page.
                if (!loadingViewVisible && mAdapter.isNotEmpty()) {
                    val item = mAdapter.getItem(position)
                    if (item is BaseMangaPage) {
                        // Capture the new chapter's page before its transition saves progress.
                        ReadManga.durChapterPos = if (item is MangaPage) {
                            item.index
                        } else {
                            (item.index - 1).coerceAtLeast(0)
                        }
                        if (ReadManga.durChapterIndex < item.chapterIndex) {
                            ReadManga.moveToNextChapter()
                        } else if (ReadManga.durChapterIndex > item.chapterIndex) {
                            ReadManga.moveToPrevChapter()
                        } else {
                            ReadManga.curPageChanged()
                        }
                        if (item is MangaPage) {
                            upSeekBar(item.index, item.imageCount)
                            upInfoBar(item)
                        }
                    }
                }
            }
            longTapListener = { event ->
                if (!AppConfig.mangaLongClickSaveImage) {
                    false
                } else {
                    val position = findChildViewUnder(event.x, event.y)
                        ?.let { getChildAdapterPosition(it) }
                        ?: RecyclerView.NO_POSITION
                    (mAdapter.getItem(position) as? MangaPage)?.let {
                        saveImage(it.mImageUrl)
                        true
                    } ?: false
                }
            }
        }
        webtoonFrame.run {
            onTouchMiddle {
                if (!isMenuVisible && !loadingViewVisible) {
                    showMenu(true)
                }
            }
            onNextPage {
                scrollToNext()
            }
            onPrevPage {
                scrollToPrev()
            }
        }
    }

    private fun alignSpreadStartPosition(pos: Int, items: List<Any> = mAdapter.getItems()): Int {
        if (!isDoublePageMode || pos <= 0) return pos
        val item = items.getOrNull(pos) as? MangaPage ?: return pos
        if (item.index % 2 == 1) {
            val prev = items.getOrNull(pos - 1) as? MangaPage
            if (prev != null && prev.chapterIndex == item.chapterIndex) {
                return pos - 1
            }
        }
        return pos
    }

    private fun saveImage(src: String) {
        val path = ACache.get().getAsString(AppConst.imagePathKey)
        if (path.isNullOrEmpty()) {
            selectImageDir.launch { value = src }
        } else {
            viewModel.saveImage(src, path.toUri())
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        viewModel.initData(intent)
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        viewModel.initData(intent)
        justInitData = true
    }

    override fun upContent() {
        lifecycleScope.launch {
            val updateId = ++contentUpdateId
            val name = ReadManga.book?.name.orEmpty()
            setTitle(name)
            bookTitleState = name
            chapterNameState = ReadManga.curMangaChapter?.chapter?.title.orEmpty()
            chapterUrlState = ReadManga.curMangaChapter?.chapter?.url.orEmpty()
            sourceNameState = ReadManga.bookSource?.bookSourceName ?: getString(R.string.book_source)
            // This only snapshots the loaded chapters and clamps the reading position.
            // Keep snapshots ordered; an older IO task must not overwrite a newer list.
            val data = ReadManga.mangaContents
            val pos = data.pos
            val list = data.items
            val curFinish = data.curFinish
            val nextFinish = data.nextFinish
            mAdapter.submitList(list) {
                if (loadingViewVisible && curFinish) {
                    val alignedPos = alignSpreadStartPosition(pos, list)
                    infoBarVisible = !mMangaFooterConfig.hideFooter
                    upInfoBar(list[alignedPos])
                    mLayoutManager.scrollToPositionWithOffset(alignedPos, 0)
                    recyclerView.doOnNextLayout {
                        if (updateId == contentUpdateId) {
                            loadingViewVisible = false
                            loadingErrorMessage = null
                            loadMoreView.visible()
                            ReadManga.curMangaChapter?.let { chapter ->
                                upSeekBar(ReadManga.durChapterPos, chapter.imageCount)
                            }
                        }
                    }
                }

                if (curFinish) {
                    if (!ReadManga.hasNextChapter) {
                        loadMoreView.noMore("暂无章节了！")
                    } else if (nextFinish) {
                        loadMoreView.stopLoad()
                    } else {
                        loadMoreView.startLoad()
                    }
                }
            }
        }
    }

    private fun upSeekBar(value: Int, count: Int) {
        seekPageValue = value
        seekPageCount = count.coerceAtLeast(1)
    }

    private fun upInfoBar(page: Any?) {
        if (page !is MangaPage) {
            return
        }
        val chapterIndex = page.chapterIndex
        val chapterSize = page.chapterSize
        val chapterPos = page.index
        val imageCount = page.imageCount
        val chapterName = page.mChapterName
        chapterNameState = chapterName
        val spreadStart = if (isDoublePageMode) (chapterPos / 2) * 2 else chapterPos
        val spreadEnd = if (isDoublePageMode && imageCount > 0) {
            (spreadStart + 1).coerceAtMost(imageCount - 1)
        } else {
            chapterPos
        }
        mMangaFooterConfig.run {
            mLabelBuilder.clear()
            infoBarVisible = !hideFooter
            infoBarAlignCenter = footerOrientation == ReaderInfoBarView.ALIGN_CENTER

            if (!hideChapterName) {
                mLabelBuilder.append(chapterName).append(" ")
            }

            if (!hidePageNumber) {
                if (!hidePageNumberLabel) {
                    mLabelBuilder.append(getString(R.string.manga_check_page_number))
                }
                val pageText = if (isDoublePageMode && spreadEnd > spreadStart) {
                    "${spreadStart + 1}-${spreadEnd + 1}/${imageCount}"
                } else {
                    "${chapterPos + 1}/${imageCount}"
                }
                mLabelBuilder.append(pageText).append(" ")
            }

            if (!hideChapter) {
                if (!hideChapterLabel) {
                    mLabelBuilder.append(getString(R.string.manga_check_chapter))
                }
                mLabelBuilder.append("${chapterIndex + 1}/${chapterSize}").append(" ")
            }

            if (!hideProgressRatio) {
                if (!hideProgressRatioLabel) {
                    mLabelBuilder.append(getString(R.string.manga_check_progress))
                }
                val progressPos = if (isDoublePageMode) spreadEnd else chapterPos
                val percent = if (chapterSize == 0 || imageCount == 0 && chapterIndex == 0) {
                    "0.0%"
                } else if (imageCount == 0) {
                    df.format((chapterIndex + 1.0f) / chapterSize.toDouble())
                } else {
                    var percent =
                        df.format(
                            chapterIndex * 1.0f / chapterSize + 1.0f /
                                    chapterSize * (progressPos + 1) / imageCount.toDouble()
                        )
                    if (percent == "100.0%" && (chapterIndex + 1 != chapterSize || progressPos + 1 != imageCount)) {
                        percent = "99.9%"
                    }
                    percent
                }
                mLabelBuilder.append(percent)
            }
        }
        infoBarText = if (mLabelBuilder.isEmpty()) "" else mLabelBuilder.toString()
    }

    override fun onResume() {
        super.onResume()
        ReadManga.readStartTime = System.currentTimeMillis()
        networkChangedListener.register()
        networkChangedListener.onNetworkChanged = {
            // 当网络是可用状态且无需初始化时同步进度（初始化中已有同步进度逻辑）
            if (AppConfig.syncBookProgressPlus && NetworkUtils.isAvailable() && !justInitData && ReadManga.inBookshelf) {
                ReadManga.syncProgress({ progress -> sureNewProgress(progress) })
            }
        }
        if (enableAutoScrollPage) {
            mScrollTimer.isEnabledPage = true
        }
        if (enableAutoScroll) {
            mScrollTimer.isEnabled = true
        }
    }

    override fun onPause() {
        super.onPause()
        ReadManga.upReadTime()
        if (ReadManga.inBookshelf) {
            ReadManga.saveRead()
            if (!BuildConfig.DEBUG) {
                if (AppConfig.syncBookProgressPlus) {
                    ReadManga.syncProgress()
                } else {
                    ReadManga.uploadProgress()
                }
            }
        }
        if (!BuildConfig.DEBUG) {
            Backup.autoBack(this)
        }
        ReadManga.cancelPreDownloadTask()
        networkChangedListener.unRegister()
        mScrollTimer.isEnabledPage = false
        mScrollTimer.isEnabled = false
    }

    override fun loadFail(msg: String, retry: Boolean) {
        lifecycleScope.launch {
            if (loadingViewVisible) {
                loadingErrorMessage = msg
                canRetryLoading = retry
            } else {
                loadMoreView.error(null, "加载失败，点击重试")
            }
        }
    }

    override fun onDestroy() {
        ReadManga.unregister(this)
        super.onDestroy()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        Glide.get(this).clearMemory()
    }

    override fun sureNewProgress(progress: BookProgress) {
        syncDialog?.dismiss()
        syncDialog = alert(R.string.get_book_progress) {
            setMessage(R.string.cloud_progress_exceeds_current)
            okButton {
                ReadManga.setProgress(progress)
            }
            noButton()
        }
    }

    override fun showLoading() {
        lifecycleScope.launch {
            contentUpdateId++
            loadingErrorMessage = null
            loadingViewVisible = true
        }
    }

    override fun startLoad() {
        lifecycleScope.launch {
            loadMoreView.startLoad()
        }
    }

    override fun scrollBy(distance: Int) {
        val horizontal = mLayoutManager.orientation == LinearLayoutManager.HORIZONTAL
        val direction = if (mLayoutManager.reverseLayout) -1 else 1
        if (if (horizontal) !recyclerView.canScrollHorizontally(direction)
            else !recyclerView.canScrollVertically(1)) {
            return
        }
        val time = ceil(16f / distance * 10000).toInt()
        recyclerView.smoothScrollBy(
            if (horizontal) 10000 * direction else 0,
            if (horizontal) 0 else 10000, mLinearInterpolator, time,
        )
    }

    override fun scrollPage() {
        scrollToNext()
    }

    override val oldBook: Book?
        get() = ReadManga.book

    override fun changeTo(
        source: BookSource,
        book: Book,
        toc: List<BookChapter>,
        onSuccess: () -> Unit,
    ) {
        if (book.isImage) {
            loadingErrorMessage = null
            loadingViewVisible = true
            viewModel.changeTo(book, toc, onSuccess)
        } else {
            toastOnUi("所选择的源不是漫画源")
        }
    }

    override fun updateColorFilter(config: MangaColorFilterConfig) {
        mAdapter.setMangaImageColorFilter(config)
        updateWindowBrightness(config.l)
    }

    fun openBookInfoActivity() {
        ReadManga.book?.let {
            bookInfoActivity.launch {
                putExtra("name", it.name)
                putExtra("author", it.author)
            }
        }
    }

    fun upSystemUiVisibility(menuIsVisible: Boolean) {
        toggleSystemBar(menuIsVisible)
        if (!menuIsVisible) {
            // Keep focus in touch mode so Android delivers the first page key after a gesture.
            recyclerView.requestFocus()
        }
        if (enableAutoScroll) {
            mScrollTimer.isEnabled = !menuIsVisible
        }
        if (enableAutoScrollPage) {
            mScrollTimer.isEnabledPage = !menuIsVisible
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        val action = event.action
        val isDown = action == 0

        if (keyCode == KeyEvent.KEYCODE_MENU) {
            if (isDown && !canShowMenu) {
                showMenu(true)
                return true
            }
            if (!isDown && !canShowMenu) {
                canShowMenu = true
                return true
            }
        }
        if (!isMenuVisible && !loadingViewVisible) {
            val direction = when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> if (mLayoutManager.reverseLayout) 1 else -1
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (mLayoutManager.reverseLayout) -1 else 1
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_PAGE_UP -> -1
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> 1
                KeyEvent.KEYCODE_SPACE -> if (event.isShiftPressed) -1 else 1
                else -> 0
            }
            if (direction != 0) {
                if (isDown) scrollPageTo(direction)
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun setRecyclerViewPreloader(maxPreload: Int) {
        if (mRecyclerViewPreloader != null) {
            recyclerView.removeOnScrollListener(mRecyclerViewPreloader!!)
        }
        val dm = resources.displayMetrics
        val preloadWidth = if (isDoublePageMode) {
            (dm.widthPixels / 2).coerceAtLeast(1)
        } else {
            val columnWidth = io.legado.app.utils.resolveMangaColumnWidth(
                containerWidthPx = dm.widthPixels,
                containerHeightPx = dm.heightPixels,
                isHorizontalScroll = isHorizontalMode,
                maxVerticalColumnWidthPx = 680.dpToPx(),
            )
            if (columnWidth > 0) columnWidth else dm.widthPixels
        }
        val sizeProvider = FixedPreloadSizeProvider<Any>(preloadWidth, SIZE_ORIGINAL)
        mRecyclerViewPreloader = RecyclerViewPreloader(
            Glide.with(this), mAdapter, sizeProvider, maxPreload
        )
        recyclerView.addOnScrollListener(mRecyclerViewPreloader!!)
    }

    @SuppressLint("NotifyDataSetChanged")
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        setHorizontalScroll(isHorizontalMode)
        syncMenuStatesFromConfig()
        mAdapter.notifyDataSetChanged()
    }

    private fun setHorizontalScroll(enable: Boolean) {
        val recycler = recyclerView
        val anchor = recycler.currentPagePosition()
        val resumeAutoScroll = enableAutoScroll && !isMenuVisible
        if (resumeAutoScroll) mScrollTimer.isEnabled = false
        recycler.stopScroll()
        mPagerSnapHelper.attachToRecyclerView(null)
        mAdapter.isHorizontal = enable
        mAdapter.isDoublePage = isDoublePageMode
        mAdapter.rightToLeft = enable && AppConfig.mangaRightToLeft
        setRecyclerViewPreloader(AppConfig.mangaPreDownloadNum)
        // Keep adapter positions in reading order. Only their horizontal placement is reversed.
        recycler.layoutDirection = View.LAYOUT_DIRECTION_LTR
        mLayoutManager.orientation = if (enable) LinearLayoutManager.HORIZONTAL else LinearLayoutManager.VERTICAL
        mLayoutManager.reverseLayout = enable && AppConfig.mangaRightToLeft
        webtoonFrame.rightToLeft = mLayoutManager.reverseLayout
        isRightToLeftMenu = mLayoutManager.reverseLayout
        isHorizontalModeState = enable
        if (anchor != RecyclerView.NO_POSITION) {
            val alignedAnchor = alignSpreadStartPosition(anchor)
            mLayoutManager.scrollToPositionWithOffset(alignedAnchor, 0)
            upInfoBar(mAdapter.getItem(alignedAnchor))
            recycler.doOnNextLayout {
                updatePageSnap()
                if (resumeAutoScroll) mScrollTimer.isEnabled = true
            }
        } else {
            updatePageSnap()
            if (resumeAutoScroll) mScrollTimer.isEnabled = true
        }
    }

    private fun updatePageSnap() {
        val snap = isHorizontalMode && !enableAutoScroll &&
            !AppConfig.disableHorizontalPageSnap && !AppConfig.disableMangaPageAnim
        mPagerSnapHelper.attachToRecyclerView(if (snap) recyclerView else null)
        webtoonFrame.enableSimulationCurl =
            isHorizontalMode && !enableAutoScroll && !AppConfig.disableMangaPageAnim
    }

    private fun setDisableMangaScale(disable: Boolean) {
        webtoonFrame.disableMangaScale = disable
        recyclerView.disableMangaScale = disable
        if (disable) {
            recyclerView.resetZoom()
        }
    }

    private fun setDisableClickScroll(disable: Boolean) {
        webtoonFrame.disabledClickScroll = disable
    }

    private fun upLayoutInDisplayCutoutMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    private fun scrollToNext() {
        scrollPageTo(1)
    }

    private fun scrollToPrev() {
        scrollPageTo(-1)
    }

    private fun scrollPageTo(direction: Int) {
        if (loadingViewVisible) return
        var dx = 0
        var dy = 0
        if (isHorizontalMode) {
            val physicalDirection = if (mLayoutManager.reverseLayout) -direction else direction
            if (!recyclerView.canScrollHorizontally(physicalDirection)) return
            if (!AppConfig.disableMangaPageAnim &&
                webtoonFrame.startSimulationPageTurn(physicalDirection)
            ) {
                return
            }
            dx = recyclerView.run {
                width - paddingStart - paddingEnd
            } * physicalDirection
        } else {
            if (!recyclerView.canScrollVertically(direction)) return
            dy = recyclerView.run {
                height - paddingTop - paddingBottom
            } * direction
        }
        if (AppConfig.disableMangaPageAnim) {
            recyclerView.scrollBy(dx, dy)
        } else {
            recyclerView.smoothScrollBy(dx, dy)
        }
    }

    private fun showNumberPickerDialog(
        min: Int,
        title: String,
        initValue: Int,
        callback: (Int) -> Unit,
    ) {
        NumberPickerDialog(this)
            .setTitle(title)
            .setMaxValue(9999)
            .setMinValue(min)
            .setValue(initValue)
            .show {
                callback.invoke(it)
            }
    }

    override fun finish() {
        val book = ReadManga.book ?: return super.finish()

        if (ReadManga.inBookshelf) {
            return super.finish()
        }

        if (!AppConfig.showAddToShelfAlert) {
            viewModel.removeFromBookshelf { super.finish() }
        } else {
            alert(title = getString(R.string.add_to_bookshelf)) {
                setMessage(getString(R.string.check_add_bookshelf, book.name))
                okButton {
                    ReadManga.book?.removeType(BookType.notShelf)
                    ReadManga.book?.save()
                    ReadManga.inBookshelf = true
                    setResult(RESULT_OK)
                }
                noButton { viewModel.removeFromBookshelf { super.finish() } }
            }
        }
    }

    fun updateWindowBrightness(brightness: Int) {
        val layoutParams = window.attributes
        val normalizedBrightness = brightness.toFloat() / 255.0f
        layoutParams.screenBrightness = normalizedBrightness.coerceIn(0f, 1f)
        window.attributes = layoutParams
        // 强制刷新屏幕
        window.decorView.postInvalidate()
    }

    fun skipToPage(index: Int) {
        val durChapterIndex = ReadManga.durChapterIndex
        val itemPos = mAdapter.getItems().fastBinarySearch {
            val chapterIndex: Int
            val pageIndex: Int
            if (it is BaseMangaPage) {
                chapterIndex = it.chapterIndex
                pageIndex = it.index
            } else {
                error("unknown item type")
            }
            val delta = chapterIndex - durChapterIndex
            if (delta != 0) {
                delta
            } else {
                pageIndex - index
            }
        }
        if (itemPos > -1) {
            val alignedPos = alignSpreadStartPosition(itemPos)
            mLayoutManager.scrollToPositionWithOffset(alignedPos, 0)
            upInfoBar(mAdapter.getItem(alignedPos))
            ReadManga.durChapterPos = index
            seekPageValue = index
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                scrollToPrev()
                return true
            }

            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                scrollToNext()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun updateEepaper(value: Int) {
        mAdapter.updateThreshold(value)
    }
}
