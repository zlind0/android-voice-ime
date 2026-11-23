package com.litertime.android.storage

enum class DownloadMirror(val displayName: String, val baseUrl: String, val supportsAsr: Boolean) {
    HF_MIRROR("HF 镜像 (hf-mirror.com)", "https://hf-mirror.com", true),
    HUGGING_FACE("Hugging Face", "https://huggingface.co", true),
    MODELSCOPE("ModelScope (仅 LLM)", "https://modelscope.cn", false);

    companion object {
        fun fromId(value: String?): DownloadMirror =
            entries.firstOrNull { it.name == value } ?: HF_MIRROR
    }
}

enum class AsrEngineKind {
    WHISPER,
    SENSE_VOICE,
}

data class LlmModelSpec(
    val id: String,
    val displayName: String,
    val fileName: String,
    val expectedSizeBytes: Long,
    val repo: String,
    val commit: String,
    val minRamGb: Int,
    val description: String,
) {
    fun downloadUrl(mirror: DownloadMirror): String = when (mirror) {
        DownloadMirror.MODELSCOPE ->
            "https://modelscope.cn/models/$repo/resolve/master/$fileName"
        DownloadMirror.HF_MIRROR, DownloadMirror.HUGGING_FACE ->
            "${mirror.baseUrl}/$repo/resolve/$commit/$fileName?download=true"
    }
}

data class AsrFileSpec(
    val fileName: String,
    val approxSizeBytes: Long,
)

data class AsrModelSpec(
    val id: String,
    val displayName: String,
    val kind: AsrEngineKind,
    val repo: String,
    val languageSummary: String,
    val files: List<AsrFileSpec>,
    val encoderFile: String? = null,
    val decoderFile: String? = null,
    val senseVoiceModelFile: String? = null,
    val tokensFile: String,
) {
    val totalApproxBytes: Long
        get() = files.sumOf { it.approxSizeBytes }

    fun downloadUrl(fileName: String, mirror: DownloadMirror): String {
        val effectiveMirror = if (mirror.supportsAsr) mirror else DownloadMirror.HF_MIRROR
        return "${effectiveMirror.baseUrl}/$repo/resolve/main/$fileName?download=true"
    }
}

object ModelCatalog {
    val llmModels: List<LlmModelSpec> = listOf(
        LlmModelSpec(
            id = "gemma4-e2b",
            displayName = "Gemma 4 E2B",
            fileName = "gemma-4-E2B-it.litertlm",
            expectedSizeBytes = 2_588_147_712L,
            repo = "litert-community/gemma-4-E2B-it-litert-lm",
            commit = "6e5c4f1e395deb959c494953478fa5cec4b8008f",
            minRamGb = 8,
            description = "端侧多模态模型（音频+视觉），推荐 8GB+ 内存设备",
        ),
        LlmModelSpec(
            id = "gemma4-e4b",
            displayName = "Gemma 4 E4B",
            fileName = "gemma-4-E4B-it.litertlm",
            expectedSizeBytes = 3_659_530_240L,
            repo = "litert-community/gemma-4-E4B-it-litert-lm",
            commit = "28299f30ee4d43294517a4ac93abd6163412f07f",
            minRamGb = 12,
            description = "更大参数量端侧多模态模型，推荐 12GB+ 内存设备",
        ),
    )

    val asrModels: List<AsrModelSpec> = listOf(
        AsrModelSpec(
            id = "whisper-small",
            displayName = "Whisper Small (多语言)",
            kind = AsrEngineKind.WHISPER,
            repo = "csukuangfj/sherpa-onnx-whisper-small",
            languageSummary = "99 种语言（含中文/英文）",
            files = listOf(
                AsrFileSpec("small-encoder.int8.onnx", 112_400_000L),
                AsrFileSpec("small-decoder.int8.onnx", 262_200_000L),
                AsrFileSpec("small-tokens.txt", 816_000L),
            ),
            encoderFile = "small-encoder.int8.onnx",
            decoderFile = "small-decoder.int8.onnx",
            tokensFile = "small-tokens.txt",
        ),
        AsrModelSpec(
            id = "whisper-base",
            displayName = "Whisper Base (多语言)",
            kind = AsrEngineKind.WHISPER,
            repo = "csukuangfj/sherpa-onnx-whisper-base",
            languageSummary = "99 种语言（含中文/英文）",
            files = listOf(
                AsrFileSpec("base-encoder.int8.onnx", 29_100_000L),
                AsrFileSpec("base-decoder.int8.onnx", 130_700_000L),
                AsrFileSpec("base-tokens.txt", 816_000L),
            ),
            encoderFile = "base-encoder.int8.onnx",
            decoderFile = "base-decoder.int8.onnx",
            tokensFile = "base-tokens.txt",
        ),
        AsrModelSpec(
            id = "whisper-tiny-en",
            displayName = "Whisper Tiny EN",
            kind = AsrEngineKind.WHISPER,
            repo = "csukuangfj/sherpa-onnx-whisper-tiny.en",
            languageSummary = "仅英语",
            files = listOf(
                AsrFileSpec("tiny.en-encoder.int8.onnx", 12_900_000L),
                AsrFileSpec("tiny.en-decoder.int8.onnx", 89_900_000L),
                AsrFileSpec("tiny.en-tokens.txt", 816_000L),
            ),
            encoderFile = "tiny.en-encoder.int8.onnx",
            decoderFile = "tiny.en-decoder.int8.onnx",
            tokensFile = "tiny.en-tokens.txt",
        ),
        AsrModelSpec(
            id = "whisper-base-en",
            displayName = "Whisper Base EN",
            kind = AsrEngineKind.WHISPER,
            repo = "csukuangfj/sherpa-onnx-whisper-base.en",
            languageSummary = "仅英语",
            files = listOf(
                AsrFileSpec("base.en-encoder.int8.onnx", 29_100_000L),
                AsrFileSpec("base.en-decoder.int8.onnx", 130_700_000L),
                AsrFileSpec("base.en-tokens.txt", 816_000L),
            ),
            encoderFile = "base.en-encoder.int8.onnx",
            decoderFile = "base.en-decoder.int8.onnx",
            tokensFile = "base.en-tokens.txt",
        ),
        AsrModelSpec(
            id = "whisper-small-en",
            displayName = "Whisper Small EN",
            kind = AsrEngineKind.WHISPER,
            repo = "csukuangfj/sherpa-onnx-whisper-small.en",
            languageSummary = "仅英语",
            files = listOf(
                AsrFileSpec("small.en-encoder.int8.onnx", 112_400_000L),
                AsrFileSpec("small.en-decoder.int8.onnx", 262_200_000L),
                AsrFileSpec("small.en-tokens.txt", 816_000L),
            ),
            encoderFile = "small.en-encoder.int8.onnx",
            decoderFile = "small.en-decoder.int8.onnx",
            tokensFile = "small.en-tokens.txt",
        ),
        AsrModelSpec(
            id = "sensevoice",
            displayName = "SenseVoice (中英日韩粤)",
            kind = AsrEngineKind.SENSE_VOICE,
            repo = "csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17",
            languageSummary = "中/英/日/韩/粤",
            files = listOf(
                AsrFileSpec("model.int8.onnx", 239_233_841L),
                AsrFileSpec("tokens.txt", 311_000L),
            ),
            senseVoiceModelFile = "model.int8.onnx",
            tokensFile = "tokens.txt",
        ),
    )

    fun findLlm(id: String?): LlmModelSpec? = llmModels.firstOrNull { it.id == id }

    fun findAsr(id: String?): AsrModelSpec? = asrModels.firstOrNull { it.id == id }
}
