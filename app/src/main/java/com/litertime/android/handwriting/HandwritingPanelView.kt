package com.litertime.android.handwriting

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.litertime.android.ime.traditional.ChinesePunctuationView
import com.litertime.android.ime.traditional.EmojiPickerView
import com.litertime.android.settings.ImeSettings
import com.litertime.android.ui.PastelPalette
import java.util.concurrent.Executors

/**
 * Complete Chinese handwriting panel powered by `Ismantic/Handwritten`:
 * - Candidate bar on top (top-10 live predictions)
 * - 田字格 writing pad in the middle (single or dual alternating boxes; auto-commits top-1 & fades out after configurable delay)
 * - Bottom bar: [标点] [😊] [空格] [，] [。]
 * - Sub-views for Chinese Punctuation Picker and shared Emoji Picker.
 */
@SuppressLint("ViewConstructor")
class HandwritingPanelView(context: Context) : FrameLayout(context) {

    interface Listener {
        fun onCommitText(text: String)
        fun onReplaceLastText(deleteChars: Int, replacement: String)
        fun onBackspaceInEditor()
        fun onRecentEmojiUsed(emoji: String)
    }

    var listener: Listener? = null

    private enum class SubView {
        CANVAS,
        PUNCTUATION,
        EMOJI,
    }

    private val density = resources.displayMetrics.density
    private fun dp(value: Float): Int = (value * density).toInt()
    private fun dpF(value: Float): Float = value * density

    private val mainHandler = Handler(Looper.getMainLooper())
    private val inferenceExecutor = Executors.newSingleThreadExecutor()
    private val controller = HandwritingController()

    @Volatile
    private var recognizer: HCCRRecognizer? = null

    @Volatile
    private var modelLoadError: String? = null

    @Volatile
    private var modelLoadingStarted = false

    private var settings = ImeSettings()
    private var currentSubView = SubView.CANVAS
    private var strokeRevision = 0
    private var recognizedRevision = 0
    private var recognizedBoxIndex = -1
    private var pendingAutoCommitAfterRecognition = false
    private val pendingCommitQueue = PendingCommitQueue()

    private val mainContent: LinearLayout
    private val candidateScroll: HorizontalScrollView
    private val candidateRow: LinearLayout
    private val primaryCanvasView: HandwritingCanvasView
    private val dualBoxSpacer: View
    private val secondaryCanvasView: HandwritingCanvasView
    private val punctuationView: ChinesePunctuationView
    private val emojiView: EmojiPickerView

    private val recognizeRunnable = Runnable { runLiveRecognition() }
    private val autoCommitRunnable = Runnable { onAutoCommitTimerFired() }

    private data class ToolbarButtonStyle(
        val view: TextView,
        val isModifier: Boolean,
        val isMutedText: Boolean,
    )

    private val toolbarButtons = mutableListOf<ToolbarButtonStyle>()

    init {
        setBackgroundColor(PastelPalette.BG_CANVAS)

        // 1. Main writing layout
        mainContent = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }

        // Top candidate bar (42dp)
        candidateScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(42f),
            )
        }
        candidateRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10f), dp(4f), dp(10f), dp(4f))
        }
        candidateScroll.addView(candidateRow)
        mainContent.addView(candidateScroll)

        // Writing pad (single or dual 田字格)
        val canvasContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            isMotionEventSplittingEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ).apply {
                setMargins(dp(10f), dp(2f), dp(10f), dp(6f))
            }
        }
        primaryCanvasView = createCanvasBox(boxIndex = 0).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        }
        dualBoxSpacer = View(context).apply {
            visibility = GONE
            layoutParams = LinearLayout.LayoutParams(dp(8f), LinearLayout.LayoutParams.MATCH_PARENT)
        }
        secondaryCanvasView = createCanvasBox(boxIndex = 1).apply {
            visibility = GONE
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        }
        canvasContainer.addView(primaryCanvasView)
        canvasContainer.addView(dualBoxSpacer)
        canvasContainer.addView(secondaryCanvasView)
        updateDualBoxLayout()
        mainContent.addView(canvasContainer)

        // Bottom toolbar (44dp): [标点] [😊] [空格] [，] [。]
        val bottomToolbar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10f), 0, dp(10f), dp(6f))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(44f),
            )
        }

        bottomToolbar.addView(
            createToolbarButton(label = "标点", widthDp = 50f, weight = 0f, isModifier = true) {
                showSubView(SubView.PUNCTUATION)
            },
        )
        bottomToolbar.addView(
            createToolbarButton(label = "😊", widthDp = 42f, weight = 0f, isModifier = true) {
                showSubView(SubView.EMOJI)
            },
        )
        bottomToolbar.addView(
            createToolbarButton(
                label = "空格",
                widthDp = 0f,
                weight = 1f,
                isModifier = false,
                isMutedText = true,
            ) {
                commitWithPendingFlush(" ")
            },
        )
        bottomToolbar.addView(
            createToolbarButton(label = "，", widthDp = 46f, weight = 0f, isModifier = false) {
                commitWithPendingFlush("，")
            },
        )
        bottomToolbar.addView(
            createToolbarButton(
                label = "。",
                widthDp = 46f,
                weight = 0f,
                isModifier = false,
                marginEndDp = 0f,
            ) {
                commitWithPendingFlush("。")
            },
        )
        mainContent.addView(bottomToolbar)
        addView(mainContent)

        // 2. Chinese Punctuation Picker
        punctuationView = ChinesePunctuationView(context).apply {
            visibility = GONE
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            listener = object : ChinesePunctuationView.Listener {
                override fun onPunctuationSelected(text: String) {
                    commitWithPendingFlush(text)
                }

                override fun onBackClick() {
                    showSubView(SubView.CANVAS)
                }

                override fun onBackspaceClick() {
                    this@HandwritingPanelView.listener?.onBackspaceInEditor()
                }
            }
        }
        addView(punctuationView)

        // 3. Shared Emoji Picker
        emojiView = EmojiPickerView(context, initialReturnLabel = "手写").apply {
            visibility = GONE
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            listener = object : EmojiPickerView.Listener {
                override fun onEmojiSelected(emoji: String) {
                    commitWithPendingFlush(emoji)
                    this@HandwritingPanelView.listener?.onRecentEmojiUsed(emoji)
                }

                override fun onBackClick() {
                    showSubView(SubView.CANVAS)
                }

                override fun onBackspaceClick() {
                    this@HandwritingPanelView.listener?.onBackspaceInEditor()
                }
            }
        }
        addView(emojiView)

        refreshCandidatesUi()
    }

    fun applySettings(newSettings: ImeSettings) {
        val dualBoxChanged = settings.handwritingDualBoxEnabled != newSettings.handwritingDualBoxEnabled
        settings = newSettings
        setBackgroundColor(PastelPalette.BG_CANVAS)
        if (dualBoxChanged) {
            flushPendingCharacter()
            clearSession()
        }
        updateDualBoxLayout()
        primaryCanvasView.refreshPalette()
        secondaryCanvasView.refreshPalette()
        primaryCanvasView.applyStyle(
            strokeWidthDp = newSettings.handwritingStrokeWidthDp,
            inkColorArgb = newSettings.handwritingInkColor.argb,
            showGridLines = newSettings.handwritingShowGrid,
        )
        secondaryCanvasView.applyStyle(
            strokeWidthDp = newSettings.handwritingStrokeWidthDp,
            inkColorArgb = newSettings.handwritingInkColor.argb,
            showGridLines = newSettings.handwritingShowGrid,
        )
        toolbarButtons.forEach { styleToolbarButton(it) }
        punctuationView.applyPalette()
        punctuationView.setHapticEnabled(newSettings.keyboardHapticEnabled)
        emojiView.applyPalette()
        emojiView.setHapticEnabled(newSettings.keyboardHapticEnabled)
        emojiView.setRecentEmojis(newSettings.recentEmojiList())
        refreshCandidatesUi()
    }

    fun ensureModelLoaded() {
        if (recognizer != null || modelLoadingStarted) return
        modelLoadingStarted = true
        refreshCandidatesUi()
        inferenceExecutor.execute {
            try {
                val instance = HCCRRecognizer(context.assets)
                recognizer = instance
                modelLoadError = null
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to initialize HCCRRecognizer", t)
                modelLoadError = t.message ?: "手写模型加载失败"
            } finally {
                mainHandler.post { refreshCandidatesUi() }
            }
        }
    }

    fun resetToCanvas() {
        showSubView(SubView.CANVAS)
    }

    /**
     * Called when the user taps Backspace on the top voice bar while handwriting is open.
     * Returns `true` if un-committed strokes on the canvas were cleared; `false` if the caller
     * should delete a character from the host editor.
     */
    fun handleTopBarBackspace(): Boolean {
        if (currentSubView != SubView.CANVAS) {
            return false
        }
        return when (controller.onBackspace()) {
            HandwritingController.BackspaceAction.CLEAR_CANVAS -> {
                mainHandler.removeCallbacks(recognizeRunnable)
                mainHandler.removeCallbacks(autoCommitRunnable)
                pendingAutoCommitAfterRecognition = false
                strokeRevision++
                activeCanvas().clearImmediate()
                refreshCandidatesUi()
                true
            }
            HandwritingController.BackspaceAction.DELETE_EDITOR -> false
        }
    }

    /**
     * Commits top-1 candidate if un-committed strokes are waiting on canvas (e.g., before Enter or
     * collapsing/switching panels).
     */
    fun flushPendingCharacter() {
        mainHandler.removeCallbacks(recognizeRunnable)
        mainHandler.removeCallbacks(autoCommitRunnable)
        pendingAutoCommitAfterRecognition = false
        strokeRevision++
        val flushed = controller.flushPendingTopCandidate()
        clearAllCanvasesImmediate()
        refreshCandidatesUi()
        if (!flushed.isNullOrEmpty()) {
            commitOrdered(flushed)
        }
    }

    fun clearSession() {
        mainHandler.removeCallbacks(recognizeRunnable)
        mainHandler.removeCallbacks(autoCommitRunnable)
        pendingAutoCommitAfterRecognition = false
        strokeRevision++
        recognizedBoxIndex = -1
        pendingCommitQueue.clear()
        controller.clearAll(clearLastAutoCommit = true, resetActiveBox = true)
        clearAllCanvasesImmediate()
        refreshCandidatesUi()
    }

    fun release() {
        clearSession()
        inferenceExecutor.execute {
            recognizer?.close()
            recognizer = null
        }
        inferenceExecutor.shutdown()
    }

    private fun showSubView(target: SubView) {
        if (target != SubView.CANVAS) {
            flushPendingCharacter()
        }
        currentSubView = target
        mainContent.visibility = if (target == SubView.CANVAS) VISIBLE else GONE
        punctuationView.visibility = if (target == SubView.PUNCTUATION) VISIBLE else GONE
        emojiView.visibility = if (target == SubView.EMOJI) VISIBLE else GONE
        if (target == SubView.EMOJI) {
            emojiView.resetToDefaultTab()
        }
    }

    private fun createCanvasBox(boxIndex: Int): HandwritingCanvasView =
        HandwritingCanvasView(context).apply {
            strokeListener = object : HandwritingCanvasView.StrokeListener {
                override fun onStrokeStart() {
                    onBoxStrokeStart(boxIndex)
                }

                override fun onStrokeEnd() {
                    onBoxStrokeEnd(boxIndex)
                }
            }
        }

    private fun canvasForBox(boxIndex: Int): HandwritingCanvasView =
        if (boxIndex == 1 && settings.handwritingDualBoxEnabled) secondaryCanvasView else primaryCanvasView

    private fun activeCanvas(): HandwritingCanvasView = canvasForBox(controller.activeBoxIndex)

    private fun updateDualBoxLayout() {
        val dual = settings.handwritingDualBoxEnabled
        dualBoxSpacer.visibility = if (dual) VISIBLE else GONE
        secondaryCanvasView.visibility = if (dual) VISIBLE else GONE
    }

    private fun clearAllCanvasesImmediate() {
        primaryCanvasView.clearImmediate()
        secondaryCanvasView.clearImmediate()
    }

    private fun onBoxStrokeStart(boxIndex: Int) {
        mainHandler.removeCallbacks(recognizeRunnable)
        mainHandler.removeCallbacks(autoCommitRunnable)
        pendingAutoCommitAfterRecognition = false

        val prevBox = controller.activeBoxIndex
        val prevWasFresh = recognizedRevision == strokeRevision && recognizedBoxIndex == prevBox
        val result = controller.onStrokeStartInBox(boxIndex)
        strokeRevision++

        if (result.flushedPreviousBoxIndex != null) {
            val flushedBox = result.flushedPreviousBoxIndex
            val prevCanvas = canvasForBox(flushedBox)
            if (prevWasFresh) {
                val committed = result.committedPreviousText
                if (!committed.isNullOrEmpty()) {
                    commitOrdered(committed)
                }
            } else {
                val rec = recognizer
                if (rec != null && prevCanvas.hasStrokes()) {
                    val bmp = prevCanvas.renderToBitmap()
                    enqueueAsyncFlushRecognition(rec, bmp)
                } else if (!result.committedPreviousText.isNullOrEmpty()) {
                    commitOrdered(result.committedPreviousText)
                }
            }
            prevCanvas.startFadeOut(FADE_OUT_DURATION_MS) {
                onBoxFadeFinished(flushedBox)
            }
        }

        if (result.interruptedFade || result.candidatesCleared || result.flushedPreviousBoxIndex != null) {
            refreshCandidatesUi()
        }
    }

    private fun onBoxStrokeEnd(boxIndex: Int) {
        if (boxIndex != controller.activeBoxIndex) return
        controller.onStrokeEnd()
        strokeRevision++
        mainHandler.removeCallbacks(recognizeRunnable)
        mainHandler.removeCallbacks(autoCommitRunnable)
        val delayMs = settings.handwritingFadeDelayMs.toLong()
        val recDelay = minOf(RECOGNIZE_DEBOUNCE_MS, (delayMs / 2L).coerceAtLeast(30L))
        mainHandler.postDelayed(recognizeRunnable, recDelay)
        mainHandler.postDelayed(autoCommitRunnable, delayMs)
    }

    private fun onBoxFadeFinished(boxIndex: Int) {
        if (boxIndex == controller.activeBoxIndex) {
            controller.onFadeFinished()
            refreshCandidatesUi()
        }
    }

    private fun commitOrdered(text: String) {
        if (text.isEmpty()) return
        pendingCommitQueue.enqueueCommit(text).forEach { toCommit ->
            listener?.onCommitText(toCommit)
        }
    }

    private fun enqueueAsyncFlushRecognition(rec: HCCRRecognizer, bmp: Bitmap) {
        val token = pendingCommitQueue.reservePending()
        inferenceExecutor.execute {
            val topText = try {
                rec.recognizeBitmap(bmp, HCCRRecognizer.DEFAULT_TOP_K).firstOrNull()?.text
            } catch (t: Throwable) {
                Log.w(TAG, "HCCR flush inference error", t)
                null
            } finally {
                bmp.recycle()
            }
            mainHandler.post {
                pendingCommitQueue.resolve(token, topText).forEach { toCommit ->
                    listener?.onCommitText(toCommit)
                }
            }
        }
    }

    private fun commitWithPendingFlush(text: String) {
        mainHandler.removeCallbacks(recognizeRunnable)
        mainHandler.removeCallbacks(autoCommitRunnable)
        pendingAutoCommitAfterRecognition = false

        if (controller.hasUncommittedStrokes()) {
            val targetCanvas = activeCanvas()
            val isFresh = recognizedRevision == strokeRevision && recognizedBoxIndex == controller.activeBoxIndex
            if (isFresh) {
                val flushed = controller.flushPendingTopCandidate()
                strokeRevision++
                clearAllCanvasesImmediate()
                refreshCandidatesUi()
                if (!flushed.isNullOrEmpty()) {
                    commitOrdered(flushed)
                }
            } else {
                val rec = recognizer
                val snapshot = if (rec != null && targetCanvas.hasStrokes()) {
                    targetCanvas.renderToBitmap()
                } else {
                    null
                }
                val fallback = controller.flushPendingTopCandidate()
                strokeRevision++
                clearAllCanvasesImmediate()
                refreshCandidatesUi()
                if (rec != null && snapshot != null) {
                    enqueueAsyncFlushRecognition(rec, snapshot)
                } else if (!fallback.isNullOrEmpty()) {
                    commitOrdered(fallback)
                }
            }
        } else {
            controller.clearAll(clearLastAutoCommit = true)
            strokeRevision++
            clearAllCanvasesImmediate()
            refreshCandidatesUi()
        }

        commitOrdered(text)
    }

    private fun runLiveRecognition() {
        val rec = recognizer ?: return
        val targetBox = controller.activeBoxIndex
        val targetCanvas = canvasForBox(targetBox)
        if (!targetCanvas.hasStrokes()) return
        val targetRevision = strokeRevision
        val bmp = targetCanvas.renderToBitmap()

        inferenceExecutor.execute {
            val results = try {
                rec.recognizeBitmap(bmp, HCCRRecognizer.DEFAULT_TOP_K)
            } catch (t: Throwable) {
                Log.w(TAG, "HCCR inference error", t)
                emptyList()
            } finally {
                bmp.recycle()
            }
            mainHandler.post {
                if (targetRevision != strokeRevision || targetBox != controller.activeBoxIndex) {
                    return@post
                }
                recognizedRevision = targetRevision
                recognizedBoxIndex = targetBox
                controller.onRecognitionResult(results)
                refreshCandidatesUi()
                if (pendingAutoCommitAfterRecognition) {
                    pendingAutoCommitAfterRecognition = false
                    performAutoCommitAndFade()
                }
            }
        }
    }

    private fun onAutoCommitTimerFired() {
        if (!activeCanvas().hasStrokes()) return
        if (recognizedRevision != strokeRevision || recognizedBoxIndex != controller.activeBoxIndex) {
            // Recognition is still running (or debounced); run immediately if not yet started.
            pendingAutoCommitAfterRecognition = true
            mainHandler.removeCallbacks(recognizeRunnable)
            runLiveRecognition()
            return
        }
        performAutoCommitAndFade()
    }

    private fun performAutoCommitAndFade() {
        val targetBox = controller.activeBoxIndex
        val committed = controller.onAutoCommitTimeout(settings.handwritingAutoCommitFirst)
        if (!committed.isNullOrEmpty()) {
            commitOrdered(committed)
        }
        refreshCandidatesUi()
        canvasForBox(targetBox).startFadeOut(FADE_OUT_DURATION_MS) {
            onBoxFadeFinished(targetBox)
        }
    }

    private fun refreshCandidatesUi() {
        candidateRow.removeAllViews()
        val list = controller.candidates
        if (list.isEmpty()) {
            val hint = when {
                modelLoadError != null -> "手写模型加载失败: $modelLoadError"
                recognizer == null -> "正在加载手写模型…"
                settings.handwritingDualBoxEnabled -> "在下方双格交替手写汉字"
                else -> "在下方田字格手写汉字"
            }
            val tv = TextView(context).apply {
                text = hint
                setTextColor(PastelPalette.TEXT_MUTED)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4f), 0, dp(4f), 0)
            }
            candidateRow.addView(tv)
            return
        }

        list.forEachIndexed { index, item ->
            val highlightFirst = index == 0 && settings.handwritingAutoCommitFirst
            val chip = TextView(context).apply {
                text = item.text
                setTextColor(if (highlightFirst) PastelPalette.INK_ON_PASTEL else PastelPalette.TEXT_PRIMARY)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
                typeface = if (index == 0) {
                    Typeface.create("sans-serif-medium", Typeface.BOLD)
                } else {
                    Typeface.DEFAULT
                }
                gravity = Gravity.CENTER
                minWidth = dp(42f)
                setPadding(dp(12f), 0, dp(12f), 0)
                background = if (highlightFirst) {
                    pillStateBackground(PastelPalette.SKY_BLUE, PastelPalette.SKY_BLUE_PRESSED)
                } else {
                    pillStateBackground(PastelPalette.SURFACE_CHAR_KEY, PastelPalette.SURFACE_KEY_PRESSED)
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    dp(34f),
                ).apply {
                    marginEnd = dp(6f)
                }
                setOnClickListener {
                    triggerHaptic()
                    onCandidateChipClicked(item.text)
                }
            }
            candidateRow.addView(chip)
        }
        candidateScroll.scrollTo(0, 0)
    }

    private fun onCandidateChipClicked(candidateText: String) {
        mainHandler.removeCallbacks(recognizeRunnable)
        mainHandler.removeCallbacks(autoCommitRunnable)
        pendingAutoCommitAfterRecognition = false
        strokeRevision++

        when (val action = controller.onCandidateSelected(candidateText)) {
            is HandwritingController.CommitAction.Commit -> {
                activeCanvas().clearImmediate()
                refreshCandidatesUi()
                commitOrdered(action.text)
            }
            is HandwritingController.CommitAction.ReplaceLast -> {
                activeCanvas().clearImmediate()
                refreshCandidatesUi()
                listener?.onReplaceLastText(action.deleteChars, action.replacement)
            }
            HandwritingController.CommitAction.None -> Unit
        }
    }

    private fun createToolbarButton(
        label: String,
        widthDp: Float,
        weight: Float,
        isModifier: Boolean,
        isMutedText: Boolean = false,
        marginEndDp: Float = 6f,
        onClick: () -> Unit,
    ): TextView {
        return TextView(context).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (label.length > 1) 13.5f else 17f)
            typeface = Typeface.create("sans-serif-medium", if (isModifier || isMutedText) Typeface.NORMAL else Typeface.BOLD)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                if (weight > 0f) 0 else dp(widthDp),
                dp(38f),
                weight,
            ).apply {
                if (marginEndDp > 0f) {
                    marginEnd = dp(marginEndDp)
                }
            }
            setOnClickListener {
                triggerHaptic()
                onClick()
            }
            val style = ToolbarButtonStyle(this, isModifier, isMutedText)
            toolbarButtons += style
            styleToolbarButton(style)
        }
    }

    private fun styleToolbarButton(style: ToolbarButtonStyle) {
        val normalColor = if (style.isModifier) {
            PastelPalette.SURFACE_MOD_KEY
        } else {
            PastelPalette.SURFACE_CHAR_KEY
        }
        val pressedColor = if (style.isModifier) {
            PastelPalette.SURFACE_PRESSED
        } else {
            PastelPalette.SURFACE_KEY_PRESSED
        }
        val textColor = when {
            style.isModifier -> PastelPalette.TEXT_MOD_KEY
            style.isMutedText -> PastelPalette.TEXT_MOD_KEY
            else -> PastelPalette.TEXT_PRIMARY
        }
        style.view.setTextColor(textColor)
        style.view.background = pillStateBackground(normalColor, pressedColor)
    }

    private fun triggerHaptic() {
        if (settings.keyboardHapticEnabled) {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
    }

    private fun pillStateBackground(normalColor: Int, pressedColor: Int): StateListDrawable =
        StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), pillBackground(pressedColor))
            addState(intArrayOf(), pillBackground(normalColor))
        }

    private fun pillBackground(fillColor: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpF(8.5f)
            setColor(fillColor)
        }

    companion object {
        private const val TAG = "HandwritingPanelView"
        private const val RECOGNIZE_DEBOUNCE_MS = 150L
        private const val FADE_OUT_DURATION_MS = 250L
    }
}
