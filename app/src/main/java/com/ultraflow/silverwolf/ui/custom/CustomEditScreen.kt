package com.ultraflow.silverwolf.ui.custom

import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.launch

@Composable
fun CustomEditScreen(
    currentPrompt: String,
    currentAvatarUri: String?,
    currentBackgroundUri: String?,
    currentRefAudioBase64: String,
    currentRefAudioMime: String,
    onSave: (prompt: String, avatarUri: String?, backgroundUri: String?, refAudioBase64: String, refAudioMime: String) -> Unit,
    onBack: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val shapes = MaterialTheme.shapes
    val context = LocalContext.current

    var prompt by remember { mutableStateOf(currentPrompt) }
    var avatarUri by remember { mutableStateOf(currentAvatarUri) }
    var backgroundUri by remember { mutableStateOf(currentBackgroundUri) }
    var refAudioBase64 by remember { mutableStateOf(currentRefAudioBase64) }
    var refAudioMime by remember { mutableStateOf(currentRefAudioMime) }
    var refAudioName by remember {
        mutableStateOf(
            if (currentRefAudioBase64.isNotEmpty()) "已加载参考音频" else ""
        )
    }
    val scrollState = rememberScrollState()

    val avatarLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? -> uri?.let { avatarUri = it.toString() } }

    val backgroundLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? -> uri?.let { backgroundUri = it.toString() } }

    val scope = rememberCoroutineScope()
    var isExtractingAudio by remember { mutableStateOf(false) }
    var showSourceDialog by remember { mutableStateOf(false) }

    val audioLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            refAudioName = it.lastPathSegment ?: "audio"
            isExtractingAudio = true
            scope.launch {
                val result = com.ultraflow.silverwolf.audio.AudioExtractor.extractWav(context, it)
                isExtractingAudio = false
                result.onSuccess { wavBytes ->
                    refAudioBase64 = Base64.encodeToString(wavBytes, Base64.NO_WRAP)
                    refAudioMime = "audio/wav"
                }
            }
        }
    }

    val videoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            refAudioName = it.lastPathSegment ?: "video"
            isExtractingAudio = true
            scope.launch {
                val result = com.ultraflow.silverwolf.audio.AudioExtractor.extractWav(context, it)
                isExtractingAudio = false
                result.onSuccess { wavBytes ->
                    refAudioBase64 = Base64.encodeToString(wavBytes, Base64.NO_WRAP)
                    refAudioMime = "audio/wav"
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(colors.background)) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = colors.onSurface)
                }
                Text(
                    text = "自定义角色",
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.onSurface,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { onSave(prompt, avatarUri, backgroundUri, refAudioBase64, refAudioMime) }) {
                    Icon(Icons.Default.Save, "保存", tint = colors.tertiary)
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(scrollState)
                    .padding(horizontal = 24.dp)
            ) {
                Spacer(modifier = Modifier.height(16.dp))

                // ===== 图片选择 =====
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    ImagePickerItem("角色头像", avatarUri) { avatarLauncher.launch("image/*") }
                    ImagePickerItem("背景图片", backgroundUri) { backgroundLauncher.launch("image/*") }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // ===== 参考音频选择 =====
                Text(
                    text = "参考音频（克隆音色）",
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.tertiary
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "上传一段5~15秒的音频，AI将克隆该音色进行对话",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(12.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = shapes.medium,
                    colors = CardDefaults.cardColors(containerColor = colors.surfaceContainer)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 音频图标
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(shapes.medium)
                                .background(
                                    if (refAudioBase64.isNotEmpty()) colors.primary.copy(alpha = 0.15f)
                                    else colors.surfaceVariant
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.AudioFile,
                                contentDescription = null,
                                tint = if (refAudioBase64.isNotEmpty()) colors.primary else colors.onSurfaceVariant,
                                modifier = Modifier.size(28.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (refAudioBase64.isNotEmpty()) refAudioName else "未选择音频",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (refAudioBase64.isNotEmpty()) colors.onSurface else colors.onSurfaceVariant
                            )
                            if (refAudioBase64.isNotEmpty()) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = null,
                                        tint = colors.tertiary,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "已加载",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = colors.tertiary
                                    )
                                }
                            }
                        }

                        // 选择/清除按钮
                        if (refAudioBase64.isNotEmpty()) {
                            IconButton(onClick = {
                                refAudioBase64 = ""
                                refAudioMime = "audio/wav"
                                refAudioName = ""
                            }) {
                                Icon(Icons.Default.Close, "清除", tint = colors.error, modifier = Modifier.size(20.dp))
                            }
                        }

                        OutlinedButton(
                            onClick = { showSourceDialog = true },
                            shape = shapes.small,
                            enabled = !isExtractingAudio
                        ) {
                            if (isExtractingAudio) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = colors.primary)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("提取中...")
                            } else {
                                Text(if (refAudioBase64.isNotEmpty()) "更换" else "选择音频")
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // ===== 系统提示词 =====
                Text(
                    text = "系统提示词",
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.tertiary
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "留空则为直接对话模式（无系统提示词）",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 200.dp),
                    placeholder = {
                        Text(
                            text = "输入系统提示词...\n\n例如：你是一个活泼的AI助手，名叫银狼...",
                            color = colors.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = colors.primary,
                        unfocusedBorderColor = colors.outline,
                        focusedTextColor = colors.onSurface,
                        unfocusedTextColor = colors.onSurface,
                        cursorColor = colors.tertiary
                    ),
                    shape = shapes.medium
                )

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = { onSave(prompt, avatarUri, backgroundUri, refAudioBase64, refAudioMime) },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = shapes.medium,
                    colors = ButtonDefaults.buttonColors(containerColor = colors.primary)
                ) {
                    Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "保存配置", fontWeight = FontWeight.Bold)
                }

                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }

    // 音频来源选择弹窗
    if (showSourceDialog) {
        AlertDialog(
            onDismissRequest = { showSourceDialog = false },
            title = { Text("选择音频来源") },
            text = {
                Column {
                    ListItem(
                        headlineContent = { Text("音频文件") },
                        supportingContent = { Text("WAV / MP3 / FLAC 等") },
                        leadingContent = { Icon(Icons.Default.AudioFile, contentDescription = null, tint = colors.primary) },
                        modifier = Modifier.clickable {
                            showSourceDialog = false
                            audioLauncher.launch("audio/*")
                        }
                    )
                    ListItem(
                        headlineContent = { Text("从视频提取") },
                        supportingContent = { Text("MP4 / MKV / AVI 等，自动提取音频转WAV") },
                        leadingContent = { Icon(Icons.Default.VideoFile, contentDescription = null, tint = colors.tertiary) },
                        modifier = Modifier.clickable {
                            showSourceDialog = false
                            videoLauncher.launch("video/*")
                        }
                    )
                }
            },
            confirmButton = {}
        )
    }
}

@Composable
private fun ImagePickerItem(
    label: String,
    imageUri: String?,
    onClick: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val shapes = MaterialTheme.shapes

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(120.dp)
                .clip(shapes.large)
                .border(
                    width = 2.dp,
                    color = if (imageUri != null) colors.primary.copy(alpha = 0.5f) else colors.outline,
                    shape = shapes.large
                )
                .background(colors.surfaceContainer)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            if (imageUri != null) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(imageUri)
                        .crossfade(true)
                        .build(),
                    contentDescription = label,
                    modifier = Modifier.fillMaxSize().clip(shapes.large),
                    contentScale = ContentScale.Crop
                )
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Add, "选择图片", tint = colors.onSurfaceVariant, modifier = Modifier.size(32.dp))
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("选择图片", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(text = label, style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)
    }
}
