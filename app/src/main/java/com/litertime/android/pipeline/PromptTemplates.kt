package com.litertime.android.pipeline

object PromptTemplates {
    const val SYSTEM_PROMPT_VOICE_IME: String =
        "你是一个输入法语音助手。根据用户的指令，输出最终可直接上屏的文本内容，不要添加任何前缀、解释、引号或 Markdown 包裹。如果用户没有要求，陈述句最后不要添加句号。"

    /**
     * Resolves the system prompt actually sent to the model. A non-null [override] wins so the
     * debug playground can replay a request with an edited prompt (including an intentionally
     * empty one) without touching the defaults.
     */
    fun resolveSystemPrompt(override: String?): String =
        override ?: SYSTEM_PROMPT_VOICE_IME

    /**
     * Resolves the user prompt actually sent to the model, falling back to the mode template.
     */
    fun resolveUserPrompt(mode: VoiceMode, override: String?): String =
        override ?: userPromptFor(mode)

    fun userPromptFor(mode: VoiceMode): String = when (mode) {
        VoiceMode.WHISPER_RAW ->
            "请原样转写这段语音。"
        VoiceMode.GEMMA_POLISH_NORMAL ->
            "请将这段语音听写为自然流畅的日常书面文字：保留原意，去除口头禅、重复和明显口误，补全合适标点。直接输出最终文本。"
        VoiceMode.GEMMA_POLISH_FORMAL ->
            "请将这段语音听写并润色为正式、得体、清晰的书面或商务表达：保留核心语义，优化措辞与标点，使用*至少100字*表达用户的意思。直接输出最终文本。"
        VoiceMode.GEMMA_SCREENSHOT_INSTRUCTION ->
            "请结合附带的当前屏幕截图理解上下文，并严格按照语音中的指令直接生成可输入到当前输入框的文本结果，不要输出多余说明。如果用户没有要求，大约输出100字。"
    }
}
