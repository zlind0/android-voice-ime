package com.litertime.android.pipeline

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.litertime.android.audio.WavEncoder
import com.litertime.android.settings.ImeSettingsStore
import com.litertime.android.storage.AsrEngineKind
import com.litertime.android.storage.ModelCatalog
import com.litertime.android.storage.ModelStorage
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Offline ASR backend backed by sherpa-onnx (Whisper / SenseVoice).
 * Splits decoded output by natural punctuation/whitespace clauses and streams via [GenerationEvent.Token]
 * without artificial sleep delays.
 */
class SherpaAsrBackend(
    private val modelStorage: ModelStorage,
    private val settingsStore: ImeSettingsStore,
) : TextGenerationBackend {
    override val backendId: String = "sherpa_asr"
    override val displayName: String = "Whisper / ASR"

    private val loadMutex = Mutex()
    private val cancelledIds = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var recognizer: OfflineRecognizer? = null

    @Volatile
    private var loadedModelId: String? = null

    @Volatile
    private var loadedLanguage: String? = null

    override suspend fun ensureLoaded(modelId: String, onStatus: (String) -> Unit) {
        loadMutex.withLock {
            val spec = ModelCatalog.findAsr(modelId)
                ?: throw IllegalArgumentException("未知的 ASR 模型: $modelId")
            if (!modelStorage.isAsrInstalled(spec)) {
                throw IllegalStateException("ASR 模型 ${spec.displayName} 尚未下载")
            }

            val language = settingsStore.getSettings().whisperLanguage
            if (recognizer != null && loadedModelId == spec.id && loadedLanguage == language) {
                return
            }

            unloadInternal()
            onStatus("正在加载 ${spec.displayName}...")

            val dir = modelStorage.asrModelDir(spec.id)
            val tokensPath = File(dir, spec.tokensFile).absolutePath
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)

            val modelConfig = OfflineModelConfig().apply {
                tokens = tokensPath
                numThreads = threads
                provider = "cpu"
                debug = false
                when (spec.kind) {
                    AsrEngineKind.WHISPER -> {
                        modelType = "whisper"
                        whisper = OfflineWhisperModelConfig().apply {
                            encoder = File(dir, spec.encoderFile!!).absolutePath
                            decoder = File(dir, spec.decoderFile!!).absolutePath
                            this.language = language
                            task = "transcribe"
                            tailPaddings = -1
                        }
                    }
                    AsrEngineKind.SENSE_VOICE -> {
                        modelType = "sense_voice"
                        senseVoice = OfflineSenseVoiceModelConfig().apply {
                            model = File(dir, spec.senseVoiceModelFile!!).absolutePath
                            this.language = language.ifBlank { "auto" }
                            useInverseTextNormalization = true
                        }
                    }
                }
            }

            val recognizerConfig = OfflineRecognizerConfig().apply {
                featConfig = FeatureConfig(sampleRate = WavEncoder.SAMPLE_RATE, featureDim = 80)
                this.modelConfig = modelConfig
                decodingMethod = "greedy_search"
            }

            val created = withContext(Dispatchers.IO) {
                OfflineRecognizer(assetManager = null, config = recognizerConfig)
            }
            recognizer = created
            loadedModelId = spec.id
            loadedLanguage = language
            onStatus("${spec.displayName} 已就绪 (CPU)")
        }
    }

    override fun generateStream(request: VoiceGenerationRequest): Flow<GenerationEvent> = channelFlow {
        val activeFlag = AtomicBoolean(true)
        try {
            ensureLoaded(request.modelId) { status ->
                trySend(GenerationEvent.Status(status))
            }
        } catch (t: Throwable) {
            send(GenerationEvent.Error(t.message ?: "加载 Whisper 模型失败"))
            return@channelFlow
        }

        if (cancelledIds.remove(request.requestId)) {
            return@channelFlow
        }

        val currentRecognizer = recognizer
        if (currentRecognizer == null) {
            send(GenerationEvent.Error("ASR 识别器未就绪"))
            return@channelFlow
        }

        send(GenerationEvent.Status("Whisper 正在识别..."))
        val wavBytes = request.audioWavFile.readBytes()
        val (samples, sampleRate) = WavEncoder.wavToFloatSamples(wavBytes)

        if (cancelledIds.remove(request.requestId)) {
            return@channelFlow
        }

        val stream = currentRecognizer.createStream()
        val text = try {
            stream.acceptWaveform(samples, sampleRate)
            currentRecognizer.decode(stream)
            currentRecognizer.getResult(stream).text.trim()
        } finally {
            stream.release()
        }

        if (cancelledIds.remove(request.requestId) || !activeFlag.get()) {
            return@channelFlow
        }

        if (text.isNotEmpty()) {
            val chunks = splitIntoNaturalChunks(text)
            for (chunk in chunks) {
                if (cancelledIds.remove(request.requestId)) {
                    return@channelFlow
                }
                send(GenerationEvent.Token(chunk))
            }
        }
        send(GenerationEvent.Completed)
    }.flowOn(Dispatchers.IO)

    override fun cancel(requestId: String) {
        cancelledIds.add(requestId)
    }

    override fun unload() {
        unloadInternal()
    }

    private fun unloadInternal() {
        runCatching { recognizer?.release() }
        recognizer = null
        loadedModelId = null
        loadedLanguage = null
    }

    companion object {
        private val CHUNK_DELIMITERS = setOf('，', '。', '！', '？', '；', ',', '.', '!', '?', ';', '\n')

        fun splitIntoNaturalChunks(text: String): List<String> {
            if (text.length <= 16) return listOf(text)
            val result = mutableListOf<String>()
            val current = StringBuilder()
            for (ch in text) {
                current.append(ch)
                if (ch in CHUNK_DELIMITERS || (current.length >= 16 && ch == ' ')) {
                    result.add(current.toString())
                    current.clear()
                }
            }
            if (current.isNotEmpty()) {
                result.add(current.toString())
            }
            return result
        }
    }
}
