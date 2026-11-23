package com.litertime.android.ime

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.litertime.android.LiteRtImeApp
import com.litertime.android.accessibility.ScreenCaptureAccessibilityService
import com.litertime.android.audio.AudioCaptureManager
import com.litertime.android.audio.WavEncoder
import com.litertime.android.inference.InferenceClient
import com.litertime.android.inference.StreamListener
import com.litertime.android.pipeline.VoiceMode
import com.litertime.android.settings.ImeSettingsStore
import com.litertime.android.settings.LlmProviderType
import com.litertime.android.storage.ModelCatalog
import com.litertime.android.ui.MainActivity
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private const val TAG = "LiteRtImeService"

class LiteRtImeService : InputMethodService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioCapture = AudioCaptureManager()
    private val commitGuard = StreamingCommitGuard()
    private val trailingPeriodStripper = TrailingPeriodStripper()

    private lateinit var inferenceClient: InferenceClient
    private lateinit var overlayView: VoiceImeOverlayView
    private var statusCollectJob: Job? = null

    private val streamListener = object : StreamListener {
        override fun onToken(requestId: String, delta: String) {
            mainHandler.post {
                if (!commitGuard.shouldCommit(requestId)) return@post
                val text = if (settingsStore().getSettings().stripTrailingPeriod) {
                    trailingPeriodStripper.consume(delta)
                } else {
                    delta
                }
                if (text.isNotEmpty()) {
                    currentInputConnection?.commitText(text, 1)
                }
            }
        }

        override fun onComplete(requestId: String) {
            mainHandler.post {
                if (commitGuard.activeId() == requestId) {
                    if (commitGuard.shouldCommit(requestId)) {
                        val tail = if (settingsStore().getSettings().stripTrailingPeriod) {
                            trailingPeriodStripper.flush()
                        } else {
                            ""
                        }
                        if (tail.isNotEmpty()) {
                            currentInputConnection?.commitText(tail, 1)
                        }
                    }
                    trailingPeriodStripper.reset()
                    commitGuard.complete(requestId)
                    overlayView.updateStatusBanner("", cancellable = false)
                }
            }
        }

        override fun onError(requestId: String, errorMessage: String) {
            mainHandler.post {
                if (commitGuard.activeId() == requestId) {
                    trailingPeriodStripper.reset()
                    commitGuard.complete(requestId)
                    overlayView.updateStatusBanner("", cancellable = false)
                    Toast.makeText(this@LiteRtImeService, errorMessage, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        inferenceClient = InferenceClient(this)
        inferenceClient.addListener(streamListener)
        inferenceClient.bind()
    }

    override fun onDestroy() {
        cancelActiveRequest()
        audioCapture.cancel()
        statusCollectJob?.cancel()
        inferenceClient.removeListener(streamListener)
        inferenceClient.unbind()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onCreateInputView(): View {
        window?.window?.let { win ->
            WindowCompat.setDecorFitsSystemWindows(win, false)
            win.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            @Suppress("DEPRECATION")
            win.navigationBarColor = Color.TRANSPARENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                win.isNavigationBarContrastEnforced = false
            }
        }

        overlayView = VoiceImeOverlayView(this).apply {
            listener = object : VoiceImeOverlayView.Listener {
                override fun onSwitchKeyboardClick() {
                    val switched = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        switchToPreviousInputMethod()
                    } else {
                        false
                    }
                    if (!switched) {
                        onSwitchKeyboardLongClick()
                    }
                }

                override fun onSwitchKeyboardLongClick() {
                    val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
                    imm?.showInputMethodPicker()
                }

                override fun onBackspaceClick() {
                    performBackspace()
                }

                override fun onEnterClick() {
                    sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
                }

                override fun onStartRecording(source: MicSource): Boolean {
                    if (ContextCompat.checkSelfPermission(
                            this@LiteRtImeService,
                            Manifest.permission.RECORD_AUDIO,
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        Toast.makeText(
                            this@LiteRtImeService,
                            "请先授予麦克风权限",
                            Toast.LENGTH_SHORT,
                        ).show()
                        openSettingsActivity()
                        return false
                    }

                    cancelActiveRequest()
                    return audioCapture.start(
                        onAmplitude = { amp -> overlayView.updateAmplitude(amp) },
                        onError = { msg ->
                            mainHandler.post {
                                Toast.makeText(this@LiteRtImeService, msg, Toast.LENGTH_SHORT).show()
                            }
                        },
                    )
                }

                override fun onFinishRecording(source: MicSource, action: RadialAction) {
                    val pcmBytes = audioCapture.stopAndCollectPcm()
                    if (action == RadialAction.CANCEL || action == RadialAction.NONE) {
                        return
                    }
                    if (pcmBytes.size < WavEncoder.SAMPLE_RATE / 4) {
                        Toast.makeText(
                            this@LiteRtImeService,
                            "说话时间太短，已忽略",
                            Toast.LENGTH_SHORT,
                        ).show()
                        return
                    }
                    dispatchVoiceRequest(source, action, pcmBytes)
                }

                override fun onDragAppAction(source: MicSource, action: DragAppAction) {
                    // The drag-down menu manages model residency, so the captured audio is dropped.
                    audioCapture.cancel()
                    when (action) {
                        DragAppAction.UNLOAD_WHISPER -> {
                            inferenceClient.unloadEngine("ASR")
                            Toast.makeText(
                                this@LiteRtImeService,
                                "正在卸载 Whisper 模型",
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                        DragAppAction.UNLOAD_GEMMA -> {
                            inferenceClient.unloadEngine("LLM")
                            Toast.makeText(
                                this@LiteRtImeService,
                                "正在卸载 Gemma 4 模型",
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                        DragAppAction.RELOAD_GEMMA_GPU,
                        DragAppAction.LOAD_GEMMA_GPU -> {
                            inferenceClient.preloadModel(
                                engineType = "LLM",
                                modelId = resolveLocalLlmModelId(),
                                forceGpu = true,
                            )
                            Toast.makeText(
                                this@LiteRtImeService,
                                "正在用 GPU 加载 Gemma 4…",
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                }

                override fun onCancelActiveGeneration() {
                    cancelActiveRequest()
                }

                override fun onTouchableRegionChanged() {
                    overlayView.requestLayout()
                }
            }
        }

        ViewCompat.setOnApplyWindowInsetsListener(overlayView) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            val gestures = insets.getInsets(WindowInsetsCompat.Type.mandatorySystemGestures())
            val safeBottom = maxOf(bars.bottom, gestures.bottom)
            overlayView.updateSystemInsets(
                bottomPx = safeBottom,
                leftPx = bars.left,
                rightPx = bars.right,
            )
            insets
        }
        overlayView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                ViewCompat.requestApplyInsets(v)
            }

            override fun onViewDetachedFromWindow(v: View) = Unit
        })

        statusCollectJob?.cancel()
        statusCollectJob = serviceScope.launch {
            inferenceClient.statusState.collectLatest { state ->
                val settings = (application as LiteRtImeApp).settingsStore.getSettings()
                overlayView.updateLoadedState(
                    isWhisperLoaded = state.asrLoaded,
                    isGemmaLoaded = state.llmLoaded,
                    isGemmaGpu = state.llmLoadedWithGpu,
                    inferencePid = state.inferencePid,
                    isOnlineMode = settings.llmProvider != LlmProviderType.LOCAL_LITERT,
                )
                if (commitGuard.activeId() != null && state.statusText.isNotBlank()) {
                    overlayView.updateStatusBanner(state.statusText, cancellable = true)
                }
            }
        }

        return overlayView
    }

    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        if (!::overlayView.isInitialized) return
        val w = overlayView.width
        val barTop = overlayView.getIdleTouchableTop()
        val barBottom = overlayView.getIdleTouchableBottom()

        outInsets.contentTopInsets = barTop
        outInsets.visibleTopInsets = barTop
        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
        if (overlayView.isRadialExpanded) {
            outInsets.touchableRegion.set(0, 0, w, barBottom)
        } else {
            outInsets.touchableRegion.set(0, barTop, w, barBottom)
        }
    }

    private fun performBackspace() {
        val ic = currentInputConnection ?: return
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) {
            ic.commitText("", 1)
        } else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        }
    }

    private fun openSettingsActivity() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    private fun settingsStore(): ImeSettingsStore = (application as LiteRtImeApp).settingsStore

    /** Mirrors the LLM model resolution used when dispatching a normal voice request. */
    private fun resolveLocalLlmModelId(): String {
        val app = application as LiteRtImeApp
        val storage = app.modelStorage
        val settings = app.settingsStore.getSettings()
        val configuredSpec = ModelCatalog.findLlm(settings.activeLlmModelId)
        return if (configuredSpec != null && storage.isLlmInstalled(configuredSpec)) {
            configuredSpec.id
        } else {
            storage.findFirstInstalledLlmId() ?: settings.activeLlmModelId
        }
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        if (!restarting) {
            cancelActiveRequest()
        }
    }

    override fun onFinishInput() {
        cancelActiveRequest()
        super.onFinishInput()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        audioCapture.cancel()
        cancelActiveRequest()
        super.onFinishInputView(finishingInput)
    }

    override fun onWindowHidden() {
        audioCapture.cancel()
        cancelActiveRequest()
        super.onWindowHidden()
    }

    private fun cancelActiveRequest() {
        val activeId = commitGuard.beginSession() ?: commitGuard.cancelActive()
        trailingPeriodStripper.reset()
        if (activeId != null) {
            inferenceClient.cancelRequest(activeId)
        }
        if (::overlayView.isInitialized) {
            overlayView.updateStatusBanner("", cancellable = false)
        }
    }

    private fun dispatchVoiceRequest(
        source: MicSource,
        action: RadialAction,
        pcmBytes: ByteArray,
    ) {
        val app = application as LiteRtImeApp
        val storage = app.modelStorage
        val settings = app.settingsStore.getSettings()

        val mode = when (action) {
            RadialAction.WHISPER_TRANSCRIBE -> VoiceMode.WHISPER_RAW
            RadialAction.GEMMA_POLISH_FORMAL -> VoiceMode.GEMMA_POLISH_FORMAL
            RadialAction.GEMMA_SCREENSHOT_CORRECTION -> VoiceMode.GEMMA_SCREENSHOT_CORRECTION
            RadialAction.GEMMA_SCREENSHOT_INSTRUCTION -> VoiceMode.GEMMA_SCREENSHOT_INSTRUCTION
            RadialAction.CANCEL, RadialAction.NONE -> return
        }

        val backendType: String
        val modelId: String
        if (source == MicSource.WHISPER) {
            backendType = "ASR"
            val configuredSpec = com.litertime.android.storage.ModelCatalog.findAsr(settings.activeAsrModelId)
            modelId = if (configuredSpec != null && storage.isAsrInstalled(configuredSpec)) {
                configuredSpec.id
            } else {
                storage.findFirstInstalledAsrId() ?: settings.activeAsrModelId
            }
        } else if (settings.llmProvider != LlmProviderType.LOCAL_LITERT) {
            backendType = "ONLINE_LLM"
            modelId = settings.onlineModelName
        } else {
            backendType = "LLM"
            modelId = resolveLocalLlmModelId()
        }

        val requestId = UUID.randomUUID().toString()
        commitGuard.startRequest(requestId)
        trailingPeriodStripper.reset()
        overlayView.updateStatusBanner(
            if (backendType == "ASR") "正在识别语音..." else "正在思考与润色...",
            cancellable = true,
        )

        serviceScope.launch {
            val ipcDir = File(cacheDir, "ime-ipc").apply { mkdirs() }
            val wavFile = File(ipcDir, "$requestId.wav")
            var screenshotFile: File? = null

            val needsScreenshot = mode == VoiceMode.GEMMA_SCREENSHOT_CORRECTION ||
                mode == VoiceMode.GEMMA_SCREENSHOT_INSTRUCTION
            if (needsScreenshot) {
                if (!ScreenCaptureAccessibilityService.isReady()) {
                    commitGuard.complete(requestId)
                    overlayView.updateStatusBanner("", cancellable = false)
                    Toast.makeText(
                        this@LiteRtImeService,
                        "请先在设置中开启“LiteRT 语音输入法截屏辅助”无障碍服务",
                        Toast.LENGTH_LONG,
                    ).show()
                    return@launch
                }
                // Allow radial overlay collapse to redraw before capturing host screen
                delay(90L)
                val targetShot = File(ipcDir, "$requestId.jpg")
                // Crop the screenshot at the top edge of the IME panel so none of our UI is captured.
                val location = IntArray(2)
                overlayView.getLocationOnScreen(location)
                val panelTopPx = location[1] + overlayView.getIdleTouchableTop()
                screenshotFile = ScreenCaptureAccessibilityService.captureScreenshotToFile(
                    targetShot,
                    keepTopPx = if (panelTopPx > 0) panelTopPx else Int.MAX_VALUE,
                )
                if (screenshotFile == null) {
                    Log.w(TAG, "Screenshot capture returned null; proceeding without image or aborting")
                    commitGuard.complete(requestId)
                    overlayView.updateStatusBanner("", cancellable = false)
                    Toast.makeText(
                        this@LiteRtImeService,
                        "截屏失败，请确认无障碍服务权限正常",
                        Toast.LENGTH_SHORT,
                    ).show()
                    return@launch
                }
            }

            withContext(Dispatchers.IO) {
                wavFile.writeBytes(WavEncoder.pcm16ToWav(pcmBytes))
            }

            if (!commitGuard.shouldCommit(requestId)) {
                wavFile.delete()
                screenshotFile?.delete()
                return@launch
            }

            val extraJson = JSONObject().apply {
                put("language", settings.whisperLanguage)
            }.toString()

            val dispatched = inferenceClient.runRequest(
                requestId = requestId,
                backendType = backendType,
                mode = mode.name,
                modelId = modelId,
                audioWavFile = wavFile,
                screenshotFile = screenshotFile,
                extraJson = extraJson,
            )
            if (!dispatched) {
                commitGuard.complete(requestId)
                overlayView.updateStatusBanner("", cancellable = false)
                wavFile.delete()
                screenshotFile?.delete()
                Toast.makeText(
                    this@LiteRtImeService,
                    "推理服务正在连接，请稍后重试",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }
}
