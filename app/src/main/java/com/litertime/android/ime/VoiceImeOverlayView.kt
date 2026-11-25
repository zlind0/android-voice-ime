package com.litertime.android.ime

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Debug
import android.util.AttributeSet
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.PathInterpolator
import com.litertime.android.ui.PastelPalette
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

enum class ExpandedPanelMode {
    NONE,
    ENGLISH_KEYBOARD,
    CHINESE_HANDWRITING,
}

/**
 * Top voice bar & radial gesture controller inside [ImeRootContainerView]:
 * - Left: two vertically stacked buttons (collapsed) / side-by-side buttons (expanded):
 *   top/left = English QWERTY (`En` + keyboard icon), bottom/right = Chinese handwriting (`中` + pen icon).
 *   Long-pressing either left button opens the system input method picker.
 * - Center: Whisper Mic and AI (Gemma 4) Mic. Both shrink to half height (`104dp -> 52dp`) along
 *   with scaled mic glyph and `"AI"` text when a traditional input panel expands below.
 * - Right: Backspace and Enter.
 * - Radial semicircle disc, floating knob, and down-drag tray chips are drawn via
 *   [drawRadialOverlay] in a top sibling layer so they always render above any expanded panel.
 */
class VoiceImeOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    interface Listener {
        fun onToggleEnglishKeyboard()
        fun onToggleChineseHandwriting()
        fun onShowSystemImePicker()
        fun onBackspaceClick()
        fun onEnterClick()
        fun onStartRecording(source: MicSource): Boolean
        fun onFinishRecording(source: MicSource, action: RadialAction)
        fun onDragAppAction(source: MicSource, action: DragAppAction)
        fun onCancelActiveGeneration()
        fun onTouchableRegionChanged()
    }

    var listener: Listener? = null

    /** Invoked during panel expand/collapse animation or height changes so [ImeRootContainerView] can layout the traditional panel. */
    var onPanelGeometryChanged: (() -> Unit)? = null

    /** Invoked when radial overlay needs a redraw on the top overlay layer. */
    var onRadialOverlayInvalidate: (() -> Unit)? = null

    var isRadialExpanded: Boolean = false
        private set

    var expandedMode: ExpandedPanelMode = ExpandedPanelMode.NONE
        private set

    private val density = resources.displayMetrics.density
    private fun dp(value: Float): Float = value * density
    private fun sp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

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

    // Geometry constants
    val radialTopSpacePx = dp(140f).toInt()
    private val collapsedTopPaddingPx = dp(12f)
    private val expandedTopPaddingPx = dp(8f)
    private val collapsedMicHeightPx = dp(104f)
    private val expandedMicHeightPx = dp(52f)
    private val collapsedBelowMicPx = dp(48f)
    private val expandedBelowMicPx = dp(24f)

    private val radialRadiusPx = dp(132f)
    private val knobRadiusPx = dp(28f)
    private val barPaddingHPx = dp(14f)
    private val barInnerGapPx = dp(8f)
    private val memoryRefreshIntervalMs = 2_000L

    private var safeBottomInsetPx: Int = 0
    private var safeLeftInsetPx: Int = 0
    private var safeRightInsetPx: Int = 0

    // Panel expansion animation state
    private var panelExpandProgress: Float = 0f
    private var currentPanelHeightPx: Float = 0f
    private var targetPanelHeightPx: Float = dp(240f)
    private var maxSupportedPanelHeightPx: Float = dp(340f)
    private var panelAnimator: ValueAnimator? = null
    private var heightSwitchAnimator: ValueAnimator? = null

    // Loaded state indicators
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
    private var currentDistanceRatio: Float = 0f
    private var expandProgress: Float = 0f
    private var voiceAmplitude: Float = 0f

    private var trayActions: List<DragAppAction> = emptyList()
    private var trayChips: List<Pair<DragAppAction, RectF>> = emptyList()
    private var selectedTrayAction: DragAppAction? = null
    private var isTrayTargeted: Boolean = false

    private var radialAnimator: ValueAnimator? = null

    // Hit rectangles
    private val englishBtnRect = RectF()
    private val handwritingBtnRect = RectF()
    private val whisperBtnRect = RectF()
    private val gemmaBtnRect = RectF()
    private val backspaceBtnRect = RectF()
    private val enterBtnRect = RectF()
    private val barCardRect = RectF()
    private val cancelBannerRect = RectF()
    private val cancelBannerButtonRect = RectF()
    private val scratchRect = RectF()
    private val scratchRect2 = RectF()
    private val scratchPath = Path()

    private enum class IdleTouchTarget {
        NONE,
        ENGLISH,
        HANDWRITING,
        BACKSPACE,
        ENTER,
        CANCEL_BANNER,
    }

    private var idleTouchTarget: IdleTouchTarget = IdleTouchTarget.NONE

    private val longPressTimeoutMs = 500L
    private var leftLongPressTriggered = false
    private val leftLongPressRunnable = Runnable {
        if (idleTouchTarget == IdleTouchTarget.ENGLISH || idleTouchTarget == IdleTouchTarget.HANDWRITING) {
            leftLongPressTriggered = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            idleTouchTarget = IdleTouchTarget.NONE
            invalidate()
            listener?.onShowSystemImePicker()
        }
    }

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

    // Paints (Dark Pastel palette, borderless)
    private val barCardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PastelPalette.BG_CANVAS
        style = Paint.Style.FILL
    }
    private val sideBtnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PastelPalette.SURFACE_SIDE_BTN
        style = Paint.Style.FILL
    }
    private val sideBtnPressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PastelPalette.SURFACE_PRESSED
        style = Paint.Style.FILL
    }
    private val sideBtnActivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PastelPalette.SKY_BLUE
        style = Paint.Style.FILL
    }
    private val sideBtnActivePressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PastelPalette.SKY_BLUE_PRESSED
        style = Paint.Style.FILL
    }
    private val micInactivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PastelPalette.SURFACE_MIC_INACTIVE
        style = Paint.Style.FILL
    }
    private val micInactivePressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PastelPalette.SURFACE_PRESSED
        style = Paint.Style.FILL
    }
    private val micLoadedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PastelPalette.SKY_BLUE
        style = Paint.Style.FILL
    }
    private val micLoadedPressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PastelPalette.SKY_BLUE_PRESSED
        style = Paint.Style.FILL
    }
    private val enterBtnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PastelPalette.PINK_LIGHT
        style = Paint.Style.FILL
    }
    private val enterBtnPressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PastelPalette.PINK_PRESSED
        style = Paint.Style.FILL
    }
    private val backspaceBtnPressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PastelPalette.SURFACE_BACKSPACE_PRESSED
        style = Paint.Style.FILL
    }
    private val aiTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PastelPalette.TEXT_ON_DARK_SKY
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        textAlign = Paint.Align.LEFT
    }
    private val leftBtnLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PastelPalette.TEXT_ON_DARK_SKY
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val lightGlyphStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PastelPalette.TEXT_ON_DARK_SKY
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val lightGlyphFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PastelPalette.TEXT_ON_DARK_SKY
        style = Paint.Style.FILL
    }
    private val darkGlyphStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PastelPalette.INK_ON_PASTEL
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val darkGlyphFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PastelPalette.INK_ON_PASTEL
        style = Paint.Style.FILL
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PastelPalette.TEXT_ON_DARK_SKY
        style = Paint.Style.STROKE
        strokeWidth = dp(1.75f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val iconFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PastelPalette.TEXT_ON_DARK_SKY
        style = Paint.Style.FILL
    }
    private val iconDarkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PastelPalette.INK_ON_PASTEL
        style = Paint.Style.STROKE
        strokeWidth = dp(1.75f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val iconDarkFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PastelPalette.INK_ON_PASTEL
        style = Paint.Style.FILL
    }
    private val enterIconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PastelPalette.INK_ON_PINK
        style = Paint.Style.STROKE
        strokeWidth = dp(1.85f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val pinkGlyphStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PastelPalette.INK_ON_PINK
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val pinkGlyphFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PastelPalette.INK_ON_PINK
        style = Paint.Style.FILL
    }
    private val roseIconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PastelPalette.INK_ON_ROSE
        style = Paint.Style.STROKE
        strokeWidth = dp(1.8f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val roseIconFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PastelPalette.INK_ON_ROSE
        style = Paint.Style.FILL
    }
    private val discBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PastelPalette.DISC_BG
        style = Paint.Style.FILL
    }
    private val sectorHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PastelPalette.SECTOR_HIGHLIGHT
        style = Paint.Style.FILL
    }
    private val sectorCancelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PastelPalette.SECTOR_CANCEL
        style = Paint.Style.FILL
    }
    private val centerHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PastelPalette.CENTER_HIGHLIGHT
        style = Paint.Style.FILL
    }
    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PastelPalette.SECTOR_DIVIDER
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val sectorTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PastelPalette.TEXT_PRIMARY
        textSize = sp(11.5f)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val cancelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PastelPalette.INK_ON_ROSE
        textSize = sp(11.5f)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val chipTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PastelPalette.TEXT_PRIMARY
        textSize = sp(11.5f)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PastelPalette.SKY_BLUE
        style = Paint.Style.FILL
    }
    private val knobCancelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PastelPalette.ROSE_DANGER
        style = Paint.Style.FILL
    }
    private val knobGemmaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PastelPalette.SKY_BLUE
        style = Paint.Style.FILL
    }
    private val chipBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PastelPalette.SURFACE_CHIP_IDLE
        style = Paint.Style.FILL
    }
    private val chipSelectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PastelPalette.SKY_BLUE
        style = Paint.Style.FILL
    }
    private val chipDangerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PastelPalette.ROSE_DANGER
        style = Paint.Style.FILL
    }
    private val amplitudeRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PastelPalette.AMPLITUDE_RING
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
    }
    private val pillBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PastelPalette.PILL_BG
        style = Paint.Style.FILL
    }
    private val pillTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PastelPalette.TEXT_PRIMARY
        textSize = sp(12f)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val memoryTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PastelPalette.TEXT_MUTED
        textSize = sp(10f)
        textAlign = Paint.Align.RIGHT
    }

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

    /** Re-reads the resolved palette into the cached paints after the keyboard theme changed. */
    fun refreshPalette() {
        barCardPaint.color = PastelPalette.BG_CANVAS
        sideBtnPaint.color = PastelPalette.SURFACE_SIDE_BTN
        sideBtnPressedPaint.color = PastelPalette.SURFACE_PRESSED
        sideBtnActivePaint.color = PastelPalette.SKY_BLUE
        sideBtnActivePressedPaint.color = PastelPalette.SKY_BLUE_PRESSED
        micInactivePaint.color = PastelPalette.SURFACE_MIC_INACTIVE
        micInactivePressedPaint.color = PastelPalette.SURFACE_PRESSED
        micLoadedPaint.color = PastelPalette.SKY_BLUE
        micLoadedPressedPaint.color = PastelPalette.SKY_BLUE_PRESSED
        enterBtnPaint.color = PastelPalette.PINK_LIGHT
        enterBtnPressedPaint.color = PastelPalette.PINK_PRESSED
        backspaceBtnPressedPaint.color = PastelPalette.SURFACE_BACKSPACE_PRESSED
        aiTextPaint.color = PastelPalette.TEXT_ON_DARK_SKY
        leftBtnLabelPaint.color = PastelPalette.TEXT_ON_DARK_SKY
        lightGlyphStrokePaint.color = PastelPalette.TEXT_ON_DARK_SKY
        lightGlyphFillPaint.color = PastelPalette.TEXT_ON_DARK_SKY
        darkGlyphStrokePaint.color = PastelPalette.INK_ON_PASTEL
        darkGlyphFillPaint.color = PastelPalette.INK_ON_PASTEL
        iconPaint.color = PastelPalette.TEXT_ON_DARK_SKY
        iconFillPaint.color = PastelPalette.TEXT_ON_DARK_SKY
        iconDarkPaint.color = PastelPalette.INK_ON_PASTEL
        iconDarkFillPaint.color = PastelPalette.INK_ON_PASTEL
        enterIconPaint.color = PastelPalette.INK_ON_PINK
        pinkGlyphStrokePaint.color = PastelPalette.INK_ON_PINK
        pinkGlyphFillPaint.color = PastelPalette.INK_ON_PINK
        roseIconPaint.color = PastelPalette.INK_ON_ROSE
        roseIconFillPaint.color = PastelPalette.INK_ON_ROSE
        discBgPaint.color = PastelPalette.DISC_BG
        sectorHighlightPaint.color = PastelPalette.SECTOR_HIGHLIGHT
        sectorCancelPaint.color = PastelPalette.SECTOR_CANCEL
        centerHighlightPaint.color = PastelPalette.CENTER_HIGHLIGHT
        dividerPaint.color = PastelPalette.SECTOR_DIVIDER
        sectorTextPaint.color = PastelPalette.TEXT_PRIMARY
        cancelTextPaint.color = PastelPalette.INK_ON_ROSE
        chipTextPaint.color = PastelPalette.TEXT_PRIMARY
        knobPaint.color = PastelPalette.SKY_BLUE
        knobCancelPaint.color = PastelPalette.ROSE_DANGER
        knobGemmaPaint.color = PastelPalette.SKY_BLUE
        chipBgPaint.color = PastelPalette.SURFACE_CHIP_IDLE
        chipSelectedPaint.color = PastelPalette.SKY_BLUE
        chipDangerPaint.color = PastelPalette.ROSE_DANGER
        amplitudeRingPaint.color = PastelPalette.AMPLITUDE_RING
        pillBgPaint.color = PastelPalette.PILL_BG
        pillTextPaint.color = PastelPalette.TEXT_PRIMARY
        memoryTextPaint.color = PastelPalette.TEXT_MUTED
        invalidate()
        onRadialOverlayInvalidate?.invoke()
    }

    fun getMaxWindowHeightPx(): Int {
        val expandedHeader = expandedTopPaddingPx + expandedMicHeightPx + expandedBelowMicPx
        val collapsedHeader = collapsedTopPaddingPx + collapsedMicHeightPx + collapsedBelowMicPx
        val maxContent = maxOf(collapsedHeader, expandedHeader + maxSupportedPanelHeightPx)
        return (radialTopSpacePx + maxContent + safeBottomInsetPx).roundToInt()
    }

    fun updateMaxSupportedPanelHeightDp(maxHeightDp: Int) {
        val clamped = dp(maxHeightDp.toFloat()).coerceAtLeast(dp(200f))
        if (abs(clamped - maxSupportedPanelHeightPx) > 1f) {
            maxSupportedPanelHeightPx = clamped
            requestLayout()
        }
    }

    fun setExpandedPanel(mode: ExpandedPanelMode, targetHeightDp: Int) {
        val nextPanelHeightPx = if (mode == ExpandedPanelMode.NONE) {
            0f
        } else {
            dp(targetHeightDp.toFloat()).coerceAtLeast(dp(180f))
        }
        val wasCollapsed = expandedMode == ExpandedPanelMode.NONE && panelExpandProgress == 0f
        val willCollapse = mode == ExpandedPanelMode.NONE

        if (mode == expandedMode && abs(nextPanelHeightPx - targetPanelHeightPx) < 1f) {
            return
        }

        expandedMode = mode
        panelAnimator?.cancel()
        heightSwitchAnimator?.cancel()

        if (!willCollapse) {
            targetPanelHeightPx = nextPanelHeightPx
        }

        if (willCollapse) {
            // Update touchable region after collapse animation finishes so host app doesn't jump mid-slide.
            val startP = panelExpandProgress
            val startH = currentPanelHeightPx
            panelAnimator = ValueAnimator.ofFloat(startP, 0f).apply {
                duration = 200L
                interpolator = PathInterpolator(0.4f, 0f, 0.2f, 1f)
                addUpdateListener { anim ->
                    val p = anim.animatedValue as Float
                    panelExpandProgress = p
                    currentPanelHeightPx = if (startP > 0.001f) startH * (p / startP) else 0f
                    recomputeAndNotifyGeometry()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        panelExpandProgress = 0f
                        currentPanelHeightPx = 0f
                        recomputeAndNotifyGeometry()
                        listener?.onTouchableRegionChanged()
                    }
                })
                start()
            }
        } else if (wasCollapsed || panelExpandProgress < 0.99f) {
            val startP = panelExpandProgress
            val startH = currentPanelHeightPx
            panelAnimator = ValueAnimator.ofFloat(startP, 1f).apply {
                duration = 220L
                interpolator = PathInterpolator(0.4f, 0f, 0.2f, 1f)
                addUpdateListener { anim ->
                    val p = anim.animatedValue as Float
                    panelExpandProgress = p
                    val t = if (1f - startP > 0.001f) (p - startP) / (1f - startP) else 1f
                    currentPanelHeightPx = lerp(startH, targetPanelHeightPx, t.coerceIn(0f, 1f))
                    recomputeAndNotifyGeometry()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        panelExpandProgress = 1f
                        currentPanelHeightPx = targetPanelHeightPx
                        recomputeAndNotifyGeometry()
                        listener?.onTouchableRegionChanged()
                    }
                })
                start()
            }
            listener?.onTouchableRegionChanged()
        } else {
            // Switching directly between English keyboard and Chinese handwriting while already expanded.
            val startH = currentPanelHeightPx
            heightSwitchAnimator = ValueAnimator.ofFloat(startH, targetPanelHeightPx).apply {
                duration = 160L
                interpolator = PathInterpolator(0.4f, 0f, 0.2f, 1f)
                addUpdateListener { anim ->
                    currentPanelHeightPx = anim.animatedValue as Float
                    recomputeAndNotifyGeometry()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        currentPanelHeightPx = targetPanelHeightPx
                        recomputeAndNotifyGeometry()
                        listener?.onTouchableRegionChanged()
                    }
                })
                start()
            }
            listener?.onTouchableRegionChanged()
        }
    }

    fun collapseImmediate() {
        panelAnimator?.cancel()
        heightSwitchAnimator?.cancel()
        expandedMode = ExpandedPanelMode.NONE
        panelExpandProgress = 0f
        currentPanelHeightPx = 0f
        recomputeAndNotifyGeometry()
        listener?.onTouchableRegionChanged()
    }

    fun getCurrentTraditionalPanelTop(): Int =
        (barCardRect.bottom - safeBottomInsetPx - currentPanelHeightPx).roundToInt().coerceAtLeast(0)

    fun getCurrentTraditionalPanelHeight(): Int = currentPanelHeightPx.roundToInt().coerceAtLeast(0)

    fun getPanelExpandProgress(): Float = panelExpandProgress

    fun updateSystemInsets(bottomPx: Int, leftPx: Int, rightPx: Int) {
        if (safeBottomInsetPx == bottomPx && safeLeftInsetPx == leftPx && safeRightInsetPx == rightPx) {
            return
        }
        safeBottomInsetPx = bottomPx.coerceAtLeast(0)
        safeLeftInsetPx = leftPx.coerceAtLeast(0)
        safeRightInsetPx = rightPx.coerceAtLeast(0)
        if (width > 0 && height > 0) {
            computeRects(width.toFloat(), height.toFloat())
        }
        requestLayout()
        invalidate()
        onPanelGeometryChanged?.invoke()
        listener?.onTouchableRegionChanged()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val specHeight = MeasureSpec.getSize(heightMeasureSpec)
        val desired = getMaxWindowHeightPx()
        val finalH = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.AT_MOST) {
            minOf(desired, specHeight)
        } else {
            desired
        }
        setMeasuredDimension(width, finalH)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        computeRects(w.toFloat(), h.toFloat())
        onPanelGeometryChanged?.invoke()
    }

    private fun recomputeAndNotifyGeometry() {
        if (width > 0 && height > 0) {
            computeRects(width.toFloat(), height.toFloat())
        }
        invalidate()
        onPanelGeometryChanged?.invoke()
    }

    private fun computeRects(w: Float, h: Float) {
        val p = panelExpandProgress.coerceIn(0f, 1f)
        val contentBottom = (h - safeBottomInsetPx).coerceAtLeast(dp(1f))
        val topPad = lerp(collapsedTopPaddingPx, expandedTopPaddingPx, p)
        val micHeight = lerp(collapsedMicHeightPx, expandedMicHeightPx, p)
        val belowMic = lerp(collapsedBelowMicPx, expandedBelowMicPx, p)

        val totalContentHeight = topPad + micHeight + belowMic + currentPanelHeightPx
        val panelTop = (contentBottom - totalContentHeight).coerceAtLeast(0f)
        barCardRect.set(0f, panelTop, w, h)

        val micTop = panelTop + topPad
        val micBottom = micTop + micHeight

        val contentLeft = safeLeftInsetPx + barPaddingHPx
        val contentRight = (w - safeRightInsetPx - barPaddingHPx).coerceAtLeast(contentLeft + dp(160f))

        // Collapsed (p=0): left 2x1 stack (56dp wide), right 2x1 stack (48dp wide)
        val colLeftW = dp(56f)
        val colRightW = dp(48f)
        val stackGap = dp(6f)
        val collapsedRowH = (collapsedMicHeightPx - stackGap) / 2f
        val cTop = micTop
        val cBot = micBottom

        val enCollapsed = RectF(contentLeft, cTop, contentLeft + colLeftW, cTop + collapsedRowH)
        val zhCollapsed = RectF(contentLeft, cBot - collapsedRowH, contentLeft + colLeftW, cBot)
        val bsCollapsed = RectF(contentRight - colRightW, cTop, contentRight, cTop + collapsedRowH)
        val entCollapsed = RectF(contentRight - colRightW, cBot - collapsedRowH, contentRight, cBot)

        // Expanded (p=1): left 1x2 horizontal pair, right 1x2 horizontal pair
        val expBtnW = dp(44f)
        val expBtnH = (expandedMicHeightPx - dp(4f)).coerceAtLeast(dp(36f))
        val expPairGap = dp(6f)
        val expCenterY = (micTop + micBottom) / 2f
        val expTop = expCenterY - expBtnH / 2f
        val expBot = expCenterY + expBtnH / 2f

        val enExpanded = RectF(contentLeft, expTop, contentLeft + expBtnW, expBot)
        val zhExpanded = RectF(enExpanded.right + expPairGap, expTop, enExpanded.right + expPairGap + expBtnW, expBot)

        val entExpanded = RectF(contentRight - expBtnW, expTop, contentRight, expBot)
        val bsExpanded = RectF(entExpanded.left - expPairGap - expBtnW, expTop, entExpanded.left - expPairGap, expBot)

        lerpRect(enCollapsed, enExpanded, p, englishBtnRect)
        lerpRect(zhCollapsed, zhExpanded, p, handwritingBtnRect)
        lerpRect(bsCollapsed, bsExpanded, p, backspaceBtnRect)
        lerpRect(entCollapsed, entExpanded, p, enterBtnRect)

        val leftMaxRight = maxOf(englishBtnRect.right, handwritingBtnRect.right)
        val rightMinLeft = minOf(backspaceBtnRect.left, enterBtnRect.left)
        val micsLeft = leftMaxRight + barInnerGapPx
        val micsRight = rightMinLeft - barInnerGapPx
        val totalMicWidth = (micsRight - micsLeft - barInnerGapPx).coerceAtLeast(dp(64f))
        val eachMicWidth = totalMicWidth / 2f

        whisperBtnRect.set(micsLeft, micTop, micsLeft + eachMicWidth, micBottom)
        gemmaBtnRect.set(whisperBtnRect.right + barInnerGapPx, micTop, micsRight, micBottom)

        // Status banner & memory readout between micBottom and traditionalPanelTop
        val headerAreaBottom = micBottom + belowMic
        val memoryReserveWidth = memoryTextPaint.measureText("内存 9999 MB")
        val memoryLeft = contentRight - memoryReserveWidth
        val cancelRight = memoryLeft - dp(8f)

        val stripBottom = headerAreaBottom - dp(2f)
        val stripTop = stripBottom - dp(20f)
        cancelBannerButtonRect.set(
            (cancelRight - dp(46f)).coerceAtLeast(contentLeft),
            stripTop,
            cancelRight.coerceAtLeast(contentLeft),
            stripBottom,
        )
        val collapsedBannerTop = micBottom + dp(1f)
        val collapsedBannerBottom = collapsedBannerTop + dp(20f)
        val bannerTop = lerp(collapsedBannerTop, stripTop, p)
        val bannerBottom = lerp(collapsedBannerBottom, stripBottom, p)
        val bannerRight = if (showCancelBanner) {
            (cancelBannerButtonRect.left - dp(6f)).coerceAtLeast(contentLeft + dp(40f))
        } else {
            (memoryLeft - dp(8f)).coerceAtLeast(contentLeft + dp(40f))
        }
        cancelBannerRect.set(contentLeft + dp(2f), bannerTop, bannerRight, bannerBottom)
    }

    fun getTargetTouchableTop(): Int {
        if (height <= 0) return 0
        if (expandedMode == ExpandedPanelMode.NONE && panelExpandProgress < 0.01f) {
            return barCardRect.top.toInt().coerceIn(0, height)
        }
        val contentBottom = (height - safeBottomInsetPx).coerceAtLeast(0)
        val targetPanelTop = if (expandedMode == ExpandedPanelMode.NONE) {
            contentBottom - (collapsedTopPaddingPx + collapsedMicHeightPx + collapsedBelowMicPx).toInt()
        } else {
            contentBottom - (expandedTopPaddingPx + expandedMicHeightPx + expandedBelowMicPx + targetPanelHeightPx).toInt()
        }
        return targetPanelTop.coerceIn(0, height)
    }

    fun getIdleTouchableBottom(): Int = if (height <= 0) 0 else height

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
        if (width > 0 && height > 0) {
            computeRects(width.toFloat(), height.toFloat())
        }
        if (hadBanner != hasBanner) {
            listener?.onTouchableRegionChanged()
        }
        invalidate()
    }

    fun updateAmplitude(amplitude: Float) {
        voiceAmplitude = amplitude.coerceIn(0f, 1f)
        if (isRadialExpanded) {
            onRadialOverlayInvalidate?.invoke()
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
    }

    /** Drawn by the top-most sibling overlay layer inside [ImeRootContainerView]. */
    fun drawRadialOverlay(canvas: Canvas) {
        if (isRadialExpanded && activeSource != null) {
            drawTrayTargets(canvas)
            drawRadialDisc(canvas, activeSource!!)
        }
    }

    private fun drawStatusBanner(canvas: Canvas) {
        val prevAlign = pillTextPaint.textAlign
        pillTextPaint.textAlign = Paint.Align.LEFT
        val textY = cancelBannerRect.centerY() - (pillTextPaint.descent() + pillTextPaint.ascent()) / 2f
        canvas.save()
        canvas.clipRect(cancelBannerRect)
        canvas.drawText(statusMessage, cancelBannerRect.left, textY, pillTextPaint)
        canvas.restore()
        pillTextPaint.textAlign = prevAlign

        if (showCancelBanner) {
            canvas.drawRoundRect(cancelBannerButtonRect, dp(10f), dp(10f), knobCancelPaint)
            val cy = cancelBannerButtonRect.centerY() - (cancelTextPaint.descent() + cancelTextPaint.ascent()) / 2f
            canvas.drawText("停止", cancelBannerButtonRect.centerX(), cy, cancelTextPaint)
        }
    }

    private fun drawMemoryUsage(canvas: Canvas) {
        val label = if (memoryUsageMb > 0) formatMemoryLabel(memoryUsageMb) else "内存 --"
        val x = width - safeRightInsetPx - barPaddingHPx
        val p = panelExpandProgress.coerceIn(0f, 1f)
        val headerBottom = barCardRect.top +
            lerp(collapsedTopPaddingPx, expandedTopPaddingPx, p) +
            lerp(collapsedMicHeightPx, expandedMicHeightPx, p) +
            lerp(collapsedBelowMicPx, expandedBelowMicPx, p)
        val baseline = headerBottom - dp(5f)
        canvas.drawText(label, x, baseline, memoryTextPaint)
    }

    private fun formatMemoryLabel(mb: Int): String =
        if (mb >= 1024) String.format(Locale.US, "内存 %.1f GB", mb / 1024f) else "内存 $mb MB"

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
        if (residentPages == null || residentPages <= 0L) null
        else ((residentPages * pageSizeBytes) / (1024L * 1024L)).toInt()
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
        canvas.drawRect(barCardRect, barCardPaint)

        val p = panelExpandProgress.coerceIn(0f, 1f)
        val sideCorner = lerp(dp(14f), dp(12f), p)

        // Left button 1: English QWERTY (Keyboard icon + "En")
        val enActive = expandedMode == ExpandedPanelMode.ENGLISH_KEYBOARD
        val enPressed = idleTouchTarget == IdleTouchTarget.ENGLISH
        val enPaint = when {
            enActive && enPressed -> sideBtnActivePressedPaint
            enActive -> sideBtnActivePaint
            enPressed -> sideBtnPressedPaint
            else -> sideBtnPaint
        }
        canvas.drawRoundRect(englishBtnRect, sideCorner, sideCorner, enPaint)
        drawEnglishButtonContent(canvas, englishBtnRect, isActive = enActive)

        // Left button 2: Chinese Handwriting (Pen icon + "中")
        val zhActive = expandedMode == ExpandedPanelMode.CHINESE_HANDWRITING
        val zhPressed = idleTouchTarget == IdleTouchTarget.HANDWRITING
        val zhPaint = when {
            zhActive && zhPressed -> sideBtnActivePressedPaint
            zhActive -> sideBtnActivePaint
            zhPressed -> sideBtnPressedPaint
            else -> sideBtnPaint
        }
        canvas.drawRoundRect(handwritingBtnRect, sideCorner, sideCorner, zhPaint)
        drawHandwritingButtonContent(canvas, handwritingBtnRect, isActive = zhActive)

        // Right column/pair: Backspace & Enter
        val bsPaint = if (idleTouchTarget == IdleTouchTarget.BACKSPACE) {
            backspaceBtnPressedPaint
        } else {
            sideBtnPaint
        }
        canvas.drawRoundRect(backspaceBtnRect, sideCorner, sideCorner, bsPaint)
        drawBackspaceIcon(canvas, backspaceBtnRect.centerX(), backspaceBtnRect.centerY())

        val entPaint = if (idleTouchTarget == IdleTouchTarget.ENTER) enterBtnPressedPaint else enterBtnPaint
        canvas.drawRoundRect(enterBtnRect, sideCorner, sideCorner, entPaint)
        drawEnterIcon(canvas, enterBtnRect.centerX(), enterBtnRect.centerY())

        // Mic buttons: shrink height 104dp -> 52dp and scale mic icon + "AI" text proportionally.
        val micGlyphHeight = computeMicGlyphHeight(gemmaBtnRect, p)
        val micRadius = lerp(dp(26f), dp(18f), p)

        val whisperPressed = isRadialExpanded && activeSource == MicSource.WHISPER
        val whisperBg = when {
            whisperLoaded && whisperPressed -> micLoadedPressedPaint
            whisperLoaded -> micLoadedPaint
            whisperPressed -> micInactivePressedPaint
            else -> micInactivePaint
        }
        canvas.drawRoundRect(whisperBtnRect, micRadius, micRadius, whisperBg)
        drawScaledMicGlyph(
            canvas = canvas,
            cx = whisperBtnRect.centerX(),
            centerY = whisperBtnRect.centerY(),
            heightPx = micGlyphHeight,
            strokePaint = if (whisperLoaded) darkGlyphStrokePaint else lightGlyphStrokePaint,
            fillPaint = if (whisperLoaded) darkGlyphFillPaint else lightGlyphFillPaint,
        )

        val gemmaPressed = isRadialExpanded && activeSource == MicSource.GEMMA
        val gemmaBg = when {
            gemmaLoaded && gemmaPressed -> enterBtnPressedPaint
            gemmaLoaded -> enterBtnPaint
            gemmaPressed -> micInactivePressedPaint
            else -> micInactivePaint
        }
        canvas.drawRoundRect(gemmaBtnRect, micRadius, micRadius, gemmaBg)
        drawAiMicContent(canvas, gemmaBtnRect, micGlyphHeight, p, isLoaded = gemmaLoaded)
    }

    private fun drawEnglishButtonContent(canvas: Canvas, rect: RectF, isActive: Boolean) {
        val cx = rect.centerX()
        val cy = rect.centerY()
        val iconCy = cy - dp(7f)
        drawCompactKeyboardIcon(canvas, cx, iconCy, isActive)

        leftBtnLabelPaint.color = if (isActive) PastelPalette.INK_ON_PASTEL else PastelPalette.TEXT_ON_DARK_SKY
        leftBtnLabelPaint.textSize = sp(11.5f)
        val textY = cy + dp(12f)
        canvas.drawText("En", cx, textY, leftBtnLabelPaint)
    }

    private fun drawHandwritingButtonContent(canvas: Canvas, rect: RectF, isActive: Boolean) {
        val cx = rect.centerX()
        val cy = rect.centerY()
        val iconCy = cy - dp(7.5f)
        drawPenIcon(canvas, cx, iconCy, isActive)

        leftBtnLabelPaint.color = if (isActive) PastelPalette.INK_ON_PASTEL else PastelPalette.TEXT_ON_DARK_SKY
        leftBtnLabelPaint.textSize = sp(11.5f)
        val textY = cy + dp(12.5f)
        canvas.drawText("中", cx, textY, leftBtnLabelPaint)
    }

    private fun drawCompactKeyboardIcon(canvas: Canvas, cx: Float, cy: Float, isActive: Boolean) {
        val stroke = if (isActive) iconDarkPaint else iconPaint
        val fill = if (isActive) iconDarkFillPaint else iconFillPaint
        val w = dp(18f)
        val h = dp(11.5f)
        scratchRect.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
        canvas.drawRoundRect(scratchRect, dp(2f), dp(2f), stroke)
        val dotY = cy - dp(2f)
        for (dx in listOf(-dp(4.8f), 0f, dp(4.8f))) {
            canvas.drawCircle(cx + dx, dotY, dp(1.0f), fill)
        }
        val barY = cy + dp(2f)
        canvas.drawLine(cx - dp(4f), barY, cx + dp(4f), barY, stroke)
    }

    private fun drawPenIcon(canvas: Canvas, cx: Float, cy: Float, isActive: Boolean) {
        val stroke = if (isActive) iconDarkPaint else iconPaint
        // Clean slanted fountain/stylus pen icon
        scratchPath.reset()
        val tipX = cx - dp(6.5f)
        val tipY = cy + dp(6.5f)
        val topX = cx + dp(5.5f)
        val topY = cy - dp(6.5f)
        scratchPath.moveTo(tipX, tipY)
        scratchPath.lineTo(tipX + dp(4.5f), tipY - dp(1.2f))
        scratchPath.lineTo(topX + dp(2.2f), topY + dp(2.2f))
        scratchPath.lineTo(topX - dp(1.2f), topY - dp(1.2f))
        scratchPath.lineTo(tipX + dp(1.2f), tipY - dp(4.5f))
        scratchPath.close()
        canvas.drawPath(scratchPath, stroke)
        // Pen tip nib line
        canvas.drawLine(tipX, tipY, tipX + dp(2.2f), tipY - dp(2.2f), stroke)
    }

    private fun computeMicGlyphHeight(rect: RectF, expandP: Float): Float {
        val vMargin = lerp(dp(16f), dp(10f), expandP)
        val maxByHeight = (rect.height() - vMargin).coerceAtMost(dp(66f))
        if (maxByHeight <= 0f) return 0f

        val horizontalPadding = lerp(dp(14f), dp(8f), expandP)
        val contentWidth = (rect.width() - horizontalPadding * 2f).coerceAtLeast(0f)
        val gap = lerp(dp(10f), dp(5f), expandP)
        val glyphWidthRatio = 12f / 16.5f

        val probeSize = 100f
        aiTextPaint.textSize = probeSize
        val probeBounds = Rect()
        aiTextPaint.getTextBounds("AI", 0, 2, probeBounds)
        val inkRatio = (probeBounds.height() / probeSize).coerceAtLeast(0.01f)
        val aiWidthAtProbe = aiTextPaint.measureText("AI")

        var glyphHeight = maxByHeight
        val glyphWidth = glyphHeight * glyphWidthRatio
        val aiTextSize = (glyphHeight / inkRatio) / 2f
        val aiWidth = aiWidthAtProbe * (aiTextSize / probeSize)
        val totalWidth = glyphWidth + gap + aiWidth
        if (totalWidth > contentWidth && totalWidth > 0f) {
            glyphHeight *= contentWidth / totalWidth
        }
        return (glyphHeight * 0.5f).coerceAtLeast(0f)
    }

    private fun drawAiMicContent(
        canvas: Canvas,
        rect: RectF,
        glyphHeight: Float,
        expandP: Float,
        isLoaded: Boolean,
    ) {
        if (glyphHeight <= 0f) return
        val gap = lerp(dp(10f), dp(5f), expandP)
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

        drawScaledMicGlyph(
            canvas = canvas,
            cx = startX + glyphWidth / 2f,
            centerY = centerY,
            heightPx = glyphHeight,
            strokePaint = if (isLoaded) pinkGlyphStrokePaint else lightGlyphStrokePaint,
            fillPaint = if (isLoaded) pinkGlyphFillPaint else lightGlyphFillPaint,
        )

        aiTextPaint.color = if (isLoaded) PastelPalette.INK_ON_PINK else PastelPalette.TEXT_ON_DARK_SKY
        aiTextPaint.textSize = aiTextSize
        val textBounds = Rect()
        aiTextPaint.getTextBounds("AI", 0, 2, textBounds)
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
        val r = dp(8.5f)
        val arrowX = cx - dp(5f)
        val shaftY = cy + dp(3f)
        val rightX = cx + dp(5.5f)
        canvas.drawLine(rightX, cy - r, rightX, shaftY, enterIconPaint)
        canvas.drawLine(rightX, shaftY, arrowX, shaftY, enterIconPaint)
        canvas.drawLine(arrowX, shaftY, arrowX + dp(4.5f), shaftY - dp(4.5f), enterIconPaint)
        canvas.drawLine(arrowX, shaftY, arrowX + dp(4.5f), shaftY + dp(4.5f), enterIconPaint)
    }

    private fun dragActionsFor(source: MicSource): List<DragAppAction> =
        RadialMenuGeometry.dragAppActions(
            source = source,
            whisperLoaded = whisperLoaded,
            gemmaLoaded = gemmaLoaded,
            gemmaLoadedWithGpu = gemmaLoadedWithGpu,
        )

    private fun micRowBottom(): Float = whisperBtnRect.bottom

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

    private fun layoutTrayChips(actions: List<DragAppAction>) {
        trayChips = emptyList()
        if (actions.isEmpty() || width <= 0) return
        val left = safeLeftInsetPx + barPaddingHPx
        val right = (width - safeRightInsetPx - barPaddingHPx).coerceAtLeast(left + dp(120f))
        val chipTop = micRowBottom() + dp(4f)
        val maxBottom = barCardRect.bottom - safeBottomInsetPx - dp(2f)
        val chipBottom = (chipTop + dp(40f)).coerceAtMost(maxBottom)
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
            chipTextPaint.color = when {
                !selected -> PastelPalette.TEXT_PRIMARY
                isDanger -> PastelPalette.INK_ON_ROSE
                else -> PastelPalette.INK_ON_PASTEL
            }
            canvas.drawRoundRect(rect, rect.height() / 2f, rect.height() / 2f, background)
            val previousTextSize = chipTextPaint.textSize
            val maxTextWidth = rect.width() - dp(12f)
            val textWidth = chipTextPaint.measureText(action.label)
            if (maxTextWidth > 0f && textWidth > maxTextWidth) {
                chipTextPaint.textSize = previousTextSize * (maxTextWidth / textWidth)
            }
            val cy = rect.centerY() - (chipTextPaint.descent() + chipTextPaint.ascent()) / 2f
            canvas.drawText(action.label, rect.centerX(), cy, chipTextPaint)
            chipTextPaint.textSize = previousTextSize
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
        val basePath = Path().apply {
            addArc(scratchRect, 180f, 180f)
            close()
        }
        canvas.drawPath(basePath, discBgPaint)

        val innerR = currentR * RadialMenuGeometry.CENTER_ZONE_RATIO
        if (isTrayTargeted) {
            // Suppressed while targeted in lower tray
        } else if (currentAction == RadialAction.CANCEL) {
            val sweepAngle = if (source == MicSource.WHISPER) 180f else 45f
            drawRingSector(canvas, anchorX, anchorY, innerR, currentR, 180f, sweepAngle, sectorCancelPaint)
        } else if (source == MicSource.GEMMA) {
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
            scratchRect.set(anchorX - innerR, anchorY - innerR, anchorX + innerR, anchorY + innerR)
            val centerPath = Path().apply {
                addArc(scratchRect, 180f, 180f)
                close()
            }
            canvas.drawPath(centerPath, centerHighlightPaint)
        }

        scratchRect.set(anchorX - innerR, anchorY - innerR, anchorX + innerR, anchorY + innerR)
        canvas.drawArc(scratchRect, 180f, 180f, false, dividerPaint)

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
            drawSectorLabel(canvas, anchorX, anchorY, currentR * 0.78f, 67.5f, "截屏")
            drawSectorLabel(canvas, anchorX, anchorY, currentR * 0.78f, 22.5f, "指令")
            drawSectorLabel(canvas, anchorX, anchorY, currentR * 0.28f, 90f, "普通纠错")
        }

        if (!isTrayTargeted) {
            val hintText = when (currentAction) {
                RadialAction.CANCEL -> "松手取消本次语音"
                RadialAction.WHISPER_TRANSCRIBE -> if (source == MicSource.GEMMA) {
                    "正在聆听 · 普通纠错"
                } else {
                    "正在聆听 · Whisper 原样输出"
                }
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

        val knobX = pointerX.coerceIn(dp(12f), (width - dp(12f)).coerceAtLeast(dp(12f)))
        val knobY = pointerY.coerceIn(dp(12f), (height - dp(12f)).coerceAtLeast(dp(12f)))
        val isDangerState = (isTrayTargeted && (
            selectedTrayAction == DragAppAction.UNLOAD_WHISPER ||
                selectedTrayAction == DragAppAction.UNLOAD_GEMMA
            )) || (!isTrayTargeted && currentAction == RadialAction.CANCEL)
        amplitudeRingPaint.color = if (isDangerState) {
            PastelPalette.AMPLITUDE_RING_CANCEL
        } else {
            PastelPalette.AMPLITUDE_RING
        }
        val pulseR = knobRadiusPx + dp(6f) + voiceAmplitude * dp(18f)
        canvas.drawCircle(knobX, knobY, pulseR, amplitudeRingPaint)
        canvas.drawCircle(knobX, knobY, knobRadiusPx, knobFillPaint(source))
        drawMicIcon(canvas, knobX, knobY, source == MicSource.GEMMA, isDanger = isDangerState)
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
                leftLongPressTriggered = false
                if (statusMessage.isNotEmpty() && showCancelBanner && cancelBannerButtonRect.contains(x, y)) {
                    idleTouchTarget = IdleTouchTarget.CANCEL_BANNER
                    invalidate()
                    return true
                }
                if (englishBtnRect.contains(x, y)) {
                    idleTouchTarget = IdleTouchTarget.ENGLISH
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    removeCallbacks(leftLongPressRunnable)
                    postDelayed(leftLongPressRunnable, longPressTimeoutMs)
                    invalidate()
                    return true
                }
                if (handwritingBtnRect.contains(x, y)) {
                    idleTouchTarget = IdleTouchTarget.HANDWRITING
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    removeCallbacks(leftLongPressRunnable)
                    postDelayed(leftLongPressRunnable, longPressTimeoutMs)
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
                // Consume touches inside the dark panel background so nothing leaks underneath.
                return y >= barCardRect.top
            }

            MotionEvent.ACTION_MOVE -> {
                if (isRadialExpanded && activeSource != null) {
                    val src = activeSource!!
                    pointerX = x
                    pointerY = y
                    if (pointerY > trayActivationY() && trayActions.isNotEmpty()) {
                        isTrayTargeted = true
                        val picked = selectTrayActionAt(x, y)
                        if (picked != selectedTrayAction) {
                            selectedTrayAction = picked
                            if (picked != null) {
                                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            }
                        }
                        onRadialOverlayInvalidate?.invoke()
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
                    onRadialOverlayInvalidate?.invoke()
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
                if (idleTouchTarget == IdleTouchTarget.ENGLISH || idleTouchTarget == IdleTouchTarget.HANDWRITING) {
                    val targetRect = if (idleTouchTarget == IdleTouchTarget.ENGLISH) englishBtnRect else handwritingBtnRect
                    val slop = dp(12f)
                    val stillInside = x >= targetRect.left - slop &&
                        x <= targetRect.right + slop &&
                        y >= targetRect.top - slop &&
                        y <= targetRect.bottom + slop
                    if (!stillInside) {
                        removeCallbacks(leftLongPressRunnable)
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
                removeCallbacks(leftLongPressRunnable)
                val target = idleTouchTarget
                idleTouchTarget = IdleTouchTarget.NONE
                invalidate()
                if (!leftLongPressTriggered) {
                    when (target) {
                        IdleTouchTarget.ENGLISH -> listener?.onToggleEnglishKeyboard()
                        IdleTouchTarget.HANDWRITING -> listener?.onToggleChineseHandwriting()
                        IdleTouchTarget.BACKSPACE -> Unit
                        IdleTouchTarget.ENTER -> listener?.onEnterClick()
                        IdleTouchTarget.CANCEL_BANNER -> listener?.onCancelActiveGeneration()
                        IdleTouchTarget.NONE -> Unit
                    }
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(backspaceRepeatRunnable)
                removeCallbacks(leftLongPressRunnable)
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
        removeCallbacks(leftLongPressRunnable)
        removeCallbacks(memoryRefreshRunnable)
        panelAnimator?.cancel()
        heightSwitchAnimator?.cancel()
        radialAnimator?.cancel()
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

        radialAnimator?.cancel()
        radialAnimator = ValueAnimator.ofFloat(0.25f, 1f).apply {
            duration = 160L
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                expandProgress = anim.animatedValue as Float
                onRadialOverlayInvalidate?.invoke()
                invalidate()
            }
            start()
        }
    }

    private fun endRadialSession() {
        radialAnimator?.cancel()
        expandProgress = 0f
        isRadialExpanded = false
        val wasActive = activeSource != null
        activeSource = null
        currentAction = RadialAction.NONE
        voiceAmplitude = 0f
        clearTray()
        onRadialOverlayInvalidate?.invoke()
        if (wasActive) {
            listener?.onTouchableRegionChanged()
        }
        invalidate()
    }

    private fun drawBackspaceIcon(canvas: Canvas, cx: Float, cy: Float) {
        val left = cx - dp(9.5f)
        val tipX = left + dp(5f)
        val right = cx + dp(9f)
        val halfH = dp(6f)
        val outline = Path().apply {
            moveTo(left, cy)
            lineTo(tipX, cy - halfH)
            lineTo(right, cy - halfH)
            lineTo(right, cy + halfH)
            lineTo(tipX, cy + halfH)
            close()
        }
        canvas.drawPath(outline, iconPaint)
        val crossCenterX = cx + dp(1.8f)
        val r = dp(2.6f)
        canvas.drawLine(crossCenterX - r, cy - r, crossCenterX + r, cy + r, iconPaint)
        canvas.drawLine(crossCenterX - r, cy + r, crossCenterX + r, cy - r, iconPaint)
    }

    private fun drawMicIcon(canvas: Canvas, cx: Float, cy: Float, sparkle: Boolean, isDanger: Boolean = false) {
        val stroke = if (isDanger) roseIconPaint else iconDarkPaint
        val fill = if (isDanger) roseIconFillPaint else iconDarkFillPaint
        val capsuleW = dp(6f)
        val capsuleH = dp(10f)
        scratchRect.set(
            cx - capsuleW / 2f,
            cy - capsuleH / 2f - dp(2f),
            cx + capsuleW / 2f,
            cy + capsuleH / 2f - dp(2f),
        )
        canvas.drawRoundRect(scratchRect, capsuleW / 2f, capsuleW / 2f, fill)

        scratchRect.set(cx - dp(6f), cy - dp(5f), cx + dp(6f), cy + dp(6f))
        canvas.drawArc(scratchRect, 0f, 180f, false, stroke)
        canvas.drawLine(cx, cy + dp(6f), cx, cy + dp(9.5f), stroke)
        canvas.drawLine(cx - dp(3.5f), cy + dp(9.5f), cx + dp(3.5f), cy + dp(9.5f), stroke)

        if (sparkle) {
            val sx = cx + dp(7.5f)
            val sy = cy - dp(7.5f)
            canvas.drawLine(sx - dp(2.2f), sy, sx + dp(2.2f), sy, stroke)
            canvas.drawLine(sx, sy - dp(2.2f), sx, sy + dp(2.2f), stroke)
        }
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    private fun lerpRect(a: RectF, b: RectF, t: Float, out: RectF) {
        out.set(
            lerp(a.left, b.left, t),
            lerp(a.top, b.top, t),
            lerp(a.right, b.right, t),
            lerp(a.bottom, b.bottom, t),
        )
    }
}
