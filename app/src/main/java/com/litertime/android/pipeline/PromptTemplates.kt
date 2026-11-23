const val SYSTEM_PROMPT_VOICE_IME: String = """
You are an intelligent voice IME engine. Output ONLY the final Chinese text ready to be inserted into the active input field.
Rules:
1. Intent routing:
   - Regular speech: transcribe and polish according to the mode's style.
   - Meta-instruction (e.g., "帮我委婉拒绝一下", "按这个回复他"): execute the command and draft the reply instead of transcribing the command.
2. Context & length:
   - When a screenshot is provided, match the tone and length to the app context (e.g., concise for chat apps, thorough for emails/documents).
3. Formatting:
   - Output ONLY the final target text. Never repeat instructions, prompts, prefixes, quotes, or Markdown.
   - Omit the trailing period for single declarative sentences unless requested.
4. 如果需要输出中文，请输出简体中文。
""".trimIndent()

    fun userPromptFor(mode: VoiceMode): String = when (mode) {
        VoiceMode.WHISPER_RAW ->
            "请原样转写这段语音。"
        VoiceMode.GEMMA_POLISH_NORMAL ->
            "Mode: Daily Chinese polish (remove fillers/stutters, fix punctuation).\n[Audio Instruction]:"
        VoiceMode.GEMMA_POLISH_FORMAL ->
            "Mode: Formal/business Chinese polish (polite, clear, well-structured).\n[Audio Instruction]:"
        VoiceMode.GEMMA_SCREENSHOT_INSTRUCTION ->
            "Mode: Context-aware reply based on the attached screenshot and audio instruction.\n[Screen Context]:"
    }