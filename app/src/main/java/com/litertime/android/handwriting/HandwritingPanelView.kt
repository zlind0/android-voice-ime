package com.litertime.android.handwriting

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
import java.util.concurrent.Executors

/**
 * Complete Chinese handwriting panel powered by `Ismantic/Handwritten`:
 * - Candidate bar on top (top-10 live predictions)
 * - 田字格 writing pad in the middle (auto-commits top-1 & fades out after configurable delay)
 * - Bottom bar: [标点] [😊] [，] [。] [！] [？] [空格]
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
    private var pendingAutoCommitAfterRecognition = false

    private val mainContent: LinearLayout
    private val candidateScroll: HorizontalScrollView
    private val candidateRow: LinearLayout
    private val canvasView: HandwritingCanvasView
    private val punctuationView: ChinesePunctuationView
    private val emojiView: EmojiPickerView

    private val recognizeRunnable = Runnable { runLiveRecognition() }
    private val autoCommitRunnable = Runnable { onAutoCommitTimerFired() }

    init {
        setBackgroundColor(0xFF1A1D24.toInt())

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

        // Writing pad (田字格)
        canvasView = HandwritingCanvasView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ).apply {
                setMargins(dp(10f), dp(2f), dp(10f), dp(6f))
            }
            strokeListener = object : HandwritingCanvasView.StrokeListener {
                override fun onStrokeStart() {
                    mainHandler.removeCallbacks(recognizeRunnable)
                    mainHandler.removeCallbacks(autoCommitRunnable)
                    pendingAutoCommitAfterRecognition = false
                    val interruptedFade = controller.onStrokeStart()
                    if (interruptedFade) {
                        refreshCandidatesUi()
                    }
                }

                override fun onStrokeEnd() {
                    controller.onStrokeEnd()
                    strokeRevision++
                    mainHandler.removeCallbacks(recognizeRunnable)
                    mainHandler.removeCallbacks(autoCommitRunnable)
                    val delayMs = settings.handwritingFadeDelayMs.toLong()
                    val recDelay = minOf(RECOGNIZE_DEBOUNCE_MS, (delayMs / 2L).coerceAtLeast(30L))
                    mainHandler.postDelayed(recognizeRunnable, recDelay)
                    mainHandler.postDelayed(autoCommitRunnable, delayMs)
                }
            }
        }
        mainContent.addView(canvasView)

        // Bottom toolbar (44dp): [标点] [😊] [，] [。] [！] [？] [空格]
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
            createToolbarButton(label = "标点", widthDp = 50f, weight = 0f, isAccent = false) {
                showSubView(SubView.PUNCTUATION)
            },
        )
        bottomToolbar.addView(
            createToolbarButton(label = "😊", widthDp = 42f, weight = 0f, isAccent = false) {
                showSubView(SubView.EMOJI)
            },
        )
        listOf("，", "。", "！", "？").forEach { punct ->
            bottomToolbar.addView(
                createToolbarButton(label = punct, widthDp = 0f, weight = 1f, isAccent = false) {
                    commitWithPendingFlush(punct)
                },
            )
        }
        bottomToolbar.addView(
            createToolbarButton(
                label = "空格",
                widthDp = 0f,
                weight = 1.45f,
                isAccent = false,
                marginEndDp = 0f,
            ) {
                commitWithPendingFlush(" ")
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
        settings = newSettings
        canvasView.applyStyle(
            strokeWidthDp = newSettings.handwritingStrokeWidthDp,
            inkColorArgb = newSettings.handwritingInkColor.argb,
            showGridLines = newSettings.handwritingShowGrid,
        )
        punctuationView.setHapticEnabled(newSettings.keyboardHapticEnabled)
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
                canvasView.clearImmediate()
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
        val flushed = controller.flushPendingTopCandidate()
        canvasView.clearImmediate()
        refreshCandidatesUi()
        if (!flushed.isNullOrEmpty()) {
            listener?.onCommitText(flushed)
        }
    }

    fun clearSession() {
        mainHandler.removeCallbacks(recognizeRunnable)
        mainHandler.removeCallbacks(autoCommitRunnable)
        pendingAutoCommitAfterRecognition = false
        strokeRevision++
        controller.clearAll(clearLastAutoCommit = true)
        canvasView.clearImmediate()
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

    private fun commitWithPendingFlush(text: String) {
        flushPendingCharacter()
        listener?.onCommitText(text)
    }

    private fun runLiveRecognition() {
        val rec = recognizer ?: return
        if (!canvasView.hasStrokes()) return
        val targetRevision = strokeRevision
        val bmp = canvasView.renderToBitmap()

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
                if (targetRevision != strokeRevision) {
                    return@post
                }
                recognizedRevision = targetRevision
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
        if (!canvasView.hasStrokes()) return
        if (recognizedRevision != strokeRevision) {
            // Recognition is still running (or debounced); run immediately if not yet started.
            pendingAutoCommitAfterRecognition = true
            mainHandler.removeCallbacks(recognizeRunnable)
            runLiveRecognition()
            return
        }
        performAutoCommitAndFade()
    }

    private fun performAutoCommitAndFade() {
        val committed = controller.onAutoCommitTimeout(settings.handwritingAutoCommitFirst)
        if (!committed.isNullOrEmpty()) {
            listener?.onCommitText(committed)
        }
        refreshCandidatesUi()
        canvasView.startFadeOut(FADE_OUT_DURATION_MS) {
            controller.onFadeFinished()
            refreshCandidatesUi()
        }
    }

    private fun refreshCandidatesUi() {
        candidateRow.removeAllViews()
        val list = controller.candidates
        if (list.isEmpty()) {
            val hint = when {
                modelLoadError != null -> "手写模型加载失败: $modelLoadError"
                recognizer == null -> "正在加载手写模型…"
                else -> "在下方田字格手写汉字"
            }
            val tv = TextView(context).apply {
                text = hint
                setTextColor(0x88FFFFFF.toInt())
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
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
                typeface = if (index == 0) {
                    Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                } else {
                    Typeface.DEFAULT
                }
                gravity = Gravity.CENTER
                minWidth = dp(42f)
                setPadding(dp(12f), 0, dp(12f), 0)
                background = if (highlightFirst) {
                    pillBackground(0xFF263B5E.toInt(), strokeColor = 0xFF60A5FA.toInt())
                } else {
                    pillBackground(0xFF282D38.toInt())
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
                canvasView.clearImmediate()
                refreshCandidatesUi()
                listener?.onCommitText(action.text)
            }
            is HandwritingController.CommitAction.ReplaceLast -> {
                canvasView.clearImmediate()
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
        isAccent: Boolean,
        marginEndDp: Float = 6f,
        onClick: () -> Unit,
    ): TextView {
        return TextView(context).apply {
            text = label
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (label.length > 1) 14f else 17f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = pillBackground(if (isAccent) 0xFF2563EB.toInt() else 0xFF2B313D.toInt())
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
        }
    }

    private fun triggerHaptic() {
        if (settings.keyboardHapticEnabled) {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
    }

    private fun pillBackground(fillColor: Int, strokeColor: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpF(8f)
            setColor(fillColor)
            if (strokeColor != null) {
                setStroke(dp(1f).coerceAtLeast(1), strokeColor)
            }
        }

    companion object {
        private const val TAG = "HandwritingPanelView"
        private const val RECOGNIZE_DEBOUNCE_MS = 150L
        private const val FADE_OUT_DURATION_MS = 250L
    }
}
