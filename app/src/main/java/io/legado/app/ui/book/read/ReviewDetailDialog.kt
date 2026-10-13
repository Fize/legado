package io.legado.app.ui.book.read

import android.content.Context
import android.graphics.Color as AndroidColor
import android.graphics.drawable.Drawable
import android.graphics.drawable.ShapeDrawable
import android.os.Bundle
import android.util.Size
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.Target
import com.google.android.flexbox.FlexWrap
import com.google.android.flexbox.FlexboxLayout
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.BaseSource
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.exoplayer.ExoPlayerHelper
import io.legado.app.help.glide.ImageLoader
import io.legado.app.help.glide.OkHttpModelLoader
import io.legado.app.model.ReadBook
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.analyzeRule.AnalyzeUrl.Companion.getMediaItem
import io.legado.app.model.analyzeRule.ReviewRuleParser
import io.legado.app.model.analyzeRule.ReviewRuleParser.DetailItem as ReviewDetailItem
import io.legado.app.model.jsSource.JsSourceReview
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.dialog.PhotoDialog
import io.legado.app.utils.dpToPx
import io.legado.app.utils.getCompatColor
import io.legado.app.utils.gone
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.isDataUrl
import io.legado.app.utils.setLayout
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.visible
import io.legado.app.utils.windowSize
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import splitties.systemservices.windowManager
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

class ReviewDetailDialog() : BaseDialogFragment() {

    constructor(
        paragraphNum: Int,
        totalCount: Int,
        chapterIndex: Int,
        paragraphData: String?,
        bookUrl: String,
        sourceKey: String,
        ruleHash: Int,
    ) : this() {
        arguments = Bundle().apply {
            putInt(ARG_PARAGRAPH_NUM, paragraphNum)
            putInt(ARG_TOTAL_COUNT, totalCount)
            putInt(ARG_CHAPTER_INDEX, chapterIndex)
            putString(ARG_PARAGRAPH_DATA, paragraphData)
            putString(ARG_BOOK_URL, bookUrl)
            putString(ARG_SOURCE_KEY, sourceKey)
            putInt(ARG_RULE_HASH, ruleHash)
        }
    }

    private var paragraphNum: Int = 0
    private var totalCount: Int = 0
    private var chapterIndex: Int = 0
    private var paragraphData: String = ""
    private var bookUrl: String = ""
    private var sourceKey: String = ""
    private var ruleHash: Int = 0
    private var isLoading = false
    private var hasMore = true
    private var currentPage = 1
    private var nextPageUrl: String? = null
    private val mainItemIndexByKey = LinkedHashMap<String, Int>()
    private val detailItems = ArrayList<ReviewDetailItem>()
    private val expandedReplyParentKeys = HashSet<String>()
    private val replyLoadingParentKeys = HashSet<String>()
    private val replyExhaustedParentKeys = HashSet<String>()
    private val replyPageByParentKey = HashMap<String, Int>()
    private var hasReplyUrl = false
    private val imageSizes = HashMap<String, Size>()
    private var uiItemsState by mutableStateOf<List<ReviewUiItem>>(emptyList())
    private var isInitialLoadingState by mutableStateOf(false)
    private var emptyOrErrorMessageState by mutableStateOf<String?>(null)
    private var audioStateVersion by mutableIntStateOf(0)
    private val sourceImageOptions by lazy {
        RequestOptions().set(OkHttpModelLoader.sourceOriginOption, sourceKey)
    }
    private var reviewSource: BaseSource? = null
    private var reviewAudioPlayer: ExoPlayer? = null
    private var currentAudioUrl: String? = null
    private var isAudioPreparing = false
    private var dragStartRawY = 0f
    private var dragStartHeightPx = 0
    private var isDraggingHeight = false
    private var hasDraggedHeight = false
    private val dragTouchSlop by lazy {
        ViewConfiguration.get(requireContext()).scaledTouchSlop
    }
    private val audioPlayerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            val previousAudioUrl = currentAudioUrl
            when (playbackState) {
                Player.STATE_BUFFERING -> isAudioPreparing = true
                Player.STATE_READY -> isAudioPreparing = false
                Player.STATE_ENDED -> {
                    isAudioPreparing = false
                    currentAudioUrl = null
                }
            }
            refreshAudioPlaybackUi(previousAudioUrl, currentAudioUrl)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            refreshAudioPlaybackUi(currentAudioUrl, currentAudioUrl)
        }

        override fun onPlayerError(error: PlaybackException) {
            val previousAudioUrl = currentAudioUrl
            isAudioPreparing = false
            currentAudioUrl = null
            AppLog.put("段评语音播放失败\n${error.localizedMessage}", error)
            context?.toastOnUi(error.localizedMessage ?: getString(R.string.load_over_time))
            refreshAudioPlaybackUi(previousAudioUrl, currentAudioUrl)
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.run {
            setBackgroundDrawableResource(R.color.transparent)
            decorView.setPadding(0, 0, 0, 0)
            val attr = attributes
            attr.dimAmount = 0.16f
            attr.gravity = Gravity.BOTTOM
            attributes = attr
        }
        applyDialogHeightRatio(lastHeightRatio)
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
                    ReviewDetailScreen()
                }
            }
        }
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        paragraphNum = arguments?.getInt(ARG_PARAGRAPH_NUM) ?: 0
        totalCount = arguments?.getInt(ARG_TOTAL_COUNT) ?: 0
        chapterIndex = arguments?.getInt(ARG_CHAPTER_INDEX) ?: 0
        paragraphData = arguments?.getString(ARG_PARAGRAPH_DATA).orEmpty()
        bookUrl = arguments?.getString(ARG_BOOK_URL).orEmpty()
        sourceKey = arguments?.getString(ARG_SOURCE_KEY).orEmpty()
        ruleHash = arguments?.getInt(ARG_RULE_HASH) ?: 0
        loadDetailPage(paragraphNum, 1, append = false)
    }

    private fun setupHeightDrag(dragHandle: View) {
        dragHandle.setOnClickListener {
            val windowHeight = requireContext().windowManager.windowSize.heightPixels
            if (windowHeight <= 0) return@setOnClickListener
            val currentHeight = dialog?.window?.attributes?.height?.takeIf { it > 0 }
                ?: (windowHeight * lastHeightRatio).roundToInt()
            val midpoint = windowHeight * (DEFAULT_HEIGHT_RATIO + MAX_HEIGHT_RATIO) / 2f
            applyDialogHeightRatio(
                if (currentHeight < midpoint) MAX_HEIGHT_RATIO else DEFAULT_HEIGHT_RATIO
            )
        }
        dragHandle.setOnTouchListener { handle, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val windowHeight = requireContext().windowManager.windowSize.heightPixels
                    if (windowHeight <= 0) return@setOnTouchListener false
                    isDraggingHeight = true
                    hasDraggedHeight = false
                    dragStartRawY = event.rawY
                    dragStartHeightPx = dialog?.window?.attributes?.height?.takeIf { it > 0 }
                        ?: (windowHeight * lastHeightRatio).roundToInt()
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (!isDraggingHeight) return@setOnTouchListener false
                    val deltaY = event.rawY - dragStartRawY
                    if (!hasDraggedHeight && abs(deltaY) < dragTouchSlop) {
                        return@setOnTouchListener true
                    }
                    hasDraggedHeight = true
                    applyDialogHeightPx((dragStartHeightPx - deltaY).roundToInt())
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (!isDraggingHeight) return@setOnTouchListener false
                    isDraggingHeight = false
                    if (!hasDraggedHeight) handle.performClick()
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    isDraggingHeight = false
                    true
                }

                else -> false
            }
        }
    }

    private fun applyDialogHeightRatio(heightRatio: Float) {
        val windowHeight = requireContext().windowManager.windowSize.heightPixels
        if (windowHeight <= 0) return
        applyDialogHeightPx((windowHeight * heightRatio).roundToInt())
    }

    private fun applyDialogHeightPx(heightPx: Int) {
        val windowHeight = requireContext().windowManager.windowSize.heightPixels
        if (windowHeight <= 0) return
        val minHeight = (windowHeight * MIN_HEIGHT_RATIO).roundToInt()
        val maxHeight = (windowHeight * MAX_HEIGHT_RATIO).roundToInt()
        val clampedHeight = heightPx.coerceIn(minHeight, maxHeight)
        setLayout(1f, clampedHeight)
        lastHeightRatio = clampedHeight.toFloat() / windowHeight
    }

    override fun onDestroyView() {
        releaseAudioPlayer()
        super.onDestroyView()
    }

    private fun ensureAudioPlayer(): ExoPlayer {
        reviewAudioPlayer?.let { return it }
        return ExoPlayerHelper.createHttpExoPlayer(requireContext()).also { player ->
            player.addListener(audioPlayerListener)
            reviewAudioPlayer = player
        }
    }

    private fun toggleAudioPlayback(audioUrl: String) {
        val source = reviewSource ?: return
        val previousAudioUrl = currentAudioUrl
        val player = ensureAudioPlayer()
        if (currentAudioUrl == audioUrl) {
            if (isAudioPreparing) return
            if (player.isPlaying) {
                player.pause()
            } else {
                player.play()
            }
            refreshAudioPlaybackUi(previousAudioUrl, currentAudioUrl)
            return
        }
        currentAudioUrl = audioUrl
        isAudioPreparing = true
        runCatching {
            val mediaItem = AnalyzeUrl(
                audioUrl,
                source = source
            ).getMediaItem()
            player.setMediaItem(mediaItem, true)
            player.prepare()
            player.playWhenReady = true
        }.onFailure {
            isAudioPreparing = false
            currentAudioUrl = null
            AppLog.put("段评语音播放失败\n${it.localizedMessage}", it)
            context?.toastOnUi(it.localizedMessage ?: getString(R.string.load_over_time))
        }
        refreshAudioPlaybackUi(previousAudioUrl, currentAudioUrl)
    }

    private fun releaseAudioPlayer() {
        val previousAudioUrl = currentAudioUrl
        isAudioPreparing = false
        currentAudioUrl = null
        reviewAudioPlayer?.runCatching {
            removeListener(audioPlayerListener)
            stop()
            clearMediaItems()
            release()
        }
        reviewAudioPlayer = null
        refreshAudioPlaybackUi(previousAudioUrl, currentAudioUrl)
    }

    private fun refreshAudioPlaybackUi(oldAudioUrl: String?, newAudioUrl: String?) {
        if (view == null) return
        if (oldAudioUrl.isNullOrBlank() && newAudioUrl.isNullOrBlank()) return
        audioStateVersion++
    }

    private fun loadDetailPage(paragraphNum: Int, page: Int, append: Boolean) {
        if (isLoading) return
        if (!append) {
            isInitialLoadingState = true
            emptyOrErrorMessageState = null
            uiItemsState = emptyList()
            currentPage = 1
            hasMore = true
            nextPageUrl = null
            mainItemIndexByKey.clear()
            detailItems.clear()
            expandedReplyParentKeys.clear()
            replyLoadingParentKeys.clear()
            replyExhaustedParentKeys.clear()
            replyPageByParentKey.clear()
            hasReplyUrl = false
        }
        if (!hasMore) return
        isLoading = true
        Coroutine.async(lifecycleScope, IO, start = CoroutineStart.LAZY) {
            val source = ReadBook.bookSource ?: return@async null
            if (source.getKey() != sourceKey) return@async null
            val book = ReadBook.book ?: return@async null
            if (book.bookUrl != bookUrl) return@async null
            val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, chapterIndex) ?: return@async null
            if (source.isJsSource()) {
                if (source.mainJs.hashCode() != ruleHash) return@async null
                val result = JsSourceReview.getReviewDetailAwait(
                    source = source,
                    book = book,
                    chapter = chapter,
                    paragraphIndex = paragraphNum,
                    paragraphData = paragraphData,
                    page = page,
                ) ?: return@async null
                return@async ReviewResult(
                    items = result.items,
                    nextPageUrl = result.nextPageUrl,
                    hasNextPageRule = true,
                    hasReplyUrl = JsSourceReview.hasReviewRepliesCapability(source),
                    source = source,
                )
            }
            val rule = source.ruleReview ?: return@async null
            if (!rule.enabled || rule.hashCode() != ruleHash) return@async null
            val firstPageUrlRule = rule.reviewDetailUrl?.takeIf { it.isNotBlank() } ?: return@async null
            val nextPageUrlRule = rule.reviewDetailNextPageUrl?.takeIf { it.isNotBlank() }
            val effectiveNextUrl = nextPageUrl?.takeIf { it.isNotBlank() }
            if (page > 1 && effectiveNextUrl == null && nextPageUrlRule == null) return@async null
            val detailUrlRule = when {
                page > 1 && !effectiveNextUrl.isNullOrBlank() -> effectiveNextUrl
                page > 1 -> nextPageUrlRule ?: firstPageUrlRule
                else -> firstPageUrlRule
            }
            if (rule.detailListRule.isNullOrBlank() || rule.detailContentRule.isNullOrBlank()) {
                return@async null
            }
            val paraIndex = paragraphNum.toString()
            val paraData = paragraphData
            val analyzeUrl = AnalyzeUrl(
                detailUrlRule,
                page = page,
                extraParams = mapOf(
                    "paraIndex" to paraIndex,
                    "paraData" to paraData,
                    "page" to page.toString()
                ),
                baseUrl = chapter.url,
                source = source,
                ruleData = book,
                chapter = chapter,
                coroutineContext = coroutineContext
            )
            val body = analyzeUrl.getStrResponseAwait(useWebView = false).body ?: ""
            val result = ReviewRuleParser.parseDetailPage(
                body = body,
                rule = rule,
                nextPageRule = nextPageUrlRule,
                baseUrl = analyzeUrl.url,
                source = source,
                book = book,
                chapter = chapter,
                context = coroutineContext,
                paraIndex = paraIndex,
                paraData = paraData,
                page = page.toString()
            )
            ReviewResult(
                items = result.items,
                nextPageUrl = result.nextPageUrl,
                hasNextPageRule = nextPageUrlRule != null,
                hasReplyUrl = !rule.reviewQuoteUrl.isNullOrBlank() &&
                        !rule.replyListRule.isNullOrBlank() &&
                        !rule.replyContentRule.isNullOrBlank(),
                source = source,
            )
        }.onSuccess(Main) { result ->
            if (!append) {
                isInitialLoadingState = false
            }
            result?.source?.let { reviewSource = it }
            result?.let { hasReplyUrl = it.hasReplyUrl }
            val items = result?.items.orEmpty()
            val nextUrlFromRule = result?.nextPageUrl
            if (result?.hasNextPageRule == true) {
                nextPageUrl = nextUrlFromRule
                if (nextUrlFromRule.isNullOrBlank()) {
                    hasMore = false
                }
            }
            if (items.isEmpty() && !append) {
                hasMore = false
                emptyOrErrorMessageState = getString(R.string.content_empty)
                isLoading = false
                return@onSuccess
            }
            if (items.isEmpty()) {
                hasMore = false
                isLoading = false
                return@onSuccess
            }
            val mergedCount = mergeDetailItems(items)
            if (mergedCount == 0 && append) {
                hasMore = false
                isLoading = false
                return@onSuccess
            }
            currentPage = page
            renderUiItems()
            isLoading = false
        }.onError {
            isLoading = false
            if (!append) {
                isInitialLoadingState = false
                emptyOrErrorMessageState = it.localizedMessage ?: getString(R.string.content_empty)
            }
        }.start()
    }

    private fun loadReplies(parentKey: String) {
        if (!hasReplyUrl || !replyLoadingParentKeys.add(parentKey)) return
        val itemIndex = mainItemIndexByKey[parentKey]
        val reviewId = itemIndex?.let { detailItems.getOrNull(it)?.id }
            ?.takeIf { it.isNotBlank() }
        if (reviewId == null) {
            replyLoadingParentKeys.remove(parentKey)
            return
        }
        val page = (replyPageByParentKey[parentKey] ?: 0) + 1
        renderUiItems()
        Coroutine.async(lifecycleScope, IO, start = CoroutineStart.LAZY) {
            val source = ReadBook.bookSource ?: return@async null
            if (source.getKey() != sourceKey) return@async null
            val book = ReadBook.book ?: return@async null
            if (book.bookUrl != bookUrl) return@async null
            val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, chapterIndex)
                ?: return@async null
            if (source.isJsSource()) {
                if (source.mainJs.hashCode() != ruleHash) return@async null
                val replies = JsSourceReview.getReviewRepliesAwait(
                    source = source,
                    book = book,
                    chapter = chapter,
                    paragraphIndex = paragraphNum,
                    paragraphData = paragraphData,
                    reviewId = reviewId,
                    page = page,
                ) ?: return@async null
                return@async ReplyResult(
                    replies = replies,
                    page = page,
                    source = source,
                )
            }
            val rule = source.ruleReview ?: return@async null
            if (!rule.enabled || rule.hashCode() != ruleHash) return@async null
            val replyUrlRule = rule.reviewQuoteUrl?.takeIf { it.isNotBlank() }
                ?: return@async null
            if (rule.replyListRule.isNullOrBlank() || rule.replyContentRule.isNullOrBlank()) {
                return@async null
            }
            val paraIndex = paragraphNum.toString()
            val analyzeUrl = AnalyzeUrl(
                replyUrlRule,
                page = page,
                extraParams = mapOf(
                    "paraIndex" to paraIndex,
                    "paraData" to paragraphData,
                    "reviewId" to reviewId,
                    "page" to page.toString(),
                ),
                baseUrl = chapter.url,
                source = source,
                ruleData = book,
                chapter = chapter,
                coroutineContext = coroutineContext,
            )
            val body = analyzeUrl.getStrResponseAwait(useWebView = false).body
                ?.takeIf { it.isNotBlank() }
                ?: error(getString(R.string.content_empty))
            ReplyResult(
                replies = ReviewRuleParser.parseReplyPage(
                    body = body,
                    rule = rule,
                    baseUrl = analyzeUrl.url,
                    source = source,
                    book = book,
                    chapter = chapter,
                    context = coroutineContext,
                    paraIndex = paraIndex,
                    paraData = paragraphData,
                    page = page.toString(),
                ),
                page = page,
                source = source,
            )
        }.onSuccess(Main) { result ->
            replyLoadingParentKeys.remove(parentKey)
            if (result == null) {
                context?.toastOnUi(R.string.review_rule_missing)
                renderUiItems()
                return@onSuccess
            }
            reviewSource = result.source
            val currentIndex = mainItemIndexByKey[parentKey]
            val current = currentIndex?.let { detailItems.getOrNull(it) }
            if (current == null) {
                renderUiItems()
                return@onSuccess
            }
            replyPageByParentKey[parentKey] = result.page
            expandedReplyParentKeys.add(parentKey)
            if (result.replies.isEmpty()) {
                replyExhaustedParentKeys.add(parentKey)
            } else {
                val replies = mergeReplies(current.replies, result.replies)
                detailItems[currentIndex] = current.copy(replies = replies)
                if (replies.size == current.replies.size ||
                    current.replyCount != null && replies.size >= current.replyCount
                ) {
                    replyExhaustedParentKeys.add(parentKey)
                }
            }
            renderUiItems()
        }.onError {
            replyLoadingParentKeys.remove(parentKey)
            context?.toastOnUi(it.localizedMessage ?: getString(R.string.load_over_time))
            renderUiItems()
        }.start()
    }

    private fun buildDetailItemKey(item: ReviewDetailItem, isReply: Boolean): String {
        val id = item.id?.trim().orEmpty()
        if (id.isNotEmpty()) {
            return (if (isReply) "r|" else "m|") + id
        }
        return buildString {
            append(if (isReply) "r" else "m")
            append('|')
            append(item.name.orEmpty())
            append('|')
            append(item.replyToName.orEmpty())
            append('|')
            append(item.content.orEmpty())
            append('|')
            append(item.time.orEmpty())
            append('|')
            append(item.avatar.orEmpty())
            append('|')
            append(item.imageUrl.orEmpty())
            append('|')
            append(item.audioUrl.orEmpty())
        }
    }

    private fun mergeDetailItems(newItems: List<ReviewDetailItem>): Int {
        var changed = 0
        newItems.forEach { incoming ->
            val mainKey = buildDetailItemKey(incoming, isReply = false)
            val normalizedReplies = dedupeReplies(incoming.replies)
            val normalized = incoming.copy(replies = normalizedReplies)
            val index = mainItemIndexByKey[mainKey]
            if (index == null) {
                detailItems.add(normalized)
                mainItemIndexByKey[mainKey] = detailItems.lastIndex
                changed++
            } else {
                val old = detailItems[index]
                val mergedReplies = mergeReplies(old.replies, normalized.replies)
                val mergedReplyCount = max(old.replyCount ?: 0, normalized.replyCount ?: 0)
                    .takeIf { it > 0 }
                if (mergedReplies.size != old.replies.size || mergedReplyCount != old.replyCount) {
                    detailItems[index] = old.copy(
                        replies = mergedReplies,
                        replyCount = mergedReplyCount
                    )
                    changed++
                }
            }
        }
        return changed
    }

    private fun dedupeReplies(replies: List<ReviewDetailItem>): List<ReviewDetailItem> {
        if (replies.isEmpty()) return replies
        val seen = HashSet<String>(replies.size)
        val result = ArrayList<ReviewDetailItem>(replies.size)
        replies.forEach { reply ->
            val key = buildDetailItemKey(reply, isReply = true)
            if (seen.add(key)) result.add(reply)
        }
        return result
    }

    private fun mergeReplies(
        oldReplies: List<ReviewDetailItem>,
        newReplies: List<ReviewDetailItem>
    ): List<ReviewDetailItem> {
        if (oldReplies.isEmpty()) return newReplies
        if (newReplies.isEmpty()) return oldReplies
        val result = ArrayList<ReviewDetailItem>(oldReplies.size + newReplies.size)
        result.addAll(oldReplies)
        val seen = HashSet<String>(oldReplies.size + newReplies.size)
        oldReplies.forEach { seen.add(buildDetailItemKey(it, isReply = true)) }
        newReplies.forEach { reply ->
            val key = buildDetailItemKey(reply, isReply = true)
            if (seen.add(key)) result.add(reply)
        }
        return result
    }

    private data class ReviewResult(
        val items: List<ReviewDetailItem>,
        val nextPageUrl: String?,
        val hasNextPageRule: Boolean,
        val hasReplyUrl: Boolean,
        val source: BaseSource,
    )

    private data class ReplyResult(
        val replies: List<ReviewDetailItem>,
        val page: Int,
        val source: BaseSource,
    )

    private data class ReviewUiItem(
        val id: String?,
        val avatar: String?,
        val name: String?,
        val replyToName: String?,
        val badges: List<String>,
        val content: String?,
        val imageUrl: String?,
        val audioUrl: String?,
        val time: String?,
        val likeCount: Int?,
        val isReply: Boolean,
        val itemType: Int = TYPE_NORMAL,
        val parentKey: String? = null,
        val moreCount: Int = 0,
        val isLoading: Boolean = false,
    )

    private fun flattenItems(items: List<ReviewDetailItem>): List<ReviewUiItem> {
        val list = ArrayList<ReviewUiItem>()
        items.forEach { item ->
            val parentKey = buildDetailItemKey(item, isReply = false)
            list.add(item.toUiItem(isReply = false, parentKey = parentKey))

            val loadedReplyCount = item.replies.size
            val totalReplyCount = max(item.replyCount ?: 0, loadedReplyCount)
            val isExpanded = loadedReplyCount > 0 ||
                    expandedReplyParentKeys.contains(parentKey)
            val canLoadMore = hasReplyUrl &&
                    !item.id.isNullOrBlank() &&
                    !replyExhaustedParentKeys.contains(parentKey) &&
                    (item.replyCount ?: 0) > loadedReplyCount

            if (isExpanded) {
                item.replies.forEach { reply ->
                    list.add(reply.toUiItem(isReply = true, parentKey = parentKey))
                }
            }

            if ((!isExpanded && loadedReplyCount <= 0 && !canLoadMore) ||
                (isExpanded && !canLoadMore)
            ) return@forEach
            val moreCount = if (isExpanded) {
                totalReplyCount - loadedReplyCount
            } else {
                totalReplyCount
            }
            list.add(
                ReviewUiItem(
                    id = null,
                    avatar = null,
                    name = null,
                    replyToName = null,
                    badges = emptyList(),
                    content = null,
                    imageUrl = null,
                    audioUrl = null,
                    time = null,
                    likeCount = null,
                    isReply = false,
                    itemType = TYPE_MORE,
                    parentKey = parentKey,
                    moreCount = moreCount,
                    isLoading = replyLoadingParentKeys.contains(parentKey),
                )
            )
        }
        return list
    }

    private fun renderUiItems() {
        uiItemsState = flattenItems(detailItems)
    }

    private fun ReviewDetailItem.toUiItem(isReply: Boolean, parentKey: String? = null) = ReviewUiItem(
        id = id,
        avatar = avatar,
        name = name,
        replyToName = replyToName,
        badges = badges,
        content = content,
        imageUrl = imageUrl,
        audioUrl = audioUrl,
        time = time,
        likeCount = likeCount,
        isReply = isReply,
        parentKey = parentKey
    )

    private fun bindReviewImage(context: Context, imageView: ImageView, imageUrl: String?) {
        Glide.with(context).clear(imageView)
        imageView.setImageDrawable(null)
        imageView.updateLayoutParams<ViewGroup.LayoutParams> {
            height = ViewGroup.LayoutParams.WRAP_CONTENT
        }
        if (imageUrl.isNullOrBlank()) {
            imageView.gone()
            return
        }

        val imageRequest = ImageLoader.load(context, imageUrl)
            .apply(sourceImageOptions)
            .addListener(object : RequestListener<Drawable> {
                override fun onLoadFailed(
                    e: GlideException?,
                    model: Any?,
                    target: Target<Drawable>,
                    isFirstResource: Boolean
                ): Boolean = false

                override fun onResourceReady(
                    resource: Drawable,
                    model: Any,
                    target: Target<Drawable>?,
                    dataSource: DataSource,
                    isFirstResource: Boolean
                ): Boolean {
                    val width = resource.intrinsicWidth
                    val height = resource.intrinsicHeight
                    if (width > 0 && height > 0) {
                        imageSizes[imageUrl] = Size(width, height)
                    }
                    return false
                }
            })
        imageSizes[imageUrl]?.let { size ->
            val placeholder = ShapeDrawable().apply {
                intrinsicWidth = size.width
                intrinsicHeight = size.height
                paint.color = AndroidColor.TRANSPARENT
            }
            imageRequest.placeholder(placeholder).error(placeholder)
        }
        imageView.visible()
        imageRequest.into(imageView)
    }

    private fun bindBadges(context: Context, container: FlexboxLayout, badges: List<String>) {
        container.removeAllViews()
        if (badges.isEmpty()) {
            container.gone()
            return
        }
        container.visible()
        badges.forEach { badge ->
            if (badge.isAbsUrl() || badge.isDataUrl()) {
                val badgeHeight = 20.dpToPx()
                val iv = ImageView(context).apply {
                    layoutParams = FlexboxLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        badgeHeight
                    ).apply { setMargins(0, 0, 6.dpToPx(), 0) }
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    adjustViewBounds = true
                }
                ImageLoader.load(context, badge)
                    .apply(sourceImageOptions)
                    .into(iv)
                container.addView(iv)
            } else {
                val tv = TextView(context).apply {
                    layoutParams = FlexboxLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins(0, 0, 6.dpToPx(), 0) }
                    setPadding(6.dpToPx(), 2.dpToPx(), 6.dpToPx(), 2.dpToPx())
                    setTextColor(context.getCompatColor(R.color.secondaryText))
                    textSize = 11f
                    text = badge
                    setBackgroundResource(R.drawable.bg_review_badge_chip)
                }
                container.addView(tv)
            }
        }
    }

    @Composable
    private fun ReviewDetailScreen() {
        val context = requireContext()
        val items = uiItemsState
        val initialLoading = isInitialLoadingState
        val emptyOrErrorMsg = emptyOrErrorMessageState
        val audioTick = audioStateVersion
        val listState = rememberLazyListState()

        val shouldLoadMore by remember {
            derivedStateOf {
                val layoutInfo = listState.layoutInfo
                val totalItems = layoutInfo.totalItemsCount
                val lastVisibleIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                totalItems > 0 && lastVisibleIndex >= totalItems - 3
            }
        }

        LaunchedEffect(shouldLoadMore, items.size) {
            if (shouldLoadMore && hasMore && !isLoading) {
                loadDetailPage(paragraphNum, currentPage + 1, append = true)
            }
        }

        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
            color = colorResource(R.color.background)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(42.dp)
                        .padding(horizontal = 6.dp)
                ) {
                    IconButton(
                        onClick = { dismiss() },
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .size(36.dp)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_baseline_close),
                            contentDescription = stringResource(R.string.close),
                            tint = colorResource(R.color.secondaryText)
                        )
                    }

                    AndroidView(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .width(48.dp)
                            .fillMaxHeight(),
                        factory = { ctx ->
                            val outValue = TypedValue()
                            ctx.theme.resolveAttribute(
                                androidx.appcompat.R.attr.selectableItemBackgroundBorderless,
                                outValue,
                                true
                            )
                            FrameLayout(ctx).apply {
                                id = R.id.drag_handle
                                isClickable = true
                                isFocusable = true
                                contentDescription = ctx.getString(R.string.review_resize_handle)
                                setBackgroundResource(outValue.resourceId)
                                addView(
                                    View(ctx).apply {
                                        alpha = 0.45f
                                        setBackgroundResource(R.drawable.bg_review_drag_handle)
                                    },
                                    FrameLayout.LayoutParams(
                                        32.dpToPx(),
                                        4.dpToPx(),
                                        Gravity.CENTER
                                    )
                                )
                                setupHeightDrag(this)
                            }
                        }
                    )

                    if (totalCount > 0) {
                        Text(
                            text = stringResource(R.string.review_total_count, totalCount),
                            color = colorResource(R.color.secondaryText),
                            fontSize = 14.sp,
                            maxLines = 1,
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .padding(end = 8.dp)
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        itemsIndexed(
                            items = items,
                            key = { index, item ->
                                if (item.itemType == TYPE_MORE) {
                                    "more_${item.parentKey}_$index"
                                } else {
                                    "${item.parentKey}_${item.isReply}_${item.id ?: index}_$index"
                                }
                            }
                        ) { _, item ->
                            ReviewCommentRow(
                                context = context,
                                item = item,
                                audioVersion = audioTick
                            )
                        }
                    }

                    if (initialLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .size(36.dp)
                                .align(Alignment.Center),
                            strokeWidth = 2.dp
                        )
                    }

                    if (!emptyOrErrorMsg.isNullOrBlank() && items.isEmpty() && !initialLoading) {
                        Text(
                            text = emptyOrErrorMsg,
                            color = colorResource(R.color.secondaryText),
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(16.dp)
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun ReviewCommentRow(
        context: Context,
        item: ReviewUiItem,
        audioVersion: Int
    ) {
        val primaryColor = colorResource(R.color.primaryText)
        val secondaryColor = colorResource(R.color.secondaryText)
        val contentColor = colorResource(R.color.reviewContentText)
        val accentColor = colorResource(R.color.accent)

        if (item.itemType == TYPE_MORE) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !item.isLoading) {
                        val parentKey = item.parentKey ?: return@clickable
                        loadReplies(parentKey)
                    }
                    .padding(start = 54.dp, top = 2.dp, end = 8.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (item.isLoading) {
                        stringResource(R.string.loading)
                    } else {
                        stringResource(R.string.review_more_replies, item.moreCount)
                    },
                    fontSize = 14.sp,
                    color = accentColor
                )
            }
            return
        }

        // 子评论缩进：使用主评论头像宽度作为占位，避免写死 magic number
        val mainAvatarSize = 36.dp
        val replyIndent = mainAvatarSize
        val startPadding = if (item.isReply) 8.dp + replyIndent else 8.dp
        val avatarSize = if (item.isReply) 28.dp else 36.dp

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = startPadding, top = 8.dp, end = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.Top
        ) {
            if (!item.avatar.isNullOrBlank()) {
                AndroidView(
                    modifier = Modifier.size(avatarSize),
                    factory = { ctx ->
                        ImageView(ctx).apply {
                            scaleType = ImageView.ScaleType.CENTER_CROP
                        }
                    },
                    update = { ivAvatar ->
                        ImageLoader.load(context, item.avatar)
                            .apply(sourceImageOptions)
                            .circleCrop()
                            .into(ivAvatar)
                    }
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 10.dp, end = 6.dp)
            ) {
                if (!item.name.isNullOrBlank() || item.badges.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (!item.name.isNullOrBlank()) {
                            Text(
                                text = item.name.orEmpty(),
                                color = primaryColor,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(end = 6.dp)
                            )
                        }
                        if (item.badges.isNotEmpty()) {
                            AndroidView(
                                factory = { ctx ->
                                    FlexboxLayout(ctx).apply {
                                        flexWrap = FlexWrap.WRAP
                                    }
                                },
                                update = { llBadges ->
                                    bindBadges(context, llBadges, item.badges)
                                }
                            )
                        }
                    }
                }

                val bodyAnnotated = remember(item.isReply, item.content, item.replyToName, secondaryColor, contentColor) {
                    if (item.isReply) {
                        val content = item.content.orEmpty().trim()
                        val replyToName = item.replyToName.orEmpty().trim()
                        when {
                            content.isEmpty() || replyToName.isEmpty() -> buildAnnotatedString {
                                append(content)
                            }
                            else -> buildAnnotatedString {
                                val prefix = "回复 $replyToName："
                                withStyle(SpanStyle(color = secondaryColor)) {
                                    append(prefix)
                                }
                                append(content)
                            }
                        }
                    } else {
                        buildAnnotatedString {
                            append(item.content.orEmpty())
                        }
                    }
                }
                val hasText = bodyAnnotated.text.isNotBlank()
                if (hasText) {
                    Text(
                        text = bodyAnnotated,
                        color = contentColor,
                        fontSize = if (item.isReply) 14.sp else 16.sp,
                        lineHeight = if (item.isReply) 18.sp else 20.sp,
                        modifier = Modifier.padding(top = if (item.isReply) 0.dp else 4.dp)
                    )
                }

                if (!item.imageUrl.isNullOrBlank()) {
                    val imageUrl = item.imageUrl
                    AndroidView(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp),
                        factory = { ctx ->
                            ImageView(ctx).apply {
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.WRAP_CONTENT
                                )
                                adjustViewBounds = true
                                maxHeight = 240.dpToPx()
                                scaleType = ImageView.ScaleType.FIT_START
                            }
                        },
                        update = { imageView ->
                            bindReviewImage(context, imageView, imageUrl)
                            imageView.setOnClickListener {
                                PhotoDialog(imageUrl, sourceKey)
                                    .show(this@ReviewDetailDialog.childFragmentManager, "reviewPhoto")
                            }
                        }
                    )
                }

                if (!item.audioUrl.isNullOrBlank()) {
                    val audioUrl = item.audioUrl
                    val isCurrentAudio = audioVersion >= 0 && audioUrl == currentAudioUrl
                    val audioTextRes = when {
                        isCurrentAudio && isAudioPreparing -> R.string.loading
                        isCurrentAudio && reviewAudioPlayer?.isPlaying == true -> R.string.review_pause_audio
                        else -> R.string.review_play_audio
                    }
                    val audioIconRes = if (isCurrentAudio && reviewAudioPlayer?.isPlaying == true) {
                        R.drawable.ic_pause_24dp
                    } else {
                        R.drawable.ic_play_24dp
                    }
                    Row(
                        modifier = Modifier
                            .padding(top = 6.dp)
                            .height(28.dp)
                            .background(
                                color = secondaryColor.copy(alpha = 0.12f),
                                shape = RoundedCornerShape(14.dp)
                            )
                            .clickable { toggleAudioPlayback(audioUrl) }
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            painter = painterResource(audioIconRes),
                            contentDescription = null,
                            tint = secondaryColor,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = stringResource(audioTextRes),
                            color = secondaryColor,
                            fontSize = 12.sp
                        )
                    }
                }

                if (!item.time.isNullOrBlank()) {
                    Text(
                        text = item.time.orEmpty(),
                        color = secondaryColor,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 7.dp)
                    )
                }
            }

            val likeCount = item.likeCount
            val showLikeArea = !item.isReply || (likeCount != null && likeCount > 0)
            if (showLikeArea) {
                Column(
                    modifier = Modifier
                        .width(44.dp)
                        .padding(top = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_review_like),
                        contentDescription = null,
                        tint = secondaryColor,
                        modifier = Modifier.size(22.dp)
                    )
                    if (likeCount != null && likeCount > 0) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = likeCount.toString(),
                            color = secondaryColor,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }

    private companion object {
        private const val DEFAULT_HEIGHT_RATIO = 0.68f
        private const val MIN_HEIGHT_RATIO = 0.35f
        private const val MAX_HEIGHT_RATIO = 0.92f
        const val ARG_PARAGRAPH_NUM = "paragraphNum"
        const val ARG_TOTAL_COUNT = "totalCount"
        const val ARG_CHAPTER_INDEX = "chapterIndex"
        const val ARG_PARAGRAPH_DATA = "paragraphData"
        const val ARG_BOOK_URL = "bookUrl"
        const val ARG_SOURCE_KEY = "sourceKey"
        const val ARG_RULE_HASH = "ruleHash"
        const val TYPE_NORMAL = 0
        const val TYPE_MORE = 1
        private var lastHeightRatio = DEFAULT_HEIGHT_RATIO
    }
}
