package com.litertime.android.settings

import com.litertime.android.pipeline.VoiceMode
import com.litertime.android.pipeline.online.OnlineProviderDialect
import com.litertime.android.pipeline.online.ThinkingEffort
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineLlmSettingsTest {
    @Test
    fun perModeOverridesFallBackToUnifiedValues() {
        val settings = ImeSettings(
            onlineLlmPreferred = true,
            onlineBaseUrl = "https://unified.example/v1",
            onlineApiKey = "unified-key",
            onlineModelName = "unified-model",
            onlinePerModeModels = true,
            onlineOverrides = mapOf(
                OnlineLlmModelProfile.POLISH to OnlineLlmOverride(
                    modelName = "polish-model",
                ),
                OnlineLlmModelProfile.SCREENSHOT_CORRECTION to OnlineLlmOverride(
                    baseUrl = "https://shots.example/v1",
                    apiKey = "shot-key",
                ),
            ),
        )

        val polish = settings.onlineConfigFor(VoiceMode.GEMMA_POLISH_FORMAL)
        assertEquals("https://unified.example/v1", polish.baseUrl)
        assertEquals("unified-key", polish.apiKey)
        assertEquals("polish-model", polish.modelName)

        val screenshot = settings.onlineConfigFor(VoiceMode.GEMMA_SCREENSHOT_CORRECTION)
        assertEquals("https://shots.example/v1", screenshot.baseUrl)
        assertEquals("shot-key", screenshot.apiKey)
        assertEquals("unified-model", screenshot.modelName)

        val correction = settings.onlineConfigFor(VoiceMode.WHISPER_RAW)
        assertEquals(settings.onlineUnifiedConfig, correction)
    }

    @Test
    fun rebuttalModeHasItsOwnOnlineProfile() {
        assertEquals(
            OnlineLlmModelProfile.SCREENSHOT_REBUTTAL,
            OnlineLlmModelProfile.fromVoiceMode(VoiceMode.GEMMA_SCREENSHOT_REBUTTAL),
        )
        assertEquals(
            OnlineLlmModelProfile.CORRECTION,
            OnlineLlmModelProfile.fromVoiceMode(VoiceMode.WHISPER_RAW),
        )

        val settings = ImeSettings(
            onlinePerModeModels = true,
            onlineModelName = "unified-model",
            onlineOverrides = mapOf(
                OnlineLlmModelProfile.SCREENSHOT_REBUTTAL to OnlineLlmOverride(
                    modelName = "rebuttal-model",
                ),
            ),
        )
        assertEquals(
            "rebuttal-model",
            settings.onlineConfigFor(VoiceMode.GEMMA_SCREENSHOT_REBUTTAL).modelName,
        )
        assertEquals(
            "unified-model",
            settings.onlineConfigFor(VoiceMode.GEMMA_SCREENSHOT_INSTRUCTION).modelName,
        )
    }

    @Test
    fun perModeFlagOffAlwaysUsesUnifiedConfig() {
        val settings = ImeSettings(
            onlinePerModeModels = false,
            onlineModelName = "unified-model",
            onlineOverrides = mapOf(
                OnlineLlmModelProfile.POLISH to OnlineLlmOverride(modelName = "ignored"),
            ),
        )

        assertEquals(
            "unified-model",
            settings.onlineConfigFor(VoiceMode.GEMMA_POLISH_FORMAL).modelName,
        )
    }

    @Test
    fun roundTripsOnlineConfiguration() {
        val dir = Files.createTempDirectory("ime-online-settings-test").toFile()
        try {
            val store = ImeSettingsStore(dir)
            assertFalse(store.getSettings().onlineLlmPreferred)

            store.saveSettings(
                ImeSettings(
                    onlineLlmPreferred = true,
                    onlineBaseUrl = "https://openrouter.ai/api/v1",
                    onlineApiKey = "secret",
                    onlineModelName = "openai/gpt-4o-mini",
                    onlinePerModeModels = true,
                    onlineOverrides = mapOf(
                        OnlineLlmModelProfile.SCREENSHOT_INSTRUCTION to OnlineLlmOverride(
                            baseUrl = "https://custom.example/v1",
                            apiKey = "custom-key",
                            modelName = "vendor/vision-model",
                        ),
                    ),
                ),
            )

            val reloaded = ImeSettingsStore(dir).getSettings()
            assertTrue(reloaded.onlineLlmPreferred)
            assertTrue(reloaded.onlinePerModeModels)
            assertEquals("secret", reloaded.onlineApiKey)
            assertEquals(
                OnlineLlmOverride(
                    baseUrl = "https://custom.example/v1",
                    apiKey = "custom-key",
                    modelName = "vendor/vision-model",
                ),
                reloaded.onlineOverride(OnlineLlmModelProfile.SCREENSHOT_INSTRUCTION),
            )
            assertEquals(
                "vendor/vision-model",
                reloaded.onlineConfigFor(VoiceMode.GEMMA_SCREENSHOT_INSTRUCTION).modelName,
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun generationParametersRoundTripAndInheritPerMode() {
        val dir = Files.createTempDirectory("ime-online-generation-test").toFile()
        try {
            val store = ImeSettingsStore(dir)
            val defaults = store.getSettings()
            assertEquals(ResolvedOnlineLlmConfig.DEFAULT_MAX_TOKENS, defaults.onlineMaxTokens)
            assertEquals(
                ResolvedOnlineLlmConfig.DEFAULT_TEMPERATURE,
                defaults.onlineTemperature,
                0.0001,
            )
            assertFalse(defaults.onlineThinkingEnabled)

            store.saveSettings(
                ImeSettings(
                    onlineModelName = "deepseek-v4-pro",
                    onlineMaxTokens = 4096,
                    onlineTemperature = 0.7,
                    onlineThinkingEnabled = true,
                    onlineThinkingEffort = ThinkingEffort.HIGH,
                    onlineProviderDialect = OnlineProviderDialect.MOONSHOT,
                    onlinePerModeModels = true,
                    onlineOverrides = mapOf(
                        OnlineLlmModelProfile.POLISH to OnlineLlmOverride(
                            maxTokens = 2048,
                            thinkingEnabled = false,
                            providerDialect = OnlineProviderDialect.OPENROUTER,
                        ),
                    ),
                ),
            )

            val reloaded = ImeSettingsStore(dir).getSettings()
            assertEquals(4096, reloaded.onlineMaxTokens)
            assertEquals(0.7, reloaded.onlineTemperature, 0.0001)
            assertTrue(reloaded.onlineThinkingEnabled)
            assertEquals(ThinkingEffort.HIGH, reloaded.onlineThinkingEffort)
            assertEquals(OnlineProviderDialect.MOONSHOT, reloaded.onlineProviderDialect)

            val unified = reloaded.onlineUnifiedConfig
            assertEquals(4096, unified.maxTokens)
            assertEquals(ThinkingEffort.HIGH, unified.thinkingEffort)
            assertEquals(OnlineProviderDialect.MOONSHOT, unified.providerDialect)

            // Only the explicitly overridden values differ; the rest inherits the unified config.
            val polish = reloaded.onlineConfigFor(VoiceMode.GEMMA_POLISH_FORMAL)
            assertEquals(2048, polish.maxTokens)
            assertFalse(polish.thinkingEnabled)
            assertEquals(0.7, polish.temperature, 0.0001)
            assertEquals(ThinkingEffort.HIGH, polish.thinkingEffort)
            // Provider adaptation is per profile so each endpoint can be detected independently.
            assertEquals(OnlineProviderDialect.OPENROUTER, polish.providerDialect)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun migratesLegacyOnlineProviderToPreferredSwitch() {
        val dir = Files.createTempDirectory("ime-online-migration-test").toFile()
        try {
            java.io.File(dir, "ime-settings.properties").writeText(
                "llmProvider=ONLINE_OPENAI_COMPAT\n",
            )
            assertTrue(ImeSettingsStore(dir).getSettings().onlineLlmPreferred)
        } finally {
            dir.deleteRecursively()
        }
    }
}