package com.litertime.android.pipeline

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Process
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import com.litertime.android.BuildConfig
import com.litertime.android.settings.ImeSettingsStore
import com.litertime.android.storage.ModelCatalog
import com.litertime.android.storage.ModelStorage
import com.litertime.android.vision.ImageEncoder
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val TAG = "LiteRtGemmaBackend"
private const val THOUGHT_CHANNEL = "thought"

class LiteRtGemmaBackend(
    private val context: Context,
    private val modelStorage: ModelStorage,
    private val settingsStore: ImeSettingsStore,
) : TextGenerationBackend {
    override val backendId: String = "litert_gemma"
    override val displayName: String = "LiteRT-LM (Gemma 4)"

    private val loadMutex = Mutex()
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeConversations = ConcurrentHashMap<String, Conversation>()

    @Volatile
    private var engine: Engine? = null

    @Volatile
    private var loadedModelId: String? = null

    @Volatile
    var loadedWithGpu: Boolean = false
        private set

    override suspend fun ensureLoaded(modelId: String, onStatus: (String) -> Unit) {
        ensureLoaded(modelId = modelId, forceGpu = false, onStatus = onStatus)
    }

    /** [forceGpu] bypasses the saved GPU toggle and any stale GPU crash marker. */
    suspend fun ensureLoaded(
        modelId: String,
        forceGpu: Boolean,
        onStatus: (String) -> Unit,
    ) {
        loadMutex.withLock {
            val spec = ModelCatalog.findLlm(modelId)
                ?: throw IllegalArgumentException("未知的 Gemma 模型: $modelId")
            val modelFile = modelStorage.llmModelFile(spec)
                ?: throw IllegalStateException("模型 ${spec.displayName} 尚未下载或路径不可访问")

            if (engine != null && loadedModelId == spec.id && (!forceGpu || loadedWithGpu)) {
                return
            }

            unloadInternal()

            val settings = settingsStore.getSettings()
            val gpuCrashDetected = modelStorage.hasGpuCrashMarker(BuildConfig.VERSION_CODE)
            val tryGpu = forceGpu || (settings.useGpuForLlm && !gpuCrashDetected)
            val cacheDir = modelStorage.litertCacheDir().absolutePath

            Log.i(TAG, "Loading ${spec.id}: tryGpu=$tryGpu, gpuCrashDetected=$gpuCrashDetected, cacheDir=$cacheDir")
            onStatus("正在加载 ${spec.displayName} (${if (tryGpu) "GPU" else "CPU"})...")

            if (tryGpu) {
                modelStorage.writeGpuCrashMarker(Process.myPid(), BuildConfig.VERSION_CODE)
            }

            val loadedEngine = withContext(Dispatchers.IO) {
                try {
                    initEngine(modelFile, cacheDir, useGpu = tryGpu)
                } catch (e: Throwable) {
                    if (tryGpu) {
                        Log.e(TAG, "GPU initialization failed, falling back to CPU: ${e.message}", e)
                        onStatus("GPU 初始化失败，正在回退 CPU 加载 ${spec.displayName}...")
                        initEngine(modelFile, cacheDir, useGpu = false)
                    } else {
                        throw e
                    }
                }
            }

            engine = loadedEngine
            loadedModelId = spec.id
            loadedWithGpu = loadedEngine.engineConfig.backend is Backend.GPU
            Log.i(TAG, "Engine initialized: model=${spec.id}, loadedWithGpu=$loadedWithGpu")
            if (!loadedWithGpu && tryGpu) {
                modelStorage.clearGpuCrashMarker()
            }
            onStatus("${spec.displayName} 已就绪 (${if (loadedWithGpu) "GPU" else "CPU"})")
        }
    }

    private fun initEngine(modelFile: File, cacheDir: String, useGpu: Boolean): Engine {
        val mainBackend = if (useGpu) Backend.GPU() else Backend.CPU()
        val visionBackend = if (useGpu) Backend.GPU() else Backend.CPU()
        val config = EngineConfig(
            modelPath = modelFile.absolutePath,
            backend = mainBackend,
            visionBackend = visionBackend,
            audioBackend = Backend.CPU(), // Gemma audio encoder runs on CPU
            maxNumTokens = 4096,
            cacheDir = cacheDir,
        )
        val newEngine = Engine(config)
        try {
            newEngine.initialize()
            return newEngine
        } catch (t: Throwable) {
            runCatching { newEngine.close() }
            throw t
        }
    }

    override fun generateStream(request: VoiceGenerationRequest): Flow<GenerationEvent> = callbackFlow {
        try {
            ensureLoaded(request.modelId) { status ->
                trySend(GenerationEvent.Status(status))
            }
        } catch (t: Throwable) {
            trySend(GenerationEvent.Error(t.message ?: "加载 Gemma 4 模型失败"))
            close()
            return@callbackFlow
        }

        val currentEngine = engine
        if (currentEngine == null) {
            trySend(GenerationEvent.Error("Gemma 4 引擎未就绪"))
            close()
            return@callbackFlow
        }

        val conversation = try {
            withContext(Dispatchers.IO) {
                currentEngine.createConversation(
                    ConversationConfig(
                        systemInstruction = Contents.of(
                            Content.Text(
                                PromptTemplates.resolveSystemPrompt(request.systemPrompt),
                            ),
                        ),
                        samplerConfig = SamplerConfig(
                            topK = 40,
                            topP = 0.95,
                            temperature = 0.35,
                        ),
                    ),
                )
            }
        } catch (t: Throwable) {
            trySend(GenerationEvent.Error("创建会话失败: ${t.message}"))
            close()
            return@callbackFlow
        }

        activeConversations[request.requestId] = conversation

        fun closeConversationAsync() {
            activeConversations.remove(request.requestId)
            // Must close outside LiteRT native callback thread to avoid self-join deadlock.
            cleanupScope.launch {
                runCatching { conversation.close() }
            }
        }

        try {
            val contents = withContext(Dispatchers.IO) {
                val list = mutableListOf<Content>()

                // 1) Mode instruction first.
                list.add(Content.Text(PromptTemplates.resolveUserPrompt(request.mode, request.userPrompt)))

                // 2) Optional screenshot: label + image.
                val shotFile = request.screenshotFile
                if (shotFile != null && shotFile.isFile) {
                    val bitmap = BitmapFactory.decodeFile(shotFile.absolutePath)
                    if (bitmap != null) {
                        try {
                            val scaled = ImageEncoder.downscaleBitmap(bitmap, maxEdge = 896)
                            try {
                                list.add(Content.Text("[屏幕内容]:"))
                                list.add(Content.ImageBytes(ImageEncoder.encodeTga(scaled)))
                            } finally {
                                if (scaled !== bitmap) scaled.recycle()
                            }
                        } finally {
                            bitmap.recycle()
                        }
                    }
                }

                // 3) Audio always last: label + audio.
                list.add(Content.Text("\n[语音]:"))
                list.add(Content.AudioBytes(request.audioWavFile.readBytes()))

                Contents.of(list)
            }

            trySend(GenerationEvent.Status("Gemma 4 正在理解并生成..."))
            conversation.sendMessageAsync(
                contents,
                object : MessageCallback {
                    override fun onMessage(message: Message) {
                        val thinking = message.channels[THOUGHT_CHANNEL]
                        val text = message.toString()
                        if (text.isNotEmpty() && text != thinking) {
                            trySend(GenerationEvent.Token(text))
                        }
                    }

                    override fun onDone() {
                        if (loadedWithGpu) {
                            modelStorage.clearGpuCrashMarker()
                        }
                        closeConversationAsync()
                        trySend(GenerationEvent.Completed)
                        close()
                    }

                    override fun onError(throwable: Throwable) {
                        closeConversationAsync()
                        if (throwable is CancellationException) {
                            trySend(GenerationEvent.Completed)
                        } else {
                            Log.e(TAG, "Gemma inference error", throwable)
                            trySend(GenerationEvent.Error(throwable.message ?: "Gemma 4 推理出错"))
                        }
                        close()
                    }
                },
                extraContext = mapOf("enable_thinking" to false),
                thinkingConfig = ThinkingConfig(enableThinking = false),
            )
        } catch (t: Throwable) {
            closeConversationAsync()
            trySend(GenerationEvent.Error(t.message ?: "发送推理请求失败"))
            close()
        }

        awaitClose {
            val pending = activeConversations.remove(request.requestId)
            if (pending != null) {
                cleanupScope.launch {
                    runCatching { pending.cancelProcess() }
                    runCatching { pending.close() }
                }
            }
        }
    }

    override fun cancel(requestId: String) {
        val conv = activeConversations[requestId] ?: return
        runCatching { conv.cancelProcess() }
    }

    override fun unload() {
        unloadInternal()
    }

    private fun unloadInternal() {
        val snapshot = activeConversations.values.toList()
        activeConversations.clear()
        snapshot.forEach { conv ->
            runCatching { conv.cancelProcess() }
            runCatching { conv.close() }
        }
        runCatching { engine?.close() }
        engine = null
        loadedModelId = null
        loadedWithGpu = false
    }
}
