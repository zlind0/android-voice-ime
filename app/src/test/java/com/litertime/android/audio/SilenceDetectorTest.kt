package com.litertime.android.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SilenceDetectorTest {
    @Test
    fun emptyAndShortBuffersCountAsSilent() {
        assertTrue(SilenceDetector.isEffectivelySilent(ByteArray(0)))
        assertTrue(SilenceDetector.isEffectivelySilent(byteArrayOf(0x7F)))
    }

    @Test
    fun digitalSilenceAndLowNoiseCountAsSilent() {
        assertTrue(SilenceDetector.isEffectivelySilent(pcm16Samples(List(400) { 0 })))
        assertTrue(SilenceDetector.isEffectivelySilent(pcm16Samples(List(400) { if (it % 2 == 0) 120 else -140 })))
    }

    @Test
    fun speechLevelSamplesAreNotSilent() {
        assertFalse(SilenceDetector.isEffectivelySilent(pcm16Samples(List(400) { if (it % 2 == 0) 2400 else -3100 })))
        // A single loud transient is enough to keep the recording, even if surrounded by silence.
        val mostlySilent = MutableList(400) { 0 }
        mostlySilent[200] = 2000
        assertFalse(SilenceDetector.isEffectivelySilent(pcm16Samples(mostlySilent)))
    }

    @Test
    fun minimumNegativeSampleValueIsHandled() {
        assertFalse(
            SilenceDetector.isEffectivelySilent(
                pcm16Samples(listOf(Short.MIN_VALUE.toInt())),
            ),
        )
    }

    private fun pcm16Samples(samples: List<Int>): ByteArray {
        val bytes = ByteArray(samples.size * 2)
        samples.forEachIndexed { index, value ->
            bytes[index * 2] = (value and 0xFF).toByte()
            bytes[index * 2 + 1] = ((value shr 8) and 0xFF).toByte()
        }
        return bytes
    }
}