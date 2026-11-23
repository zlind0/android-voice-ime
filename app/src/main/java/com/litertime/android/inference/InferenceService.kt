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
import android.os.RemoteCallbackList
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.litertime.android.R
import com.litertime.android.ipc.IInferenceCallback
import com.litertime.android.ipc.IInferenceService
import com.litertime.android.pipeline.GenerationEvent
import com.litertime.android.pipeline.LiteRtGemmaBackend
import com.litertime.android.pipeline.OnlineLlmBackend
import com.litertime.android.pipeline.SherpaAsrBackend
import com.litertime.android.pipeline.TextGenerationBackend
import com.litertime.android.pipeline.VoiceGenerationRequest
import com.litertime.android.pipeline.VoiceMode
import com.litertime.android.settings.ImeSettingsStore
import com.litertime.android.storage.ModelCatalog
import com.litertime.android.storage.ModelStorage
import com.litertime.android.ui.MainActivity
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

private const val TAG = "InferenceService"
private const val CHANNEL_ID = "model_runtime"
private const val NOTIFICATION_ID = 1001
private const val ACTION_UNLOAD_MODEL = "com.litertime.android.inference.ACTION_UNLOAD_MODEL"

/**
 * Isolated `:inference` Foreground Service hosting either Sherpa-ONNX Whisper/ASR OR LiteRT-LM
 * Gemma 4 (mutually exclusive) so weights remain resident across IME hide/show cycles until the
 * user explicitly taps "卸载模型" on the persistent notification or in Settings.
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

    @Volatile
    private var loadedEngineType: String = "NONE"

    @Volatile
    private var loadedModelId: String = ""

    @Volatile
    private var currentStatusText: String = "未加载模型"

    private val binder = object : IInferenceService.Stub() {
        override fun registerCallback(callback: IInferenceCallback?) {
            if (callback != null) {
                callbacks.register(callback)
                runCatching {
                    callback.onStatusChanged(currentStatusText, loadedEngineType, loadedModelId)
                }
            }
        }

        override fun unregisterCallback(callback: IInferenceCallback?) {
            if (callback != null) {
                callbacks.unregister(callback)
            }
        }

        override fun preloadModel(engineType: String?, modelId: String?) {
            val type = engineType ?: return
            val id = modelId ?: return
            serviceScope.launch {
                ensureExclusiveBackendLoaded(type, id)
            }
        }

        override fun unloadModel() {
            serviceScope.launch {
                unloadAllModels(stopService = true)
            }
        }

        override fun getLoadedStateJson(): String {
            return JSONObject().apply {
                put("status", currentStatusText)
                put("engineType", loadedEngineType)
                put("modelId", loadedModelId)
            }.toString()
        }

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
            val languageHint = runCatching {
                JSONObject(extraJson ?: "{}").optString("language", "")
            }.getOrDefault("")

            val job = serviceScope.launch {
                try {
                    if (type == "ONLINE_LLM") {
                        runStreamingBackend(
                            backend = onlineBackend,
                            request = VoiceGenerationRequest(
                                requestId = reqId,
                                mode = voiceMode,
                                modelId = id,
                                audioWavFile = audioFile,
                                screenshotFile = imageFile,
                                languageHint = languageHint,
                            ),
                        )
                        return@launch
                    }

                    val loaded = ensureExclusiveBackendLoaded(type, id)
                    if (!loaded) {
                        broadcastError(reqId, currentStatusText)
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
                        ),
                    )
                } finally {
                    activeRequestJobs.remove(reqId)
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
        callbacks.kill()
        serviceScope.cancel()
        super.onDestroy()
    }

    private suspend fun ensureExclusiveBackendLoaded(engineType: String, modelId: String): Boolean {
        return engineMutex.withLock {
            if (loadedEngineType == engineType && loadedModelId == modelId) {
                return@withLock true
            }

            // Mutually exclusive residency: unload previous engine before loading the requested one.
            if (loadedEngineType == "ASR" && engineType != "ASR") {
                asrBackend.unload()
                loadedEngineType = "NONE"
                loadedModelId = ""
            } else if (loadedEngineType == "LLM" && engineType != "LLM") {
                gemmaBackend.unload()
                loadedEngineType = "NONE"
                loadedModelId = ""
            }

            val displayTitle = when (engineType) {
                "ASR" -> ModelCatalog.findAsr(modelId)?.displayName ?: modelId
                "LLM" -> ModelCatalog.findLlm(modelId)?.displayName ?: modelId
                else -> modelId
            }

            updateStatusAndNotification("正在加载 $displayTitle...", "LOADING", modelId)
            promoteToForeground("正在加载 $displayTitle...")

            try {
                when (engineType) {
                    "ASR" -> asrBackend.ensureLoaded(modelId) { msg ->
                        updateStatusAndNotification(msg, "LOADING", modelId)
                    }
                    "LLM" -> gemmaBackend.ensureLoaded(modelId) { msg ->
                        updateStatusAndNotification(msg, "LOADING", modelId)
                    }
                    else -> throw IllegalArgumentException("未知引擎类型: $engineType")
                }
                loadedEngineType = engineType
                loadedModelId = modelId
                val readyText = "$displayTitle 已加载"
                updateStatusAndNotification(readyText, engineType, modelId)
                promoteToForeground(readyText)
                true
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to load $engineType:$modelId", t)
                loadedEngineType = "NONE"
                loadedModelId = ""
                val errText = t.message ?: "模型加载失败"
                updateStatusAndNotification(errText, "NONE", "")
                promoteToForeground("加载失败：$errText")
                false
            }
        }
    }

    private suspend fun runStreamingBackend(
        backend: TextGenerationBackend,
        request: VoiceGenerationRequest,
    ) {
        backend.generateStream(request).collect { event ->
            when (event) {
                is GenerationEvent.Status -> updateStatusOnly(event.message)
                is GenerationEvent.Token -> broadcastToken(request.requestId, event.delta)
                is GenerationEvent.Completed -> broadcastComplete(request.requestId)
                is GenerationEvent.Error -> broadcastError(request.requestId, event.message)
            }
        }
    }

    private suspend fun unloadAllModels(stopService: Boolean) {
        engineMutex.withLock {
            activeRequestJobs.values.forEach { it.cancel() }
            activeRequestJobs.clear()
            asrBackend.unload()
            gemmaBackend.unload()
            loadedEngineType = "NONE"
            loadedModelId = ""
            updateStatusAndNotification("模型已卸载", "NONE", "")
        }
        if (stopService) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun updateStatusOnly(status: String) {
        currentStatusText = status
        broadcastStatus(status, loadedEngineType, loadedModelId)
    }

    private fun updateStatusAndNotification(status: String, engineType: String, modelId: String) {
        currentStatusText = status
        broadcastStatus(status, engineType, modelId)
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

    private fun broadcastStatus(status: String, engineType: String, modelId: String) {
        synchronized(callbacks) {
            val count = callbacks.beginBroadcast()
            try {
                for (i in 0 until count) {
                    runCatching {
                        callbacks.getBroadcastItem(i).onStatusChanged(status, engineType, modelId)
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
