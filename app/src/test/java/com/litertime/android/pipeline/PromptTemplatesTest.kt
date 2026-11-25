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
    fun cascadePromptsAreTextBasedAndDistinctFromAudioPrompts() {
        val cascadePrompts = VoiceMode.entries.map { PromptTemplates.userPromptFor(it, cascade = true) }
        cascadePrompts.forEach { assertTrue(it.isNotBlank()) }
        assertEquals(VoiceMode.entries.size, cascadePrompts.toSet().size)
        VoiceMode.entries.forEach { mode ->
            assertTrue(
                PromptTemplates.userPromptFor(mode, cascade = true) !=
                    PromptTemplates.userPromptFor(mode),
            )
        }

        assertTrue(PromptTemplates.SYSTEM_PROMPT_CASCADE_VOICE_IME.contains("语音识别"))
        assertEquals(
            PromptTemplates.SYSTEM_PROMPT_CASCADE_VOICE_IME,
            PromptTemplates.resolveSystemPrompt(override = null, saved = "", cascade = true),
        )
        // An explicit saved prompt still wins over the cascade default.
        assertEquals(
            "自定义系统提示词",
            PromptTemplates.resolveSystemPrompt(
                override = null,
                saved = "自定义系统提示词",
                cascade = true,
            ),
        )
        assertEquals(
            PromptTemplates.userPromptFor(VoiceMode.GEMMA_POLISH_FORMAL, cascade = true),
            PromptTemplates.resolveUserPrompt(
                mode = VoiceMode.GEMMA_POLISH_FORMAL,
                override = null,
                saved = "",
                cascade = true,
            ),
        )
    }

    @Test
    fun instructionModeUsesDedicatedSystemPrompt() {
        assertEquals(
            PromptTemplates.SYSTEM_PROMPT_SCREENSHOT_INSTRUCTION,
            PromptTemplates.resolveSystemPrompt(
                override = null,
                saved = "全局系统提示词",
                mode = VoiceMode.GEMMA_SCREENSHOT_INSTRUCTION,
            ),
        )
        assertEquals(
            "指令专用提示词",
            PromptTemplates.resolveSystemPrompt(
                override = null,
                mode = VoiceMode.GEMMA_SCREENSHOT_INSTRUCTION,
                modeSaved = "指令专用提示词",
            ),
        )
        assertEquals(
            "回放覆盖",
            PromptTemplates.resolveSystemPrompt(
                override = "回放覆盖",
                mode = VoiceMode.GEMMA_SCREENSHOT_INSTRUCTION,
                modeSaved = "指令专用提示词",
            ),
        )
        // Other modes keep using the shared prompt.
        assertEquals(
            "全局系统提示词",
            PromptTemplates.resolveSystemPrompt(
                override = null,
                saved = "全局系统提示词",
                mode = VoiceMode.WHISPER_RAW,
                modeSaved = "指令专用提示词",
            ),
        )
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
