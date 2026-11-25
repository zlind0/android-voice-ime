package com.litertime.android.pipeline.online

import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject

/** A vendor group shown by the model picker. OpenRouter-style ids are grouped by the prefix. */
data class OnlineModelGroup(
    val vendor: String,
    val models: List<String>,
)

/** Grouping helpers kept free of Android APIs so they can be unit tested. */
object OnlineModelGroups {
    const val UNGROUPED_VENDOR = "其他"

    fun group(modelIds: List<String>): List<OnlineModelGroup> {
        val grouped = LinkedHashMap<String, MutableList<String>>()
        modelIds.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .sortedBy { it.lowercase(Locale.US) }
            .forEach { id ->
                val slash = id.indexOf('/')
                val vendor = if (slash > 0) id.substring(0, slash) else UNGROUPED_VENDOR
                grouped.getOrPut(vendor) { mutableListOf() }.add(id)
            }
        return grouped.entries
            .map { OnlineModelGroup(it.key, it.value.toList()) }
            .sortedWith(
                compareBy<OnlineModelGroup> { it.vendor != UNGROUPED_VENDOR }
                    .thenBy { it.vendor.lowercase(Locale.US) },
            )
    }
}

/** Account balance rendered by the settings screen. */
data class OnlineBalance(
    val primary: String,
    val detail: String = "",
)

class OnlineLlmApiException(
    message: String,
    val httpCode: Int? = null,
    val transient: Boolean = false,
    cause: Throwable? = null,
) : IOException(message, cause)

/**
 * Small OpenAI-compatible HTTP client shared by the settings UI and the inference backend.
 *
 * It intentionally only supports the OpenAI wire format: `GET /models`,
 * `POST /chat/completions`, plus best-effort provider-specific balance endpoints.
 */
object OnlineLlmApi {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .build()

    private val streamClient = client.newBuilder()
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun normalizeBaseUrl(raw: String): String {
        var value = raw.trim().trimEnd('/')
        if (value.endsWith("/chat/completions")) {
            value = value.removeSuffix("/chat/completions").trimEnd('/')
        }
        if (value.endsWith("/models")) {
            value = value.removeSuffix("/models").trimEnd('/')
        }
        return value
    }

    fun modelsUrl(baseUrl: String): String = "${normalizeBaseUrl(baseUrl)}/models"

    fun chatCompletionsUrl(baseUrl: String): String =
        "${normalizeBaseUrl(baseUrl)}/chat/completions"

    suspend fun fetchModels(baseUrl: String, apiKey: String): List<String> = withContext(Dispatchers.IO) {
        val json = executeJson(
            Request.Builder()
                .url(modelsUrl(baseUrl))
                .get()
                .applyAuth(apiKey)
                .build(),
        )
        parseModelIds(json)
    }

    suspend fun testHello(baseUrl: String, apiKey: String, modelName: String): String =
        withContext(Dispatchers.IO) {
            val messages = JSONArray().put(
                JSONObject()
                    .put("role", "user")
                    .put("content", "Hello"),
            )
            val body = JSONObject()
                .put("model", modelName.trim())
                .put("messages", messages)
                .put("temperature", 0.0)
                .put("max_tokens", 32)
                .toString()
            val json = executeJson(
                Request.Builder()
                    .url(chatCompletionsUrl(baseUrl))
                    .post(body.toRequestBody(jsonMediaType))
                    .applyAuth(apiKey)
                    .build(),
            )
            parseAssistantText(json).ifBlank { "连接成功（模型返回空内容）" }
        }

    suspend fun fetchBalance(baseUrl: String, apiKey: String): OnlineBalance =
        withContext(Dispatchers.IO) {
            val base = normalizeBaseUrl(baseUrl)
            val candidates = balanceCandidates(base)
            var lastError: OnlineLlmApiException? = null
            for (url in candidates) {
                try {
                    val json = executeJson(
                        Request.Builder()
                            .url(url)
                            .get()
                            .applyAuth(apiKey)
                            .build(),
                    )
                    return@withContext parseBalance(json)
                } catch (e: OnlineLlmApiException) {
                    lastError = e
                    if (e.httpCode == null || e.httpCode in 401..403) throw e
                    // A missing balance route is expected for many compatible providers.
                    if (e.httpCode !in 400..405) throw e
                }
            }
            val last = lastError
            if (last != null && (last.httpCode == null || last.httpCode in 400..405)) {
                throw OnlineLlmApiException("该服务未提供可识别的余额接口", last.httpCode)
            }
            throw last ?: OnlineLlmApiException("该服务未提供可识别的余额接口")
        }

    private fun balanceCandidates(baseUrl: String): List<String> {
        val lower = baseUrl.lowercase(Locale.US)
        return when {
            lower.contains("openrouter.ai") -> listOf("$baseUrl/credits")
            lower.contains("deepseek.com") -> listOf(rootOf(baseUrl) + "/user/balance")
            else -> listOf(
                "$baseUrl/credits",
                "$baseUrl/user/balance",
                "$baseUrl/dashboard/billing/credit_grants",
            )
        }.distinct()
    }

    private fun rootOf(url: String): String {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd < 0) return url.substringBefore('/', missingDelimiterValue = url)
        val hostEnd = url.indexOf('/', startIndex = schemeEnd + 3)
        return if (hostEnd < 0) url else url.substring(0, hostEnd)
    }

    internal fun newChatCall(baseUrl: String, apiKey: String, body: String): Call =
        streamClient.newCall(
            Request.Builder()
                .url(chatCompletionsUrl(baseUrl))
                .post(body.toRequestBody(jsonMediaType))
                .header("Accept", "text/event-stream")
                .applyAuth(apiKey)
                .build(),
        )

    internal fun openChatStream(call: Call): Response = call.execute()

    internal fun parseModelIds(json: String): List<String> {
        val root = runCatching { JSONObject(json) }.getOrNull()
        if (root != null) {
            val data = root.optJSONArray("data") ?: root.optJSONArray("models")
            if (data != null) return idsFromArray(data)
        }
        val array = runCatching { JSONArray(json) }.getOrNull()
        if (array != null) return idsFromArray(array)
        throw OnlineLlmApiException("模型列表格式无法识别")
    }

    private fun idsFromArray(array: JSONArray): List<String> {
        val result = mutableListOf<String>()
        for (i in 0 until array.length()) {
            val item = array.opt(i)
            val id = when (item) {
                is String -> item
                is JSONObject -> item.optString("id").ifBlank { item.optString("name") }
                else -> ""
            }
            if (id.isNotBlank()) result += id
        }
        return result.distinct().sortedBy { it.lowercase(Locale.US) }
    }

    internal fun parseAssistantText(json: String): String {
        val root = JSONObject(json)
        val choices = root.optJSONArray("choices") ?: return ""
        if (choices.length() == 0) return ""
        val choice = choices.optJSONObject(0) ?: return ""
        val message = choice.optJSONObject("message")
        return message?.opt("content")?.asText().orEmpty()
            .ifBlank { choice.opt("text")?.asText().orEmpty() }
    }

    private fun parseBalance(json: String): OnlineBalance {
        val root = runCatching { JSONObject(json) }.getOrNull()
            ?: throw OnlineLlmApiException("余额返回格式无法识别")
        val data = root.optJSONObject("data") ?: root

        if (data.has("total_credits")) {
            val total = data.optDouble("total_credits")
            val used = data.optDouble("total_usage", 0.0)
            val remaining = (total - used).coerceAtLeast(0.0)
            return OnlineBalance(
                primary = "剩余 ${money(remaining)}",
                detail = "总额 ${money(total)} · 已用 ${money(used)}",
            )
        }

        val balanceInfos = root.optJSONArray("balance_infos")
            ?: data.optJSONArray("balance_infos")
        if (balanceInfos != null && balanceInfos.length() > 0) {
            val parts = mutableListOf<String>()
            val details = mutableListOf<String>()
            for (i in 0 until balanceInfos.length()) {
                val info = balanceInfos.optJSONObject(i) ?: continue
                val currency = info.optString("currency").trim()
                val total = info.optString("total_balance").trim()
                if (total.isNotBlank()) parts += listOf(currency, total).filter { it.isNotBlank() }
                    .joinToString(" ")
                val granted = info.optString("granted_balance").trim()
                val toppedUp = info.optString("topped_up_balance").trim()
                val detail = listOf(
                    "充值".takeIf { toppedUp.isNotBlank() }?.let { "$it $toppedUp" },
                    "赠送".takeIf { granted.isNotBlank() }?.let { "$it $granted" },
                ).filterNotNull().joinToString(" · ")
                if (detail.isNotBlank()) details += detail
            }
            if (parts.isNotEmpty()) {
                return OnlineBalance(parts.joinToString(" / "), details.joinToString(" · "))
            }
        }

        if (root.has("total_available") || data.has("total_available")) {
            val available = data.optDouble("total_available")
            val used = data.optDouble("total_used", 0.0)
            return OnlineBalance("剩余 ${money(available)}", "已用 ${money(used)}")
        }

        val numericCandidates = listOf("balance", "remaining", "total_balance", "available_balance")
        for (key in numericCandidates) {
            val source = if (data.has(key)) data else root
            if (!source.has(key)) continue
            val value = source.opt(key)
            val text = value?.asText()?.trim().orEmpty()
            if (text.isNotBlank()) {
                return OnlineBalance(
                    primary = if (value is Number) "余额 ${money(value.toDouble())}" else "余额 $text",
                )
            }
        }

        throw OnlineLlmApiException("该服务未提供可识别的余额接口")
    }

    private fun executeJson(request: Request): String {
        val response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            throw OnlineLlmApiException(
                "网络错误：${e.message ?: e.javaClass.simpleName}",
                transient = true,
                cause = e,
            )
        }
        response.use {
            if (!it.isSuccessful) throw apiError(it)
            return it.body?.string().orEmpty()
        }
    }

    internal fun apiError(response: Response): OnlineLlmApiException {
        val raw = runCatching { response.body?.string().orEmpty() }.getOrDefault("")
        val message = runCatching {
            JSONObject(raw).optJSONObject("error")?.optString("message").orEmpty()
        }.getOrNull().takeUnless { it.isNullOrBlank() }
            ?: raw.trim().take(300).ifBlank { response.message }
        val code = response.code
        return OnlineLlmApiException(
            message = "HTTP $code：$message",
            httpCode = code,
            transient = code == 408 || code == 429 || code >= 500,
        )
    }

    private fun Request.Builder.applyAuth(apiKey: String): Request.Builder {
        val key = apiKey.trim()
        if (key.isNotEmpty()) {
            header("Authorization", "Bearer $key")
        }
        header("User-Agent", "LiteRT-IME/OpenAI-Compatible")
        return this
    }

    private fun money(value: Double): String =
        String.format(Locale.US, "%.2f", value)

    private fun Any?.asText(): String = when (this) {
        null -> ""
        is String -> this
        is JSONArray -> {
            val parts = mutableListOf<String>()
            for (i in 0 until length()) {
                val item = opt(i)
                if (item is JSONObject) {
                    val text = item.optString("text")
                    if (text.isNotBlank()) parts += text
                }
            }
            parts.joinToString("")
        }
        else -> toString()
    }
}