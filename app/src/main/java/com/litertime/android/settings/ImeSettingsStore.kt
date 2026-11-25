package com.litertime.android.settings

import android.content.Context
import com.litertime.android.pipeline.VoiceMode
import com.litertime.android.storage.DownloadMirror
import com.litertime.android.ui.PastelPalette
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

enum class HandwritingInkColor(val displayName: String, val argb: Int) {
    WHITE("纯白", 0xFFFFFFFF.toInt()),
    SKY_BLUE("天蓝", 0xFFADDDEF.toInt()),
    EMERALD("翠绿", 0xFFB2EAD3.toInt()),
    AMBER("琥珀", 0xFFF5E29E.toInt());

    companion object {
        fun fromId(value: String?): HandwritingInkColor =
            entries.firstOrNull { it.name == value } ?: WHITE
    }
}

data class ImeSettings(
    val activeAsrModelId: String = "whisper-small",
    val activeLlmModelId: String = "gemma4-e2b",
    val useGpuForLlm: Boolean = true,
    /**
     * When enabled, AI-mic voice input is first transcribed by Whisper/ASR and only the text is
     * handed to the large model (cascade), instead of feeding audio straight into Gemma 4.
     */
    val voiceCascadeEnabled: Boolean = false,
    val whisperLanguage: String = "",
    val downloadMirror: DownloadMirror = DownloadMirror.HF_MIRROR,
    val llmProvider: LlmProviderType = LlmProviderType.LOCAL_LITERT,
    val onlineBaseUrl: String = "https://api.openai.com/v1",
    val onlineApiKey: String = "",
    val onlineModelName: String = "gpt-4o-mini",
    /** When enabled, every AI request is persisted under `files/ai-request-logs/`. */
    val debugRequestLoggingEnabled: Boolean = false,
    /** When enabled, a sentence-final period in the generated text is dropped before committing. */
    val stripTrailingPeriod: Boolean = false,
    /** Unified system prompt. Blank keeps the built-in default from `PromptTemplates`. */
    val systemPromptOverride: String = "",
    /** Dedicated system prompt for the screenshot instruction mode. Blank keeps the default. */
    val systemPromptScreenshotInstruction: String = "",
    /** Per-mode user prompt overrides. Blank keeps the built-in default for that mode. */
    val userPromptWhisperRaw: String = "",
    val userPromptPolishFormal: String = "",
    val userPromptScreenshotCorrection: String = "",
    val userPromptScreenshotInstruction: String = "",
    /** Chinese handwriting delay (ms) after last stroke before auto-commit + fade-out. */
    val handwritingFadeDelayMs: Int = DEFAULT_HANDWRITING_FADE_DELAY_MS,
    val handwritingAutoCommitFirst: Boolean = true,
    val handwritingStrokeWidthDp: Float = DEFAULT_HANDWRITING_STROKE_WIDTH_DP,
    val handwritingInkColor: HandwritingInkColor = HandwritingInkColor.WHITE,
    val handwritingShowGrid: Boolean = false,
    /** When enabled, Chinese handwriting shows two side-by-side boxes for alternating writing. */
    val handwritingDualBoxEnabled: Boolean = true,
    val handwritingPanelHeightDp: Int = DEFAULT_HANDWRITING_PANEL_HEIGHT_DP,
    /** English QWERTY keyboard settings. */
    val keyboardHeightDp: Int = DEFAULT_KEYBOARD_HEIGHT_DP,
    val keyboardShowNumberRow: Boolean = false,
    val keyboardHapticEnabled: Boolean = true,
    val keyboardKeyPopupEnabled: Boolean = true,
    val keyboardAutoCapsEnabled: Boolean = true,
    val keyboardDoubleSpacePeriod: Boolean = true,
    /** Custom keyboard theme: accent 1 (selected sky-blue family). */
    val accentColor1: Int = PastelPalette.DEFAULT_ACCENT_1,
    /** Custom keyboard theme: accent 2 (Enter/Done & loaded-AI pink family). */
    val accentColor2: Int = PastelPalette.DEFAULT_ACCENT_2,
    /** Custom keyboard theme: the base canvas the subtle key surfaces are derived from. */
    val backgroundColor: Int = PastelPalette.DEFAULT_BG_CANVAS,
    val recentEmojis: String = "",
) {
    val hasCustomKeyboardColors: Boolean
        get() = accentColor1 != PastelPalette.DEFAULT_ACCENT_1 ||
            accentColor2 != PastelPalette.DEFAULT_ACCENT_2 ||
            backgroundColor != PastelPalette.DEFAULT_BG_CANVAS
    fun recentEmojiList(): List<String> =
        recentEmojis.split(RECENT_EMOJI_SEPARATOR).filter { it.isNotEmpty() }

    fun withAddedRecentEmoji(emoji: String): ImeSettings {
        if (emoji.isBlank()) return this
        val updated = (listOf(emoji) + recentEmojiList().filter { it != emoji }).take(MAX_RECENT_EMOJIS)
        return copy(recentEmojis = updated.joinToString(RECENT_EMOJI_SEPARATOR))
    }

    companion object {
        const val MIN_HANDWRITING_FADE_DELAY_MS = 400
        const val MAX_HANDWRITING_FADE_DELAY_MS = 2500
        const val DEFAULT_HANDWRITING_FADE_DELAY_MS = 1000

        const val MIN_HANDWRITING_STROKE_WIDTH_DP = 2.5f
        const val MAX_HANDWRITING_STROKE_WIDTH_DP = 10.0f
        const val DEFAULT_HANDWRITING_STROKE_WIDTH_DP = 5.0f

        const val MIN_HANDWRITING_PANEL_HEIGHT_DP = 220
        const val MAX_HANDWRITING_PANEL_HEIGHT_DP = 340
        const val DEFAULT_HANDWRITING_PANEL_HEIGHT_DP = 270

        const val MIN_KEYBOARD_HEIGHT_DP = 200
        const val MAX_KEYBOARD_HEIGHT_DP = 320
        const val DEFAULT_KEYBOARD_HEIGHT_DP = 240

        const val MAX_RECENT_EMOJIS = 32
        private const val RECENT_EMOJI_SEPARATOR = "\u001F"
    }
    fun userPromptOverride(mode: VoiceMode): String = when (mode) {
        VoiceMode.WHISPER_RAW -> userPromptWhisperRaw
        VoiceMode.GEMMA_POLISH_FORMAL -> userPromptPolishFormal
        VoiceMode.GEMMA_SCREENSHOT_CORRECTION -> userPromptScreenshotCorrection
        VoiceMode.GEMMA_SCREENSHOT_INSTRUCTION -> userPromptScreenshotInstruction
    }

    fun withUserPromptOverride(mode: VoiceMode, prompt: String): ImeSettings = when (mode) {
        VoiceMode.WHISPER_RAW -> copy(userPromptWhisperRaw = prompt)
        VoiceMode.GEMMA_POLISH_FORMAL -> copy(userPromptPolishFormal = prompt)
        VoiceMode.GEMMA_SCREENSHOT_CORRECTION -> copy(userPromptScreenshotCorrection = prompt)
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
            voiceCascadeEnabled = props.getProperty("voiceCascadeEnabled", "false").toBoolean(),
            whisperLanguage = props.getProperty("whisperLanguage", ""),
            downloadMirror = DownloadMirror.fromId(props.getProperty("downloadMirror")),
            llmProvider = LlmProviderType.fromId(props.getProperty("llmProvider")),
            onlineBaseUrl = props.getProperty("onlineBaseUrl", "https://api.openai.com/v1"),
            onlineApiKey = props.getProperty("onlineApiKey", ""),
            onlineModelName = props.getProperty("onlineModelName", "gpt-4o-mini"),
            debugRequestLoggingEnabled = props.getProperty("debugRequestLoggingEnabled", "false").toBoolean(),
            stripTrailingPeriod = props.getProperty("stripTrailingPeriod", "false").toBoolean(),
            systemPromptOverride = props.getProperty("promptSystem", ""),
            systemPromptScreenshotInstruction = props.getProperty("promptSystemScreenshotInstruction", ""),
            userPromptWhisperRaw = props.getProperty("promptUserWhisperRaw", ""),
            userPromptPolishFormal = props.getProperty("promptUserPolishFormal", ""),
            userPromptScreenshotCorrection = props.getProperty("promptUserScreenshotCorrection", ""),
            userPromptScreenshotInstruction = props.getProperty("promptUserScreenshotInstruction", ""),
            handwritingFadeDelayMs = (props.getProperty("handwritingFadeDelayMs")?.toIntOrNull()
                ?: ImeSettings.DEFAULT_HANDWRITING_FADE_DELAY_MS)
                .coerceIn(ImeSettings.MIN_HANDWRITING_FADE_DELAY_MS, ImeSettings.MAX_HANDWRITING_FADE_DELAY_MS),
            handwritingAutoCommitFirst = props.getProperty("handwritingAutoCommitFirst", "true").toBoolean(),
            handwritingStrokeWidthDp = (props.getProperty("handwritingStrokeWidthDp")?.toFloatOrNull()
                ?: ImeSettings.DEFAULT_HANDWRITING_STROKE_WIDTH_DP)
                .coerceIn(ImeSettings.MIN_HANDWRITING_STROKE_WIDTH_DP, ImeSettings.MAX_HANDWRITING_STROKE_WIDTH_DP),
            handwritingInkColor = HandwritingInkColor.fromId(props.getProperty("handwritingInkColor")),
            handwritingShowGrid = props.getProperty("handwritingShowGrid", "false").toBoolean(),
            handwritingDualBoxEnabled = props.getProperty("handwritingDualBoxEnabled", "true").toBoolean(),
            handwritingPanelHeightDp = (props.getProperty("handwritingPanelHeightDp")?.toIntOrNull()
                ?: ImeSettings.DEFAULT_HANDWRITING_PANEL_HEIGHT_DP)
                .coerceIn(ImeSettings.MIN_HANDWRITING_PANEL_HEIGHT_DP, ImeSettings.MAX_HANDWRITING_PANEL_HEIGHT_DP),
            keyboardHeightDp = (props.getProperty("keyboardHeightDp")?.toIntOrNull()
                ?: ImeSettings.DEFAULT_KEYBOARD_HEIGHT_DP)
                .coerceIn(ImeSettings.MIN_KEYBOARD_HEIGHT_DP, ImeSettings.MAX_KEYBOARD_HEIGHT_DP),
            keyboardShowNumberRow = props.getProperty("keyboardShowNumberRow", "false").toBoolean(),
            keyboardHapticEnabled = props.getProperty("keyboardHapticEnabled", "true").toBoolean(),
            keyboardKeyPopupEnabled = props.getProperty("keyboardKeyPopupEnabled", "true").toBoolean(),
            keyboardAutoCapsEnabled = props.getProperty("keyboardAutoCapsEnabled", "true").toBoolean(),
            keyboardDoubleSpacePeriod = props.getProperty("keyboardDoubleSpacePeriod", "true").toBoolean(),
            accentColor1 = parseColor(props.getProperty("accentColor1"), PastelPalette.DEFAULT_ACCENT_1),
            accentColor2 = parseColor(props.getProperty("accentColor2"), PastelPalette.DEFAULT_ACCENT_2),
            backgroundColor = parseColor(props.getProperty("backgroundColor"), PastelPalette.DEFAULT_BG_CANVAS),
            recentEmojis = props.getProperty("recentEmojis", ""),
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
            setProperty("voiceCascadeEnabled", settings.voiceCascadeEnabled.toString())
            setProperty("whisperLanguage", settings.whisperLanguage)
            setProperty("downloadMirror", settings.downloadMirror.name)
            setProperty("llmProvider", settings.llmProvider.name)
            setProperty("onlineBaseUrl", settings.onlineBaseUrl)
            setProperty("onlineApiKey", settings.onlineApiKey)
            setProperty("onlineModelName", settings.onlineModelName)
            setProperty("debugRequestLoggingEnabled", settings.debugRequestLoggingEnabled.toString())
            setProperty("stripTrailingPeriod", settings.stripTrailingPeriod.toString())
            setProperty("promptSystem", settings.systemPromptOverride)
            setProperty(
                "promptSystemScreenshotInstruction",
                settings.systemPromptScreenshotInstruction,
            )
            setProperty("promptUserWhisperRaw", settings.userPromptWhisperRaw)
            setProperty("promptUserPolishFormal", settings.userPromptPolishFormal)
            setProperty("promptUserScreenshotCorrection", settings.userPromptScreenshotCorrection)
            setProperty("promptUserScreenshotInstruction", settings.userPromptScreenshotInstruction)
            setProperty(
                "handwritingFadeDelayMs",
                settings.handwritingFadeDelayMs
                    .coerceIn(ImeSettings.MIN_HANDWRITING_FADE_DELAY_MS, ImeSettings.MAX_HANDWRITING_FADE_DELAY_MS)
                    .toString(),
            )
            setProperty("handwritingAutoCommitFirst", settings.handwritingAutoCommitFirst.toString())
            setProperty(
                "handwritingStrokeWidthDp",
                settings.handwritingStrokeWidthDp
                    .coerceIn(ImeSettings.MIN_HANDWRITING_STROKE_WIDTH_DP, ImeSettings.MAX_HANDWRITING_STROKE_WIDTH_DP)
                    .toString(),
            )
            setProperty("handwritingInkColor", settings.handwritingInkColor.name)
            setProperty("handwritingShowGrid", settings.handwritingShowGrid.toString())
            setProperty("handwritingDualBoxEnabled", settings.handwritingDualBoxEnabled.toString())
            setProperty(
                "handwritingPanelHeightDp",
                settings.handwritingPanelHeightDp
                    .coerceIn(ImeSettings.MIN_HANDWRITING_PANEL_HEIGHT_DP, ImeSettings.MAX_HANDWRITING_PANEL_HEIGHT_DP)
                    .toString(),
            )
            setProperty(
                "keyboardHeightDp",
                settings.keyboardHeightDp
                    .coerceIn(ImeSettings.MIN_KEYBOARD_HEIGHT_DP, ImeSettings.MAX_KEYBOARD_HEIGHT_DP)
                    .toString(),
            )
            setProperty("keyboardShowNumberRow", settings.keyboardShowNumberRow.toString())
            setProperty("keyboardHapticEnabled", settings.keyboardHapticEnabled.toString())
            setProperty("keyboardKeyPopupEnabled", settings.keyboardKeyPopupEnabled.toString())
            setProperty("keyboardAutoCapsEnabled", settings.keyboardAutoCapsEnabled.toString())
            setProperty("keyboardDoubleSpacePeriod", settings.keyboardDoubleSpacePeriod.toString())
            setProperty("accentColor1", formatColor(settings.accentColor1))
            setProperty("accentColor2", formatColor(settings.accentColor2))
            setProperty("backgroundColor", formatColor(settings.backgroundColor))
            setProperty("recentEmojis", settings.recentEmojis)
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

/** Persist an ARGB color as an unsigned 8-digit hex string (e.g. `FFADDDEF`). */
private fun formatColor(argb: Int): String = "%08X".format(argb)

private fun parseColor(raw: String?, fallback: Int): Int =
    raw?.trim()?.takeIf { it.isNotEmpty() }?.toLongOrNull(16)?.toInt() ?: fallback
