package com.litertime.android.pipeline

object PromptTemplates {
    val SYSTEM_PROMPT_VOICE_IME: String = """
你是一个输入法语音助手。根据用户的指令，输出最终可直接上屏的文本内容，不要添加任何前缀、解释、引号或 Markdown 包裹。如果用户没有要求，陈述句最后不要添加句号。如果无法确定语音的语言，请输出简体中文。
""".trimIndent()

    /**
     * System prompt used by the "voice cascade" pipeline, where the audio has already been
     * transcribed by Whisper/ASR and the model receives text instead of audio.
     */
    val SYSTEM_PROMPT_CASCADE_VOICE_IME: String = """
你是一个无感情的文本润色生成引擎，不是对话助手。根据用户的指令，输出最终可直接上屏的文本内容，不要添加任何前缀、解释、引号或 Markdown 包裹。如果用户没有要求，陈述句最后不要添加句号。如果无法确定语音的语言，请输出简体中文。
""".trimIndent()

    /**
     * Resolves the system prompt actually sent to the model. A non-null [override] wins so the
     * debug playground can replay a request with an edited prompt (including an intentionally
     * empty one) without touching the defaults. Otherwise the user's saved prompt is used, with a
     * blank value meaning "keep the built-in default". [cascade] selects the ASR-text default.
     */
    fun resolveSystemPrompt(
        override: String?,
        saved: String? = null,
        cascade: Boolean = false,
    ): String = override ?: saved?.takeIf { it.isNotBlank() }
        ?: if (cascade) SYSTEM_PROMPT_CASCADE_VOICE_IME else SYSTEM_PROMPT_VOICE_IME

    /**
     * Resolves the user prompt actually sent to the model, falling back to the saved per-mode
     * prompt and finally to the built-in mode template.
     */
    fun resolveUserPrompt(
        mode: VoiceMode,
        override: String?,
        saved: String? = null,
        cascade: Boolean = false,
    ): String = override ?: saved?.takeIf { it.isNotBlank() } ?: userPromptFor(mode, cascade)

    fun userPromptFor(mode: VoiceMode, cascade: Boolean = false): String =
        if (cascade) cascadeUserPromptFor(mode) else audioUserPromptFor(mode)

    private fun audioUserPromptFor(mode: VoiceMode): String = when (mode) {
        VoiceMode.WHISPER_RAW ->
            "请原样转写这段语音。\n"
        VoiceMode.GEMMA_POLISH_FORMAL ->
            "以下文字来自语音识别，可能含同音字、断句或标点错误。请先纠正明显的识别错误，再润色为正式、得体、清晰的书面或商务表达：保留核心语义，优化措辞与标点，保持原有人称视角（如“您”、“我”）和核心语义，严禁擅自科普解释。使用*至少50字*表达用户的意思。直接输出最终文本。\n"
        VoiceMode.GEMMA_SCREENSHOT_CORRECTION ->
            "请结合附带的当前屏幕截图理解上下文，将这段语音转写为文字，并纠正与屏幕内容明显不符的识别错误，注意用户输入与屏幕内容的关联性；保持用户原本的表达、语气和意图，不要照搬或扩写截图内容。直接输出纠正后的最终文本。\n"
        VoiceMode.GEMMA_SCREENSHOT_INSTRUCTION ->
            "请结合附带的当前屏幕截图理解上下文，并严格按照语音中的指令直接生成可输入到当前输入框的文本结果，不要输出多余说明。如果用户没有要求，大约输出50-100字。\n"
    }

    /**
     * Mode prompts for the voice cascade: the model decodes text produced by Whisper/ASR, so the
     * wording drops "听写这段语音" and instead asks the model to fix transcription errors.
     */
    private fun cascadeUserPromptFor(mode: VoiceMode): String = when (mode) {
        VoiceMode.WHISPER_RAW ->
            "请原样输出这段文字。\n"
        VoiceMode.GEMMA_POLISH_FORMAL ->
            "以下文字来自语音识别，可能含同音字、断句或标点错误。请先纠正明显的识别错误，再润色为正式、得体、清晰的书面或商务表达：保留核心语义，优化措辞与标点，使用*至少50字*表达用户的意思。直接输出最终文本。\n"
        VoiceMode.GEMMA_SCREENSHOT_CORRECTION ->
            "以下文字来自语音识别，可能含同音字、断句或标点错误。请结合附带的当前屏幕截图理解上下文，纠正与屏幕内容明显不符的识别错误，注意用户输入与屏幕内容的关联性；保持用户原本的表达、语气和意图，不要照搬或扩写截图内容。直接输出纠正后的最终文本。\n"
        VoiceMode.GEMMA_SCREENSHOT_INSTRUCTION ->
            "以下文字是语音指令的识别结果，可能含同音字、断句或标点错误。请结合附带的当前屏幕截图理解上下文，严格按照指令直接生成可输入到当前输入框的文本结果，不要输出多余说明。如果用户没有要求，大约输出50-100字。\n"
    }
}