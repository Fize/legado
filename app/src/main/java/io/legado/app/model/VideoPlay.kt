package io.legado.app.model

import android.annotation.SuppressLint
import android.content.Context
import io.legado.app.help.storage.RestoreRecoveryGate
import android.content.Context.MODE_PRIVATE
import android.content.SharedPreferences
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.view.Window
import androidx.core.content.edit
import com.shuyu.gsyvideoplayer.listener.GSYMediaPlayerListener
import com.shuyu.gsyvideoplayer.utils.CommonUtil
import com.shuyu.gsyvideoplayer.video.StandardGSYVideoPlayer
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.constant.SourceType
import io.legado.app.data.appDb
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.exception.ContentEmptyException
import io.legado.app.help.globalExecutor
import com.shuyu.gsyvideoplayer.video.base.GSYVideoView
import io.legado.app.help.CacheManager
import io.legado.app.help.book.getDanmaku
import io.legado.app.help.book.update
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.gsyVideo.ExoVideoManager
import io.legado.app.help.gsyVideo.ExoVideoManager.Companion.FULLSCREEN_ID
import io.legado.app.help.gsyVideo.FloatingPlayer
import io.legado.app.help.gsyVideo.VideoPlayer
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.FileUtils
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.externalCache
import io.legado.app.utils.postEvent
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.io.File
import java.util.concurrent.atomic.AtomicLong

object VideoPlay : CoroutineScope by MainScope(){
    private const val VIDEO_POS_NAME = "video_pos_" //单链接播放进度
    private const val VIDEO_POS_SAVE_TIME = 60 * 60 * 24 * 20 //20天
    private var needClearTemp = true //需要清理缓存
    private const val VIDEO_TEMP_PATH = "video_temp"
    private val videoTempFile by lazy { File(FileUtils.getCachePath(), VIDEO_TEMP_PATH) }

    const val VIDEO_PREF_NAME = "video_config"
    const val PROGRESS_CHECKPOINT_INTERVAL_MS = 5_000L

    private val videoPrefs: SharedPreferences by lazy { appCtx.getSharedPreferences(VIDEO_PREF_NAME, MODE_PRIVATE) }
    /**  是否自动播放  **/
    var autoPlay
        get() = videoPrefs.getBoolean("autoPlay", true)
        set(value) {
            if (RestoreRecoveryGate.isQuarantined) return
            videoPrefs.edit { putBoolean("autoPlay", value) }
        }
    /**  直接全屏，需先启用自动播放  **/
    var startFull
        get() = videoPrefs.getBoolean("startFull", false)
        set(value) {
            if (RestoreRecoveryGate.isQuarantined) return
            videoPrefs.edit { putBoolean("startFull", value) }
        }
    /**  默认使用悬浮窗播放  **/
    var defaultFloatWindow
        get() = videoPrefs.getBoolean("defaultFloatWindow", false)
        set(value) {
            if (RestoreRecoveryGate.isQuarantined) return
            videoPrefs.edit { putBoolean("defaultFloatWindow", value) }
        }
    /**  长按倍速  **/
    var longPressSpeed
        get() = videoPrefs.getInt("longPressSpeed", 30)
        set(value) {
            if (RestoreRecoveryGate.isQuarantined) return
            videoPrefs.edit { putInt("longPressSpeed", value) }
        }
    /**  全屏底部进度条  **/
    var fullBottomProgressBar
        get() = videoPrefs.getBoolean("fullBottomProgressBar", true)
        set(value) {
            if (RestoreRecoveryGate.isQuarantined) return
            videoPrefs.edit { putBoolean("fullBottomProgressBar", value) }
        }
    /**  弹幕滚动速度  **/
    var danmakuSpeed = 1.2f
    /**  锁屏  **/
    var lockCurScreen = false
    /**  竖屏视频  **/
    var isPortraitVideo = false

    val videoManager by lazy { ExoVideoManager() }
    private var isLoading = false
    private val playbackGeneration = AtomicLong()

    private fun isPlaybackCurrent(generation: Long) =
        !RestoreRecoveryGate.isQuarantined && generation == playbackGeneration.get()
    private val loadScope = CoroutineScope(SupervisorJob() + IO)
    var videoUrl: String? = null //播放链接
    var singleUrl = false
    var videoTitle: String? = null
    var source: BaseSource? = null
    var book: Book? = null
    var toc: List<BookChapter>? =  null
    var chapter: BookChapter? = null
    var volumes = arrayListOf<BookChapter>()
    var episodes: List<BookChapter>? =  null
    /**  在当前episodes中的位置  **/
    var chapterInVolumeIndex = 0
    /**  卷章节 -> 线路或者季数  **/
    var durVolumeIndex = 0
    /**  当前卷  **/
    var durVolume: BookChapter? = null
    /**  本集的进度  **/
    var durChapterPos = 0
    var inBookshelf = true
    /**  订阅收藏  **/    /**  订阅历史记录,收藏优先  **/    /**  弹幕相关  **/
    var danmakuFile: File? = null
    var danmakuStr: String? = null
    var danmakuShow = true

    /**
     * 开始播放
     */
    fun startPlay(player: StandardGSYVideoPlayer) {
        if (RestoreRecoveryGate.isQuarantined || source == null) return
        val generation = playbackGeneration.incrementAndGet()
        loadScope.coroutineContext.cancelChildren()
        danmakuStr = null
        danmakuFile = null
        val initialPlayer = player.getCurrentPlayer()
        if (singleUrl) {
            val mUrl = videoUrl ?: return
            Coroutine.async(loadScope, IO) {
                if (!isPlaybackCurrent(generation)) return@async
                val savedPosition = CacheManager.getLong(VIDEO_POS_NAME + mUrl)
                val analyzeUrl = AnalyzeUrl(
                    mUrl,
                    source = source,
                    ruleData = book,
                    chapter = null
                )
                withContext(Main) {
                    if (!isPlaybackCurrent(generation)) return@withContext
                    val currentPlayer = player.getCurrentPlayer()
                    savedPosition?.let { currentPlayer.seekOnStart = it }
                    inBookshelf = true
                    currentPlayer.mapHeadData = analyzeUrl.headerMap
                    val url = analyzeUrl.url
                    currentPlayer.setUp(url, false, File(appCtx.externalCache, "exoplayer"), videoTitle)
                    if (autoPlay) {
                        currentPlayer.startPlayLogic()
                    }
                }
            }.onError {
                if (!isPlaybackCurrent(generation)) return@onError
                AppLog.put("加载视频链接失败", it, true)
            }
            return
        }
        durChapterPos.takeIf { it > 0 }?.toLong()?.let { initialPlayer.seekOnStart = it }
        val book = book
        if (book == null) {
            appCtx.toastOnUi("未找到书籍")
            return
        }
        chapter = if (episodes.isNullOrEmpty()) {
            //没有卷目录，那么卷就是播放的章节（适合电影类，没有剧集，全是线路卷章节，如果全是章节没有卷的写法，播放完后会继续下一个线路重复播放）
            val durVolume = durVolume
            when {
                durVolume == null -> null
                durVolume.url.startsWith(durVolume.title) -> null //卷章节没获取到链接（链接以标题开头）则返回null
                else -> durVolume
            }
        } else {
            // 优先获取当前索引的剧集，如果不存在则尝试获取第一个剧集
            episodes?.getOrNull(chapterInVolumeIndex) ?: run {
                chapterInVolumeIndex = 0
                episodes?.getOrNull(chapterInVolumeIndex)
            }
        }
        val chapter = chapter
        if (chapter == null) {
            appCtx.toastOnUi("未找到章节")
            return
        }
        videoTitle = chapter.title
        WebBook.getContent(loadScope, source as BookSource, book, chapter)
            .onSuccess(IO) { content ->
                if (!isPlaybackCurrent(generation)) return@onSuccess
                val content = content.trim()
                val mUrl = if (content.isEmpty()) {
                    throw ContentEmptyException("正文为空")
                } else if (content.startsWith("<")) { //当作mpd文本
                    val name = MD5Utils.md5Encode(content) + ".mpd"
                    val file = FileUtils.createFileIfNotExist(videoTempFile,name)
                    file.writeText(content)
                    Uri.fromFile(file).toString()
                } else {
                    content
                }
                if (!isPlaybackCurrent(generation)) return@onSuccess
                val analyzeUrl = AnalyzeUrl(
                    mUrl,
                    source = source,
                    ruleData = book,
                    chapter = chapter
                )
                val danmaku = chapter.getDanmaku()
                val playUrl = analyzeUrl.url
                withContext(Main) {
                    if (!isPlaybackCurrent(generation)) return@withContext
                    val currentPlayer = player.getCurrentPlayer()
                    currentPlayer.mapHeadData = analyzeUrl.headerMap
                    videoUrl = mUrl
                    when (danmaku) {
                        is String -> danmakuStr = danmaku
                        is File -> danmakuFile = danmaku
                    }
                    currentPlayer.setUp(playUrl, false, File(appCtx.externalCache, "exoplayer"), chapter.title)
                    if (autoPlay) {
                        currentPlayer.startPlayLogic()
                    }
                }
            }.onError {
                if (!isPlaybackCurrent(generation)) return@onError
                AppLog.put("获取资源链接出错\n$it", it, true)
            }
        isLoading = false
    }

    /**
     * 退出全屏，主要用于返回键
     *
     * @return 返回是否全屏
     */
    fun backFromWindowFull(context: Context?): Boolean {
        var backFrom = false
        val vp =
            (CommonUtil.scanForActivity(context)).findViewById<View?>(Window.ID_ANDROID_CONTENT) as ViewGroup
        val oldF = vp.findViewById<View?>(FULLSCREEN_ID)
        if (oldF != null) {
            backFrom = true
            CommonUtil.hideNavKey(context)
            if (videoManager.lastListener() != null) {
                videoManager.lastListener().onBackFullscreen()
            }
        }
        return backFrom
    }
    /**
     * 页面销毁了记得调用是否所有的video
     */
    fun releaseAllVideos() {
        if (videoManager.listener() != null) {
            videoManager.listener().onCompletion()
        }
        videoManager.releaseMediaPlayer()
        if (!isLoading) {
            //还原所有状态
            videoUrl = null
            singleUrl = false
            videoTitle = null
            source = null
            book = null
            toc = null
            chapter = null
            volumes.clear()
            episodes = null
            chapterInVolumeIndex = 0
            durVolumeIndex = 0
            durVolume = null
            durChapterPos = 0
            inBookshelf = true
            danmakuStr = null
            danmakuFile = null
            lockCurScreen = false
            isPortraitVideo = false
            release()
            if (needClearTemp) {
                needClearTemp = false
                FileUtils.delete(videoTempFile)
            }
        }
    }
    /**
     * 暂停播放
     */
    fun onPause() {
        if (videoManager.listener() != null) {
            videoManager.listener().onVideoPause()
        }
    }

    /**
     * 恢复播放
     */
    fun onResume() {
        if (RestoreRecoveryGate.isQuarantined) return
        if (videoManager.listener() != null) {
            videoManager.listener().onVideoResume()
        }
    }


    /**
     * 恢复暂停状态
     * @param seek 是否产生seek动作,直播设置为false
     */
    fun onResume(seek: Boolean) {
        if (RestoreRecoveryGate.isQuarantined) return
        if (videoManager.listener() != null) {
            videoManager.listener().onVideoResume(seek)
        }
    }

    //播放器移植 - 辅助函数
    @SuppressLint("StaticFieldLeak")
    private var sSwitchVideo: StandardGSYVideoPlayer? = null
    private var sMediaPlayerListener: GSYMediaPlayerListener? = null
    fun savePlayState(switchVideo: StandardGSYVideoPlayer) {
        if (RestoreRecoveryGate.isQuarantined) return
        when (switchVideo) {
            is VideoPlayer -> sSwitchVideo = switchVideo.saveState()
            is FloatingPlayer -> sSwitchVideo = switchVideo.saveState()
        }
        sMediaPlayerListener = switchVideo
    }
    fun clonePlayState(switchVideo: StandardGSYVideoPlayer) {
        if (RestoreRecoveryGate.isQuarantined) return
        when (switchVideo) {
            is VideoPlayer -> sSwitchVideo?.let { switchVideo.cloneState(it) }
            is FloatingPlayer -> sSwitchVideo?.let { switchVideo.cloneState(it) }
        }
    }

    fun release() {
        sMediaPlayerListener?.onAutoCompletion()
        sMediaPlayerListener = null
        sSwitchVideo = null
    }

    fun stopLoading() {
        playbackGeneration.incrementAndGet()
        loadScope.coroutineContext.cancelChildren()
        isLoading = false
    }

    fun initSource(sourceKey: String?, sourceType: Int?, bookUrl: String?, record:String?): Boolean {
        if (RestoreRecoveryGate.isQuarantined) return false
        isLoading = true
        source = sourceKey?.let {
            when (sourceType) {
                SourceType.book -> appDb.bookSourceDao.getBookSource(it)                else -> null
            }
        }
        book = bookUrl?.let {
            toc = appDb.bookChapterDao.getChapterList(it)
            volumes.clear()
            toc?.forEach { t ->
                if (t.isVolume) {
                    volumes.add(t)
                }
            }
            appDb.bookDao.getBook(it) ?: appDb.searchBookDao.getSearchBook(it)?.toBook()
        }?.also { b ->
            chapterInVolumeIndex = b.chapterInVolumeIndex
            durVolumeIndex = b.durVolumeIndex
            durChapterPos = b.durChapterPos
            source = appDb.bookSourceDao.getBookSource(b.origin)
            SourceCallBack.callBackBook(SourceCallBack.START_READ, source as BookSource?, b, chapter)
        }
        upEpisodes()
        if (source == null) {
            isLoading = false
            appCtx.toastOnUi("未找到源")
            return false
        }
        return true
    }

    fun upEpisodes() {
        if (RestoreRecoveryGate.isQuarantined) return
        val volumes = volumes
        if (volumes.isEmpty()) {
            durVolume = null
            episodes = toc
            return
        }
        val toc = toc ?: return
        durVolume = volumes.getOrNull(durVolumeIndex)
        if (durVolume == null) {
            durVolumeIndex = 0
            durVolume = volumes.getOrNull(durVolumeIndex)
        }
        val startInt = durVolume?.index ?: 0
        val endInt = volumes.getOrNull(durVolumeIndex + 1)?.index ?: toc.size
        episodes = toc.subList(startInt + 1, endInt)
    }

    fun upDurIndex(offset: Int, player: StandardGSYVideoPlayer): Boolean {
        if (RestoreRecoveryGate.isQuarantined) return false
        val episodes = episodes ?: return false
        val index = chapterInVolumeIndex + offset
        if (index < 0) {
            appCtx.toastOnUi("已到开头")
            return false
        }
        if (index >= episodes.size) {
            appCtx.toastOnUi("已播放完")
            return false
        }
        chapterInVolumeIndex = index
        saveRead(0)
        startPlay(player)
        postEvent(EventBus.UP_VIDEO_INFO, arrayListOf(1)) //更新选集视图
        return true
    }

    fun saveRead(durPos: Int? = null) {
        if (RestoreRecoveryGate.isQuarantined) return
        val book = book
        val state = (videoManager.listener() as? StandardGSYVideoPlayer)?.currentState
        val hasPosition = state in setOf(GSYVideoView.CURRENT_STATE_PLAYING,
            GSYVideoView.CURRENT_STATE_PAUSE, GSYVideoView.CURRENT_STATE_PLAYING_BUFFERING_START,
            GSYVideoView.CURRENT_STATE_AUTO_COMPLETE)
        val durPos = durPos ?: if (hasPosition) videoManager.currentPosition.toInt() else durChapterPos
        durChapterPos = durPos
        if (book == null) {
            videoUrl?.let { videoUrl ->
                CacheManager.put(VIDEO_POS_NAME + videoUrl, durPos, VIDEO_POS_SAVE_TIME)
            }
            return
        }
        val durVolumeIndex = durVolumeIndex
        val chapterInVolumeIndex = chapterInVolumeIndex
        val source = source
        val chapter = chapter
        val currentTitle = videoTitle
        globalExecutor.execute {
            if (RestoreRecoveryGate.isQuarantined) return@execute
            runCatching {
                book.let { book ->
                    book.lastCheckCount = 0
                    val durTime = System.currentTimeMillis()
                    book.durChapterTime = durTime
                    book.durVolumeIndex = durVolumeIndex
                    book.chapterInVolumeIndex = chapterInVolumeIndex
                    book.durChapterIndex = chapter?.index ?: book.durChapterIndex
                    book.durChapterPos = durPos
                    book.durChapterTitle = chapter?.title
                    SourceCallBack.callBackBook(SourceCallBack.SAVE_READ, source as BookSource?, book, chapter, durTime.toString())
                    book.update()
                }
                postEvent(EventBus.VIDEO_SUB_TITLE, currentTitle ?: appCtx.getString(R.string.data_loading))
            }.onFailure { AppLog.put("保存视频进度失败", it) }
        }
    }

    fun getDisplayCover(): String? {
        return book?.getDisplayCover()
    }
}
