package com.litertime.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.litertime.android.pipeline.online.OnlineBalance
import com.litertime.android.pipeline.online.OnlineLlmApi
import com.litertime.android.pipeline.online.OnlineModelGroup
import com.litertime.android.pipeline.online.OnlineModelGroups
import com.litertime.android.pipeline.online.OnlineProviderDialect
import com.litertime.android.pipeline.online.OnlineRequestOptions
import com.litertime.android.pipeline.online.OnlineThinkingSupport
import com.litertime.android.pipeline.online.ThinkingEffort
import com.litertime.android.settings.ImeSettings
import com.litertime.android.settings.OnlineLlmModelProfile
import com.litertime.android.settings.ResolvedOnlineLlmConfig
import kotlinx.coroutines.launch

/**
 * The entire "在线大模型设置" page. The endpoint editor is intentionally reused for the unified
 * configuration and every per-mode override so all fields behave identically.
 */
@Composable
internal fun OnlineLlmSettingsPage(
    settings: ImeSettings,
    onUpdate: ((ImeSettings) -> ImeSettings) -> ImeSettings,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SettingsCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(Icons.Rounded.Cloud, contentDescription = null)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "优先使用在线大模型",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "AI 麦克风优先走 OpenAI 兼容在线接口；断网、请求超时或本地模型已加载时自动使用本地 Gemma 4。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = settings.onlineLlmPreferred,
                    onCheckedChange = { checked ->
                        onUpdate { it.copy(onlineLlmPreferred = checked) }
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color(PastelPalette.INK_ON_PASTEL),
                        checkedTrackColor = Color(PastelPalette.SKY_BLUE),
                        checkedBorderColor = Color.Transparent,
                        uncheckedThumbColor = Color(PastelPalette.TEXT_MUTED),
                        uncheckedTrackColor = Color(PastelPalette.SURFACE_CHIP_IDLE),
                        uncheckedBorderColor = Color.Transparent,
                    ),
                )
            }
            Text(
                text = "只支持 OpenAI 兼容协议。在线模型只接收 Whisper 识别出的文本与截图；Whisper 麦克风本身仍使用本地 ASR。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        OnlineEndpointEditor(
            title = "统一配置",
            description = "未开启分模式时，所有 AI 功能共用这套 Endpoint、API Key 与 Model。",
            baseUrl = settings.onlineBaseUrl,
            apiKey = settings.onlineApiKey,
            modelName = settings.onlineModelName,
            placeholderBaseUrl = "https://api.openai.com/v1",
            placeholderApiKey = "可留空",
            placeholderModel = "例如 gpt-4o-mini",
            effective = settings.onlineUnifiedConfig,
            inherited = null,
            maxTokens = settings.onlineMaxTokens,
            temperature = settings.onlineTemperature,
            thinkingEnabled = settings.onlineThinkingEnabled,
            thinkingEffort = settings.onlineThinkingEffort,
            providerDialect = settings.onlineProviderDialect,
            onBaseUrlChange = { value -> onUpdate { it.copy(onlineBaseUrl = value) } },
            onApiKeyChange = { value -> onUpdate { it.copy(onlineApiKey = value) } },
            onModelNameChange = { value -> onUpdate { it.copy(onlineModelName = value) } },
            onMaxTokensChange = { value ->
                onUpdate {
                    it.copy(
                        onlineMaxTokens = (value ?: ResolvedOnlineLlmConfig.DEFAULT_MAX_TOKENS)
                            .coerceIn(
                                ResolvedOnlineLlmConfig.MIN_MAX_TOKENS,
                                ResolvedOnlineLlmConfig.MAX_MAX_TOKENS,
                            ),
                    )
                }
            },
            onTemperatureChange = { value ->
                onUpdate {
                    it.copy(
                        onlineTemperature = (value ?: ResolvedOnlineLlmConfig.DEFAULT_TEMPERATURE)
                            .coerceIn(
                                ResolvedOnlineLlmConfig.MIN_TEMPERATURE,
                                ResolvedOnlineLlmConfig.MAX_TEMPERATURE,
                            ),
                    )
                }
            },
            onThinkingEnabledChange = { value ->
                if (value != null) onUpdate { it.copy(onlineThinkingEnabled = value) }
            },
            onThinkingEffortChange = { value ->
                if (value != null) onUpdate { it.copy(onlineThinkingEffort = value) }
            },
            onProviderDialectChange = { value ->
                onUpdate { it.copy(onlineProviderDialect = value) }
            },
        )

        SettingsCard {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        onUpdate { it.copy(onlinePerModeModels = !it.onlinePerModeModels) }
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Checkbox(
                    checked = settings.onlinePerModeModels,
                    onCheckedChange = { checked ->
                        onUpdate { it.copy(onlinePerModeModels = checked) }
                    },
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "为不同的模式使用不同的模型",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = "每个 AI 功能都可以使用完全不同的 Endpoint、API Key 与 Model。留空的字段沿用上面的统一配置。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (settings.onlinePerModeModels) {
            val unified = settings.onlineUnifiedConfig
            OnlineLlmModelProfile.entries.forEach { profile ->
                val override = settings.onlineOverride(profile)
                OnlineEndpointEditor(
                    title = profile.displayName,
                    description = "留空使用统一值；API Key 留空显示（默认）。",
                    baseUrl = override.baseUrl,
                    apiKey = override.apiKey,
                    modelName = override.modelName,
                    placeholderBaseUrl = unified.baseUrl.ifBlank { "统一 Endpoint 未配置" },
                    placeholderApiKey = "（默认）",
                    placeholderModel = unified.modelName.ifBlank { "统一 Model 未配置" },
                    effective = override.resolve(unified),
                    inherited = unified,
                    maxTokens = override.maxTokens,
                    temperature = override.temperature,
                    thinkingEnabled = override.thinkingEnabled,
                    thinkingEffort = override.thinkingEffort,
                    providerDialect = override.providerDialect,
                    onBaseUrlChange = { value ->
                        onUpdate { it.withOnlineOverride(profile, override.copy(baseUrl = value)) }
                    },
                    onApiKeyChange = { value ->
                        onUpdate { it.withOnlineOverride(profile, override.copy(apiKey = value)) }
                    },
                    onModelNameChange = { value ->
                        onUpdate { it.withOnlineOverride(profile, override.copy(modelName = value)) }
                    },
                    onMaxTokensChange = { value ->
                        onUpdate { it.withOnlineOverride(profile, override.copy(maxTokens = value)) }
                    },
                    onTemperatureChange = { value ->
                        onUpdate { it.withOnlineOverride(profile, override.copy(temperature = value)) }
                    },
                    onThinkingEnabledChange = { value ->
                        onUpdate {
                            it.withOnlineOverride(profile, override.copy(thinkingEnabled = value))
                        }
                    },
                    onThinkingEffortChange = { value ->
                        onUpdate {
                            it.withOnlineOverride(profile, override.copy(thinkingEffort = value))
                        }
                    },
                    onProviderDialectChange = { value ->
                        onUpdate {
                            it.withOnlineOverride(profile, override.copy(providerDialect = value))
                        }
                    },
                )
            }
        }
    }
}

/**
 * Endpoint/key/model editor used by the debug replay screen. It is deliberately the same
 * OpenAI-compatible filling/selection flow as the settings page, without the balance/test extras.
 */
@Composable
internal fun OnlineLlmReplayConfigDialog(
    initial: ResolvedOnlineLlmConfig,
    onDismiss: () -> Unit,
    onConfirm: (ResolvedOnlineLlmConfig) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var baseUrl by remember(initial) { mutableStateOf(initial.baseUrl) }
    var apiKey by remember(initial) { mutableStateOf(initial.apiKey) }
    var modelName by remember(initial) { mutableStateOf(initial.modelName) }
    var apiKeyVisible by remember { mutableStateOf(false) }
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var showModelPicker by remember { mutableStateOf(false) }
    var modelsLoading by remember { mutableStateOf(false) }
    var modelsError by remember { mutableStateOf<String?>(null) }
    var validationError by remember { mutableStateOf<String?>(null) }

    fun fetchModels() {
        if (baseUrl.isBlank()) {
            modelsError = "请先填写 Endpoint"
            return
        }
        if (models.isNotEmpty()) {
            showModelPicker = true
            return
        }
        scope.launch {
            modelsLoading = true
            modelsError = null
            runCatching { OnlineLlmApi.fetchModels(baseUrl, apiKey) }
                .onSuccess { fetched ->
                    if (fetched.isEmpty()) {
                        modelsError = "接口没有返回可用模型"
                    } else {
                        models = fetched
                        showModelPicker = true
                    }
                }
                .onFailure { error -> modelsError = error.message ?: "获取模型失败" }
            modelsLoading = false
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 620.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "其他云端大模型",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "仅用于本次回放。Endpoint 与 API Key 不会写入应用设置。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { value ->
                        baseUrl = value
                        models = emptyList()
                        modelsError = null
                        validationError = null
                    },
                    label = { Text("Endpoint") },
                    placeholder = { Text("https://api.openai.com/v1") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { value ->
                        apiKey = value
                        models = emptyList()
                        modelsError = null
                        validationError = null
                    },
                    label = { Text("API Key") },
                    placeholder = { Text("可留空") },
                    singleLine = true,
                    visualTransformation = if (apiKeyVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        IconButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                            Icon(
                                imageVector = if (apiKeyVisible) {
                                    Icons.Rounded.VisibilityOff
                                } else {
                                    Icons.Rounded.Visibility
                                },
                                contentDescription = if (apiKeyVisible) "隐藏 API Key" else "显示 API Key",
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = modelName,
                        onValueChange = { value ->
                            modelName = value
                            validationError = null
                        },
                        label = { Text("Model") },
                        placeholder = { Text("填写或从接口获取") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    FilledTonalButton(
                        onClick = ::fetchModels,
                        enabled = !modelsLoading,
                        colors = pastelTonalColors(),
                    ) {
                        Text(if (modelsLoading) "获取中…" else "获取模型")
                    }
                }
                modelsError?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                validationError?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    FilledTonalButton(
                        onClick = {
                            val base = OnlineLlmApi.normalizeBaseUrl(baseUrl)
                            when {
                                base.isBlank() -> validationError = "请填写 Endpoint"
                                modelName.isBlank() -> validationError = "请填写或选择 Model"
                                else -> onConfirm(
                                    ResolvedOnlineLlmConfig(
                                        baseUrl = base,
                                        apiKey = apiKey.trim(),
                                        modelName = modelName.trim(),
                                    ),
                                )
                            }
                        },
                        colors = pastelTonalColors(),
                    ) {
                        Text("使用此模型")
                    }
                }
            }
        }
    }

    if (showModelPicker) {
        OnlineModelPickerDialog(
            models = models,
            loading = modelsLoading,
            error = modelsError,
            onRefresh = {
                models = emptyList()
                fetchModels()
            },
            onSelect = { selected ->
                modelName = selected
                validationError = null
                showModelPicker = false
            },
            onDismiss = { showModelPicker = false },
        )
    }
}

@Composable
private fun OnlineEndpointEditor(
    title: String,
    description: String,
    baseUrl: String,
    apiKey: String,
    modelName: String,
    placeholderBaseUrl: String,
    placeholderApiKey: String,
    placeholderModel: String,
    effective: ResolvedOnlineLlmConfig,
    /** Non-null for a per-mode override: null fields inherit from this unified configuration. */
    inherited: ResolvedOnlineLlmConfig?,
    maxTokens: Int?,
    temperature: Double?,
    thinkingEnabled: Boolean?,
    thinkingEffort: ThinkingEffort?,
    providerDialect: OnlineProviderDialect,
    onBaseUrlChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onModelNameChange: (String) -> Unit,
    onMaxTokensChange: (Int?) -> Unit,
    onTemperatureChange: (Double?) -> Unit,
    onThinkingEnabledChange: (Boolean?) -> Unit,
    onThinkingEffortChange: (ThinkingEffort?) -> Unit,
    onProviderDialectChange: (OnlineProviderDialect) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var apiKeyVisible by remember { mutableStateOf(false) }
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var showModelPicker by remember { mutableStateOf(false) }
    var modelsLoading by remember { mutableStateOf(false) }
    var modelsError by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testSucceeded by remember { mutableStateOf(false) }
    var balance by remember { mutableStateOf<OnlineBalance?>(null) }
    var balanceLoading by remember { mutableStateOf(false) }
    var balanceError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(effective.baseUrl, effective.apiKey) {
        models = emptyList()
        modelsError = null
        balance = null
        balanceError = null
        testResult = null
        showModelPicker = false
    }

    fun fetchModels() {
        if (effective.baseUrl.isBlank()) {
            modelsError = "请先填写 Endpoint"
            return
        }
        if (models.isNotEmpty()) {
            showModelPicker = true
            return
        }
        scope.launch {
            modelsLoading = true
            modelsError = null
            runCatching { OnlineLlmApi.fetchModels(effective.baseUrl, effective.apiKey) }
                .onSuccess { fetched ->
                    if (fetched.isEmpty()) {
                        modelsError = "接口没有返回可用模型"
                    } else {
                        models = fetched
                        showModelPicker = true
                    }
                }
                .onFailure { error ->
                    modelsError = error.message ?: "获取模型失败"
                }
            modelsLoading = false
        }
    }

    fun testConnection() {
        if (effective.baseUrl.isBlank()) {
            testResult = "请先填写 Endpoint"
            testSucceeded = false
            return
        }
        if (effective.modelName.isBlank()) {
            testResult = "请先填写或获取 Model"
            testSucceeded = false
            return
        }
        scope.launch {
            testing = true
            testResult = null
            runCatching {
                OnlineLlmApi.testHello(
                    baseUrl = effective.baseUrl,
                    apiKey = effective.apiKey,
                    modelName = effective.modelName,
                    thinkingEnabled = effective.thinkingEnabled,
                    thinkingEffort = effective.thinkingEffort,
                    providerDialect = effective.providerDialect,
                )
            }.onSuccess { reply ->
                testSucceeded = true
                testResult = "连接成功：$reply"
            }.onFailure { error ->
                testSucceeded = false
                testResult = error.message ?: "连接失败"
            }
            testing = false
        }
    }

    fun refreshBalance() {
        if (effective.baseUrl.isBlank()) {
            balanceError = "请先填写 Endpoint"
            return
        }
        scope.launch {
            balanceLoading = true
            balanceError = null
            runCatching { OnlineLlmApi.fetchBalance(effective.baseUrl, effective.apiKey) }
                .onSuccess { balance = it }
                .onFailure { balanceError = it.message ?: "余额获取失败" }
            balanceLoading = false
        }
    }

    SettingsCard {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = baseUrl,
            onValueChange = { value ->
                models = emptyList()
                modelsError = null
                balance = null
                balanceError = null
                testResult = null
                onBaseUrlChange(value)
            },
            label = { Text("Endpoint") },
            placeholder = { Text(placeholderBaseUrl) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = apiKey,
            onValueChange = { value ->
                models = emptyList()
                modelsError = null
                balance = null
                balanceError = null
                testResult = null
                onApiKeyChange(value)
            },
            label = { Text("API Key") },
            placeholder = { Text(placeholderApiKey) },
            singleLine = true,
            visualTransformation = if (apiKeyVisible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            trailingIcon = {
                IconButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                    Icon(
                        imageVector = if (apiKeyVisible) {
                            Icons.Rounded.VisibilityOff
                        } else {
                            Icons.Rounded.Visibility
                        },
                        contentDescription = if (apiKeyVisible) "隐藏 API Key" else "显示 API Key",
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = modelName,
                onValueChange = { value ->
                    testResult = null
                    onModelNameChange(value)
                },
                label = { Text("Model") },
                placeholder = { Text(placeholderModel) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            FilledTonalButton(
                onClick = ::fetchModels,
                enabled = !modelsLoading,
                colors = pastelTonalColors(),
            ) {
                Text(if (modelsLoading) "获取中…" else "获取模型")
            }
        }
        modelsError?.let { error ->
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        GenerationParamsSection(
            effective = effective,
            inherited = inherited,
            maxTokens = maxTokens,
            temperature = temperature,
            thinkingEnabled = thinkingEnabled,
            thinkingEffort = thinkingEffort,
            providerDialect = providerDialect,
            onMaxTokensChange = onMaxTokensChange,
            onTemperatureChange = onTemperatureChange,
            onThinkingEnabledChange = onThinkingEnabledChange,
            onThinkingEffortChange = onThinkingEffortChange,
            onProviderDialectChange = onProviderDialectChange,
        )

        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant,
            thickness = 0.75.dp,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilledTonalButton(
                onClick = ::testConnection,
                enabled = !testing,
                colors = pastelTonalColors(),
            ) {
                Text(if (testing) "测试中…" else "测试连接")
            }
            Text(
                text = if (testing) "正在发送 Hello…" else "发送一条 Hello 验证配置",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        testResult?.let { result ->
            Text(
                text = result,
                style = MaterialTheme.typography.bodySmall,
                color = if (testSucceeded) {
                    Color(PastelPalette.MINT)
                } else {
                    Color(PastelPalette.ROSE_DANGER)
                },
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = balance?.primary ?: "余额：尚未获取",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                if (!balance?.detail.isNullOrBlank()) {
                    Text(
                        text = balance?.detail.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                balanceError?.let { error ->
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            IconButton(onClick = ::refreshBalance, enabled = !balanceLoading) {
                if (balanceLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Rounded.Refresh, contentDescription = "刷新余额")
                }
            }
            TextButton(onClick = ::refreshBalance, enabled = !balanceLoading) {
                Text(if (balance == null) "获取余额" else "刷新")
            }
        }
    }

    if (showModelPicker) {
        OnlineModelPickerDialog(
            models = models,
            loading = modelsLoading,
            error = modelsError,
            onRefresh = {
                models = emptyList()
                fetchModels()
            },
            onSelect = { selected ->
                testResult = null
                onModelNameChange(selected)
                showModelPicker = false
            },
            onDismiss = { showModelPicker = false },
        )
    }
}

/**
 * Max Tokens / Temperature plus the thinking switch and effort level. The thinking controls are
 * only rendered for models that expose a thinking configuration; per-mode overrides can inherit
 * every value from the unified configuration.
 */
@Composable
private fun GenerationParamsSection(
    effective: ResolvedOnlineLlmConfig,
    inherited: ResolvedOnlineLlmConfig?,
    maxTokens: Int?,
    temperature: Double?,
    thinkingEnabled: Boolean?,
    thinkingEffort: ThinkingEffort?,
    providerDialect: OnlineProviderDialect,
    onMaxTokensChange: (Int?) -> Unit,
    onTemperatureChange: (Double?) -> Unit,
    onThinkingEnabledChange: (Boolean?) -> Unit,
    onThinkingEffortChange: (ThinkingEffort?) -> Unit,
    onProviderDialectChange: (OnlineProviderDialect) -> Unit,
) {
    var maxTokensText by remember { mutableStateOf(maxTokens?.toString().orEmpty()) }
    var temperatureText by remember { mutableStateOf(temperature?.toString().orEmpty()) }
    val supportsThinking = remember(effective.modelName) {
        OnlineThinkingSupport.supportsThinking(effective.modelName)
    }
    val autoDialect = remember(effective.baseUrl, effective.modelName) {
        OnlineRequestOptions.detectDialect(effective.baseUrl, effective.modelName)
    }
    val resolvedThinkingEnabled = thinkingEnabled ?: inherited?.thinkingEnabled ?: false
    val resolvedThinkingEffort = thinkingEffort ?: inherited?.thinkingEffort ?: ThinkingEffort.MEDIUM

    Text(
        text = "生成参数",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = maxTokensText,
            onValueChange = { raw ->
                val digits = raw.filter { it.isDigit() }.take(6)
                val parsed = digits.toIntOrNull()
                val clamped = parsed?.coerceIn(
                    ResolvedOnlineLlmConfig.MIN_MAX_TOKENS,
                    ResolvedOnlineLlmConfig.MAX_MAX_TOKENS,
                )
                maxTokensText = if (parsed != null && clamped != parsed) {
                    clamped.toString()
                } else {
                    digits
                }
                onMaxTokensChange(clamped)
            },
            label = { Text("Max Tokens") },
            placeholder = {
                Text(
                    (inherited?.maxTokens ?: ResolvedOnlineLlmConfig.DEFAULT_MAX_TOKENS).toString(),
                )
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = temperatureText,
            onValueChange = { raw ->
                val filtered = filterTemperatureInput(raw)
                val parsed = filtered.toDoubleOrNull()
                val clamped = parsed?.coerceIn(
                    ResolvedOnlineLlmConfig.MIN_TEMPERATURE,
                    ResolvedOnlineLlmConfig.MAX_TEMPERATURE,
                )
                temperatureText = if (parsed != null && clamped != parsed) {
                    clamped.toString()
                } else {
                    filtered
                }
                onTemperatureChange(clamped)
            },
            label = { Text("Temperature") },
            placeholder = {
                Text(
                    (inherited?.temperature ?: ResolvedOnlineLlmConfig.DEFAULT_TEMPERATURE)
                        .toString(),
                )
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.weight(1f),
        )
    }
    Text(
        text = "Max Tokens 限制单次回复长度（默认 8000）；Temperature 越低越稳定（默认 0.35）。" +
            if (inherited != null) "留空沿用统一配置。" else "",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    ProviderDialectSelector(
        selected = providerDialect,
        autoDetected = autoDialect,
        onSelect = onProviderDialectChange,
    )
    Text(
        text = "自动会按 Endpoint 域名与模型名识别思考参数格式；使用代理或自定义模型名时可手动指定。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    if (supportsThinking) {
        if (inherited == null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "启用思考",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = "关闭后不额外发送思考参数；部分服务商默认仍会思考。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = resolvedThinkingEnabled,
                    onCheckedChange = onThinkingEnabledChange,
                    colors = pastelSwitchColors(),
                )
            }
        } else {
            Text(
                text = "启用思考",
                style = MaterialTheme.typography.labelLarge,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SettingsChip(
                    selected = thinkingEnabled == null,
                    onClick = { onThinkingEnabledChange(null) },
                ) { Text("跟随统一") }
                SettingsChip(
                    selected = thinkingEnabled == true,
                    onClick = { onThinkingEnabledChange(true) },
                ) { Text("开启") }
                SettingsChip(
                    selected = thinkingEnabled == false,
                    onClick = { onThinkingEnabledChange(false) },
                ) { Text("关闭") }
            }
            Text(
                text = "「跟随统一」使用统一配置的思考开关。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (resolvedThinkingEnabled) {
            Text(
                text = "思考强度",
                style = MaterialTheme.typography.labelLarge,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (inherited != null) {
                    SettingsChip(
                        selected = thinkingEffort == null,
                        onClick = { onThinkingEffortChange(null) },
                    ) { Text("跟随统一") }
                }
                ThinkingEffort.entries.forEach { effort ->
                    SettingsChip(
                        selected = if (inherited != null) {
                            thinkingEffort == effort
                        } else {
                            resolvedThinkingEffort == effort
                        },
                        onClick = { onThinkingEffortChange(effort) },
                    ) { Text(effort.displayName) }
                }
            }
        }
    } else {
        Text(
            text = if (effective.modelName.isBlank()) {
                "选择模型后显示思考参数（仅支持思考的模型可配置）。"
            } else {
                "当前模型不支持思考参数配置。"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Provider adaptation dropdown. "自动" shows the dialect the heuristics currently detect. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderDialectSelector(
    selected: OnlineProviderDialect,
    autoDetected: OnlineProviderDialect,
    onSelect: (OnlineProviderDialect) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    fun labelFor(dialect: OnlineProviderDialect): String =
        if (dialect == OnlineProviderDialect.AUTO) {
            "自动（${autoDetected.displayName}）"
        } else {
            dialect.displayName
        }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = labelFor(selected),
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text("服务商适配") },
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
            },
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            OnlineProviderDialect.entries.forEach { dialect ->
                DropdownMenuItem(
                    text = { Text(labelFor(dialect)) },
                    onClick = {
                        onSelect(dialect)
                        expanded = false
                    },
                )
            }
        }
    }
}

/** Digits plus at most one decimal point, capped to a sane length. */
private fun filterTemperatureInput(raw: String): String = buildString {
    var hasDot = false
    for (ch in raw) {
        if (length >= 5) break
        when {
            ch.isDigit() -> append(ch)
            ch == '.' && !hasDot -> {
                hasDot = true
                append(ch)
            }
        }
    }
}

@Composable
private fun SettingsChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = label,
        border = null,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Color(PastelPalette.SURFACE_CHIP_IDLE),
            labelColor = Color(PastelPalette.TEXT_ON_DARK_SKY),
            selectedContainerColor = Color(PastelPalette.SKY_BLUE),
            selectedLabelColor = Color(PastelPalette.INK_ON_PASTEL),
        ),
    )
}

@Composable
private fun pastelSwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = Color(PastelPalette.INK_ON_PASTEL),
    checkedTrackColor = Color(PastelPalette.SKY_BLUE),
    checkedBorderColor = Color.Transparent,
    uncheckedThumbColor = Color(PastelPalette.TEXT_MUTED),
    uncheckedTrackColor = Color(PastelPalette.SURFACE_CHIP_IDLE),
    uncheckedBorderColor = Color.Transparent,
)

@Composable
private fun OnlineModelPickerDialog(
    models: List<String>,
    loading: Boolean,
    error: String?,
    onRefresh: () -> Unit,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val groups = remember(models) { OnlineModelGroups.group(models) }
    var query by remember { mutableStateOf("") }
    var expandedVendors by remember { mutableStateOf<Set<String>>(emptySet()) }

    val visibleGroups = remember(groups, query) {
        val needle = query.trim()
        if (needle.isEmpty()) {
            groups
        } else {
            groups.mapNotNull { group ->
                val matches = group.models.filter { it.contains(needle, ignoreCase = true) }
                if (matches.isEmpty()) null else group.copy(models = matches)
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "选择模型",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onRefresh, enabled = !loading) {
                        if (loading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(Icons.Rounded.Refresh, contentDescription = "刷新模型")
                        }
                    }
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    leadingIcon = {
                        Icon(Icons.Rounded.Search, contentDescription = null)
                    },
                    placeholder = { Text("搜索模型") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (loading && models.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                } else {
                    LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                        visibleGroups.forEach { group ->
                            val expanded = query.isNotBlank() || group.vendor in expandedVendors
                            item(key = "vendor-${group.vendor}") {
                                VendorHeader(
                                    group = group,
                                    expanded = expanded,
                                    onClick = {
                                        expandedVendors = if (expanded) {
                                            expandedVendors - group.vendor
                                        } else {
                                            expandedVendors + group.vendor
                                        }
                                    },
                                )
                            }
                            if (expanded) {
                                items(items = group.models, key = { it }) { model ->
                                    Text(
                                        text = model,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { onSelect(model) }
                                            .padding(horizontal = 16.dp, vertical = 10.dp),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                        if (visibleGroups.isEmpty() && !loading) {
                            item {
                                Text(
                                    text = if (models.isEmpty()) "还没有模型，点击右上角刷新" else "没有匹配的模型",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 12.dp),
                                )
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
            }
        }
    }
}

@Composable
private fun VendorHeader(
    group: OnlineModelGroup,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = group.vendor,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = group.models.size.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Icon(
            imageVector = if (expanded) {
                Icons.Rounded.KeyboardArrowUp
            } else {
                Icons.Rounded.KeyboardArrowDown
            },
            contentDescription = null,
        )
    }
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun pastelTonalColors() = ButtonDefaults.filledTonalButtonColors(
    containerColor = Color(PastelPalette.SURFACE_CHIP_IDLE),
    contentColor = Color(PastelPalette.TEXT_PRIMARY),
    disabledContainerColor = Color(PastelPalette.SURFACE_MOD_KEY),
    disabledContentColor = Color(PastelPalette.TEXT_MUTED),
)

