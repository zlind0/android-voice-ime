package com.litertime.android.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Test

class WavEncoderTest {
    @Test
    fun wavHeaderAndFloatRoundTrip() {
        val pcmShorts = shortArrayOf(0, 16384, -16384, 32767, -32768)
        val pcmBytes = ByteArray(pcmShorts.size * 2)
        val buf = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN)
        pcmShorts.forEach { buf.putShort(it) }

        val wav = WavEncoder.pcm16ToWav(pcmBytes, sampleRate = 16000)
        assertEquals(44 + pcmBytes.size, wav.size)
        assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(wav, 8, 4, Charsets.US_ASCII))

        val (samples, rate) = WavEncoder.wavToFloatSamples(wav)
        assertEquals(16000, rate)
        assertEquals(pcmShorts.size, samples.size)
        assertEquals(0f, samples[0], 0.0001f)
        assertEquals(0.5f, samples[1], 0.0001f)
        assertEquals(-0.5f, samples[2], 0.0001f)
        assertEquals(32767f / 32768f, samples[3], 0.0001f)
        assertEquals(-1f, samples[4], 0.0001f)
    }
}
