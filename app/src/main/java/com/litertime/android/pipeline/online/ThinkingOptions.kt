package com.litertime.android.pipeline.online

import java.util.Locale
import org.json.JSONObject

/**
 * Thinking / reasoning effort exposed by the online model configuration screen.
 *
 * The wire value is the OpenAI-style level (`low` / `medium` / `high`). Providers that use a
 * different vocabulary (DeepSeek & Moonshot only accept `low` / `high` / `max`, DashScope takes a
 * token budget) are translated in [OnlineRequestOptions].
 */
enum class ThinkingEffort(
    val storageId: String,
    val displayName: String,
    val wireValue: String,
    val thinkingBudget: Int,
) {
    LOW("low", "低", "low", 4096),
    MEDIUM("medium", "中", "medium", 16384),
    HIGH("high", "高", "high", 32768);

    companion object {
        /** Returns null for blank/unknown ids so per-mode overrides can mean "inherit". */
        fun fromStorageId(raw: String?): ThinkingEffort? {
            val id = raw?.trim()?.lowercase(Locale.US).orEmpty()
            return entries.firstOrNull { it.storageId == id }
        }

        fun fromStorageIdOrDefault(raw: String?): ThinkingEffort =
            fromStorageId(raw) ?: MEDIUM
    }
}

/**
 * Pure, dependency-free heuristics that decide whether the model selected in the settings screen
 * exposes a thinking switch / effort level. Model ids may carry an OpenRouter-style vendor prefix,
 * so matching is done on the last path segment.
 */
object OnlineThinkingSupport {
    private val openAiReasoningId = Regex("""^o[1-5]([-._].*)?$""")

    private val thinkingIds = listOf(
        openAiReasoningId,
        Regex("""^gpt-5"""),
        Regex("""^deepseek-(r1|reasoner|flash|v\d)"""),
        Regex("""^(qwen[34]|qwen-plus|qwen-flash|qwq|qvq)"""),
        Regex("""^glm-(z1|4\.[5-9]|5)"""),
        Regex("""^kimi-(k2\.5|k2\.6|k2-thinking|k3)"""),
        Regex("""^gemini-(2\.5|3)"""),
        Regex("""^claude-(3-7|.*4)"""),
        Regex("""^grok-(3-mini|4)"""),
        Regex("""^magistral"""),
        // Generic fallbacks cover self-hosted / proxied ids such as `vendor/foo-reasoner`.
        Regex("""(thinking|reasoner|reasoning)"""),
    )

    fun supportsThinking(modelName: String): Boolean {
        val id = normalizedModelId(modelName)
        if (id.isEmpty()) return false
        return thinkingIds.any { it.containsMatchIn(id) }
    }

    /**
     * OpenAI's reasoning models replaced `max_tokens` with `max_completion_tokens` and reject
     * `temperature`. Other OpenAI-compatible providers still use the legacy field.
     */
    fun usesMaxCompletionTokens(modelName: String): Boolean {
        val id = normalizedModelId(modelName)
        return openAiReasoningId.matches(id) || id.startsWith("gpt-5")
    }

    fun acceptsTemperature(modelName: String): Boolean = !usesMaxCompletionTokens(modelName)

    internal fun normalizedModelId(modelName: String): String =
        modelName.trim().lowercase(Locale.US).substringAfterLast('/')
}

/**
 * Provider adaptation selected in the model settings screen.
 *
 * [AUTO] detects the wire format from the endpoint host and model id; every other value forces a
 * specific dialect when the automatic heuristics cannot see through a proxy or a custom model id.
 */
enum class OnlineProviderDialect(val storageId: String, val displayName: String) {
    AUTO("auto", "自动"),
    OPENAI("openai", "OpenAI"),
    OPENROUTER("openrouter", "OpenRouter"),
    DASHSCOPE("dashscope", "DashScope"),
    DEEPSEEK("deepseek", "DeepSeek"),
    MOONSHOT("moonshot", "Moonshot"),
    ZHIPU("zhipu", "智谱");

    companion object {
        fun fromStorageId(raw: String?): OnlineProviderDialect {
            val id = raw?.trim()?.lowercase(Locale.US).orEmpty()
            return entries.firstOrNull { it.storageId == id } ?: AUTO
        }
    }
}

/**
 * Writes the configured sampling and thinking parameters into an OpenAI-compatible request body.
 *
 * Defaults are chosen to be harmless: when thinking is disabled the thinking field is omitted for
 * providers that have no documented "off" value, so an existing working configuration keeps
 * behaving exactly as before.
 */
object OnlineRequestOptions {
    fun apply(
        body: JSONObject,
        baseUrl: String,
        modelName: String,
        maxTokens: Int,
        temperature: Double,
        providerDialect: OnlineProviderDialect,
        thinkingEnabled: Boolean,
        thinkingEffort: ThinkingEffort,
    ) {
        val tokenKey = if (OnlineThinkingSupport.usesMaxCompletionTokens(modelName)) {
            "max_completion_tokens"
        } else {
            "max_tokens"
        }
        body.put(tokenKey, maxTokens.coerceAtLeast(1))
        if (OnlineThinkingSupport.acceptsTemperature(modelName)) {
            body.put("temperature", temperature)
        }
        if (OnlineThinkingSupport.supportsThinking(modelName)) {
            applyThinking(
                body = body,
                baseUrl = baseUrl,
                modelName = modelName,
                providerDialect = providerDialect,
                enabled = thinkingEnabled,
                effort = thinkingEffort,
            )
        }
    }

    private fun applyThinking(
        body: JSONObject,
        baseUrl: String,
        modelName: String,
        providerDialect: OnlineProviderDialect,
        enabled: Boolean,
        effort: ThinkingEffort,
    ) {
        val modelId = OnlineThinkingSupport.normalizedModelId(modelName)
        when (resolveDialect(providerDialect, baseUrl, modelName)) {
            OnlineProviderDialect.OPENROUTER -> {
                val reasoning = if (enabled) {
                    JSONObject().put("effort", effort.wireValue)
                } else {
                    JSONObject().put("enabled", false)
                }
                body.put("reasoning", reasoning)
            }

            OnlineProviderDialect.DASHSCOPE -> {
                body.put("enable_thinking", enabled)
                if (enabled) {
                    body.put("thinking_budget", effort.thinkingBudget)
                }
            }

            OnlineProviderDialect.DEEPSEEK -> {
                body.put("thinking", JSONObject().put("type", if (enabled) "enabled" else "disabled"))
                if (enabled) {
                    body.put("reasoning_effort", deepSeekEffort(effort))
                }
            }

            OnlineProviderDialect.MOONSHOT -> {
                // K2.5/K2.6 take a binary `thinking` toggle; K3 rejects that field and always
                // reasons, so only its effort level can be adjusted.
                if (modelId.startsWith("kimi-k3")) {
                    if (enabled) {
                        body.put("reasoning_effort", deepSeekEffort(effort))
                    }
                } else {
                    body.put(
                        "thinking",
                        JSONObject().put("type", if (enabled) "enabled" else "disabled"),
                    )
                    if (enabled) {
                        body.put("reasoning_effort", deepSeekEffort(effort))
                    }
                }
            }

            OnlineProviderDialect.ZHIPU -> {
                body.put("thinking", JSONObject().put("type", if (enabled) "enabled" else "disabled"))
            }

            // OpenAI / OpenAI-compatible reasoning models have no reliable "off" switch, so only
            // send effort when the user explicitly enabled thinking.
            OnlineProviderDialect.OPENAI,
            OnlineProviderDialect.AUTO -> {
                if (enabled) {
                    body.put("reasoning_effort", effort.wireValue)
                }
            }
        }
    }

    /** Resolves [configured], falling back to the automatic host/model heuristic. */
    internal fun resolveDialect(
        configured: OnlineProviderDialect,
        baseUrl: String,
        modelName: String,
    ): OnlineProviderDialect = if (configured == OnlineProviderDialect.AUTO) {
        detectDialect(baseUrl, modelName)
    } else {
        configured
    }

    /** Automatic detection used by "自动" mode and by the settings dropdown label. */
    internal fun detectDialect(baseUrl: String, modelName: String): OnlineProviderDialect {
        val host = hostOf(baseUrl)
        val modelId = OnlineThinkingSupport.normalizedModelId(modelName)
        return when {
            host.contains("openrouter") -> OnlineProviderDialect.OPENROUTER
            host.contains("dashscope") ||
                host.contains("aliyuncs") ||
                host.contains("qwencloud") ||
                modelId.startsWith("qwen") ||
                modelId.startsWith("qwq") ||
                modelId.startsWith("qvq") -> OnlineProviderDialect.DASHSCOPE

            host.contains("deepseek") || modelId.startsWith("deepseek-") ->
                OnlineProviderDialect.DEEPSEEK

            host.contains("moonshot") || host.contains("kimi") || modelId.startsWith("kimi-") ->
                OnlineProviderDialect.MOONSHOT

            host.contains("bigmodel") || host.contains("zhipu") || modelId.startsWith("glm-") ->
                OnlineProviderDialect.ZHIPU

            else -> OnlineProviderDialect.OPENAI
        }
    }

    /** DeepSeek & Moonshot only understand `low` / `high` / `max`. */
    private fun deepSeekEffort(effort: ThinkingEffort): String = when (effort) {
        ThinkingEffort.LOW -> "low"
        ThinkingEffort.MEDIUM -> "high"
        ThinkingEffort.HIGH -> "max"
    }

    private fun hostOf(baseUrl: String): String =
        baseUrl.substringAfter("://", baseUrl)
            .substringBefore('/')
            .lowercase(Locale.US)
}
