package com.litertime.android.pipeline

import java.io.File
import kotlinx.coroutines.flow.Flow

enum class VoiceMode {
    WHISPER_RAW,
    GEMMA_POLISH_NORMAL,
    GEMMA_POLISH_FORMAL,
    GEMMA_SCREENSHOT_INSTRUCTION,
}

data class VoiceGenerationRequest(
    val requestId: String,
    val mode: VoiceMode,
    val modelId: String,
    val audioWavFile: File,
    val screenshotFile: File? = null,
    val languageHint: String = "",
    /** Optional override for the system prompt, used by the debug playground. */
    val systemPrompt: String? = null,
    /** Optional override for the mode's user prompt, used by the debug playground. */
    val userPrompt: String? = null,
)

sealed interface GenerationEvent {
    data class Status(val message: String) : GenerationEvent
    data class Token(val delta: String) : GenerationEvent
    data object Completed : GenerationEvent
    data class Error(val message: String) : GenerationEvent
}

/**
 * Unified streaming generation contract decoupling the IME / IPC layer from concrete inference
 * implementations (on-device LiteRT-LM Gemma 4, sherpa-onnx Whisper, or future online LLM APIs).
 */
interface TextGenerationBackend {
    val backendId: String
    val displayName: String

    suspend fun ensureLoaded(modelId: String, onStatus: (String) -> Unit)

    fun generateStream(request: VoiceGenerationRequest): Flow<GenerationEvent>

    fun cancel(requestId: String)

    fun unload()
}
