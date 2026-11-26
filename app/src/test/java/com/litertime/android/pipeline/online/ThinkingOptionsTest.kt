package com.litertime.android.pipeline.online

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThinkingOptionsTest {
    @Test
    fun detectsModelsWithConfigurableThinking() {
        val supported = listOf(
            "o3-mini",
            "openai/gpt-5-mini",
            "deepseek-v4-pro",
            "deepseek-v4.1-flash",
            "deepseek-r1",
            "deepseek-reasoner",
            "qwen3.8-max",
            "Qwen/Qwen3-235B-A22B",
            "qwen-plus",
            "qwq-32b",
            "glm-4.6",
            "glm-5",
            "kimi-k2-thinking",
            "moonshotai/kimi-k3",
            "google/gemini-2.5-pro",
            "anthropic/claude-sonnet-4",
            "claude-3-7-sonnet",
            "grok-4",
            "magistral-medium",
            "vendor/some-reasoner-model",
        )
        supported.forEach { model ->
            assertTrue("expected thinking support for $model", OnlineThinkingSupport.supportsThinking(model))
        }

        val unsupported = listOf(
            "gpt-4o-mini",
            "deepseek-chat",
            "llama-3.1-70b-instruct",
            "qwen2.5-72b",
            "",
        )
        unsupported.forEach { model ->
            assertFalse("expected no thinking support for $model", OnlineThinkingSupport.supportsThinking(model))
        }
    }

    @Test
    fun openAiReasoningModelsUseMaxCompletionTokens() {
        listOf("o1", "o3-mini", "o4-mini", "openai/gpt-5", "gpt-5-mini").forEach { model ->
            assertTrue(model, OnlineThinkingSupport.usesMaxCompletionTokens(model))
            assertFalse(model, OnlineThinkingSupport.acceptsTemperature(model))
        }
        listOf("gpt-4o", "gpt-4o-mini", "deepseek-v4-pro", "qwen3.8-max").forEach { model ->
            assertFalse(model, OnlineThinkingSupport.usesMaxCompletionTokens(model))
            assertTrue(model, OnlineThinkingSupport.acceptsTemperature(model))
        }
    }

    @Test
    fun parsesStoredEffortAndFallsBackToMedium() {
        assertEquals(ThinkingEffort.LOW, ThinkingEffort.fromStorageId("low"))
        assertEquals(ThinkingEffort.MEDIUM, ThinkingEffort.fromStorageId("MEDIUM"))
        assertEquals(ThinkingEffort.HIGH, ThinkingEffort.fromStorageId(" high "))
        assertNull(ThinkingEffort.fromStorageId(""))
        assertNull(ThinkingEffort.fromStorageId(null))
        assertEquals(ThinkingEffort.MEDIUM, ThinkingEffort.fromStorageIdOrDefault("unknown"))
    }

    @Test
    fun picksProviderSpecificThinkingDialect() {
        assertEquals(
            OnlineProviderDialect.DEEPSEEK,
            OnlineRequestOptions.detectDialect("https://api.deepseek.com/v1", "deepseek-v4-pro"),
        )
        assertEquals(
            OnlineProviderDialect.OPENROUTER,
            OnlineRequestOptions.detectDialect("https://openrouter.ai/api/v1", "openai/gpt-5"),
        )
        assertEquals(
            OnlineProviderDialect.DASHSCOPE,
            OnlineRequestOptions.detectDialect(
                "https://dashscope.aliyuncs.com/compatible-mode/v1",
                "qwen3.8-max",
            ),
        )
        assertEquals(
            OnlineProviderDialect.DASHSCOPE,
            OnlineRequestOptions.detectDialect("https://example.com/v1", "qwen3-32b"),
        )
        assertEquals(
            OnlineProviderDialect.ZHIPU,
            OnlineRequestOptions.detectDialect(
                "https://open.bigmodel.cn/api/paas/v4",
                "glm-4.6",
            ),
        )
        assertEquals(
            OnlineProviderDialect.MOONSHOT,
            OnlineRequestOptions.detectDialect("https://api.moonshot.ai/v1", "kimi-k3"),
        )
        assertEquals(
            OnlineProviderDialect.OPENAI,
            OnlineRequestOptions.detectDialect("https://api.openai.com/v1", "o3-mini"),
        )
    }

    @Test
    fun manualProviderDialectOverridesAutoDetection() {
        assertEquals(
            OnlineProviderDialect.OPENAI,
            OnlineRequestOptions.resolveDialect(
                OnlineProviderDialect.AUTO,
                "https://proxy.example/v1",
                "my-custom-model",
            ),
        )
        assertEquals(
            OnlineProviderDialect.ZHIPU,
            OnlineRequestOptions.resolveDialect(
                OnlineProviderDialect.ZHIPU,
                "https://proxy.example/v1",
                "my-custom-model",
            ),
        )
        assertEquals(
            OnlineProviderDialect.OPENROUTER,
            OnlineRequestOptions.resolveDialect(
                OnlineProviderDialect.OPENROUTER,
                "https://api.deepseek.com/v1",
                "deepseek-v4-pro",
            ),
        )
    }

    @Test
    fun providerDialectStorageIdsRoundTrip() {
        OnlineProviderDialect.entries.forEach { dialect ->
            assertEquals(dialect, OnlineProviderDialect.fromStorageId(dialect.storageId))
        }
        assertEquals(OnlineProviderDialect.AUTO, OnlineProviderDialect.fromStorageId(""))
        assertEquals(OnlineProviderDialect.AUTO, OnlineProviderDialect.fromStorageId("nope"))
    }
}
