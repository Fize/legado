package io.legado.app.ui.book.manga.recyclerview

import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

class WebtoonFrame : FrameLayout {

    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet, defStyle: Int) : super(
        context,
        attrs,
        defStyle
    )

    private val scaleDetector = ScaleGestureDetector(context, ScaleListener())
    private val flingDetector = GestureDetector(context, FlingListener())
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private val curlHelper = MangaSimulationCurlHelper(this) { revertPx ->
        recycler?.scrollBy(revertPx, 0)
    }

    var enableSimulationCurl = false
        set(value) {
            field = value
            if (!value) {
                curlHelper.abortAnim()
            }
        }

    fun setPageBackgroundColor(color: Int) {
        curlHelper.pageBackgroundColor = color
        setBackgroundColor(color)
        recycler?.setBackgroundColor(color)
    }

    var lastTapY: Float = 0f
        private set

    private var curlDownX = 0f
    private var curlDownY = 0f
    private var curlLastX = 0f
    private var curlLastMoveDx = 0f
    private var isCurlIntercepted = false
    private var activeCurlPhysicalDir = 1

    var doubleTapZoom = true
        set(value) {
            field = value
            recycler?.doubleTapZoom = value
            scaleDetector.isQuickScaleEnabled = value
        }

    var disableMangaScale = false

    private val recycler: WebtoonRecyclerView?
        get() = getChildAt(0) as? WebtoonRecyclerView

    private val mcRect = RectF()
    private val blRect = RectF()
    private val brRect = RectF()

    private var mTouchMiddle: (() -> Unit)? = null
    fun onTouchMiddle(init: () -> Unit) = apply { this.mTouchMiddle = init }
    private var mNextPage: (() -> Unit)? = null
    fun onNextPage(init: () -> Unit) = apply { this.mNextPage = init }
    private var mPrevPage: (() -> Unit)? = null
    fun onPrevPage(init: () -> Unit) = apply { this.mPrevPage = init }

    var disabledClickScroll = false
    var rightToLeft = false

    fun startSimulationPageTurn(physicalDirection: Int, tapY: Float = lastTapY.takeIf { it > 0f } ?: (height * 0.78f)): Boolean {
        val rv = recycler ?: return false
        if (!enableSimulationCurl || rv.isZoomed) return false
        if (!rv.canScrollHorizontally(physicalDirection)) return false
        val stepPx = (rv.width - rv.paddingStart - rv.paddingEnd) * physicalDirection
        return curlHelper.startTapTurn(
            recyclerView = rv,
            physicalDir = physicalDirection,
            stepPx = stepPx,
            performScroll = { dx -> rv.scrollBy(dx, 0) },
            tapY = tapY,
        )
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        recycler?.tapListener = { ev ->
            val location = IntArray(2)
            getLocationOnScreen(location)
            val x = ev.rawX - location[0]
            val y = ev.rawY - location[1]
            lastTapY = y
            when {
                mcRect.contains(x, y) -> {
                    mTouchMiddle?.invoke()
                }

                blRect.contains(x, y) && !disabledClickScroll -> {
                    if (rightToLeft) mNextPage?.invoke() else mPrevPage?.invoke()
                }

                brRect.contains(x, y) && !disabledClickScroll -> {
                    if (rightToLeft) mPrevPage?.invoke() else mNextPage?.invoke()
                }
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        curlHelper.setViewSize(w, h)
        curlHelper.abortAnim()
        mcRect.set(width * 0.33f, height * 0.25f, width * 0.66f, height * 0.75f)
        blRect.set(0f, 0f, width * 0.33f, height.toFloat())
        brRect.set(width * 0.66f, 0f, width.toFloat(), height.toFloat())
    }

    override fun computeScroll() {
        super.computeScroll()
        curlHelper.computeScroll()
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (enableSimulationCurl && curlHelper.onDraw(canvas)) {
            return
        }
        super.dispatchDraw(canvas)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val rv = recycler
        if (enableSimulationCurl && rv != null && !rv.isZoomed) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    curlDownX = ev.x
                    curlDownY = ev.y
                    curlLastX = ev.x
                    curlLastMoveDx = 0f
                    isCurlIntercepted = false
                    lastTapY = ev.y
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (isCurlIntercepted) {
                        isCurlIntercepted = false
                        curlHelper.finishDragTurn(cancelTurn = true)
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isCurlIntercepted) {
                        curlLastMoveDx = ev.x - curlLastX
                        curlLastX = ev.x
                        curlHelper.updateDragTouch(ev.x, ev.y)
                        return true
                    } else if (ev.pointerCount == 1) {
                        val dx = ev.x - curlDownX
                        val dy = ev.y - curlDownY
                        if (abs(dx) > touchSlop && abs(dx) > abs(dy) * 1.15f) {
                            val physicalDir = if (dx < 0) 1 else -1
                            if (rv.canScrollHorizontally(physicalDir)) {
                                val cancelEv = MotionEvent.obtain(ev).apply {
                                    action = MotionEvent.ACTION_CANCEL
                                }
                                super.dispatchTouchEvent(cancelEv)
                                cancelEv.recycle()
                                val stepPx = (rv.width - rv.paddingStart - rv.paddingEnd) * physicalDir
                                if (curlHelper.beginDragTurn(
                                        recyclerView = rv,
                                        physicalDir = physicalDir,
                                        stepPx = stepPx,
                                        performScroll = { rv.scrollBy(it, 0) },
                                        startX = curlDownX,
                                        startY = curlDownY,
                                    )
                                ) {
                                    isCurlIntercepted = true
                                    activeCurlPhysicalDir = physicalDir
                                    curlLastX = ev.x
                                    curlLastMoveDx = dx
                                    curlHelper.updateDragTouch(ev.x, ev.y)
                                    return true
                                }
                            }
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isCurlIntercepted) {
                        isCurlIntercepted = false
                        val totalDx = ev.x - curlDownX
                        val cancelTurn = if (ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                            true
                        } else if (activeCurlPhysicalDir > 0) {
                            totalDx > -width * 0.05f && curlLastMoveDx > 4f
                        } else {
                            totalDx < width * 0.05f && curlLastMoveDx < -4f
                        }
                        curlHelper.finishDragTurn(cancelTurn = cancelTurn)
                        return true
                    }
                }
            }
        }
        if (!disableMangaScale) {
            scaleDetector.onTouchEvent(ev)
            flingDetector.onTouchEvent(ev)
            val recyclerRect = Rect()
            recycler?.getHitRect(recyclerRect) ?: return super.dispatchTouchEvent(ev)
            recyclerRect.inset(1, 1)

            if (recyclerRect.right < recyclerRect.left || recyclerRect.bottom < recyclerRect.top) {
                return super.dispatchTouchEvent(ev)
            }

            ev.setLocation(
                ev.x.coerceIn(recyclerRect.left.toFloat(), recyclerRect.right.toFloat()),
                ev.y.coerceIn(recyclerRect.top.toFloat(), recyclerRect.bottom.toFloat()),
            )
        }
        return super.dispatchTouchEvent(ev)
    }

    inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            recycler?.onScaleBegin()
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            recycler?.onScale(detector.scaleFactor)
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            recycler?.onScaleEnd()
        }
    }

    inner class FlingListener : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            return true
        }

        override fun onFling(
            e1: MotionEvent?,
            e2: MotionEvent,
            velocityX: Float,
            velocityY: Float,
        ): Boolean {
            return recycler?.zoomFling(velocityX.toInt(), velocityY.toInt()) ?: false
        }
    }
}
