package com.litertime.android.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Screenshot
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.litertime.android.BuildConfig
import com.litertime.android.LiteRtImeApp
import com.litertime.android.accessibility.ScreenCaptureAccessibilityService
import com.litertime.android.inference.InferenceClient
import com.litertime.android.settings.LlmProviderType
import com.litertime.android.storage.DownloadMirror
import com.litertime.android.storage.DownloadState
import com.litertime.android.storage.ModelCatalog
import com.litertime.android.storage.ModelDownloader
import com.litertime.android.storage.StorageAccess
import com.litertime.android.storage.StorageMode
import java.io.File
import java.util.Locale

class MainActivity : ComponentActivity() {
    private lateinit var inferenceClient: InferenceClient
    private lateinit var modelDownloader: ModelDownloader

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as LiteRtImeApp
        inferenceClient = InferenceClient(this)
        inferenceClient.bind()
        modelDownloader = ModelDownloader(app.modelStorage)

        setContent {
            MaterialTheme {
                LiteRtImeSettingsScreen(
                    app = app,
                    inferenceClient = inferenceClient,
                    downloader = modelDownloader,
                )
            }
        }
    }

    override fun onDestroy() {
        inferenceClient.unbind()
        super.onDestroy()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LiteRtImeSettingsScreen(
    app: LiteRtImeApp,
    inferenceClient: InferenceClient,
    downloader: ModelDownloader,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val storage = app.modelStorage
    val settingsStore = app.settingsStore

    var refreshTick by remember { mutableIntStateOf(0) }
    var settings by remember(refreshTick) { mutableStateOf(settingsStore.getSettings()) }
    var storageConfig by remember(refreshTick) { mutableStateOf(storage.getConfig()) }
    var storageError by remember { mutableStateOf<String?>(null) }

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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("LiteRT 语音输入法设置") },
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
            // 1. Setup & Permissions
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
                        a11yConnected -> "已连接（支持拖至「截屏」执行指令）"
                        a11yEnabled -> "系统已启用，等待连接"
                        else -> "拖选 Gemma 4「截屏指令」时需开启一次"
                    },
                    granted = a11yConnected || a11yEnabled,
                    actionLabel = if (a11yConnected) "已开启" else "去开启",
                    onAction = {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    },
                )
            }

            // 2. Shared Model Storage (model-storage.md)
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

            // 3. Resident Inference Service Status
            SectionCard(title = "3. 独立推理引擎状态 (:inference)") {
                Text(
                    text = "状态：${inferenceState.statusText}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "当前驻留：${inferenceState.loadedEngineType} ${inferenceState.loadedModelId.ifBlank { "(空)" }}（Whisper 与 Gemma 互斥驻留，加载后不主动卸载）",
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

            // 4. Mirror & Model Management
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
                            if (inferenceState.loadedModelId == spec.id) {
                                inferenceClient.unloadModel()
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
                            if (inferenceState.loadedModelId == spec.id) {
                                inferenceClient.unloadModel()
                            }
                            storage.deleteAsrModel(spec.id)
                            refreshTick++
                        },
                    )
                }
            }

            // 5. Reserved Online LLM Settings
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

            Spacer(Modifier.height(24.dp))
        }
    }
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
