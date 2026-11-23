package com.litertime.android.pipeline

import com.litertime.android.settings.ImeSettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Reserved online LLM backend implementing the unified [TextGenerationBackend] contract so
 * OpenAI-compatible or Gemini cloud APIs can be plugged in without changing the IME UI or IPC.
 */
class OnlineLlmBackend(
    private val settingsStore: ImeSettingsStore,
) : TextGenerationBackend {
    override val backendId: String = "online_llm"
    override val displayName: String = "在线大模型 (预留接口)"

    override suspend fun ensureLoaded(modelId: String, onStatus: (String) -> Unit) {
        val settings = settingsStore.getSettings()
        if (settings.onlineApiKey.isBlank()) {
            throw IllegalStateException("在线大模型接口已预留，请在设置中配置 API Key 并完成客户端实现")
        }
        onStatus("在线模型 ${settings.onlineModelName} 就绪")
    }

    override fun generateStream(request: VoiceGenerationRequest): Flow<GenerationEvent> = flow {
        val settings = settingsStore.getSettings()
        if (settings.onlineApiKey.isBlank()) {
            emit(GenerationEvent.Error("在线大模型接口已预留：请先在设置中填写 Base URL 与 API Key"))
            return@flow
        }
        emit(
            GenerationEvent.Error(
                "在线大模型 (${settings.llmProvider.name}: ${settings.onlineModelName}) 通道已预留，当前版本仅启用端侧 Gemma 4 与 Whisper 推理"
            ),
        )
    }

    override fun cancel(requestId: String) = Unit

    override fun unload() = Unit
}
