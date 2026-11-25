package com.litertime.android.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Draw
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Screenshot
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.litertime.android.BuildConfig
import com.litertime.android.LiteRtImeApp
import com.litertime.android.accessibility.ScreenCaptureAccessibilityService
import com.litertime.android.debug.AiRequestLogEntry
import com.litertime.android.debug.AiRequestLogStore
import com.litertime.android.debug.PlaygroundDraft
import com.litertime.android.inference.InferenceClient
import com.litertime.android.inference.StreamListener
import com.litertime.android.pipeline.PromptTemplates
import com.litertime.android.pipeline.VoiceMode
import com.litertime.android.settings.HandwritingInkColor
import com.litertime.android.settings.ImeSettings
import com.litertime.android.settings.LlmProviderType
import com.litertime.android.storage.DownloadMirror
import com.litertime.android.storage.DownloadState
import com.litertime.android.storage.ModelCatalog
import com.litertime.android.storage.ModelDownloader
import com.litertime.android.storage.StorageAccess
import com.litertime.android.storage.StorageMode
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    private lateinit var inferenceClient: InferenceClient
    private lateinit var modelDownloader: ModelDownloader
    private val playgroundRunState = PlaygroundRunState()

    private val playgroundListener = object : StreamListener {
        override fun onStatusChanged(status: String) {
            playgroundRunState.onStatus(status)
        }

        override fun onToken(requestId: String, delta: String) {
            playgroundRunState.onToken(requestId, delta)
        }

        override fun onComplete(requestId: String) {
            playgroundRunState.onComplete(requestId)
        }

        override fun onError(requestId: String, errorMessage: String) {
            playgroundRunState.onError(requestId, errorMessage)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as LiteRtImeApp
        inferenceClient = InferenceClient(this)
        inferenceClient.addListener(playgroundListener)
        inferenceClient.bind()
        modelDownloader = ModelDownloader(app.modelStorage)

        setContent {
            MaterialTheme {
                LiteRtImeSettingsScreen(
                    app = app,
                    inferenceClient = inferenceClient,
                    downloader = modelDownloader,
                    playgroundRunState = playgroundRunState,
                )
            }
        }
    }

    override fun onDestroy() {
        inferenceClient.removeListener(playgroundListener)
        inferenceClient.unbind()
        super.onDestroy()
    }
}

/**
 * Transient Compose state for the most recent playground replay so it survives recomposition and
 * the vertical scroll position while tokens stream in.
 */
private class PlaygroundRunState {
    var requestId by mutableStateOf<String?>(null)
        private set
    var output by mutableStateOf("")
        private set
    var statusLine by mutableStateOf("")
        private set
    var running by mutableStateOf(false)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set
    var sourceLogId by mutableStateOf<String?>(null)
        private set
    var finishedTick by mutableIntStateOf(0)
        private set

    fun start(id: String, sourceLogId: String) {
        requestId = id
        this.sourceLogId = sourceLogId
        output = ""
        statusLine = "正在排队…"
        running = true
        errorMessage = null
    }

    fun fail(message: String) {
        running = false
        statusLine = ""
        errorMessage = message
    }

    fun onStatus(status: String) {
        if (running && status.isNotBlank()) {
            statusLine = status
        }
    }

    fun onToken(id: String, delta: String) {
        if (id == requestId) {
            output += delta
            statusLine = ""
        }
    }

    fun onComplete(id: String) {
        if (id == requestId) {
            running = false
            statusLine = "完成"
            finishedTick++
        }
    }

    fun onError(id: String, message: String) {
        if (id == requestId) {
            running = false
            statusLine = "失败"
            errorMessage = message
            finishedTick++
        }
    }
}

/**
 * Two-level settings navigation: [HOME] lists the sections with a compact status preview and each
 * detail page shows the full controls.
 */
private enum class SettingsPage(val title: String) {
    HOME("LiteRT 语音输入法设置"),
    PERMISSIONS("输入法与系统权限"),
    STORAGE("模型共享存储"),
    SERVICE("独立推理引擎状态"),
    MODELS("模型下载与选择"),
    INPUT("输入设置"),
    TRADITIONAL_INPUT("手写与键盘设置"),
    ONLINE("在线大模型接口"),
    PROMPTS("提示词编辑"),
    DEBUG("AI 请求调试与回放"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LiteRtImeSettingsScreen(
    app: LiteRtImeApp,
    inferenceClient: InferenceClient,
    downloader: ModelDownloader,
    playgroundRunState: PlaygroundRunState,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val storage = app.modelStorage
    val settingsStore = app.settingsStore
    val logStore = remember { AiRequestLogStore(app.filesDir, app.cacheDir) }
    val debugScope = rememberCoroutineScope()

    var currentPage by rememberSaveable { mutableStateOf(SettingsPage.HOME) }

    BackHandler(enabled = currentPage != SettingsPage.HOME) {
        currentPage = SettingsPage.HOME
    }

    var refreshTick by remember { mutableIntStateOf(0) }
    var settings by remember(refreshTick) { mutableStateOf(settingsStore.getSettings()) }
    var storageConfig by remember(refreshTick) { mutableStateOf(storage.getConfig()) }
    var storageError by remember { mutableStateOf<String?>(null) }

    var logEntries by remember { mutableStateOf<List<AiRequestLogEntry>>(emptyList()) }
    var selectedLogId by rememberSaveable { mutableStateOf<String?>(null) }
    var draftSystemPrompt by rememberSaveable { mutableStateOf("") }
    var draftUserPrompt by rememberSaveable { mutableStateOf("") }
    var draftModelId by rememberSaveable { mutableStateOf("") }
    var loadedDraftId by remember { mutableStateOf<String?>(null) }

    val inferenceState by inferenceClient.statusState.collectAsState()
    val downloadStates by downloader.states.collectAsState()
    val a11yConnected by ScreenCaptureAccessibilityService.isConnected.collectAsState()
    val a11yEnabled by ScreenCaptureAccessibilityService.isSystemEnabled.collectAsState()

    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var hasNotifPermission by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var hasAllFilesAccess by remember { mutableStateOf(Environment.isExternalStorageManager()) }
    var imeEnabled by remember { mutableStateOf(isImeEnabled(context)) }
    var imeSelected by remember { mutableStateOf(isImeSelected(context)) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasMicPermission = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO,
                ) == PackageManager.PERMISSION_GRANTED
                hasNotifPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS,
                    ) == PackageManager.PERMISSION_GRANTED
                hasAllFilesAccess = Environment.isExternalStorageManager()
                imeEnabled = isImeEnabled(context)
                imeSelected = isImeSelected(context)
                ScreenCaptureAccessibilityService.refreshSystemEnabledState(context)
                refreshTick++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(refreshTick) {
        logEntries = withContext(Dispatchers.IO) { logStore.listEntries() }
    }

    LaunchedEffect(playgroundRunState.finishedTick) {
        if (playgroundRunState.finishedTick > 0) {
            refreshTick++
        }
    }

    LaunchedEffect(selectedLogId, logEntries) {
        val id = selectedLogId ?: return@LaunchedEffect
        val entry = logEntries.firstOrNull { it.id == id } ?: return@LaunchedEffect
        if (loadedDraftId != id) {
            val draft = withContext(Dispatchers.IO) { logStore.loadDraft(entry) }
            draftSystemPrompt = draft.systemPrompt
            draftUserPrompt = draft.userPrompt
            draftModelId = draft.modelId
            loadedDraftId = id
        }
        snapshotFlow { Triple(draftSystemPrompt, draftUserPrompt, draftModelId) }
            .drop(1)
            .collectLatest { (systemPrompt, userPrompt, modelId) ->
                delay(350)
                withContext(Dispatchers.IO) {
                    logStore.saveDraft(
                        id,
                        PlaygroundDraft(
                            systemPrompt = systemPrompt,
                            userPrompt = userPrompt,
                            modelId = modelId,
                        ),
                    )
                }
            }
    }

    val selectedLogEntry = logEntries.firstOrNull { it.id == selectedLogId }

    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasMicPermission = granted
    }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasNotifPermission = granted
    }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { treeUri ->
        if (treeUri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            val resolvedPath = storage.resolveTreeUriToPath(treeUri)
            if (resolvedPath == null) {
                storageError = "无法解析所选目录的本地路径，请选择手机内部存储中的文件夹"
            } else {
                val targetDir = File(resolvedPath)
                val access = if (Environment.isExternalStorageManager()) {
                    StorageAccess.ALL_FILES
                } else {
                    StorageAccess.SAF
                }
                val ok = storage.setSharedDirectory(
                    dir = targetDir,
                    access = access,
                    treeUri = treeUri.toString(),
                    label = resolvedPath.removePrefix("/storage/emulated/0/").ifBlank { resolvedPath },
                )
                if (ok) {
                    storageError = null
                    storageConfig = storage.getConfig()
                    refreshTick++
                } else {
                    storageError =
                        "目录 $resolvedPath 不可直接读写（原生推理需要 File/mmap）。请先授予「所有文件访问权限」再重新选择。"
                }
            }
        }
    }

    val permissionPreview = buildString {
        append(
            when {
                imeSelected -> "输入法已切换"
                imeEnabled -> "已启用，未设为默认"
                else -> "输入法未启用"
            },
        )
        append(" · ")
        append(if (hasMicPermission) "麦克风已授权" else "麦克风未授权")
        append(" · ")
        append(
            when {
                a11yConnected -> "截屏服务已连接"
                a11yEnabled -> "截屏服务已启用"
                else -> "截屏服务未开启"
            },
        )
    }

    val storagePreview = if (storageConfig.mode == StorageMode.APP) {
        "应用私有存储"
    } else {
        "共享目录：" + storageConfig.label.ifBlank { storageConfig.path }
    }

    val servicePreview = if (!inferenceState.bound) {
        "未连接 :inference 服务"
    } else {
        buildString {
            append(inferenceState.statusText)
            if (inferenceState.asrLoaded) {
                append(" · ")
                append(
                    ModelCatalog.findAsr(inferenceState.asrModelId)?.displayName
                        ?: inferenceState.asrModelId,
                )
            }
            if (inferenceState.llmLoaded) {
                append(" · ")
                append(
                    ModelCatalog.findLlm(inferenceState.llmModelId)?.displayName
                        ?: inferenceState.llmModelId,
                )
                append(" (${inferenceState.llmBackendLabel})")
            }
        }
    }

    val modelsPreview = buildString {
        append("ASR: ")
        append(ModelCatalog.findAsr(settings.activeAsrModelId)?.displayName ?: settings.activeAsrModelId)
        append(" · LLM: ")
        append(ModelCatalog.findLlm(settings.activeLlmModelId)?.displayName ?: settings.activeLlmModelId)
        if (settings.voiceCascadeEnabled) {
            append(" · 语音级联")
        }
    }

    val inputPreview = if (settings.stripTrailingPeriod) "句尾句号已去除" else "保留句尾句号"

    val traditionalInputPreview = buildString {
        append(String.format(Locale.US, "手写淡出 %.1fs", settings.handwritingFadeDelayMs / 1000f))
        append(" · ")
        append(if (settings.handwritingAutoCommitFirst) "首字上屏" else "手动选字")
        append(" · ")
        append(if (settings.keyboardShowNumberRow) "含数字行" else "标准 QWERTY")
    }

    val onlinePreview = buildString {
        append(settings.llmProvider.displayName)
        if (settings.llmProvider != LlmProviderType.LOCAL_LITERT) {
            append(" · ")
            append(settings.onlineModelName)
        }
    }

    val debugPreview = buildString {
        append(if (settings.debugRequestLoggingEnabled) "记录已开启" else "记录已关闭")
        append(" · ")
        append(logEntries.size)
        append(" 条记录")
    }

    val promptsPreview = buildString {
        append(if (settings.systemPromptOverride.isBlank()) "系统 Prompt 默认" else "系统 Prompt 已自定义")
        val customizedModes = VoiceMode.entries.count { settings.userPromptOverride(it).isNotBlank() }
        append(" · ")
        append(
            if (customizedModes == 0) {
                "模式 Prompt 全部默认"
            } else {
                "模式 Prompt $customizedModes/${VoiceMode.entries.size} 已自定义"
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(currentPage.title) },
                navigationIcon = {
                    if (currentPage != SettingsPage.HOME) {
                        IconButton(onClick = { currentPage = SettingsPage.HOME }) {
                            Icon(
                                Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = "返回",
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (currentPage == SettingsPage.HOME) {
                SettingsHomeItem(
                    icon = { Icon(Icons.Rounded.Keyboard, contentDescription = null) },
                    title = "输入法与系统权限",
                    preview = permissionPreview,
                    onClick = { currentPage = SettingsPage.PERMISSIONS },
                )
                SettingsHomeItem(
                    icon = { Icon(Icons.Rounded.FolderOpen, contentDescription = null) },
                    title = "模型共享存储",
                    preview = storagePreview,
                    onClick = { currentPage = SettingsPage.STORAGE },
                )
                SettingsHomeItem(
                    icon = { Icon(Icons.Rounded.Memory, contentDescription = null) },
                    title = "独立推理引擎状态",
                    preview = servicePreview,
                    onClick = { currentPage = SettingsPage.SERVICE },
                )
                SettingsHomeItem(
                    icon = { Icon(Icons.Rounded.Download, contentDescription = null) },
                    title = "模型下载与选择",
                    preview = modelsPreview,
                    onClick = { currentPage = SettingsPage.MODELS },
                )
                SettingsHomeItem(
                    icon = { Icon(Icons.Rounded.Tune, contentDescription = null) },
                    title = "输入设置",
                    preview = inputPreview,
                    onClick = { currentPage = SettingsPage.INPUT },
                )
                SettingsHomeItem(
                    icon = { Icon(Icons.Rounded.Draw, contentDescription = null) },
                    title = "手写与键盘设置",
                    preview = traditionalInputPreview,
                    onClick = { currentPage = SettingsPage.TRADITIONAL_INPUT },
                )
                SettingsHomeItem(
                    icon = { Icon(Icons.Rounded.Cloud, contentDescription = null) },
                    title = "在线大模型接口",
                    preview = onlinePreview,
                    onClick = { currentPage = SettingsPage.ONLINE },
                )
                SettingsHomeItem(
                    icon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                    title = "提示词编辑",
                    preview = promptsPreview,
                    onClick = { currentPage = SettingsPage.PROMPTS },
                )
                SettingsHomeItem(
                    icon = { Icon(Icons.Rounded.BugReport, contentDescription = null) },
                    title = "AI 请求调试与回放",
                    preview = debugPreview,
                    onClick = { currentPage = SettingsPage.DEBUG },
                )
            }

            // 1. Setup & Permissions
            if (currentPage == SettingsPage.PERMISSIONS) {
            SectionCard(title = "1. 输入法与系统权限") {
                PermissionRow(
                    icon = { Icon(Icons.Rounded.Keyboard, contentDescription = null) },
                    title = "启用并切换输入法",
                    subtitle = when {
                        imeSelected -> "当前默认输入法已设为 LiteRT"
                        imeEnabled -> "已在系统启用，请切换为默认"
                        else -> "需在系统键盘设置中勾选启用"
                    },
                    granted = imeSelected,
                    actionLabel = if (!imeEnabled) "去启用" else "切换输入法",
                    onAction = {
                        if (!imeEnabled) {
                            context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                        } else {
                            val imm = context.getSystemService(InputMethodManager::class.java)
                            imm?.showInputMethodPicker()
                        }
                    },
                )
                HorizontalDivider()
                PermissionRow(
                    icon = { Icon(Icons.Rounded.Mic, contentDescription = null) },
                    title = "麦克风录音权限",
                    subtitle = if (hasMicPermission) "已授权" else "语音输入必需",
                    granted = hasMicPermission,
                    actionLabel = if (hasMicPermission) "已授权" else "授权",
                    enabled = !hasMicPermission,
                    onAction = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    HorizontalDivider()
                    PermissionRow(
                        icon = { Icon(Icons.Rounded.Memory, contentDescription = null) },
                        title = "通知栏常驻权限",
                        subtitle = if (hasNotifPermission) "已授权（用于显示已加载模型与卸载按钮）" else "用于显示模型加载状态",
                        granted = hasNotifPermission,
                        actionLabel = if (hasNotifPermission) "已授权" else "授权",
                        enabled = !hasNotifPermission,
                        onAction = { notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                    )
                }
                HorizontalDivider()
                PermissionRow(
                    icon = { Icon(Icons.Rounded.Screenshot, contentDescription = null) },
                    title = "无障碍截屏服务",
                    subtitle = when {
                        a11yConnected -> "已连接（支持「截屏纠错」「截屏指令」）"
                        a11yEnabled -> "系统已启用，等待连接"
                        else -> "使用 Gemma 4 截屏功能前需开启一次"
                    },
                    granted = a11yConnected || a11yEnabled,
                    actionLabel = if (a11yConnected) "已开启" else "去开启",
                    onAction = {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    },
                )
            }
            }

            // 2. Shared Model Storage (model-storage.md)
            if (currentPage == SettingsPage.STORAGE) {
            SectionCard(title = "2. 模型共享存储 (model-storage.md)") {
                Text(
                    text = "可与同目录规范的应用（如 nostalgiaPod）共用 llm-models/ 与 asr-models/ 权重，避免重复下载 2–4 GB。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = storageConfig.mode == StorageMode.APP,
                        onClick = {
                            storage.setAppStorageMode()
                            storageError = null
                            storageConfig = storage.getConfig()
                            refreshTick++
                        },
                        label = { Text("应用私有存储") },
                    )
                    FilterChip(
                        selected = storageConfig.mode == StorageMode.ANDROID,
                        onClick = {
                            if (!hasAllFilesAccess) {
                                openAllFilesPermission(context)
                            } else {
                                folderPickerLauncher.launch(null)
                            }
                        },
                        label = { Text("安卓共享目录") },
                    )
                }

                Text(
                    text = "当前有效根目录：${storage.effectiveRootDir().absolutePath}",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                )

                if (storageConfig.mode == StorageMode.ANDROID && !storage.isConfiguredSharedDirAccessible()) {
                    Text(
                        text = "提示：已选共享目录 (${storageConfig.path}) 当前不可写，未拿到权限前已下载模型可能显示为未安装。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (storageError != null) {
                    Text(
                        text = storageError!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = { openAllFilesPermission(context) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(if (hasAllFilesAccess) "所有文件权限已开" else "授予所有文件读写")
                    }
                    Button(
                        onClick = { folderPickerLauncher.launch(null) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Rounded.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text("选择共享文件夹")
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "LiteRT 编译缓存：${formatBytes(storage.litertCacheSizeBytes())}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(
                        onClick = {
                            storage.clearLitertCache()
                            refreshTick++
                            Toast.makeText(context, "编译缓存已清理", Toast.LENGTH_SHORT).show()
                        },
                    ) {
                        Text("清理缓存")
                    }
                }
            }
            }

            // 3. Resident Inference Service Status
            if (currentPage == SettingsPage.SERVICE) {
            SectionCard(title = "3. 独立推理引擎状态 (:inference)") {
                Text(
                    text = "状态：${inferenceState.statusText}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                val residentParts = buildList {
                    if (inferenceState.asrLoaded) {
                        val name = ModelCatalog.findAsr(inferenceState.asrModelId)?.displayName
                            ?: inferenceState.asrModelId
                        add("Whisper $name")
                    }
                    if (inferenceState.llmLoaded) {
                        val name = ModelCatalog.findLlm(inferenceState.llmModelId)?.displayName
                            ?: inferenceState.llmModelId
                        add("Gemma $name (${inferenceState.llmBackendLabel})")
                    }
                }
                val residentText = if (residentParts.isEmpty()) {
                    "(空)（Whisper 与 Gemma 可同时驻留，加载后不主动卸载）"
                } else {
                    residentParts.joinToString(" + ")
                }
                val pidText = if (inferenceState.inferencePid > 0) {
                    " · pid ${inferenceState.inferencePid}"
                } else {
                    ""
                }
                Text(
                    text = "当前驻留：$residentText$pidText",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = { inferenceClient.preloadModel("ASR", settings.activeAsrModelId) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("预加载 Whisper")
                    }
                    OutlinedButton(
                        onClick = { inferenceClient.preloadModel("LLM", settings.activeLlmModelId) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("预加载 Gemma 4")
                    }
                    TextButton(onClick = { inferenceClient.unloadModel() }) {
                        Text("卸载")
                    }
                }
            }
            }

            // 4. Mirror & Model Management
            if (currentPage == SettingsPage.MODELS) {
            SectionCard(title = "4. 模型下载与选择") {
                Text("下载源", style = MaterialTheme.typography.labelLarge)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    DownloadMirror.entries.forEach { mirror ->
                        FilterChip(
                            selected = settings.downloadMirror == mirror,
                            onClick = {
                                settings = settingsStore.update { it.copy(downloadMirror = mirror) }
                            },
                            label = { Text(mirror.displayName.substringBefore(" ")) },
                        )
                    }
                }

                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Gemma 4 使用 GPU 加速", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = if (storage.hasGpuCrashMarker(BuildConfig.VERSION_CODE)) {
                                "检测到当前版本 GPU 崩溃标记，当前自动回退 CPU"
                            } else {
                                "文本/视觉优先走 GPU (OpenCL/ML Drift)，音频编码走 CPU"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = settings.useGpuForLlm,
                        onCheckedChange = { checked ->
                            settings = settingsStore.update { it.copy(useGpuForLlm = checked) }
                        },
                    )
                }
                if (storage.hasGpuCrashMarker(BuildConfig.VERSION_CODE)) {
                    TextButton(
                        onClick = {
                            storage.clearGpuCrashMarker()
                            refreshTick++
                        },
                    ) {
                        Text("清除 GPU 崩溃标记")
                    }
                }

                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("语音级联", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = if (settings.voiceCascadeEnabled) {
                                "AI 麦克风先用下方 Whisper/ASR 模型转写为文字，再把文字交给大模型处理；截屏纠错与截屏指令同样生效"
                            } else {
                                "关闭时由 Gemma 4 直接理解语音（端到端多模态），不经过 Whisper"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = settings.voiceCascadeEnabled,
                        onCheckedChange = { checked ->
                            settings = settingsStore.update { it.copy(voiceCascadeEnabled = checked) }
                        },
                    )
                }
                if (settings.voiceCascadeEnabled) {
                    val cascadeAsrSpec = ModelCatalog.findAsr(settings.activeAsrModelId)
                    val cascadeAsrReady = cascadeAsrSpec != null && storage.isAsrInstalled(cascadeAsrSpec)
                    Text(
                        text = if (cascadeAsrReady) {
                            "级联 ASR：${cascadeAsrSpec?.displayName} · 提示词会自动改用识别文本版本"
                        } else {
                            "所选 ASR 模型尚未安装，级联时会自动回退到 Gemma 直接识别语音"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (cascadeAsrReady) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                }

                HorizontalDivider()
                Text("端侧 Gemma 4 多模态模型", style = MaterialTheme.typography.titleSmall)
                ModelCatalog.llmModels.forEach { spec ->
                    val installed = storage.isLlmInstalled(spec)
                    val dlState = downloadStates[spec.id] ?: DownloadState.Idle
                    ModelItemRow(
                        title = spec.displayName,
                        subtitle = "${formatBytes(spec.expectedSizeBytes)} · ${spec.description}",
                        selected = settings.activeLlmModelId == spec.id,
                        installed = installed,
                        downloadState = dlState,
                        onSelect = {
                            settings = settingsStore.update { it.copy(activeLlmModelId = spec.id) }
                        },
                        onDownload = {
                            downloader.downloadLlm(spec, settings.downloadMirror) { refreshTick++ }
                        },
                        onCancelDownload = { downloader.cancel(spec.id) },
                        onDelete = {
                            if (inferenceState.llmModelId == spec.id) {
                                inferenceClient.unloadEngine("LLM")
                            }
                            storage.deleteLlmModel(spec.id)
                            refreshTick++
                        },
                    )
                }

                HorizontalDivider()
                Text("Whisper / ASR 语音识别模型", style = MaterialTheme.typography.titleSmall)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf("" to "自动语言", "zh" to "中文", "en" to "English").forEach { (code, label) ->
                        FilterChip(
                            selected = settings.whisperLanguage == code,
                            onClick = {
                                settings = settingsStore.update { it.copy(whisperLanguage = code) }
                            },
                            label = { Text(label) },
                        )
                    }
                }
                ModelCatalog.asrModels.forEach { spec ->
                    val installed = storage.isAsrInstalled(spec)
                    val dlState = downloadStates[spec.id] ?: DownloadState.Idle
                    ModelItemRow(
                        title = spec.displayName,
                        subtitle = "${formatBytes(spec.totalApproxBytes)} · ${spec.languageSummary}",
                        selected = settings.activeAsrModelId == spec.id,
                        installed = installed,
                        downloadState = dlState,
                        onSelect = {
                            settings = settingsStore.update { it.copy(activeAsrModelId = spec.id) }
                        },
                        onDownload = {
                            downloader.downloadAsr(spec, settings.downloadMirror) { refreshTick++ }
                        },
                        onCancelDownload = { downloader.cancel(spec.id) },
                        onDelete = {
                            if (inferenceState.asrModelId == spec.id) {
                                inferenceClient.unloadEngine("ASR")
                            }
                            storage.deleteAsrModel(spec.id)
                            refreshTick++
                        },
                    )
                }
            }
            }

            // 5. Input behaviour (text post-processing before commit)
            if (currentPage == SettingsPage.INPUT) {
            SectionCard(title = "输入设置") {
                Text(
                    text = "控制语音识别/润色结果写入输入框前的文本整理。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("去掉句尾句号", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "自动删除结果末尾的「。」或「.」，句中的句号保留",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = settings.stripTrailingPeriod,
                        onCheckedChange = { checked ->
                            settings = settingsStore.update {
                                it.copy(stripTrailingPeriod = checked)
                            }
                        },
                    )
                }
            }
            }

            // Traditional input settings: Chinese handwriting + English QWERTY keyboard
            if (currentPage == SettingsPage.TRADITIONAL_INPUT) {
                SectionCard(title = "中文手写 (Handwritten)") {
                    Text(
                        text = String.format(
                            Locale.US,
                            "写完淡出延迟：%.1f 秒（默认 1.0 秒）",
                            settings.handwritingFadeDelayMs / 1000f,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = "一笔一划停笔后等待多长时间自动淡出并准备写下一个字",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Slider(
                        value = settings.handwritingFadeDelayMs.toFloat(),
                        onValueChange = { raw ->
                            val snapped = ((raw / 100f).toInt() * 100).coerceIn(
                                ImeSettings.MIN_HANDWRITING_FADE_DELAY_MS,
                                ImeSettings.MAX_HANDWRITING_FADE_DELAY_MS,
                            )
                            settings = settingsStore.update { it.copy(handwritingFadeDelayMs = snapped) }
                        },
                        valueRange = ImeSettings.MIN_HANDWRITING_FADE_DELAY_MS.toFloat()..
                            ImeSettings.MAX_HANDWRITING_FADE_DELAY_MS.toFloat(),
                        steps = (ImeSettings.MAX_HANDWRITING_FADE_DELAY_MS -
                            ImeSettings.MIN_HANDWRITING_FADE_DELAY_MS) / 100 - 1,
                    )

                    HorizontalDivider()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("默认首字自动上屏", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = "淡出时自动将首选候选字写入输入框，之后仍可点选其他候选替换",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = settings.handwritingAutoCommitFirst,
                            onCheckedChange = { checked ->
                                settings = settingsStore.update { it.copy(handwritingAutoCommitFirst = checked) }
                            },
                        )
                    }

                    HorizontalDivider()
                    Text(
                        text = String.format(Locale.US, "笔迹粗细：%.1f dp", settings.handwritingStrokeWidthDp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Slider(
                        value = settings.handwritingStrokeWidthDp,
                        onValueChange = { raw ->
                            val snapped = ((raw * 2f).toInt() / 2f).coerceIn(
                                ImeSettings.MIN_HANDWRITING_STROKE_WIDTH_DP,
                                ImeSettings.MAX_HANDWRITING_STROKE_WIDTH_DP,
                            )
                            settings = settingsStore.update { it.copy(handwritingStrokeWidthDp = snapped) }
                        },
                        valueRange = ImeSettings.MIN_HANDWRITING_STROKE_WIDTH_DP..
                            ImeSettings.MAX_HANDWRITING_STROKE_WIDTH_DP,
                    )

                    HorizontalDivider()
                    Text("笔迹颜色", style = MaterialTheme.typography.bodyMedium)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        HandwritingInkColor.entries.forEach { ink ->
                            FilterChip(
                                selected = settings.handwritingInkColor == ink,
                                onClick = {
                                    settings = settingsStore.update { it.copy(handwritingInkColor = ink) }
                                },
                                label = { Text(ink.displayName) },
                            )
                        }
                    }

                    HorizontalDivider()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("显示田字格辅助线", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = "在手写板中显示虚线十字参考线",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = settings.handwritingShowGrid,
                            onCheckedChange = { checked ->
                                settings = settingsStore.update { it.copy(handwritingShowGrid = checked) }
                            },
                        )
                    }

                    HorizontalDivider()
                    Text(
                        text = "手写板高度：${settings.handwritingPanelHeightDp} dp",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Slider(
                        value = settings.handwritingPanelHeightDp.toFloat(),
                        onValueChange = { raw ->
                            val snapped = ((raw / 10f).toInt() * 10).coerceIn(
                                ImeSettings.MIN_HANDWRITING_PANEL_HEIGHT_DP,
                                ImeSettings.MAX_HANDWRITING_PANEL_HEIGHT_DP,
                            )
                            settings = settingsStore.update { it.copy(handwritingPanelHeightDp = snapped) }
                        },
                        valueRange = ImeSettings.MIN_HANDWRITING_PANEL_HEIGHT_DP.toFloat()..
                            ImeSettings.MAX_HANDWRITING_PANEL_HEIGHT_DP.toFloat(),
                        steps = (ImeSettings.MAX_HANDWRITING_PANEL_HEIGHT_DP -
                            ImeSettings.MIN_HANDWRITING_PANEL_HEIGHT_DP) / 10 - 1,
                    )
                }

                SectionCard(title = "英文键盘 (FlorisBoard 方案)") {
                    Text(
                        text = "键盘高度：${settings.keyboardHeightDp} dp",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Slider(
                        value = settings.keyboardHeightDp.toFloat(),
                        onValueChange = { raw ->
                            val snapped = ((raw / 10f).toInt() * 10).coerceIn(
                                ImeSettings.MIN_KEYBOARD_HEIGHT_DP,
                                ImeSettings.MAX_KEYBOARD_HEIGHT_DP,
                            )
                            settings = settingsStore.update { it.copy(keyboardHeightDp = snapped) }
                        },
                        valueRange = ImeSettings.MIN_KEYBOARD_HEIGHT_DP.toFloat()..
                            ImeSettings.MAX_KEYBOARD_HEIGHT_DP.toFloat(),
                        steps = (ImeSettings.MAX_KEYBOARD_HEIGHT_DP -
                            ImeSettings.MIN_KEYBOARD_HEIGHT_DP) / 10 - 1,
                    )

                    HorizontalDivider()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("显示顶部数字行 (1–0)", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = "在 QWERTY 字母上方额外显示一行数字键",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = settings.keyboardShowNumberRow,
                            onCheckedChange = { checked ->
                                settings = settingsStore.update { it.copy(keyboardShowNumberRow = checked) }
                            },
                        )
                    }

                    HorizontalDivider()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("按键振动反馈", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = "按键、标点及手写选字时提供轻触震动",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = settings.keyboardHapticEnabled,
                            onCheckedChange = { checked ->
                                settings = settingsStore.update { it.copy(keyboardHapticEnabled = checked) }
                            },
                        )
                    }

                    HorizontalDivider()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("按键弹窗预览", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = "按下字母键时上方显示当前字符气泡（无长按符号）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = settings.keyboardKeyPopupEnabled,
                            onCheckedChange = { checked ->
                                settings = settingsStore.update { it.copy(keyboardKeyPopupEnabled = checked) }
                            },
                        )
                    }

                    HorizontalDivider()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("句首自动大写", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = "在句首自动切换为 Shift 大写状态",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = settings.keyboardAutoCapsEnabled,
                            onCheckedChange = { checked ->
                                settings = settingsStore.update { it.copy(keyboardAutoCapsEnabled = checked) }
                            },
                        )
                    }

                    HorizontalDivider()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("双击空格输入句号", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = "英文输入时连续按两次空格自动替换为「. 」",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = settings.keyboardDoubleSpacePeriod,
                            onCheckedChange = { checked ->
                                settings = settingsStore.update { it.copy(keyboardDoubleSpacePeriod = checked) }
                            },
                        )
                    }
                }
            }

            // 5. Reserved Online LLM Settings
            if (currentPage == SettingsPage.ONLINE) {
            SectionCard(title = "5. 在线大模型接口预留 (TextGenerationBackend)") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Rounded.Cloud, contentDescription = null)
                    Text(
                        text = "与本地 Gemma 4 共用同一套 TextGenerationBackend 流式接口，未来无需改动 IME UI 与状态机即可接入在线大模型。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                LlmProviderType.entries.forEach { provider ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = settings.llmProvider == provider,
                            onClick = {
                                settings = settingsStore.update { it.copy(llmProvider = provider) }
                            },
                        )
                        Text(provider.displayName, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (settings.llmProvider != LlmProviderType.LOCAL_LITERT) {
                    OutlinedTextField(
                        value = settings.onlineBaseUrl,
                        onValueChange = { value ->
                            settings = settingsStore.update { it.copy(onlineBaseUrl = value) }
                        },
                        label = { Text("API Base URL") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = settings.onlineModelName,
                        onValueChange = { value ->
                            settings = settingsStore.update { it.copy(onlineModelName = value) }
                        },
                        label = { Text("Model Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = settings.onlineApiKey,
                        onValueChange = { value ->
                            settings = settingsStore.update { it.copy(onlineApiKey = value) }
                        },
                        label = { Text("API Key") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            }

            // 6. Prompt editing (system prompt + per-mode user prompts)
            if (currentPage == SettingsPage.PROMPTS) {
            SectionCard(title = "6. 提示词编辑") {
                Text(
                    text = "留空表示使用内置默认模板。修改会立即保存，并在下一次语音请求时生效。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("统一 System Prompt", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    value = settings.systemPromptOverride,
                    onValueChange = { value ->
                        settings = settingsStore.update { it.copy(systemPromptOverride = value) }
                    },
                    label = { Text("所有模式共用") },
                    placeholder = { Text(PromptTemplates.SYSTEM_PROMPT_VOICE_IME) },
                    minLines = 4,
                    maxLines = 12,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(
                        onClick = {
                            settings = settingsStore.update {
                                it.copy(systemPromptOverride = PromptTemplates.SYSTEM_PROMPT_VOICE_IME)
                            }
                        },
                    ) {
                        Text("填入默认模板")
                    }
                    TextButton(
                        enabled = settings.systemPromptOverride.isNotBlank(),
                        onClick = {
                            settings = settingsStore.update { it.copy(systemPromptOverride = "") }
                        },
                    ) {
                        Text("恢复默认")
                    }
                }

                HorizontalDivider()
                Text("各模式 User Prompt", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = if (settings.voiceCascadeEnabled) {
                        "「语音级联」已开启：下方默认模板与占位符为识别文本版本，模型收到 ASR 文本而非音频。已自定义的模板在开启/关闭级联时共用。"
                    } else {
                        "开启「语音级联」后，未自定义的模式会自动改用识别文本版本的默认模板（模型收到 ASR 文本，不再接收音频）。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                VoiceMode.entries.forEach { mode ->
                    val savedPrompt = settings.userPromptOverride(mode)
                    val defaultPrompt = PromptTemplates.userPromptFor(
                        mode,
                        cascade = settings.voiceCascadeEnabled,
                    )
                    OutlinedTextField(
                        value = savedPrompt,
                        onValueChange = { value ->
                            settings = settingsStore.update { current ->
                                current.withUserPromptOverride(mode, value)
                            }
                        },
                        label = { Text(voiceModePromptLabel(mode)) },
                        placeholder = { Text(defaultPrompt) },
                        minLines = 3,
                        maxLines = 10,
                        supportingText = {
                            Text(if (savedPrompt.isBlank()) "当前使用内置默认模板" else "已自定义")
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TextButton(
                            onClick = {
                                settings = settingsStore.update { current ->
                                    current.withUserPromptOverride(mode, defaultPrompt)
                                }
                            },
                        ) {
                            Text("填入默认模板")
                        }
                        TextButton(
                            enabled = savedPrompt.isNotBlank(),
                            onClick = {
                                settings = settingsStore.update { current ->
                                    current.withUserPromptOverride(mode, "")
                                }
                            },
                        ) {
                            Text("恢复默认")
                        }
                    }
                }
            }
            }

            // 7. AI request logging & replay playground
            if (currentPage == SettingsPage.DEBUG) {
            SectionCard(title = "7. AI 请求调试与回放") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("记录每一次 AI 请求", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "开启后保存语音、截图（如有）、系统/用户 Prompt 与实际使用的模型",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = settings.debugRequestLoggingEnabled,
                        onCheckedChange = { checked ->
                            settings = settingsStore.update {
                                it.copy(debugRequestLoggingEnabled = checked)
                            }
                        },
                    )
                }
                Text(
                    text = "日志目录：${logStore.rootDir().absolutePath}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("共 ${logEntries.size} 条记录", style = MaterialTheme.typography.labelLarge)
                    TextButton(onClick = { refreshTick++ }) {
                        Text("刷新列表")
                    }
                }
                if (selectedLogEntry != null) {
                    HorizontalDivider()
                    Text(
                        text = "回放 ${formatLogTime(selectedLogEntry.timestamp)} · " +
                            "${selectedLogEntry.backendType} · ${selectedLogEntry.mode}",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = "原始模型：${selectedLogEntry.modelDisplayName} " +
                            "(${selectedLogEntry.modelId}) · 状态：${logStatusLabel(selectedLogEntry.status)} · " +
                            "来源：${if (selectedLogEntry.origin == "playground") "回放" else "输入法"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (selectedLogEntry.replayOf.isNotBlank()) {
                        Text(
                            text = "回放自：${selectedLogEntry.replayOf}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = "原始输出：${selectedLogEntry.output.ifBlank { "(空)" }}",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 8,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (selectedLogEntry.errorMessage.isNotBlank()) {
                        Text(
                            text = "原始错误：${selectedLogEntry.errorMessage}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Text(
                        text = "输入素材：语音 ${if (selectedLogEntry.hasAudio) "✓" else "✗"} · " +
                            "截图 ${if (selectedLogEntry.hasScreenshot) "✓" else "✗"}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (selectedLogEntry.transcript.isNotBlank()) {
                        Text(
                            text = "ASR 转写（语音级联输入）：${selectedLogEntry.transcript}",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 6,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    if (selectedLogEntry.hasScreenshot) {
                        val screenshot by produceState<ImageBitmap?>(
                            initialValue = null,
                            key1 = selectedLogEntry.id,
                        ) {
                            value = withContext(Dispatchers.IO) {
                                val file = File(
                                    selectedLogEntry.dir,
                                    selectedLogEntry.screenshotFileName,
                                )
                                runCatching {
                                    BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
                                }.getOrNull()
                            }
                        }
                        Text("输入截图（模型实际收到的画面）", style = MaterialTheme.typography.labelLarge)
                        val bitmap = screenshot
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap,
                                contentDescription = "输入截图",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 360.dp),
                                contentScale = ContentScale.Fit,
                            )
                        } else {
                            Text(
                                text = "截图加载中…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    if (selectedLogEntry.backendType == "ASR") {
                        Text(
                            text = "Whisper / ASR 由模型内部解码，不使用系统/用户 Prompt；回放时仅可切换 ASR 模型。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        OutlinedTextField(
                            value = draftSystemPrompt,
                            onValueChange = { draftSystemPrompt = it },
                            label = { Text("系统 Prompt") },
                            minLines = 2,
                            maxLines = 6,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = draftUserPrompt,
                            onValueChange = { draftUserPrompt = it },
                            label = { Text("用户 Prompt") },
                            minLines = 3,
                            maxLines = 10,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    Text("回放模型", style = MaterialTheme.typography.labelLarge)
                    val candidateModels = if (selectedLogEntry.backendType == "ASR") {
                        ModelCatalog.asrModels.map { it.id to it.displayName }
                    } else {
                        ModelCatalog.llmModels.map { it.id to it.displayName }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        candidateModels.forEach { (modelId, displayName) ->
                            val installed = if (selectedLogEntry.backendType == "ASR") {
                                ModelCatalog.findAsr(modelId)?.let { storage.isAsrInstalled(it) } ?: false
                            } else {
                                ModelCatalog.findLlm(modelId)?.let { storage.isLlmInstalled(it) } ?: false
                            }
                            FilterChip(
                                selected = draftModelId == modelId,
                                onClick = { draftModelId = modelId },
                                label = {
                                    Text(if (installed) displayName else "$displayName (未安装)")
                                },
                            )
                        }
                    }
                    if (selectedLogEntry.backendType == "ONLINE_LLM") {
                        Text(
                            text = "在线大模型通道目前为预留实现，回放只会返回占位错误。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(
                        onClick = {
                            draftSystemPrompt = selectedLogEntry.systemPrompt
                            draftUserPrompt = selectedLogEntry.userPrompt
                            draftModelId = selectedLogEntry.modelId
                        },
                    ) {
                        Text("恢复原始 Prompt 与模型")
                    }

                    val replayVisible = playgroundRunState.sourceLogId == selectedLogEntry.id
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Button(
                            onClick = {
                                debugScope.launch {
                                    val prepared = withContext(Dispatchers.IO) {
                                        logStore.prepareReplay(selectedLogEntry)
                                    }
                                    if (prepared == null) {
                                        Toast.makeText(
                                            context,
                                            "原始音频缺失，无法回放",
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                        return@launch
                                    }
                                    val requestId = UUID.randomUUID().toString()
                                    playgroundRunState.start(requestId, selectedLogEntry.id)
                                    val extraJson = JSONObject().apply {
                                        put("language", selectedLogEntry.languageHint)
                                        if (selectedLogEntry.backendType != "ASR") {
                                            put("systemPrompt", draftSystemPrompt)
                                            put("userPrompt", draftUserPrompt)
                                            if (selectedLogEntry.transcript.isNotBlank()) {
                                                put("asrTranscript", selectedLogEntry.transcript)
                                            }
                                        }
                                        put("origin", "playground")
                                        put("replayOf", selectedLogEntry.id)
                                    }.toString()
                                    val dispatched = inferenceClient.runRequest(
                                        requestId = requestId,
                                        backendType = selectedLogEntry.backendType,
                                        mode = selectedLogEntry.mode,
                                        modelId = draftModelId,
                                        audioWavFile = prepared.audioFile,
                                        screenshotFile = prepared.screenshotFile,
                                        extraJson = extraJson,
                                    )
                                    if (!dispatched) {
                                        playgroundRunState.fail("推理服务正在连接，请稍后重试")
                                    }
                                }
                            },
                            enabled = !playgroundRunState.running && selectedLogEntry.hasAudio,
                        ) {
                            Text(
                                if (playgroundRunState.running && replayVisible) {
                                    "推理中…"
                                } else {
                                    "用当前设置重新请求"
                                },
                            )
                        }
                        if (playgroundRunState.running && replayVisible) {
                            OutlinedButton(
                                onClick = {
                                    playgroundRunState.requestId?.let {
                                        inferenceClient.cancelRequest(it)
                                    }
                                },
                            ) {
                                Text("取消")
                            }
                        }
                    }

                    if (replayVisible &&
                        (playgroundRunState.running ||
                            playgroundRunState.output.isNotBlank() ||
                            playgroundRunState.errorMessage != null)
                    ) {
                        Text("回放输出", style = MaterialTheme.typography.labelLarge)
                        if (playgroundRunState.statusLine.isNotBlank()) {
                            Text(
                                text = playgroundRunState.statusLine,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            text = playgroundRunState.output.ifBlank {
                                if (playgroundRunState.running) "(等待输出…)" else "(空)"
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                        playgroundRunState.errorMessage?.let { message ->
                            Text(
                                text = "回放错误：$message",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }

                HorizontalDivider()
                if (logEntries.isEmpty()) {
                    Text(
                        text = "暂无记录。开启开关后，下一次语音请求会被保存到这里。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text("选择一条记录进行回放", style = MaterialTheme.typography.labelLarge)
                    logEntries.take(40).forEach { entry ->
                        LogEntryRow(
                            entry = entry,
                            selected = entry.id == selectedLogId,
                            onClick = { selectedLogId = entry.id },
                        )
                    }
                    if (logEntries.size > 40) {
                        Text(
                            text = "仅显示最近 40 条，完整原始数据见日志目录。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SettingsHomeItem(
    icon: @Composable () -> Unit,
    title: String,
    preview: String,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            icon()
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = preview,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LogEntryRow(
    entry: AiRequestLogEntry,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLowest
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(formatLogTime(entry.timestamp), style = MaterialTheme.typography.labelMedium)
                Text(
                    text = if (entry.origin == "playground") "回放" else "输入法",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "${entry.modelDisplayName} · ${entry.mode} · ${logStatusLabel(entry.status)}",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = entry.output.ifBlank { "(无输出)" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun voiceModePromptLabel(mode: VoiceMode): String = when (mode) {
    VoiceMode.WHISPER_RAW -> "原样转写"
    VoiceMode.GEMMA_POLISH_FORMAL -> "正式润色"
    VoiceMode.GEMMA_SCREENSHOT_CORRECTION -> "截屏纠错"
    VoiceMode.GEMMA_SCREENSHOT_INSTRUCTION -> "截屏指令"
}

private fun formatLogTime(timestamp: Long): String =
    SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestamp))

private fun logStatusLabel(status: String): String = when (status) {
    "completed" -> "已完成"
    "error" -> "失败"
    "cancelled" -> "已取消"
    "pending" -> "未完成"
    else -> status.ifBlank { "未知" }
}

@Composable
private fun SectionCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            content()
        }
    }
}

@Composable
private fun PermissionRow(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String,
    granted: Boolean,
    actionLabel: String,
    enabled: Boolean = true,
    onAction: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        icon()
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Icon(
                    imageVector = if (granted) Icons.Rounded.CheckCircle else Icons.Rounded.Warning,
                    contentDescription = null,
                    tint = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(16.dp),
                )
            }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedButton(onClick = onAction, enabled = enabled) {
            Text(actionLabel)
        }
    }
}

@Composable
private fun ModelItemRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    installed: Boolean,
    downloadState: DownloadState,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = onSelect)
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    if (installed) {
                        Text(
                            text = "已安装",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when (downloadState) {
                is DownloadState.Downloading -> {
                    TextButton(onClick = onCancelDownload) {
                        Text("取消")
                    }
                }
                else -> {
                    if (installed) {
                        IconButton(onClick = onDelete) {
                            Icon(Icons.Rounded.Delete, contentDescription = "删除模型")
                        }
                    } else {
                        IconButton(onClick = onDownload) {
                            Icon(Icons.Rounded.Download, contentDescription = "下载模型")
                        }
                    }
                }
            }
        }
        if (downloadState is DownloadState.Downloading) {
            val progress = if (downloadState.totalBytes > 0L) {
                (downloadState.downloadedBytes.toFloat() / downloadState.totalBytes.toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            }
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            Text(
                text = "正在下载 (${downloadState.fileIndex}/${downloadState.totalFiles}) ${downloadState.fileName}: " +
                    "${formatBytes(downloadState.downloadedBytes)}" +
                    if (downloadState.totalBytes > 0) " / ${formatBytes(downloadState.totalBytes)}" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else if (downloadState is DownloadState.Failed) {
            Text(
                text = "下载失败：${downloadState.error}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

private fun openAllFilesPermission(context: android.content.Context) {
    val intent = Intent(
        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
        Uri.parse("package:${context.packageName}"),
    )
    runCatching { context.startActivity(intent) }.onFailure {
        context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
    }
}

private fun isImeEnabled(context: android.content.Context): Boolean {
    val imm = context.getSystemService(InputMethodManager::class.java) ?: return false
    return imm.enabledInputMethodList.any { it.packageName == context.packageName }
}

private fun isImeSelected(context: android.content.Context): Boolean {
    val current = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.DEFAULT_INPUT_METHOD,
    ) ?: return false
    return current.startsWith("${context.packageName}/")
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val gb = bytes / (1024.0 * 1024.0 * 1024.0)
    if (gb >= 1.0) return String.format(Locale.US, "%.2f GB", gb)
    val mb = bytes / (1024.0 * 1024.0)
    if (mb >= 1.0) return String.format(Locale.US, "%.1f MB", mb)
    val kb = bytes / 1024.0
    return String.format(Locale.US, "%.0f KB", kb)
}
