package com.litertime.android.settings

import com.litertime.android.pipeline.VoiceMode
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