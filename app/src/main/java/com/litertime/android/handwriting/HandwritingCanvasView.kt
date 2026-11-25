package com.litertime.android.handwriting

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.litertime.android.ui.PastelPalette
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Dark-themed 田字格 handwriting canvas aligned with `Ismantic/Handwritten`:
 * - Collects raw strokes with `Path.lineTo` + `MotionEvent.getHistoricalX/Y` (no quadTo smoothing).
 * - Renders `renderToBitmap()` on white background with hard binary black strokes (`isAntiAlias = false`,
 *   `Cap.BUTT`, `Join.ROUND`) scaled relative to `min(viewW, viewH)` so `360px` box height always
 *   gets ~`15.84px` stroke width matching CASIA HWDB1.1 training distribution.
 * - Supports animated alpha fade-out after a character is auto-committed.
 */
class HandwritingCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    interface StrokeListener {
        fun onStrokeStart()
        fun onStrokeEnd()
    }

    var strokeListener: StrokeListener? = null

    private val density = resources.displayMetrics.density
    private fun dp(value: Float): Float = value * density

    private val strokes = mutableListOf<Path>()
    private var currentPath: Path? = null

    private var showGrid: Boolean = false
    private var inkBaseColor: Int = Color.WHITE
    private var strokeAlpha: Float = 1f
    private var isFadingOut: Boolean = false
    private var fadeAnimator: ValueAnimator? = null

    private val cardRect = RectF()
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PastelPalette.SURFACE_PAD
        style = Paint.Style.FILL
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PastelPalette.GRID_LINE
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        pathEffect = DashPathEffect(floatArrayOf(dp(6f), dp(5f)), 0f)
    }
    private val displayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = dp(5f)
    }
    private val renderPaint = Paint().apply {
        color = Color.BLACK
        isAntiAlias = false
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
        strokeJoin = Paint.Join.ROUND
    }

    fun applyStyle(strokeWidthDp: Float, inkColorArgb: Int, showGridLines: Boolean) {
        displayPaint.strokeWidth = dp(strokeWidthDp)
        inkBaseColor = inkColorArgb
        displayPaint.color = applyAlpha(inkBaseColor, strokeAlpha)
        showGrid = showGridLines
        invalidate()
    }

    /** Re-applies the resolved palette after the keyboard theme changed. */
    fun refreshPalette() {
        bgPaint.color = PastelPalette.SURFACE_PAD
        gridPaint.color = PastelPalette.GRID_LINE
        invalidate()
    }

    fun hasStrokes(): Boolean = strokes.isNotEmpty()

    fun clearImmediate() {
        fadeAnimator?.cancel()
        fadeAnimator = null
        isFadingOut = false
        strokeAlpha = 1f
        displayPaint.color = applyAlpha(inkBaseColor, strokeAlpha)
        strokes.clear()
        currentPath = null
        invalidate()
    }

    fun startFadeOut(durationMs: Long = 250L, onFinished: () -> Unit) {
        fadeAnimator?.cancel()
        if (strokes.isEmpty()) {
            isFadingOut = false
            strokeAlpha = 1f
            onFinished()
            return
        }
        isFadingOut = true
        fadeAnimator = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = durationMs
            addUpdateListener { anim ->
                strokeAlpha = anim.animatedValue as Float
                displayPaint.color = applyAlpha(inkBaseColor, strokeAlpha)
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false

                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (!cancelled) {
                        isFadingOut = false
                        strokes.clear()
                        currentPath = null
                        strokeAlpha = 1f
                        displayPaint.color = applyAlpha(inkBaseColor, strokeAlpha)
                        invalidate()
                        onFinished()
                    }
                }
            })
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val radius = dp(14f)
        cardRect.set(0f, 0f, w, h)
        canvas.drawRoundRect(cardRect, radius, radius, bgPaint)

        if (showGrid) {
            val cx = w / 2f
            val cy = h / 2f
            canvas.drawLine(dp(6f), cy, w - dp(6f), cy, gridPaint)
            canvas.drawLine(cx, dp(6f), cx, h - dp(6f), gridPaint)
        }

        for (path in strokes) {
            canvas.drawPath(path, displayPaint)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                fadeAnimator?.cancel()
                fadeAnimator = null
                if (isFadingOut || strokeAlpha < 0.99f) {
                    // User interrupted fade-out with a new stroke: discard old fading strokes cleanly.
                    strokes.clear()
                }
                isFadingOut = false
                strokeAlpha = 1f
                displayPaint.color = applyAlpha(inkBaseColor, 1f)

                val p = Path().apply { moveTo(x, y) }
                currentPath = p
                strokes.add(p)
                strokeListener?.onStrokeStart()
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val p = currentPath ?: return true
                val historySize = event.historySize
                for (i in 0 until historySize) {
                    p.lineTo(event.getHistoricalX(i), event.getHistoricalY(i))
                }
                p.lineTo(x, y)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val finishedPath = currentPath
                finishedPath?.lineTo(x, y)
                currentPath = null
                invalidate()
                if (finishedPath != null) {
                    strokeListener?.onStrokeEnd()
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /**
     * Renders strokes to a white-background bitmap scaled so the 田字格 short dimension (`min(w, h)`)
     * maps to `360px` and the binary stroke width is `0.044 * 360 ≈ 15.84px`, matching `Ismantic/Handwritten`.
     */
    fun renderToBitmap(): Bitmap {
        val viewW = width.coerceAtLeast(1)
        val viewH = height.coerceAtLeast(1)
        val boxSize = min(viewW, viewH).coerceAtLeast(1)
        val scale = RENDER_TARGET_BOX_PX.toFloat() / boxSize.toFloat()
        val dstW = (viewW * scale).roundToInt().coerceAtLeast(1)
        val dstH = (viewH * scale).roundToInt().coerceAtLeast(1)

        renderPaint.strokeWidth = boxSize * RENDER_STROKE_RATIO
        val bmp = Bitmap.createBitmap(dstW, dstH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        canvas.scale(scale, scale)
        for (path in strokes) {
            canvas.drawPath(path, renderPaint)
        }
        return bmp
    }

    private fun applyAlpha(color: Int, alpha: Float): Int {
        val a = ((Color.alpha(color) * alpha).roundToInt()).coerceIn(0, 255)
        return (color and 0x00FFFFFF) or (a shl 24)
    }

    companion object {
        private const val RENDER_TARGET_BOX_PX = 360
        private const val RENDER_STROKE_RATIO = 0.044f
    }
}
