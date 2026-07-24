package com.lv999call.app.ui.settings

import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.lv999call.app.audio.VoskModelManager
import com.lv999call.app.domain.model.ApiConfig
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    config: ApiConfig,
    voskModels: List<VoskModelManager.VoskModel>,
    voskDownloadState: SettingsViewModel.VoskDownloadState,
    onSave: (ApiConfig) -> Unit,
    onFetchModels: suspend (baseUrl: String, apiKey: String) -> Pair<List<String>, Int>,
    onDownloadVoskModel: (VoskModelManager.VoskModel) -> Unit,
    onDeleteVoskModel: (String) -> Unit,
    onResetDownloadState: () -> Unit,
    onBack: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val shapes = MaterialTheme.shapes
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var llmBaseUrl by remember(config) { mutableStateOf(config.llmBaseUrl) }
    var llmApiKey by remember(config) { mutableStateOf(config.llmApiKey) }
    var llmModel by remember(config) { mutableStateOf(config.llmModel) }
    var maxContextTokens by remember(config) { mutableStateOf(config.maxContextTokens.toFloat()) }
    var apiMaxContext by remember { mutableStateOf(200000) } // 从API读取的上限

    var asrProvider by remember(config) { mutableStateOf(config.asrProvider) }
    var asrBaseUrl by remember(config) { mutableStateOf(config.asrBaseUrl) }
    var asrApiKey by remember(config) { mutableStateOf(config.asrApiKey) }
    var asrLanguage by remember(config) { mutableStateOf(config.asrLanguage) }
    var asrVoskModelId by remember(config) { mutableStateOf(config.asrVoskModelId) }

    var ttsBaseUrl by remember(config) { mutableStateOf(config.ttsBaseUrl) }
    var ttsApiKey by remember(config) { mutableStateOf(config.ttsApiKey) }
    var ttsModel by remember(config) { mutableStateOf(config.ttsModel) }
    var ttsSpeed by remember(config) { mutableFloatStateOf(config.ttsSpeed) }

    var showApiKey by remember { mutableStateOf(false) }
    var waitTts by remember(config) { mutableStateOf(config.waitTtsBeforeRecord) }
    val scrollState = rememberScrollState()

    var modelList by remember { mutableStateOf<List<String>>(emptyList()) }
    var showModelDialog by remember { mutableStateOf(false) }
    var isLoadingModels by remember { mutableStateOf(false) }
    var modelDialogTarget by remember { mutableStateOf("tts") } // "llm" or "tts"

    // Vosk 下载完成提示
    LaunchedEffect(voskDownloadState) {
        if (voskDownloadState is SettingsViewModel.VoskDownloadState.Success) {
            asrVoskModelId = voskDownloadState.modelId
            onResetDownloadState()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(colors.background)) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = colors.onSurface) }
                Text("设置", style = MaterialTheme.typography.titleLarge, color = colors.onSurface, modifier = Modifier.weight(1f))
            }

            Column(
                modifier = Modifier.weight(1f).verticalScroll(scrollState).padding(horizontal = 24.dp)
            ) {
                // ===== LLM =====
                SectionHeader(title = "🤖 LLM 大语言模型")
                SettingsTextField("Base URL", llmBaseUrl, { llmBaseUrl = it }, "https://api.groq.com/openai")
                SettingsTextField("API Key", llmApiKey, { llmApiKey = it }, isPassword = !showApiKey, placeholder = "gsk_xxx...")

                // LLM 模型名称 + 获取按钮
                Text("模型名称", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = llmModel, onValueChange = { llmModel = it }, modifier = Modifier.weight(1f),
                        placeholder = { Text("groq/llama3-70b-8192", color = colors.onSurfaceVariant.copy(alpha = 0.4f)) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = colors.primary, unfocusedBorderColor = colors.outline,
                            focusedTextColor = colors.onSurface, unfocusedTextColor = colors.onSurface, cursorColor = colors.tertiary
                        ),
                        shape = shapes.small
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    FilledTonalIconButton(
                        onClick = {
                            isLoadingModels = true
                            modelDialogTarget = "llm"
                            scope.launch {
                                val (models, maxCtx) = onFetchModels(llmBaseUrl, llmApiKey)
                                modelList = models
                                apiMaxContext = maxCtx
                                isLoadingModels = false
                                if (modelList.isNotEmpty()) showModelDialog = true
                            }
                        },
                        enabled = !isLoadingModels && llmBaseUrl.isNotBlank() && llmApiKey.isNotBlank()
                    ) {
                        if (isLoadingModels && modelDialogTarget == "llm") {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = colors.primary)
                        } else {
                            Icon(Icons.Default.Refresh, "获取模型列表", modifier = Modifier.size(22.dp))
                        }
                    }
                }

                // 上下文窗口大小滑块
                Spacer(modifier = Modifier.height(8.dp))
                val contextK = (maxContextTokens / 1000).toInt()
                Text("上下文窗口: ${contextK}K tokens", style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
                Slider(
                    value = maxContextTokens,
                    onValueChange = { maxContextTokens = it },
                    valueRange = 4000f..apiMaxContext.toFloat(),
                    steps = 0,
                    colors = SliderDefaults.colors(thumbColor = colors.primary, activeTrackColor = colors.primary)
                )
                Text(
                    text = "模型上限: ${apiMaxContext / 1000}K（获取模型后自动更新）",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant.copy(alpha = 0.6f)
                )

                Spacer(modifier = Modifier.height(24.dp))

                // ===== ASR =====
                SectionHeader(title = "🎤 ASR 语音识别")
                Text("服务商", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 12.dp)) {
                    FilterChip(selected = asrProvider == "custom", onClick = { asrProvider = "custom" }, label = { Text("自定义HTTP") },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = colors.primary.copy(alpha = 0.2f), selectedLabelColor = colors.primary))
                    FilterChip(selected = asrProvider == "vosk", onClick = { asrProvider = "vosk" }, label = { Text("Vosk离线") },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = colors.primary.copy(alpha = 0.2f), selectedLabelColor = colors.primary))
                }

                if (asrProvider == "custom") {
                    SettingsTextField("ASR URL", asrBaseUrl, { asrBaseUrl = it }, "https://api.openai.com/v1/audio/transcriptions")
                    SettingsTextField("ASR API Key", asrApiKey, { asrApiKey = it }, isPassword = !showApiKey, placeholder = "sk-xxx...")
                }

                // Vosk 离线模型管理
                if (asrProvider == "vosk") {
                    Text("离线模型", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))

                    voskModels.forEach { model ->
                        val isDownloaded = voskModels.any { it.id == model.id }
                        val isSelected = asrVoskModelId == model.id
                        val isDownloading = voskDownloadState is SettingsViewModel.VoskDownloadState.Downloading
                            && voskDownloadState.modelId == model.id
                        val downloadProgress = if (isDownloading) (voskDownloadState as SettingsViewModel.VoskDownloadState.Downloading).progress else 0f

                        Card(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                            shape = shapes.medium,
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelected) colors.primaryContainer.copy(alpha = 0.3f) else colors.surfaceContainer
                            )
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = model.displayName,
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = if (isSelected) colors.primary else colors.onSurface
                                        )
                                        Text(
                                            text = "${model.lang} · ${model.size}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = colors.onSurfaceVariant
                                        )
                                    }

                                    if (isDownloading) {
                                        CircularProgressIndicator(
                                            progress = { downloadProgress },
                                            modifier = Modifier.size(28.dp),
                                            strokeWidth = 3.dp,
                                            color = colors.primary
                                        )
                                    } else {
                                        // 选择按钮
                                        RadioButton(
                                            selected = isSelected,
                                            onClick = { asrVoskModelId = model.id },
                                            colors = RadioButtonDefaults.colors(selectedColor = colors.primary)
                                        )
                                    }
                                }

                                // 下载进度条
                                if (isDownloading) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    LinearProgressIndicator(
                                        progress = { downloadProgress },
                                        modifier = Modifier.fillMaxWidth(),
                                        color = colors.primary,
                                        trackColor = colors.surfaceVariant
                                    )
                                    Text(
                                        text = "下载中 ${(downloadProgress * 100).toInt()}%",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = colors.onSurfaceVariant,
                                        modifier = Modifier.padding(top = 4.dp)
                                    )
                                }

                                // 操作按钮
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                    horizontalArrangement = Arrangement.End
                                ) {
                                    if (!isDownloading) {
                                        if (!isDownloaded) {
                                            FilledTonalButton(
                                                onClick = { onDownloadVoskModel(model) },
                                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                                            ) {
                                                Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text("下载", style = MaterialTheme.typography.labelMedium)
                                            }
                                        } else {
                                            Text(
                                                text = "已下载",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = colors.tertiary,
                                                modifier = Modifier.padding(end = 8.dp, top = 8.dp)
                                            )
                                            IconButton(
                                                onClick = { onDeleteVoskModel(model.id) },
                                                modifier = Modifier.size(32.dp)
                                            ) {
                                                Icon(Icons.Default.Delete, "删除", tint = colors.error, modifier = Modifier.size(18.dp))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 下载失败提示
                    if (voskDownloadState is SettingsViewModel.VoskDownloadState.Error) {
                        Text(
                            text = "⚠ 下载失败: ${voskDownloadState.message}",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.error,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }

                SettingsTextField("语言", asrLanguage, { asrLanguage = it }, "zh-CN")

                Spacer(modifier = Modifier.height(24.dp))

                // ===== TTS (MiMo) =====
                SectionHeader(title = "🔊 TTS 语音合成 (MiMo-VoiceClone)")
                SettingsTextField("TTS URL", ttsBaseUrl, { ttsBaseUrl = it }, "https://api.xiaomimimo.com")
                SettingsTextField("API Key", ttsApiKey, { ttsApiKey = it }, isPassword = !showApiKey, placeholder = "your-mimo-api-key")

                // 模型名称 + 获取按钮
                Text("模型名称", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = ttsModel, onValueChange = { ttsModel = it }, modifier = Modifier.weight(1f),
                        placeholder = { Text("mimo-v2.5-tts-voiceclone", color = colors.onSurfaceVariant.copy(alpha = 0.4f)) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = colors.primary, unfocusedBorderColor = colors.outline,
                            focusedTextColor = colors.onSurface, unfocusedTextColor = colors.onSurface, cursorColor = colors.tertiary
                        ),
                        shape = shapes.small
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    FilledTonalIconButton(
                        onClick = {
                            isLoadingModels = true
                            modelDialogTarget = "tts"
                            scope.launch {
                                val (models, _) = onFetchModels(ttsBaseUrl, ttsApiKey)
                                modelList = models
                                isLoadingModels = false
                                if (modelList.isNotEmpty()) showModelDialog = true
                            }
                        },
                        enabled = !isLoadingModels && ttsBaseUrl.isNotBlank() && ttsApiKey.isNotBlank()
                    ) {
                        if (isLoadingModels && modelDialogTarget == "tts") {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = colors.primary)
                        } else {
                            Icon(Icons.Default.Refresh, "获取模型列表", modifier = Modifier.size(22.dp))
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text("语速: ${"%.1f".format(ttsSpeed)}x", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
                Slider(value = ttsSpeed, onValueChange = { ttsSpeed = it }, valueRange = 0.5f..2.0f, steps = 14,
                    colors = SliderDefaults.colors(thumbColor = colors.primary, activeTrackColor = colors.primary))

                Spacer(modifier = Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = showApiKey, onCheckedChange = { showApiKey = it }, colors = SwitchDefaults.colors(checkedTrackColor = colors.primary))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("显示API Key", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = waitTts, onCheckedChange = { waitTts = it }, colors = SwitchDefaults.colors(checkedTrackColor = colors.primary))
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text("TTS播完再录音", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                        Text("关闭可降低延迟，但可能录到TTS声音", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant.copy(alpha = 0.6f))
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = {
                        onSave(config.copy(
                            llmBaseUrl = llmBaseUrl, llmApiKey = llmApiKey, llmModel = llmModel,
                            maxContextTokens = maxContextTokens.toInt(),
                            asrProvider = asrProvider, asrBaseUrl = asrBaseUrl, asrApiKey = asrApiKey,
                            asrLanguage = asrLanguage, asrVoskModelId = asrVoskModelId,
                            ttsBaseUrl = ttsBaseUrl, ttsApiKey = ttsApiKey, ttsModel = ttsModel, ttsSpeed = ttsSpeed,
                            ttsReferenceAudioBase64 = "", ttsReferenceAudioMime = "audio/wav",
                            waitTtsBeforeRecord = waitTts
                        ))
                    },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = shapes.medium,
                    colors = ButtonDefaults.buttonColors(containerColor = colors.primary)
                ) {
                    Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("保存设置", fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }

    // 模型选择弹窗（LLM / TTS 共用）
    if (showModelDialog) {
        val currentSelected = if (modelDialogTarget == "llm") llmModel else ttsModel
        val dialogTitle = if (modelDialogTarget == "llm") "选择LLM模型" else "选择TTS模型"

        AlertDialog(
            onDismissRequest = { showModelDialog = false },
            title = { Text(dialogTitle) },
            text = {
                Column {
                    modelList.forEach { modelId ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable {
                                if (modelDialogTarget == "llm") llmModel = modelId else ttsModel = modelId
                                showModelDialog = false
                            }.padding(vertical = 12.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = currentSelected == modelId,
                                onClick = {
                                    if (modelDialogTarget == "llm") llmModel = modelId else ttsModel = modelId
                                    showModelDialog = false
                                },
                                colors = RadioButtonDefaults.colors(selectedColor = colors.primary)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(modelId, style = MaterialTheme.typography.bodyLarge,
                                color = if (currentSelected == modelId) colors.primary else colors.onSurface)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showModelDialog = false }) { Text("取消") } }
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(text = title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.tertiary,
        fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 12.dp, top = 8.dp))
}

@Composable
private fun SettingsTextField(
    label: String, value: String, onValueChange: (String) -> Unit,
    placeholder: String = "", isPassword: Boolean = false, keyboardType: KeyboardType = KeyboardType.Text
) {
    val colors = MaterialTheme.colorScheme
    val shapes = MaterialTheme.shapes
    Column(modifier = Modifier.padding(bottom = 12.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
        OutlinedTextField(
            value = value, onValueChange = onValueChange, modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(placeholder, color = colors.onSurfaceVariant.copy(alpha = 0.4f)) },
            visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType), singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = colors.primary, unfocusedBorderColor = colors.outline,
                focusedTextColor = colors.onSurface, unfocusedTextColor = colors.onSurface, cursorColor = colors.tertiary
            ),
            shape = shapes.small
        )
    }
}
