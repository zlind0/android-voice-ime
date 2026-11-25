package com.litertime.android.ime.traditional

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import com.litertime.android.settings.ImeSettings
import com.litertime.android.ui.PastelPalette

/**
 * High-performance custom Canvas view rendering FlorisBoard's QWERTY, Symbols (`?123`), and
 * Symbols2 (`=\<`) layouts with:
 * - Multi-touch pointer tracking for fast two-thumb English typing
 * - ZERO gray corner symbol hints on any key
 * - ZERO long-press symbol popups on any key (only Backspace repeats while held)
 * - Optional single-character press preview bubble when enabled in Settings
 */
class QwertyKeyboardView(context: Context) : View(context) {

    interface Listener {
        fun onCommitText(text: String)
        fun onSpaceKeyPressed()
        fun onBackspaceClick()
        fun onEnterClick()
        fun onOpenEmojiPicker()
    }

    var listener: Listener? = null

    private data class KeyRect(
        val spec: EnglishKeyboardLayout.KeySpec,
        val rowIndex: Int,
        val rect: RectF,
    )

    private val density = resources.displayMetrics.density
    private fun dp(value: Float): Float = value * density
    private fun sp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    private val mainHandler = Handler(Looper.getMainLooper())
    private var settings = ImeSettings()
    private var subMode = EnglishKeyboardLayout.SubMode.CHARACTERS
    private var shiftState = EnglishKeyboardLayout.ShiftState.UNSHIFTED
    private var commaVariation = EnglishKeyboardLayout.CommaVariation.DEFAULT
    private var enterLabel = "↵"
    private var lastShiftTapTimeMs = 0L

    private var keyRects: List<KeyRect> = emptyList()
    private val activePointers = mutableMapOf<Int, KeyRect>()
    private var backspacePointerId: Int? = null
    private val backspaceRepeatRunnable = object : Runnable {
        override fun run() {
            if (backspacePointerId != null) {
                triggerHaptic()
                listener?.onBackspaceClick()
                mainHandler.postDelayed(this, 65L)
            }
        }
    }

    private val bgPaint = Paint().apply {
        color = PastelPalette.BG_CANVAS
        style = Paint.Style.FILL
    }
    private val charKeyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PastelPalette.SURFACE_CHAR_KEY
        style = Paint.Style.FILL
    }
    private val modKeyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PastelPalette.SURFACE_MOD_KEY
        style = Paint.Style.FILL
    }
    private val enterKeyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PastelPalette.PINK_LIGHT
        style = Paint.Style.FILL
    }
    private val enterKeyPressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PastelPalette.PINK_PRESSED
        style = Paint.Style.FILL
    }
    private val pressedKeyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PastelPalette.SURFACE_KEY_PRESSED
        style = Paint.Style.FILL
    }
    private val backspacePressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PastelPalette.SURFACE_BACKSPACE_PRESSED
        style = Paint.Style.FILL
    }
    private val shiftActivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PastelPalette.SURFACE_SHIFT_ACTIVE
        style = Paint.Style.FILL
    }
    private val capsLockBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PastelPalette.SKY_BLUE
        style = Paint.Style.FILL
    }
    private val capsLockPressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PastelPalette.SKY_BLUE_PRESSED
        style = Paint.Style.FILL
    }
    private val charTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PastelPalette.TEXT_PRIMARY
        textSize = sp(19f)
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }
    private val modTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PastelPalette.TEXT_MOD_KEY
        textSize = sp(13.5f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }
    private val enterTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PastelPalette.INK_ON_PINK
        textSize = sp(13.5f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }
    private val spaceTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PastelPalette.TEXT_MUTED
        textSize = sp(12f)
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }
    private val iconStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PastelPalette.TEXT_ON_DARK_SKY
        style = Paint.Style.STROKE
        strokeWidth = dp(1.65f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val shiftActiveIconFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PastelPalette.TEXT_PRIMARY
        style = Paint.Style.FILL
    }
    private val capsLockIconFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PastelPalette.INK_ON_PASTEL
        style = Paint.Style.FILL
    }
    private val enterIconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PastelPalette.INK_ON_PINK
        style = Paint.Style.STROKE
        strokeWidth = dp(1.8f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val popupBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PastelPalette.SURFACE_POPUP
        style = Paint.Style.FILL
    }
    private val popupTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PastelPalette.TEXT_PRIMARY
        textSize = sp(23f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }

    private val scratchRect = RectF()
    private val scratchPath = Path()

    fun applySettings(newSettings: ImeSettings) {
        val rebuildNeeded = settings.keyboardShowNumberRow != newSettings.keyboardShowNumberRow
        settings = newSettings
        if (rebuildNeeded) {
            rebuildLayout()
            invalidate()
        }
    }

    fun configureForInput(editorInfo: EditorInfo?, autoCapitalize: Boolean) {
        commaVariation = EnglishKeyboardLayout.commaVariationFor(editorInfo)
        enterLabel = EnglishKeyboardLayout.enterLabelFor(editorInfo)
        if (shiftState != EnglishKeyboardLayout.ShiftState.CAPS_LOCK) {
            shiftState = if (autoCapitalize) {
                EnglishKeyboardLayout.ShiftState.SHIFTED
            } else {
                EnglishKeyboardLayout.ShiftState.UNSHIFTED
            }
        }
        rebuildLayout()
        invalidate()
    }

    fun updateAutoShift(shouldCapitalize: Boolean) {
        if (subMode != EnglishKeyboardLayout.SubMode.CHARACTERS) return
        if (shiftState == EnglishKeyboardLayout.ShiftState.CAPS_LOCK) return
        val next = if (shouldCapitalize) {
            EnglishKeyboardLayout.ShiftState.SHIFTED
        } else {
            EnglishKeyboardLayout.ShiftState.UNSHIFTED
        }
        if (next != shiftState) {
            shiftState = next
            rebuildLayout()
            invalidate()
        }
    }

    fun resetToCharacters() {
        if (subMode != EnglishKeyboardLayout.SubMode.CHARACTERS) {
            subMode = EnglishKeyboardLayout.SubMode.CHARACTERS
            rebuildLayout()
            invalidate()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildLayout()
    }

    private fun rebuildLayout() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) {
            keyRects = emptyList()
            return
        }

        val rows = EnglishKeyboardLayout.buildRows(
            subMode = subMode,
            shiftState = shiftState,
            showNumberRow = settings.keyboardShowNumberRow,
            commaVariation = commaVariation,
            enterLabel = enterLabel,
        )
        if (rows.isEmpty()) {
            keyRects = emptyList()
            return
        }

        val outerPadH = dp(6f)
        val outerPadTop = dp(5f)
        val outerPadBottom = dp(7f)
        val rowGap = dp(7f)
        val keyGap = dp(5.5f)

        val usableH = (h - outerPadTop - outerPadBottom - rowGap * (rows.size - 1)).coerceAtLeast(dp(40f))
        val rowH = usableH / rows.size
        val fullRowW = (w - outerPadH * 2f).coerceAtLeast(dp(80f))

        val built = mutableListOf<KeyRect>()
        rows.forEachIndexed { rowIndex, row ->
            val top = outerPadTop + rowIndex * (rowH + rowGap)
            val bottom = top + rowH
            val sideInset = fullRowW * row.horizontalPaddingRatio
            val rowLeft = outerPadH + sideInset
            val rowRight = w - outerPadH - sideInset
            val totalWeight = row.keys.sumOf { it.weight.toDouble() }.toFloat().coerceAtLeast(1f)
            val totalGaps = keyGap * (row.keys.size - 1).coerceAtLeast(0)
            val unitW = ((rowRight - rowLeft) - totalGaps).coerceAtLeast(10f) / totalWeight

            var x = rowLeft
            row.keys.forEachIndexed { keyIdx, spec ->
                val kw = unitW * spec.weight
                val right = if (keyIdx == row.keys.lastIndex) rowRight else x + kw
                built += KeyRect(spec = spec, rowIndex = rowIndex, rect = RectF(x, top, right, bottom))
                x = right + keyGap
            }
        }
        keyRects = built
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        val pressedIds = activePointers.values.map { it.spec.id }.toSet()
        val cornerR = dp(8.5f)

        for (kr in keyRects) {
            val isPressed = kr.spec.id in pressedIds
            val fillPaint = when {
                kr.spec.kind == EnglishKeyboardLayout.KeyKind.ENTER ->
                    if (isPressed) enterKeyPressedPaint else enterKeyPaint
                kr.spec.kind == EnglishKeyboardLayout.KeyKind.BACKSPACE && isPressed ->
                    backspacePressedPaint
                kr.spec.kind == EnglishKeyboardLayout.KeyKind.SHIFT &&
                    shiftState == EnglishKeyboardLayout.ShiftState.CAPS_LOCK ->
                    if (isPressed) capsLockPressedPaint else capsLockBgPaint
                isPressed -> pressedKeyPaint
                kr.spec.kind == EnglishKeyboardLayout.KeyKind.SHIFT &&
                    shiftState == EnglishKeyboardLayout.ShiftState.SHIFTED -> shiftActivePaint
                kr.spec.kind == EnglishKeyboardLayout.KeyKind.CHARACTER ||
                    kr.spec.kind == EnglishKeyboardLayout.KeyKind.SPACE -> charKeyPaint
                else -> modKeyPaint
            }
            canvas.drawRoundRect(kr.rect, cornerR, cornerR, fillPaint)

            when (kr.spec.kind) {
                EnglishKeyboardLayout.KeyKind.SHIFT -> drawShiftIcon(canvas, kr.rect)
                EnglishKeyboardLayout.KeyKind.BACKSPACE -> drawBackspaceIcon(canvas, kr.rect)
                EnglishKeyboardLayout.KeyKind.ENTER -> {
                    if (kr.spec.label == "↵") {
                        drawEnterIcon(canvas, kr.rect)
                    } else {
                        val y = kr.rect.centerY() - (enterTextPaint.descent() + enterTextPaint.ascent()) / 2f
                        canvas.drawText(kr.spec.label, kr.rect.centerX(), y, enterTextPaint)
                    }
                }
                EnglishKeyboardLayout.KeyKind.SPACE -> {
                    val y = kr.rect.centerY() - (spaceTextPaint.descent() + spaceTextPaint.ascent()) / 2f
                    canvas.drawText(kr.spec.label, kr.rect.centerX(), y, spaceTextPaint)
                }
                EnglishKeyboardLayout.KeyKind.CHARACTER -> {
                    val y = kr.rect.centerY() - (charTextPaint.descent() + charTextPaint.ascent()) / 2f
                    canvas.drawText(kr.spec.label, kr.rect.centerX(), y, charTextPaint)
                }
                else -> {
                    val y = kr.rect.centerY() - (modTextPaint.descent() + modTextPaint.ascent()) / 2f
                    canvas.drawText(kr.spec.label, kr.rect.centerX(), y, modTextPaint)
                }
            }
        }

        // Single-character key preview popup (only for character keys, no corner/long-press symbols)
        if (settings.keyboardKeyPopupEnabled) {
            for (kr in activePointers.values) {
                if (kr.spec.kind == EnglishKeyboardLayout.KeyKind.CHARACTER && kr.spec.label.isNotEmpty()) {
                    drawKeyPreviewBubble(canvas, kr)
                }
            }
        }
    }

    private fun drawKeyPreviewBubble(canvas: Canvas, kr: KeyRect) {
        val popW = (kr.rect.width() * 1.25f).coerceAtLeast(dp(40f))
        val popH = dp(48f)
        val cx = kr.rect.centerX().coerceIn(popW / 2f + dp(2f), width - popW / 2f - dp(2f))
        val desiredBottom = kr.rect.top - dp(4f)
        val top = (desiredBottom - popH).coerceAtLeast(dp(2f))
        val bottom = top + popH

        scratchRect.set(cx - popW / 2f, top, cx + popW / 2f, bottom)
        canvas.drawRoundRect(scratchRect, dp(10f), dp(10f), popupBgPaint)
        val textY = scratchRect.centerY() - (popupTextPaint.descent() + popupTextPaint.ascent()) / 2f
        canvas.drawText(kr.spec.label, cx, textY, popupTextPaint)
    }

    private fun drawShiftIcon(canvas: Canvas, rect: RectF) {
        val cx = rect.centerX()
        val cy = rect.centerY() - if (shiftState == EnglishKeyboardLayout.ShiftState.CAPS_LOCK) dp(2f) else 0f
        val halfW = dp(8.5f)
        val stemHalfW = dp(3.8f)
        val topY = cy - dp(7.5f)
        val shoulderY = cy
        val bottomY = cy + dp(6.5f)

        scratchPath.reset()
        scratchPath.moveTo(cx, topY)
        scratchPath.lineTo(cx + halfW, shoulderY)
        scratchPath.lineTo(cx + stemHalfW, shoulderY)
        scratchPath.lineTo(cx + stemHalfW, bottomY)
        scratchPath.lineTo(cx - stemHalfW, bottomY)
        scratchPath.lineTo(cx - stemHalfW, shoulderY)
        scratchPath.lineTo(cx - halfW, shoulderY)
        scratchPath.close()

        when (shiftState) {
            EnglishKeyboardLayout.ShiftState.UNSHIFTED -> canvas.drawPath(scratchPath, iconStrokePaint)
            EnglishKeyboardLayout.ShiftState.SHIFTED -> canvas.drawPath(scratchPath, shiftActiveIconFillPaint)
            EnglishKeyboardLayout.ShiftState.CAPS_LOCK -> {
                canvas.drawPath(scratchPath, capsLockIconFillPaint)
                scratchRect.set(cx - dp(6f), bottomY + dp(2.5f), cx + dp(6f), bottomY + dp(4.5f))
                canvas.drawRoundRect(scratchRect, dp(1f), dp(1f), capsLockIconFillPaint)
            }
        }
    }

    private fun drawEnterIcon(canvas: Canvas, rect: RectF) {
        val cx = rect.centerX()
        val cy = rect.centerY()
        val topY = cy - dp(5.5f)
        val shaftY = cy + dp(2.5f)
        val rightX = cx + dp(6.5f)
        val arrowX = cx - dp(6f)
        canvas.drawLine(rightX, topY, rightX, shaftY, enterIconPaint)
        canvas.drawLine(rightX, shaftY, arrowX, shaftY, enterIconPaint)
        canvas.drawLine(arrowX, shaftY, arrowX + dp(4.2f), shaftY - dp(4.2f), enterIconPaint)
        canvas.drawLine(arrowX, shaftY, arrowX + dp(4.2f), shaftY + dp(4.2f), enterIconPaint)
    }

    private fun drawBackspaceIcon(canvas: Canvas, rect: RectF) {
        val cx = rect.centerX()
        val cy = rect.centerY()
        val left = cx - dp(9.5f)
        val tipX = left + dp(5f)
        val right = cx + dp(9f)
        val halfH = dp(6f)

        scratchPath.reset()
        scratchPath.moveTo(left, cy)
        scratchPath.lineTo(tipX, cy - halfH)
        scratchPath.lineTo(right, cy - halfH)
        scratchPath.lineTo(right, cy + halfH)
        scratchPath.lineTo(tipX, cy + halfH)
        scratchPath.close()
        canvas.drawPath(scratchPath, iconStrokePaint)

        val crossCx = cx + dp(1.8f)
        val r = dp(2.5f)
        canvas.drawLine(crossCx - r, cy - r, crossCx + r, cy + r, iconStrokePaint)
        canvas.drawLine(crossCx - r, cy + r, crossCx + r, cy - r, iconStrokePaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val actionIndex = event.actionIndex
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val pointerId = event.getPointerId(actionIndex)
                val x = event.getX(actionIndex)
                val y = event.getY(actionIndex)
                val hit = findKeyAt(x, y) ?: return true
                activePointers[pointerId] = hit
                triggerHaptic()
                if (hit.spec.kind == EnglishKeyboardLayout.KeyKind.BACKSPACE) {
                    backspacePointerId = pointerId
                    listener?.onBackspaceClick()
                    mainHandler.removeCallbacks(backspaceRepeatRunnable)
                    mainHandler.postDelayed(backspaceRepeatRunnable, 400L)
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                var changed = false
                for (i in 0 until event.pointerCount) {
                    val pointerId = event.getPointerId(i)
                    val prev = activePointers[pointerId] ?: continue
                    val hit = findKeyAt(event.getX(i), event.getY(i))
                    if (hit == null) {
                        if (backspacePointerId == pointerId) {
                            stopBackspaceRepeat()
                        }
                        activePointers.remove(pointerId)
                        changed = true
                    } else if (hit.spec.id != prev.spec.id) {
                        if (backspacePointerId == pointerId) {
                            stopBackspaceRepeat()
                        }
                        activePointers[pointerId] = hit
                        if (hit.spec.kind == EnglishKeyboardLayout.KeyKind.BACKSPACE) {
                            backspacePointerId = pointerId
                            listener?.onBackspaceClick()
                            mainHandler.removeCallbacks(backspaceRepeatRunnable)
                            mainHandler.postDelayed(backspaceRepeatRunnable, 400L)
                        }
                        changed = true
                    }
                }
                if (changed) invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val pointerId = event.getPointerId(actionIndex)
                val released = activePointers.remove(pointerId)
                if (backspacePointerId == pointerId) {
                    stopBackspaceRepeat()
                }
                if (released != null) {
                    handleKeyRelease(released.spec)
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                stopBackspaceRepeat()
                activePointers.clear()
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun handleKeyRelease(spec: EnglishKeyboardLayout.KeySpec) {
        when (spec.kind) {
            EnglishKeyboardLayout.KeyKind.CHARACTER -> {
                listener?.onCommitText(spec.outputText)
                if (subMode == EnglishKeyboardLayout.SubMode.CHARACTERS &&
                    shiftState == EnglishKeyboardLayout.ShiftState.SHIFTED
                ) {
                    shiftState = EnglishKeyboardLayout.ShiftState.UNSHIFTED
                    rebuildLayout()
                }
            }
            EnglishKeyboardLayout.KeyKind.SHIFT -> {
                val now = System.currentTimeMillis()
                val isDoubleTap = now - lastShiftTapTimeMs <= 300L
                lastShiftTapTimeMs = now
                shiftState = when (shiftState) {
                    EnglishKeyboardLayout.ShiftState.UNSHIFTED -> EnglishKeyboardLayout.ShiftState.SHIFTED
                    EnglishKeyboardLayout.ShiftState.SHIFTED -> if (isDoubleTap) {
                        EnglishKeyboardLayout.ShiftState.CAPS_LOCK
                    } else {
                        EnglishKeyboardLayout.ShiftState.UNSHIFTED
                    }
                    EnglishKeyboardLayout.ShiftState.CAPS_LOCK -> EnglishKeyboardLayout.ShiftState.UNSHIFTED
                }
                rebuildLayout()
            }
            EnglishKeyboardLayout.KeyKind.BACKSPACE -> Unit // Already handled on ACTION_DOWN + repeat
            EnglishKeyboardLayout.KeyKind.MODE_SYMBOLS -> {
                subMode = EnglishKeyboardLayout.SubMode.SYMBOLS
                rebuildLayout()
            }
            EnglishKeyboardLayout.KeyKind.MODE_SYMBOLS2 -> {
                subMode = EnglishKeyboardLayout.SubMode.SYMBOLS2
                rebuildLayout()
            }
            EnglishKeyboardLayout.KeyKind.MODE_CHARACTERS -> {
                subMode = EnglishKeyboardLayout.SubMode.CHARACTERS
                rebuildLayout()
            }
            EnglishKeyboardLayout.KeyKind.EMOJI_TOGGLE -> {
                listener?.onOpenEmojiPicker()
            }
            EnglishKeyboardLayout.KeyKind.SPACE -> {
                listener?.onSpaceKeyPressed()
            }
            EnglishKeyboardLayout.KeyKind.ENTER -> {
                listener?.onEnterClick()
            }
        }
    }

    private fun findKeyAt(x: Float, y: Float): KeyRect? {
        // Direct hit first, then nearest key in the matching row within padding gaps.
        keyRects.firstOrNull { it.rect.contains(x, y) }?.let { return it }
        val row = keyRects.minByOrNull { kr ->
            when {
                y < kr.rect.top -> kr.rect.top - y
                y > kr.rect.bottom -> y - kr.rect.bottom
                else -> 0f
            }
        }?.rowIndex ?: return null
        return keyRects
            .filter { it.rowIndex == row }
            .minByOrNull { kr -> kotlin.math.abs(kr.rect.centerX() - x) }
    }

    private fun stopBackspaceRepeat() {
        backspacePointerId = null
        mainHandler.removeCallbacks(backspaceRepeatRunnable)
    }

    private fun triggerHaptic() {
        if (settings.keyboardHapticEnabled) {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
    }

    override fun onDetachedFromWindow() {
        stopBackspaceRepeat()
        super.onDetachedFromWindow()
    }
}
