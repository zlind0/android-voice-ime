package com.litertime.android.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptTemplatesTest {
    @Test
    fun allModesHaveDistinctNonBlankPrompts() {
        val prompts = VoiceMode.entries.map { PromptTemplates.userPromptFor(it) }
        prompts.forEach { assertTrue(it.isNotBlank()) }
        assertEquals(VoiceMode.entries.size, prompts.toSet().size)
        assertTrue(PromptTemplates.SYSTEM_PROMPT_VOICE_IME.isNotBlank())
    }

    @Test
    fun savedPromptsOverrideDefaultsAndBlankFallsBack() {
        val mode = VoiceMode.GEMMA_SCREENSHOT_CORRECTION

        assertEquals(
            "自定义系统提示词",
            PromptTemplates.resolveSystemPrompt(override = null, saved = "自定义系统提示词"),
        )
        assertEquals(
            PromptTemplates.SYSTEM_PROMPT_VOICE_IME,
            PromptTemplates.resolveSystemPrompt(override = null, saved = "   "),
        )
        assertEquals(
            "自定义模式提示词",
            PromptTemplates.resolveUserPrompt(mode = mode, override = null, saved = "自定义模式提示词"),
        )
        assertEquals(
            PromptTemplates.userPromptFor(mode),
            PromptTemplates.resolveUserPrompt(mode = mode, override = null, saved = ""),
        )
        // Playground overrides stay stronger than saved settings, including an empty one.
        assertEquals(
            "",
            PromptTemplates.resolveSystemPrompt(override = "", saved = "自定义系统提示词"),
        )
    }
}
