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
}
