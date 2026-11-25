package com.litertime.android.ime.traditional

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnglishKeyboardLayoutTest {
    @Test
    fun qwertyLayoutHasExpectedRowsAndOptionalNumberRow() {
        val withoutNumbers = EnglishKeyboardLayout.buildRows(
            subMode = EnglishKeyboardLayout.SubMode.CHARACTERS,
            shiftState = EnglishKeyboardLayout.ShiftState.UNSHIFTED,
            showNumberRow = false,
            commaVariation = EnglishKeyboardLayout.CommaVariation.DEFAULT,
            enterLabel = "↵",
        )
        assertEquals(4, withoutNumbers.size)
        assertEquals(listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"), withoutNumbers[0].keys.map { it.label })

        val withNumbers = EnglishKeyboardLayout.buildRows(
            subMode = EnglishKeyboardLayout.SubMode.CHARACTERS,
            shiftState = EnglishKeyboardLayout.ShiftState.SHIFTED,
            showNumberRow = true,
            commaVariation = EnglishKeyboardLayout.CommaVariation.EMAIL,
            enterLabel = "Send",
        )
        assertEquals(5, withNumbers.size)
        assertEquals(listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"), withNumbers[0].keys.map { it.label })
        assertEquals("Q", withNumbers[1].keys[0].label)
        assertTrue(withNumbers[4].keys.any { it.label == "@" })
        assertTrue(withNumbers[4].keys.any { it.label == "Send" })
    }

    @Test
    fun symbolsAndSymbols2LayoutsMatchFlorisBoardStructure() {
        val sym1 = EnglishKeyboardLayout.buildRows(
            subMode = EnglishKeyboardLayout.SubMode.SYMBOLS,
            shiftState = EnglishKeyboardLayout.ShiftState.UNSHIFTED,
            showNumberRow = false,
            commaVariation = EnglishKeyboardLayout.CommaVariation.DEFAULT,
            enterLabel = "↵",
        )
        assertEquals(4, sym1.size)
        assertEquals("@", sym1[1].keys[0].label)
        assertEquals("=\\<", sym1[2].keys[0].label)
        assertEquals("ABC", sym1[3].keys[0].label)

        val sym2 = EnglishKeyboardLayout.buildRows(
            subMode = EnglishKeyboardLayout.SubMode.SYMBOLS2,
            shiftState = EnglishKeyboardLayout.ShiftState.UNSHIFTED,
            showNumberRow = false,
            commaVariation = EnglishKeyboardLayout.CommaVariation.DEFAULT,
            enterLabel = "↵",
        )
        assertEquals(4, sym2.size)
        assertEquals("~", sym2[0].keys[0].label)
        assertEquals("£", sym2[1].keys[0].label)
        assertEquals("?123", sym2[2].keys[0].label)
    }

    @Test
    fun autoCapsAndDoubleSpaceToPeriodBehaveCorrectly() {
        assertTrue(EnglishKeyboardLayout.shouldAutoCapitalize(true, null, ""))
        assertTrue(EnglishKeyboardLayout.shouldAutoCapitalize(true, null, "Hello. "))
        assertFalse(EnglishKeyboardLayout.shouldAutoCapitalize(true, null, "Hello"))
        assertFalse(EnglishKeyboardLayout.shouldAutoCapitalize(false, null, ""))

        assertTrue(EnglishKeyboardLayout.shouldConvertDoubleSpaceToPeriod(true, 200L, "word "))
        assertFalse(EnglishKeyboardLayout.shouldConvertDoubleSpaceToPeriod(true, 200L, "  "))
        assertFalse(EnglishKeyboardLayout.shouldConvertDoubleSpaceToPeriod(true, 700L, "word "))
        assertFalse(EnglishKeyboardLayout.shouldConvertDoubleSpaceToPeriod(false, 200L, "word "))
    }
}
