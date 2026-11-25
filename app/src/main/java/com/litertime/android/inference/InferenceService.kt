package com.litertime.android.inference

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.os.RemoteCallbackList
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.litertime.android.R
import com.litertime.android.debug.AiRequestLogStore
import com.litertime.android.debug.RequestLogRecorder
import com.litertime.android.ipc.IInferenceCallback
import com.litertime.android.ipc.IInferenceService
import com.litertime.android.pipeline.GenerationEvent
import com.litertime.android.pipeline.LiteRtGemmaBackend
import com.litertime.android.pipeline.OnlineLlmBackend
import com.litertime.android.pipeline.PromptTemplates
import com.litertime.android.pipeline.SherpaAsrBackend
import com.litertime.android.pipeline.TextGenerationBackend
import com.litertime.android.pipeline.VoiceGenerationRequest
import com.litertime.android.pipeline.VoiceMode
import com.litertime.android.settings.ImeSettings
import com.litertime.android.settings.ImeSettingsStore
import com.litertime.android.storage.ModelCatalog
import com.litertime.android.storage.ModelStorage
import com.litertime.android.ui.MainActivity
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

private const val TAG = "InferenceService"
private const val CHANNEL_ID = "model_runtime"
private const val NOTIFICATION_ID = 1001
private const val ACTION_UNLOAD_MODEL = "com.litertime.android.inference.ACTION_UNLOAD_MODEL"

/**
 * Isolated `:inference` Foreground Service hosting Sherpa-ONNX Whisper/ASR and LiteRT-LM Gemma 4.
 *
 * Both engines may stay resident at the same time (no mutual exclusion); weights remain loaded
 * across IME hide/show cycles until the user explicitly unloads one via the mic drag-down menu,
 * the persistent notification, or the settings screen.
 */
class InferenceService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val engineMutex = Mutex()
    private val callbacks = RemoteCallbackList<IInferenceCallback>()
    private val activeRequestJobs = ConcurrentHashMap<String, Job>()

    private lateinit var modelStorage: ModelStorage
    private lateinit var settingsStore: ImeSettingsStore
    private lateinit var asrBackend: SherpaAsrBackend
    private lateinit var gemmaBackend: LiteRtGemmaBackend
    private lateinit var onlineBackend: OnlineLlmBackend
    private lateinit var requestLogStore: AiRequestLogStore

    @Volatile
    private var asrModelId: String = ""

    @Volatile
    private var llmModelId: String = ""

    @Volatile
    private var llmLoadedWithGpu: Boolean = false

    /** True after an online request failed and the service fell back to the local model. */
    @Volatile
    private var onlineFallbackActive: Boolean = false

    @Volatile
    private var currentStatusText: String = "未加载模型"

    private val binder = object : IInferenceService.Stub() {
        override fun registerCallback(callback: IInferenceCallback?) {
            if (callback != null) {
                callbacks.register(callback)
                runCatching { callback.onStatusChanged(buildStateJson()) }
            }
        }

        override fun unregisterCallback(callback: IInferenceCallback?) {
            if (callback != null) {
                callbacks.unregister(callback)
            }
        }

        override fun preloadModel(engineType: String?, modelId: String?, forceGpu: Boolean) {
            val type = engineType ?: return
            val id = modelId ?: return
            serviceScope.launch {
                ensureBackendLoaded(type, id, forceGpu)
            }
        }

        override fun unloadEngine(engineType: String?) {
            val type = engineType ?: return
            serviceScope.launch {
                unloadEngineInternal(type)
            }
        }

        override fun unloadModel() {
            serviceScope.launch {
                unloadAllModels(stopService = true)
            }
        }

        override fun getLoadedStateJson(): String = buildStateJson()

        override fun runRequest(
            requestId: String?,
            backendType: String?,
            mode: String?,
            modelId: String?,
            audioFilePath: String?,
            imageFilePath: String?,
            extraJson: String?,
        ) {
            val reqId = requestId ?: return
            val type = backendType ?: "ASR"
            val voiceMode = runCatching { VoiceMode.valueOf(mode ?: "WHISPER_RAW") }
                .getOrDefault(VoiceMode.WHISPER_RAW)
            val id = modelId ?: ""
            val audioFile = File(audioFilePath ?: "")
            val imageFile = imageFilePath?.takeIf { it.isNotBlank() }?.let { File(it) }
            val extra = runCatching { JSONObject(extraJson ?: "{}") }.getOrDefault(JSONObject())
            val languageHint = extra.optString("language", "")
            val systemPromptOverride = if (extra.has("systemPrompt")) {
                extra.optString("systemPrompt", "")
            } else {
                null
            }
            val userPromptOverride = if (extra.has("userPrompt")) {
                extra.optString("userPrompt", "")
            } else {
                null
            }
            // Replaying a cascade request carries the original ASR transcript so the replay feeds
            // text (not audio) into the model again, exactly like the recorded run.
            val asrTranscriptOverride = if (extra.has("asrTranscript")) {
                extra.optString("asrTranscript", "").takeIf { it.isNotBlank() }
            } else {
                null
            }
            val origin = extra.optString("origin", "ime")
            val replayOf = extra.optString("replayOf", "")

            val settings = settingsStore.getSettings()
            val modelDisplayName = when (type) {
                "ASR" -> ModelCatalog.findAsr(id)?.displayName ?: id
                "LLM" -> ModelCatalog.findLlm(id)?.displayName ?: id
                else -> settings.onlineConfigFor(voiceMode).modelName.ifBlank { id }
            }
            val job = serviceScope.launch {
                // Voice cascade: Whisper/ASR transcribes first, the LLM only ever sees text.
                // Screenshot instruction mode always transcribes: its user prompt is the
                // recognized command text itself, so the model never receives raw audio there.
                val instructionNeedsTranscript =
                    voiceMode == VoiceMode.GEMMA_SCREENSHOT_INSTRUCTION
                val transcript = if (type != "ASR" && (
                    type == "ONLINE_LLM" ||
                        (voiceMode != VoiceMode.WHISPER_RAW &&
                            (settings.voiceCascadeEnabled || instructionNeedsTranscript))
                    )
                ) {
                    asrTranscriptOverride
                        ?: transcribeForCascade(reqId, audioFile, languageHint, settings)
                } else {
                    null
                }
                val cascade = transcript != null
                val resolvedSystemPrompt = if (type == "ASR") {
                    ""
                } else {
                    PromptTemplates.resolveSystemPrompt(
                        override = systemPromptOverride,
                        saved = settings.systemPromptOverride,
                        cascade = cascade,
                        mode = voiceMode,
                        modeSaved = settings.systemPromptScreenshotInstruction,
                    )
                }
                // The instruction mode feeds the ASR transcript as the user prompt verbatim.
                // A playground override that differs from the transcript is still honoured.
                val transcriptAsUserPrompt =
                    voiceMode == VoiceMode.GEMMA_SCREENSHOT_INSTRUCTION &&
                        transcript != null &&
                        (userPromptOverride == null || userPromptOverride == transcript)
                val resolvedUserPrompt = when {
                    type == "ASR" -> ""
                    transcriptAsUserPrompt -> transcript.orEmpty()
                    else -> PromptTemplates.resolveUserPrompt(
                        mode = voiceMode,
                        override = userPromptOverride,
                        saved = settings.userPromptOverride(voiceMode),
                        cascade = cascade,
                    )
                }
                val requestSystemPrompt = resolvedSystemPrompt.takeIf { type != "ASR" }
                val requestUserPrompt = resolvedUserPrompt.takeIf { type != "ASR" }
                // Copying WAV/screenshot can be a few MB, so keep it off the binder thread.
                val recorder: RequestLogRecorder? = if (settings.debugRequestLoggingEnabled) {
                    requestLogStore.recordRequest(
                        requestId = reqId,
                        backendType = type,
                        mode = voiceMode.name,
                        modelId = id,
                        modelDisplayName = modelDisplayName,
                        systemPrompt = resolvedSystemPrompt,
                        userPrompt = resolvedUserPrompt,
                        languageHint = languageHint,
                        origin = origin,
                        replayOf = replayOf,
                        audioFile = audioFile,
                        screenshotFile = imageFile,
                        transcript = transcript,
                    )
                } else {
                    null
                }
                try {
                    if (type == "ONLINE_LLM") {
                        runOnlineRequest(
                            request = VoiceGenerationRequest(
                                requestId = reqId,
                                mode = voiceMode,
                                modelId = id,
                                audioWavFile = audioFile,
                                screenshotFile = imageFile,
                                languageHint = languageHint,
                                asrTranscript = transcript,
                                userPromptIsTranscript = transcriptAsUserPrompt,
                                systemPrompt = requestSystemPrompt,
                                userPrompt = requestUserPrompt,
                            ),
                            recorder = recorder,
                        )
                        return@launch
                    }

                    val loaded = ensureBackendLoaded(type, id)
                    if (!loaded) {
                        broadcastError(reqId, currentStatusText)
                        recorder?.finish("", "error", currentStatusText)
                        return@launch
                    }

                    val backend: TextGenerationBackend = if (type == "ASR") asrBackend else gemmaBackend
                    runStreamingBackend(
                        backend = backend,
                        request = VoiceGenerationRequest(
                            requestId = reqId,
                            mode = voiceMode,
                            modelId = id,
                            audioWavFile = audioFile,
                            screenshotFile = imageFile,
                            languageHint = languageHint,
                            asrTranscript = transcript,
                            userPromptIsTranscript = transcriptAsUserPrompt,
                            systemPrompt = requestSystemPrompt,
                            userPrompt = requestUserPrompt,
                        ),
                        recorder = recorder,
                    )
                } finally {
                    activeRequestJobs.remove(reqId)
                    recorder?.finish("", "cancelled", "")
                    runCatching { if (audioFile.exists()) audioFile.delete() }
                    runCatching { if (imageFile != null && imageFile.exists()) imageFile.delete() }
                }
            }
            activeRequestJobs[reqId] = job
        }

        override fun cancelRequest(requestId: String?) {
            val reqId = requestId ?: return
            activeRequestJobs.remove(reqId)?.cancel()
            asrBackend.cancel(reqId)
            gemmaBackend.cancel(reqId)
            onlineBackend.cancel(reqId)
        }
    }

    override fun onCreate() {
        super.onCreate()
        modelStorage = ModelStorage(this)
        settingsStore = ImeSettingsStore(this)
        requestLogStore = AiRequestLogStore(filesDir, cacheDir)
        asrBackend = SherpaAsrBackend(modelStorage, settingsStore)
        gemmaBackend = LiteRtGemmaBackend(this, modelStorage, settingsStore)
        onlineBackend = OnlineLlmBackend(settingsStore)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_UNLOAD_MODEL) {
            serviceScope.launch {
                unloadAllModels(stopService = true)
            }
            return START_NOT_STICKY
        }
        promoteToForeground(currentStatusText)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        activeRequestJobs.values.forEach { it.cancel() }
        activeRequestJobs.clear()
        asrBackend.unload()
        gemmaBackend.unload()
        onlineBackend.unload()
        callbacks.kill()
        serviceScope.cancel()
        super.onDestroy()
    }

    /**
     * Runs the Whisper/ASR stage of the voice cascade and returns the transcript without
     * broadcasting any tokens to the IME. Returns null when ASR is unavailable or the audio could
     * not be recognized, so the caller can fall back to feeding audio straight into Gemma.
     */
    private suspend fun transcribeForCascade(
        requestId: String,
        audioFile: File,
        languageHint: String,
        settings: ImeSettings,
    ): String? {
        val spec = ModelCatalog.findAsr(settings.activeAsrModelId)
            ?.takeIf { modelStorage.isAsrInstalled(it) }
            ?: modelStorage.findFirstInstalledAsrId()?.let { ModelCatalog.findAsr(it) }
        if (spec == null) {
            updateStatus("语音级联：未安装 Whisper/ASR 模型，回退本地模型直接识别")
            return null
        }
        if (!ensureBackendLoaded("ASR", spec.id)) {
            updateStatus("语音级联：ASR 模型加载失败，回退本地模型直接识别")
            return null
        }

        val builder = StringBuilder()
        var failure: String? = null
        try {
            asrBackend.generateStream(
                VoiceGenerationRequest(
                    requestId = "$requestId-asr",
                    mode = VoiceMode.WHISPER_RAW,
                    modelId = spec.id,
                    audioWavFile = audioFile,
                    languageHint = languageHint,
                ),
            ).collect { event ->
                when (event) {
                    is GenerationEvent.Status -> updateStatus("语音级联 · ${event.message}")
                    is GenerationEvent.Token -> builder.append(event.delta)
                    is GenerationEvent.Completed -> Unit
                    is GenerationEvent.Error -> failure = event.message
                    is GenerationEvent.FallbackToLocal -> failure = event.reason
                }
            }
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            failure = t.message ?: "ASR 识别异常"
        }

        val transcript = builder.toString().trim()
        return when {
            failure != null -> {
                Log.w(TAG, "Cascade ASR failed, falling back to Gemma: $failure")
                updateStatus("语音级联：ASR 识别失败，回退本地模型直接识别")
                null
            }
            transcript.isEmpty() -> {
                updateStatus("语音级联：未识别到语音内容，回退本地模型直接识别")
                null
            }
            else -> transcript
        }
    }

    private suspend fun ensureBackendLoaded(
        engineType: String,
        modelId: String,
        forceGpu: Boolean = false,
    ): Boolean {
        return engineMutex.withLock {
            if (engineType != "ASR" && engineType != "LLM") {
                Log.e(TAG, "Unknown engine type: $engineType")
                updateStatus("未知引擎类型: $engineType")
                return@withLock false
            }

            // Already resident? ASR only needs the same model; the LLM additionally has to match
            // the requested backend when the caller explicitly asks for GPU.
            when (engineType) {
                "ASR" -> if (asrModelId == modelId) return@withLock true
                "LLM" -> if (llmModelId == modelId && (!forceGpu || llmLoadedWithGpu)) return@withLock true
            }

            val displayTitle = when (engineType) {
                "ASR" -> ModelCatalog.findAsr(modelId)?.displayName ?: modelId
                else -> ModelCatalog.findLlm(modelId)?.displayName ?: modelId
            }

            updateStatus("正在加载 $displayTitle...")
            promoteToForeground("正在加载 $displayTitle...")

            try {
                if (forceGpu) {
                    // An explicit "GPU 加载/重载" gesture wins over both the saved toggle and a
                    // stale crash marker; the marker is rewritten by the backend before loading,
                    // so a real GPU crash still downgrades the next attempt.
                    settingsStore.update { it.copy(useGpuForLlm = true) }
                    modelStorage.clearGpuCrashMarker()
                }
                when (engineType) {
                    "ASR" -> {
                        asrModelId = ""
                        asrBackend.ensureLoaded(modelId) { msg ->
                            updateStatus(msg)
                        }
                        asrModelId = modelId
                    }
                    "LLM" -> {
                        llmModelId = ""
                        llmLoadedWithGpu = false
                        gemmaBackend.ensureLoaded(modelId, forceGpu) { msg ->
                            updateStatus(msg)
                        }
                        llmModelId = modelId
                        llmLoadedWithGpu = gemmaBackend.loadedWithGpu
                    }
                }
                val readyText = if (engineType == "LLM") {
                    "$displayTitle 已加载 (${if (llmLoadedWithGpu) "GPU" else "CPU"})"
                } else {
                    "$displayTitle 已加载"
                }
                updateStatus(readyText)
                promoteToForeground(readyText)
                true
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to load $engineType:$modelId", t)
                if (engineType == "ASR") {
                    asrModelId = ""
                } else {
                    llmModelId = ""
                    llmLoadedWithGpu = false
                }
                val errText = t.message ?: "模型加载失败"
                updateStatus(errText)
                promoteToForeground("加载失败：$errText")
                false
            }
        }
    }

    private suspend fun unloadEngineInternal(engineType: String) {
        engineMutex.withLock {
            val displayTitle = when (engineType) {
                "ASR" -> asrModelId.ifBlank { "Whisper" }
                    .let { ModelCatalog.findAsr(it)?.displayName ?: it }
                "LLM" -> llmModelId.ifBlank { "Gemma 4" }
                    .let { ModelCatalog.findLlm(it)?.displayName ?: it }
                else -> return@withLock
            }
            when (engineType) {
                "ASR" -> {
                    asrBackend.unload()
                    asrModelId = ""
                }
                "LLM" -> {
                    gemmaBackend.unload()
                    llmModelId = ""
                    llmLoadedWithGpu = false
                    onlineFallbackActive = false
                }
            }
            val text = if (asrModelId.isBlank() && llmModelId.isBlank()) {
                "$displayTitle 已卸载 · 当前未驻留任何模型"
            } else {
                "$displayTitle 已卸载 · ${residentSummary()}"
            }
            updateStatus(text)
            promoteToForeground(text)
        }
    }

    private fun residentSummary(): String {
        val parts = mutableListOf<String>()
        if (asrModelId.isNotBlank()) {
            parts += ModelCatalog.findAsr(asrModelId)?.displayName ?: asrModelId
        }
        if (llmModelId.isNotBlank()) {
            val name = ModelCatalog.findLlm(llmModelId)?.displayName ?: llmModelId
            parts += "$name (${if (llmLoadedWithGpu) "GPU" else "CPU"})"
        }
        return if (parts.isEmpty()) "当前未驻留任何模型" else "当前驻留 " + parts.joinToString(" + ")
    }

    private fun buildStateJson(): String = JSONObject().apply {
        put("status", currentStatusText)
        put("asr", asrModelId)
        put("llm", llmModelId)
        put("llmGpu", llmLoadedWithGpu)
        put("onlineFallback", onlineFallbackActive)
        put("pid", Process.myPid())
    }.toString()

    /**
     * Streams the online model, and on a pre-token timeout/offline/5xx failure transparently loads
     * the local Gemma model and repeats the request there.
     */
    private suspend fun runOnlineRequest(
        request: VoiceGenerationRequest,
        recorder: RequestLogRecorder?,
    ) {
        setOnlineFallbackActive(false)
        val output = StringBuilder()
        var terminal = false
        var fallbackReason: String? = null
        try {
            onlineBackend.generateStream(request).collect { event ->
                when (event) {
                    is GenerationEvent.Status -> updateStatus(event.message)
                    is GenerationEvent.Token -> {
                        output.append(event.delta)
                        broadcastToken(request.requestId, event.delta)
                    }
                    is GenerationEvent.Completed -> {
                        terminal = true
                        broadcastComplete(request.requestId)
                        recorder?.finish(output.toString(), "completed", "")
                    }
                    is GenerationEvent.Error -> {
                        terminal = true
                        broadcastError(request.requestId, event.message)
                        recorder?.finish(output.toString(), "error", event.message)
                    }
                    is GenerationEvent.FallbackToLocal -> {
                        if (output.isEmpty()) {
                            fallbackReason = event.reason
                        } else {
                            terminal = true
                            val message = "${event.reason}，在线输出已中止"
                            broadcastError(request.requestId, message)
                            recorder?.finish(output.toString(), "error", message)
                        }
                    }
                }
            }
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            if (!currentCoroutineContext().isActive) {
                throw CancellationException("在线请求已取消")
            }
            if (output.isEmpty()) {
                fallbackReason = t.message ?: "在线请求失败"
            } else {
                terminal = true
                val message = t.message ?: "在线推理异常"
                broadcastError(request.requestId, message)
                recorder?.finish(output.toString(), "error", message)
            }
        }

        if (terminal) return

        val reason = fallbackReason ?: "在线大模型没有返回内容"
        setOnlineFallbackActive(true)
        updateStatus("$reason，正在回落到本地模型...")
        val localModelId = resolveLocalLlmModelId()
        val loaded = ensureBackendLoaded("LLM", localModelId)
        if (!loaded || llmModelId.isBlank()) {
            val message = "$reason，且本地模型不可用：$currentStatusText"
            broadcastError(request.requestId, message)
            recorder?.finish(output.toString(), "error", message)
            return
        }
        runStreamingBackend(
            backend = gemmaBackend,
            request = request.copy(modelId = llmModelId),
            recorder = recorder,
        )
    }

    private fun resolveLocalLlmModelId(): String {
        val configured = ModelCatalog.findLlm(settingsStore.getSettings().activeLlmModelId)
        return if (configured != null && modelStorage.isLlmInstalled(configured)) {
            configured.id
        } else {
            modelStorage.findFirstInstalledLlmId()
                ?: settingsStore.getSettings().activeLlmModelId
        }
    }

    private fun setOnlineFallbackActive(active: Boolean) {
        if (onlineFallbackActive == active) return
        onlineFallbackActive = active
        broadcastState()
    }

    private suspend fun runStreamingBackend(
        backend: TextGenerationBackend,
        request: VoiceGenerationRequest,
        recorder: RequestLogRecorder? = null,
    ) {
        val output = StringBuilder()
        var status = "cancelled"
        var errorMessage = ""
        try {
            backend.generateStream(request).collect { event ->
                when (event) {
                    is GenerationEvent.Status -> updateStatus(event.message)
                    is GenerationEvent.Token -> {
                        output.append(event.delta)
                        broadcastToken(request.requestId, event.delta)
                    }
                    is GenerationEvent.Completed -> {
                        status = "completed"
                        broadcastComplete(request.requestId)
                    }
                    is GenerationEvent.Error -> {
                        status = "error"
                        errorMessage = event.message
                        broadcastError(request.requestId, errorMessage)
                    }
                    is GenerationEvent.FallbackToLocal -> {
                        status = "error"
                        errorMessage = event.reason
                        broadcastError(request.requestId, errorMessage)
                    }
                }
            }
        } catch (t: CancellationException) {
            status = "cancelled"
            throw t
        } catch (t: Throwable) {
            status = "error"
            errorMessage = t.message ?: "推理异常"
            broadcastError(request.requestId, errorMessage)
        } finally {
            recorder?.finish(output.toString(), status, errorMessage)
        }
    }

    private suspend fun unloadAllModels(stopService: Boolean) {
        engineMutex.withLock {
            activeRequestJobs.values.forEach { it.cancel() }
            activeRequestJobs.clear()
            asrBackend.unload()
            gemmaBackend.unload()
            asrModelId = ""
            llmModelId = ""
            llmLoadedWithGpu = false
            onlineFallbackActive = false
            updateStatus("模型已卸载")
        }
        if (stopService) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun updateStatus(status: String) {
        currentStatusText = status
        broadcastState()
    }

    private fun promoteToForeground(contentText: String) {
        val notification = buildNotification(contentText)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, 0)
        }
    }

    private fun buildNotification(contentText: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val unloadIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, InferenceService::class.java).apply { action = ACTION_UNLOAD_MODEL },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("LiteRT 语音推理引擎")
            .setContentText(contentText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .addAction(0, "卸载模型", unloadIntent)
            .build()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "端侧模型运行状态",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "显示已加载的 Whisper 或 Gemma 4 模型并提供一键卸载"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun broadcastState() {
        val stateJson = buildStateJson()
        synchronized(callbacks) {
            val count = callbacks.beginBroadcast()
            try {
                for (i in 0 until count) {
                    runCatching {
                        callbacks.getBroadcastItem(i).onStatusChanged(stateJson)
                    }
                }
            } finally {
                callbacks.finishBroadcast()
            }
        }
    }

    private fun broadcastToken(requestId: String, delta: String) {
        synchronized(callbacks) {
            val count = callbacks.beginBroadcast()
            try {
                for (i in 0 until count) {
                    runCatching {
                        callbacks.getBroadcastItem(i).onToken(requestId, delta)
                    }
                }
            } finally {
                callbacks.finishBroadcast()
            }
        }
    }

    private fun broadcastComplete(requestId: String) {
        synchronized(callbacks) {
            val count = callbacks.beginBroadcast()
            try {
                for (i in 0 until count) {
                    runCatching {
                        callbacks.getBroadcastItem(i).onComplete(requestId)
                    }
                }
            } finally {
                callbacks.finishBroadcast()
            }
        }
    }

    private fun broadcastError(requestId: String, errorMessage: String) {
        synchronized(callbacks) {
            val count = callbacks.beginBroadcast()
            try {
                for (i in 0 until count) {
                    runCatching {
                        callbacks.getBroadcastItem(i).onError(requestId, errorMessage)
                    }
                }
            } finally {
                callbacks.finishBroadcast()
            }
        }
    }
}
