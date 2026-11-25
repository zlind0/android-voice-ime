package com.litertime.android.audio

import kotlin.math.abs

/**
 * Detects recordings that contain no usable speech.
 *
 * The rebuttal mode is the only AI-mic action where saying nothing is a valid choice: the user
 * just wants the on-screen viewpoint rebutted. Running Whisper on pure silence frequently
 * hallucinates a phrase ("谢谢观看" and friends), which would then be mistaken for the user's own
 * argument. Skipping ASR for effectively silent audio keeps the screenshot-only path clean.
 *
 * The threshold is deliberately conservative so softly spoken sentences are still transcribed.
 */
object SilenceDetector {
    /** Peak 16-bit sample magnitude (≈1.5% of full scale) below which audio counts as silent. */
    const val SPEECH_PEAK_THRESHOLD: Int = 500

    /** Returns true when [pcm16le] never rises above [SPEECH_PEAK_THRESHOLD]. */
    fun isEffectivelySilent(pcm16le: ByteArray): Boolean {
        if (pcm16le.size < 2) return true
        var peak = 0
        var index = 0
        while (index + 1 < pcm16le.size) {
            val raw = (pcm16le[index].toInt() and 0xFF) or (pcm16le[index + 1].toInt() shl 8)
            val magnitude = if (raw == Short.MIN_VALUE.toInt()) 32768 else abs(raw)
            if (magnitude > peak) peak = magnitude
            index += 2
        }
        return peak < SPEECH_PEAK_THRESHOLD
    }
}