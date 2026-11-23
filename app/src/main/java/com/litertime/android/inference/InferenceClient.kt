package com.litertime.android.inference

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.litertime.android.ipc.IInferenceCallback
import com.litertime.android.ipc.IInferenceService
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

private const val TAG = "InferenceClient"

data class InferenceStatusState(
    val bound: Boolean = false,
    val statusText: String = "未加载模型",
    val asrModelId: String = "",
    val llmModelId: String = "",
    val llmLoadedWithGpu: Boolean = false,
    /** pid of the `:inference` process, used by the IME to read real model memory (PSS). */
    val inferencePid: Int = 0,
) {
    val asrLoaded: Boolean get() = asrModelId.isNotBlank()
    val llmLoaded: Boolean get() = llmModelId.isNotBlank()

    val loadedSummary: String
        get() = when {
            asrLoaded && llmLoaded -> "ASR+LLM"
            asrLoaded -> "ASR"
            llmLoaded -> "LLM"
            else -> "NONE"
        }

    val llmBackendLabel: String get() = if (llmLoadedWithGpu) "GPU" else "CPU"

    companion object {
        fun fromJson(json: String?, bound: Boolean = true): InferenceStatusState {
            if (json.isNullOrBlank()) return InferenceStatusState(bound = bound)
            val obj = runCatching { JSONObject(json) }.getOrNull()
                ?: return InferenceStatusState(bound = bound)
            return InferenceStatusState(
                bound = bound,
                statusText = obj.optString("status", "未加载模型"),
                asrModelId = obj.optString("asr", ""),
                llmModelId = obj.optString("llm", ""),
                llmLoadedWithGpu = obj.optBoolean("llmGpu", false),
                inferencePid = obj.optInt("pid", 0),
            )
        }
    }
}

interface StreamListener {
    fun onStatusChanged(status: String) = Unit
    fun onToken(requestId: String, delta: String)
    fun onComplete(requestId: String)
    fun onError(requestId: String, errorMessage: String)
}

class InferenceClient(private val context: Context) {
    private val appContext = context.applicationContext
    private val listeners = CopyOnWriteArraySet<StreamListener>()

    private val _statusState = MutableStateFlow(InferenceStatusState())
    val statusState: StateFlow<InferenceStatusState> = _statusState.asStateFlow()

    @Volatile
    private var service: IInferenceService? = null

    @Volatile
    private var bindingRequested: Boolean = false

    private val binderCallback = object : IInferenceCallback.Stub() {
        override fun onStatusChanged(stateJson: String?) {
            val state = InferenceStatusState.fromJson(stateJson, bound = true)
            _statusState.value = state
            listeners.forEach { it.onStatusChanged(state.statusText) }
        }

        override fun onToken(requestId: String?, delta: String?) {
            val id = requestId ?: return
            val d = delta ?: return
            listeners.forEach { it.onToken(id, d) }
        }

        override fun onComplete(requestId: String?) {
            val id = requestId ?: return
            listeners.forEach { it.onComplete(id) }
        }

        override fun onError(requestId: String?, errorMessage: String?) {
            val id = requestId ?: return
            val msg = errorMessage ?: "未知推理错误"
            listeners.forEach { it.onError(id, msg) }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val remote = IInferenceService.Stub.asInterface(binder)
            service = remote
            runCatching {
                remote.registerCallback(binderCallback)
                val stateJson = remote.loadedStateJson
                _statusState.value = if (!stateJson.isNullOrBlank()) {
                    InferenceStatusState.fromJson(stateJson, bound = true)
                } else {
                    _statusState.value.copy(bound = true)
                }
            }.onFailure {
                Log.w(TAG, "Failed to register callback on connect", it)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            _statusState.value = InferenceStatusState(bound = false)
            if (bindingRequested) {
                bind()
            }
        }
    }

    fun bind() {
        bindingRequested = true
        val intent = Intent(appContext, InferenceService::class.java)
        runCatching {
            appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }.onFailure {
            Log.e(TAG, "Failed to bind InferenceService", it)
        }
    }

    fun unbind() {
        bindingRequested = false
        val remote = service
        service = null
        if (remote != null) {
            runCatching { remote.unregisterCallback(binderCallback) }
        }
        runCatching { appContext.unbindService(connection) }
        _statusState.value = InferenceStatusState(bound = false)
    }

    fun addListener(listener: StreamListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: StreamListener) {
        listeners.remove(listener)
    }

    fun preloadModel(engineType: String, modelId: String, forceGpu: Boolean = false) {
        val intent = Intent(appContext, InferenceService::class.java)
        appContext.startForegroundService(intent)
        if (service == null) bind()
        runCatching {
            service?.preloadModel(engineType, modelId, forceGpu)
        }.onFailure {
            Log.e(TAG, "preloadModel failed", it)
        }
    }

    /** Unloads a single engine ("ASR" or "LLM") without touching the other one. */
    fun unloadEngine(engineType: String) {
        runCatching {
            service?.unloadEngine(engineType)
        }.onFailure {
            Log.e(TAG, "unloadEngine failed", it)
        }
    }

    fun unloadModel() {
        runCatching {
            service?.unloadModel()
        }.onFailure {
            Log.e(TAG, "unloadModel failed", it)
        }
    }

    fun runRequest(
        requestId: String,
        backendType: String,
        mode: String,
        modelId: String,
        audioWavFile: File,
        screenshotFile: File?,
        extraJson: String = "{}",
    ): Boolean {
        val intent = Intent(appContext, InferenceService::class.java)
        appContext.startForegroundService(intent)
        val remote = service
        if (remote == null) {
            bind()
            return false
        }
        return runCatching {
            remote.runRequest(
                requestId,
                backendType,
                mode,
                modelId,
                audioWavFile.absolutePath,
                screenshotFile?.absolutePath ?: "",
                extraJson,
            )
            true
        }.getOrElse {
            Log.e(TAG, "runRequest IPC failed", it)
            false
        }
    }

    fun cancelRequest(requestId: String) {
        runCatching {
            service?.cancelRequest(requestId)
        }
    }
}
