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
    val loadedEngineType: String = "NONE",
    val loadedModelId: String = "",
)

interface StreamListener {
    fun onStatusChanged(status: String, loadedEngineType: String, loadedModelId: String) = Unit
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
        override fun onStatusChanged(
            status: String?,
            loadedEngineType: String?,
            loadedModelId: String?,
        ) {
            val s = status ?: ""
            val eng = loadedEngineType ?: "NONE"
            val id = loadedModelId ?: ""
            _statusState.value = _statusState.value.copy(
                bound = true,
                statusText = s,
                loadedEngineType = eng,
                loadedModelId = id,
            )
            listeners.forEach { it.onStatusChanged(s, eng, id) }
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
                if (!stateJson.isNullOrBlank()) {
                    val obj = JSONObject(stateJson)
                    _statusState.value = InferenceStatusState(
                        bound = true,
                        statusText = obj.optString("status", "未加载模型"),
                        loadedEngineType = obj.optString("engineType", "NONE"),
                        loadedModelId = obj.optString("modelId", ""),
                    )
                } else {
                    _statusState.value = _statusState.value.copy(bound = true)
                }
            }.onFailure {
                Log.w(TAG, "Failed to register callback on connect", it)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            _statusState.value = _statusState.value.copy(bound = false)
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
        _statusState.value = _statusState.value.copy(bound = false)
    }

    fun addListener(listener: StreamListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: StreamListener) {
        listeners.remove(listener)
    }

    fun preloadModel(engineType: String, modelId: String) {
        val intent = Intent(appContext, InferenceService::class.java)
        appContext.startForegroundService(intent)
        if (service == null) bind()
        runCatching {
            service?.preloadModel(engineType, modelId)
        }.onFailure {
            Log.e(TAG, "preloadModel failed", it)
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
