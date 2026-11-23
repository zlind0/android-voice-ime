package com.litertime.android.ime

/**
 * Holds back a trailing run of sentence-final periods (plus closing quotes/brackets and
 * whitespace) while the model streams, so a period that ends the whole utterance never reaches
 * the editor.
 *
 * A period followed by more text is flushed unchanged; only periods in the very last trailing
 * run are dropped by [flush]. Closing quotes collected after a dropped period are still
 * committed, and trailing whitespace is discarded.
 */
class TrailingPeriodStripper {
    private val pending = StringBuilder()

    /** Consumes a streamed delta and returns the text that is safe to commit right now. */
    fun consume(delta: String): String {
        if (delta.isEmpty()) return ""
        pending.append(delta)
        var cut = pending.length
        while (cut > 0 && isHoldable(pending[cut - 1])) cut--
        val head = pending.substring(0, cut)
        val tail = pending.substring(cut)
        pending.setLength(0)
        pending.append(tail)
        return head
    }

    /** Ends the stream: returns the closing quotes/brackets that trailed the dropped periods. */
    fun flush(): String {
        val tail = pending.toString()
        pending.setLength(0)
        return tail.filter { it in CLOSERS }
    }

    fun reset() {
        pending.setLength(0)
    }

    private fun isHoldable(ch: Char): Boolean = ch in PERIODS || ch in CLOSERS || ch.isWhitespace()

    private companion object {
        val PERIODS = setOf('。', '.', '．')
        val CLOSERS = setOf('”', '’', '"', '\'', '」', '』', '）', ')', '】', ']', '》', '〉')
    }
}