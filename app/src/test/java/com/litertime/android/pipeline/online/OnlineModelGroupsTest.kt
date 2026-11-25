package com.litertime.android.pipeline.online

import org.junit.Assert.assertEquals
import org.junit.Test

class OnlineModelGroupsTest {
    @Test
    fun groupsPrefixedModelsByVendorAndKeepsBareIdsSeparate() {
        val groups = OnlineModelGroups.group(
            listOf(
                "openai/gpt-4o-mini",
                "plain-model",
                "anthropic/claude-3.5-sonnet",
                "openai/gpt-4o",
                "openai/gpt-4o-mini",
            ),
        )

        assertEquals(listOf("其他", "anthropic", "openai"), groups.map { it.vendor })
        assertEquals(listOf("plain-model"), groups[0].models)
        assertEquals(listOf("anthropic/claude-3.5-sonnet"), groups[1].models)
        assertEquals(
            listOf("openai/gpt-4o", "openai/gpt-4o-mini"),
            groups[2].models,
        )
    }

    @Test
    fun normalizesCommonEndpointForms() {
        assertEquals(
            "https://api.openai.com/v1",
            OnlineLlmApi.normalizeBaseUrl(" https://api.openai.com/v1/ "),
        )
        assertEquals(
            "https://api.openai.com/v1",
            OnlineLlmApi.normalizeBaseUrl("https://api.openai.com/v1/chat/completions"),
        )
        assertEquals(
            "https://api.openai.com/v1",
            OnlineLlmApi.normalizeBaseUrl("https://api.openai.com/v1/models"),
        )
        assertEquals(
            "https://api.openai.com/v1/chat/completions",
            OnlineLlmApi.chatCompletionsUrl("https://api.openai.com/v1/"),
        )
    }
}