package com.litertime.android.ime

import org.junit.Assert.assertEquals
import org.junit.Test

class TrailingPeriodStripperTest {
    @Test
    fun dropsPeriodAtEndOfStream() {
        val stripper = TrailingPeriodStripper()
        assertEquals("你好", stripper.consume("你好。"))
        assertEquals("", stripper.flush())
    }

    @Test
    fun flushesHeldPeriodWhenMoreTextArrives() {
        val stripper = TrailingPeriodStripper()
        assertEquals("你好", stripper.consume("你好。"))
        assertEquals("。世界", stripper.consume("世界"))
        assertEquals("", stripper.flush())
    }

    @Test
    fun keepsPeriodsInsideASingleDelta() {
        val stripper = TrailingPeriodStripper()
        assertEquals("第一句。第二句", stripper.consume("第一句。第二句"))
        assertEquals("", stripper.flush())
    }

    @Test
    fun dropsPeriodButKeepsTrailingQuote() {
        val stripper = TrailingPeriodStripper()
        assertEquals("完成", stripper.consume("完成。”"))
        assertEquals("”", stripper.flush())
    }

    @Test
    fun dropsPeriodAndTrailingNewline() {
        val stripper = TrailingPeriodStripper()
        assertEquals("Done", stripper.consume("Done.\n"))
        assertEquals("", stripper.flush())
    }

    @Test
    fun resetDiscardsHeldText() {
        val stripper = TrailingPeriodStripper()
        assertEquals("好", stripper.consume("好。"))
        stripper.reset()
        assertEquals("", stripper.flush())
        assertEquals("下一句", stripper.consume("下一句"))
    }
}