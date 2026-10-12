package io.legado.app.ui.book.info

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.LeadingMarginSpan
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.textclassifier.TextClassifier
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import io.legado.app.R
import io.legado.app.base.VMBaseComposeActivity
import io.legado.app.constant.BookType
import io.legado.app.constant.EventBus
import io.legado.app.constant.Theme
import io.legado.app.data.appDb
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.AppWebDav
import io.legado.app.help.GlideImageGetter
import io.legado.app.help.TextViewTagHandler
import io.legado.app.help.WebCacheManager
import io.legado.app.help.book.addType
import io.legado.app.help.book.getLocalUri
import io.legado.app.help.book.isAudio
import io.legado.app.help.book.isImage
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isLocalTxt
import io.legado.app.help.book.isVideo
import io.legado.app.help.book.isWebFile
import io.legado.app.help.book.readProgress
import io.legado.app.help.book.removeType
import io.legado.app.help.book.update
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.webView.PooledWebView
import io.legado.app.help.webView.WebJsExtensions
import io.legado.app.help.webView.WebJsExtensions.Companion.getInjectionString
import io.legado.app.help.webView.WebJsExtensions.Companion.nameCache
import io.legado.app.help.webView.WebJsExtensions.Companion.nameJava
import io.legado.app.help.webView.WebJsExtensions.Companion.nameSource
import io.legado.app.help.webView.WebViewPool
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.webdav.WebDavException
import io.legado.app.lib.webdav.isWebDavOverwriteConflict
import io.legado.app.model.SourceCallBack
import io.legado.app.model.remote.RemoteBookWebDav
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.association.OnLineImportActivity
import io.legado.app.ui.book.audio.AudioPlayActivity
import io.legado.app.ui.book.changecover.ChangeCoverDialog
import io.legado.app.ui.book.changesource.ChangeBookSourceDialog
import io.legado.app.ui.book.group.GroupSelectDialog
import io.legado.app.ui.book.info.edit.BookInfoEditActivity
import io.legado.app.ui.book.manga.ReadMangaActivity
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.book.read.ReadBookActivity.Companion.RESULT_DELETED
import io.legado.app.ui.book.search.SearchActivity
import io.legado.app.ui.book.source.edit.BookSourceEditActivity
import io.legado.app.ui.book.toc.TocActivityResult
import io.legado.app.ui.compose.component.LegadoBookCover
import io.legado.app.ui.compose.component.LegadoTopAppBar
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.ui.video.VideoPlayerActivity
import io.legado.app.ui.widget.dialog.PhotoDialog
import io.legado.app.ui.widget.dialog.VariableDialog
import io.legado.app.ui.widget.dialog.WaitDialog
import io.legado.app.ui.widget.text.ScrollTextView
import io.legado.app.utils.ConvertUtils
import io.legado.app.utils.FileDoc
import io.legado.app.utils.GSON
import io.legado.app.utils.StartActivityContract
import io.legado.app.utils.dpToPx
import io.legado.app.utils.getCompatColor
import io.legado.app.utils.longToastOnUi
import io.legado.app.utils.observeEvent
import io.legado.app.utils.openFileUri
import io.legado.app.utils.openUrl
import io.legado.app.utils.sendToClip
import io.legado.app.utils.setHtml
import io.legado.app.utils.setMarkdown
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import io.noties.markwon.Markwon
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.html.HtmlPlugin
import io.noties.markwon.image.glide.GlideImagesPlugin
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class BookInfoActivity :
    VMBaseComposeActivity<BookInfoViewModel>(toolBarTheme = Theme.Dark),
    GroupSelectDialog.CallBack,
    ChangeBookSourceDialog.CallBack,
    ChangeCoverDialog.CallBack,
    VariableDialog.Callback {

    private val tocActivityResult = registerForActivityResult(TocActivityResult()) {
        it?.let {
            readFromChapter(
                index = it[0] as Int,
                pos = it[1] as Int,
                changed = it[2] as Boolean,
                volumeIndex = it[3] as Int,
                chapterInVolumeIndex = it[4] as Int,
                highlightLayoutTitleLength =
                    (it[TocActivityResult.HIGHLIGHT_LAYOUT_TITLE_LENGTH_INDEX] as Int)
                        .takeUnless { titleLength ->
                            titleLength == TocActivityResult.NO_HIGHLIGHT_LAYOUT_TITLE_LENGTH
                        },
                highlightAnchorText =
                    (it[TocActivityResult.HIGHLIGHT_ANCHOR_TEXT_INDEX] as String)
                        .takeIf(String::isNotEmpty),
            )
        } ?: let {
            if (!viewModel.inBookshelf) {
                viewModel.delBook() //进目录会保存book，此时退出目录触发的book删除，不通知书源回调
            }
        }
    }
    private val localBookTreeSelect = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { treeUri ->
            AppConfig.defaultBookTreeUri = treeUri.toString()
        }
    }
    private val readBookResult = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.upBook(intent)
        when (it.resultCode) {
            RESULT_OK -> {
                viewModel.inBookshelf = true
                upTvBookshelf()
            }

            RESULT_DELETED -> {
                setResult(RESULT_OK)
                finish()
            }
        }
    }
    private val infoEditResult = registerForActivityResult(
        StartActivityContract(BookInfoEditActivity::class.java)
    ) {
        if (it.resultCode == RESULT_OK) {
            viewModel.upEditBook()
        }
    }
    private val editSourceResult = registerForActivityResult(
        StartActivityContract(BookSourceEditActivity::class.java)
    ) {
        if (it.resultCode == RESULT_CANCELED) {
            return@registerForActivityResult
        }
        book?.let { book ->
            viewModel.bookSource = appDb.bookSourceDao.getBookSource(book.origin)?.also { source ->
                viewModel.hasCustomBtn = source.customButton
            }
            viewModel.refreshBook(book)
        }
    }
    private var chapterChanged = false
    private val waitDialog by lazy { WaitDialog(this) }
    private val book get() = viewModel.getBook(false)

    override val viewModel by viewModels<BookInfoViewModel>()

    private var currentBookState by mutableStateOf<Book?>(null)
    private var isLoadingState by mutableStateOf(false)
    private var inBookshelfState by mutableStateOf(false)
    private var hasCustomBtnState by mutableStateOf(false)
    private var tocTextState by mutableStateOf("")
    private var lastedTextState by mutableStateOf("")
    private var groupTextState by mutableStateOf("")
    private var kindsState by mutableStateOf<List<String>>(emptyList())
    private var coverRefreshVersion by mutableIntStateOf(0)
    private var introVisibleState by mutableStateOf(false)
    private var introToggleVisibleState by mutableStateOf(false)
    private var introToggleExpandedState by mutableStateOf(true)

    private val introContainer by lazy {
        FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
    }

    private var isIntroTextViewAttached = false
    private var introContent: String? = null
    private var introExpanded = false
    private var introCanCollapse = false
    private var introRenderGeneration = 0
    private var introRenderJob: Job? = null
    private val introTextViewDelegate = lazy {
        val view = ScrollTextView(this, null).apply {
            isClickable = true
            isFocusable = true
            minimumHeight = 48.dpToPx()
            setPaddingRelative(8.dpToPx(), 0, 0, 8.dpToPx())
            setTextColor(getCompatColor(R.color.secondaryText))
            setTextIsSelectable(true)
            textSize = 14f
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            view.revealOnFocusHint = false
        }
        view.onTextLayoutChanged = {
            updateIntroOverflow()
        }
        view
    }
    private val introTextView by introTextViewDelegate

    private var pooledWebView: PooledWebView? = null

    private val imgAvailableWidth by lazy {
        val textView = introTextView
        val w = textView.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        w - textView.paddingLeft - textView.paddingRight - 8.dpToPx()
    }
    private var initGetter = false
    private val glideImageGetter by lazy {
        initGetter = true
        GlideImageGetter(
            this,
            introTextView,
            lifecycle,
            imgAvailableWidth,
            viewModel.bookSource?.bookSourceUrl
        )
    }

    private val textViewTagHandler by lazy {
        TextViewTagHandler(object : TextViewTagHandler.OnButtonClickListener {
            override fun onButtonClick(name: String, click: String) {
                viewModel.onButtonClick(this@BookInfoActivity, "info button $name", click)
            }
        })
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        tocTextState = getString(R.string.toc_s, getString(R.string.loading))
        viewModel.bookData.observe(this) { showBook(it) }
        viewModel.chapterListData.observe(this) {
            upLoading(viewModel.loadingData.value == true, it)
        }
        viewModel.loadingData.observe(this) { isLoading ->
            isLoadingState = isLoading
            if (isLoading) {
                upLoading(true)
            } else {
                viewModel.chapterListData.value?.let { upLoading(false, it) }
            }
        }
        viewModel.waitDialogData.observe(this) { upWaitDialogStatus(it) }
        viewModel.initData(intent)
    }

    override fun observeLiveBus() {
        viewModel.actionLive.observe(this) {
            when (it) {
                "selectBooksDir" -> localBookTreeSelect.launch {
                    title = getString(R.string.select_book_folder)
                }
            }
        }

        observeEvent<Boolean>(EventBus.REFRESH_BOOK_INFO) {
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                refreshBook()
            }
        }

        observeEvent<Boolean>(EventBus.REFRESH_BOOK_TOC) {
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                refreshToc()
            }
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (isIntroTextViewAttached && ev.action == MotionEvent.ACTION_DOWN) {
            currentFocus?.let {
                if (it === introTextView && introTextView.hasSelection()) {
                    it.clearFocus()
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun refreshBook() {
        upLoading(true)
        viewModel.getBook()?.let {
            viewModel.refreshBook(it)
        }
    }

    private fun refreshToc() {
        upLoading(true)
        viewModel.getBook()?.let {
            viewModel.loadChapter(it, true, isFromBookInfo = true)
        }
    }

    private fun upLoadBook(
        book: Book,
        bookWebDav: RemoteBookWebDav? = AppWebDav.defaultBookWebDav,
        overwrite: Boolean = true,
        onConflict: (() -> Unit)? = null,
        onFinished: () -> Unit = {},
    ) {
        lifecycleScope.launch {
            waitDialog.setText(R.string.loading)
            waitDialog.show()
            var uploadConflict = false
            try {
                bookWebDav
                    ?.upload(book, overwrite)
                    ?: throw NoStackTraceException(getString(R.string.webdav_not_configured))
                book.lastCheckTime = System.currentTimeMillis()
                viewModel.saveBook(book)
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                if (!overwrite &&
                    e is WebDavException &&
                    isWebDavOverwriteConflict(e.responseCode)
                ) {
                    uploadConflict = true
                } else {
                    toastOnUi(e.localizedMessage)
                }
            } finally {
                waitDialog.dismiss()
            }
            if (uploadConflict) {
                onConflict?.invoke() ?: onFinished()
            } else {
                onFinished()
            }
        }
    }

    private fun confirmAndUploadBook(
        book: Book,
        onFinished: () -> Unit = {},
    ) {
        val bookWebDav = AppWebDav.defaultBookWebDav
        if (bookWebDav == null) {
            toastOnUi(R.string.webdav_not_configured)
            onFinished()
            return
        }
        lifecycleScope.launch {
            waitDialog.setText(R.string.loading)
            waitDialog.show()
            val remoteExists = try {
                withContext(IO) { bookWebDav.hasRemoteBook(book) }
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                toastOnUi(e.localizedMessage)
                onFinished()
                return@launch
            } finally {
                waitDialog.dismiss()
            }
            if (!remoteExists) {
                upLoadBook(
                    book = book,
                    bookWebDav = bookWebDav,
                    overwrite = false,
                    onConflict = {
                        showUploadOverwriteConfirm(book, bookWebDav, onFinished)
                    },
                    onFinished = onFinished,
                )
                return@launch
            }
            showUploadOverwriteConfirm(book, bookWebDav, onFinished)
        }
    }

    private fun showUploadOverwriteConfirm(
        book: Book,
        bookWebDav: RemoteBookWebDav,
        onFinished: () -> Unit,
    ) {
        alert(R.string.draw, R.string.webdav_book_exists_confirm) {
            yesButton {
                upLoadBook(
                    book = book,
                    bookWebDav = bookWebDav,
                    overwrite = true,
                    onFinished = onFinished,
                )
            }
            noButton { onFinished() }
            onCancelled { onFinished() }
        }
    }

    private fun showBook(book: Book) {
        currentBookState = book
        showCover(book)
        lastedTextState = if (book.isWebFile) {
            getString(R.string.lasted_show, "下载中...")
        } else {
            getString(R.string.lasted_show, book.latestChapterTitle)
        }
        showBookIntro(book)
        hasCustomBtnState = viewModel.hasCustomBtn
        upTvBookshelf()
        upKinds(book)
        upGroup(book.group)
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
                        alert(R.string.draw, R.string.jump_to_another_app) {
                            yesButton { openUrl(uri) }
                            noButton()
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
                introContainer.requestLayout()
            }
        }
    }

    private fun showBookIntro(book: Book) {
        val intro = book.getDisplayIntro()
        if (intro.isNullOrBlank()) {
            introContent = null
            introExpanded = false
            introCanCollapse = false
            introRenderGeneration++
            introRenderJob?.cancel()
            destroyWeb()
            introContainer.removeAllViews()
            isIntroTextViewAttached = false
            introVisibleState = false
            updateIntroToggle()
            return
        }
        if (intro != introContent) {
            introContent = intro
            introExpanded = true
            introCanCollapse = false
        }
        val renderGeneration = ++introRenderGeneration
        introRenderJob?.cancel()
        introVisibleState = true
        if (intro.startsWith("<useweb>")) {
            val lastIndex = intro.lastIndexOf("<")
            if (lastIndex < 8) {
                val tvIntro = prepareIntroTextView()
                tvIntro.text = intro
                scheduleIntroOverflowCheck(renderGeneration)
                return
            }
            introCanCollapse = false
            updateIntroToggle()
            val html = intro.substring(8, lastIndex)
            val pooledWebView = this.pooledWebView ?: let {
                val pooledWebView = WebViewPool.acquire(this)
                val webView = pooledWebView.realWebView
                webView.onResume()
                webView.webViewClient = CustomWebViewClient()
                webView.addJavascriptInterface(WebCacheManager, nameCache)
                viewModel.bookSource?.let {
                    webView.addJavascriptInterface(it as BaseSource, nameSource)
                    val webJsExtensions = WebJsExtensions(it, null, webView)
                    webView.addJavascriptInterface(webJsExtensions, nameJava)
                }
                pooledWebView
            }
            val webView = pooledWebView.realWebView
            if (isIntroTextViewAttached || this.pooledWebView == null) {
                isIntroTextViewAttached = false
                this.pooledWebView = pooledWebView
                introContainer.removeAllViews()
                (webView.parent as? ViewGroup)?.removeView(webView)
                introContainer.addView(webView)
            }
            val bookUrl = viewModel.getBook()?.bookUrl
                ?.takeIf { it.startsWith("http", true) }
                ?.substringBefore(",")
            webView.loadDataWithBaseURL(bookUrl, html, "text/html", "utf-8", bookUrl)
            return
        }
        val tvIntro = prepareIntroTextView()
        if (intro.startsWith("<usehtml>")) {
            val lastIndex = intro.lastIndexOf("<")
            if (lastIndex < 9) {
                tvIntro.text = intro
                scheduleIntroOverflowCheck(renderGeneration)
                return
            }
            val html = intro.substring(9, lastIndex)
            tvIntro.setHtml(
                html,
                glideImageGetter,
                textViewTagHandler,
                imgOnLongClickListener = {
                    showDialogFragment(PhotoDialog(it, viewModel.bookSource?.bookSourceUrl))
                },
                imgOnClickListener = {
                    viewModel.onButtonClick(this@BookInfoActivity, "info image", it)
                }
            )
            scheduleIntroOverflowCheck(renderGeneration)
        } else if (intro.startsWith("<md>")) {
            val lastIndex = intro.lastIndexOf("<")
            if (lastIndex < 4) {
                tvIntro.text = intro
                scheduleIntroOverflowCheck(renderGeneration)
                return
            }
            val mark = intro.substring(4, lastIndex)
            tvIntro.text = null
            introRenderJob = lifecycleScope.launch {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    tvIntro.setTextClassifier(TextClassifier.NO_OP)
                }
                val context = this@BookInfoActivity
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
                if (renderGeneration != introRenderGeneration) return@launch
                tvIntro.setMarkdown(
                    markwon,
                    markdown,
                    imgOnLongClickListener = { source ->
                        showDialogFragment(PhotoDialog(source, viewModel.bookSource?.bookSourceUrl))
                    }
                )
                scheduleIntroOverflowCheck(renderGeneration)
            }
        } else {
            setPlainBookIntro(tvIntro, intro)
            scheduleIntroOverflowCheck(renderGeneration)
        }
    }

    private fun setPlainBookIntro(textView: ScrollTextView, intro: String) {
        val ranges = introIndentRanges(intro)
        if (ranges.isEmpty()) {
            textView.text = intro
            return
        }
        val indentWidth = textView.paint.measureText("　　").roundToInt().coerceAtLeast(1)
        textView.text = SpannableString(intro).apply {
            ranges.forEach { range ->
                setSpan(
                    LeadingMarginSpan.Standard(indentWidth, 0),
                    range.start,
                    range.endExclusive,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        }
    }

    private fun prepareIntroTextView(): ScrollTextView {
        val tvIntro = introTextView
        if (!isIntroTextViewAttached || pooledWebView != null) {
            destroyWeb()
            introContainer.removeAllViews()
            (tvIntro.parent as? ViewGroup)?.removeView(tvIntro)
            tvIntro.text = null
            introContainer.addView(tvIntro)
            isIntroTextViewAttached = true
        }
        applyIntroCollapseState(tvIntro)
        return tvIntro
    }

    private fun applyIntroCollapseState(tvIntro: ScrollTextView = introTextView) {
        tvIntro.internalScrollEnabled = false
        tvIntro.maxLines = if (introExpanded) Int.MAX_VALUE else BookIntroCollapse.COLLAPSED_LINES
        tvIntro.maxMeasuredHeight = if (introExpanded) {
            null
        } else {
            collapsedIntroHeight(tvIntro)
        }
        tvIntro.ellipsize = if (introExpanded) null else TextUtils.TruncateAt.END
        if (!introExpanded) {
            tvIntro.scrollTo(0, 0)
        }
        updateIntroToggle()
        tvIntro.requestLayout()
    }

    private fun collapsedIntroHeight(tvIntro: ScrollTextView): Int =
        tvIntro.lineHeight * BookIntroCollapse.COLLAPSED_LINES +
            tvIntro.compoundPaddingTop + tvIntro.compoundPaddingBottom

    private fun scheduleIntroOverflowCheck(renderGeneration: Int) {
        introTextView.post {
            if (renderGeneration == introRenderGeneration &&
                lifecycle.currentState != Lifecycle.State.DESTROYED
            ) {
                updateIntroOverflow()
            }
        }
    }

    private fun updateIntroOverflow() {
        if (!isIntroTextViewAttached || pooledWebView != null) return
        val tvIntro = introTextView
        val textLayout = tvIntro.layout ?: return
        val lineCount = textLayout.lineCount
        if (lineCount <= 0) return
        val lastLine = lineCount - 1
        val collapsedHeight = collapsedIntroHeight(tvIntro)
        val canCollapse = tvIntro.isMeasuredHeightLimited || BookIntroCollapse.hasOverflow(
            expanded = introExpanded,
            lineCount = lineCount,
            lastLineEllipsisCount = textLayout.getEllipsisCount(lastLine),
            lastLineEnd = textLayout.getLineEnd(lastLine),
            textLength = tvIntro.text.length,
            contentHeight = textLayout.height +
                tvIntro.compoundPaddingTop + tvIntro.compoundPaddingBottom,
            collapsedContentHeight = collapsedHeight,
        )
        if (introCanCollapse != canCollapse) {
            introCanCollapse = canCollapse
            updateIntroToggle()
        }
    }

    private fun updateIntroToggle() {
        if (!introCanCollapse || !isIntroTextViewAttached || pooledWebView != null) {
            introToggleVisibleState = false
            return
        }
        introToggleExpandedState = introExpanded
        introToggleVisibleState = true
    }

    private fun upKinds(book: Book) {
        lifecycleScope.launch {
            var kinds = book.getKindList()
            if (book.isLocal) {
                withContext(IO) {
                    val size = try {
                        FileDoc.fromUri(book.getLocalUri(), false).size
                    } catch (e: Exception) {
                        currentCoroutineContext().ensureActive()
                        0L
                    }
                    if (size > 0) {
                        kinds = kinds.toMutableList()
                        kinds.add(ConvertUtils.formatFileSize(size))
                    }
                }
            }
            if (kinds.isEmpty()) {
                kindsState = emptyList()
            } else {
                kindsState = kinds
            }
        }
    }

    private fun showCover(book: Book) {
        currentBookState = book
        coverRefreshVersion++
    }

    private fun upLoading(isLoading: Boolean, chapterList: List<BookChapter>? = null) {
        when {
            isLoading -> {
                tocTextState = getString(R.string.toc_s, getString(R.string.loading))
            }

            chapterList.isNullOrEmpty() -> {
                tocTextState = getString(
                    R.string.toc_s,
                    getString(R.string.error_load_toc)
                )
                lastedTextState = getString(R.string.lasted_show, book?.latestChapterTitle)
            }

            else -> {
                book?.let {
                    val tocTitle = resolveBookInfoTocTitle(
                        it.durChapterTitle,
                        it.durChapterIndex,
                        chapterList,
                    ) ?: getString(R.string.no_last_chapter)
                    val readStatus = resolveBookInfoReadProgress(it)?.let { percent ->
                        getString(R.string.read_y, "$percent%")
                    }
                    tocTextState = getString(
                        R.string.toc_s,
                        listOfNotNull(tocTitle, readStatus).joinToString("  ·  "),
                    )
                    lastedTextState = getString(R.string.lasted_show, it.latestChapterTitle)
                }
            }
        }
    }

    private fun upTvBookshelf() {
        inBookshelfState = viewModel.inBookshelf
    }

    private fun upGroup(groupId: Long) {
        viewModel.loadGroup(groupId) {
            groupTextState = if (it.isNullOrEmpty()) {
                if (book?.isLocal == true) {
                    getString(R.string.group_s, getString(R.string.local_no_group))
                } else {
                    getString(R.string.group_s, getString(R.string.no_group))
                }
            } else {
                getString(R.string.group_s, it)
            }
        }
    }

    private fun onTocViewClick() {
        if (viewModel.chapterListData.value.isNullOrEmpty()) {
            toastOnUi(R.string.chapter_list_empty)
            return
        }
        val book = viewModel.getBook(false)
        if (book == null) {
            toastOnUi(R.string.book_not_exist)
            return
        }
        if (!viewModel.inBookshelf) {
            viewModel.saveBook(book) { //点击目录会保存book
                viewModel.saveChapterList {
                    openChapterList()
                }
            }
        } else {
            openChapterList()
        }
    }

    private fun setSourceVariable() {
        lifecycleScope.launch {
            val source = viewModel.bookSource
            if (source == null) {
                toastOnUi("书源不存在")
                return@launch
            }
            val comment =
                source.getDisplayVariableComment("源变量可在js中通过source.getVariable()获取")
            val variable = withContext(IO) { source.getVariable() }
            showDialogFragment(
                VariableDialog(
                    getString(R.string.set_source_variable),
                    source.getKey(),
                    variable,
                    comment
                )
            )
        }
    }

    private fun setBookVariable() {
        lifecycleScope.launch {
            val source = viewModel.bookSource
            if (source == null) {
                toastOnUi("书源不存在")
                return@launch
            }
            val book = viewModel.getBook() ?: return@launch
            val variable = withContext(IO) { book.getCustomVariable() }
            val comment = source.getDisplayVariableComment(
                """书籍变量可在js中通过book.getVariable("custom")获取"""
            )
            showDialogFragment(
                VariableDialog(
                    getString(R.string.set_book_variable),
                    book.bookUrl,
                    variable,
                    comment
                )
            )
        }
    }

    override fun setVariable(key: String, variable: String?) {
        when (key) {
            viewModel.bookSource?.getKey() -> viewModel.bookSource?.setVariable(variable)
            viewModel.bookData.value?.bookUrl -> viewModel.bookData.value?.let {
                it.putCustomVariable(variable)
                if (viewModel.inBookshelf) {
                    viewModel.saveBook(it)
                }
            }
        }
    }

    @SuppressLint("InflateParams")
    private fun deleteBook() {
        viewModel.getBook()?.let { book ->
            if (LocalConfig.bookInfoDeleteAlert) {
                alert(
                    titleResource = R.string.draw,
                    messageResource = R.string.sure_del
                ) {
                    var deleteOriginalCheckBox: CheckBox? = null
                    var deleteRemoteCheckBox: CheckBox? = null
                    if (book.isLocal) {
                        deleteOriginalCheckBox = CheckBox(this@BookInfoActivity).apply {
                            setText(R.string.delete_book_file)
                            isChecked = LocalConfig.deleteBookOriginal
                        }
                        val view = LinearLayout(this@BookInfoActivity).apply {
                            orientation = LinearLayout.VERTICAL
                            setPadding(16.dpToPx(), 0, 16.dpToPx(), 0)
                            addView(deleteOriginalCheckBox)
                            if (AppWebDav.defaultBookWebDav != null) {
                                deleteRemoteCheckBox = CheckBox(this@BookInfoActivity).apply {
                                    setText(R.string.delete_webdav_book_file)
                                }
                                addView(deleteRemoteCheckBox)
                            }
                        }
                        customView { view }
                    }
                    yesButton {
                        if (deleteOriginalCheckBox != null) {
                            LocalConfig.deleteBookOriginal = deleteOriginalCheckBox.isChecked
                        }
                        deleteBook(
                            book = book,
                            deleteOriginal = LocalConfig.deleteBookOriginal,
                            deleteRemote = deleteRemoteCheckBox?.isChecked == true,
                        )
                    }
                    noButton()
                }
            } else {
                deleteBook(book, LocalConfig.deleteBookOriginal, deleteRemote = false)
            }
        }
    }

    private fun deleteBook(book: Book, deleteOriginal: Boolean, deleteRemote: Boolean) {
        if (!deleteRemote) {
            finishDeleteBook(book, deleteOriginal)
            return
        }
        val bookWebDav = AppWebDav.defaultBookWebDav
        if (bookWebDav == null) {
            toastOnUi(R.string.webdav_not_configured)
            return
        }
        lifecycleScope.launch {
            waitDialog.setText(R.string.loading)
            waitDialog.show()
            val deleted = try {
                withContext(IO) { bookWebDav.delete(book) }
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                toastOnUi(e.localizedMessage ?: getString(R.string.delete_webdav_book_file_fail))
                return@launch
            } finally {
                waitDialog.dismiss()
            }
            if (!deleted) {
                toastOnUi(R.string.delete_webdav_book_file_fail)
                return@launch
            }
            finishDeleteBook(book, deleteOriginal)
        }
    }

    private fun finishDeleteBook(book: Book, deleteOriginal: Boolean) {
        SourceCallBack.callBackBook(SourceCallBack.DEL_BOOK_SHELF, viewModel.bookSource, book)
        viewModel.delBook(deleteOriginal) {
            setResult(RESULT_OK)
            finish()
        }
    }

    private fun openChapterList() {
        viewModel.getBook()?.let {
            tocActivityResult.launch(it.bookUrl)
        }
    }

    private fun readFromChapter(
        index: Int,
        pos: Int,
        changed: Boolean,
        volumeIndex: Int,
        chapterInVolumeIndex: Int,
        highlightLayoutTitleLength: Int?,
        highlightAnchorText: String?,
    ) {
        viewModel.getBook(false)?.let { book ->
            val deferHighlightPosition = highlightLayoutTitleLength != null &&
                !book.isAudio && !book.isVideo &&
                (book.isLocal || !book.isImage || !AppConfig.showMangaUi)
            if (!deferHighlightPosition) {
                book.durChapterIndex = index
                book.durChapterPos = pos
                book.durVolumeIndex = volumeIndex
                book.chapterInVolumeIndex = chapterInVolumeIndex
            }
            chapterChanged = changed
            if (!viewModel.inBookshelf) {
                book.addType(BookType.notShelf)
                lifecycleScope.launch {
                    withContext(IO) {
                        book.save()
                    }
                    viewModel.saveChapterList {
                        startReadActivity(
                            book,
                            index.takeIf { deferHighlightPosition },
                            pos.takeIf { deferHighlightPosition },
                            highlightLayoutTitleLength.takeIf { deferHighlightPosition },
                            highlightAnchorText.takeIf { deferHighlightPosition }
                        )
                    }
                }
            } else {
                lifecycleScope.launch {
                    withContext(IO) {
                        book.update()
                    }
                    startReadActivity(
                        book,
                        index.takeIf { deferHighlightPosition },
                        pos.takeIf { deferHighlightPosition },
                        highlightLayoutTitleLength.takeIf { deferHighlightPosition },
                        highlightAnchorText.takeIf { deferHighlightPosition }
                    )
                }
            }
        }
    }

    private fun showWebFileDownloadAlert(
        onClick: ((Book) -> Unit)? = null,
    ) {
        val webFiles = viewModel.webFiles
        if (webFiles.isEmpty()) {
            toastOnUi("Unexpected webFileData")
            return
        }
        val uploadCheckBox = CheckBox(this).apply {
            setText(R.string.upload_imported_book_to_webdav)
            isChecked = LocalConfig.uploadImportedBookToWebDav
            setOnCheckedChangeListener { _, isChecked ->
                LocalConfig.uploadImportedBookToWebDav = isChecked
            }
        }
        alert(titleResource = R.string.download_and_import_file) {
            items(webFiles) { _, webFile, _ ->
                val uploadToWebDav = uploadCheckBox.isChecked
                if (webFile.isSupported) {
                    viewModel.importOrDownloadWebFile<Book>(webFile) {
                        onWebBookImported(it, uploadToWebDav, onClick)
                    }
                } else if (webFile.isSupportDecompress) {
                    viewModel.importOrDownloadWebFile<Uri>(webFile) { uri ->
                        viewModel.getArchiveFilesName(uri) { fileNames ->
                            if (fileNames.size == 1) {
                                viewModel.importArchiveBook(uri, fileNames[0]) {
                                    onWebBookImported(it, uploadToWebDav, onClick)
                                }
                            } else {
                                showDecompressFileImportAlert(uri, fileNames) {
                                    onWebBookImported(it, uploadToWebDav, onClick)
                                }
                            }
                        }
                    }
                } else {
                    alert(
                        title = getString(R.string.draw),
                        message = getString(R.string.file_not_supported, webFile.name)
                    ) {
                        neutralButton(R.string.open_fun) {
                            viewModel.importOrDownloadWebFile<Uri>(webFile) {
                                openFileUri(it, "*/*")
                            }
                        }
                        noButton()
                    }
                }
            }
            customView {
                LinearLayout(this@BookInfoActivity).apply {
                    setPadding(16.dpToPx(), 0, 16.dpToPx(), 8.dpToPx())
                    addView(uploadCheckBox)
                }
            }
        }
    }

    private fun onWebBookImported(
        book: Book,
        uploadToWebDav: Boolean,
        onClick: ((Book) -> Unit)?,
    ) {
        val onFinished: () -> Unit = { onClick?.invoke(book) }
        if (uploadToWebDav) {
            confirmAndUploadBook(book, onFinished)
        } else {
            onFinished()
        }
    }

    private fun showDecompressFileImportAlert(
        archiveFileUri: Uri,
        fileNames: List<String>,
        success: ((Book) -> Unit)? = null,
    ) {
        if (fileNames.isEmpty()) {
            toastOnUi(R.string.unsupport_archivefile_entry)
            return
        }
        selector(
            R.string.import_select_book,
            fileNames
        ) { _, name, _ ->
            viewModel.importArchiveBook(archiveFileUri, name) {
                success?.invoke(it)
            }
        }
    }

    private fun readBook(book: Book) {
        if (!viewModel.inBookshelf) {
            book.addType(BookType.notShelf)
            viewModel.saveBook(book) {
                viewModel.saveChapterList {
                    startReadActivity(book)
                }
            }
        } else {
            viewModel.saveBook(book) {
                startReadActivity(book)
            }
        }
    }

    private fun startReadActivity(
        book: Book,
        highlightIndex: Int? = null,
        highlightChapterPos: Int? = null,
        highlightLayoutTitleLength: Int? = null,
        highlightAnchorText: String? = null,
    ) {
        when {
            book.isAudio -> readBookResult.launch(
                Intent(this, AudioPlayActivity::class.java)
                    .putExtra("bookUrl", book.bookUrl)
                    .putExtra("inBookshelf", viewModel.inBookshelf)
            )
            book.isVideo -> readBookResult.launch(
                Intent(this, VideoPlayerActivity::class.java)
                    .putExtra("bookUrl", book.bookUrl)
                    .putExtra("inBookshelf", viewModel.inBookshelf)
            )

            else -> readBookResult.launch(
                Intent(
                    this,
                    if (!book.isLocal && book.isImage && AppConfig.showMangaUi) ReadMangaActivity::class.java
                    else ReadBookActivity::class.java
                ).apply {
                    putExtra("bookUrl", book.bookUrl)
                    putExtra("inBookshelf", viewModel.inBookshelf)
                    putExtra("chapterChanged", chapterChanged)
                    if (highlightIndex != null &&
                        highlightChapterPos != null &&
                        highlightLayoutTitleLength != null
                    ) {
                        putExtra("index", highlightIndex)
                        putExtra("chapterPos", highlightChapterPos)
                        putExtra(
                            TocActivityResult.EXTRA_HIGHLIGHT_LAYOUT_TITLE_LENGTH,
                            highlightLayoutTitleLength
                        )
                        highlightAnchorText?.let {
                            putExtra(TocActivityResult.EXTRA_HIGHLIGHT_ANCHOR_TEXT, it)
                        }
                    }
                }
            )
        }
    }

    override val oldBook: Book?
        get() = viewModel.bookData.value

    override fun changeTo(
        source: BookSource,
        book: Book,
        toc: List<BookChapter>,
        onSuccess: () -> Unit,
    ) {
        viewModel.changeTo(source, book, toc, onSuccess)
    }

    override fun coverChangeTo(coverUrl: String) {
        viewModel.bookData.value?.let { book ->
            book.customCoverUrl = coverUrl
            book.persistedCoverUrl = null
            showCover(book)
            if (viewModel.inBookshelf) {
                viewModel.saveBook(book, preserveCustomCoverUrl = false)
            }
        }
    }

    override fun upGroup(requestCode: Int, groupId: Long) {
        upGroup(groupId)
        viewModel.getBook()?.let { book ->
            book.group = groupId
            if (viewModel.inBookshelf) {
                viewModel.saveBook(book)
            } else if (groupId > 0) {
                viewModel.addToBookshelf {
                    upTvBookshelf()
                }
            }
        }
    }

    private fun upWaitDialogStatus(isShow: Boolean) {
        val showText = "Loading....."
        if (isShow) {
            waitDialog.run {
                setText(showText)
                show()
            }
        } else {
            waitDialog.dismiss()
        }
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
        introRenderJob?.cancel()
        if (introTextViewDelegate.isInitialized()) {
            introTextView.onTextLayoutChanged = null
        }
        destroyWeb()
        super.onDestroy()
        if (initGetter) {
            glideImageGetter.clear()
        }
    }

    private fun destroyWeb() {
        pooledWebView?.let { WebViewPool.release(it) }
        pooledWebView = null
    }

    @Composable
    override fun Content() {
        val currentBook = currentBookState
        val configuration = LocalConfiguration.current
        val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        var showMenu by remember { mutableStateOf(false) }

        var canUpdateChecked by remember(currentBook, showMenu) {
            mutableStateOf(currentBook?.canUpdate ?: true)
        }
        var splitLongChapterChecked by remember(currentBook, showMenu) {
            mutableStateOf(currentBook?.getSplitLongChapter() ?: true)
        }
        var deleteAlertChecked by remember(showMenu) {
            mutableStateOf(LocalConfig.bookInfoDeleteAlert)
        }

        Scaffold(
            topBar = {
                LegadoTopAppBar(
                    title = stringResource(R.string.book_info),
                    onNavigationClick = { finish() },
                    actions = {
                        if (hasCustomBtnState) {
                            TextButton(
                                onClick = {
                                    viewModel.bookSource?.customButton?.let {
                                        viewModel.getBook()?.let { b ->
                                            SourceCallBack.callBackBtn(
                                                this@BookInfoActivity,
                                                SourceCallBack.CLICK_CUSTOM_BUTTON,
                                                viewModel.bookSource,
                                                b,
                                                null
                                            )
                                        }
                                    }
                                }
                            ) {
                                Text(stringResource(R.string.custom_button))
                            }
                        }
                        if (inBookshelfState) {
                            IconButton(
                                onClick = {
                                    viewModel.getBook()?.let {
                                        infoEditResult.launch {
                                            putExtra("bookUrl", it.bookUrl)
                                        }
                                    }
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Edit,
                                    contentDescription = stringResource(R.string.edit),
                                )
                            }
                        }
                        IconButton(
                            onClick = {
                                viewModel.getBook()?.let {
                                    val bookJson = GSON.toJson(it)
                                    val shareStr = "${it.bookUrl}#$bookJson"
                                    SourceCallBack.callBackBtn(
                                        this@BookInfoActivity,
                                        SourceCallBack.CLICK_SHARE_BOOK,
                                        viewModel.bookSource,
                                        it,
                                        null,
                                        result = shareStr
                                    ) {
                                        val intent = Intent(Intent.ACTION_SEND)
                                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        intent.putExtra(Intent.EXTRA_TEXT, shareStr)
                                        intent.type = "text/plain"
                                        startActivity(Intent.createChooser(intent, it.name))
                                    }
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = stringResource(R.string.share),
                            )
                        }
                        IconButton(onClick = ::refreshBook) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = stringResource(R.string.refresh),
                            )
                        }
                        IconButton(onClick = { showMenu = true }) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = stringResource(R.string.more_menu),
                            )
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                        ) {
                            if (viewModel.bookSource?.hasLogin() == true) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.login)) },
                                    onClick = {
                                        showMenu = false
                                        viewModel.bookSource?.let {
                                            startActivity<SourceLoginActivity> {
                                                putExtra("type", "bookSource")
                                                putExtra("key", it.bookSourceUrl)
                                                putExtra("bookUrl", book?.bookUrl)
                                            }
                                        }
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.to_top)) },
                                onClick = {
                                    showMenu = false
                                    viewModel.topBook()
                                },
                            )
                            if (viewModel.bookSource != null) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.set_source_variable)) },
                                    onClick = {
                                        showMenu = false
                                        setSourceVariable()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.set_book_variable)) },
                                    onClick = {
                                        showMenu = false
                                        setBookVariable()
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.copy_book_url)) },
                                onClick = {
                                    showMenu = false
                                    viewModel.getBook()?.let {
                                        SourceCallBack.callBackBtn(
                                            this@BookInfoActivity,
                                            SourceCallBack.CLICK_COPY_BOOK_URL,
                                            viewModel.bookSource,
                                            it,
                                            null,
                                            result = it.bookUrl
                                        ) {
                                            sendToClip(it.bookUrl)
                                        }
                                    }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.copy_toc_url)) },
                                onClick = {
                                    showMenu = false
                                    viewModel.getBook()?.let {
                                        SourceCallBack.callBackBtn(
                                            this@BookInfoActivity,
                                            SourceCallBack.CLICK_COPY_TOC_URL,
                                            viewModel.bookSource,
                                            it,
                                            null,
                                            result = it.tocUrl
                                        ) {
                                            sendToClip(it.tocUrl)
                                        }
                                    }
                                },
                            )
                            if (viewModel.bookSource != null) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.allow_update)) },
                                    trailingIcon = {
                                        Checkbox(
                                            checked = canUpdateChecked,
                                            onCheckedChange = null,
                                        )
                                    },
                                    onClick = {
                                        showMenu = false
                                        viewModel.getBook()?.let {
                                            val next = !it.canUpdate
                                            canUpdateChecked = next
                                            it.canUpdate = next
                                            if (viewModel.inBookshelf) {
                                                if (!it.canUpdate) {
                                                    it.removeType(BookType.updateError)
                                                }
                                                viewModel.saveBook(it)
                                            }
                                        }
                                    },
                                )
                            }
                            if (currentBook?.isLocalTxt == true) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.split_long_chapter)) },
                                    trailingIcon = {
                                        Checkbox(
                                            checked = splitLongChapterChecked,
                                            onCheckedChange = null,
                                        )
                                    },
                                    onClick = {
                                        showMenu = false
                                        val next = !splitLongChapterChecked
                                        splitLongChapterChecked = next
                                        upLoading(true)
                                        viewModel.getBook()?.let {
                                            it.setSplitLongChapter(next)
                                            viewModel.loadBookInfo(it, false)
                                        }
                                        if (!next) longToastOnUi(R.string.need_more_time_load_content)
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.clear_cache)) },
                                onClick = {
                                    showMenu = false
                                    viewModel.getBook()?.let {
                                        SourceCallBack.callBackBtn(
                                            this@BookInfoActivity,
                                            SourceCallBack.CLICK_CLEAR_CACHE,
                                            viewModel.bookSource,
                                            it,
                                            null
                                        ) {
                                            viewModel.clearCache(it)
                                        }
                                    }
                                },
                            )
                            if (currentBook?.isLocal == true) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.upload_to_remote)) },
                                    onClick = {
                                        showMenu = false
                                        viewModel.getBook()?.let { confirmAndUploadBook(it) }
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.delete_alert)) },
                                trailingIcon = {
                                    Checkbox(
                                        checked = deleteAlertChecked,
                                        onCheckedChange = null,
                                    )
                                },
                                onClick = {
                                    showMenu = false
                                    val next = !deleteAlertChecked
                                    deleteAlertChecked = next
                                    LocalConfig.bookInfoDeleteAlert = next
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.log)) },
                                onClick = {
                                    showMenu = false
                                    showDialogFragment<AppLogDialog>()
                                },
                            )
                        }
                    },
                )
            },
            bottomBar = {
                Surface(
                    tonalElevation = 3.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.navigationBars),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedButton(
                            onClick = {
                                viewModel.getBook()?.let { b ->
                                    if (viewModel.inBookshelf) {
                                        deleteBook()
                                    } else {
                                        if (b.isWebFile) {
                                            showWebFileDownloadAlert()
                                        } else {
                                            viewModel.addToBookshelf {
                                                upTvBookshelf()
                                            }
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                text = if (inBookshelfState) {
                                    stringResource(R.string.remove_from_bookshelf)
                                } else {
                                    stringResource(R.string.add_to_bookshelf)
                                }
                            )
                        }
                        Button(
                            onClick = {
                                viewModel.getBook()?.let { b ->
                                    if (b.isWebFile) {
                                        showWebFileDownloadAlert {
                                            readBook(it)
                                        }
                                    } else {
                                        readBook(b)
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(text = stringResource(R.string.reading))
                        }
                    }
                }
            },
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                if (currentBook != null) {
                    if (isLandscape) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(20.dp),
                        ) {
                            Column(
                                modifier = Modifier
                                    .weight(0.45f)
                                    .fillMaxHeight()
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                BookHeaderSection(currentBook)
                                BookMetadataActionsSection(currentBook)
                            }
                            Column(
                                modifier = Modifier
                                    .weight(0.55f)
                                    .fillMaxHeight()
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                BookKindsSection(currentBook)
                                BookIntroSection()
                            }
                        }
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            BookHeaderSection(currentBook)
                            BookKindsSection(currentBook)
                            BookMetadataActionsSection(currentBook)
                            BookIntroSection()
                        }
                    }
                }

                if (isLoadingState) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                            .align(Alignment.TopCenter),
                    )
                }
            }
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun BookHeaderSection(book: Book) {
        val coverVer = coverRefreshVersion
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val coverPath = remember(book.customCoverUrl, book.coverUrl, coverVer) {
                book.getDisplayCover()
            }
            LegadoBookCover(
                path = coverPath,
                name = book.name,
                author = book.author,
                loadOnlyWifi = false,
                sourceOrigin = book.getCoverSourceOrigin(),
                modifier = Modifier
                    .width(96.dp)
                    .height(134.dp)
                    .combinedClickable(
                        onClick = {
                            viewModel.getBook()?.let {
                                showDialogFragment(ChangeCoverDialog(it.name, it.author))
                            }
                        },
                        onLongClick = {
                            viewModel.getBook()?.getDisplayCover()?.let { path ->
                                showDialogFragment(PhotoDialog(path, isBook = true))
                            }
                        },
                    ),
            )
            Spacer(modifier = Modifier.width(14.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = book.name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.combinedClickable(
                        onClick = {
                            viewModel.getBook(false)?.let { b ->
                                SourceCallBack.callBackBtn(
                                    this@BookInfoActivity,
                                    SourceCallBack.CLICK_BOOK_NAME,
                                    viewModel.bookSource,
                                    b,
                                    null,
                                    result = b.name
                                ) {
                                    SearchActivity.start(this@BookInfoActivity, b.name)
                                }
                            }
                        },
                        onLongClick = {
                            viewModel.getBook(false)?.let { b ->
                                SourceCallBack.callBackBtn(
                                    this@BookInfoActivity,
                                    SourceCallBack.LONG_CLICK_BOOK_NAME,
                                    viewModel.bookSource,
                                    b,
                                    null,
                                    result = b.name
                                ) {
                                    SearchActivity.start(this@BookInfoActivity, b.name)
                                }
                            }
                        },
                    ),
                )
                Text(
                    text = stringResource(R.string.author_show, book.getRealAuthor()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.combinedClickable(
                        onClick = {
                            viewModel.getBook(false)?.let { b ->
                                SourceCallBack.callBackBtn(
                                    this@BookInfoActivity,
                                    SourceCallBack.CLICK_AUTHOR,
                                    viewModel.bookSource,
                                    b,
                                    null,
                                    result = b.author
                                ) {
                                    SearchActivity.start(this@BookInfoActivity, b.author)
                                }
                            }
                        },
                        onLongClick = {
                            viewModel.getBook(false)?.let { b ->
                                SourceCallBack.callBackBtn(
                                    this@BookInfoActivity,
                                    SourceCallBack.LONG_CLICK_AUTHOR,
                                    viewModel.bookSource,
                                    b,
                                    null,
                                    result = b.author
                                ) {
                                    SearchActivity.start(this@BookInfoActivity, b.author)
                                }
                            }
                        },
                    ),
                )
                Text(
                    text = lastedTextState,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }

    @OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
    @Composable
    private fun BookKindsSection(book: Book) {
        if (kindsState.isEmpty()) return
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            kindsState.forEach { kind ->
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.combinedClickable(
                        onClick = {
                            val source = viewModel.bookSource ?: return@combinedClickable
                            SourceCallBack.callBackBtn(
                                this@BookInfoActivity,
                                SourceCallBack.CLICK_BOOK_LABEL,
                                source,
                                book,
                                null,
                                result = kind
                            ) {
                                SearchActivity.start(this@BookInfoActivity, source, kind)
                            }
                        },
                        onLongClick = {
                            val source = viewModel.bookSource ?: return@combinedClickable
                            SourceCallBack.callBackBtn(
                                this@BookInfoActivity,
                                SourceCallBack.LONG_CLICK_BOOK_LABEL,
                                source,
                                book,
                                null,
                                result = kind
                            )
                        },
                    ),
                ) {
                    Text(
                        text = kind,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }

    @Composable
    private fun BookMetadataActionsSection(book: Book) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            HorizontalDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.origin_show, book.originName),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .clickable {
                            viewModel.getBook()?.let { b ->
                                if (b.isLocal) return@clickable
                                if (!appDb.bookSourceDao.has(b.origin)) {
                                    toastOnUi(R.string.error_no_source)
                                    return@clickable
                                }
                                editSourceResult.launch {
                                    putExtra("sourceUrl", b.origin)
                                }
                            }
                        }
                        .padding(vertical = 6.dp),
                )
                TextButton(
                    onClick = {
                        viewModel.getBook()?.let { b ->
                            showDialogFragment(ChangeBookSourceDialog(b.name, b.author))
                        }
                    }
                ) {
                    Text(stringResource(R.string.change_origin))
                }
            }
            if (!book.isWebFile) {
                HorizontalDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = ::onTocViewClick)
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = tocTextState,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .padding(vertical = 6.dp),
                    )
                    TextButton(onClick = ::onTocViewClick) {
                        Text(stringResource(R.string.view_toc))
                    }
                }
            }
            HorizontalDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = groupTextState,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 6.dp),
                )
                TextButton(
                    onClick = {
                        viewModel.getBook()?.let {
                            showDialogFragment(GroupSelectDialog(it.group))
                        }
                    }
                ) {
                    Text(stringResource(R.string.change_group))
                }
            }
            HorizontalDivider()
        }
    }

    @Composable
    private fun BookIntroSection() {
        if (!introVisibleState) return
        Column(modifier = Modifier.fillMaxWidth()) {
            AndroidView(
                modifier = Modifier.fillMaxWidth(),
                factory = {
                    (introContainer.parent as? ViewGroup)?.removeView(introContainer)
                    introContainer
                },
            )
            if (introToggleVisibleState) {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    TextButton(
                        onClick = {
                            if (!introCanCollapse || !isIntroTextViewAttached) return@TextButton
                            introExpanded = !introExpanded
                            applyIntroCollapseState()
                            scheduleIntroOverflowCheck(introRenderGeneration)
                        },
                        modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                    ) {
                        Text(
                            text = stringResource(
                                if (introToggleExpandedState) {
                                    R.string.book_intro_collapse
                                } else {
                                    R.string.book_intro_expand
                                }
                            )
                        )
                    }
                }
            }
        }
    }

}

internal fun resolveBookInfoTocTitle(
    storedTitle: String?,
    currentIndex: Int,
    chapters: List<BookChapter>,
): String? {
    return storedTitle?.takeIf { it.isNotBlank() }
        ?: (chapters.getOrNull(currentIndex) ?: chapters.lastOrNull())
            ?.getDisplayTitle(chineseConvert = false)
            ?.takeIf { it.isNotBlank() }
}

internal fun resolveBookInfoReadProgress(book: Book): Int? {
    if (book.totalChapterNum <= 1) return null
    return book.readProgress()?.let { (it * 100).roundToInt() }
}
