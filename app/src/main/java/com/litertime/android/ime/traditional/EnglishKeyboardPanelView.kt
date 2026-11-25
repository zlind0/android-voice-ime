package com.litertime.android.ime.traditional

import android.annotation.SuppressLint
import android.content.Context
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import com.litertime.android.settings.ImeSettings
import com.litertime.android.ui.PastelPalette

/**
 * English traditional keyboard panel switching between [QwertyKeyboardView] (QWERTY, Symbols,
 * Symbols2) and the shared [EmojiPickerView].
 */
@SuppressLint("ViewConstructor")
class EnglishKeyboardPanelView(context: Context) : FrameLayout(context) {

    interface Listener {
        fun onCommitText(text: String)
        fun onReplaceLastCharacters(deleteCount: Int, replacement: String)
        fun onBackspaceClick()
        fun onEnterClick()
        fun getTextBeforeCursor(count: Int): CharSequence?
        fun onRecentEmojiUsed(emoji: String)
    }

    var listener: Listener? = null

    private var settings = ImeSettings()
    private var editorInfo: EditorInfo? = null
    private var lastSpacePressMs = 0L

    private val qwertyView: QwertyKeyboardView
    private val emojiView: EmojiPickerView

    init {
        setBackgroundColor(PastelPalette.BG_CANVAS)

        qwertyView = QwertyKeyboardView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            listener = object : QwertyKeyboardView.Listener {
                override fun onCommitText(text: String) {
                    this@EnglishKeyboardPanelView.listener?.onCommitText(text)
                    refreshAutoShift()
                }

                override fun onSpaceKeyPressed() {
                    val now = System.currentTimeMillis()
                    val elapsed = if (lastSpacePressMs == 0L) Long.MAX_VALUE else now - lastSpacePressMs
                    lastSpacePressMs = now
                    val before = this@EnglishKeyboardPanelView.listener?.getTextBeforeCursor(4)
                    if (EnglishKeyboardLayout.shouldConvertDoubleSpaceToPeriod(
                            enabled = settings.keyboardDoubleSpacePeriod,
                            elapsedSinceLastSpaceMs = elapsed,
                            textBeforeCursor = before,
                        )
                    ) {
                        this@EnglishKeyboardPanelView.listener?.onReplaceLastCharacters(1, ". ")
                        lastSpacePressMs = 0L
                    } else {
                        this@EnglishKeyboardPanelView.listener?.onCommitText(" ")
                    }
                    refreshAutoShift()
                }

                override fun onBackspaceClick() {
                    lastSpacePressMs = 0L
                    this@EnglishKeyboardPanelView.listener?.onBackspaceClick()
                    refreshAutoShift()
                }

                override fun onEnterClick() {
                    lastSpacePressMs = 0L
                    this@EnglishKeyboardPanelView.listener?.onEnterClick()
                }

                override fun onOpenEmojiPicker() {
                    showEmojiPicker()
                }
            }
        }
        addView(qwertyView)

        emojiView = EmojiPickerView(context, initialReturnLabel = "ABC").apply {
            visibility = GONE
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            listener = object : EmojiPickerView.Listener {
                override fun onEmojiSelected(emoji: String) {
                    this@EnglishKeyboardPanelView.listener?.onCommitText(emoji)
                    this@EnglishKeyboardPanelView.listener?.onRecentEmojiUsed(emoji)
                }

                override fun onBackClick() {
                    showQwerty()
                }

                override fun onBackspaceClick() {
                    this@EnglishKeyboardPanelView.listener?.onBackspaceClick()
                }
            }
        }
        addView(emojiView)
    }

    fun applySettings(newSettings: ImeSettings) {
        settings = newSettings
        setBackgroundColor(PastelPalette.BG_CANVAS)
        qwertyView.applySettings(newSettings)
        emojiView.applyPalette()
        emojiView.setHapticEnabled(newSettings.keyboardHapticEnabled)
        emojiView.setRecentEmojis(newSettings.recentEmojiList())
    }

    fun onStartInput(info: EditorInfo?) {
        editorInfo = info
        lastSpacePressMs = 0L
        val before = listener?.getTextBeforeCursor(16)
        val autoCaps = EnglishKeyboardLayout.shouldAutoCapitalize(
            autoCapsEnabled = settings.keyboardAutoCapsEnabled,
            editorInfo = info,
            textBeforeCursor = before,
        )
        qwertyView.configureForInput(info, autoCaps)
    }

    fun resetToQwerty() {
        showQwerty()
        qwertyView.resetToCharacters()
        onStartInput(editorInfo)
    }

    private fun refreshAutoShift() {
        val before = listener?.getTextBeforeCursor(16)
        val autoCaps = EnglishKeyboardLayout.shouldAutoCapitalize(
            autoCapsEnabled = settings.keyboardAutoCapsEnabled,
            editorInfo = editorInfo,
            textBeforeCursor = before,
        )
        qwertyView.updateAutoShift(autoCaps)
    }

    private fun showEmojiPicker() {
        qwertyView.visibility = GONE
        emojiView.visibility = VISIBLE
        emojiView.resetToDefaultTab()
    }

    private fun showQwerty() {
        emojiView.visibility = GONE
        qwertyView.visibility = VISIBLE
    }
}
