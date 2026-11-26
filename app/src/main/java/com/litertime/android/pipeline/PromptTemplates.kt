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
你是一个无感情的文本润色生成引擎，不是对话助手。用户提供的文字来自语音识别（ASR），可能包含同音字、断句或标点错误。根据用户的指令，输出最终可直接上屏的文本内容，不要添加任何前缀、解释、引号或 Markdown 包裹。如果用户没有要求，陈述句最后不要添加句号。如果无法确定语音的语言，请输出简体中文。
""".trimIndent()

    /**
     * Dedicated system prompt for the screenshot instruction mode. In this mode the user message
     * is exactly the ASR transcript (the spoken command), optionally followed by a screenshot, so
     * all instruction-following semantics live here instead of being duplicated in the user prompt.
     */
    val SYSTEM_PROMPT_SCREENSHOT_INSTRUCTION: String = """
你是一个输入法语音指令执行助手，不是聊天助手。用户的输入是一条指令，并附带当前屏幕截图。请理解屏幕内容，并严格按照该指令生成可直接输入到当前输入框的文本结果。只输出最终结果，不要添加任何前缀、解释、引号或 Markdown 包裹。如果用户没有要求，陈述句最后不要添加句号。如果无法确定语言，请输出简体中文。
""".trimIndent()

    /**
     * Dedicated system prompt for the screenshot rebuttal mode. The user message is exactly the ASR
     * transcript (the user's spoken stance, arguments, or objection to what is on screen), so this
     * prompt must both locate the viewpoint in the screenshot and fold in the user's own position.
     * An empty transcript is a valid input here: the model then rebuts the main on-screen claim.
     */
    val SYSTEM_PROMPT_SCREENSHOT_REBUTTAL: String = """
你是一个输入法里的反驳助手，不是聊天助手。用户会附带当前屏幕截图，并把语音识别后的文本作为用户输入；这段文本通常代表用户自己的立场、论据或对图中观点的不满。请先找出截图中要反驳的核心观点，再结合用户的立场，用犀利、精准、针针见血、毒辣、祖安的语言写出可直接上屏的反驳，大约20-50字。只输出反驳正文，不要添加任何前缀、解释、引号或 Markdown 包裹；不要复述截图内容。如果用户的语音文本为空或与截图无关，就直接反驳图中最主要的观点。
""".trimIndent()

    /** Built-in dedicated system prompt for [mode], or null when it shares the unified prompt. */
    fun dedicatedSystemPromptFor(mode: VoiceMode): String? = when (mode) {
        VoiceMode.GEMMA_SCREENSHOT_INSTRUCTION -> SYSTEM_PROMPT_SCREENSHOT_INSTRUCTION
        VoiceMode.GEMMA_SCREENSHOT_REBUTTAL -> SYSTEM_PROMPT_SCREENSHOT_REBUTTAL
        VoiceMode.WHISPER_RAW,
        VoiceMode.GEMMA_POLISH_FORMAL,
        VoiceMode.GEMMA_SCREENSHOT_CORRECTION,
        -> null
    }

    /**
     * Resolves the system prompt actually sent to the model. A non-null [override] wins so the
     * debug playground can replay a request with an edited prompt (including an intentionally
     * empty one) without touching the defaults. Modes with a dedicated prompt use their saved
     * override ([dedicatedSaved]) or the built-in default. Other modes use the shared saved
     * prompt, with a blank value meaning "keep the built-in default"; [cascade] selects the
     * ASR-text default.
     */
    fun resolveSystemPrompt(
        override: String?,
        saved: String? = null,
        cascade: Boolean = false,
        mode: VoiceMode? = null,
        dedicatedSaved: Map<VoiceMode, String> = emptyMap(),
    ): String {
        override?.let { return it }
        val dedicatedDefault = mode?.let(::dedicatedSystemPromptFor)
        if (dedicatedDefault != null) {
            val modeSaved = dedicatedSaved[mode]
            return modeSaved?.takeIf { it.isNotBlank() } ?: dedicatedDefault
        }
        return saved?.takeIf { it.isNotBlank() }
            ?: if (cascade) SYSTEM_PROMPT_CASCADE_VOICE_IME else SYSTEM_PROMPT_VOICE_IME
    }

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
            "请将这段语音转写为文字，并先纠正明显的同音字、断句或标点错误；保持用户原本的表达、语气和意图，不要扩写或改写内容。直接输出最终文本。\n"
        VoiceMode.GEMMA_POLISH_FORMAL ->
            "以下文字来自语音识别，可能含同音字、断句或标点错误。请先纠正明显的识别错误，再润色为正式、得体、清晰的书面或商务表达：保留核心语义，优化措辞与标点，保持原有人称视角（如“您”、“我”）和核心语义，严禁擅自科普解释。使用*至少50字*表达用户的意思。直接输出最终文本。\n"
        VoiceMode.GEMMA_SCREENSHOT_CORRECTION ->
            "请结合附带的当前屏幕截图理解上下文，将这段语音转写为文字，并纠正与屏幕内容明显不符的识别错误，注意用户输入与屏幕内容的关联性；保持用户原本的表达、语气和意图，不要照搬或扩写截图内容。直接输出纠正后的最终文本。\n"
        VoiceMode.GEMMA_SCREENSHOT_INSTRUCTION ->
            "请结合附带的当前屏幕截图理解上下文，并严格按照语音中的指令直接生成可输入到当前输入框的文本结果，不要输出多余说明。如果用户没有要求，大约输出50-100字。\n"
        VoiceMode.GEMMA_SCREENSHOT_REBUTTAL ->
            "请结合这段语音中用户表达的立场或论据，针对当前屏幕截图中的主要观点，用犀利、针针见血、毒辣、凶狠、尖锐、祖安的语言写一段反驳。直接输出反驳正文。\n"
    }

    /**
     * Mode prompts for the voice cascade: the model decodes text produced by Whisper/ASR, so the
     * wording drops "听写这段语音" and instead asks the model to fix transcription errors.
     */
    private fun cascadeUserPromptFor(mode: VoiceMode): String = when (mode) {
        VoiceMode.WHISPER_RAW ->
            "以下文字来自语音识别，可能含同音字、断句或标点错误。请先纠正明显的识别错误，保持用户原本的表达、语气和意图，不要扩写或改写内容。直接输出最终文本。\n"
        VoiceMode.GEMMA_POLISH_FORMAL ->
            "以下文字是语音识别结果，可能含同音字、断句或标点错误。请先纠正识别错误，再润色为正式、得体、清晰的书面或商务表达：保留核心语义，优化措辞与标点，保持原有人称视角（如“您”、“我”）和核心语义，严禁擅自科普解释。使用*至少50字*表达用户的意思。直接输出最终文本。\n"
        VoiceMode.GEMMA_SCREENSHOT_CORRECTION ->
            "以下文字来自语音识别，可能含同音字、断句或标点错误。请结合附带的当前屏幕截图理解上下文，纠正与屏幕内容明显不符的识别错误，并输出纠正后的最终文本；保持用户原本的表达、语气和意图，不要照搬或扩写截图内容。\n"
        VoiceMode.GEMMA_SCREENSHOT_INSTRUCTION ->
            "以下文字是语音指令的识别结果，可能含同音字、断句或标点错误。请结合附带的当前屏幕截图理解上下文，严格按照指令直接生成可输入到当前输入框的文本结果，不要输出多余说明。如果用户没有要求，大约输出50-100字。\n"
        VoiceMode.GEMMA_SCREENSHOT_REBUTTAL ->
            "请针对当前屏幕截图中的主要观点，用犀利、针针见血、凶狠、尖锐、祖安的语言写一段反驳。如果识别文本中给出了用户的立场或论据，请融入反驳，但不要复述识别文本；如果识别文本为空，就直接反驳图中最主要的观点。直接输出反驳正文。\n"
    }
}