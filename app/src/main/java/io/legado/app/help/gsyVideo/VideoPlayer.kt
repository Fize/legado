package io.legado.app.help.gsyVideo

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.ImageView
import android.widget.TextView
import com.shuyu.gsyvideoplayer.listener.LockClickListener
import com.shuyu.gsyvideoplayer.utils.CommonUtil
import com.shuyu.gsyvideoplayer.video.StandardGSYVideoPlayer
import com.shuyu.gsyvideoplayer.video.base.GSYVideoPlayer
import io.legado.app.R
import io.legado.app.model.VideoPlay
import master.flame.danmaku.controller.DrawHandler
import master.flame.danmaku.danmaku.loader.IllegalDataException
import master.flame.danmaku.danmaku.loader.android.DanmakuLoaderFactory
import master.flame.danmaku.danmaku.model.BaseDanmaku
import master.flame.danmaku.danmaku.model.DanmakuTimer
import master.flame.danmaku.danmaku.model.IDisplayer
import master.flame.danmaku.danmaku.model.android.DanmakuContext
import master.flame.danmaku.danmaku.model.android.SpannedCacheStuffer
import master.flame.danmaku.danmaku.parser.BaseDanmakuParser
import master.flame.danmaku.ui.widget.DanmakuView
import java.io.File
import java.io.FileInputStream

class VideoPlayer: StandardGSYVideoPlayer {
    constructor(context: Context?, fullFlag: Boolean?) : super(context, fullFlag) //必须的,全屏时依靠这个构建知道获取全屏布局
    constructor(context: Context?) : super(context)
    constructor(context: Context?, attrs: AttributeSet?) : super(context, attrs)

    private var episodeList: TextView? = null
    private var playbackSpeed: TextView? = null
    private var playSpeed: Float = 1.0f
    private var btnNext: ImageView? = null
    private var tipView: TextView? = null
    private var isChanging = false
    private var isLongPressSpeed = false

    private var mParser: BaseDanmakuParser? = null //解析器对象
    private var mDanmakuView: DanmakuView? = null //弹幕view
    private var mDanmakuContext: DanmakuContext? = null
    var mToggleDanmaku: TextView? = null //弹幕开关
    private var mDanmakuStartSeekPosition: Long = -1


    override fun initInflate(context: Context?) {
        val ctx = context ?: this.context
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Float) = (v * d).toInt().coerceAtLeast(1)
        val root = android.widget.RelativeLayout(ctx).apply {
            layoutParams = LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(android.graphics.Color.BLACK)
            addView(
                android.widget.RelativeLayout(ctx).apply {
                    id = R.id.surface_container
                    layoutParams = android.widget.RelativeLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    gravity = android.view.Gravity.CENTER
                }
            )
            addView(
                DanmakuView(ctx).apply {
                    id = R.id.danmaku_view
                    layoutParams = android.widget.RelativeLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    visibility = View.GONE
                }
            )
            addView(
                android.widget.RelativeLayout(ctx).apply {
                    id = R.id.thumb
                    layoutParams = android.widget.RelativeLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    ).apply {
                        addRule(android.widget.RelativeLayout.ALIGN_PARENT_START)
                        addRule(android.widget.RelativeLayout.ALIGN_PARENT_TOP)
                        addRule(android.widget.RelativeLayout.ALIGN_PARENT_END)
                        addRule(android.widget.RelativeLayout.ALIGN_PARENT_BOTTOM)
                    }
                    setBackgroundColor(android.graphics.Color.BLACK)
                }
            )
            if (mIfCurrentIsFullscreen) {
                addView(
                    androidx.constraintlayout.widget.ConstraintLayout(ctx).apply {
                        id = R.id.layout_bottom
                        layoutParams = android.widget.RelativeLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply {
                            addRule(android.widget.RelativeLayout.ALIGN_PARENT_BOTTOM)
                        }
                        setBackgroundColor(0x80000000.toInt())
                        setPadding(0, 0, 0, dp(12f))
                        visibility = View.INVISIBLE
                        addView(
                            TextView(ctx).apply {
                                id = R.id.current
                                layoutParams = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams(
                                    ViewGroup.LayoutParams.WRAP_CONTENT,
                                    ViewGroup.LayoutParams.WRAP_CONTENT
                                ).apply {
                                    marginStart = dp(12f)
                                    startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                                    topToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                                }
                                text = "00:00"
                                setTextColor(android.graphics.Color.WHITE)
                            }
                        )
                        addView(
                            TextView(ctx).apply {
                                id = R.id.sprit
                                layoutParams = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams(
                                    ViewGroup.LayoutParams.WRAP_CONTENT,
                                    ViewGroup.LayoutParams.WRAP_CONTENT
                                ).apply {
                                    startToEnd = R.id.current
                                    topToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                                }
                                text = "/"
                                setTextColor(android.graphics.Color.WHITE)
                            }
                        )
                        addView(
                            TextView(ctx).apply {
                                id = R.id.total
                                layoutParams = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams(
                                    ViewGroup.LayoutParams.WRAP_CONTENT,
                                    ViewGroup.LayoutParams.WRAP_CONTENT
                                ).apply {
                                    startToEnd = R.id.sprit
                                    topToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                                }
                                text = "00:00"
                                setTextColor(android.graphics.Color.WHITE)
                            }
                        )
                        addView(
                            android.widget.SeekBar(ctx).apply {
                                id = R.id.progress
                                layoutParams = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.WRAP_CONTENT
                                ).apply {
                                    topToBottom = R.id.sprit
                                }
                                background = null
                                max = 100
                                setPadding(0, dp(8f), 0, dp(8f))
                                progressDrawable = androidx.core.content.ContextCompat.getDrawable(ctx, com.shuyu.gsyvideoplayer.R.drawable.video_seek_progress)
                                thumb = androidx.core.content.ContextCompat.getDrawable(ctx, com.shuyu.gsyvideoplayer.R.drawable.video_seek_thumb)
                            }
                        )
                        addView(
                            ImageView(ctx).apply {
                                id = R.id.start
                                layoutParams = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams(
                                    dp(24f),
                                    dp(24f)
                                ).apply {
                                    marginStart = dp(12f)
                                    startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                                    topToBottom = R.id.progress
                                }
                                setImageResource(R.drawable.ic_play_24dp)
                            }
                        )
                        addView(
                            ImageView(ctx).apply {
                                id = R.id.next
                                layoutParams = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams(
                                    dp(24f),
                                    dp(24f)
                                ).apply {
                                    marginStart = dp(12f)
                                    startToEnd = R.id.start
                                    topToBottom = R.id.progress
                                }
                                setImageResource(R.drawable.ic_skip_next)
                            }
                        )
                        addView(
                            TextView(ctx).apply {
                                id = R.id.toggle_danmaku
                                layoutParams = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams(
                                    ViewGroup.LayoutParams.WRAP_CONTENT,
                                    ViewGroup.LayoutParams.WRAP_CONTENT
                                ).apply {
                                    marginEnd = dp(16f)
                                    endToStart = R.id.episode_list
                                    topToBottom = R.id.progress
                                }
                                gravity = android.view.Gravity.CENTER
                                minEms = 2
                                text = "关弹幕"
                                setTextColor(android.graphics.Color.WHITE)
                                visibility = View.INVISIBLE
                            }
                        )
                        addView(
                            TextView(ctx).apply {
                                id = R.id.episode_list
                                layoutParams = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams(
                                    ViewGroup.LayoutParams.WRAP_CONTENT,
                                    ViewGroup.LayoutParams.WRAP_CONTENT
                                ).apply {
                                    marginEnd = dp(16f)
                                    endToStart = R.id.playback_speed
                                    topToBottom = R.id.progress
                                }
                                gravity = android.view.Gravity.CENTER
                                minEms = 2
                                text = "选集"
                                setTextColor(android.graphics.Color.WHITE)
                            }
                        )
                        addView(
                            TextView(ctx).apply {
                                id = R.id.playback_speed
                                layoutParams = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams(
                                    ViewGroup.LayoutParams.WRAP_CONTENT,
                                    ViewGroup.LayoutParams.WRAP_CONTENT
                                ).apply {
                                    marginEnd = dp(32f)
                                    endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                                    topToBottom = R.id.progress
                                }
                                gravity = android.view.Gravity.CENTER
                                minEms = 2
                                text = "倍速"
                                setTextColor(android.graphics.Color.WHITE)
                            }
                        )
                    }
                )
            } else {
                addView(
                    android.widget.LinearLayout(ctx).apply {
                        id = R.id.layout_bottom
                        layoutParams = android.widget.RelativeLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            dp(50f)
                        ).apply {
                            addRule(android.widget.RelativeLayout.ALIGN_PARENT_BOTTOM)
                        }
                        setBackgroundColor(0x99000000.toInt())
                        gravity = android.view.Gravity.CENTER_VERTICAL
                        orientation = android.widget.LinearLayout.HORIZONTAL
                        visibility = View.INVISIBLE
                        addView(
                            TextView(ctx).apply {
                                id = R.id.current
                                layoutParams = android.widget.LinearLayout.LayoutParams(
                                    ViewGroup.LayoutParams.WRAP_CONTENT,
                                    ViewGroup.LayoutParams.WRAP_CONTENT
                                ).apply {
                                    marginStart = dp(16f)
                                }
                                text = "00:00"
                                setTextColor(android.graphics.Color.WHITE)
                            }
                        )
                        addView(
                            android.widget.SeekBar(ctx).apply {
                                id = R.id.progress
                                layoutParams = android.widget.LinearLayout.LayoutParams(
                                    0,
                                    ViewGroup.LayoutParams.WRAP_CONTENT,
                                    1f
                                ).apply {
                                    gravity = android.view.Gravity.CENTER_VERTICAL
                                }
                                background = null
                                max = 100
                                setPadding(0, dp(8f), 0, dp(8f))
                                progressDrawable = androidx.core.content.ContextCompat.getDrawable(ctx, com.shuyu.gsyvideoplayer.R.drawable.video_seek_progress)
                                thumb = androidx.core.content.ContextCompat.getDrawable(ctx, com.shuyu.gsyvideoplayer.R.drawable.video_seek_thumb)
                            }
                        )
                        addView(
                            TextView(ctx).apply {
                                id = R.id.total
                                layoutParams = android.widget.LinearLayout.LayoutParams(
                                    ViewGroup.LayoutParams.WRAP_CONTENT,
                                    ViewGroup.LayoutParams.WRAP_CONTENT
                                ).apply {
                                    marginEnd = dp(16f)
                                }
                                text = "00:00"
                                setTextColor(android.graphics.Color.WHITE)
                            }
                        )
                        addView(
                            TextView(ctx).apply {
                                id = R.id.toggle_danmaku
                                layoutParams = android.widget.LinearLayout.LayoutParams(
                                    ViewGroup.LayoutParams.WRAP_CONTENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                                gravity = android.view.Gravity.CENTER
                                minEms = 2
                                text = "关弹幕"
                                setTextColor(android.graphics.Color.WHITE)
                                visibility = View.GONE
                            }
                        )
                        addView(
                            ImageView(ctx).apply {
                                id = R.id.fullscreen
                                layoutParams = android.widget.LinearLayout.LayoutParams(
                                    ViewGroup.LayoutParams.WRAP_CONTENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                                val pad16 = dp(16f)
                                setPadding(pad16, pad16, pad16, pad16)
                                scaleType = ImageView.ScaleType.CENTER
                                setImageResource(com.shuyu.gsyvideoplayer.R.drawable.video_enlarge)
                            }
                        )
                    }
                )
            }
            addView(
                android.widget.ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
                    id = R.id.bottom_progressbar
                    layoutParams = android.widget.RelativeLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(1.5f)
                    ).apply {
                        addRule(android.widget.RelativeLayout.ALIGN_PARENT_BOTTOM)
                    }
                    max = 100
                    progressDrawable = androidx.core.content.ContextCompat.getDrawable(ctx, com.shuyu.gsyvideoplayer.R.drawable.video_progress)
                }
            )
            addView(
                ImageView(ctx).apply {
                    id = R.id.back_tiny
                    layoutParams = android.widget.RelativeLayout.LayoutParams(dp(24f), dp(24f)).apply {
                        marginStart = dp(6f)
                        topMargin = dp(6f)
                    }
                    visibility = View.GONE
                }
            )
            addView(
                moe.codeest.enviews.ENDownloadView(ctx, null).apply {
                    id = R.id.loading
                    layoutParams = android.widget.RelativeLayout.LayoutParams(dp(28f), dp(28f)).apply {
                        addRule(android.widget.RelativeLayout.CENTER_HORIZONTAL)
                        addRule(android.widget.RelativeLayout.CENTER_VERTICAL)
                    }
                    visibility = View.INVISIBLE
                }
            )
            if (mIfCurrentIsFullscreen) {
                addView(
                    ImageView(ctx).apply {
                        id = R.id.small_close
                        layoutParams = android.widget.RelativeLayout.LayoutParams(dp(30f), dp(30f))
                        setPaddingRelative(dp(10f), dp(10f), 0, 0)
                        scaleType = ImageView.ScaleType.CENTER_INSIDE
                        setImageResource(com.shuyu.gsyvideoplayer.R.drawable.video_small_close)
                        visibility = View.GONE
                    }
                )
                addView(
                    ImageView(ctx).apply {
                        id = R.id.lock_screen
                        layoutParams = android.widget.RelativeLayout.LayoutParams(dp(30f), dp(30f)).apply {
                            addRule(android.widget.RelativeLayout.ALIGN_PARENT_END)
                            addRule(android.widget.RelativeLayout.CENTER_VERTICAL)
                            marginEnd = dp(50f)
                        }
                        scaleType = ImageView.ScaleType.CENTER_INSIDE
                        setImageResource(com.shuyu.gsyvideoplayer.R.drawable.unlock)
                        visibility = View.GONE
                    }
                )
                addView(
                    android.widget.LinearLayout(ctx).apply {
                        id = R.id.layout_top
                        layoutParams = android.widget.RelativeLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            dp(48f)
                        ).apply {
                            addRule(android.widget.RelativeLayout.ALIGN_PARENT_TOP)
                            addRule(android.widget.RelativeLayout.END_OF, R.id.back_tiny)
                        }
                        setBackgroundResource(com.shuyu.gsyvideoplayer.R.drawable.video_title_bg)
                        gravity = android.view.Gravity.CENTER_VERTICAL
                        addView(
                            ImageView(ctx).apply {
                                id = R.id.back
                                layoutParams = android.widget.LinearLayout.LayoutParams(dp(48f), dp(48f))
                                setPaddingRelative(dp(10f), 0, 0, 0)
                                scaleType = ImageView.ScaleType.CENTER_INSIDE
                                setImageResource(com.shuyu.gsyvideoplayer.R.drawable.video_back)
                            }
                        )
                        addView(
                            TextView(ctx).apply {
                                id = R.id.title
                                layoutParams = android.widget.LinearLayout.LayoutParams(
                                    0,
                                    ViewGroup.LayoutParams.WRAP_CONTENT,
                                    1f
                                )
                                setPaddingRelative(dp(10f), 0, 0, 0)
                                setTextColor(android.graphics.Color.WHITE)
                                textSize = 18f
                            }
                        )
                    }
                )
            } else {
                addView(
                    moe.codeest.enviews.ENPlayView(ctx, null).apply {
                        id = R.id.start
                        layoutParams = android.widget.RelativeLayout.LayoutParams(dp(60f), dp(60f)).apply {
                            addRule(android.widget.RelativeLayout.CENTER_HORIZONTAL)
                            addRule(android.widget.RelativeLayout.CENTER_VERTICAL)
                        }
                    }
                )
            }
            addView(
                android.widget.RelativeLayout(ctx).apply {
                    id = R.id.preview_layout
                    layoutParams = android.widget.RelativeLayout.LayoutParams(
                        ctx.resources.getDimensionPixelSize(com.shuyu.gsyvideoplayer.R.dimen.seek_bar_image),
                        dp(100f)
                    ).apply {
                        addRule(android.widget.RelativeLayout.ABOVE, R.id.layout_bottom)
                        addRule(android.widget.RelativeLayout.ALIGN_PARENT_BOTTOM)
                        bottomMargin = dp(30f)
                    }
                    visibility = View.GONE
                }
            )
            addView(
                TextView(ctx).apply {
                    id = R.id.tip_view
                    layoutParams = android.widget.RelativeLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        addRule(android.widget.RelativeLayout.ALIGN_PARENT_TOP)
                        addRule(android.widget.RelativeLayout.CENTER_IN_PARENT)
                        topMargin = dp(50f)
                    }
                    alpha = 0f
                    setBackgroundResource(R.drawable.bg_fillet_btn)
                    elevation = dp(4f).toFloat()
                    gravity = android.view.Gravity.CENTER
                    val pad12 = dp(12f)
                    setPadding(pad12, pad12, pad12, pad12)
                    setTextColor(android.graphics.Color.WHITE)
                    visibility = View.INVISIBLE
                }
            )
        }
        addView(root)
    }

    override fun getLayoutId(): Int = 0

    override fun getFullWindowPlayer(): VideoPlayer? {
        val activity = CommonUtil.scanForActivity(context) ?: return null
        val vp = activity.findViewById<View?>(Window.ID_ANDROID_CONTENT) as ViewGroup
        val full = vp.findViewById<View?>(fullId)
        var gsyVideoPlayer: VideoPlayer? = null
        if (full != null) {
            gsyVideoPlayer = full as VideoPlayer
        }
        return gsyVideoPlayer
    }
    override fun getSmallWindowPlayer(): VideoPlayer? = null

    override fun getCurrentPlayer(): VideoPlayer {
        val fullVideoPlayer = getFullWindowPlayer()
        if (fullVideoPlayer != null) {
            return fullVideoPlayer
        }
        val smallVideoPlayer = getSmallWindowPlayer()
        if (smallVideoPlayer != null) {
            return smallVideoPlayer
        }
        return this
    }

    fun getLockCurScreen() = mLockCurScreen

    public override fun lockTouchLogic() = super.lockTouchLogic()

    override fun init(context: Context) {
        super.init(context)
        initView()
        post {
            gestureDetector = GestureDetector(
                getContext().applicationContext,
                object : GestureDetector.SimpleOnGestureListener() {
                    override fun onDoubleTap(e: MotionEvent): Boolean {
                        touchDoubleUp(e)
                        return super.onDoubleTap(e)
                    }

                    override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                        if (!mChangePosition && !mChangeVolume && !mBrightness && mCurrentState != CURRENT_STATE_ERROR
                        ) {
                            onClickUiToggle(e)
                        }
                        return super.onSingleTapConfirmed(e)
                    }

                    override fun onLongPress(e: MotionEvent) {
                        if (mCurrentState == CURRENT_STATE_PLAYING) {
                            val speed = VideoPlay.longPressSpeed / 10.0f
                            setVideoSpeed(speed)
                            showOverlayTip("${speed}倍速播放中")
                            isLongPressSpeed = true
                        }
                        super.onLongPress(e)
                    }
                }
            )
            mLockClickListener = LockClickListener { view, lock ->
                VideoPlay.lockCurScreen = lock
            }
        }
    }
    override fun touchSurfaceUp(){
        if (isLongPressSpeed) {
            isLongPressSpeed = false
            setVideoSpeed(playSpeed)
            showOverlayTip()
            val time = getCurrentPositionWhenPlaying()
            resolveDanmakuStart(time)
        }
        super.touchSurfaceUp()
    }

    private fun setVideoSpeed(speed: Float) {
        setSpeed(speed, true)
        if (mDanmakuView != null&& !mDanmakuView!!.isPaused) {
            mDanmakuContext!!.setScrollSpeedFactor(VideoPlay.danmakuSpeed - (speed - 1f) / 6f)
            mDanmakuView!!.invalidate()
        }
    }

    override fun onPrepared() {
        super.onPrepared()
        onPrepareDanmaku(this)
    }
    private fun onPrepareDanmaku(gsyVideoPlayer: VideoPlayer) {
        val view = gsyVideoPlayer.mDanmakuView
        val par = gsyVideoPlayer.mParser
        val con = gsyVideoPlayer.mDanmakuContext
        if ( view != null && !view.isPrepared && par != null) {
            view.prepare(par, con)
        }
    }

    override fun onVideoPause() {
        super.onVideoPause()
        VideoPlay.saveRead()
        danmakuOnPause()
    }
    fun danmakuOnPause() {
        if (mDanmakuView != null && mDanmakuView!!.isPrepared) {
            mDanmakuView!!.pause()
        }
    }

    override fun onVideoResume(isResume: Boolean) {
        super.onVideoResume(isResume)
        danmakuOnResume()
    }
    fun danmakuOnResume() {
        if (mDanmakuView != null && mDanmakuView!!.isPrepared && mDanmakuView!!.isPaused) {
            mDanmakuView!!.resume()
        }
    }

    override fun clickStartIcon() {
        super.clickStartIcon()
        if (mCurrentState == CURRENT_STATE_PLAYING) {
            danmakuOnResume()
        } else if (mCurrentState == CURRENT_STATE_PAUSE) {
            danmakuOnPause()
        }
    }

    override fun onAutoCompletion() { //播放完成
        super.onAutoCompletion()
        VideoPlay.upDurIndex(1, this)
    }

    override fun onCompletion() {
        super.onCompletion()
        releaseDanmaku(this)
    }
    fun releaseDanmaku(gsyVideoPlayer: VideoPlayer) {
        gsyVideoPlayer.mDanmakuView?.release()
    }


    override fun onSeekComplete() {
        super.onSeekComplete()
        val time = mProgressBar.progress * getDuration() / 100
        //如果已经初始化过的，直接seek到对于位置
        if (mHadPlay && mDanmakuView != null && mDanmakuView!!.isPrepared) {
            resolveDanmakuSeek(time)
        } else if (mHadPlay && mDanmakuView != null && !mDanmakuView!!.isPrepared) {
            //如果没有初始化过的，记录位置等待
            mDanmakuStartSeekPosition = time
        }
    }


    fun showOverlayTip(message: String? = null, delay: Long = 0) {
        tipView?.apply {
            message?.also {
                text = it
                visibility = VISIBLE
                alpha = 1f
                if (delay > 0) {
                    postDelayed({
                        alpha = 0f
                    }, delay)
                }
            } ?: run {
                visibility = INVISIBLE
                alpha = 0f
            }
        }
    }

    private fun initView() {
        isNeedLockFull = true //使用锁定按钮
        playbackSpeed = findViewById(R.id.playback_speed)
        playbackSpeed?.setOnClickListener {
            if (mHadPlay && !isChanging) {
                showSpeedDialog()
            }
        }
        tipView = findViewById(R.id.tip_view)
        if (mIfCurrentIsFullscreen && !VideoPlay.fullBottomProgressBar) {
            mBottomProgressBar = null
        }
        //切换选集
        episodeList = findViewById(R.id.episode_list)
        btnNext = findViewById(R.id.next)
        if (VideoPlay.episodes == null) {
            episodeList?.visibility = GONE
            btnNext?.visibility = GONE
            return
        }
        episodeList?.setOnClickListener {
            if (mHadPlay && !isChanging) {
                showEpisodeDialog()
            }
        }
        btnNext?.setOnClickListener {
            VideoPlay.upDurIndex(1,this)
        }
    }


    override fun setUp(url: String?, cacheWithPlay: Boolean, cachePath: File?, title: String?): Boolean {
        initDanmaku()
        return super.setUp(url, cacheWithPlay, cachePath, title)
    }

    private fun initDanmaku() {
        val danmakuFile = VideoPlay.danmakuFile
        val danmakuStr = VideoPlay.danmakuStr
        if (danmakuFile == null && danmakuStr.isNullOrBlank()) {
            mToggleDanmaku?.visibility = GONE
            return
        }
        mDanmakuView = findViewById<DanmakuView>(R.id.danmaku_view)?.also {
            it.visibility = VISIBLE
        }
        //弹幕开关
        mToggleDanmaku = findViewById<TextView>(R.id.toggle_danmaku)?.also {
            it.visibility = VISIBLE
            it.setOnClickListener { //按钮事件
                VideoPlay.danmakuShow = !VideoPlay.danmakuShow
                resolveDanmakuShow()
            }
        }
        if (mDanmakuView != null) {
            // 设置最大显示行数
            val maxLinesPair = HashMap<Int?, Int?>()
            maxLinesPair[BaseDanmaku.TYPE_SCROLL_RL] = 5 // 滚动弹幕最大显示5行
            // 设置是否禁止重叠
            val overlappingEnablePair = HashMap<Int?, Boolean?>()
            overlappingEnablePair[BaseDanmaku.TYPE_SCROLL_RL] = true
            overlappingEnablePair[BaseDanmaku.TYPE_FIX_TOP] = true
            val danmakuAdapter = DanmakuAdapter(mDanmakuView)
            mDanmakuContext = DanmakuContext.create() //初始化上下文
            mDanmakuContext!!.setDanmakuStyle(IDisplayer.DANMAKU_STYLE_STROKEN, 3f) //设置弹幕类型
                .setDuplicateMergingEnabled(false) //设置是否合并重复弹幕
                .setScrollSpeedFactor(VideoPlay.danmakuSpeed) //设置弹幕滚动速度
                .setScaleTextSize(1.0f) //设置弹幕字体大小
                .setCacheStuffer(SpannedCacheStuffer(), danmakuAdapter) //设置缓存绘制填充器 图文混排使用SpannedCacheStuffer
                .setMaximumLines(maxLinesPair) //设置最大行数
                .preventOverlapping(overlappingEnablePair) //设置是否禁止重叠
            mParser = createParser(danmakuFile, danmakuStr) //加载弹幕资源文件
            mDanmakuView!!.setCallback(object : DrawHandler.Callback {
                override fun updateTimer(timer: DanmakuTimer?) {}
                override fun drawingFinished() {}
                override fun danmakuShown(danmaku: BaseDanmaku?) {}
                override fun prepared() {
                    if (mDanmakuView != null) {
                        mDanmakuView!!.start()
                        if (mDanmakuStartSeekPosition != -1L) {
                            resolveDanmakuSeek(mDanmakuStartSeekPosition)
                            mDanmakuStartSeekPosition = -1L
                        }
                        resolveDanmakuShow()
                    }
                }
            })
            mDanmakuView!!.enableDanmakuDrawingCache(true)
        }
    }

    /**
     * 弹幕偏移
     */
    private fun resolveDanmakuSeek(time: Long) {
        if (mHadPlay && mDanmakuView != null && mDanmakuView!!.isPrepared) {
            mDanmakuView!!.seekTo(time)
        }
    }

    private fun resolveDanmakuStart(time: Long) {
        if (mHadPlay && mDanmakuView != null && mDanmakuView!!.isPrepared) {
            mDanmakuView!!.seekTo(time)
        }
    }


    private fun resolveDanmakuShow() {
        post {
            if (VideoPlay.danmakuShow) {
                if (!mDanmakuView!!.isShown) mDanmakuView!!.show()
                mToggleDanmaku?.text = "关弹幕"
            } else {
                if (mDanmakuView!!.isShown) mDanmakuView!!.hide()
                mToggleDanmaku?.text = "开弹幕"
            }
        }
    }

    /**
     * 创建解析器对象，解析输入流
     *
     * @param stream
     * @return
     */
    private fun createParser(danmakuFile: File?, danmakuStr: String?): BaseDanmakuParser {
        val loader = DanmakuLoaderFactory.create(DanmakuLoaderFactory.TAG_BILI)
        try {
            if (danmakuFile != null) {
                loader.load(FileInputStream(danmakuFile))
            } else if (danmakuStr != null) {
                if (danmakuStr.startsWith("http",true)) {
                    loader.load(danmakuStr)
                } else {
                    loader.load(danmakuStr.byteInputStream())
                }
            }
        } catch (e: IllegalDataException) {
            e.printStackTrace()
        }
        val parser: BaseDanmakuParser = BiliDanmukuParser()
        val dataSource = loader.dataSource
        parser.load(dataSource)
        return parser
    }

    private fun showEpisodeDialog() {
        if (!mHadPlay || VideoPlay.episodes.isNullOrEmpty()) {
            return
        }
        isChanging = true
        val choiceEpisodeDialog = ChoiceEpisodeDialog(mContext)
        choiceEpisodeDialog.initList(VideoPlay.episodes!!, object :
            ChoiceEpisodeDialog.OnListItemClickListener {
            override fun onItemClick(position: Int) {
                VideoPlay.chapterInVolumeIndex = position
                VideoPlay.saveRead(0)
                VideoPlay.startPlay(this@VideoPlayer)
            }

            override fun finishDialog() {
                isChanging = false
            }
        }, VideoPlay.chapterInVolumeIndex)
        choiceEpisodeDialog.show()
    }

    private fun showSpeedDialog() {
        if (!mHadPlay) {
            return
        }
        isChanging = true
        val choiceSpeedDialog = ChoiceSpeedDialog(mContext)
        choiceSpeedDialog.initList(listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 2.5f, 3.0f).reversed(), object :
            ChoiceSpeedDialog.OnListItemClickListener {
            @SuppressLint("SetTextI18n")
            override fun onItemClick(value: Float) {
                playSpeed = value
                setSpeed(playSpeed, true)
                if (playSpeed != 1.0f) {
                    playbackSpeed?.text = "${playSpeed}X"
                    showOverlayTip("${playSpeed}倍播放中", 2000)
                } else {
                    playbackSpeed?.text = "倍速"
                }
            }

            override fun finishDialog() {
                isChanging = false
            }
        })
        choiceSpeedDialog.show()
    }

    override fun updateStartImage() {
        if (mIfCurrentIsFullscreen) {
            if (mStartButton is ImageView) {
                val imageView = mStartButton as ImageView
                when (mCurrentState) {
                    CURRENT_STATE_PLAYING -> {
                        imageView.setImageResource(R.drawable.ic_pause_24dp)
                    }
                    CURRENT_STATE_ERROR -> {
                        imageView.setImageResource(R.drawable.ic_pause_outline_24dp)
                    }
                    else -> {
                        imageView.setImageResource(R.drawable.ic_play_24dp)
                    }
                }
            }
        } else {
            super.updateStartImage()
        }
    }

    override fun onError(what: Int, extra: Int) {
        super.onError(what, extra)
        VideoPlay.saveRead()
        mSeekOnStart = VideoPlay.durChapterPos.toLong()
    }


    /**
     * 处理播放器在全屏切换时，弹幕显示的逻辑
     * 需要格外注意的是，因为全屏和小屏，是切换了播放器，所以需要同步之间的弹幕状态
     */
    override fun startWindowFullscreen(
        context: Context?,
        actionBar: Boolean,
        statusBar: Boolean
    ): VideoPlayer? {
        val pendingSeek = seekOnStart
        val gsyBaseVideoPlayer = super.startWindowFullscreen(context, actionBar, statusBar)
        if (gsyBaseVideoPlayer != null) {
            val gsyVideoPlayer = gsyBaseVideoPlayer as VideoPlayer
            // The library's fullscreen clone does not copy a seek that is waiting for preparation.
            if (pendingSeek > 0) {
                gsyVideoPlayer.seekOnStart = pendingSeek
                seekOnStart = 0
            }
            //对弹幕设置偏移记录
//            gsyVideoPlayer.mDanmakuView = this.mDanmakuView
            gsyVideoPlayer.mDanmakuStartSeekPosition = this.getCurrentPositionWhenPlaying()
            onPrepareDanmaku(gsyVideoPlayer)
        }
        return gsyBaseVideoPlayer
    }

    /**
     * 处理播放器在退出全屏时，弹幕显示的逻辑
     * 需要格外注意的是，因为全屏和小屏，是切换了播放器，所以需要同步之间的弹幕状态
     */
    override fun resolveNormalVideoShow(
        oldF: View?,
        vp: ViewGroup?,
        gsyVideoPlayer: GSYVideoPlayer?
    ) {
        super.resolveNormalVideoShow(oldF, vp, gsyVideoPlayer)
        if (gsyVideoPlayer != null) {
            val videoPlayer = gsyVideoPlayer as VideoPlayer
            if (mDanmakuView != null && mDanmakuView!!.isPrepared) {
                resolveDanmakuSeek(videoPlayer.getCurrentPositionWhenPlaying())
                resolveDanmakuShow()
                releaseDanmaku(videoPlayer)
            }
        }
    }

    override fun release() {
        super.release()
        releaseDanmaku(this)
    }

    /**********以下重载GSYVideoPlayer的GSYVideoViewBridge相关实现***********/
    override fun getGSYVideoManager(): ExoVideoManager {
        return VideoPlay.videoManager.apply { initContext(context.applicationContext) }
    }
    public override fun backFromFull(context: Context?): Boolean {
        return VideoPlay.backFromWindowFull(context)
    }
    override fun releaseVideos() {
        VideoPlay.releaseAllVideos()
    }

    override fun getFullId(): Int {
        return ExoVideoManager.FULLSCREEN_ID
    }

    override fun getSmallId(): Int {
        return ExoVideoManager.SMALL_ID
    }
    override fun setDisplay(surface: Surface?) {
        if (surface != null && mTextureView.getShowView() is SurfaceView) {
            val surfaceView = (mTextureView.getShowView() as SurfaceView?)
            gsyVideoManager.setDisplayNew(surfaceView)
        } else if (surface != null) {
            gsyVideoManager.setDisplay(surface)
        } else {
            gsyVideoManager.setDisplayNew(null)
        }
    }
    fun nextUI() { resetProgressAndTime() }


    //播放器转移
    fun setSurfaceToPlay() {
        addTextureView()
        gsyVideoManager.setListener(this)
        checkoutState()
    }

    var needDestroy: Boolean = true
    override fun onSurfaceDestroyed(surface: Surface?): Boolean {
        if (needDestroy) {
            return super.onSurfaceDestroyed(surface)
        } else {
            releaseSurface(surface)
            needDestroy = true
            return true
        }
    }

    fun saveState(): VideoPlayer {
        return this
    }

    fun cloneState(switchVideo: StandardGSYVideoPlayer) {
        cloneParams(switchVideo, this)
    }
}
