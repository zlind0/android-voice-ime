package com.litertime.android.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

object WavEncoder {
    const val SAMPLE_RATE: Int = 16_000
    const val CHANNELS: Int = 1
    const val BITS_PER_SAMPLE: Int = 16

    /**
     * Wraps raw little-endian 16-bit mono PCM bytes with a standard 44-byte RIFF/WAVE header.
     */
    fun pcm16ToWav(pcmBytes: ByteArray, sampleRate: Int = SAMPLE_RATE): ByteArray {
        val pcmDataSize = pcmBytes.size
        val riffChunkSize = pcmDataSize + 36
        val byteRate = sampleRate * CHANNELS * (BITS_PER_SAMPLE / 8)
        val blockAlign = CHANNELS * (BITS_PER_SAMPLE / 8)

        val out = ByteArray(44 + pcmDataSize)
        val header = ByteBuffer.wrap(out, 0, 44).order(ByteOrder.LITTLE_ENDIAN)
        header.put('R'.code.toByte())
        header.put('I'.code.toByte())
        header.put('F'.code.toByte())
        header.put('F'.code.toByte())
        header.putInt(riffChunkSize)
        header.put('W'.code.toByte())
        header.put('A'.code.toByte())
        header.put('V'.code.toByte())
        header.put('E'.code.toByte())
        header.put('f'.code.toByte())
        header.put('m'.code.toByte())
        header.put('t'.code.toByte())
        header.put(' '.code.toByte())
        header.putInt(16) // PCM subchunk1 size
        header.putShort(1) // AudioFormat = PCM
        header.putShort(CHANNELS.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort(blockAlign.toShort())
        header.putShort(BITS_PER_SAMPLE.toShort())
        header.put('d'.code.toByte())
        header.put('a'.code.toByte())
        header.put('t'.code.toByte())
        header.put('a'.code.toByte())
        header.putInt(pcmDataSize)

        System.arraycopy(pcmBytes, 0, out, 44, pcmDataSize)
        return out
    }

    /**
     * Parses a 16-bit PCM WAV byte array into normalized [-1f, 1f] float samples and sample rate.
     */
    fun wavToFloatSamples(wavBytes: ByteArray): Pair<FloatArray, Int> {
        require(wavBytes.size >= 44) { "Invalid WAV data: too short (${wavBytes.size} bytes)" }
        val buffer = ByteBuffer.wrap(wavBytes).order(ByteOrder.LITTLE_ENDIAN)
        val riff = String(wavBytes, 0, 4, Charsets.US_ASCII)
        val wave = String(wavBytes, 8, 4, Charsets.US_ASCII)
        require(riff == "RIFF" && wave == "WAVE") { "Invalid WAV header" }

        var offset = 12
        var sampleRate = SAMPLE_RATE
        var dataOffset = -1
        var dataSize = 0

        while (offset + 8 <= wavBytes.size) {
            val chunkId = String(wavBytes, offset, 4, Charsets.US_ASCII)
            val chunkSize = buffer.getInt(offset + 4)
            val payloadOffset = offset + 8
            if (chunkId == "fmt " && payloadOffset + 16 <= wavBytes.size) {
                sampleRate = buffer.getInt(payloadOffset + 4)
            } else if (chunkId == "data") {
                dataOffset = payloadOffset
                dataSize = chunkSize.coerceAtMost(wavBytes.size - payloadOffset).coerceAtLeast(0)
                break
            }
            val paddedSize = chunkSize + (chunkSize and 1)
            offset = payloadOffset + paddedSize
        }

        if (dataOffset < 0) {
            dataOffset = 44
            dataSize = (wavBytes.size - 44).coerceAtLeast(0)
        }

        val sampleCount = dataSize / 2
        val samples = FloatArray(sampleCount)
        val pcmBuffer = ByteBuffer.wrap(wavBytes, dataOffset, sampleCount * 2)
            .order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until sampleCount) {
            samples[i] = pcmBuffer.short / 32768.0f
        }
        return samples to sampleRate
    }

    fun calculateNormalizedPeak(pcmBytes: ByteArray, bytesRead: Int): Float {
        if (bytesRead < 2) return 0f
        var maxSample = 0
        var i = 0
        while (i + 1 < bytesRead) {
            val lo = pcmBytes[i].toInt() and 0xFF
            val hi = pcmBytes[i + 1].toInt()
            val sample = (hi shl 8) or lo
            val absVal = abs(sample)
            if (absVal > maxSample) maxSample = absVal
            i += 2
        }
        return (maxSample / 32767f).coerceIn(0f, 1f)
    }
}
