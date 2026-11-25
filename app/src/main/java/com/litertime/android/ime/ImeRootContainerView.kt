package com.litertime.android.ime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import com.litertime.android.handwriting.HandwritingPanelView
import com.litertime.android.ime.traditional.EnglishKeyboardPanelView
import com.litertime.android.settings.ImeSettings
import com.litertime.android.ui.PastelPalette

/**
 * Root IME view hosting three layers:
 * 1. [voiceOverlayView]: bottom-anchored dark panel + top voice bar (mic buttons, En/中 toggles, Backspace/Enter).
 * 2. [traditionalPanelContainer]: animated expandable slot directly beneath the voice bar holding
 *    either [englishKeyboardPanel] or [handwritingPanel].
 * 3. [radialOverlayView]: non-touchable foreground layer drawing the radial semicircle disc,
 *    draggable knob, and down-drag tray chips *above* the expanded traditional panel.
 */
@SuppressLint("ViewConstructor")
class ImeRootContainerView(context: Context) : FrameLayout(context) {

    val voiceOverlayView = VoiceImeOverlayView(context)
    val englishKeyboardPanel = EnglishKeyboardPanelView(context)
    val handwritingPanel = HandwritingPanelView(context)

    private val traditionalPanelContainer = FrameLayout(context).apply {
        clipChildren = true
        clipToPadding = true
        visibility = GONE
    }

    private val radialOverlayView = object : View(context) {
        override fun onDraw(canvas: Canvas) {
            voiceOverlayView.drawRadialOverlay(canvas)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean = false
    }.apply {
        isClickable = false
        isFocusable = false
    }

    private var safeLeftPx = 0
    private var safeRightPx = 0
    private var safeBottomPx = 0
    private var currentSettings = ImeSettings()

    init {
        clipChildren = false
        clipToPadding = false

        addView(
            voiceOverlayView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )

        traditionalPanelContainer.addView(
            englishKeyboardPanel,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
        traditionalPanelContainer.addView(
            handwritingPanel,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
        addView(
            traditionalPanelContainer,
            LayoutParams(LayoutParams.MATCH_PARENT, 0),
        )

        addView(
            radialOverlayView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )

        voiceOverlayView.onPanelGeometryChanged = { syncTraditionalPanelGeometry() }
        voiceOverlayView.onRadialOverlayInvalidate = { radialOverlayView.invalidate() }
    }

    val isRadialExpanded: Boolean
        get() = voiceOverlayView.isRadialExpanded

    val expandedMode: ExpandedPanelMode
        get() = voiceOverlayView.expandedMode

    fun applySettings(settings: ImeSettings) {
        currentSettings = settings
        // Resolve the three user-customizable base colors before any child reads the palette.
        PastelPalette.applyTheme(
            accent1 = settings.accentColor1,
            accent2 = settings.accentColor2,
            background = settings.backgroundColor,
        )
        val maxDp = maxOf(settings.handwritingPanelHeightDp, settings.keyboardHeightDp)
        voiceOverlayView.updateMaxSupportedPanelHeightDp(maxDp)
        voiceOverlayView.refreshPalette()
        englishKeyboardPanel.applySettings(settings)
        handwritingPanel.applySettings(settings)

        // If a panel is currently open and the user changed its height in Settings, resize smoothly.
        when (voiceOverlayView.expandedMode) {
            ExpandedPanelMode.ENGLISH_KEYBOARD ->
                voiceOverlayView.setExpandedPanel(ExpandedPanelMode.ENGLISH_KEYBOARD, settings.keyboardHeightDp)
            ExpandedPanelMode.CHINESE_HANDWRITING ->
                voiceOverlayView.setExpandedPanel(ExpandedPanelMode.CHINESE_HANDWRITING, settings.handwritingPanelHeightDp)
            ExpandedPanelMode.NONE -> Unit
        }
    }

    fun toggleEnglishKeyboard() {
        if (voiceOverlayView.expandedMode == ExpandedPanelMode.CHINESE_HANDWRITING) {
            handwritingPanel.flushPendingCharacter()
        }
        if (voiceOverlayView.expandedMode == ExpandedPanelMode.ENGLISH_KEYBOARD) {
            voiceOverlayView.setExpandedPanel(ExpandedPanelMode.NONE, 0)
        } else {
            englishKeyboardPanel.resetToQwerty()
            englishKeyboardPanel.visibility = VISIBLE
            handwritingPanel.visibility = GONE
            voiceOverlayView.setExpandedPanel(
                ExpandedPanelMode.ENGLISH_KEYBOARD,
                currentSettings.keyboardHeightDp,
            )
        }
    }

    fun toggleChineseHandwriting() {
        if (voiceOverlayView.expandedMode == ExpandedPanelMode.CHINESE_HANDWRITING) {
            handwritingPanel.flushPendingCharacter()
            voiceOverlayView.setExpandedPanel(ExpandedPanelMode.NONE, 0)
        } else {
            handwritingPanel.ensureModelLoaded()
            handwritingPanel.resetToCanvas()
            handwritingPanel.visibility = VISIBLE
            englishKeyboardPanel.visibility = GONE
            voiceOverlayView.setExpandedPanel(
                ExpandedPanelMode.CHINESE_HANDWRITING,
                currentSettings.handwritingPanelHeightDp,
            )
        }
    }

    fun collapseImmediate() {
        if (voiceOverlayView.expandedMode == ExpandedPanelMode.CHINESE_HANDWRITING) {
            handwritingPanel.flushPendingCharacter()
        }
        voiceOverlayView.collapseImmediate()
        syncTraditionalPanelGeometry()
    }

    fun updateSystemInsets(bottomPx: Int, leftPx: Int, rightPx: Int) {
        safeLeftPx = leftPx.coerceAtLeast(0)
        safeRightPx = rightPx.coerceAtLeast(0)
        safeBottomPx = bottomPx.coerceAtLeast(0)
        traditionalPanelContainer.setPadding(safeLeftPx, 0, safeRightPx, 0)
        voiceOverlayView.updateSystemInsets(safeBottomPx, safeLeftPx, safeRightPx)
        syncTraditionalPanelGeometry()
    }

    fun getTouchableTop(): Int = voiceOverlayView.getTargetTouchableTop()

    fun getTouchableBottom(): Int = voiceOverlayView.getIdleTouchableBottom()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val maxWinH = voiceOverlayView.getMaxWindowHeightPx()
        val height = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.AT_MOST) {
            minOf(maxWinH, MeasureSpec.getSize(heightMeasureSpec))
        } else {
            maxWinH
        }
        val exactHeightSpec = MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
        super.onMeasure(widthMeasureSpec, exactHeightSpec)
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val w = right - left
        val h = bottom - top
        voiceOverlayView.layout(0, 0, w, h)
        radialOverlayView.layout(0, 0, w, h)
        layoutTraditionalContainer(w)
    }

    private fun syncTraditionalPanelGeometry() {
        val h = voiceOverlayView.getCurrentTraditionalPanelHeight()
        val progress = voiceOverlayView.getPanelExpandProgress()
        val visible = h > 1 && (voiceOverlayView.expandedMode != ExpandedPanelMode.NONE || progress > 0.01f)
        traditionalPanelContainer.visibility = if (visible) VISIBLE else GONE
        traditionalPanelContainer.alpha = progress.coerceIn(0f, 1f)

        if (visible) {
            when (voiceOverlayView.expandedMode) {
                ExpandedPanelMode.ENGLISH_KEYBOARD -> {
                    englishKeyboardPanel.visibility = VISIBLE
                    handwritingPanel.visibility = GONE
                }
                ExpandedPanelMode.CHINESE_HANDWRITING -> {
                    handwritingPanel.visibility = VISIBLE
                    englishKeyboardPanel.visibility = GONE
                }
                ExpandedPanelMode.NONE -> Unit
            }
        }

        if (width > 0) {
            layoutTraditionalContainer(width)
        }
    }

    private fun layoutTraditionalContainer(w: Int) {
        val panelTop = voiceOverlayView.getCurrentTraditionalPanelTop()
        val panelH = voiceOverlayView.getCurrentTraditionalPanelHeight()
        val targetChildH = when (voiceOverlayView.expandedMode) {
            ExpandedPanelMode.ENGLISH_KEYBOARD -> dp(currentSettings.keyboardHeightDp.toFloat())
            ExpandedPanelMode.CHINESE_HANDWRITING -> dp(currentSettings.handwritingPanelHeightDp.toFloat())
            ExpandedPanelMode.NONE -> maxOf(panelH, dp(220f))
        }
        // Measure children at their full target height so they slide cleanly without text squishing during animation.
        val childWSpec = MeasureSpec.makeMeasureSpec((w - safeLeftPx - safeRightPx).coerceAtLeast(0), MeasureSpec.EXACTLY)
        val childHSpec = MeasureSpec.makeMeasureSpec(targetChildH.coerceAtLeast(0), MeasureSpec.EXACTLY)
        englishKeyboardPanel.measure(childWSpec, childHSpec)
        handwritingPanel.measure(childWSpec, childHSpec)

        traditionalPanelContainer.layout(0, panelTop, w, panelTop + panelH)
        val cw = (w - safeLeftPx - safeRightPx).coerceAtLeast(0)
        englishKeyboardPanel.layout(safeLeftPx, 0, safeLeftPx + cw, targetChildH)
        handwritingPanel.layout(safeLeftPx, 0, safeLeftPx + cw, targetChildH)
    }

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()
}
