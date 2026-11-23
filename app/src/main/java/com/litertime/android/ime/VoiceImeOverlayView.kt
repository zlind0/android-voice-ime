package com.litertime.android.ime

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * Transparent-top IME overlay view containing:
 * - Bottom 1/3 voice input bar: [Switch Keyboard] [Whisper Mic] [Gemma 4 Mic] [Backspace]
 * - Expandable radial semicircle disc above the pressed mic button with draggable circular knob
 *
 * The upper area remains fully transparent so the host app shows through; `LiteRtImeService`
 * adjusts `onComputeInsets().touchableRegion` dynamically via [isRadialExpanded],
 * [getIdleTouchableTop], and [getIdleTouchableBottom], while respecting system bottom safe insets.
 */
class VoiceImeOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    interface Listener {
        fun onSwitchKeyboardClick()
        fun onSwitchKeyboardLongClick()
        fun onBackspaceClick()
        fun onStartRecording(source: MicSource): Boolean
        fun onFinishRecording(source: MicSource, action: RadialAction)
        fun onCancelActiveGeneration()
        fun onTouchableRegionChanged()
    }

    var listener: Listener? = null

    var isRadialExpanded: Boolean = false
        private set

    private val density = resources.displayMetrics.density
    private fun dp(value: Float): Float = value * density
    private fun sp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    private val barHeightPx = dp(80f)
    private val statusStripHeightPx = dp(32f)
    private val baseWindowHeightPx = dp(268f).toInt()
    private val minBottomMarginPx = dp(12f).toInt()
    private val radialRadiusPx = dp(132f)
    private val knobRadiusPx = dp(28f)
    private val sideButtonSizePx = dp(46f)
    private val barPaddingHPx = dp(14f)
    private val barInnerGapPx = dp(10f)

    private var safeBottomInsetPx: Int = 0
    private var safeLeftInsetPx: Int = 0
    private var safeRightInsetPx: Int = 0

    // Loaded state indicators
    private var loadedEngineType: String = "NONE"
    private var loadedModelId: String = ""
    private var onlineModeEnabled: Boolean = false

    // Generation status banner
    private var statusMessage: String = ""
    private var showCancelBanner: Boolean = false

    // Radial gesture state
    private var activeSource: MicSource? = null
    private var anchorX: Float = 0f
    private var anchorY: Float = 0f
    private var pointerX: Float = 0f
    private var pointerY: Float = 0f
    private var currentAction: RadialAction = RadialAction.NONE
    private var expandProgress: Float = 0f
    private var voiceAmplitude: Float = 0f

    private var expandAnimator: ValueAnimator? = null

    // Hit rectangles computed in onLayout / onSizeChanged
    private val switchBtnRect = RectF()
    private val whisperBtnRect = RectF()
    private val gemmaBtnRect = RectF()
    private val backspaceBtnRect = RectF()
    private val barCardRect = RectF()
    private val cancelBannerRect = RectF()
    private val cancelBannerButtonRect = RectF()
    private val scratchRect = RectF()

    // Touch target tracking when not in radial mode
    private enum class IdleTouchTarget {
        NONE,
        SWITCH,
        BACKSPACE,
        CANCEL_BANNER,
    }

    private var idleTouchTarget: IdleTouchTarget = IdleTouchTarget.NONE

    private val backspaceRepeatDelayMs = 400L
    private val backspaceRepeatIntervalMs = 65L
    private val backspaceRepeatRunnable = object : Runnable {
        override fun run() {
            if (idleTouchTarget == IdleTouchTarget.BACKSPACE) {
                listener?.onBackspaceClick()
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                postDelayed(this, backspaceRepeatIntervalMs)
            }
        }
    }

    // Paints
    private val barCardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xF51A1D24.toInt()
        style = Paint.Style.FILL
    }
    private val barBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x33FFFFFF
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val sideBtnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF282D38.toInt()
        style = Paint.Style.FILL
    }
    private val sideBtnPressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF394152.toInt()
        style = Paint.Style.FILL
    }
    private val whisperBtnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF23394D.toInt()
        style = Paint.Style.FILL
    }
    private val gemmaBtnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF2E284D.toInt()
        style = Paint.Style.FILL
    }
    private val activeBtnStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF60A5FA.toInt()
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
    }
    private val gemmaStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFA78BFA.toInt()
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
    }
    private val loadedDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF34D399.toInt()
        style = Paint.Style.FILL
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = sp(14f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.LEFT
    }
    private val subLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xB8FFFFFF.toInt()
        textSize = sp(10.5f)
        textAlign = Paint.Align.LEFT
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val iconFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val discBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xEC161922.toInt()
        style = Paint.Style.FILL
    }
    private val discBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x4DFFFFFF
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
    }
    private val sectorHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x5560A5FA
        style = Paint.Style.FILL
    }
    private val sectorCancelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66EF4444
        style = Paint.Style.FILL
    }
    private val centerHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x3A38BDF8
        style = Paint.Style.FILL
    }
    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x30FFFFFF
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val sectorTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = sp(11.5f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF3B82F6.toInt()
        style = Paint.Style.FILL
    }
    private val knobCancelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFEF4444.toInt()
        style = Paint.Style.FILL
    }
    private val knobGemmaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF8B5CF6.toInt()
        style = Paint.Style.FILL
    }
    private val amplitudeRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x6693C5FD
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
    }
    private val pillBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xE6111827.toInt()
        style = Paint.Style.FILL
    }
    private val pillTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = sp(12.5f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    init {
        setWillNotDraw(false)
        isClickable = true
    }

    fun updateSystemInsets(bottomPx: Int, leftPx: Int, rightPx: Int) {
        if (safeBottomInsetPx == bottomPx && safeLeftInsetPx == leftPx && safeRightInsetPx == rightPx) {
            return
        }
        safeBottomInsetPx = bottomPx.coerceAtLeast(0)
        safeLeftInsetPx = leftPx.coerceAtLeast(0)
        safeRightInsetPx = rightPx.coerceAtLeast(0)
        if (width > 0 && height > 0) {
            computeRects(width.toFloat(), (baseWindowHeightPx + safeBottomInsetPx).toFloat())
        }
        requestLayout()
        invalidate()
        listener?.onTouchableRegionChanged()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(width, baseWindowHeightPx + safeBottomInsetPx)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        computeRects(w.toFloat(), h.toFloat())
    }

    private fun computeRects(w: Float, h: Float) {
        val contentBottom = (h - safeBottomInsetPx - minBottomMarginPx).coerceAtLeast(barHeightPx)
        val barTop = contentBottom - barHeightPx
        val cardMarginLeft = dp(8f) + safeLeftInsetPx
        val cardMarginRight = dp(8f) + safeRightInsetPx
        barCardRect.set(cardMarginLeft, barTop + dp(4f), (w - cardMarginRight).coerceAtLeast(cardMarginLeft + dp(120f)), contentBottom)

        val centerY = barCardRect.centerY()
        val leftX = barCardRect.left + barPaddingHPx
        val rightX = barCardRect.right - barPaddingHPx

        switchBtnRect.set(
            leftX,
            centerY - sideButtonSizePx / 2f,
            leftX + sideButtonSizePx,
            centerY + sideButtonSizePx / 2f,
        )
        backspaceBtnRect.set(
            rightX - sideButtonSizePx,
            centerY - sideButtonSizePx / 2f,
            rightX,
            centerY + sideButtonSizePx / 2f,
        )

        val micsLeft = switchBtnRect.right + barInnerGapPx
        val micsRight = backspaceBtnRect.left - barInnerGapPx
        val totalMicWidth = (micsRight - micsLeft - barInnerGapPx).coerceAtLeast(dp(80f))
        val eachMicWidth = totalMicWidth / 2f
        val micHeight = dp(52f)

        whisperBtnRect.set(
            micsLeft,
            centerY - micHeight / 2f,
            micsLeft + eachMicWidth,
            centerY + micHeight / 2f,
        )
        gemmaBtnRect.set(
            whisperBtnRect.right + barInnerGapPx,
            centerY - micHeight / 2f,
            micsRight,
            centerY + micHeight / 2f,
        )

        val bannerBottom = barCardRect.top - dp(4f)
        val bannerTop = bannerBottom - statusStripHeightPx
        cancelBannerRect.set(cardMarginLeft + dp(8f), bannerTop, barCardRect.right - dp(8f), bannerBottom)
        cancelBannerButtonRect.set(
            cancelBannerRect.right - dp(68f),
            bannerTop + dp(3f),
            cancelBannerRect.right - dp(6f),
            bannerBottom - dp(3f),
        )
    }

    fun getIdleTouchableTop(): Int {
        if (height <= 0) return 0
        val top = if (statusMessage.isNotEmpty()) {
            cancelBannerRect.top - dp(4f)
        } else {
            barCardRect.top - dp(4f)
        }
        return top.toInt().coerceIn(0, height)
    }

    fun getIdleTouchableBottom(): Int {
        if (height <= 0) return 0
        return (barCardRect.bottom + dp(4f)).toInt().coerceIn(0, height)
    }

    fun updateLoadedState(engineType: String, modelId: String, isOnlineMode: Boolean) {
        loadedEngineType = engineType
        loadedModelId = modelId
        onlineModeEnabled = isOnlineMode
        invalidate()
    }

    fun updateStatusBanner(message: String, cancellable: Boolean) {
        val hadBanner = statusMessage.isNotEmpty()
        val hasBanner = message.isNotEmpty()
        statusMessage = message
        showCancelBanner = cancellable
        if (hadBanner != hasBanner) {
            listener?.onTouchableRegionChanged()
        }
        invalidate()
    }

    fun updateAmplitude(amplitude: Float) {
        voiceAmplitude = amplitude.coerceIn(0f, 1f)
        if (isRadialExpanded) {
            postInvalidateOnAnimation()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (statusMessage.isNotEmpty() && !isRadialExpanded) {
            drawStatusBanner(canvas)
        }

        drawBottomBar(canvas)

        if (isRadialExpanded && activeSource != null) {
            drawRadialDisc(canvas, activeSource!!)
        }
    }

    private fun drawStatusBanner(canvas: Canvas) {
        canvas.drawRoundRect(cancelBannerRect, dp(16f), dp(16f), pillBgPaint)
        val prevAlign = pillTextPaint.textAlign
        pillTextPaint.textAlign = Paint.Align.LEFT
        val textY = cancelBannerRect.centerY() - (pillTextPaint.descent() + pillTextPaint.ascent()) / 2f
        canvas.drawText(statusMessage, cancelBannerRect.left + dp(14f), textY, pillTextPaint)
        pillTextPaint.textAlign = prevAlign

        if (showCancelBanner) {
            canvas.drawRoundRect(cancelBannerButtonRect, dp(13f), dp(13f), knobCancelPaint)
            val cy = cancelBannerButtonRect.centerY() - (sectorTextPaint.descent() + sectorTextPaint.ascent()) / 2f
            canvas.drawText("取消", cancelBannerButtonRect.centerX(), cy, sectorTextPaint)
        }
    }

    private fun drawBottomBar(canvas: Canvas) {
        val cardRadius = dp(24f)
        canvas.drawRoundRect(barCardRect, cardRadius, cardRadius, barCardPaint)
        canvas.drawRoundRect(barCardRect, cardRadius, cardRadius, barBorderPaint)

        // Left circular switch keyboard button
        val switchPaint = if (idleTouchTarget == IdleTouchTarget.SWITCH) sideBtnPressedPaint else sideBtnPaint
        canvas.drawCircle(switchBtnRect.centerX(), switchBtnRect.centerY(), switchBtnRect.width() / 2f, switchPaint)
        drawKeyboardIcon(canvas, switchBtnRect.centerX(), switchBtnRect.centerY())

        // Right circular backspace button
        val backspacePaint = if (idleTouchTarget == IdleTouchTarget.BACKSPACE) sideBtnPressedPaint else sideBtnPaint
        canvas.drawCircle(backspaceBtnRect.centerX(), backspaceBtnRect.centerY(), backspaceBtnRect.width() / 2f, backspacePaint)
        drawBackspaceIcon(canvas, backspaceBtnRect.centerX(), backspaceBtnRect.centerY())

        // Whisper mic pill
        val micRadius = dp(20f)
        canvas.drawRoundRect(whisperBtnRect, micRadius, micRadius, whisperBtnPaint)
        if (loadedEngineType == "ASR") {
            canvas.drawRoundRect(whisperBtnRect, micRadius, micRadius, activeBtnStrokePaint)
        }
        drawMicButtonContent(
            canvas = canvas,
            rect = whisperBtnRect,
            title = "Whisper",
            subtitle = if (loadedEngineType == "ASR") "已加载" else "原样识别",
            isLoaded = loadedEngineType == "ASR",
            isSparkle = false,
        )

        // Gemma 4 mic pill
        canvas.drawRoundRect(gemmaBtnRect, micRadius, micRadius, gemmaBtnPaint)
        if (loadedEngineType == "LLM") {
            canvas.drawRoundRect(gemmaBtnRect, micRadius, micRadius, gemmaStrokePaint)
        }
        val gemmaTitle = if (onlineModeEnabled) "在线 AI" else "Gemma 4"
        drawMicButtonContent(
            canvas = canvas,
            rect = gemmaBtnRect,
            title = gemmaTitle,
            subtitle = if (loadedEngineType == "LLM") "已加载" else "润色 · 截屏",
            isLoaded = loadedEngineType == "LLM",
            isSparkle = true,
        )
    }

    private fun drawMicButtonContent(
        canvas: Canvas,
        rect: RectF,
        title: String,
        subtitle: String,
        isLoaded: Boolean,
        isSparkle: Boolean,
    ) {
        val iconCx = rect.left + dp(24f)
        val iconCy = rect.centerY()
        drawMicIcon(canvas, iconCx, iconCy, isSparkle)

        val textX = rect.left + dp(40f)
        val titleY = rect.centerY() - dp(2f)
        val subY = rect.centerY() + dp(13f)
        canvas.drawText(title, textX, titleY, labelPaint)
        canvas.drawText(subtitle, textX, subY, subLabelPaint)

        if (isLoaded) {
            canvas.drawCircle(rect.right - dp(12f), rect.top + dp(12f), dp(4f), loadedDotPaint)
        }
    }

    private fun drawRadialDisc(canvas: Canvas, source: MicSource) {
        val currentR = radialRadiusPx * expandProgress
        if (currentR < 4f) return

        scratchRect.set(anchorX - currentR, anchorY - currentR, anchorX + currentR, anchorY + currentR)

        // Base semicircle
        val basePath = Path().apply {
            addArc(scratchRect, 180f, 180f)
            close()
        }
        canvas.drawPath(basePath, discBgPaint)

        // Highlight active sector or center
        val innerR = currentR * RadialMenuGeometry.CENTER_ZONE_RATIO
        if (currentAction == RadialAction.CANCEL) {
            val startAngle = if (source == MicSource.WHISPER) 180f else 180f
            val sweepAngle = if (source == MicSource.WHISPER) 180f else 45f
            drawRingSector(canvas, anchorX, anchorY, innerR, currentR, startAngle, sweepAngle, sectorCancelPaint)
        } else if (source == MicSource.GEMMA) {
            val dist = hypot(pointerX - anchorX, anchorY - pointerY)
            if (dist < innerR) {
                scratchRect.set(anchorX - innerR, anchorY - innerR, anchorX + innerR, anchorY + innerR)
                val centerPath = Path().apply {
                    addArc(scratchRect, 180f, 180f)
                    close()
                }
                canvas.drawPath(centerPath, centerHighlightPaint)
            } else {
                when (currentAction) {
                    RadialAction.GEMMA_POLISH_NORMAL ->
                        drawRingSector(canvas, anchorX, anchorY, innerR, currentR, 225f, 45f, sectorHighlightPaint)
                    RadialAction.GEMMA_POLISH_FORMAL ->
                        drawRingSector(canvas, anchorX, anchorY, innerR, currentR, 270f, 45f, sectorHighlightPaint)
                    RadialAction.GEMMA_SCREENSHOT_INSTRUCTION ->
                        drawRingSector(canvas, anchorX, anchorY, innerR, currentR, 315f, 45f, sectorHighlightPaint)
                    else -> Unit
                }
            }
        } else {
            // Whisper center highlight
            scratchRect.set(anchorX - innerR, anchorY - innerR, anchorX + innerR, anchorY + innerR)
            val centerPath = Path().apply {
                addArc(scratchRect, 180f, 180f)
                close()
            }
            canvas.drawPath(centerPath, centerHighlightPaint)
        }

        // Semicircle outline & inner ring guide
        canvas.drawPath(basePath, discBorderPaint)
        scratchRect.set(anchorX - innerR, anchorY - innerR, anchorX + innerR, anchorY + innerR)
        canvas.drawArc(scratchRect, 180f, 180f, false, dividerPaint)

        // Sector dividers & labels
        if (source == MicSource.WHISPER) {
            drawSectorLabel(canvas, anchorX, anchorY, currentR * 0.78f, 90f, "拖到边缘取消")
            drawSectorLabel(canvas, anchorX, anchorY, currentR * 0.30f, 90f, "松手识别")
        } else {
            for (angle in listOf(45f, 90f, 135f)) {
                val (x1, y1) = RadialMenuGeometry.pointOnArc(anchorX, anchorY, innerR, angle)
                val (x2, y2) = RadialMenuGeometry.pointOnArc(anchorX, anchorY, currentR, angle)
                canvas.drawLine(x1, y1, x2, y2, dividerPaint)
            }
            drawSectorLabel(canvas, anchorX, anchorY, currentR * 0.78f, 157.5f, "取消")
            drawSectorLabel(canvas, anchorX, anchorY, currentR * 0.78f, 112.5f, "普通")
            drawSectorLabel(canvas, anchorX, anchorY, currentR * 0.78f, 67.5f, "正式")
            drawSectorLabel(canvas, anchorX, anchorY, currentR * 0.78f, 22.5f, "截屏")
            drawSectorLabel(canvas, anchorX, anchorY, currentR * 0.28f, 90f, "松手普通润色")
        }

        // Floating action pill above disc
        val hintText = when (currentAction) {
            RadialAction.CANCEL -> "松手取消本次语音"
            RadialAction.WHISPER_TRANSCRIBE -> "正在聆听 · Whisper 原样输出"
            RadialAction.GEMMA_POLISH_NORMAL -> "正在聆听 · 润色为普通表达"
            RadialAction.GEMMA_POLISH_FORMAL -> "正在聆听 · 润色为正式书面语"
            RadialAction.GEMMA_SCREENSHOT_INSTRUCTION -> "正在聆听 · 结合截屏执行指令"
            RadialAction.NONE -> "正在聆听..."
        }
        val pillWidth = pillTextPaint.measureText(hintText) + dp(28f)
        val pillCenterX = anchorX.coerceIn(pillWidth / 2f + dp(8f), width - pillWidth / 2f - dp(8f))
        val pillBottom = (anchorY - currentR - dp(10f)).coerceAtLeast(dp(34f))
        scratchRect.set(pillCenterX - pillWidth / 2f, pillBottom - dp(28f), pillCenterX + pillWidth / 2f, pillBottom)
        canvas.drawRoundRect(scratchRect, dp(14f), dp(14f), pillBgPaint)
        val textY = scratchRect.centerY() - (pillTextPaint.descent() + pillTextPaint.ascent()) / 2f
        canvas.drawText(hintText, pillCenterX, textY, pillTextPaint)

        // Draggable knob at finger with live voice amplitude ring
        val eval = RadialMenuGeometry.evaluate(
            anchorX = anchorX,
            anchorY = anchorY,
            pointerX = pointerX,
            pointerY = pointerY,
            radius = radialRadiusPx,
            source = source,
            bottomGracePx = dp(12f),
        )
        val pulseR = knobRadiusPx + dp(6f) + voiceAmplitude * dp(18f)
        canvas.drawCircle(eval.knobX, eval.knobY, pulseR, amplitudeRingPaint)
        val activeKnobPaint = when {
            currentAction == RadialAction.CANCEL -> knobCancelPaint
            source == MicSource.GEMMA -> knobGemmaPaint
            else -> knobPaint
        }
        canvas.drawCircle(eval.knobX, eval.knobY, knobRadiusPx, activeKnobPaint)
        drawMicIcon(canvas, eval.knobX, eval.knobY, source == MicSource.GEMMA)
    }

    private fun drawRingSector(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        innerR: Float,
        outerR: Float,
        startAngleDeg: Float,
        sweepAngleDeg: Float,
        paint: Paint,
    ) {
        val outerRect = RectF(cx - outerR, cy - outerR, cx + outerR, cy + outerR)
        val innerRect = RectF(cx - innerR, cy - innerR, cx + innerR, cy + innerR)
        val path = Path().apply {
            arcTo(outerRect, startAngleDeg, sweepAngleDeg, true)
            arcTo(innerRect, startAngleDeg + sweepAngleDeg, -sweepAngleDeg, false)
            close()
        }
        canvas.drawPath(path, paint)
    }

    private fun drawSectorLabel(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        angleDeg: Float,
        text: String,
    ) {
        val (x, y) = RadialMenuGeometry.pointOnArc(cx, cy, radius, angleDeg)
        val textY = y - (sectorTextPaint.descent() + sectorTextPaint.ascent()) / 2f
        canvas.drawText(text, x, textY, sectorTextPaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (statusMessage.isNotEmpty() && showCancelBanner && cancelBannerButtonRect.contains(x, y)) {
                    idleTouchTarget = IdleTouchTarget.CANCEL_BANNER
                    invalidate()
                    return true
                }
                if (switchBtnRect.contains(x, y)) {
                    idleTouchTarget = IdleTouchTarget.SWITCH
                    invalidate()
                    return true
                }
                if (backspaceBtnRect.contains(x, y)) {
                    idleTouchTarget = IdleTouchTarget.BACKSPACE
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    listener?.onBackspaceClick()
                    removeCallbacks(backspaceRepeatRunnable)
                    postDelayed(backspaceRepeatRunnable, backspaceRepeatDelayMs)
                    invalidate()
                    return true
                }
                val source = when {
                    whisperBtnRect.contains(x, y) -> MicSource.WHISPER
                    gemmaBtnRect.contains(x, y) -> MicSource.GEMMA
                    else -> null
                }
                if (source != null) {
                    val started = listener?.onStartRecording(source) ?: false
                    if (!started) {
                        return true
                    }
                    beginRadialSession(source, x, y)
                    return true
                }
                return false
            }

            MotionEvent.ACTION_MOVE -> {
                if (isRadialExpanded && activeSource != null) {
                    pointerX = x
                    pointerY = y
                    val eval = RadialMenuGeometry.evaluate(
                        anchorX = anchorX,
                        anchorY = anchorY,
                        pointerX = pointerX,
                        pointerY = pointerY,
                        radius = radialRadiusPx,
                        source = activeSource!!,
                        bottomGracePx = dp(12f),
                    )
                    if (eval.action != currentAction) {
                        currentAction = eval.action
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    }
                    invalidate()
                    return true
                }
                if (idleTouchTarget == IdleTouchTarget.BACKSPACE) {
                    val slop = dp(12f)
                    val stillInside = x >= backspaceBtnRect.left - slop &&
                        x <= backspaceBtnRect.right + slop &&
                        y >= backspaceBtnRect.top - slop &&
                        y <= backspaceBtnRect.bottom + slop
                    if (!stillInside) {
                        removeCallbacks(backspaceRepeatRunnable)
                        idleTouchTarget = IdleTouchTarget.NONE
                        invalidate()
                    }
                    return true
                }
                return idleTouchTarget != IdleTouchTarget.NONE
            }

            MotionEvent.ACTION_UP -> {
                if (isRadialExpanded && activeSource != null) {
                    val src = activeSource!!
                    val finalAction = currentAction
                    endRadialSession()
                    listener?.onFinishRecording(src, finalAction)
                    return true
                }
                removeCallbacks(backspaceRepeatRunnable)
                val target = idleTouchTarget
                idleTouchTarget = IdleTouchTarget.NONE
                invalidate()
                when (target) {
                    IdleTouchTarget.SWITCH -> {
                        if (event.eventTime - event.downTime >= 450L) {
                            listener?.onSwitchKeyboardLongClick()
                        } else {
                            listener?.onSwitchKeyboardClick()
                        }
                    }
                    IdleTouchTarget.BACKSPACE -> Unit // Already fired on ACTION_DOWN + repeat
                    IdleTouchTarget.CANCEL_BANNER -> listener?.onCancelActiveGeneration()
                    IdleTouchTarget.NONE -> Unit
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(backspaceRepeatRunnable)
                if (isRadialExpanded && activeSource != null) {
                    val src = activeSource!!
                    endRadialSession()
                    listener?.onFinishRecording(src, RadialAction.CANCEL)
                    return true
                }
                idleTouchTarget = IdleTouchTarget.NONE
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(backspaceRepeatRunnable)
        super.onDetachedFromWindow()
    }

    private fun beginRadialSession(source: MicSource, downX: Float, downY: Float) {
        activeSource = source
        val minAnchorX = radialRadiusPx + dp(8f)
        val maxAnchorX = (width - radialRadiusPx - dp(8f)).coerceAtLeast(minAnchorX)
        anchorX = downX.coerceIn(minAnchorX, maxAnchorX)
        anchorY = barCardRect.top - dp(4f)
        pointerX = downX
        pointerY = downY
        currentAction = if (source == MicSource.WHISPER) {
            RadialAction.WHISPER_TRANSCRIBE
        } else {
            RadialAction.GEMMA_POLISH_NORMAL
        }
        isRadialExpanded = true
        voiceAmplitude = 0f
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        listener?.onTouchableRegionChanged()

        expandAnimator?.cancel()
        expandAnimator = ValueAnimator.ofFloat(0.25f, 1f).apply {
            duration = 160L
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                expandProgress = anim.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun endRadialSession() {
        expandAnimator?.cancel()
        expandProgress = 0f
        isRadialExpanded = false
        val wasActive = activeSource != null
        activeSource = null
        currentAction = RadialAction.NONE
        voiceAmplitude = 0f
        if (wasActive) {
            listener?.onTouchableRegionChanged()
        }
        invalidate()
    }

    private fun drawKeyboardIcon(canvas: Canvas, cx: Float, cy: Float) {
        val w = dp(20f)
        val h = dp(13f)
        scratchRect.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
        canvas.drawRoundRect(scratchRect, dp(2.5f), dp(2.5f), iconPaint)
        val dotY1 = cy - dp(2.2f)
        val dotY2 = cy + dp(2.2f)
        for (dx in listOf(-dp(5.5f), 0f, dp(5.5f))) {
            canvas.drawCircle(cx + dx, dotY1, dp(1.1f), iconFillPaint)
        }
        canvas.drawLine(cx - dp(4.5f), dotY2, cx + dp(4.5f), dotY2, iconPaint)
    }

    private fun drawBackspaceIcon(canvas: Canvas, cx: Float, cy: Float) {
        val left = cx - dp(10f)
        val tipX = left + dp(5.5f)
        val right = cx + dp(9.5f)
        val halfH = dp(6.5f)
        val outline = Path().apply {
            moveTo(left, cy)
            lineTo(tipX, cy - halfH)
            lineTo(right, cy - halfH)
            lineTo(right, cy + halfH)
            lineTo(tipX, cy + halfH)
            close()
        }
        canvas.drawPath(outline, iconPaint)
        val crossCenterX = cx + dp(2f)
        val r = dp(2.8f)
        canvas.drawLine(crossCenterX - r, cy - r, crossCenterX + r, cy + r, iconPaint)
        canvas.drawLine(crossCenterX - r, cy + r, crossCenterX + r, cy - r, iconPaint)
    }

    private fun drawMicIcon(canvas: Canvas, cx: Float, cy: Float, sparkle: Boolean) {
        val capsuleW = dp(6f)
        val capsuleH = dp(10f)
        scratchRect.set(
            cx - capsuleW / 2f,
            cy - capsuleH / 2f - dp(2f),
            cx + capsuleW / 2f,
            cy + capsuleH / 2f - dp(2f),
        )
        canvas.drawRoundRect(scratchRect, capsuleW / 2f, capsuleW / 2f, iconFillPaint)

        scratchRect.set(cx - dp(6f), cy - dp(5f), cx + dp(6f), cy + dp(6f))
        canvas.drawArc(scratchRect, 0f, 180f, false, iconPaint)
        canvas.drawLine(cx, cy + dp(6f), cx, cy + dp(9.5f), iconPaint)
        canvas.drawLine(cx - dp(3.5f), cy + dp(9.5f), cx + dp(3.5f), cy + dp(9.5f), iconPaint)

        if (sparkle) {
            val sx = cx + dp(7.5f)
            val sy = cy - dp(7.5f)
            canvas.drawLine(sx - dp(2.2f), sy, sx + dp(2.2f), sy, iconPaint)
            canvas.drawLine(sx, sy - dp(2.2f), sx, sy + dp(2.2f), iconPaint)
        }
    }
}
