package com.litertime.android.ime.traditional

import android.text.InputType
import android.view.inputmethod.EditorInfo

/**
 * Pure keyboard layout & state definitions following FlorisBoard's QWERTY, Symbols (`?123`), and
 * Symbols2 (`=\<`) layouts, intentionally stripped of gray corner symbol hints and long-press
 * symbol popups as requested.
 */
object EnglishKeyboardLayout {

    enum class SubMode {
        CHARACTERS,
        SYMBOLS,
        SYMBOLS2,
        EMOJI,
    }

    enum class ShiftState {
        UNSHIFTED,
        SHIFTED,
        CAPS_LOCK,
    }

    enum class KeyKind {
        CHARACTER,
        SHIFT,
        BACKSPACE,
        MODE_SYMBOLS,
        MODE_SYMBOLS2,
        MODE_CHARACTERS,
        EMOJI_TOGGLE,
        SPACE,
        ENTER,
    }

    enum class CommaVariation {
        DEFAULT,
        EMAIL,
        URI,
    }

    data class KeySpec(
        val id: String,
        val label: String,
        val outputText: String = "",
        val weight: Float = 1f,
        val kind: KeyKind = KeyKind.CHARACTER,
    )

    data class RowSpec(
        val keys: List<KeySpec>,
        val horizontalPaddingRatio: Float = 0f,
    )

    private val numberRowKeys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")
    private val qwertyRow1 = listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p")
    private val qwertyRow2 = listOf("a", "s", "d", "f", "g", "h", "j", "k", "l")
    private val qwertyRow3 = listOf("z", "x", "c", "v", "b", "n", "m")

    private val symbolsRow1 = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")
    private val symbolsRow2 = listOf("@", "#", "$", "%", "&", "-", "+", "(", ")", "/")
    private val symbolsRow3 = listOf("*", "\"", "'", ":", ";", "!", "?")

    private val symbols2Row1 = listOf("~", "`", "|", "•", "√", "π", "÷", "×", "¶", "∆")
    private val symbols2Row2 = listOf("£", "¢", "€", "¥", "^", "°", "=", "{", "}", "\\")
    private val symbols2Row3 = listOf("_", "©", "®", "™", "✓", "[", "]")

    fun buildRows(
        subMode: SubMode,
        shiftState: ShiftState,
        showNumberRow: Boolean,
        commaVariation: CommaVariation,
        enterLabel: String,
    ): List<RowSpec> {
        return when (subMode) {
            SubMode.CHARACTERS -> buildCharactersRows(
                shiftState = shiftState,
                showNumberRow = showNumberRow,
                commaVariation = commaVariation,
                enterLabel = enterLabel,
            )
            SubMode.SYMBOLS -> buildSymbolsRows(enterLabel = enterLabel)
            SubMode.SYMBOLS2 -> buildSymbols2Rows(enterLabel = enterLabel)
            SubMode.EMOJI -> emptyList()
        }
    }

    private fun buildCharactersRows(
        shiftState: ShiftState,
        showNumberRow: Boolean,
        commaVariation: CommaVariation,
        enterLabel: String,
    ): List<RowSpec> {
        val uppercase = shiftState != ShiftState.UNSHIFTED
        fun letterKey(ch: String): KeySpec {
            val out = if (uppercase) ch.uppercase() else ch
            return KeySpec(id = "char_$ch", label = out, outputText = out)
        }

        val rows = mutableListOf<RowSpec>()
        if (showNumberRow) {
            rows += RowSpec(
                keys = numberRowKeys.map { d -> KeySpec(id = "num_$d", label = d, outputText = d) },
            )
        }
        rows += RowSpec(keys = qwertyRow1.map(::letterKey))
        rows += RowSpec(
            keys = qwertyRow2.map(::letterKey),
            horizontalPaddingRatio = 0.05f,
        )
        rows += RowSpec(
            keys = buildList {
                add(KeySpec(id = "shift", label = "", weight = 1.5f, kind = KeyKind.SHIFT))
                addAll(qwertyRow3.map(::letterKey))
                add(KeySpec(id = "backspace", label = "", weight = 1.5f, kind = KeyKind.BACKSPACE))
            },
        )
        val commaChar = when (commaVariation) {
            CommaVariation.EMAIL -> "@"
            CommaVariation.URI -> "/"
            CommaVariation.DEFAULT -> ","
        }
        rows += RowSpec(
            keys = listOf(
                KeySpec(id = "mode_sym", label = "?123", weight = 1.45f, kind = KeyKind.MODE_SYMBOLS),
                KeySpec(id = "comma", label = commaChar, outputText = commaChar, weight = 1.0f),
                KeySpec(id = "emoji", label = "😊", weight = 1.0f, kind = KeyKind.EMOJI_TOGGLE),
                KeySpec(id = "space", label = "English", outputText = " ", weight = 3.85f, kind = KeyKind.SPACE),
                KeySpec(id = "period", label = ".", outputText = ".", weight = 1.0f),
                KeySpec(id = "enter", label = enterLabel, weight = 1.7f, kind = KeyKind.ENTER),
            ),
        )
        return rows
    }

    private fun buildSymbolsRows(enterLabel: String): List<RowSpec> {
        return listOf(
            RowSpec(keys = symbolsRow1.map { KeySpec(id = "sym1_$it", label = it, outputText = it) }),
            RowSpec(keys = symbolsRow2.map { KeySpec(id = "sym2_$it", label = it, outputText = it) }),
            RowSpec(
                keys = buildList {
                    add(KeySpec(id = "mode_sym2", label = "=\\<", weight = 1.5f, kind = KeyKind.MODE_SYMBOLS2))
                    addAll(symbolsRow3.map { KeySpec(id = "sym3_$it", label = it, outputText = it) })
                    add(KeySpec(id = "backspace", label = "", weight = 1.5f, kind = KeyKind.BACKSPACE))
                },
            ),
            RowSpec(
                keys = listOf(
                    KeySpec(id = "mode_abc", label = "ABC", weight = 1.45f, kind = KeyKind.MODE_CHARACTERS),
                    KeySpec(id = "sym_comma", label = ",", outputText = ",", weight = 1.0f),
                    KeySpec(id = "emoji", label = "😊", weight = 1.0f, kind = KeyKind.EMOJI_TOGGLE),
                    KeySpec(id = "space", label = "English", outputText = " ", weight = 3.85f, kind = KeyKind.SPACE),
                    KeySpec(id = "sym_period", label = ".", outputText = ".", weight = 1.0f),
                    KeySpec(id = "enter", label = enterLabel, weight = 1.7f, kind = KeyKind.ENTER),
                ),
            ),
        )
    }

    private fun buildSymbols2Rows(enterLabel: String): List<RowSpec> {
        return listOf(
            RowSpec(keys = symbols2Row1.map { KeySpec(id = "sym2_1_$it", label = it, outputText = it) }),
            RowSpec(keys = symbols2Row2.map { KeySpec(id = "sym2_2_$it", label = it, outputText = it) }),
            RowSpec(
                keys = buildList {
                    add(KeySpec(id = "mode_sym1", label = "?123", weight = 1.5f, kind = KeyKind.MODE_SYMBOLS))
                    addAll(symbols2Row3.map { KeySpec(id = "sym2_3_$it", label = it, outputText = it) })
                    add(KeySpec(id = "backspace", label = "", weight = 1.5f, kind = KeyKind.BACKSPACE))
                },
            ),
            RowSpec(
                keys = listOf(
                    KeySpec(id = "mode_abc", label = "ABC", weight = 1.45f, kind = KeyKind.MODE_CHARACTERS),
                    KeySpec(id = "sym2_lt", label = "<", outputText = "<", weight = 1.0f),
                    KeySpec(id = "emoji", label = "😊", weight = 1.0f, kind = KeyKind.EMOJI_TOGGLE),
                    KeySpec(id = "space", label = "English", outputText = " ", weight = 3.85f, kind = KeyKind.SPACE),
                    KeySpec(id = "sym2_gt", label = ">", outputText = ">", weight = 1.0f),
                    KeySpec(id = "enter", label = enterLabel, weight = 1.7f, kind = KeyKind.ENTER),
                ),
            ),
        )
    }

    fun commaVariationFor(editorInfo: EditorInfo?): CommaVariation {
        if (editorInfo == null) return CommaVariation.DEFAULT
        val inputClass = editorInfo.inputType and InputType.TYPE_MASK_CLASS
        val variation = editorInfo.inputType and InputType.TYPE_MASK_VARIATION
        if (inputClass != InputType.TYPE_CLASS_TEXT) return CommaVariation.DEFAULT
        return when (variation) {
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS -> CommaVariation.EMAIL
            InputType.TYPE_TEXT_VARIATION_URI -> CommaVariation.URI
            else -> CommaVariation.DEFAULT
        }
    }

    fun enterLabelFor(editorInfo: EditorInfo?): String {
        if (editorInfo == null) return "↵"
        if ((editorInfo.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0) return "↵"
        return when (editorInfo.imeOptions and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_DONE -> "Done"
            EditorInfo.IME_ACTION_GO -> "Go"
            EditorInfo.IME_ACTION_NEXT -> "Next"
            EditorInfo.IME_ACTION_SEARCH -> "Search"
            EditorInfo.IME_ACTION_SEND -> "Send"
            else -> "↵"
        }
    }

    fun shouldAutoCapitalize(
        autoCapsEnabled: Boolean,
        editorInfo: EditorInfo?,
        textBeforeCursor: CharSequence?,
    ): Boolean {
        if (!autoCapsEnabled) return false
        if (editorInfo != null) {
            val inputClass = editorInfo.inputType and InputType.TYPE_MASK_CLASS
            if (inputClass != InputType.TYPE_CLASS_TEXT) return false
            val variation = editorInfo.inputType and InputType.TYPE_MASK_VARIATION
            if (variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS ||
                variation == InputType.TYPE_TEXT_VARIATION_URI ||
                variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            ) {
                return false
            }
        }
        if (textBeforeCursor.isNullOrEmpty()) return true
        val trimmedEnd = textBeforeCursor.toString().trimEnd(' ', '\t')
        if (trimmedEnd.isEmpty()) return true
        val last = trimmedEnd.last()
        return last == '\n' || last == '.' || last == '!' || last == '?'
    }

    fun shouldConvertDoubleSpaceToPeriod(
        enabled: Boolean,
        elapsedSinceLastSpaceMs: Long,
        textBeforeCursor: CharSequence?,
    ): Boolean {
        if (!enabled || elapsedSinceLastSpaceMs !in 1L..500L) return false
        if (textBeforeCursor.isNullOrEmpty() || textBeforeCursor.length < 2) return false
        val len = textBeforeCursor.length
        val last = textBeforeCursor[len - 1]
        val prev = textBeforeCursor[len - 2]
        return last == ' ' && (prev.isLetterOrDigit() || prev == ')' || prev == '"' || prev == '\'')
    }
}
