package io.legado.app.ui.video

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import android.view.textclassifier.TextClassifier
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.activity.addCallback
import androidx.activity.viewModels
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import com.shuyu.gsyvideoplayer.listener.GSYSampleCallBack
import io.legado.app.R
import io.legado.app.base.BaseComposeActivity
import io.legado.app.constant.BookType
import io.legado.app.constant.EventBus
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.help.GlideImageGetter
import io.legado.app.help.TextViewTagHandler
import io.legado.app.help.WebCacheManager
import io.legado.app.help.book.removeType
import io.legado.app.help.config.AppConfig
import io.legado.app.help.gsyVideo.VideoPlayer
import io.legado.app.help.webView.PooledWebView
import io.legado.app.help.webView.WebJsExtensions
import io.legado.app.help.webView.WebJsExtensions.Companion.getInjectionString
import io.legado.app.help.webView.WebJsExtensions.Companion.nameCache
import io.legado.app.help.webView.WebJsExtensions.Companion.nameJava
import io.legado.app.help.webView.WebJsExtensions.Companion.nameSource
import io.legado.app.help.webView.WebViewPool
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.ThemeStore.Companion.accentColor
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.model.SourceCallBack
import io.legado.app.model.VideoPlay
import io.legado.app.service.VideoPlayService
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.association.OnLineImportActivity
import io.legado.app.ui.book.source.edit.BookSourceEditActivity
import io.legado.app.ui.book.toc.TocActivityResult
import io.legado.app.ui.compose.component.LegadoTopAppBar
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.ui.video.config.SettingsDialog
import io.legado.app.ui.widget.dialog.PhotoDialog
import io.legado.app.ui.widget.image.CoverImageView
import io.legado.app.ui.widget.text.ScrollTextView
import io.legado.app.utils.StartActivityContract
import io.legado.app.utils.dpToPx
import io.legado.app.utils.gone
import io.legado.app.utils.longSnackbar
import io.legado.app.utils.observeEvent
import io.legado.app.utils.observeEventSticky
import io.legado.app.utils.openUrl
import io.legado.app.utils.sendToClip
import io.legado.app.utils.setHtml
import io.legado.app.utils.setMarkdown
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.toggleSystemBar
import io.legado.app.utils.visible
import io.noties.markwon.Markwon
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.html.HtmlPlugin
import io.noties.markwon.image.glide.GlideImagesPlugin
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VideoPlayerActivity : BaseComposeActivity(), SettingsDialog.CallBack {

    private val viewModel by viewModels<VideoPlayerViewModel>()
    private val playerView: VideoPlayer by lazy { VideoPlayer(this) }
    private val ivCoverView: CoverImageView by lazy {
        CoverImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageResource(R.drawable.image_cover_default)
        }
    }
    private val tvIntroContainer: FrameLayout by lazy { FrameLayout(this) }
    private var isIntroTextViewAttached = false
    private val introTextView by lazy {
        ScrollTextView(this).apply {
            isClickable = true
            isFocusable = true
            minimumHeight = 48.dpToPx()
            setPadding(8.dpToPx(), 0, 0, 8.dpToPx())
            setTextColor(ContextCompat.getColor(this@VideoPlayerActivity, R.color.secondaryText))
            setTextIsSelectable(true)
            textSize = 14f
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
                revealOnFocusHint = false
            }
        }
    }
    private var pooledWebView: PooledWebView? = null
    private val imgAvailableWidth by lazy {
        val textView = introTextView
        (textView.width - textView.paddingLeft - textView.paddingRight - 8.dpToPx()).coerceAtLeast(100)
    }
    private var initGetter = false
    private val glideImageGetter by lazy {
        initGetter = true
        GlideImageGetter(
            this,
            introTextView,
            lifecycle,
            imgAvailableWidth,
            VideoPlay.source?.getKey()
        )
    }

    private val textViewTagHandler by lazy {
        TextViewTagHandler(object : TextViewTagHandler.OnButtonClickListener {
            override fun onButtonClick(name: String, click: String) {
                viewModel.onButtonClick(this@VideoPlayerActivity, "info button $name", click)
            }
        })
    }
    private var isNew = true
    private var forwardedToFloatingWindow = false
    private var isFullScreen by mutableStateOf(false)
    private var orientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

    private var titleState by mutableStateOf("")
    private var bookState by mutableStateOf<Book?>(null)
    private var volumesState by mutableStateOf<List<BookChapter>>(emptyList())
    private var episodesState by mutableStateOf<List<BookChapter>>(emptyList())
    private var selectedVolumeIndexState by mutableIntStateOf(0)
    private var selectedEpisodeIndexState by mutableIntStateOf(0)
    private var customBtnVisibleState by mutableStateOf(false)

    private val bookSourceEditResult =
        registerForActivityResult(StartActivityContract(BookSourceEditActivity::class.java)) {
            if (it.resultCode == RESULT_OK) {
                viewModel.upSource {
                    customBtnVisibleState = (VideoPlay.source as? BookSource)?.customButton == true
                }
            }
        }
    private val tocActivityResult = registerForActivityResult(TocActivityResult()) {
        it?.let {
            if (it[2] as Boolean) {
                VideoPlay.chapterInVolumeIndex = it[0] as Int
                val durChapterPos = it[1] as Int
                VideoPlay.durVolumeIndex = it[3] as Int
                VideoPlay.chapterInVolumeIndex = it[4] as Int
                VideoPlay.upEpisodes()
                VideoPlay.saveRead(durChapterPos)
                episodesState = VideoPlay.episodes.orEmpty()
                upView()
                VideoPlay.startPlay(playerView)
            }
        }
    }

    @Composable
    override fun Content() {
        VideoPlayerScreen()
    }

    @OptIn(UnstableApi::class)
    override fun onActivityCreated(savedInstanceState: Bundle?) {
        isNew = intent.getBooleanExtra("isNew", true)
        if (isNew &&
            intent.action == null &&
            VideoPlay.defaultFloatWindow &&
            !intent.getBooleanExtra("forceNormalPlayer", false)
        ) {
            forwardedToFloatingWindow = true
            intent.putExtra("forwardedToFloatingWindow", true)
            playerView.needDestroy = false
            ContextCompat.startForegroundService(
                this,
                Intent(intent).setClass(this, VideoPlayService::class.java)
            )
            super.finish()
            return
        }
        playerView.enlargeImageRes = R.drawable.ic_fullscreen
        if (isNew) {
            intent.getStringExtra("videoUrl")?.let {
                VideoPlay.videoUrl = it
                VideoPlay.singleUrl = true
            }
            intent.getStringExtra("videoTitle")?.let {
                titleState = it
                VideoPlay.videoTitle = it
            }
            val sourceKey = intent.getStringExtra("sourceKey")
            val sourceType = intent.getIntExtra("sourceType", 0)
            val bookUrl = intent.getStringExtra("bookUrl")
            val record = intent.getStringExtra("record")
            VideoPlay.inBookshelf = intent.getBooleanExtra("inBookshelf", true)
            if (!VideoPlay.initSource(sourceKey, sourceType, bookUrl, record)) {
                finish()
                return
            }
            VideoPlay.startPlay(playerView)
            VideoPlay.saveRead()
        } else {
            VideoPlay.clonePlayState(playerView)
            playerView.setSurfaceToPlay()
            playerView.startAfterPrepared()
            titleState = VideoPlay.videoTitle.orEmpty()
        }
        customBtnVisibleState = (VideoPlay.source as? BookSource)?.customButton == true
        setupPlayerView()
        initView()
        upView()
        onBackPressedDispatcher.addCallback(this) {
            if (isFullScreen) {
                toggleFullScreen()
                return@addCallback
            }
            finish()
        }
    }

    private fun initView() {
        viewModel.upStarMenuData.observe(this) { upStarMenu() }
        val book = VideoPlay.book
        bookState = book
        if (book == null) {
            return
        }
        showBook(book)
        episodesState = VideoPlay.episodes.orEmpty()
        volumesState = VideoPlay.volumes
        selectedEpisodeIndexState = VideoPlay.chapterInVolumeIndex
        selectedVolumeIndexState = VideoPlay.durVolumeIndex
    }

    private fun showBook(book: Book) {
        showCover(book)
        showBookIntro(book)
    }

    inner class CustomWebViewClient : WebViewClient() {
        private val jsStr = getInjectionString
        override fun shouldOverrideUrlLoading(
            view: WebView?,
            request: WebResourceRequest?
        ): Boolean {
            request?.let {
                val uri = it.url
                return when (uri.scheme) {
                    "http", "https" -> false
                    "legado", "yuedu" -> {
                        startActivity<OnLineImportActivity> {
                            data = uri
                        }
                        true
                    }

                    else -> {
                        tvIntroContainer.longSnackbar(R.string.jump_to_another_app, R.string.confirm) {
                            openUrl(uri)
                        }
                        true
                    }
                }
            }
            return true
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            super.onPageStarted(view, url, favicon)
            view?.evaluateJavascript(jsStr, null)
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            view?.post {
                tvIntroContainer.requestLayout()
            }
        }
    }

    private fun showBookIntro(book: Book) {
        val intro = book.getDisplayIntro()
        if (intro.isNullOrBlank()) {
            destroyWeb()
            tvIntroContainer.removeAllViews()
            isIntroTextViewAttached = false
            tvIntroContainer.gone()
            return
        }
        tvIntroContainer.visible()
        if (intro.startsWith("<useweb>")) {
            val lastIndex = intro.lastIndexOf("<")
            if (lastIndex < 8) {
                introTextView.text = intro
                return
            }
            val html = intro.substring(8, lastIndex)
            val pooledWebView = this.pooledWebView ?: let {
                val pooledWebView = WebViewPool.acquire(this)
                val webView = pooledWebView.realWebView
                webView.onResume()
                webView.webViewClient = CustomWebViewClient()
                webView.addJavascriptInterface(WebCacheManager, nameCache)
                VideoPlay.source?.let {
                    webView.addJavascriptInterface(it, nameSource)
                    val webJsExtensions = WebJsExtensions(it, null, webView)
                    webView.addJavascriptInterface(webJsExtensions, nameJava)
                }
                pooledWebView
            }
            val webView = pooledWebView.realWebView
            if (isIntroTextViewAttached || this.pooledWebView == null) {
                isIntroTextViewAttached = false
                this.pooledWebView = pooledWebView
                tvIntroContainer.removeAllViews()
                tvIntroContainer.addView(webView)
            }
            val bookUrl = VideoPlay.book?.bookUrl
                ?.takeIf { it.startsWith("http", true) }
                ?.substringBefore(",")
            webView.loadDataWithBaseURL(bookUrl, html, "text/html", "utf-8", bookUrl)
            return
        }
        val tvIntro = introTextView
        if (!isIntroTextViewAttached || pooledWebView != null) {
            destroyWeb()
            tvIntroContainer.removeAllViews()
            tvIntro.text = null
            tvIntroContainer.addView(tvIntro)
            isIntroTextViewAttached = true
        }
        if (intro.startsWith("<usehtml>")) {
            val lastIndex = intro.lastIndexOf("<")
            if (lastIndex < 9) {
                tvIntro.text = intro
                return
            }
            val html = intro.substring(9, lastIndex)
            tvIntro.setHtml(
                html,
                glideImageGetter,
                textViewTagHandler,
                imgOnLongClickListener = {
                    showDialogFragment(PhotoDialog(it, VideoPlay.source?.getKey()))
                },
                imgOnClickListener = {
                    viewModel.onButtonClick(this@VideoPlayerActivity, "info image", it)
                }
            )
        } else if (intro.startsWith("<md>")) {
            val lastIndex = intro.lastIndexOf("<")
            if (lastIndex < 4) {
                tvIntro.text = intro
                return
            }
            val mark = intro.substring(4, lastIndex)
            lifecycleScope.launch {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    tvIntro.setTextClassifier(TextClassifier.NO_OP)
                }
                val context = this@VideoPlayerActivity
                val markwon: Markwon
                val markdown = withContext(IO) {
                    markwon = Markwon.builder(context)
                        .usePlugin(
                            GlideImagesPlugin.create(
                                Glide.with(context)
                                    .applyDefaultRequestOptions(
                                        RequestOptions()
                                            .override(imgAvailableWidth)
                                            .encodeQuality(88)
                                    )
                            )
                        )
                        .usePlugin(HtmlPlugin.create())
                        .usePlugin(TablePlugin.create(context))
                        .build()
                    markwon.toMarkdown(mark)
                }
                tvIntro.setMarkdown(
                    markwon,
                    markdown,
                    imgOnLongClickListener = { source ->
                        showDialogFragment(PhotoDialog(source, VideoPlay.source?.getKey()))
                    }
                )
            }
        } else {
            tvIntro.text = intro
        }
    }

    private fun showCover(book: Book) {
        ivCoverView.load(book, false)
    }

    private fun upView() {
        upEpisodesView()
        upVolumesView()
    }

    private fun upEpisodesView() {
        episodesState = VideoPlay.episodes.orEmpty()
        selectedEpisodeIndexState = VideoPlay.chapterInVolumeIndex
    }

    private fun upVolumesView() {
        volumesState = VideoPlay.volumes
        selectedVolumeIndexState = VideoPlay.durVolumeIndex
    }

    private fun toggleFullScreen() {
        isFullScreen = !isFullScreen
        toggleSystemBar(!isFullScreen)
        if (isFullScreen) {
            orientation = requestedOrientation
            requestedOrientation = if (VideoPlay.isPortraitVideo) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT // 竖屏
            } else {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE // 横屏
            }
            supportActionBar?.hide()
            playerView.startWindowFullscreen(this, false, false)
        } else {
            requestedOrientation = orientation
            supportActionBar?.show()
            playerView.postDelayed({
                playerView.backFromFull(this)
            }, if (VideoPlay.isPortraitVideo) 300 else 0)
            upView()
        }
    }

    @Suppress("DEPRECATION")
    @SuppressLint("SwitchIntDef")
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (isFullScreen) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN)
            window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            window.addFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN)
            when (newConfig.orientation) {
                Configuration.ORIENTATION_LANDSCAPE -> {
                    if (!VideoPlay.isPortraitVideo) {
                        toggleFullScreen()
                    }
                }
                Configuration.ORIENTATION_PORTRAIT -> {
                    if (VideoPlay.isPortraitVideo) {
                        toggleFullScreen()
                    }
                }
            }
        }
    }

    private fun setupPlayerView() {
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        val videoWidth = playerView.currentVideoWidth
        val videoHeight = playerView.currentVideoHeight
        val height = if (videoWidth > 0 && videoHeight > 0) (screenWidth * videoHeight / videoWidth) else (screenWidth * 9 / 16)
        val clampedHeight = if (height < screenHeight / 2) height else screenHeight / 2
        playerView.layoutParams = ViewGroup.LayoutParams(screenWidth, clampedHeight)
        playerView.isNeedOrientationUtils = false
        playerView.fullscreenButton.setOnClickListener { toggleFullScreen() }
        playerView.setBackFromFullScreenListener { toggleFullScreen() }
        playerView.setVideoAllCallBack(object : GSYSampleCallBack() {
            @SuppressLint("SourceLockedOrientationActivity")
            override fun onPrepared(url: String?, vararg objects: Any?) {
                super.onPrepared(url, *objects)
                playerView.post {
                    val player = playerView.getCurrentPlayer()
                    if (VideoPlay.lockCurScreen && !player.getLockCurScreen()) {
                        player.lockTouchLogic()
                    }
                    val curVideoWidth = playerView.currentVideoWidth
                    val curVideoHeight = playerView.currentVideoHeight
                    if (curVideoWidth > 0 && curVideoHeight > 0) {
                        val layoutParams = playerView.layoutParams
                            ?: ViewGroup.LayoutParams(screenWidth, clampedHeight)
                        val parentWidth = playerView.width.takeIf { it > 0 } ?: screenWidth
                        val aspectRatio = curVideoHeight.toFloat() / curVideoWidth.toFloat()
                        val isPortraitVideo = aspectRatio > 1.2f
                        VideoPlay.isPortraitVideo = isPortraitVideo
                        if (isFullScreen && isPortraitVideo) {
                            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                            return@post
                        }
                        if (VideoPlay.startFull && VideoPlay.autoPlay && !isFullScreen) {
                            toggleFullScreen()
                            return@post
                        }
                        val calcHeight = (parentWidth * aspectRatio).toInt()
                        val curScreenHeight = resources.displayMetrics.heightPixels
                        layoutParams.height =
                            if (calcHeight < curScreenHeight / 2) calcHeight else curScreenHeight / 2
                        playerView.layoutParams = layoutParams
                    }
                }
            }
        })
    }

    private fun upStarMenu() {}

    private fun startFloatingWindow() {
        VideoPlay.savePlayState(playerView)
        val intent = Intent(this, VideoPlayService::class.java).apply {
            putExtra("isNew", false)
        }
        ContextCompat.startForegroundService(this, intent)
        playerView.needDestroy = false
        finish()
    }

    override fun observeLiveBus() {
        observeEventSticky<String>(EventBus.VIDEO_SUB_TITLE) {
            titleState = it
        }

        observeEvent<ArrayList<Int>>(EventBus.UP_VIDEO_INFO) {
            it.forEach { value ->
                when (value) {
                    1 -> upEpisodesView()
                }
            }
        }
    }

    override fun finish() {
        val book = VideoPlay.book ?: return super.finish()
        if (VideoPlay.inBookshelf) {
            callBackBookEnd()
            return super.finish()
        }
        if (!AppConfig.showAddToShelfAlert) {
            callBackBookEnd()
            viewModel.removeFromBookshelf { super.finish() }
        } else {
            alert(title = getString(R.string.add_to_bookshelf)) {
                setMessage(getString(R.string.check_add_bookshelf, book.name))
                okButton {
                    VideoPlay.book?.removeType(BookType.notShelf)
                    VideoPlay.book?.save()
                    VideoPlay.inBookshelf = true
                    setResult(RESULT_OK)
                }
                noButton {
                    callBackBookEnd()
                    viewModel.removeFromBookshelf { super.finish() }
                }
            }
        }
    }

    private fun callBackBookEnd() {
        SourceCallBack.callBackBook(
            SourceCallBack.END_READ,
            VideoPlay.source as BookSource?,
            VideoPlay.book,
            VideoPlay.chapter
        )
    }

    override fun onStart() {
        super.onStart()
        if (initGetter) {
            glideImageGetter.start()
        }
    }

    override fun onStop() {
        super.onStop()
        if (initGetter) {
            glideImageGetter.stop()
        }
    }

    override fun onDestroy() {
        destroyWeb()
        super.onDestroy()
        if (initGetter) {
            glideImageGetter.clear()
        }
        if (!forwardedToFloatingWindow) {
            VideoPlay.saveRead()
            VideoPlay.stopLoading()
            playerView.getCurrentPlayer().release()
        }
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun destroyWeb() {
        pooledWebView?.let { WebViewPool.release(it) }
        pooledWebView = null
    }

    @Composable
    private fun VideoPlayerScreen() {
        val context = LocalContext.current
        var overflowExpanded by remember { mutableStateOf(false) }
        val volumeListState = rememberLazyListState()
        val episodeListState = rememberLazyListState()

        LaunchedEffect(selectedVolumeIndexState, volumesState.size) {
            if (selectedVolumeIndexState in volumesState.indices) {
                volumeListState.animateScrollToItem(selectedVolumeIndexState)
            }
        }

        LaunchedEffect(selectedEpisodeIndexState, episodesState.size) {
            if (selectedEpisodeIndexState in episodesState.indices) {
                episodeListState.animateScrollToItem(selectedEpisodeIndexState)
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(context.backgroundColor))
        ) {
            if (!isFullScreen) {
                LegadoTopAppBar(
                    title = titleState,
                    onBack = { finish() },
                    actions = {
                        val iconTint = Color(context.primaryTextColor)
                        if (customBtnVisibleState) {
                            IconButton(
                                onClick = {
                                    (VideoPlay.source as? BookSource)?.let { source ->
                                        VideoPlay.book?.let { book ->
                                            SourceCallBack.callBackBtn(
                                                this@VideoPlayerActivity,
                                                SourceCallBack.CLICK_CUSTOM_BUTTON,
                                                source,
                                                book,
                                                VideoPlay.chapter,
                                                BookType.video
                                            )
                                        }
                                    }
                                }
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_custom),
                                    contentDescription = stringResource(R.string.custom_button),
                                    tint = iconTint
                                )
                            }
                        }

                        IconButton(onClick = { startFloatingWindow() }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_float_window),
                                contentDescription = stringResource(R.string.float_window),
                                tint = iconTint
                            )
                        }

                        Box {
                            IconButton(onClick = { overflowExpanded = true }) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = stringResource(R.string.more_menu),
                                    tint = iconTint
                                )
                            }
                            DropdownMenu(
                                expanded = overflowExpanded,
                                onDismissRequest = { overflowExpanded = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.config_settings)) },
                                    onClick = {
                                        overflowExpanded = false
                                        showDialogFragment(SettingsDialog(this@VideoPlayerActivity))
                                    }
                                )
                                if (VideoPlay.source?.hasLogin() == true) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.login)) },
                                        onClick = {
                                            overflowExpanded = false
                                            VideoPlay.source?.let { s ->
                                                if (s is BookSource) {
                                                    startActivity<SourceLoginActivity> {
                                                        putExtra("bookType", BookType.video)
                                                    }
                                                }
                                            }
                                        }
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.copy_play_url)) },
                                    onClick = {
                                        overflowExpanded = false
                                        val url = VideoPlay.videoUrl
                                        if (url.isNullOrBlank()) {
                                            toastOnUi("暂无播放地址")
                                        } else {
                                            VideoPlay.book?.let {
                                                SourceCallBack.callBackBtn(
                                                    this@VideoPlayerActivity,
                                                    SourceCallBack.CLICK_COPY_PLAY_URL,
                                                    VideoPlay.source as? BookSource,
                                                    it,
                                                    VideoPlay.chapter,
                                                    BookType.video,
                                                    url
                                                ) {
                                                    sendToClip(url)
                                                }
                                            }
                                        }
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.open_other_video_player)) },
                                    onClick = {
                                        overflowExpanded = false
                                        val url = VideoPlay.videoUrl
                                        if (url.isNullOrBlank()) {
                                            toastOnUi("暂无播放地址")
                                        } else {
                                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                                setDataAndType(url.toUri(), "video/*")
                                            }
                                            startActivity(intent)
                                        }
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.edit_book_source)) },
                                    onClick = {
                                        overflowExpanded = false
                                        VideoPlay.source?.let { s ->
                                            if (s is BookSource) {
                                                bookSourceEditResult.launch {
                                                    putExtra("sourceUrl", s.getKey())
                                                }
                                            }
                                        }
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.log)) },
                                    onClick = {
                                        overflowExpanded = false
                                        showDialogFragment<AppLogDialog>()
                                    }
                                )
                            }
                        }
                    }
                )
            }

            AndroidView(
                modifier = Modifier.fillMaxWidth(),
                factory = {
                    (playerView.parent as? ViewGroup)?.removeView(playerView)
                    playerView
                }
            )

            val book = bookState
            if (!isFullScreen && book != null) {
                // Book Data Section
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(8.dp)
                        .padding(bottom = 12.dp)
                ) {
                    AndroidView(
                        modifier = Modifier
                            .width(110.dp)
                            .height(160.dp),
                        factory = {
                            (ivCoverView.parent as? ViewGroup)?.removeView(ivCoverView)
                            ivCoverView
                        }
                    )

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = book.name,
                                color = colorResource(R.color.primaryText),
                                fontSize = 20.sp,
                                maxLines = 1,
                                modifier = Modifier.horizontalScroll(rememberScrollState())
                            )
                        }

                        val realAuthor = book.getRealAuthor()
                        if (realAuthor.isNotEmpty()) {
                            Text(
                                text = realAuthor,
                                color = colorResource(R.color.tv_text_summary),
                                fontSize = 14.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                        ) {
                            AndroidView(
                                modifier = Modifier.fillMaxWidth(),
                                factory = {
                                    (tvIntroContainer.parent as? ViewGroup)?.removeView(tvIntroContainer)
                                    tvIntroContainer
                                }
                            )
                        }
                    }
                }

                // Volumes & Chapters Section
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 20.dp)
                ) {
                    if (volumesState.isNotEmpty()) {
                        LazyRow(
                            state = volumeListState,
                            contentPadding = PaddingValues(4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            itemsIndexed(volumesState) { index, volume ->
                                VideoChapterItem(
                                    title = volume.title,
                                    isSelected = index == selectedVolumeIndexState,
                                    isVolume = true,
                                    onClick = {
                                        if (index != VideoPlay.durVolumeIndex) {
                                            VideoPlay.durVolumeIndex = index
                                            VideoPlay.chapterInVolumeIndex = 0
                                            VideoPlay.upEpisodes()
                                            episodesState = VideoPlay.episodes.orEmpty()
                                            VideoPlay.saveRead(0)
                                            upVolumesView()
                                            upEpisodesView()
                                            VideoPlay.startPlay(playerView)
                                        }
                                    }
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (episodesState.isNotEmpty()) {
                            LazyRow(
                                state = episodeListState,
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(8.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                itemsIndexed(episodesState) { index, chapter ->
                                    VideoChapterItem(
                                        title = chapter.title,
                                        isSelected = index == selectedEpisodeIndexState,
                                        isVolume = false,
                                        onClick = {
                                            if (index != VideoPlay.chapterInVolumeIndex) {
                                                VideoPlay.chapterInVolumeIndex = index
                                                VideoPlay.saveRead(0)
                                                upEpisodesView()
                                                VideoPlay.startPlay(playerView)
                                            }
                                        }
                                    )
                                }
                            }
                        } else {
                            Box(modifier = Modifier.weight(1f))
                        }

                        IconButton(
                            onClick = {
                                VideoPlay.book?.bookUrl?.let {
                                    tocActivityResult.launch(it)
                                }
                            },
                            modifier = Modifier.size(46.dp)
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_chapter_list),
                                contentDescription = stringResource(R.string.chapter_list),
                                tint = colorResource(R.color.primaryText)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VideoChapterItem(
    title: String,
    isSelected: Boolean,
    isVolume: Boolean,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(4.dp)
    val accent = Color(accentColor)
    Box(
        modifier = Modifier
            .width(if (isVolume) 80.dp else 100.dp)
            .height(if (isVolume) 40.dp else 50.dp)
            .padding(2.dp)
            .clip(shape)
            .border(
                width = 1.dp,
                color = if (isSelected) accent else colorResource(R.color.divider),
                shape = shape
            )
            .background(colorResource(R.color.background_card))
            .clickable(onClick = onClick)
            .padding(4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = title,
            color = if (isSelected) accent else colorResource(R.color.primaryText),
            fontSize = if (isVolume) 11.sp else 12.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}
