package com.litertime.android.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val TAG = "AudioCaptureManager"
private const val MAX_RECORD_MS = 30_000L

/**
 * Captures 16 kHz mono PCM16 audio into memory for Whisper / Gemma 4 audio inference.
 */
class AudioCaptureManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var recorder: AudioRecord? = null
    private var captureJob: Job? = null
    private val isRecording = AtomicBoolean(false)
    private val streamLock = Any()
    private val pcmStream = ByteArrayOutputStream()

    @SuppressLint("MissingPermission")
    fun start(
        onAmplitude: (Float) -> Unit,
        onError: (String) -> Unit,
    ): Boolean {
        if (isRecording.get()) return false

        val minBuf = AudioRecord.getMinBufferSize(
            WavEncoder.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) {
            onError("无法初始化麦克风缓冲区")
            return false
        }
        val bufferSize = (minBuf * 2).coerceAtLeast(4096)

        val audioRecord = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                WavEncoder.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create AudioRecord", e)
            onError("创建录音器失败: ${e.message}")
            return false
        }

        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            onError("麦克风初始化失败，请确认权限")
            return false
        }

        synchronized(streamLock) {
            pcmStream.reset()
        }
        recorder = audioRecord
        isRecording.set(true)

        captureJob = scope.launch {
            val readBuffer = ByteArray(bufferSize)
            val startAt = System.currentTimeMillis()
            try {
                audioRecord.startRecording()
                while (isActive && isRecording.get()) {
                    val read = audioRecord.read(readBuffer, 0, readBuffer.size)
                    if (read > 0) {
                        synchronized(streamLock) {
                            pcmStream.write(readBuffer, 0, read)
                        }
                        onAmplitude(WavEncoder.calculateNormalizedPeak(readBuffer, read))
                    } else if (read < 0) {
                        Log.w(TAG, "AudioRecord.read returned error code: $read")
                        break
                    }
                    if (System.currentTimeMillis() - startAt >= MAX_RECORD_MS) {
                        break
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Audio capture error", e)
                onError("录音异常: ${e.message}")
            }
        }
        return true
    }

    fun stopAndCollectPcm(): ByteArray {
        isRecording.set(false)
        val current = recorder
        recorder = null
        try {
            if (current?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                current.stop()
            }
        } catch (e: Exception) {
            Log.w(TAG, "stop() warning: ${e.message}")
        } finally {
            try {
                current?.release()
            } catch (_: Exception) {
            }
        }
        captureJob?.cancel()
        captureJob = null
        return synchronized(streamLock) {
            val bytes = pcmStream.toByteArray()
            pcmStream.reset()
            bytes
        }
    }

    fun cancel() {
        stopAndCollectPcm()
    }
}
