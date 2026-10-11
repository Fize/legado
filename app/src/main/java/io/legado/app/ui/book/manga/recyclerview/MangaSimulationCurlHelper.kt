package io.legado.app.ui.book.manga.recyclerview

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Region
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.View
import android.widget.Scroller
import androidx.core.graphics.createBitmap
import androidx.interpolator.view.animation.LinearOutSlowInInterpolator
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * 漫画横屏模式下的仿真卷页（Bezier Page Curl）渲染与动画助手：
 * 提炼自电子书 [io.legado.app.ui.book.read.page.delegate.SimulationPageDelegate] 的双二次贝塞尔曲线卷页算法，
 * 支持手势跟手拖拽翻书与点击平滑掀页动画。
 */
@Suppress("DEPRECATION")
class MangaSimulationCurlHelper(
    private val hostView: View,
    private val onCurlCancelRevertScroll: (Int) -> Unit,
) {

    var isActive: Boolean = false
        private set
    var isDragging: Boolean = false
        private set

    private var viewWidth: Int = 1
    private var viewHeight: Int = 1
    private var mMaxLength: Float = 1f

    private var physicalDirection: Int = 1
    private var stepPxScrolled: Int = 0
    private var isCancel: Boolean = false

    private var touchX: Float = 0.1f
    private var touchY: Float = 0.1f
    private var mTouchX: Float = 0.1f
    private var mTouchY: Float = 0.1f

    private var mCornerX: Int = 1
    private var mCornerY: Int = 1
    private var mIsRtOrLb: Boolean = false

    private val mPath0 = Path()
    private val mPath1 = Path()

    private val mBezierStart1 = PointF()
    private val mBezierControl1 = PointF()
    private val mBezierVertex1 = PointF()
    private var mBezierEnd1 = PointF()

    private val mBezierStart2 = PointF()
    private val mBezierControl2 = PointF()
    private val mBezierVertex2 = PointF()
    private var mBezierEnd2 = PointF()

    private var mMiddleX = 0f
    private var mMiddleY = 0f
    private var mDegrees = 0f
    private var mTouchToCornerDis = 0f

    // 模拟漫画纸张背面的半透微印质感
    private val mBackPageColorFilter = ColorMatrixColorFilter(
        ColorMatrix(
            floatArrayOf(
                0.42f, 0.24f, 0.24f, 0f, 118f,
                0.24f, 0.42f, 0.24f, 0f, 116f,
                0.24f, 0.24f, 0.42f, 0f, 112f,
                0f, 0f, 0f, 1f, 0f,
            )
        )
    )
    private val mMatrix = Matrix()
    private val mMatrixArray = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 1f)
    private val mPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val mFolderShadowDrawableRL: GradientDrawable
    private val mFolderShadowDrawableLR: GradientDrawable
    private val mBackShadowDrawableRL: GradientDrawable
    private val mBackShadowDrawableLR: GradientDrawable
    private val mFrontShadowDrawableHBT: GradientDrawable
    private val mFrontShadowDrawableHTB: GradientDrawable
    private val mFrontShadowDrawableVLR: GradientDrawable
    private val mFrontShadowDrawableVRL: GradientDrawable

    private var beforeBitmap: Bitmap? = null
    private var afterBitmap: Bitmap? = null
    private val offscreenCanvas = Canvas()

    private val scroller = Scroller(hostView.context, LinearOutSlowInInterpolator())

    init {
        val folderColors = intArrayOf(0x333333, -0x4fcccccd)
        mFolderShadowDrawableRL = GradientDrawable(GradientDrawable.Orientation.RIGHT_LEFT, folderColors).apply {
            gradientType = GradientDrawable.LINEAR_GRADIENT
        }
        mFolderShadowDrawableLR = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, folderColors).apply {
            gradientType = GradientDrawable.LINEAR_GRADIENT
        }

        val backShadowColors = intArrayOf(-0xeeeeef, 0x111111)
        mBackShadowDrawableRL = GradientDrawable(GradientDrawable.Orientation.RIGHT_LEFT, backShadowColors).apply {
            gradientType = GradientDrawable.LINEAR_GRADIENT
        }
        mBackShadowDrawableLR = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, backShadowColors).apply {
            gradientType = GradientDrawable.LINEAR_GRADIENT
        }

        val frontShadowColors = intArrayOf(-0x7feeeeef, 0x111111)
        mFrontShadowDrawableVLR = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, frontShadowColors).apply {
            gradientType = GradientDrawable.LINEAR_GRADIENT
        }
        mFrontShadowDrawableVRL = GradientDrawable(GradientDrawable.Orientation.RIGHT_LEFT, frontShadowColors).apply {
            gradientType = GradientDrawable.LINEAR_GRADIENT
        }
        mFrontShadowDrawableHTB = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, frontShadowColors).apply {
            gradientType = GradientDrawable.LINEAR_GRADIENT
        }
        mFrontShadowDrawableHBT = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, frontShadowColors).apply {
            gradientType = GradientDrawable.LINEAR_GRADIENT
        }
    }

    var pageBackgroundColor: Int = Color.WHITE

    fun setViewSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        viewWidth = width
        viewHeight = height
        mMaxLength = hypot(viewWidth.toDouble(), viewHeight.toDouble()).toFloat()
    }

    private fun captureView(targetView: View, reuse: Bitmap?): Bitmap? {
        if (viewWidth <= 0 || viewHeight <= 0) return null
        val bmp = if (reuse != null && !reuse.isRecycled &&
            reuse.width == viewWidth && reuse.height == viewHeight
        ) {
            reuse
        } else {
            reuse?.recycle()
            createBitmap(viewWidth, viewHeight)
        }
        offscreenCanvas.setBitmap(bmp)
        offscreenCanvas.drawColor(pageBackgroundColor)
        targetView.draw(offscreenCanvas)
        offscreenCanvas.setBitmap(null)
        return bmp
    }

    fun abortAnim() {
        if (!scroller.isFinished) {
            scroller.abortAnimation()
        }
        if (isActive && isCancel && stepPxScrolled != 0) {
            val revert = -stepPxScrolled
            stepPxScrolled = 0
            onCurlCancelRevertScroll(revert)
        }
        isActive = false
        isDragging = false
        isCancel = false
        stepPxScrolled = 0
        hostView.invalidate()
    }

    /**
     * 启动点击/按键触发的整页仿真翻书动画
     */
    fun startTapTurn(
        recyclerView: View,
        physicalDir: Int,
        stepPx: Int,
        performScroll: (Int) -> Unit,
        tapY: Float = viewHeight * 0.78f,
        durationMs: Int = 420,
    ): Boolean {
        if (viewWidth <= 0 || viewHeight <= 0 || stepPx == 0) return false
        abortAnim()
        physicalDirection = if (physicalDir >= 0) 1 else -1
        beforeBitmap = captureView(recyclerView, beforeBitmap)
        performScroll(stepPx)
        afterBitmap = captureView(recyclerView, afterBitmap)
        stepPxScrolled = stepPx
        isCancel = false
        isDragging = false
        isActive = true

        if (physicalDirection > 0) {
            val fromTopCorner = tapY < viewHeight * 0.35f
            mCornerX = viewWidth
            mCornerY = if (fromTopCorner) 0 else viewHeight
            mIsRtOrLb = (mCornerX == 0 && mCornerY == viewHeight) || (mCornerY == 0 && mCornerX == viewWidth)
            val startX = viewWidth * 0.88f
            val startY = if (fromTopCorner) viewHeight * 0.16f else viewHeight * 0.84f
            val endX = -viewWidth * 0.85f
            val endY = if (fromTopCorner) 4f else viewHeight - 4f
            touchX = startX
            touchY = startY
            scroller.startScroll(
                startX.toInt(),
                startY.toInt(),
                (endX - startX).toInt(),
                (endY - startY).toInt(),
                durationMs,
            )
        } else {
            mCornerX = viewWidth
            mCornerY = viewHeight
            mIsRtOrLb = false
            val startX = viewWidth * 0.12f
            val startY = viewHeight * 0.84f
            val endX = viewWidth - 4f
            val endY = viewHeight - 4f
            touchX = startX
            touchY = startY
            scroller.startScroll(
                startX.toInt(),
                startY.toInt(),
                (endX - startX).toInt(),
                (endY - startY).toInt(),
                durationMs,
            )
        }
        hostView.invalidate()
        return true
    }

    /**
     * 启动手指横向拖拽触发的跟手仿真卷页
     */
    fun beginDragTurn(
        recyclerView: View,
        physicalDir: Int,
        stepPx: Int,
        performScroll: (Int) -> Unit,
        startX: Float,
        startY: Float,
    ): Boolean {
        if (viewWidth <= 0 || viewHeight <= 0 || stepPx == 0) return false
        abortAnim()
        physicalDirection = if (physicalDir >= 0) 1 else -1
        beforeBitmap = captureView(recyclerView, beforeBitmap)
        performScroll(stepPx)
        afterBitmap = captureView(recyclerView, afterBitmap)
        stepPxScrolled = stepPx
        isCancel = false
        isDragging = true
        isActive = true

        mCornerX = viewWidth
        mCornerY = if (physicalDirection > 0 && startY < viewHeight * 0.35f) 0 else viewHeight
        mIsRtOrLb = (mCornerX == 0 && mCornerY == viewHeight) || (mCornerY == 0 && mCornerX == viewWidth)
        updateDragTouch(startX, startY)
        return true
    }

    fun updateDragTouch(x: Float, y: Float) {
        if (!isActive || !isDragging) return
        touchX = x.coerceIn(4f, viewWidth - 4f)
        val rawY = if (physicalDirection < 0 || (y > viewHeight * 0.3f && y < viewHeight * 0.75f)) {
            if (mCornerY == 0) viewHeight * 0.16f else viewHeight * 0.84f
        } else {
            y
        }
        touchY = if (mCornerY == 0) {
            rawY.coerceIn(6f, viewHeight - 16f)
        } else {
            rawY.coerceIn(16f, viewHeight - 6f)
        }
        hostView.invalidate()
    }

    fun finishDragTurn(cancelTurn: Boolean, durationMs: Int = 340) {
        if (!isActive || !isDragging) return
        isDragging = false
        isCancel = cancelTurn
        val targetX: Float
        val targetY: Float = if (mCornerY == 0) 4f else viewHeight - 4f
        if (physicalDirection > 0) {
            targetX = if (cancelTurn) viewWidth - 4f else -viewWidth * 0.85f
        } else {
            targetX = if (cancelTurn) -viewWidth * 0.85f else viewWidth - 4f
        }
        scroller.startScroll(
            touchX.toInt(),
            touchY.toInt(),
            (targetX - touchX).toInt(),
            (targetY - touchY).toInt(),
            durationMs,
        )
        hostView.invalidate()
    }

    fun computeScroll() {
        if (!isActive || isDragging) return
        if (scroller.computeScrollOffset()) {
            touchX = scroller.currX.toFloat()
            val targetY = scroller.currY.toFloat()
            touchY = if (mCornerY == 0) {
                targetY.coerceAtLeast(3f)
            } else {
                targetY.coerceAtMost(viewHeight - 3f)
            }
            hostView.invalidate()
        } else {
            val needRevert = isCancel && stepPxScrolled != 0
            val revertPx = -stepPxScrolled
            isActive = false
            isCancel = false
            stepPxScrolled = 0
            if (needRevert) {
                onCurlCancelRevertScroll(revertPx)
            }
            hostView.invalidate()
        }
    }

    fun onDraw(canvas: Canvas): Boolean {
        if (!isActive) return false
        val topPage = if (physicalDirection > 0) beforeBitmap else afterBitmap
        val underPage = if (physicalDirection > 0) afterBitmap else beforeBitmap
        if (topPage == null || underPage == null) return false

        calcPoints()
        drawCurrentPageArea(canvas, topPage)
        drawNextPageAreaAndShadow(canvas, underPage)
        drawCurrentPageShadow(canvas)
        drawCurrentBackArea(canvas, topPage)
        return true
    }

    private fun drawCurrentPageArea(canvas: Canvas, bitmap: Bitmap) {
        mPath0.reset()
        mPath0.moveTo(mBezierStart1.x, mBezierStart1.y)
        mPath0.quadTo(mBezierControl1.x, mBezierControl1.y, mBezierEnd1.x, mBezierEnd1.y)
        mPath0.lineTo(mTouchX, mTouchY)
        mPath0.lineTo(mBezierEnd2.x, mBezierEnd2.y)
        mPath0.quadTo(mBezierControl2.x, mBezierControl2.y, mBezierStart2.x, mBezierStart2.y)
        mPath0.lineTo(mCornerX.toFloat(), mCornerY.toFloat())
        mPath0.close()

        canvas.save()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            canvas.clipOutPath(mPath0)
        } else {
            canvas.clipPath(mPath0, Region.Op.XOR)
        }
        canvas.drawBitmap(bitmap, 0f, 0f, null)
        canvas.restore()
    }

    private fun drawNextPageAreaAndShadow(canvas: Canvas, bitmap: Bitmap) {
        mPath1.reset()
        mPath1.moveTo(mBezierStart1.x, mBezierStart1.y)
        mPath1.lineTo(mBezierVertex1.x, mBezierVertex1.y)
        mPath1.lineTo(mBezierVertex2.x, mBezierVertex2.y)
        mPath1.lineTo(mBezierStart2.x, mBezierStart2.y)
        mPath1.lineTo(mCornerX.toFloat(), mCornerY.toFloat())
        mPath1.close()
        mDegrees = Math.toDegrees(
            atan2(
                (mBezierControl1.x - mCornerX).toDouble(),
                mBezierControl2.y - mCornerY.toDouble(),
            )
        ).toFloat()
        val leftX: Int
        val rightX: Int
        val backShadowDrawable: GradientDrawable
        if (mIsRtOrLb) {
            leftX = mBezierStart1.x.toInt()
            rightX = (mBezierStart1.x + mTouchToCornerDis / 4).toInt()
            backShadowDrawable = mBackShadowDrawableLR
        } else {
            leftX = (mBezierStart1.x - mTouchToCornerDis / 4).toInt()
            rightX = mBezierStart1.x.toInt()
            backShadowDrawable = mBackShadowDrawableRL
        }
        canvas.save()
        canvas.clipPath(mPath0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            canvas.clipPath(mPath1)
        } else {
            canvas.clipPath(mPath1, Region.Op.INTERSECT)
        }
        canvas.drawBitmap(bitmap, 0f, 0f, null)
        canvas.rotate(mDegrees, mBezierStart1.x, mBezierStart1.y)
        backShadowDrawable.setBounds(
            leftX,
            mBezierStart1.y.toInt(),
            rightX,
            (mMaxLength + mBezierStart1.y).toInt(),
        )
        backShadowDrawable.draw(canvas)
        canvas.restore()
    }

    private fun drawCurrentPageShadow(canvas: Canvas) {
        val degree: Double = if (mIsRtOrLb) {
            Math.PI / 4 - atan2(mBezierControl1.y - mTouchY, mTouchX - mBezierControl1.x)
        } else {
            Math.PI / 4 - atan2(mTouchY - mBezierControl1.y, mTouchX - mBezierControl1.x)
        }
        val d1 = 25f * 1.414 * cos(degree)
        val d2 = 25f * 1.414 * sin(degree)
        val x = (mTouchX + d1).toFloat()
        val y = if (mIsRtOrLb) (mTouchY + d2).toFloat() else (mTouchY - d2).toFloat()

        mPath1.reset()
        mPath1.moveTo(x, y)
        mPath1.lineTo(mTouchX, mTouchY)
        mPath1.lineTo(mBezierControl1.x, mBezierControl1.y)
        mPath1.lineTo(mBezierStart1.x, mBezierStart1.y)
        mPath1.close()
        canvas.save()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            canvas.clipOutPath(mPath0)
        } else {
            canvas.clipPath(mPath0, Region.Op.XOR)
        }
        canvas.clipPath(mPath1)

        var leftX: Int
        var rightX: Int
        var currentPageShadow: GradientDrawable
        if (mIsRtOrLb) {
            leftX = mBezierControl1.x.toInt()
            rightX = (mBezierControl1.x + 25).toInt()
            currentPageShadow = mFrontShadowDrawableVLR
        } else {
            leftX = (mBezierControl1.x - 25).toInt()
            rightX = (mBezierControl1.x + 1).toInt()
            currentPageShadow = mFrontShadowDrawableVRL
        }
        var rotateDegrees = Math.toDegrees(
            atan2(mTouchX - mBezierControl1.x, mBezierControl1.y - mTouchY).toDouble()
        ).toFloat()
        canvas.rotate(rotateDegrees, mBezierControl1.x, mBezierControl1.y)
        currentPageShadow.setBounds(
            leftX,
            (mBezierControl1.y - mMaxLength).toInt(),
            rightX,
            mBezierControl1.y.toInt(),
        )
        currentPageShadow.draw(canvas)
        canvas.restore()

        mPath1.reset()
        mPath1.moveTo(x, y)
        mPath1.lineTo(mTouchX, mTouchY)
        mPath1.lineTo(mBezierControl2.x, mBezierControl2.y)
        mPath1.lineTo(mBezierStart2.x, mBezierStart2.y)
        mPath1.close()
        canvas.save()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            canvas.clipOutPath(mPath0)
        } else {
            canvas.clipPath(mPath0, Region.Op.XOR)
        }
        canvas.clipPath(mPath1)

        if (mIsRtOrLb) {
            leftX = mBezierControl2.y.toInt()
            rightX = (mBezierControl2.y + 25).toInt()
            currentPageShadow = mFrontShadowDrawableHTB
        } else {
            leftX = (mBezierControl2.y - 25).toInt()
            rightX = (mBezierControl2.y + 1).toInt()
            currentPageShadow = mFrontShadowDrawableHBT
        }
        rotateDegrees = Math.toDegrees(
            atan2(mBezierControl2.y - mTouchY, mBezierControl2.x - mTouchX).toDouble()
        ).toFloat()
        canvas.rotate(rotateDegrees, mBezierControl2.x, mBezierControl2.y)
        val temp = if (mBezierControl2.y < 0) {
            (mBezierControl2.y - viewHeight).toDouble()
        } else {
            mBezierControl2.y.toDouble()
        }
        val hmg = hypot(mBezierControl2.x.toDouble(), temp)
        if (hmg > mMaxLength) {
            currentPageShadow.setBounds(
                (mBezierControl2.x - 25 - hmg).toInt(),
                leftX,
                (mBezierControl2.x + mMaxLength - hmg).toInt(),
                rightX,
            )
        } else {
            currentPageShadow.setBounds(
                (mBezierControl2.x - mMaxLength).toInt(),
                leftX,
                mBezierControl2.x.toInt(),
                rightX,
            )
        }
        currentPageShadow.draw(canvas)
        canvas.restore()
    }

    private fun drawCurrentBackArea(canvas: Canvas, bitmap: Bitmap) {
        val i = ((mBezierStart1.x + mBezierControl1.x) / 2).toInt()
        val f1 = abs(i - mBezierControl1.x)
        val i1 = ((mBezierStart2.y + mBezierControl2.y) / 2).toInt()
        val f2 = abs(i1 - mBezierControl2.y)
        val f3 = min(f1, f2)
        mPath1.reset()
        mPath1.moveTo(mBezierVertex2.x, mBezierVertex2.y)
        mPath1.lineTo(mBezierVertex1.x, mBezierVertex1.y)
        mPath1.lineTo(mBezierEnd1.x, mBezierEnd1.y)
        mPath1.lineTo(mTouchX, mTouchY)
        mPath1.lineTo(mBezierEnd2.x, mBezierEnd2.y)
        mPath1.close()

        val folderShadowDrawable: GradientDrawable
        val left: Int
        val right: Int
        if (mIsRtOrLb) {
            left = (mBezierStart1.x - 1).toInt()
            right = (mBezierStart1.x + f3 + 1).toInt()
            folderShadowDrawable = mFolderShadowDrawableLR
        } else {
            left = (mBezierStart1.x - f3 - 1).toInt()
            right = (mBezierStart1.x + 1).toInt()
            folderShadowDrawable = mFolderShadowDrawableRL
        }
        canvas.save()
        canvas.clipPath(mPath0)
        canvas.clipPath(mPath1)

        mPaint.colorFilter = mBackPageColorFilter
        val dis = hypot(
            mCornerX - mBezierControl1.x.toDouble(),
            mBezierControl2.y - mCornerY.toDouble(),
        ).toFloat().coerceAtLeast(0.1f)
        val f8 = (mCornerX - mBezierControl1.x) / dis
        val f9 = (mBezierControl2.y - mCornerY) / dis
        mMatrixArray[0] = 1 - 2 * f9 * f9
        mMatrixArray[1] = 2 * f8 * f9
        mMatrixArray[3] = mMatrixArray[1]
        mMatrixArray[4] = 1 - 2 * f8 * f8
        mMatrix.reset()
        mMatrix.setValues(mMatrixArray)
        mMatrix.preTranslate(-mBezierControl1.x, -mBezierControl1.y)
        mMatrix.postTranslate(mBezierControl1.x, mBezierControl1.y)
        canvas.drawColor(0xFFECEAE4.toInt())
        canvas.drawBitmap(bitmap, mMatrix, mPaint)
        mPaint.colorFilter = null

        canvas.rotate(mDegrees, mBezierStart1.x, mBezierStart1.y)
        folderShadowDrawable.setBounds(
            left,
            mBezierStart1.y.toInt(),
            right,
            (mBezierStart1.y + mMaxLength).toInt(),
        )
        folderShadowDrawable.draw(canvas)
        canvas.restore()
    }

    private fun calcPoints() {
        mTouchX = touchX
        mTouchY = touchY

        mMiddleX = (mTouchX + mCornerX) / 2
        mMiddleY = (mTouchY + mCornerY) / 2
        val dxCorner = (mCornerX - mMiddleX).let { if (abs(it) < 0.1f) 0.1f else it }
        mBezierControl1.x = mMiddleX - (mCornerY - mMiddleY) * (mCornerY - mMiddleY) / dxCorner
        mBezierControl1.y = mCornerY.toFloat()
        mBezierControl2.x = mCornerX.toFloat()

        val f4 = mCornerY - mMiddleY
        mBezierControl2.y = if (abs(f4) < 0.1f) {
            mMiddleY - (mCornerX - mMiddleX) * (mCornerX - mMiddleX) / 0.1f
        } else {
            mMiddleY - (mCornerX - mMiddleX) * (mCornerX - mMiddleX) / f4
        }
        mBezierStart1.x = mBezierControl1.x - (mCornerX - mBezierControl1.x) / 2
        mBezierStart1.y = mCornerY.toFloat()

        if (mTouchX > 0 && mTouchX < viewWidth) {
            if (mBezierStart1.x < 0 || mBezierStart1.x > viewWidth) {
                if (mBezierStart1.x < 0) {
                    mBezierStart1.x = viewWidth - mBezierStart1.x
                }
                val f1 = abs(mCornerX - mTouchX).coerceAtLeast(0.1f)
                val f2 = viewWidth * f1 / mBezierStart1.x.coerceAtLeast(0.1f)
                mTouchX = abs(mCornerX - f2)

                val f3 = abs(mCornerX - mTouchX) * abs(mCornerY - mTouchY) / f1
                mTouchY = abs(mCornerY - f3)

                mMiddleX = (mTouchX + mCornerX) / 2
                mMiddleY = (mTouchY + mCornerY) / 2

                val dxCorner2 = (mCornerX - mMiddleX).let { if (abs(it) < 0.1f) 0.1f else it }
                mBezierControl1.x = mMiddleX - (mCornerY - mMiddleY) * (mCornerY - mMiddleY) / dxCorner2
                mBezierControl1.y = mCornerY.toFloat()
                mBezierControl2.x = mCornerX.toFloat()

                val f5 = mCornerY - mMiddleY
                mBezierControl2.y = if (abs(f5) < 0.1f) {
                    mMiddleY - (mCornerX - mMiddleX) * (mCornerX - mMiddleX) / 0.1f
                } else {
                    mMiddleY - (mCornerX - mMiddleX) * (mCornerX - mMiddleX) / f5
                }
                mBezierStart1.x = mBezierControl1.x - (mCornerX - mBezierControl1.x) / 2
            }
        }
        mBezierStart2.x = mCornerX.toFloat()
        mBezierStart2.y = mBezierControl2.y - (mCornerY - mBezierControl2.y) / 2

        mTouchToCornerDis = hypot(
            (mTouchX - mCornerX).toDouble(),
            (mTouchY - mCornerY).toDouble(),
        ).toFloat()

        mBezierEnd1 = getCross(PointF(mTouchX, mTouchY), mBezierControl1, mBezierStart1, mBezierStart2)
        mBezierEnd2 = getCross(PointF(mTouchX, mTouchY), mBezierControl2, mBezierStart1, mBezierStart2)

        mBezierVertex1.x = (mBezierStart1.x + 2 * mBezierControl1.x + mBezierEnd1.x) / 4
        mBezierVertex1.y = (2 * mBezierControl1.y + mBezierStart1.y + mBezierEnd1.y) / 4
        mBezierVertex2.x = (mBezierStart2.x + 2 * mBezierControl2.x + mBezierEnd2.x) / 4
        mBezierVertex2.y = (2 * mBezierControl2.y + mBezierStart2.y + mBezierEnd2.y) / 4
    }

    private fun getCross(P1: PointF, P2: PointF, P3: PointF, P4: PointF): PointF {
        val crossP = PointF()
        val a1 = (P2.y - P1.y) / (P2.x - P1.x)
        val b1 = (P1.x * P2.y - P2.x * P1.y) / (P1.x - P2.x)
        val a2 = (P4.y - P3.y) / (P4.x - P3.x)
        val b2 = (P3.x * P4.y - P4.x * P3.y) / (P3.x - P4.x)
        crossP.x = (b2 - b1) / (a1 - a2)
        crossP.y = a1 * crossP.x + b1
        return crossP
    }
}
