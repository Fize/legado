package io.legado.app.service

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.os.Bundle
import android.provider.Settings
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.View.OnTouchListener
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.dynamicanimation.animation.FloatPropertyCompat
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import com.shuyu.gsyvideoplayer.listener.GSYSampleCallBack
import io.legado.app.R
import io.legado.app.base.BaseService
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.IntentAction
import io.legado.app.constant.NotificationId
import io.legado.app.help.MediaHelp
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.glide.ImageLoader
import io.legado.app.help.gsyVideo.FloatingPlayer
import io.legado.app.model.VideoPlay
import io.legado.app.receiver.MediaButtonReceiver
import io.legado.app.ui.video.VideoPlayerActivity
import io.legado.app.ui.book.read.PlatformMediaReturnSession
import io.legado.app.data.repository.PlatformReaderReturnRepository
import io.legado.app.constant.BookType
import io.legado.app.help.storage.RestoreRecoveryGate
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.lang.ref.WeakReference
import io.legado.app.utils.activityPendingIntent
import io.legado.app.utils.broadcastPendingIntent
import io.legado.app.utils.printOnDebug
import io.legado.app.utils.servicePendingIntent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import splitties.systemservices.notificationManager
import kotlin.math.abs

/**
 * 视频悬浮窗服务
 */
class VideoPlayService : BaseService() {
    companion object {
        @JvmStatic
        var pause = true
            private set
        private const val APP_ACTION_STOP = "Stop"
        private var activeService: WeakReference<VideoPlayService>? = null

        /** Put the previous save/return on the persistence queue before a new host can claim. */
        internal fun prepareForPlayerEntry(intent: Intent): Boolean {
            val service = activeService?.get() ?: return true
            if (!service.canRunVideoCallbacks() || !intent.getBooleanExtra("isNew", true)) return true
            service.preparation?.cancel()
            val id = intent.getStringExtra(PlatformReaderReturnRepository.REQUEST_ID)
            if (id != null && service.platformReturn.requestId == id && VideoPlay.book?.bookUrl == intent.getStringExtra("bookUrl")) {
                VideoPlay.saveRead()
                if (runCatching { service.platformReturn.handoffTo(intent) { } }
                        .onFailure { AppLog.put("切换视频窗口失败", it, toast = true) }.isFailure) return false
                service.returnSessionFinished = true
                service.handoffOut = true
                intent.putExtra("isNew", false).putExtra("forceNormalPlayer", true)
            } else {
                VideoPlay.saveRead()
                service.platformReturn.close()
                service.returnSessionFinished = true
                service.handoffOut = true
                service.stopSelf()
            }
            return true
        }

        internal fun finishPlayerEntry(intent: Intent) {
            val service = activeService?.get() ?: return
            if (!intent.hasExtra(io.legado.app.data.repository.MediaHandoffTickets.EXTRA) ||
                service.platformReturn.isOutgoingHandoff(intent)) service.stopSelf()
        }
    }
    override val guardsRestoreRecovery = true
    private var mediaInitialized = false
    private var platformReturn = PlatformMediaReturnSession()
    private var returnSessionFinished = false
    private var handoffOut = false
    private var handoffStopJob: Job? = null
    private var playbackIntent: Intent? = null
    private var preparation: Coroutine<*>? = null
    private val preparationSemaphore = Semaphore(1)
    private var latestPlaybackStartId = 0
    private var inputGeneration = 0L

    private fun canRunVideoCallbacks(): Boolean =
        lifecycle.currentState != Lifecycle.State.DESTROYED && !stopIfRestoreRecoveryRequired()

    private lateinit var windowManager: WindowManager
    private lateinit var params: WindowManager.LayoutParams
    private val mediaSessionDelegate = lazy {
        MediaSessionCompat(this, "videoPlayService")
    }
    private val mediaSessionCompat by mediaSessionDelegate
    private val floatingViewDelegate = lazy {
        FrameLayout(this).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
            addView(
                FloatingPlayer(this@VideoPlayService).apply {
                    id = R.id.floatingPlayerView
                    layoutParams = FrameLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        Gravity.CENTER
                    )
                }
            )
        }
    }
    private val floatingView by floatingViewDelegate
    private val playerView by lazy { floatingView.findViewById<FloatingPlayer>(R.id.floatingPlayerView) }
    private var isNew = true
    private var mediaNotificationReady = false
    private var upNotificationJob: Coroutine<*>? = null
    private var animator: SpringAnimation? = null
    private var verticalAnimator: ValueAnimator? = null
    private var cover: Bitmap =
        BitmapFactory.decodeResource(appCtx.resources, R.drawable.icon_read_book)
    private var upPlayProgressJob: Job? = null
    private var broadcastReceiver: BroadcastReceiver? = null
    private val activityLifecycleCallbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            if (!handoffOut && activity is VideoPlayerActivity && activity.intent.getBooleanExtra("isNew", true) &&
                !activity.intent.getBooleanExtra("forwardedToFloatingWindow", false)
            ) {
                // 确保 Activity 创建完成后才停止服务,留够时间复制播放器
                stop()
            }
        }

        override fun onActivityStarted(activity: Activity) {}
        override fun onActivityResumed(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivityStopped(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }

    private fun updateViewPosition() {
        if (!canRunVideoCallbacks()) return
        try {
            windowManager.updateViewLayout(floatingView, params)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun cancelAnimator() {
        animator?.cancel()
        verticalAnimator?.cancel()
    }

    private val layoutParamsXProperty =
        object : FloatPropertyCompat<WindowManager.LayoutParams>("x") {
            override fun getValue(layoutParams: WindowManager.LayoutParams): Float {
                return layoutParams.x.toFloat()
            }

            override fun setValue(layoutParams: WindowManager.LayoutParams, value: Float) {
                layoutParams.x = value.toInt()
                updateViewPosition()
            }
        }

    private fun startEdgeAnimation() {
        if (!canRunVideoCallbacks()) return
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        val viewWidth = floatingView.width
        val viewHeight = floatingView.height
        val currentX = params.x
        val endX = if (viewWidth == screenWidth) {
            0
        } else if (currentX + viewWidth / 2 > screenWidth / 2) {
            screenWidth - viewWidth - 30
        } else {
            30
        }
        val currentY = params.y
        var endY = currentY
        if (currentY < 30) {
            endY = 30
        } else if (currentY > screenHeight - viewHeight - 60) {
            endY = screenHeight - viewHeight - 60
        }
        if (endY != currentY) {
            verticalAnimator = ValueAnimator.ofInt(currentY, endY).apply {
                duration = 200
                interpolator = DecelerateInterpolator()
                addUpdateListener { animation ->
                    params.y = animation.animatedValue as Int
                    updateViewPosition()
                }
                start()
            }
        }
        animator = SpringAnimation(params, layoutParamsXProperty, endX.toFloat()).apply {
            spring.stiffness = SpringForce.STIFFNESS_LOW  // 低刚度更Q弹
            spring.dampingRatio = SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY  // 中等阻尼
            start()
        }
    }

    override fun onCreate() {
        super.onCreate()
        if (isRestoreRecoveryBlocked) return
        mediaInitialized = true
        activeService = WeakReference(this)
        initMediaSession()
        initBroadcastReceiver()
        application.registerActivityLifecycleCallbacks(activityLifecycleCallbacks)
        val book = VideoPlay.book
        val coverPath = book?.getDisplayCover() ?: VideoPlay.getDisplayCover()
        val sourceOrigin = if (book != null) {
            book.getCoverSourceOrigin()
        } else {
            VideoPlay.source?.getKey()
        }
        execute {
            ImageLoader
                .loadBitmap(
                    this@VideoPlayService,
                    coverPath,
                    sourceOrigin,
                )
                .submit()
                .get()
        }.onSuccess {
            if (!canRunVideoCallbacks()) return@onSuccess
            if (it.width > 16 && it.height > 16) {
                cover = it
                upMediaMetadata()
                upVideoPlayNotification()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (stopIfRestoreRecoveryRequired()) {
            io.legado.app.data.repository.MediaHandoffTickets.expire(intent)
            return START_NOT_STICKY
        }
        val startResult = super.onStartCommand(intent, flags, startId)
        if (startResult == START_NOT_STICKY) return startResult
        if (intent != null && intent.action == null) {
            if (returnSessionFinished || platformReturn.handoffStopped) {
                platformReturn.retireIncomingHandoff()
                platformReturn = PlatformMediaReturnSession()
                returnSessionFinished = false
                handoffOut = false
            }
            inputGeneration = platformReturn.receiveHandoff(intent)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                checkFloatPermission()
                stopSelf()
                return START_NOT_STICKY
            }
        }
        if (intent == null) {
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        intent.action?.let { action ->
            when (action) {
                IntentAction.pause -> pause()
                IntentAction.resume -> resume()
                IntentAction.prev -> VideoPlay.upDurIndex(-1, playerView)
                IntentAction.next -> VideoPlay.upDurIndex(1, playerView)
                IntentAction.stop -> stop()
            }
            return startResult
        }
        latestPlaybackStartId = startId
        val receivingSession = platformReturn
        handoffStopJob?.cancel()
        handoffStopJob = lifecycleScope.launch {
            receivingSession.stopEvents.collect { stopped ->
                if (stopped && platformReturn === receivingSession && latestPlaybackStartId == startId) stopSelf()
            }
        }
        if (receivingSession.handoffStopped) return START_NOT_STICKY
        preparation?.cancel()
        val needsPreparation = platformReturn.needsPreparation(intent)
        if (needsPreparation) {
            val session = platformReturn
            val generation = inputGeneration
            preparation = execute(semaphore = preparationSemaphore) {
                val prepared = session.prepare(intent, BookType.video, generation)
                currentCoroutineContext().ensureActive()
                Intent(intent).apply { prepared?.book?.let { putExtra("bookUrl", it.bookUrl) } }
            }.onSuccess { prepared ->
                if (session.handoffStopped) stopSelfResult(startId)
                else if (canRunVideoCallbacks() && latestPlaybackStartId == startId) startPlayer(prepared, startId)
            }.onError {
                if (!RestoreRecoveryGate.isQuarantined) AppLog.put("视频悬浮播放初始化失败", it)
                stopSelfResult(startId)
            }
        } else startPlayer(intent, startId)
        return startResult
    }

    private fun startPlayer(intent: Intent, startId: Int) {
        if (!canRunVideoCallbacks()) return
        playbackIntent = Intent(intent)
        isNew = intent.getBooleanExtra("isNew", true) || VideoPlay.videoManager.listener() == null
        upNotificationJob?.cancel()
        if (isNew) {
            mediaNotificationReady = false
            VideoPlay.stopLoading()
            VideoPlay.releaseAllVideos()
            startForegroundNotification()
            val videoUrl = intent.getStringExtra("videoUrl")
            VideoPlay.videoUrl = videoUrl
            VideoPlay.singleUrl = videoUrl != null
            VideoPlay.videoTitle = intent.getStringExtra("videoTitle")
            val sourceKey = intent.getStringExtra("sourceKey")
            val sourceType = intent.getIntExtra("sourceType", 0)
            val bookUrl = intent.getStringExtra("bookUrl")
            val record = intent.getStringExtra("record")
            VideoPlay.inBookshelf = intent.getBooleanExtra("inBookshelf", true)
            if (!VideoPlay.initSource(sourceKey, sourceType, bookUrl, record)) {
                stopSelfResult(startId)
                return
            }
            VideoPlay.startPlay(playerView)
            VideoPlay.saveRead(VideoPlay.durChapterPos)
        } else {
            VideoPlay.clonePlayState(playerView)
            playerView.setSurfaceToPlay()
            playerView.startAfterPrepared()
        }
        setupPlayerView()
        if (floatingView.parent == null) {
            createFloatingWindow()
        }
        if (!isNew) {
            mediaNotificationReady = true
            upVideoPlayNotification()
        }
    }

    @SuppressLint("UnspecifiedImmutableFlag")
    private fun initMediaSession() {
        mediaSessionCompat.setFlags(
            MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
        )
        mediaSessionCompat.setCallback(object : MediaSessionCompat.Callback() {
            override fun onSeekTo(pos: Long) {
                if (canRunVideoCallbacks()) playerView.seekTo(pos)
            }
            override fun onPlay() = resume()
            override fun onPause() = pause()
            override fun onCustomAction(action: String?, extras: Bundle?) {
                action ?: return
                when (action) {
                    APP_ACTION_STOP -> stop()
                }
            }
            override fun onSkipToPrevious() {
                super.onSkipToPrevious()
                if (canRunVideoCallbacks()) VideoPlay.upDurIndex(-1, playerView)
            }
            override fun onSkipToNext() {
                super.onSkipToNext()
                if (canRunVideoCallbacks()) VideoPlay.upDurIndex(1, playerView)
            }
        })
        mediaSessionCompat.setMediaButtonReceiver(
            broadcastPendingIntent<MediaButtonReceiver>(Intent.ACTION_MEDIA_BUTTON)
        )
        mediaSessionCompat.isActive = true
    }

    private fun upVideoPlayNotification() {
        if (!canRunVideoCallbacks() || !mediaNotificationReady) return
        upNotificationJob?.cancel()
        upNotificationJob = execute {
            try {
                val notification = createNotification()
                withContext(Main) {
                    if (canRunVideoCallbacks() && mediaNotificationReady) {
                        notificationManager.notify(
                            NotificationId.VideoPlayService,
                            notification.build()
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.put("创建视频播放通知出错,${e.localizedMessage}", e, true)
            }
        }
    }

    override fun startForegroundNotification() {
        if (!canRunVideoCallbacks()) return
        val notification = NotificationCompat.Builder(this, AppConst.channelIdReadAloud)
            .setSmallIcon(R.drawable.ic_volume_up)
            .setSubText(getString(R.string.video))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentTitle(getString(R.string.video))
            .setContentIntent(activityPendingIntent<VideoPlayerActivity>("activity"))
            .build()
        startForeground(NotificationId.VideoPlayService, notification)
    }

    /**
     * 断开耳机监听
     */
    private fun initBroadcastReceiver() {
        broadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (AudioManager.ACTION_AUDIO_BECOMING_NOISY == intent.action) {
                    pause()
                }
            }
        }
        val intentFilter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        registerReceiver(broadcastReceiver, intentFilter)
    }

    /**
     * 暂停播放
     */
    private fun pause(fromCB: Boolean = false) {
        if (!canRunVideoCallbacks()) return
        try {
            pause = true
            upPlayProgressJob?.cancel()
            if (!fromCB) {
                playerView.onVideoPause()
            }
            VideoPlay.saveRead()
            upMediaSessionPlaybackState(PlaybackStateCompat.STATE_PAUSED)
            upVideoPlayNotification()
        } catch (e: Exception) {
            e.printOnDebug()
        }
    }

    /**
     * 恢复播放
     */
    @SuppressLint("WakelockTimeout")
    private fun resume(fromCB: Boolean = false) {
        if (!canRunVideoCallbacks()) return
        try {
            pause = false
            if (!fromCB) {
                playerView.onVideoResume()
            }
            upPlayProgress()
            upVideoPlayNotification()
        } catch (e: Exception) {
            e.printOnDebug()
            stop()
        }
    }

    /**
     * 每隔0.5秒发送播放进度
     */
    private fun upPlayProgress() {
        if (!canRunVideoCallbacks()) return
        upPlayProgressJob?.cancel()
        upPlayProgressJob = lifecycleScope.launch {
            var lastCheckpoint = SystemClock.elapsedRealtime()
            while (isActive && !handoffOut && canRunVideoCallbacks()) {
                upMediaSessionPlaybackState(PlaybackStateCompat.STATE_PLAYING)
                pause = false
                val now = SystemClock.elapsedRealtime()
                if (now - lastCheckpoint >= VideoPlay.PROGRESS_CHECKPOINT_INTERVAL_MS) {
                    VideoPlay.saveRead()
                    lastCheckpoint = now
                }
                delay(500)
            }
        }
    }

    private fun upMediaSessionPlaybackState(state: Int) {
        if (!canRunVideoCallbacks()) return
        mediaSessionCompat.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(MediaHelp.MEDIA_SESSION_ACTIONS)
                .setState(state, playerView.getCurrentPositionWhenPlaying(), 1f)
                .addCustomAction(
                    APP_ACTION_STOP,
                    getString(R.string.stop),
                    R.drawable.ic_stop_black_24dp
                )
                .build()
        )
    }

    private fun createNotification(): NotificationCompat.Builder {
        val videoTitle = VideoPlay.videoTitle ?: getString(R.string.video)
        val nTitle = getString(R.string.audio_play_t) + ": $videoTitle"
        val nSubtitle = getString(R.string.audio_play_s)
        val builder = NotificationCompat.Builder(this@VideoPlayService, AppConst.channelIdReadAloud)
            .setSmallIcon(R.drawable.ic_volume_up)
            .setSubText(getString(R.string.video))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentTitle(nTitle)
            .setContentText(nSubtitle)
            .setContentIntent(
                activityPendingIntent<VideoPlayerActivity>("activity")
            )
        builder.setLargeIcon(cover)
        builder.addAction(
            R.drawable.ic_skip_previous,
            getString(R.string.previous),
            servicePendingIntent<VideoPlayService>(IntentAction.prev)
        )
        if (pause) {
            builder.addAction(
                R.drawable.ic_play_24dp,
                getString(R.string.resume),
                servicePendingIntent<VideoPlayService>(IntentAction.resume)
            )
        } else {
            builder.addAction(
                R.drawable.ic_pause_24dp,
                getString(R.string.pause),
                servicePendingIntent<VideoPlayService>(IntentAction.pause)
            )
        }
        builder.addAction(
            R.drawable.ic_skip_next,
            getString(R.string.next),
            servicePendingIntent<VideoPlayService>(IntentAction.next)
        )
        builder.addAction(
            R.drawable.ic_stop_black_24dp,
            getString(R.string.stop),
            servicePendingIntent<VideoPlayService>(IntentAction.stop)
        )
        builder.setStyle(
            androidx.media.app.NotificationCompat.MediaStyle()
                .setShowActionsInCompactView(0, 1, 2)
                .setMediaSession(mediaSessionCompat.sessionToken)
        )
        builder.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        return builder
    }

    @OptIn(UnstableApi::class)
    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    private fun createFloatingWindow() {
        if (!canRunVideoCallbacks()) return
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val screenWidth = resources.displayMetrics.widthPixels
        val videoWidth = playerView.currentVideoWidth
        val videoHeight = playerView.currentVideoHeight
        val windowWidth = if (videoHeight > videoWidth * 1.2) {
            screenWidth / 2 //竖屏时为屏幕的1/2
        } else {
            screenWidth * 3 / 4 //默认为屏幕3/4宽
        }
        val windowHeight = if (videoWidth > 0 && videoHeight > 0) (windowWidth * videoHeight / videoWidth) else (windowWidth * 9 / 16) // 默认16:9比例
        // 设置窗口参数
        params = WindowManager.LayoutParams(
            windowWidth,
            windowHeight,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.START or Gravity.TOP
            x = 30
            y = screenWidth / 10
        }
        floatingView.setOnTouchListener(FloatingTouchListener())
        windowManager.addView(floatingView, params)

    }

    inner class FloatingTouchListener : OnTouchListener {
        private var initialTouchX = 0f
        private var initialTouchY = 0f
        private var initialX = 0
        private var initialY = 0
        private var isClick = true

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            if (!canRunVideoCallbacks()) return false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isClick = true
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    initialX = params.x
                    initialY = params.y
                    cancelAnimator()
                }

                MotionEvent.ACTION_MOVE -> {
                    val deltaX = event.rawX - initialTouchX
                    val deltaY = event.rawY - initialTouchY
                    if (abs(deltaX) > 20 || abs(deltaY) > 20) {
                        isClick = false
                        params.x = (initialX + (event.rawX - initialTouchX)).toInt()
                        params.y = (initialY + (event.rawY - initialTouchY)).toInt()
                        updateViewPosition()
                        cancelAnimator()
                    } else {
                        isClick = true
                    }
                }

                MotionEvent.ACTION_UP -> {
                    if (isClick) {
                        playerView.showControlUi()
                    } else {
                        startEdgeAnimation()
                    }

                }
            }
            return false
        }
    }


    private fun setupPlayerView() {
        playerView.fullscreenB.setOnClickListener {
            toggleFullScreen()
        }
        playerView.backButton.setOnClickListener { stop() }
        if (playerView.isInPlayingState) {
            upMediaMetadata()
            upPlayProgress()
        }
        playerView.setVideoAllCallBack(object : GSYSampleCallBack() {
            override fun onPrepared(url: String?, vararg objects: Any?) {
                if (!canRunVideoCallbacks()) return
                mediaNotificationReady = true
                upMediaMetadata()
                upPlayProgress()
                upVideoPlayNotification()
                //根据实际视频比例再次调整悬浮窗高度,来适配竖屏视频。如果是全屏切换过来的时候不会触发
                val videoWidth = playerView.currentVideoWidth
                val videoHeight = playerView.currentVideoHeight
                val screenWidth = resources.displayMetrics.widthPixels
                if (videoWidth > 0 && videoHeight > 0) {
                    val parentWidth = if (videoHeight > videoWidth * 1.2) {
                        VideoPlay.isPortraitVideo = true
                        screenWidth / 2 //竖屏时为屏幕的1/2
                    } else {
                        VideoPlay.isPortraitVideo = false
                        params.width
                    }
                    val aspectRatio = videoHeight.toFloat() / videoWidth.toFloat()
                    val height = (parentWidth * aspectRatio).toInt()
                    params.height = height
                    windowManager.updateViewLayout(floatingView, params)
                }
            }
            override fun onAutoComplete(url: String?, vararg objects: Any?) {
                if (!canRunVideoCallbacks()) return
                if (!VideoPlay.upDurIndex(1, playerView)) {
                    stop()
                }
            }
            override fun onClickStartIcon(url: String?, vararg objects: Any?) {
                resume(true)
            }
            override fun onClickResume(url: String?, vararg objects: Any?) {
                resume(true)
            }
            override fun onClickStop(url: String?, vararg objects: Any?) {
                pause(true)
            }
        })
    }

    private fun stop() {
        VideoPlay.saveRead()
        platformReturn.stopPendingHandoff()
        returnSessionFinished = true
        preparation?.cancel()
        stopSelf()
        pause = true
    }

    private fun toggleFullScreen() {
        if (handoffOut || !canRunVideoCallbacks()) return
        VideoPlay.savePlayState(playerView)
        VideoPlay.saveRead()
        val fullscreenIntent = (playbackIntent?.let(::Intent) ?: Intent()).setClass(this, VideoPlayerActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("isNew", false)
            putExtra("forceNormalPlayer", true)
        }
        if (runCatching { platformReturn.handoffTo(fullscreenIntent, ::startActivity) }
                .onFailure { AppLog.put("切换视频窗口失败", it, toast = true) }.isFailure) return
        returnSessionFinished = true
        handoffOut = true
        playerView.needDestroy = false
    }


    private fun upMediaMetadata() {
        if (!canRunVideoCallbacks()) return
        val metadata = MediaMetadataCompat.Builder()
            .putBitmap(MediaMetadataCompat.METADATA_KEY_ART, cover)
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, VideoPlay.videoTitle ?: "null")
            .putText(MediaMetadataCompat.METADATA_KEY_ARTIST, VideoPlay.book?.name ?: "视频播放")
            .putText(MediaMetadataCompat.METADATA_KEY_ALBUM, VideoPlay.book?.author ?: "null")
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, playerView.getDuration())
            .build()
        mediaSessionCompat.setMetadata(metadata)
    }

    private fun removeFloatingWindow() {
        if (::windowManager.isInitialized && floatingViewDelegate.isInitialized() && floatingView.parent != null) {
            windowManager.removeView(floatingView)
        }
    }

    override fun onRestoreRecoveryRequired() {
        acknowledgeRecoveryForegroundStart(NotificationId.VideoPlayService)
        preparation?.cancel()
        platformReturn.abandonForRecovery()
        returnSessionFinished = true
        pause = true
        mediaNotificationReady = false
        upPlayProgressJob?.cancel()
        upNotificationJob?.cancel()
        cancelAnimator()
        if (mediaInitialized) {
            if (floatingViewDelegate.isInitialized()) playerView.setVideoAllCallBack(null)
            VideoPlay.stopLoading()
            VideoPlay.releaseAllVideos()
            removeFloatingWindow()
            if (mediaSessionDelegate.isInitialized()) {
                mediaSessionCompat.setCallback(null)
                mediaSessionCompat.isActive = false
            }
        }
        super.onRestoreRecoveryRequired()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (activeService?.get() === this) activeService = null
        preparation?.cancel()
        pause = true
        mediaNotificationReady = false
        upPlayProgressJob?.cancel()
        upNotificationJob?.cancel()
        cancelAnimator()
        if (isRestoreRecoveryBlocked) stopForeground(STOP_FOREGROUND_REMOVE)
        notificationManager.cancel(NotificationId.VideoPlayService)
        platformReturn.retireIncomingHandoff()
        if (!mediaInitialized) return
        if (!handoffOut && !isRestoreRecoveryBlocked) {
            VideoPlay.saveRead()
            platformReturn.close()
            // A stopped receiver may never have created its own player view.
            VideoPlay.stopLoading()
            VideoPlay.releaseAllVideos()
        }
        try {
            if (floatingViewDelegate.isInitialized()) playerView.setVideoAllCallBack(null)
            removeFloatingWindow()
            if (mediaSessionDelegate.isInitialized()) {
                mediaSessionCompat.setCallback(null)
                mediaSessionCompat.release()
            }
            broadcastReceiver?.let { unregisterReceiver(it) }
            application.unregisterActivityLifecycleCallbacks(activityLifecycleCallbacks)
            if (floatingViewDelegate.isInitialized()) playerView.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

}
