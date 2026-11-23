package com.litertime.android.ime

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Debug
import android.util.AttributeSet
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Bottom-anchored IME view containing:
 * - A full-width rectangular panel that reaches the bottom of the screen:
 *   [Switch Keyboard] [Whisper Mic] [Gemma 4 Mic] [Backspace]
 * - A status hint row directly below the two mic buttons, and a live readout of the
 *   `:inference` process memory in the bottom strip that stays clear of the system safe area.
 *   The red "停止" pill sits to the left of that readout.
 * - An expandable radial semicircle disc above the panel with a draggable circular knob, plus a
 *   persistent "tray" of app-action bubbles (unload model, reload Gemma with GPU) filling the
 *   lower control area below the mic row. Both are visible for the whole gesture: the knob moves
 *   freely across the disc, the mic row and the tray, and the bubble under the finger wins.
 *
 * Only the strip above the panel stays transparent so the host app shows through.
 * `LiteRtImeService` adjusts `onComputeInsets().touchableRegion` dynamically via
 * [isRadialExpanded], [getIdleTouchableTop], and [getIdleTouchableBottom], while respecting
 * system bottom safe insets.
 */
class VoiceImeOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    interface Listener {
        fun onSwitchKeyboardClick()
        fun onSwitchKeyboardLongClick()
        fun onBackspaceClick()
        fun onEnterClick()
        fun onStartRecording(source: MicSource): Boolean
        fun onFinishRecording(source: MicSource, action: RadialAction)

        /**
         * The mic drag ended below the mic row on an app-level action (unload / GPU reload).
         * The recording is discarded; no audio request is dispatched.
         */
        fun onDragAppAction(source: MicSource, action: DragAppAction)
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

    // Page size used to convert /proc/<pid>/statm resident pages, derived from /proc/self so
    // 16 KB-page kernels are handled without touching @hide OsConstants fields.
    private val pageSizeBytes: Long by lazy {
        runCatching {
            val totalPages = File("/proc/self/statm")
                .readText()
                .trim()
                .split(' ')
                .firstOrNull()
                ?.toLongOrNull()
            val vmSizeKb = File("/proc/self/status")
                .readLines()
                .firstOrNull { it.startsWith("VmSize:") }
                ?.split(Regex("\\s+"))
                ?.getOrNull(1)
                ?.toLongOrNull()
            if (totalPages != null && totalPages > 0L && vmSizeKb != null && vmSizeKb > 0L) {
                vmSizeKb * 1024L / totalPages
            } else {
                4096L
            }
        }.getOrDefault(4096L)
    }

    // Layout: one full-width rectangular panel anchored to the bottom of the screen.
    // The transparent strip above the panel hosts the radial gesture disc.
    private val radialTopSpacePx = dp(140f).toInt()
    private val panelTopPaddingPx = dp(12f)
    private val micButtonHeightPx = dp(104f) // 2× the previous 52dp
    private val belowMicRegionPx = dp(48f) // status hint row + memory/cancel strip below the mic row
    private val baseWindowHeightPx =
        (radialTopSpacePx + panelTopPaddingPx + micButtonHeightPx + belowMicRegionPx).toInt()
    private val radialRadiusPx = dp(132f)
    private val knobRadiusPx = dp(28f)
    private val sideButtonSizePx = dp(46f)
    private val barPaddingHPx = dp(16f)
    private val barInnerGapPx = dp(10f)
    private val statusRowHeightPx = dp(20f)
    private val memoryRefreshIntervalMs = 2_000L

    private var safeBottomInsetPx: Int = 0
    private var safeLeftInsetPx: Int = 0
    private var safeRightInsetPx: Int = 0

    // Loaded state indicators (Whisper and Gemma may be resident at the same time).
    private var whisperLoaded: Boolean = false
    private var gemmaLoaded: Boolean = false
    private var gemmaLoadedWithGpu: Boolean = false
    private var inferencePid: Int = 0
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
    // Distance ratio produced by the last geometry evaluation; drives the center-zone highlight.
    private var currentDistanceRatio: Float = 0f
    private var expandProgress: Float = 0f
    private var voiceAmplitude: Float = 0f

    // Down-drag app-action targets (unload / GPU reload) that live in the lower control area,
// below the mic row. They are laid out and drawn for the whole gesture so the user sees the
// full option map immediately instead of only after dragging there.
    private var trayActions: List<DragAppAction> = emptyList()
    private var trayChips: List<Pair<DragAppAction, RectF>> = emptyList()
    private var selectedTrayAction: DragAppAction? = null
    private var isTrayTargeted: Boolean = false

    private var expandAnimator: ValueAnimator? = null

    // Hit rectangles computed in onLayout / onSizeChanged
    private val switchBtnRect = RectF()
    private val whisperBtnRect = RectF()
    private val gemmaBtnRect = RectF()
    private val backspaceBtnRect = RectF()
    private val enterBtnRect = RectF()
    private val barCardRect = RectF()
    private val cancelBannerRect = RectF()
    private val cancelBannerButtonRect = RectF()
    private val scratchRect = RectF()

    // Touch target tracking when not in radial mode
    private enum class IdleTouchTarget {
        NONE,
        SWITCH,
        BACKSPACE,
        ENTER,
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
        color = 0xFF3A3A3A.toInt()
        style = Paint.Style.FILL
    }
    private val rainbowColors = intArrayOf(
        0xFFFFB4B4.toInt(), // low-saturation red
        0xFFFFD3A5.toInt(), // low-saturation orange
        0xFFFBE98F.toInt(), // low-saturation yellow
        0xFFB7E4C7.toInt(), // low-saturation green
        0xFFA8D8F0.toInt(), // low-saturation blue
        0xFFC4B5F0.toInt(), // low-saturation purple
    )
    private val enterBtnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF2563EB.toInt()
        style = Paint.Style.FILL
    }
    private val enterBtnPressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF5B93FF.toInt()
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
    private val aiTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.LEFT
    }
    private val whiteGlyphStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val whiteGlyphFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val rainbowGlyphStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val rainbowGlyphFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
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
    private val chipBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF2A3140.toInt()
        style = Paint.Style.FILL
    }
    private val chipSelectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF2563EB.toInt()
        style = Paint.Style.FILL
    }
    private val chipDangerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFB91C1C.toInt()
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
    private val memoryTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x99FFFFFF.toInt()
        textSize = sp(10f)
        textAlign = Paint.Align.RIGHT
    }

    // Live process memory readout shown in the bottom strip.
    private var memoryUsageMb: Int = 0
    private val memoryRefreshRunnable = object : Runnable {
        override fun run() {
            memoryUsageMb = readMemoryUsageMb()
            invalidate()
            postDelayed(this, memoryRefreshIntervalMs)
        }
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
        val contentBottom = (h - safeBottomInsetPx).coerceAtLeast(dp(1f))
        val contentHeight = panelTopPaddingPx + micButtonHeightPx + belowMicRegionPx
        val panelTop = (contentBottom - contentHeight).coerceAtLeast(0f)
        // Full-width rectangular panel: no floating card, spans to the bottom of the screen.
        barCardRect.set(0f, panelTop, w, h)

        val micTop = panelTop + panelTopPaddingPx
        val micBottom = micTop + micButtonHeightPx
        val centerY = (micTop + micBottom) / 2f

        val contentLeft = safeLeftInsetPx + barPaddingHPx
        val contentRight = (w - safeRightInsetPx - barPaddingHPx)
            .coerceAtLeast(contentLeft + dp(120f))

        switchBtnRect.set(
            contentLeft,
            centerY - sideButtonSizePx / 2f,
            contentLeft + sideButtonSizePx,
            centerY + sideButtonSizePx / 2f,
        )
        // Right column: backspace on top, blue enter key below it.
        backspaceBtnRect.set(
            contentRight - sideButtonSizePx,
            micTop,
            contentRight,
            micTop + sideButtonSizePx,
        )
        enterBtnRect.set(
            contentRight - sideButtonSizePx,
            micBottom - sideButtonSizePx,
            contentRight,
            micBottom,
        )

        val micsLeft = switchBtnRect.right + barInnerGapPx
        val micsRight = backspaceBtnRect.left - barInnerGapPx
        val totalMicWidth = (micsRight - micsLeft - barInnerGapPx).coerceAtLeast(dp(80f))
        val eachMicWidth = totalMicWidth / 2f

        whisperBtnRect.set(micsLeft, micTop, micsLeft + eachMicWidth, micBottom)
        gemmaBtnRect.set(
            whisperBtnRect.right + barInnerGapPx,
            micTop,
            micsRight,
            micBottom,
        )

        // Status hint row sits directly below the two mic buttons.
        val statusTop = micBottom + dp(1f)
        val statusBottom = statusTop + statusRowHeightPx
        cancelBannerRect.set(contentLeft + dp(4f), statusTop, contentRight - dp(4f), statusBottom)

        // Bottom strip: the red "停止" pill sits to the LEFT of the memory readout,
        // both hugging the bottom-right corner of the panel.
        val stripBottom = barCardRect.bottom - safeBottomInsetPx - dp(2f)
        val stripTop = stripBottom - dp(22f)
        val memoryReserveWidth = memoryTextPaint.measureText("内存 9999 MB")
        val memoryLeft = contentRight - memoryReserveWidth
        val cancelRight = memoryLeft - dp(10f)
        cancelBannerButtonRect.set(
            (cancelRight - dp(48f)).coerceAtLeast(contentLeft),
            stripTop,
            cancelRight.coerceAtLeast(contentLeft),
            stripBottom,
        )
    }

    fun getIdleTouchableTop(): Int {
        if (height <= 0) return 0
        return barCardRect.top.toInt().coerceIn(0, height)
    }

    fun getIdleTouchableBottom(): Int {
        if (height <= 0) return 0
        // The rectangular panel reaches the bottom of the screen.
        return height
    }

    fun updateLoadedState(
        isWhisperLoaded: Boolean,
        isGemmaLoaded: Boolean,
        isGemmaGpu: Boolean,
        inferencePid: Int,
        isOnlineMode: Boolean,
    ) {
        whisperLoaded = isWhisperLoaded
        gemmaLoaded = isGemmaLoaded
        gemmaLoadedWithGpu = isGemmaGpu
        this.inferencePid = inferencePid
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

        drawBottomBar(canvas)

        if (!isRadialExpanded) {
            if (statusMessage.isNotEmpty()) {
                drawStatusBanner(canvas)
            }
            drawMemoryUsage(canvas)
        }

        if (isRadialExpanded && activeSource != null) {
            drawTrayTargets(canvas)
            drawRadialDisc(canvas, activeSource!!)
        }
    }

    private fun drawStatusBanner(canvas: Canvas) {
        val prevAlign = pillTextPaint.textAlign
        pillTextPaint.textAlign = Paint.Align.LEFT
        val textY = cancelBannerRect.centerY() - (pillTextPaint.descent() + pillTextPaint.ascent()) / 2f
        canvas.drawText(statusMessage, cancelBannerRect.left + dp(2f), textY, pillTextPaint)
        pillTextPaint.textAlign = prevAlign

        if (showCancelBanner) {
            canvas.drawRoundRect(cancelBannerButtonRect, dp(13f), dp(13f), knobCancelPaint)
            val cy = cancelBannerButtonRect.centerY() - (sectorTextPaint.descent() + sectorTextPaint.ascent()) / 2f
            canvas.drawText("停止", cancelBannerButtonRect.centerX(), cy, sectorTextPaint)
        }
    }

    private fun drawMemoryUsage(canvas: Canvas) {
        val label = if (memoryUsageMb > 0) formatMemoryLabel(memoryUsageMb) else "内存 --"
        val x = width - safeRightInsetPx - barPaddingHPx
        val baseline = barCardRect.bottom - safeBottomInsetPx - dp(6f)
        canvas.drawText(label, x, baseline, memoryTextPaint)
    }

    private fun formatMemoryLabel(mb: Int): String =
        if (mb >= 1024) String.format(Locale.US, "内存 %.1f GB", mb / 1024f) else "内存 $mb MB"

    /**
     * PSS of the `:inference` process, which is where the model weights actually live.
     * Falls back to /proc RSS, then to this UI process's own PSS when the pid is unknown.
     */
    private fun readMemoryUsageMb(): Int {
        val pid = inferencePid
        if (pid > 0) {
            readProcessPssMb(pid)?.let { return it }
            readProcessRssMb(pid)?.let { return it }
        }
        return readOwnProcessPssMb() ?: 0
    }

    private fun readProcessPssMb(pid: Int): Int? = try {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val pssKb = manager?.getProcessMemoryInfo(intArrayOf(pid))?.firstOrNull()?.totalPss ?: 0
        if (pssKb > 0) pssKb / 1024 else null
    } catch (_: Throwable) {
        null
    }

    private fun readProcessRssMb(pid: Int): Int? = try {
        val residentPages = File("/proc/$pid/statm")
            .readText()
            .trim()
            .split(' ')
            .getOrNull(1)
            ?.toLongOrNull()
        if (residentPages == null || residentPages <= 0L) {
            null
        } else {
            ((residentPages * pageSizeBytes) / (1024L * 1024L)).toInt()
        }
    } catch (_: Throwable) {
        null
    }

    private fun readOwnProcessPssMb(): Int? = try {
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        if (info.totalPss > 0) info.totalPss / 1024 else null
    } catch (_: Throwable) {
        val runtime = Runtime.getRuntime()
        ((runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L)).toInt()
    }

    private fun drawBottomBar(canvas: Canvas) {
        // Full-width rectangular panel that fills from its top edge to the screen bottom.
        canvas.drawRect(barCardRect, barCardPaint)
        canvas.drawLine(0f, barCardRect.top, width.toFloat(), barCardRect.top, barBorderPaint)

        // Left circular switch keyboard button
        val switchPaint = if (idleTouchTarget == IdleTouchTarget.SWITCH) sideBtnPressedPaint else sideBtnPaint
        canvas.drawCircle(switchBtnRect.centerX(), switchBtnRect.centerY(), switchBtnRect.width() / 2f, switchPaint)
        drawKeyboardIcon(canvas, switchBtnRect.centerX(), switchBtnRect.centerY())

        // Right column: backspace on top, blue enter key below it.
        val backspacePaint = if (idleTouchTarget == IdleTouchTarget.BACKSPACE) sideBtnPressedPaint else sideBtnPaint
        canvas.drawCircle(backspaceBtnRect.centerX(), backspaceBtnRect.centerY(), backspaceBtnRect.width() / 2f, backspacePaint)
        drawBackspaceIcon(canvas, backspaceBtnRect.centerX(), backspaceBtnRect.centerY())

        val enterPaint = if (idleTouchTarget == IdleTouchTarget.ENTER) enterBtnPressedPaint else enterBtnPaint
        canvas.drawRoundRect(enterBtnRect, dp(14f), dp(14f), enterPaint)
        drawEnterIcon(canvas, enterBtnRect.centerX(), enterBtnRect.centerY())

        // The two large microphone buttons share one glyph size so they stay visually balanced.
        val micGlyphHeight = computeMicGlyphHeight(gemmaBtnRect)
        val micRadius = dp(26f)

        // Whisper mic: original dark blue fill, white icon, no text.
        canvas.drawRoundRect(whisperBtnRect, micRadius, micRadius, whisperBtnPaint)
        if (whisperLoaded) {
            canvas.drawRoundRect(whisperBtnRect, micRadius, micRadius, activeBtnStrokePaint)
        }
        drawScaledMicGlyph(
            canvas = canvas,
            cx = whisperBtnRect.centerX(),
            centerY = whisperBtnRect.centerY(),
            heightPx = micGlyphHeight,
            strokePaint = whiteGlyphStrokePaint,
            fillPaint = whiteGlyphFillPaint,
        )
        if (whisperLoaded) {
            canvas.drawCircle(whisperBtnRect.right - dp(12f), whisperBtnRect.top + dp(12f), dp(4f), loadedDotPaint)
        }

        // AI mic: dark purple fill, light rainbow mic glyph plus "AI".
        canvas.drawRoundRect(gemmaBtnRect, micRadius, micRadius, gemmaBtnPaint)
        if (gemmaLoaded) {
            canvas.drawRoundRect(gemmaBtnRect, micRadius, micRadius, gemmaStrokePaint)
        }
        drawAiMicContent(canvas, gemmaBtnRect, micGlyphHeight)
        if (gemmaLoaded) {
            canvas.drawCircle(gemmaBtnRect.right - dp(12f), gemmaBtnRect.top + dp(12f), dp(4f), loadedDotPaint)
        }
    }

    /** Largest mic glyph height that fits both the button height and the "AI" lockup width. */
    private fun computeMicGlyphHeight(rect: RectF): Float {
        val maxByHeight = (rect.height() - dp(16f)).coerceAtMost(dp(66f))
        if (maxByHeight <= 0f) return 0f

        val horizontalPadding = dp(14f)
        val contentWidth = (rect.width() - horizontalPadding * 2f).coerceAtLeast(0f)
        val gap = dp(10f)
        // The drawn mic glyph is 12dp wide for every 16.5dp of height.
        val glyphWidthRatio = 12f / 16.5f

        val probeSize = 100f
        aiTextPaint.textSize = probeSize
        val probeBounds = Rect()
        aiTextPaint.getTextBounds("AI", 0, 2, probeBounds)
        val inkRatio = (probeBounds.height() / probeSize).coerceAtLeast(0.01f)
        val aiWidthAtProbe = aiTextPaint.measureText("AI")

        var glyphHeight = maxByHeight
        val glyphWidth = glyphHeight * glyphWidthRatio
        // "AI" is drawn at half the mic glyph height, so it takes less horizontal room.
        val aiTextSize = (glyphHeight / inkRatio) / 2f
        val aiWidth = aiWidthAtProbe * (aiTextSize / probeSize)
        val totalWidth = glyphWidth + gap + aiWidth
        if (totalWidth > contentWidth && totalWidth > 0f) {
            glyphHeight *= contentWidth / totalWidth
        }
        // Halve the whole lockup (mic icon + "AI") relative to the previous size.
        return (glyphHeight * 0.5f).coerceAtLeast(0f)
    }

    private fun drawAiMicContent(canvas: Canvas, rect: RectF, glyphHeight: Float) {
        if (glyphHeight <= 0f) return
        val gap = dp(10f)
        val glyphWidthRatio = 12f / 16.5f

        val probeSize = 100f
        aiTextPaint.textSize = probeSize
        val probeBounds = Rect()
        aiTextPaint.getTextBounds("AI", 0, 2, probeBounds)
        val inkRatio = (probeBounds.height() / probeSize).coerceAtLeast(0.01f)
        val aiWidthAtProbe = aiTextPaint.measureText("AI")
        val aiTextSize = (glyphHeight / inkRatio) / 2f
        val aiWidth = aiWidthAtProbe * (aiTextSize / probeSize)
        val glyphWidth = glyphHeight * glyphWidthRatio

        val totalWidth = glyphWidth + gap + aiWidth
        val startX = rect.centerX() - totalWidth / 2f
        val centerY = rect.centerY()

        // Light rainbow gradient applied to the mic glyph itself, not the button.
        val glyphShader = LinearGradient(
            0f,
            centerY - glyphHeight / 2f,
            0f,
            centerY + glyphHeight / 2f,
            rainbowColors,
            null,
            Shader.TileMode.CLAMP,
        )
        rainbowGlyphStrokePaint.shader = glyphShader
        rainbowGlyphFillPaint.shader = glyphShader

        drawScaledMicGlyph(
            canvas = canvas,
            cx = startX + glyphWidth / 2f,
            centerY = centerY,
            heightPx = glyphHeight,
            strokePaint = rainbowGlyphStrokePaint,
            fillPaint = rainbowGlyphFillPaint,
        )

        aiTextPaint.textSize = aiTextSize
        val textBounds = Rect()
        aiTextPaint.getTextBounds("AI", 0, 2, textBounds)
        // Bottom-align "AI" with the bottom edge of the mic glyph.
        val baseline = centerY + glyphHeight / 2f - textBounds.bottom
        canvas.drawText("AI", startX + glyphWidth + gap, baseline, aiTextPaint)
    }

    private fun drawScaledMicGlyph(
        canvas: Canvas,
        cx: Float,
        centerY: Float,
        heightPx: Float,
        strokePaint: Paint,
        fillPaint: Paint,
    ) {
        if (heightPx <= 0f) return
        // The base glyph is 16.5dp tall from capsule top to the stand foot.
        val s = heightPx / dp(16.5f)
        val cy = centerY - dp(1.25f) * s

        strokePaint.strokeWidth = dp(2f) * s

        val capsuleW = dp(6f) * s
        val capsuleH = dp(10f) * s
        scratchRect.set(
            cx - capsuleW / 2f,
            cy - capsuleH / 2f - dp(2f) * s,
            cx + capsuleW / 2f,
            cy + capsuleH / 2f - dp(2f) * s,
        )
        canvas.drawRoundRect(scratchRect, capsuleW / 2f, capsuleW / 2f, fillPaint)

        scratchRect.set(cx - dp(6f) * s, cy - dp(5f) * s, cx + dp(6f) * s, cy + dp(6f) * s)
        canvas.drawArc(scratchRect, 0f, 180f, false, strokePaint)
        canvas.drawLine(cx, cy + dp(6f) * s, cx, cy + dp(9.5f) * s, strokePaint)
        canvas.drawLine(
            cx - dp(3.5f) * s,
            cy + dp(9.5f) * s,
            cx + dp(3.5f) * s,
            cy + dp(9.5f) * s,
            strokePaint,
        )
    }

    private fun drawEnterIcon(canvas: Canvas, cx: Float, cy: Float) {
        val r = dp(9f)
        val arrowX = cx - dp(5f)
        val shaftY = cy + dp(3f)
        val rightX = cx + dp(6f)
        canvas.drawLine(rightX, cy - r, rightX, shaftY, iconPaint)
        canvas.drawLine(rightX, shaftY, arrowX, shaftY, iconPaint)
        canvas.drawLine(arrowX, shaftY, arrowX + dp(5f), shaftY - dp(5f), iconPaint)
        canvas.drawLine(arrowX, shaftY, arrowX + dp(5f), shaftY + dp(5f), iconPaint)
    }

    private fun dragActionsFor(source: MicSource): List<DragAppAction> =
        RadialMenuGeometry.dragAppActions(
            source = source,
            whisperLoaded = whisperLoaded,
            gemmaLoaded = gemmaLoaded,
            gemmaLoadedWithGpu = gemmaLoadedWithGpu,
        )

    private fun micRowBottom(): Float = whisperBtnRect.bottom

    /** The tray bubbles define the trigger zone; a couple of dp of slop keep it forgiving. */
    private fun trayActivationY(): Float =
        (trayChips.firstOrNull()?.second?.top ?: (micRowBottom() + dp(4f))) - dp(2f)

    private fun prepareTray(source: MicSource) {
        trayActions = dragActionsFor(source)
        trayChips = emptyList()
        selectedTrayAction = null
        isTrayTargeted = false
        layoutTrayChips(trayActions)
    }

    private fun clearTray() {
        isTrayTargeted = false
        selectedTrayAction = null
        trayActions = emptyList()
        trayChips = emptyList()
    }

    /** Splits the whole lower control area below the mic row into equal, edge-to-edge bubbles. */
    private fun layoutTrayChips(actions: List<DragAppAction>) {
        trayChips = emptyList()
        if (actions.isEmpty() || width <= 0) return
        val left = safeLeftInsetPx + barPaddingHPx
        val right = (width - safeRightInsetPx - barPaddingHPx).coerceAtLeast(left + dp(120f))
        val chipTop = micRowBottom() + dp(4f)
        val chipBottom = barCardRect.bottom - safeBottomInsetPx - dp(2f)
        if (chipBottom - chipTop < dp(16f)) return
        val gap = dp(6f)
        val available = (right - left).coerceAtLeast(dp(1f))
        val chipWidth = (available - gap * (actions.size - 1)) / actions.size
        if (chipWidth <= 0f) return
        var x = left
        val chips = mutableListOf<Pair<DragAppAction, RectF>>()
        actions.forEachIndexed { index, action ->
            val chipRight = if (index == actions.lastIndex) right else x + chipWidth
            chips += action to RectF(x, chipTop, chipRight, chipBottom)
            x = chipRight + gap
        }
        trayChips = chips
    }

    /** Chooses the bubble under the finger; null when the finger is still above the tray. */
    private fun selectTrayActionAt(x: Float, y: Float): DragAppAction? {
        if (trayChips.isEmpty()) return null
        if (y < trayChips.first().second.top - dp(2f)) return null
        return trayChips.minByOrNull { abs(it.second.centerX() - x) }?.first
    }

    private fun trayHint(action: DragAppAction): String = when (action) {
        DragAppAction.UNLOAD_WHISPER -> "松手卸载 Whisper"
        DragAppAction.UNLOAD_GEMMA -> "松手卸载 Gemma 4"
        DragAppAction.RELOAD_GEMMA_GPU -> "松手用 GPU 重载 Gemma 4"
        DragAppAction.LOAD_GEMMA_GPU -> "松手用 GPU 加载 Gemma 4"
    }

    private fun drawTrayTargets(canvas: Canvas) {
        if (trayChips.isEmpty()) return
        for ((action, rect) in trayChips) {
            val isDanger = action == DragAppAction.UNLOAD_WHISPER || action == DragAppAction.UNLOAD_GEMMA
            val selected = action == selectedTrayAction
            val background = when {
                !selected -> chipBgPaint
                isDanger -> chipDangerPaint
                else -> chipSelectedPaint
            }
            canvas.drawRoundRect(rect, rect.height() / 2f, rect.height() / 2f, background)
            // Equal-width bubbles: shrink the label instead of overflowing on narrow screens.
            val previousTextSize = sectorTextPaint.textSize
            val maxTextWidth = rect.width() - dp(12f)
            val textWidth = sectorTextPaint.measureText(action.label)
            if (maxTextWidth > 0f && textWidth > maxTextWidth) {
                sectorTextPaint.textSize = previousTextSize * (maxTextWidth / textWidth)
            }
            val cy = rect.centerY() - (sectorTextPaint.descent() + sectorTextPaint.ascent()) / 2f
            canvas.drawText(action.label, rect.centerX(), cy, sectorTextPaint)
            sectorTextPaint.textSize = previousTextSize
        }

        val selected = selectedTrayAction
        if (isTrayTargeted && selected != null) {
            val hintText = trayHint(selected)
            val pillWidth = pillTextPaint.measureText(hintText) + dp(28f)
            val centerX = pointerX.coerceIn(pillWidth / 2f + dp(8f), width - pillWidth / 2f - dp(8f))
            val pillBottom = (trayChips.first().second.top - dp(6f)).coerceAtLeast(dp(34f))
            scratchRect.set(
                centerX - pillWidth / 2f,
                pillBottom - dp(28f),
                centerX + pillWidth / 2f,
                pillBottom,
            )
            canvas.drawRoundRect(scratchRect, dp(14f), dp(14f), pillBgPaint)
            val textY = scratchRect.centerY() - (pillTextPaint.descent() + pillTextPaint.ascent()) / 2f
            canvas.drawText(hintText, centerX, textY, pillTextPaint)
        }
    }

    private fun knobFillPaint(source: MicSource): Paint = when {
        isTrayTargeted -> when (selectedTrayAction) {
            null -> if (source == MicSource.GEMMA) knobGemmaPaint else knobPaint
            DragAppAction.UNLOAD_WHISPER, DragAppAction.UNLOAD_GEMMA -> knobCancelPaint
            else -> knobPaint
        }
        currentAction == RadialAction.CANCEL -> knobCancelPaint
        source == MicSource.GEMMA -> knobGemmaPaint
        else -> knobPaint
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

        // Highlight active sector or center (suppressed while the finger is in the lower tray)
        val innerR = currentR * RadialMenuGeometry.CENTER_ZONE_RATIO
        if (isTrayTargeted) {
            // No semicircle sector is active in this state.
        } else if (currentAction == RadialAction.CANCEL) {
            val startAngle = if (source == MicSource.WHISPER) 180f else 180f
            val sweepAngle = if (source == MicSource.WHISPER) 180f else 45f
            drawRingSector(canvas, anchorX, anchorY, innerR, currentR, startAngle, sweepAngle, sectorCancelPaint)
        } else if (source == MicSource.GEMMA) {
            // The center zone is a full circle around the anchor (the finger starts inside the
            // mic button, which is below the anchor). Any point inside it commits the default
            // action, so highlight the whole zone instead of only the upper half-disc.
            if (currentDistanceRatio < RadialMenuGeometry.CENTER_ZONE_RATIO) {
                scratchRect.set(anchorX - innerR, anchorY - innerR, anchorX + innerR, anchorY + innerR)
                val centerPath = Path().apply {
                    addArc(scratchRect, 0f, 360f)
                    close()
                }
                canvas.drawPath(centerPath, centerHighlightPaint)
            } else {
                when (currentAction) {
                    RadialAction.GEMMA_POLISH_FORMAL ->
                        drawRingSector(canvas, anchorX, anchorY, innerR, currentR, 225f, 45f, sectorHighlightPaint)
                    RadialAction.GEMMA_SCREENSHOT_CORRECTION ->
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
            drawSectorLabel(canvas, anchorX, anchorY, currentR * 0.78f, 90f, "取消")
            drawSectorLabel(canvas, anchorX, anchorY, currentR * 0.30f, 90f, "普通识别")
        } else {
            for (angle in listOf(45f, 90f, 135f)) {
                val (x1, y1) = RadialMenuGeometry.pointOnArc(anchorX, anchorY, innerR, angle)
                val (x2, y2) = RadialMenuGeometry.pointOnArc(anchorX, anchorY, currentR, angle)
                canvas.drawLine(x1, y1, x2, y2, dividerPaint)
            }
            drawSectorLabel(canvas, anchorX, anchorY, currentR * 0.78f, 157.5f, "取消")
            drawSectorLabel(canvas, anchorX, anchorY, currentR * 0.78f, 112.5f, "正式")
            drawSectorLabel(canvas, anchorX, anchorY, currentR * 0.78f, 67.5f, "纠错")
            drawSectorLabel(canvas, anchorX, anchorY, currentR * 0.78f, 22.5f, "指令")
            drawSectorLabel(canvas, anchorX, anchorY, currentR * 0.28f, 90f, "截屏纠错")
        }

        // Floating action pill above disc (the tray draws its own pill while targeted)
        if (!isTrayTargeted) {
            val hintText = when (currentAction) {
                RadialAction.CANCEL -> "松手取消本次语音"
                RadialAction.WHISPER_TRANSCRIBE -> "正在聆听 · Whisper 原样输出"
                RadialAction.GEMMA_POLISH_FORMAL -> "正在聆听 · 润色为正式书面语"
                RadialAction.GEMMA_SCREENSHOT_CORRECTION -> "正在聆听 · 结合截屏纠正识别"
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
        }

        // Draggable knob: follows the finger freely across the disc, the mic row and the tray.
        val knobX = pointerX.coerceIn(dp(12f), (width - dp(12f)).coerceAtLeast(dp(12f)))
        val knobY = pointerY.coerceIn(dp(12f), (height - dp(12f)).coerceAtLeast(dp(12f)))
        val pulseR = knobRadiusPx + dp(6f) + voiceAmplitude * dp(18f)
        canvas.drawCircle(knobX, knobY, pulseR, amplitudeRingPaint)
        canvas.drawCircle(knobX, knobY, knobRadiusPx, knobFillPaint(source))
        drawMicIcon(canvas, knobX, knobY, source == MicSource.GEMMA)
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
                if (enterBtnRect.contains(x, y)) {
                    idleTouchTarget = IdleTouchTarget.ENTER
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
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
                    val src = activeSource!!
                    pointerX = x
                    pointerY = y
                    // The lower tray is selectable as soon as the finger leaves the mic row.
                    if (pointerY > trayActivationY() && trayActions.isNotEmpty()) {
                        isTrayTargeted = true
                        val picked = selectTrayActionAt(x, y)
                        if (picked != selectedTrayAction) {
                            selectedTrayAction = picked
                            if (picked != null) {
                                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            }
                        }
                        invalidate()
                        return true
                    }
                    if (isTrayTargeted) {
                        isTrayTargeted = false
                        selectedTrayAction = null
                    }
                    val eval = RadialMenuGeometry.evaluate(
                        anchorX = anchorX,
                        anchorY = anchorY,
                        pointerX = pointerX,
                        pointerY = pointerY,
                        radius = radialRadiusPx,
                        source = src,
                        bottomGracePx = dp(12f),
                    )
                    if (eval.action != currentAction) {
                        currentAction = eval.action
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    }
                    currentDistanceRatio = eval.distanceRatio
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
                    val wasTrayTargeted = isTrayTargeted
                    val trayAction = selectedTrayAction
                    val finalAction = currentAction
                    endRadialSession()
                    if (wasTrayTargeted) {
                        if (trayAction != null) {
                            listener?.onDragAppAction(src, trayAction)
                        } else {
                            listener?.onFinishRecording(src, RadialAction.CANCEL)
                        }
                    } else {
                        listener?.onFinishRecording(src, finalAction)
                    }
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
                    IdleTouchTarget.ENTER -> listener?.onEnterClick()
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

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        removeCallbacks(memoryRefreshRunnable)
        post(memoryRefreshRunnable)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(backspaceRepeatRunnable)
        removeCallbacks(memoryRefreshRunnable)
        super.onDetachedFromWindow()
    }

    private fun beginRadialSession(source: MicSource, downX: Float, downY: Float) {
        activeSource = source
        prepareTray(source)
        val minAnchorX = radialRadiusPx + dp(8f)
        val maxAnchorX = (width - radialRadiusPx - dp(8f)).coerceAtLeast(minAnchorX)
        anchorX = downX.coerceIn(minAnchorX, maxAnchorX)
        anchorY = barCardRect.top - dp(4f)
        pointerX = downX
        pointerY = downY
        currentAction = RadialMenuGeometry.centerActionFor(source)
        currentDistanceRatio = 0f
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
        clearTray()
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
