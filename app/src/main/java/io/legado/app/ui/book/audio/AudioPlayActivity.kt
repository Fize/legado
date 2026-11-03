package io.legado.app.ui.book.audio

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.SeekBar
import androidx.activity.addCallback
import androidx.activity.viewModels
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.core.view.doOnNextLayout
import androidx.lifecycle.lifecycleScope
import com.dirror.lyricviewx.LyricUtil
import com.dirror.lyricviewx.LyricViewX
import com.dirror.lyricviewx.OnPlayClickListener
import io.legado.app.R
import io.legado.app.base.BaseComposeActivity
import io.legado.app.constant.BookType
import io.legado.app.constant.EventBus
import io.legado.app.constant.Status
import io.legado.app.constant.Theme
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.repository.PlatformReaderReturnRepository
import io.legado.app.help.audio.AudioCacheManager
import io.legado.app.help.audio.AudioCachePolicy
import io.legado.app.help.book.isAudio
import io.legado.app.help.book.removeType
import io.legado.app.help.book.simulatedTotalChapterNum
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.ThemeStore.Companion.accentColor
import io.legado.app.lib.theme.view.ThemeSeekBar
import io.legado.app.model.AudioCacheKey
import io.legado.app.model.AudioCacheStateChanged
import io.legado.app.model.AudioPlay
import io.legado.app.model.BookCover
import io.legado.app.model.SourceCallBack
import io.legado.app.service.AudioCacheService
import io.legado.app.service.AudioPlayService
import io.legado.app.ui.about.openAppLogs
import io.legado.app.ui.book.audio.SliderPopup.Companion.SPEED
import io.legado.app.ui.book.audio.config.AudioSkipCredits
import io.legado.app.ui.book.changesource.ChangeBookSourceDialog
import io.legado.app.ui.book.source.edit.BookSourceEditActivity
import io.legado.app.ui.book.toc.TocActivityResult
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.AppActivity
import io.legado.app.ui.navigation.SourceLoginRoute
import io.legado.app.ui.navigation.SourceLoginTarget
import io.legado.app.ui.widget.dialog.SleepTimerDialog
import io.legado.app.ui.widget.image.CircleImageView
import io.legado.app.ui.widget.seekbar.SeekBarChangeListener
import io.legado.app.utils.StartActivityContract
import io.legado.app.utils.dpToPx
import io.legado.app.utils.gone
import io.legado.app.utils.invisible
import io.legado.app.utils.observeEvent
import io.legado.app.utils.observeEventSticky
import io.legado.app.utils.postEvent
import io.legado.app.utils.sendToClip
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.startActivityForBook
import io.legado.app.utils.toDurationTime
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.visible
import kotlinx.coroutines.Dispatchers.Default
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * 音频播放
 */
@SuppressLint("ObsoleteSdkInt")
class AudioPlayActivity :
    BaseComposeActivity(toolBarTheme = Theme.Dark),
    ChangeBookSourceDialog.CallBack,
    AudioPlay.CallBack,
    SleepTimerDialog.CallBack {

    override val guardsRestoreRecovery = true

    private val viewModel by viewModels<AudioPlayViewModel>()
    private val speedControlPopup by lazy { SliderPopup(this, SPEED) }
    private var adjustProgress = false
    private var playMode by mutableStateOf(AudioPlay.PlayMode.LIST_END_STOP)
    private var titleState by mutableStateOf("")
    private var subTitleState by mutableStateOf("")
    private var chapterIndexTextState by mutableStateOf("")
    private var durTimeTextState by mutableStateOf("00:00")
    private var allTimeTextState by mutableStateOf("00:00")
    private var timerTextState by mutableStateOf("")
    private var speedTextState by mutableStateOf("")
    private var playStatusState by mutableIntStateOf(AudioPlay.status)
    private var loadingState by mutableStateOf(false)
    private var skipPreviousEnabledState by mutableStateOf(false)
    private var skipNextEnabledState by mutableStateOf(false)
    private var customBtnVisibleState by mutableStateOf(false)
    private var listenerReadyState by mutableStateOf(false)
    private var hasLyricState by mutableStateOf(false)
    private var showCacheRangeDialogState by mutableStateOf(false)
    private var cacheRangeChapterCount by mutableIntStateOf(0)

    private val ivBg by lazy {
        ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
    }
    private val ivCover by lazy {
        CircleImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            borderColor = accentColor
            borderWidth = 2.dpToPx()
            setImageResource(R.drawable.image_rss_article)
        }
    }
    private val lyricViewX by lazy {
        LyricViewX(this).apply {
            setCurrentColor(ContextCompat.getColor(this@AudioPlayActivity, R.color.md_white_1000))
            val pad = 16.dpToPx()
            setPadding(pad, 0, pad, 0)
            gone()
        }
    }
    private val playerProgress by lazy {
        ThemeSeekBar(this).apply {
            progressBackgroundTintList = ContextCompat.getColorStateList(
                this@AudioPlayActivity,
                R.color.md_dark_secondary
            )
            setOnSeekBarChangeListener(object : SeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    durTimeTextState = progress.toDurationTime()
                }

                override fun onStartTrackingTouch(seekBar: SeekBar) {
                    adjustProgress = true
                }

                override fun onStopTrackingTouch(seekBar: SeekBar) {
                    adjustProgress = false
                    AudioPlay.adjustProgress(seekBar.progress)
                }
            })
        }
    }
    private var speedButtonAnchorView: View? = null

    private var lyricOn = false
    private var oldLyric: String? = null
    private var pendingAudioCacheAction: (() -> Unit)? = null

    private val tocActivityResult = registerForActivityResult(TocActivityResult()) {
        it?.let {
            if (it[0] != AudioPlay.book?.durChapterIndex
                || it[1] == 0
            ) {
                AudioPlay.skipTo(it[0] as Int)
            }
        }
    }
    private val sourceEditResult =
        registerForActivityResult(StartActivityContract(BookSourceEditActivity::class.java)) {
            if (it.resultCode == RESULT_OK) {
                viewModel.upSource()
            }
        }
    private val audioCacheDirSelect = registerForActivityResult(HandleFileContract()) { result ->
        val treeUri = result.uri?.toString()
        if (treeUri == null) {
            pendingAudioCacheAction = null
            return@registerForActivityResult
        }
        lifecycleScope.launch {
            val available = withContext(IO) {
                runCatching { AudioCacheManager.isCacheDirAvailable(treeUri) }
                    .getOrDefault(false)
            }
            if (available) {
                if (AppConfig.audioCacheTreeUri != treeUri) {
                    AudioCacheService.stop(this@AudioPlayActivity)
                }
                AppConfig.audioCacheTreeUri = treeUri
                toastOnUi(R.string.audio_cache_folder_selected)
                pendingAudioCacheAction?.invoke()
            } else {
                toastOnUi(R.string.audio_cache_folder_invalid)
            }
            pendingAudioCacheAction = null
        }
    }

    @Composable
    override fun Content() {
        AudioPlayScreen()
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        onBackPressedDispatcher.addCallback(this) { finish() }
        AudioPlay.register(this)
        viewModel.titleData.observe(this) { name ->
            titleState = name
            val lyric = AudioPlay.durChapter?.getVariable("lyric")?.takeIf { it.isNotBlank() }
            upLyric(lyric ?: AudioPlay.durLyric)
        }
        viewModel.coverData.observe(this) {
            upCover(it)
        }
        viewModel.customBtnListData.observe(this) { customBtnVisibleState = it }
        viewModel.initData(
            intent = intent,
            success = ::initListener,
            error = ::finishAfterInitError,
        )
        initView()
    }

    private fun finishAfterInitError() {
        super.finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (redirectIfRestoreRecoveryRequired()) return
        AudioPlay.saveRead()
        setIntent(intent)
        val requestedBookUrl = intent.getStringExtra("bookUrl")
        if (intent.getStringExtra(PlatformReaderReturnRepository.REQUEST_ID) == null &&
            viewModel.platformRequestId == null &&
            shouldReuseCurrentAudioPlay(requestedBookUrl, AudioPlay.book?.bookUrl)) return
        viewModel.initData(
            intent = intent,
            success = ::initListener,
            error = ::finishAfterInitError,
        )
    }

    private fun selectAudioCacheFolder(onSelected: (() -> Unit)? = null) {
        pendingAudioCacheAction = onSelected
        audioCacheDirSelect.launch {
            title = getString(R.string.audio_cache_select_folder)
            mode = HandleFileContract.DIR_SYS
        }
    }

    private fun ensureAudioCacheFolder(action: () -> Unit) {
        val treeUri = AppConfig.audioCacheTreeUri
        lifecycleScope.launch {
            val available = withContext(IO) {
                runCatching { AudioCacheManager.isCacheDirAvailable(treeUri) }
                    .getOrDefault(false)
            }
            if (available) {
                action()
            } else {
                toastOnUi(
                    if (treeUri.isNullOrBlank()) R.string.audio_cache_folder_not_set
                    else R.string.audio_cache_folder_invalid
                )
                selectAudioCacheFolder(action)
            }
        }
    }

    private fun showAudioCacheRange() {
        val book = AudioPlay.book ?: return
        val chapterCount = AudioPlay.simulatedChapterSize.takeIf { it > 0 }
            ?: book.simulatedTotalChapterNum()
        if (chapterCount <= 0) return
        cacheRangeChapterCount = chapterCount
        showCacheRangeDialogState = true
    }

    private fun clearCurrentAudioCache() {
        val book = AudioPlay.book ?: return
        val chapter = AudioPlay.durChapter ?: return
        ensureAudioCacheFolder {
            val treeUri = AppConfig.audioCacheTreeUri
            lifecycleScope.launch {
                val removed = withContext(IO) {
                    runCatching {
                        AudioCacheManager.removeCachedChapter(
                            treeUri,
                            book.bookUrl,
                            chapter,
                        )
                    }.getOrDefault(false)
                }
                if (removed) {
                    postEvent(
                        EventBus.AUDIO_CACHE_CHANGED,
                        AudioCacheStateChanged(
                            bookUrl = book.bookUrl,
                            key = AudioCacheKey.from(chapter),
                            cached = false,
                            treeUri = treeUri,
                        )
                    )
                    toastOnUi(R.string.audio_cache_current_chapter_cleared)
                } else {
                    toastOnUi(R.string.audio_cache_current_chapter_not_found)
                }
            }
        }
    }

    private fun initView() {
        observeEventSticky<AudioPlay.PlayMode>(EventBus.PLAY_MODE_CHANGED) {
            playMode = it
        }
    }

    private fun initListener() {
        listenerReadyState = true
    }

    private fun upCover(path: String?) {
        val sourceOrigin = AudioPlay.book?.getCoverSourceOrigin()
        BookCover.load(this, path, sourceOrigin = sourceOrigin) {
            BookCover.loadBlur(this, path, sourceOrigin = sourceOrigin)
                .into(ivBg)
        }.into(ivCover)
    }

    override fun upLyric(lyric: String?) {
        if (oldLyric == lyric) return
        oldLyric = lyric
        hasLyricState = false
        lyricViewX.gone()
        if (lyric.isNullOrBlank()) return
        lifecycleScope.launch {
            // Sources may return only LRC metadata when no subtitles are available.
            val lyricEntries = withContext(Default) {
                LyricUtil.parseLrc(arrayOf(lyric, null))
            }
            if (oldLyric != lyric || lyricEntries.isNullOrEmpty()) return@launch
            hasLyricState = true
            if (!lyricOn) {
                lyricOn = true
                lyricViewX.apply {
                    setLabel("")
                    setNormalTextSize(50F)
                    setCurrentTextSize(60F)
                    setTimelineTextColor(accentColor)
                    setDraggable(true, object : OnPlayClickListener {
                        override fun onPlayClick(time: Long): Boolean {
                            AudioPlay.adjustProgress(time.toInt())
                            playButton(false)
                            return true
                        }
                    })
                }
            }
            // Keep lyrics out of the draw pass until ConstraintLayout has assigned their width.
            lyricViewX.invisible()
            fun loadLyricWhenWide(view: View) {
                if (oldLyric != lyric) return
                // LyricViewX subtracts 16dp padding on both sides before building StaticLayout.
                if (view.width <= 32.dpToPx()) {
                    view.doOnNextLayout(::loadLyricWhenWide)
                } else {
                    lyricViewX.loadLyric(lyricEntries)
                    lyricViewX.visible()
                    upLyricP(AudioPlay.durChapterPos)
                }
            }
            lyricViewX.doOnLayout(::loadLyricWhenWide)
        }
    }

    override fun upLyricP(position: Int) {
        lyricViewX.updateTime(position.toLong(), false)
    }

    private fun playButton(noLyr: Boolean = true) {
        val status = AudioPlay.status
        when (status) {
            Status.PLAY if noLyr -> {
                AudioPlay.pause(this)
            }
            Status.PAUSE -> {
                AudioPlay.resume(this)
            }
            else -> {
                AudioPlay.loadOrUpPlayUrl()
            }
        }
    }

    override val oldBook: Book?
        get() = AudioPlay.book

    override fun changeTo(
        source: BookSource,
        book: Book,
        toc: List<BookChapter>,
        onSuccess: () -> Unit,
    ) {
        if (book.isAudio) {
            viewModel.changeTo(source, book, toc, onSuccess)
        } else {
            AudioPlay.stop()
            lifecycleScope.launch {
                withContext(IO) {
                    AudioPlay.book?.migrateTo(book, toc)
                    book.removeType(BookType.updateError)
                    viewModel.replaceForOtherReader(book, toc)
                }
                onSuccess()
                startActivityForBook(book)
                finish()
            }
        }
    }

    override fun finish() {
        if (isRestoreRecoveryBlocked) return super.finish()
        val book = AudioPlay.book ?: return super.finish()
        if (AudioPlay.inBookshelf) {
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
                    AudioPlay.book?.removeType(BookType.notShelf)
                    AudioPlay.book?.save()
                    SourceCallBack.callBackBook(SourceCallBack.ADD_BOOK_SHELF, AudioPlay.bookSource, AudioPlay.book)
                    AudioPlay.inBookshelf = true
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
        SourceCallBack.callBackBook(SourceCallBack.END_READ, AudioPlay.bookSource, AudioPlay.book, AudioPlay.durChapter)
    }

    override fun onDestroy() {
        if (!wasActivityContentCreated) { super.onDestroy(); return }
        if (isFinishing && !isRestoreRecoveryBlocked) {
            AudioPlay.saveRead()
            viewModel.finishPlatformReturn()
        }
        super.onDestroy()
        if (AudioPlay.status != Status.PLAY) {
            AudioPlay.stop()
        }
        AudioPlay.unregister(this)
    }

    @SuppressLint("SetTextI18n")
    override fun observeLiveBus() {
        observeEvent<Boolean>(EventBus.MEDIA_BUTTON) {
            if (it) {
                playButton()
            }
        }
        observeEventSticky<Int>(EventBus.AUDIO_STATE) {
            AudioPlay.status = it
            playStatusState = it
        }
        observeEventSticky<String>(EventBus.AUDIO_SUB_TITLE) {
            subTitleState = it
            val chapterSize = AudioPlay.simulatedChapterSize
            if (chapterSize > 0) {
                chapterIndexTextState = getString(
                    R.string.audio_chapter_progress,
                    AudioPlay.durChapterIndex + 1,
                    chapterSize,
                )
            } else {
                chapterIndexTextState = ""
            }
            skipPreviousEnabledState = AudioPlay.durChapterIndex > 0
            skipNextEnabledState =
                AudioPlay.durChapterIndex < AudioPlay.simulatedChapterSize - 1
        }
        observeEventSticky<Int>(EventBus.AUDIO_SIZE) {
            playerProgress.max = it
            allTimeTextState = it.toDurationTime()
        }
        observeEventSticky<Int>(EventBus.AUDIO_PROGRESS) {
            if (!adjustProgress) playerProgress.progress = it
            durTimeTextState = it.toDurationTime()
        }
        observeEventSticky<Int>(EventBus.AUDIO_BUFFER_PROGRESS) {
            playerProgress.secondaryProgress = it
        }
        observeEventSticky<Float>(EventBus.AUDIO_SPEED) {
            speedTextState = if (it == 1f) {
                ""
            } else {
                String.format(Locale.ROOT, "%.1fX", it)
            }
        }
        observeEventSticky<Int>(EventBus.AUDIO_DS) { upTimerText() }
        observeEventSticky<Int>(EventBus.AUDIO_CHAPTER_STOP) { upTimerText() }
    }

    private fun upTimerText() {
        val chapter = AudioPlayService.chapterToStop
        val minute = AudioPlayService.timeMinute
        timerTextState = when {
            chapter > 0 -> getString(R.string.audio_stop_chapters, chapter)
            minute > 0 -> getString(R.string.timer_m, minute)
            else -> ""
        }
    }

    override fun onSleepTimerMinute(minute: Int) {
        AudioPlay.setTimer(minute)
    }

    override fun onSleepTimerChapter(count: Int) {
        AudioPlay.setChapterStop(count)
    }

    override fun upLoading(loading: Boolean) {
        runOnUiThread {
            loadingState = loading
        }
    }

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
    @Composable
    private fun AudioPlayScreen() {
        val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
        var overflowExpanded by remember { mutableStateOf(false) }
        var wakeLockChecked by remember { mutableStateOf(AppConfig.audioPlayUseWakeLock) }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colorResource(R.color.background))
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = {
                    (ivBg.parent as? ViewGroup)?.removeView(ivBg)
                    ivBg
                }
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x90000000))
            )

            Column(modifier = Modifier.fillMaxSize()) {
                TopAppBar(
                    title = {
                        Text(
                            text = titleState,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { finish() }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.back),
                                tint = Color.White
                            )
                        }
                    },
                    actions = {
                        if (customBtnVisibleState) {
                            IconButton(
                                onClick = {
                                    AudioPlay.bookSource?.let { source ->
                                        AudioPlay.book?.let { book ->
                                            SourceCallBack.callBackBtn(
                                                this@AudioPlayActivity,
                                                SourceCallBack.CLICK_CUSTOM_BUTTON,
                                                source,
                                                book,
                                                AudioPlay.durChapter,
                                                BookType.audio
                                            )
                                        }
                                    }
                                }
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_custom),
                                    contentDescription = stringResource(R.string.custom_button),
                                    tint = Color.White
                                )
                            }
                        }
                        IconButton(
                            onClick = {
                                AudioPlay.book?.let {
                                    showDialogFragment(ChangeBookSourceDialog(it.name, it.author))
                                }
                            }
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_exchange),
                                contentDescription = stringResource(R.string.change_origin),
                                tint = Color.White
                            )
                        }
                        Box {
                            IconButton(
                                onClick = {
                                    wakeLockChecked = AppConfig.audioPlayUseWakeLock
                                    overflowExpanded = true
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = stringResource(R.string.more_menu),
                                    tint = Color.White
                                )
                            }
                            DropdownMenu(
                                expanded = overflowExpanded,
                                onDismissRequest = { overflowExpanded = false }
                            ) {
                                if (AudioPlay.bookSource?.hasLogin() == true) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.login)) },
                                        onClick = {
                                            overflowExpanded = false
                                            AudioPlay.bookSource?.let {
                                                startActivity(
                                                    AppActivity.startIntent(
                                                        this@AudioPlayActivity,
                                                        SourceLoginRoute(
                                                            SourceLoginTarget.CurrentReading,
                                                            bookType = BookType.audio,
                                                        ),
                                                    ),
                                                )
                                            }
                                        }
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.copy_play_url)) },
                                    onClick = {
                                        overflowExpanded = false
                                        AudioPlay.book?.let {
                                            val url = AudioPlay.durPlayUrl.ifBlank {
                                                AudioPlay.durChapter?.resourceUrl.orEmpty()
                                            }
                                            SourceCallBack.callBackBtn(
                                                this@AudioPlayActivity,
                                                SourceCallBack.CLICK_COPY_PLAY_URL,
                                                AudioPlay.bookSource,
                                                it,
                                                AudioPlay.durChapter,
                                                BookType.audio,
                                                url
                                            ) {
                                                sendToClip(url)
                                            }
                                        }
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.audio_cache_select_folder)) },
                                    onClick = {
                                        overflowExpanded = false
                                        selectAudioCacheFolder()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.audio_cache_range)) },
                                    onClick = {
                                        overflowExpanded = false
                                        showAudioCacheRange()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.audio_cache_clear_current_chapter)) },
                                    onClick = {
                                        overflowExpanded = false
                                        clearCurrentAudioCache()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.edit_book_source)) },
                                    onClick = {
                                        overflowExpanded = false
                                        AudioPlay.bookSource?.let {
                                            sourceEditResult.launch {
                                                putExtra("sourceUrl", it.bookSourceUrl)
                                            }
                                        }
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.audio_play_wake_lock)) },
                                    trailingIcon = {
                                        Checkbox(
                                            checked = wakeLockChecked,
                                            onCheckedChange = null
                                        )
                                    },
                                    onClick = {
                                        overflowExpanded = false
                                        AppConfig.audioPlayUseWakeLock = !AppConfig.audioPlayUseWakeLock
                                        wakeLockChecked = AppConfig.audioPlayUseWakeLock
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.skip_book_credits)) },
                                    onClick = {
                                        overflowExpanded = false
                                        AudioPlay.book?.let {
                                            showDialogFragment(AudioSkipCredits.newInstance(it))
                                        }
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.log)) },
                                    onClick = {
                                        overflowExpanded = false
                                        this@AudioPlayActivity.openAppLogs()
                                    }
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        titleContentColor = Color.White,
                        navigationIconContentColor = Color.White,
                        actionIconContentColor = Color.White
                    )
                )

                // Middle content area (Cover + Lyric + Timer/Speed badges)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    if (!isLandscape) {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                AndroidView(
                                    modifier = Modifier.size(260.dp),
                                    factory = {
                                        (ivCover.parent as? ViewGroup)?.removeView(ivCover)
                                        ivCover
                                    }
                                )
                            }
                            AndroidView(
                                modifier = if (hasLyricState) {
                                    Modifier
                                        .weight(1f)
                                        .fillMaxWidth()
                                } else {
                                    Modifier
                                        .height(0.dp)
                                        .fillMaxWidth()
                                },
                                factory = {
                                    (lyricViewX.parent as? ViewGroup)?.removeView(lyricViewX)
                                    lyricViewX
                                }
                            )
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                                contentAlignment = Alignment.Center
                            ) {
                                AndroidView(
                                    modifier = Modifier.size(260.dp),
                                    factory = {
                                        (ivCover.parent as? ViewGroup)?.removeView(ivCover)
                                        ivCover
                                    }
                                )
                            }
                            AndroidView(
                                modifier = if (hasLyricState) {
                                    Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                } else {
                                    Modifier
                                        .width(0.dp)
                                        .fillMaxHeight()
                                },
                                factory = {
                                    (lyricViewX.parent as? ViewGroup)?.removeView(lyricViewX)
                                    lyricViewX
                                }
                            )
                        }
                    }

                    // Timer badge (top-start) & Speed badge (top-end)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (timerTextState.isNotEmpty()) {
                            Surface(
                                color = Color(0x66000000),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_timer_black_24dp),
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = timerTextState,
                                        color = Color.White,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        } else {
                            Spacer(modifier = Modifier.width(1.dp))
                        }

                        if (speedTextState.isNotEmpty()) {
                            Surface(
                                color = Color(0x66000000),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = speedTextState,
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }

                // Subtitle & Chapter Index area
                if (!isLandscape) {
                    Text(
                        text = subTitleState,
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 24.dp, top = 8.dp, end = 24.dp, bottom = 2.dp)
                    )
                    if (chapterIndexTextState.isNotEmpty()) {
                        Text(
                            text = chapterIndexTextState,
                            color = colorResource(R.color.md_dark_secondary),
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 24.dp, end = 24.dp, bottom = 6.dp)
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 24.dp, top = 8.dp, end = 24.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = subTitleState,
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.weight(1f)
                        )
                        if (chapterIndexTextState.isNotEmpty()) {
                            Text(
                                text = chapterIndexTextState,
                                color = colorResource(R.color.md_dark_secondary),
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                }

                // Progress bar row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = durTimeTextState,
                        color = Color.White
                    )
                    AndroidView(
                        modifier = Modifier
                            .weight(1f)
                            .height(25.dp),
                        factory = {
                            (playerProgress.parent as? ViewGroup)?.removeView(playerProgress)
                            playerProgress
                        }
                    )
                    Text(
                        text = allTimeTextState,
                        color = Color.White
                    )
                }

                // Bottom play control menu
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            showDialogFragment(
                                SleepTimerDialog.newInstance(
                                    AudioPlayService.timeMinute,
                                    AudioPlayService.chapterToStop,
                                    useEpisodes = true,
                                )
                            )
                        },
                        modifier = Modifier.size(46.dp)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_timer_black_24dp),
                            contentDescription = stringResource(R.string.set_timer),
                            tint = Color.White
                        )
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        Box(modifier = Modifier.size(46.dp)) {
                            AndroidView(
                                modifier = Modifier.size(1.dp),
                                factory = { ctx ->
                                    View(ctx).also { speedButtonAnchorView = it }
                                }
                            )
                            IconButton(
                                onClick = {
                                    speedButtonAnchorView?.let { anchor ->
                                        speedControlPopup.showAsDropDown(
                                            anchor,
                                            0,
                                            (-100).dpToPx(),
                                            Gravity.TOP
                                        )
                                    }
                                },
                                modifier = Modifier.fillMaxSize()
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_speed_control),
                                    contentDescription = stringResource(R.string.speed_control),
                                    tint = Color.White
                                )
                            }
                        }
                    } else {
                        Spacer(modifier = Modifier.size(46.dp))
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    IconButton(
                        onClick = { if (listenerReadyState) AudioPlay.prev() },
                        enabled = skipPreviousEnabledState,
                        modifier = Modifier.size(46.dp)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_skip_previous),
                            contentDescription = stringResource(R.string.skip_previous),
                            tint = if (skipPreviousEnabledState) Color.White else Color.White.copy(alpha = 0.4f)
                        )
                    }

                    Box(
                        modifier = Modifier.padding(10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        FloatingActionButton(
                            onClick = { if (listenerReadyState) playButton() },
                            containerColor = Color.White,
                            contentColor = Color.Black,
                            shape = CircleShape,
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .combinedClickable(
                                    onClick = { if (listenerReadyState) playButton() },
                                    onLongClick = { if (listenerReadyState) AudioPlay.stop() }
                                )
                        ) {
                            Icon(
                                painter = painterResource(
                                    if (playStatusState == Status.PLAY) R.drawable.ic_pause_24dp
                                    else R.drawable.ic_play_24dp
                                ),
                                contentDescription = stringResource(R.string.audio_play),
                                tint = Color.Black
                            )
                        }
                        if (loadingState) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(56.dp),
                                color = Color(accentColor),
                                strokeWidth = 3.dp
                            )
                        }
                    }

                    IconButton(
                        onClick = { if (listenerReadyState) AudioPlay.next() },
                        enabled = skipNextEnabledState,
                        modifier = Modifier.size(46.dp)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_skip_next),
                            contentDescription = stringResource(R.string.skip_next),
                            tint = if (skipNextEnabledState) Color.White else Color.White.copy(alpha = 0.4f)
                        )
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    IconButton(
                        onClick = { if (listenerReadyState) AudioPlay.changePlayMode() },
                        modifier = Modifier.size(46.dp)
                    ) {
                        Icon(
                            painter = painterResource(playMode.iconRes),
                            contentDescription = stringResource(R.string.play_mode),
                            tint = Color.White
                        )
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    IconButton(
                        onClick = {
                            if (listenerReadyState) {
                                AudioPlay.book?.let {
                                    tocActivityResult.launch(it.bookUrl)
                                }
                            }
                        },
                        modifier = Modifier.size(46.dp)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_chapter_list),
                            contentDescription = stringResource(R.string.chapter_list),
                            tint = Color.White
                        )
                    }
                }
            }
        }

        if (showCacheRangeDialogState) {
            var startInput by remember { mutableStateOf((AudioPlay.durChapterIndex + 1).toString()) }
            var endInput by remember { mutableStateOf(cacheRangeChapterCount.toString()) }

            AlertDialog(
                onDismissRequest = { showCacheRangeDialogState = false },
                title = { Text(stringResource(R.string.audio_cache_range)) },
                text = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = startInput,
                            onValueChange = { startInput = it },
                            label = { Text(stringResource(R.string.start)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = endInput,
                            onValueChange = { endInput = it },
                            label = { Text(stringResource(R.string.end)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val book = AudioPlay.book
                            if (book != null) {
                                val start = startInput.trim().toIntOrNull()
                                val end = endInput.trim().toIntOrNull()
                                val range = AudioCachePolicy.normalizeRange(
                                    start = start?.minus(1) ?: -1,
                                    endInclusive = end?.minus(1) ?: -1,
                                    chapterCount = cacheRangeChapterCount,
                                )
                                if (range == null) {
                                    toastOnUi(R.string.error_scope_input)
                                } else {
                                    showCacheRangeDialogState = false
                                    ensureAudioCacheFolder {
                                        AudioCacheService.start(
                                            this@AudioPlayActivity,
                                            book.bookUrl,
                                            range.first,
                                            range.last,
                                        )
                                        toastOnUi(R.string.audio_cache_start_range)
                                    }
                                }
                            } else {
                                showCacheRangeDialogState = false
                            }
                        }
                    ) {
                        Text(stringResource(android.R.string.ok))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showCacheRangeDialogState = false }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            )
        }
    }
}
