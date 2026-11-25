package com.litertime.android.pipeline

import java.io.File
import kotlinx.coroutines.flow.Flow

enum class VoiceMode {
    WHISPER_RAW,
    GEMMA_POLISH_FORMAL,
    GEMMA_SCREENSHOT_CORRECTION,
    GEMMA_SCREENSHOT_INSTRUCTION,
}

data class VoiceGenerationRequest(
    val requestId: String,
    val mode: VoiceMode,
    val modelId: String,
    val audioWavFile: File,
    val screenshotFile: File? = null,
    val languageHint: String = "",
    /**
     * Text produced by Whisper/ASR when the voice cascade is enabled. When non-null the backend
     * consumes this text instead of the audio, and the prompts describe an ASR transcript.
     */
    val asrTranscript: String? = null,
    /**
     * True when [userPrompt] already *is* the ASR transcript (screenshot instruction mode), so
     * the backend must not append a separate transcript block and must not feed audio.
     */
    val userPromptIsTranscript: Boolean = false,
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

    /**
     * The online backend could not start (offline/timeout/bad config) before producing any token.
     * The host service should transparently continue with the on-device model.
     */
    data class FallbackToLocal(val reason: String) : GenerationEvent
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
