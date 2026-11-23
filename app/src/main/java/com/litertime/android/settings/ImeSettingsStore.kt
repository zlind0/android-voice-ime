package com.litertime.android.settings

import android.content.Context
import com.litertime.android.pipeline.VoiceMode
import com.litertime.android.storage.DownloadMirror
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Properties

enum class LlmProviderType(val displayName: String) {
    LOCAL_LITERT("本地 LiteRT (Gemma 4)"),
    ONLINE_OPENAI_COMPAT("在线 API (OpenAI 兼容 · 预留)"),
    ONLINE_GEMINI("在线 API (Gemini · 预留)");

    companion object {
        fun fromId(value: String?): LlmProviderType =
            entries.firstOrNull { it.name == value } ?: LOCAL_LITERT
    }
}

data class ImeSettings(
    val activeAsrModelId: String = "whisper-small",
    val activeLlmModelId: String = "gemma4-e2b",
    val useGpuForLlm: Boolean = true,
    val whisperLanguage: String = "",
    val downloadMirror: DownloadMirror = DownloadMirror.HF_MIRROR,
    val llmProvider: LlmProviderType = LlmProviderType.LOCAL_LITERT,
    val onlineBaseUrl: String = "https://api.openai.com/v1",
    val onlineApiKey: String = "",
    val onlineModelName: String = "gpt-4o-mini",
    /** When enabled, every AI request is persisted under `files/ai-request-logs/`. */
    val debugRequestLoggingEnabled: Boolean = false,
    /** Unified system prompt. Blank keeps the built-in default from `PromptTemplates`. */
    val systemPromptOverride: String = "",
    /** Per-mode user prompt overrides. Blank keeps the built-in default for that mode. */
    val userPromptWhisperRaw: String = "",
    val userPromptPolishNormal: String = "",
    val userPromptPolishFormal: String = "",
    val userPromptScreenshotInstruction: String = "",
) {
    fun userPromptOverride(mode: VoiceMode): String = when (mode) {
        VoiceMode.WHISPER_RAW -> userPromptWhisperRaw
        VoiceMode.GEMMA_POLISH_NORMAL -> userPromptPolishNormal
        VoiceMode.GEMMA_POLISH_FORMAL -> userPromptPolishFormal
        VoiceMode.GEMMA_SCREENSHOT_INSTRUCTION -> userPromptScreenshotInstruction
    }

    fun withUserPromptOverride(mode: VoiceMode, prompt: String): ImeSettings = when (mode) {
        VoiceMode.WHISPER_RAW -> copy(userPromptWhisperRaw = prompt)
        VoiceMode.GEMMA_POLISH_NORMAL -> copy(userPromptPolishNormal = prompt)
        VoiceMode.GEMMA_POLISH_FORMAL -> copy(userPromptPolishFormal = prompt)
        VoiceMode.GEMMA_SCREENSHOT_INSTRUCTION -> copy(userPromptScreenshotInstruction = prompt)
    }
}

/**
 * Cross-process settings store backed by `<app>/files/ime-settings.properties` with mtime caching,
 * allowing both the IME process and `:inference` process to read fresh preferences.
 */
class ImeSettingsStore(private val appFilesDir: File) {
    constructor(context: Context) : this(context.applicationContext.filesDir)

    private val settingsFile = File(appFilesDir, "ime-settings.properties")

    @Volatile
    private var cachedMtime: Long = -1L

    @Volatile
    private var cachedSettings: ImeSettings = ImeSettings()

    @Synchronized
    fun getSettings(): ImeSettings {
        if (!settingsFile.isFile) {
            cachedMtime = -1L
            cachedSettings = ImeSettings()
            return cachedSettings
        }
        val mtime = settingsFile.lastModified()
        if (mtime == cachedMtime) {
            return cachedSettings
        }
        val props = Properties()
        runCatching {
            FileInputStream(settingsFile).use { props.load(it) }
        }
        val loaded = ImeSettings(
            activeAsrModelId = props.getProperty("activeAsrModelId", "whisper-small"),
            activeLlmModelId = props.getProperty("activeLlmModelId", "gemma4-e2b"),
            useGpuForLlm = props.getProperty("useGpuForLlm", "true").toBoolean(),
            whisperLanguage = props.getProperty("whisperLanguage", ""),
            downloadMirror = DownloadMirror.fromId(props.getProperty("downloadMirror")),
            llmProvider = LlmProviderType.fromId(props.getProperty("llmProvider")),
            onlineBaseUrl = props.getProperty("onlineBaseUrl", "https://api.openai.com/v1"),
            onlineApiKey = props.getProperty("onlineApiKey", ""),
            onlineModelName = props.getProperty("onlineModelName", "gpt-4o-mini"),
            debugRequestLoggingEnabled = props.getProperty("debugRequestLoggingEnabled", "false").toBoolean(),
            systemPromptOverride = props.getProperty("promptSystem", ""),
            userPromptWhisperRaw = props.getProperty("promptUserWhisperRaw", ""),
            userPromptPolishNormal = props.getProperty("promptUserPolishNormal", ""),
            userPromptPolishFormal = props.getProperty("promptUserPolishFormal", ""),
            userPromptScreenshotInstruction = props.getProperty("promptUserScreenshotInstruction", ""),
        )
        cachedMtime = mtime
        cachedSettings = loaded
        return loaded
    }

    @Synchronized
    fun update(transform: (ImeSettings) -> ImeSettings): ImeSettings {
        val updated = transform(getSettings())
        saveSettings(updated)
        return updated
    }

    @Synchronized
    fun saveSettings(settings: ImeSettings) {
        appFilesDir.mkdirs()
        val props = Properties().apply {
            setProperty("activeAsrModelId", settings.activeAsrModelId)
            setProperty("activeLlmModelId", settings.activeLlmModelId)
            setProperty("useGpuForLlm", settings.useGpuForLlm.toString())
            setProperty("whisperLanguage", settings.whisperLanguage)
            setProperty("downloadMirror", settings.downloadMirror.name)
            setProperty("llmProvider", settings.llmProvider.name)
            setProperty("onlineBaseUrl", settings.onlineBaseUrl)
            setProperty("onlineApiKey", settings.onlineApiKey)
            setProperty("onlineModelName", settings.onlineModelName)
            setProperty("debugRequestLoggingEnabled", settings.debugRequestLoggingEnabled.toString())
            setProperty("promptSystem", settings.systemPromptOverride)
            setProperty("promptUserWhisperRaw", settings.userPromptWhisperRaw)
            setProperty("promptUserPolishNormal", settings.userPromptPolishNormal)
            setProperty("promptUserPolishFormal", settings.userPromptPolishFormal)
            setProperty("promptUserScreenshotInstruction", settings.userPromptScreenshotInstruction)
        }
        val tmp = File(appFilesDir, "ime-settings.properties.tmp")
        FileOutputStream(tmp).use { props.store(it, "LiteRT IME settings") }
        if (!tmp.renameTo(settingsFile)) {
            tmp.copyTo(settingsFile, overwrite = true)
            tmp.delete()
        }
        cachedMtime = settingsFile.lastModified()
        cachedSettings = settings
    }
}
