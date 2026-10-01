package com.lv999call.app.ui.settings

import android.Manifest
import android.os.Build
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
import androidx.compose.material.icons.filled.MusicNote
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
import com.lv999call.app.notify.MemoryReminderScheduler
import com.lv999call.app.notify.ReminderNotifier
import com.lv999call.app.ui.common.Live2DAuthorCredit
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    config: ApiConfig,
    voskModels: List<VoskModelManager.VoskModel>,
    voskDownloadState: SettingsViewModel.VoskDownloadState,
    isModelDownloaded: (String) -> Boolean = { false },
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
    var apiMaxContext by remember(config) { mutableStateOf(config.maxContextTokens.coerceAtLeast(200000)) }
    var temperature by remember(config) { mutableStateOf(config.llmTemperature) }
    var topP by remember(config) { mutableStateOf(config.llmTopP) }
    var maxOutputTokens by remember(config) { mutableStateOf(config.llmMaxOutputTokens.toFloat()) }

    var asrProvider by remember(config) { mutableStateOf(config.asrProvider) }
    var asrBaseUrl by remember(config) { mutableStateOf(config.asrBaseUrl) }
    var asrApiKey by remember(config) { mutableStateOf(config.asrApiKey) }
    var asrModel by remember(config) { mutableStateOf(config.asrModel) }
    var asrLanguage by remember(config) { mutableStateOf(config.asrLanguage) }
    var asrVoskModelId by remember(config) { mutableStateOf(config.asrVoskModelId) }

    var ttsApiKey by remember(config) { mutableStateOf(config.ttsApiKey) }
    var ttsModel by remember(config) { mutableStateOf(config.ttsModel) }
    // 朗读超时：滑杆吃 Float，存的是 Int 秒；保存时统一过 clamp
    var ttsPlaybackTimeoutSec by remember(config) { mutableStateOf(config.ttsPlaybackTimeoutSec.toFloat()) }

    var showApiKey by remember { mutableStateOf(false) }
    var live2dEnabled by remember(config) { mutableStateOf(config.live2dEnabled) }
    var live2dTransformEnabled by remember(config) { mutableStateOf(config.live2dTransformEnabled) }
    // 声音跟着情绪走（默认开）。它是 Live2D 的子开关：表情标签协议只在 Live2D 打开时注入，
    // 关掉 Live2D 后它无从生效，所以跟着 Live2D 一起隐藏
    var emotionVoiceEnabled by remember(config) { mutableStateOf(config.emotionVoiceEnabled) }
    // 长期记忆两个开关（plan4 §5.7）
    var memoryAutoSummarizeEnabled by remember(config) { mutableStateOf(config.memoryAutoSummarizeEnabled) }
    var memorySummarizeShortCalls by remember(config) { mutableStateOf(config.memorySummarizeShortCalls) }
    // 记忆提醒通知（默认关）。它是子开关，只在记忆总开关打开时显示
    var memoryReminderEnabled by remember(config) { mutableStateOf(config.memoryReminderEnabled) }
    val scrollState = rememberScrollState()

    var modelList by remember { mutableStateOf<List<String>>(emptyList()) }
    var showModelDialog by remember { mutableStateOf(false) }
    var isLoadingModels by remember { mutableStateOf(false) }
    var modelDialogTarget by remember { mutableStateOf("tts") } // "llm" or "tts"
    var fetchError by remember { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    /**
     * 通知权限请求（API 33+ 的 POST_NOTIFICATIONS）。
     *
     * ⚠️ 这是本项目**第一处**运行时权限请求（RECORD_AUDIO 那条只 checkSelfPermission、
     * 从不 request），所以这套"开关 + 请求 + 拒绝回滚"的写法是新立的：
     * · 请求只在用户主动打开开关时发起（不是一进设置页就弹，那属于骚扰）；
     * · **被拒绝时绝不能把开关留在打开状态** —— 那样用户以为开好了，而 Worker 每 12 小时
     *   检查一次权限、永远静默跳过，表现为"功能是坏的"。宁可回滚开关 + 明确告诉用户怎么办。
     */
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        // granted 只代表"权限对话框的结果"；系统里的通知总开关/渠道屏蔽是另一回事，
        // 所以这里再走一遍 canPost，避免"权限给了但通知仍被关着"时把开关误置为开
        if (granted && ReminderNotifier.canPost(context)) {
            memoryReminderEnabled = true
            MemoryReminderScheduler.sync(context, true)
        } else {
            memoryReminderEnabled = false
            scope.launch {
                snackbarHostState.showSnackbar(
                    if (!granted) {
                        "没有通知权限，提醒发不出来。可在系统设置里为「LV999」开启通知后重试"
                    } else {
                        "系统里通知被关闭了（或屏蔽了「角色的提醒」），请到系统设置里打开"
                    }
                )
            }
        }
    }

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
                                try {
                                    val (models, maxCtx) = onFetchModels(llmBaseUrl, llmApiKey)
                                    modelList = models
                                    apiMaxContext = maxCtx
                                    if (modelList.isNotEmpty()) {
                                        showModelDialog = true
                                    } else {
                                        snackbarHostState.showSnackbar("该接口未返回模型列表")
                                    }
                                } catch (e: Exception) {
                                    fetchError = "获取模型失败: ${e.message?.take(80)}"
                                    snackbarHostState.showSnackbar(fetchError ?: "未知错误")
                                } finally {
                                    isLoadingModels = false
                                }
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

                // ===== 采样参数 =====
                // 只保留 OpenAI 兼容接口普遍支持的三个（温度 / top_p / 最大输出），
                // 各家私有开关不进这里，避免在不支持的模型上直接 400。
                Spacer(modifier = Modifier.height(16.dp))
                ParamSlider(
                    label = "温度 Temperature",
                    hint = "越低越稳定保守，越高越发散有创意。角色扮演建议 0.6~0.9",
                    valueText = "%.2f".format(temperature),
                    value = temperature,
                    onValueChange = { temperature = it },
                    valueRange = 0f..2f,
                    steps = 19,
                    onReset = { temperature = 0.7f }
                )
                ParamSlider(
                    label = "核采样 Top P",
                    hint = "候选词的累计概率上限。一般保持 1.0，只调温度即可",
                    valueText = "%.2f".format(topP),
                    value = topP,
                    onValueChange = { topP = it },
                    valueRange = 0.1f..1f,
                    steps = 17,
                    onReset = { topP = 1.0f }
                )
                ParamSlider(
                    label = "最大输出长度",
                    hint = "单次回复的 token 上限。写太长会拖慢开口，语音对话建议 512~2048",
                    valueText = "${maxOutputTokens.toInt()}",
                    value = maxOutputTokens,
                    onValueChange = { maxOutputTokens = it },
                    // 128 起步、128 一档：够粗也够用，避免滑杆停在 1903 这种怪数字上
                    valueRange = 128f..4096f,
                    steps = 30,
                    onReset = { maxOutputTokens = 2048f }
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
                    SettingsTextField("ASR 模型名", asrModel, { asrModel = it }, "whisper-1")
                    Text(
                        text = "OpenAI 官方端点必填（如 whisper-1 / whisper-large-v3）；自建服务端可留空，由服务端用默认模型",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }

                // Vosk 离线模型管理
                if (asrProvider == "vosk") {
                    Text("离线模型", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))

                    voskModels.forEach { model ->
                        val isDownloaded = isModelDownloaded(model.id)
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

                SettingsTextField("语言", asrLanguage, { asrLanguage = it }, "zh")
                Text(
                    text = "ISO-639-1 两位码（zh / en / ja）；填 auto 走自动检测。旧值 zh-CN 会自动转成 zh",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant.copy(alpha = 0.6f)
                )

                Spacer(modifier = Modifier.height(24.dp))

                // ===== TTS (MiMo) =====
                SectionHeader(title = "🔊 TTS 语音合成 (MiMo)")
                SettingsTextField("API Key", ttsApiKey, { ttsApiKey = it }, isPassword = !showApiKey, placeholder = "your-mimo-api-key")

                // 模型名称：端点与请求格式已锁死 MiMo，这里只选"预置音色"还是"克隆音色"
                Text("模型名称", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
                OutlinedTextField(
                    value = ttsModel, onValueChange = { ttsModel = it }, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("mimo-v2.5-tts-voiceclone", color = colors.onSurfaceVariant.copy(alpha = 0.4f)) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = colors.primary, unfocusedBorderColor = colors.outline,
                        focusedTextColor = colors.onSurface, unfocusedTextColor = colors.onSurface, cursorColor = colors.tertiary
                    ),
                    shape = shapes.small
                )
                Text(
                    text = "可选 mimo-v2.5-tts-voiceclone（用参考音频克隆音色）/ mimo-v2.5-tts（预置音色）。" +
                        "内置角色若锁定了发声策略，会无视这里的选项",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.padding(top = 4.dp)
                )

                // 朗读超时：从发起 TTS 请求到放完最后一段音频的总时长上限。
                // 卡死（服务端不下发音频 / 放一半断流）由它兜底，代价是长回复也会被它切，
                // 所以给用户一根可调的滑杆，而不是写死一个数。
                Spacer(modifier = Modifier.height(12.dp))
                ParamSlider(
                    label = "朗读超时",
                    hint = "一次朗读最多等多久：从发出 TTS 请求到放完最后一段音频，超时立即停止并回到聆听。" +
                        "长回复总被切尾巴就调大；想让卡住时更快脱身就调小",
                    valueText = "${ttsPlaybackTimeoutSec.toInt()} 秒",
                    value = ttsPlaybackTimeoutSec,
                    onValueChange = { ttsPlaybackTimeoutSec = it },
                    valueRange = ApiConfig.MIN_TTS_PLAYBACK_TIMEOUT_SEC.toFloat()..
                        ApiConfig.MAX_TTS_PLAYBACK_TIMEOUT_SEC.toFloat(),
                    steps = (ApiConfig.MAX_TTS_PLAYBACK_TIMEOUT_SEC -
                        ApiConfig.MIN_TTS_PLAYBACK_TIMEOUT_SEC) / ApiConfig.TTS_PLAYBACK_TIMEOUT_STEP_SEC - 1,
                    onReset = {
                        ttsPlaybackTimeoutSec = ApiConfig.DEFAULT_TTS_PLAYBACK_TIMEOUT_SEC.toFloat()
                    }
                )

                Spacer(modifier = Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = showApiKey, onCheckedChange = { showApiKey = it }, colors = SwitchDefaults.colors(checkedTrackColor = colors.primary))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("显示API Key", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = live2dEnabled, onCheckedChange = { live2dEnabled = it }, colors = SwitchDefaults.colors(checkedTrackColor = colors.primary))
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text("启用 Live2D 形象", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                        Text("通话界面显示动态角色；关闭或加载失败时回退为静态头像", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant.copy(alpha = 0.6f))
                    }
                }

                // 模型作者署名（与通话页舞台左下角是同一个入口）。
                // 每个内置角色的模型作者不同，这里按角色各列一行。
                // 放在开关正下方而不是藏进"关于"：用户在这个页面决定要不要用这个形象，
                // 出处就该在同一屏里给到。
                Spacer(modifier = Modifier.height(6.dp))
                com.lv999call.app.preset.BuiltInCharacters.ALL.forEach { character ->
                    character.credit?.let { credit ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Spacer(modifier = Modifier.width(28.dp))
                            Column {
                                Live2DAuthorCredit(label = credit.label, url = credit.url)
                                Text(
                                    text = "点击前往作者 B 站主页",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.onSurfaceVariant.copy(alpha = 0.45f)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                }

                // 子开关：只在启用 Live2D 时才有意义
                if (live2dEnabled) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Spacer(modifier = Modifier.width(28.dp))
                        Switch(checked = live2dTransformEnabled, onCheckedChange = { live2dTransformEnabled = it }, colors = SwitchDefaults.colors(checkedTrackColor = colors.primary))
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text("接通/挂断变身过场", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                            Text("播模型自带的划卡变身（接通约 4.7s、挂断约 2.3s，挂断会多等这一小段再返回）；关掉不影响待机动作、表情与口型", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant.copy(alpha = 0.6f))
                        }
                    }

                    // 第二个子开关：声音跟着情绪走（v1.6.0 的新能力）。
                    // 它同样依赖表情标签协议，而协议只在 Live2D 打开时注入 —— 所以放这里，
                    // 而不是给一个"开着却什么都不做"的独立开关
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Spacer(modifier = Modifier.width(28.dp))
                        Switch(checked = emotionVoiceEnabled, onCheckedChange = { emotionVoiceEnabled = it }, colors = SwitchDefaults.colors(checkedTrackColor = colors.primary))
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text("声音跟着情绪走", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                            Text("表情变化时连朗读语气一起变（只调音量、音高、气息，语速始终是角色本来的）；关掉后脸照常变，声音保持她一贯的语气", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant.copy(alpha = 0.6f))
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // ===== 长期记忆（plan4 §5.7）=====
                SectionHeader(title = "🧠 长期记忆")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = memoryAutoSummarizeEnabled,
                        onCheckedChange = { memoryAutoSummarizeEnabled = it },
                        colors = SwitchDefaults.colors(checkedTrackColor = colors.primary)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text("长期记忆", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                        Text(
                            text = "每次通话结束后自动总结成一条备忘，下次开聊时让角色想起。关掉后不再总结、也不会读取，" +
                                "但已有记忆会原样保留（想删请去记忆库清空）",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                }
                // 子开关：总开关关掉后它没有任何作用，与 Live2D 的子开关同一套处理
                if (memoryAutoSummarizeEnabled) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Spacer(modifier = Modifier.width(28.dp))
                        Switch(
                            checked = memorySummarizeShortCalls,
                            onCheckedChange = { memorySummarizeShortCalls = it },
                            colors = SwitchDefaults.colors(checkedTrackColor = colors.primary)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text("短通话也总结", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                            Text(
                                text = "默认只有聊够两轮才记；打开后哪怕只说一句也记下来。想一句不落地留住就开它，代价是记忆库里会多出些零碎条目",
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                        }
                    }

                    // 记忆提醒通知：第二个子开关，与上面的子开关同一套缩进与处理
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Spacer(modifier = Modifier.width(28.dp))
                        Switch(
                            checked = memoryReminderEnabled,
                            onCheckedChange = { want ->
                                when {
                                    // 关：立刻取消后台任务。用户关掉开关后**必须**马上生效，
                                    // 不能等下一次 App 启动的 sync（那时可能已经推过一条了）
                                    !want -> {
                                        memoryReminderEnabled = false
                                        MemoryReminderScheduler.sync(context, false)
                                    }
                                    // API 33+ 且还没有通知权限 → 先要权限，结果由上面的回调决定。
                                    // 这里刻意**先不改开关状态**：被拒时回调会把它按回关，
                                    // 若先置 true 再回滚，用户会看到开关"闪一下"
                                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                        !ReminderNotifier.hasPostPermission(context) -> {
                                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    }
                                    // 有权限，但系统里通知被关了 / 本渠道被屏蔽：系统不会再弹对话框，
                                    // 只能引导用户自己去系统设置（开关保持关，避免"看起来开着却收不到"）
                                    !ReminderNotifier.canPost(context) -> {
                                        memoryReminderEnabled = false
                                        scope.launch {
                                            snackbarHostState.showSnackbar("系统里通知已被关闭，请到系统设置里为「LV999」打开通知")
                                        }
                                    }
                                    else -> {
                                        memoryReminderEnabled = true
                                        MemoryReminderScheduler.sync(context, true)
                                    }
                                }
                            },
                            colors = SwitchDefaults.colors(checkedTrackColor = colors.primary)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text("记忆提醒通知", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                            Text(
                                text = "角色偶尔会以通知形式给你发一条消息，像主动来找你说话。每天最多一条，" +
                                    "且 22:00–09:00 不会打扰你。只发文字通知，不会响铃、不涉及通话",
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = {
                        onSave(config.copy(
                            llmBaseUrl = llmBaseUrl, llmApiKey = llmApiKey, llmModel = llmModel,
                            maxContextTokens = maxContextTokens.toInt(),
                            llmTemperature = temperature,
                            llmTopP = topP,
                            llmMaxOutputTokens = maxOutputTokens.toInt(),
                            asrProvider = asrProvider, asrBaseUrl = asrBaseUrl, asrApiKey = asrApiKey,
                            asrModel = asrModel,
                            asrLanguage = asrLanguage, asrVoskModelId = asrVoskModelId,
                            ttsApiKey = ttsApiKey, ttsModel = ttsModel,
                            ttsPlaybackTimeoutSec = ApiConfig.clampTtsPlaybackTimeoutSec(
                                ttsPlaybackTimeoutSec.toInt()
                            ),
                            live2dEnabled = live2dEnabled,
                            live2dTransformEnabled = live2dTransformEnabled,
                            emotionVoiceEnabled = emotionVoiceEnabled,
                            memoryAutoSummarizeEnabled = memoryAutoSummarizeEnabled,
                            memorySummarizeShortCalls = memorySummarizeShortCalls,
                            memoryReminderEnabled = memoryReminderEnabled
                        ))
                        // 开关切换时已经 sync 过一次，但那只改了"内存里的意图"，配置要到
                        // 这里保存才落盘。再 sync 一次是为了对齐**落盘后的最终状态**：
                        // 用户开了开关又没保存就退出时，内存里的 sync 已经入队，而配置仍是关的
                        // （Worker 读到关就自己跳过，不会误推），下次启动 App 会按配置取消掉它
                        MemoryReminderScheduler.sync(context, memoryReminderEnabled)
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
                Column(modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
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

    // 错误提示
    SnackbarHost(hostState = snackbarHostState, modifier = Modifier.navigationBarsPadding().padding(16.dp))
}

@Composable
private fun SectionHeader(title: String) {
    Text(text = title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.tertiary,
        fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 12.dp, top = 8.dp))
}

/**
 * 带说明、当前值与「恢复默认」的滑杆。
 *
 * 采样参数大多没有绝对正确答案，用户需要知道自己在调什么，
 * 以及随时能退回来 —— 所以每根滑杆都配了单行说明和重置入口。
 */
@Composable
private fun ParamSlider(
    label: String,
    hint: String,
    valueText: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onReset: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Column(modifier = Modifier.padding(bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = valueText,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.primary,
                fontWeight = FontWeight.Bold
            )
            TextButton(onClick = onReset, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text("默认", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            }
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            colors = SliderDefaults.colors(thumbColor = colors.primary, activeTrackColor = colors.primary)
        )
        Text(
            text = hint,
            style = MaterialTheme.typography.labelSmall,
            color = colors.onSurfaceVariant.copy(alpha = 0.6f)
        )
    }
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
