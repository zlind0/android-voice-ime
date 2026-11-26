package com.litertime.android.pipeline

import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import com.litertime.android.pipeline.online.OnlineLlmApi
import com.litertime.android.pipeline.online.OnlineLlmApiException
import com.litertime.android.pipeline.online.OnlineRequestOptions
import com.litertime.android.settings.ImeSettingsStore
import com.litertime.android.settings.ResolvedOnlineLlmConfig
import com.litertime.android.vision.ImageEncoder
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import okhttp3.Call
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "OnlineLlmBackend"

/** Upper bound for the raw body kept to parse a single non-streaming completion object. */
private const val MAX_RAW_RESPONSE_CHARS = 256 * 1024

/**
 * OpenAI-compatible online backend. It consumes the ASR transcript (or the screenshot instruction
 * text) and streams the chat-completion answer through the same contract as the on-device model.
 *
 * Network failures, timeouts and 5xx responses are surfaced as [GenerationEvent.FallbackToLocal]
 * when no token has been emitted yet, so the inference service can transparently continue with the
 * local model.
 */
class OnlineLlmBackend(
    private val settingsStore: ImeSettingsStore,
) : TextGenerationBackend {
    override val backendId: String = "online_llm"
    override val displayName: String = "在线大模型 (OpenAI 兼容)"

    private val activeCalls = ConcurrentHashMap<String, Call>()

    override suspend fun ensureLoaded(modelId: String, onStatus: (String) -> Unit) {
        val config = resolvedConfig(modelId)
        if (!config.isConfigured) {
            throw IllegalStateException("请先在设置中填写在线大模型的 Endpoint 与 Model")
        }
        onStatus("在线模型 ${config.modelName} 就绪")
    }

    override fun generateStream(request: VoiceGenerationRequest): Flow<GenerationEvent> = flow {
        val config = request.onlineConfigOverride ?: resolvedConfig(request.modelId, request.mode)
        if (!config.isConfigured) {
            emit(GenerationEvent.FallbackToLocal("在线大模型尚未配置 Endpoint 或 Model"))
            return@flow
        }
        val transcript = request.asrTranscript
        if (transcript == null) {
            emit(GenerationEvent.FallbackToLocal("在线大模型需要 Whisper 语音识别文本"))
            return@flow
        }

        val body = buildRequestBody(request, config, transcript)
        val call = OnlineLlmApi.newChatCall(config.baseUrl, config.apiKey, body)
        activeCalls[request.requestId] = call
        var emittedToken = false
        var terminal = false
        try {
            emit(GenerationEvent.Status("在线模型 ${config.modelName} 正在生成..."))
            val response = OnlineLlmApi.openChatStream(call)
            response.use { res ->
                if (!res.isSuccessful) {
                    terminal = true
                    val error = OnlineLlmApi.apiError(res)
                    if (error.transient) {
                        emit(GenerationEvent.FallbackToLocal(error.message ?: "在线请求失败"))
                    } else {
                        emit(GenerationEvent.Error(error.message ?: "在线请求失败"))
                    }
                    return@use
                }
                val bodySource = res.body ?: run {
                    terminal = true
                    emit(GenerationEvent.FallbackToLocal("在线模型未返回响应体"))
                    return@use
                }
                // Consume the body line by line so tokens reach the IME as soon as the server
                // flushes them. The content type is deliberately NOT used to pick a buffered
                // path: some OpenAI-compatible gateways label a streaming response as
                // `application/json`, which used to make the whole answer appear at once.
                val rawResponse = StringBuilder()
                var sawStreamFrame = false
                bodySource.source().use { source ->
                    while (true) {
                        val line = source.readUtf8Line() ?: break
                        val trimmed = line.trim()
                        if (trimmed.isEmpty()) continue
                        if (trimmed.startsWith(":")) continue // SSE comment / keep-alive
                        if (trimmed.startsWith("data:")) {
                            sawStreamFrame = true
                            val payload = trimmed.removePrefix("data:").trim()
                            if (payload == "[DONE]") break
                            val text = runCatching { parseSseDelta(payload) }.getOrNull().orEmpty()
                            if (text.isEmpty()) continue
                            emittedToken = true
                            emit(GenerationEvent.Token(text))
                            continue
                        }
                        // Bare JSON line: either an NDJSON stream delta or a single completion
                        // object (some providers ignore `stream: true`). Keep the raw text only
                        // until the first token arrives so a pretty-printed object still parses.
                        if (!emittedToken && rawResponse.length < MAX_RAW_RESPONSE_CHARS) {
                            rawResponse.append(line).append('\n')
                        }
                        val text = runCatching { parseSseDelta(trimmed) }.getOrNull().orEmpty()
                        if (text.isNotEmpty()) {
                            sawStreamFrame = true
                            emittedToken = true
                            emit(GenerationEvent.Token(text))
                        }
                    }
                }
                if (!emittedToken && !sawStreamFrame && rawResponse.isNotBlank()) {
                    val text = runCatching { OnlineLlmApi.parseAssistantText(rawResponse.toString()) }
                        .getOrElse { error ->
                            terminal = true
                            emit(GenerationEvent.Error("解析在线模型响应失败：${error.message}"))
                            return@use
                        }
                    if (text.isNotEmpty()) {
                        emittedToken = true
                        emit(GenerationEvent.Token(text))
                    }
                }
            }
            if (!terminal) {
                if (!emittedToken) {
                    emit(GenerationEvent.Error("在线模型没有返回内容"))
                } else {
                    emit(GenerationEvent.Completed)
                }
            }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            val active = currentCoroutineContext().isActive
            if (!active) throw t
            Log.w(TAG, "Online request failed: ${t.message}", t)
            val reason = when (t) {
                is OnlineLlmApiException -> t.message ?: "在线请求失败"
                is IOException -> "在线请求超时或网络不可用（${t.message ?: t.javaClass.simpleName}）"
                else -> t.message ?: "在线请求失败"
            }
            val transient = t is OnlineLlmApiException && t.transient || t is IOException
            when {
                !emittedToken && transient -> emit(GenerationEvent.FallbackToLocal(reason))
                !emittedToken -> emit(GenerationEvent.FallbackToLocal(reason))
                else -> emit(GenerationEvent.Error("$reason，在线输出已中止"))
            }
        } finally {
            activeCalls.remove(request.requestId)
        }
    }.flowOn(Dispatchers.IO)

    override fun cancel(requestId: String) {
        activeCalls.remove(requestId)?.cancel()
    }

    override fun unload() {
        activeCalls.values.forEach { runCatching { it.cancel() } }
        activeCalls.clear()
    }

    private fun resolvedConfig(
        requestModelId: String,
        mode: VoiceMode? = null,
    ): ResolvedOnlineLlmConfig {
        val settings = settingsStore.getSettings()
        val config = if (mode != null) settings.onlineConfigFor(mode) else settings.onlineUnifiedConfig
        return if (config.modelName.isBlank()) {
            config.copy(modelName = requestModelId.trim())
        } else {
            config
        }
    }

    private fun buildRequestBody(
        request: VoiceGenerationRequest,
        config: ResolvedOnlineLlmConfig,
        transcript: String,
    ): String {
        val messages = JSONArray()
        val system = request.systemPrompt
            ?: PromptTemplates.resolveSystemPrompt(
                override = null,
                cascade = true,
                mode = request.mode,
            )
        if (system.isNotBlank()) {
            messages.put(
                JSONObject()
                    .put("role", "system")
                    .put("content", system),
            )
        }

        val userText = buildString {
            val prompt = request.userPrompt
                ?: PromptTemplates.resolveUserPrompt(
                    mode = request.mode,
                    override = null,
                    cascade = true,
                )
            if (prompt.isNotBlank()) append(prompt)
            if (!request.userPromptIsTranscript && transcript.isNotBlank()) {
                if (isNotEmpty() && !endsWith("\n")) append('\n')
                append("\n[语音识别文本]:\n")
                append(transcript)
            }
        }
        val image = request.screenshotFile?.takeIf { it.isFile }?.let(::encodeScreenshotDataUrl)
        val userMessage = JSONObject().put("role", "user")
        if (image == null) {
            userMessage.put("content", userText)
        } else {
            val parts = JSONArray()
            if (userText.isNotBlank()) {
                parts.put(
                    JSONObject()
                        .put("type", "text")
                        .put("text", "$userText\n\n[屏幕内容]: 见附图"),
                )
            }
            parts.put(
                JSONObject()
                    .put("type", "image_url")
                    .put("image_url", JSONObject().put("url", image)),
            )
            userMessage.put("content", parts)
        }
        messages.put(userMessage)

        val body = JSONObject()
            .put("model", config.modelName)
            .put("messages", messages)
            .put("stream", true)
        OnlineRequestOptions.apply(
            body = body,
            baseUrl = config.baseUrl,
            modelName = config.modelName,
            maxTokens = config.maxTokens,
            temperature = config.temperature,
            providerDialect = config.providerDialect,
            thinkingEnabled = config.thinkingEnabled,
            thinkingEffort = config.thinkingEffort,
        )
        return body.toString()
    }

    private fun encodeScreenshotDataUrl(file: java.io.File): String? = runCatching {
        val original = BitmapFactory.decodeFile(file.absolutePath) ?: return null
        val scaled = ImageEncoder.downscaleBitmap(original, maxEdge = 1024)
        try {
            val output = ByteArrayOutputStream()
            val format = android.graphics.Bitmap.CompressFormat.JPEG
            scaled.compress(format, 82, output)
            val base64 = Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
            "data:image/jpeg;base64,$base64"
        } finally {
            if (scaled !== original) scaled.recycle()
            original.recycle()
        }
    }.getOrNull()

    private fun parseSseDelta(payload: String): String {
        val json = JSONObject(payload)
        val choices = json.optJSONArray("choices") ?: return ""
        if (choices.length() == 0) return ""
        val choice = choices.optJSONObject(0) ?: return ""
        val containers = listOfNotNull(choice.optJSONObject("delta"), choice.optJSONObject("message"))
        for (container in containers) {
            val text = container.opt("content").textValue()
            if (text.isNotEmpty()) return text
        }
        return ""
    }

    private fun Any?.textValue(): String = when (this) {
        null -> ""
        is String -> this
        is JSONArray -> buildString {
            for (i in 0 until length()) {
                val item = opt(i)
                if (item is JSONObject) append(item.optString("text"))
            }
        }
        else -> ""
    }
}