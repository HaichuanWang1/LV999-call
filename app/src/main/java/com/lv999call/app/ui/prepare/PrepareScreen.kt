package com.lv999call.app.ui.prepare

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lv999call.app.audio.AudioExtractor
import com.lv999call.app.domain.model.BuiltInCharacter
import com.lv999call.app.domain.model.DialogMode
import com.lv999call.app.domain.model.TtsPolicy

@Composable
fun PrepareScreen(
    mode: DialogMode,
    /**
     * 当前内置角色。null 表示自定义预设（走 [promptPreview] 分支）。
     *
     * 标题、介绍卡、内置音色文案全部来自它 —— 准备页不认识任何具体角色。
     */
    character: BuiltInCharacter? = null,
    promptPreview: String = "",
    backgroundResId: Int? = null,
    backgroundUri: String? = null,
    hasCustomAudio: Boolean = false,
    ttsPrompt: String = "",
    onTtsPromptChange: (String) -> Unit = {},
    onStartCall: () -> Unit,
    onSelectAudio: (Uri) -> Unit,
    onClearAudio: () -> Unit,
    onBack: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val shapes = MaterialTheme.shapes
    val scrollState = rememberScrollState()
    val context = LocalContext.current

    // 音频选择状态
    var selectedAudioName by remember { mutableStateOf("") }
    var isExtractingAudio by remember { mutableStateOf(false) }
    var extractError by remember { mutableStateOf<String?>(null) }

    // TTS提示词本地状态（避免每次按键都写DataStore）
    var localTtsPrompt by remember(ttsPrompt) { mutableStateOf(ttsPrompt) }

    val audioLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            selectedAudioName = it.lastPathSegment ?: "audio"
            isExtractingAudio = true
            extractError = null
            onSelectAudio(it)
            isExtractingAudio = false
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // 背景图（模糊）
        if (backgroundResId != null) {
            Image(
                painter = painterResource(id = backgroundResId),
                contentDescription = null,
                modifier = Modifier.fillMaxSize().blur(8.dp),
                contentScale = ContentScale.Crop,
                alpha = 0.4f
            )
        }

        // 渐变遮罩
        Box(
            modifier = Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(
                    colors.background.copy(alpha = 0.8f),
                    colors.background.copy(alpha = 0.6f),
                    colors.background.copy(alpha = 0.9f)
                ))
            )
        )

        Column(modifier = Modifier.fillMaxSize()) {
            // 顶部栏
            Row(
                modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = {
                    onTtsPromptChange(localTtsPrompt)
                    onBack()
                }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = colors.onSurface)
                }
                Text(
                    text = character?.displayName ?: when (mode) {
                        DialogMode.QUICK -> "快速模式"
                        DialogMode.LONG -> "内置角色"
                        DialogMode.CUSTOM -> "自定义"
                    },
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.onSurface
                )
            }

            Column(
                modifier = Modifier.weight(1f).padding(horizontal = 24.dp).verticalScroll(scrollState)
            ) {
                Spacer(modifier = Modifier.height(16.dp))

                if (promptPreview.isNotEmpty()) {
                    Text("系统提示词预览", style = MaterialTheme.typography.titleMedium, color = colors.tertiary)
                    Spacer(modifier = Modifier.height(12.dp))

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = shapes.medium,
                        colors = CardDefaults.cardColors(containerColor = colors.surfaceContainer.copy(alpha = 0.8f))
                    ) {
                        Text(
                            text = promptPreview,
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurface,
                            modifier = Modifier.padding(16.dp),
                            lineHeight = 22.sp
                        )
                    }
                } else {
                    // 内置角色介绍卡
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = shapes.medium,
                        colors = CardDefaults.cardColors(containerColor = colors.surfaceContainerHigh.copy(alpha = 0.6f))
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "${character?.emoji ?: "✦"} ${character?.prepareTitle ?: "内置角色"}",
                                style = MaterialTheme.typography.titleMedium,
                                color = colors.secondary
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = character?.prepareDescription
                                    ?: "使用内置提示词与音色，开启沉浸式角色扮演语音对话。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.onSurfaceVariant,
                                lineHeight = 22.sp
                            )
                        }
                    }
                }

                // ===== 参考音频设置 =====
                //
                // 发声被角色锁定时（如 DeepSeek 酱强制使用 MiMo 预置音色），
                // 参考音频对本次通话毫无作用 —— 与其给一个改了没用的输入框，
                // 不如直接把"当前音色"说清楚，并把选择入口收起来。
                Spacer(modifier = Modifier.height(20.dp))
                Text("参考音色", style = MaterialTheme.typography.titleMedium, color = colors.tertiary)
                Spacer(modifier = Modifier.height(8.dp))

                val lockedVoice = character?.ttsPolicy as? TtsPolicy.PresetVoice
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = shapes.medium,
                    colors = CardDefaults.cardColors(containerColor = colors.surfaceContainer.copy(alpha = 0.8f))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        when {
                            lockedVoice != null -> {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.MusicNote, contentDescription = null,
                                        tint = colors.tertiary, modifier = Modifier.size(20.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("当前：内置「${lockedVoice.voice}」音色（已锁定）",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = colors.tertiary)
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("该角色固定使用 MiMo 预置音色，不受设置中的 TTS 模型与参考音频影响。",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.onSurfaceVariant.copy(alpha = 0.7f))
                            }

                            hasCustomAudio -> {
                                // 已设置自定义音色
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.MusicNote, contentDescription = null,
                                        tint = colors.primary, modifier = Modifier.size(20.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("已设置自定义音色", style = MaterialTheme.typography.bodyMedium,
                                        color = colors.primary, modifier = Modifier.weight(1f))
                                    IconButton(onClick = { onClearAudio(); selectedAudioName = "" },
                                        modifier = Modifier.size(32.dp)) {
                                        Icon(Icons.Default.Close, "清除", tint = colors.onSurfaceVariant,
                                            modifier = Modifier.size(18.dp))
                                    }
                                }
                            }

                            else -> {
                                // 使用角色自带音色
                                Text(
                                    text = character?.displayName?.let { "当前：内置${it}音色" } ?: "当前：使用设置中的音色",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colors.onSurfaceVariant
                                )
                            }
                        }

                        // 音色被锁定时不给选择入口：改了也不生效，徒增困惑
                        if (lockedVoice == null) {
                            Spacer(modifier = Modifier.height(12.dp))

                            // 选择音频文件按钮
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = { audioLauncher.launch("audio/*") },
                                    enabled = !isExtractingAudio,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.MusicNote, contentDescription = null,
                                        modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(if (isExtractingAudio) "处理中..." else "选择音频",
                                        style = MaterialTheme.typography.labelMedium)
                                }
                            }

                            // 错误提示
                            extractError?.let { error ->
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(error, style = MaterialTheme.typography.bodySmall, color = colors.error)
                            }

                            Spacer(modifier = Modifier.height(8.dp))
                            Text("支持 mp3/wav 格式，最长15秒，自动转为参考音色",
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.onSurfaceVariant.copy(alpha = 0.6f))
                        }
                    }
                }

                // TTS风格提示词
                Spacer(modifier = Modifier.height(20.dp))
                Text("TTS 风格提示词", style = MaterialTheme.typography.titleMedium, color = colors.tertiary)
                Spacer(modifier = Modifier.height(4.dp))
                Text("控制语音合成的语气、情感、语速等", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = localTtsPrompt,
                    onValueChange = { localTtsPrompt = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp)
                        .onFocusChanged { if (!it.isFocused) onTtsPromptChange(localTtsPrompt) },
                    placeholder = { Text("例如：用温柔的声音、带有笑意地说", color = colors.onSurfaceVariant.copy(alpha = 0.4f)) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = colors.primary, unfocusedBorderColor = colors.outline,
                        focusedTextColor = colors.onSurface, unfocusedTextColor = colors.onSurface, cursorColor = colors.tertiary
                    ),
                    shape = shapes.small
                )

                Spacer(modifier = Modifier.height(32.dp))
            }

            // 开始通话按钮
            Box(
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 48.dp),
                contentAlignment = Alignment.Center
            ) {
                FilledTonalButton(
                    onClick = {
                        onTtsPromptChange(localTtsPrompt)
                        onStartCall()
                    },
                    modifier = Modifier.size(120.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = colors.primaryContainer,
                        contentColor = colors.onPrimaryContainer
                    )
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Phone, "开始通话", modifier = Modifier.size(36.dp))
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("通话", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
