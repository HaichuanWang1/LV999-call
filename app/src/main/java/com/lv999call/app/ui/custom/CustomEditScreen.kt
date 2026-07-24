package com.lv999call.app.ui.custom

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
import androidx.compose.material.icons.filled.Phone
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
import com.lv999call.app.audio.AudioExtractor
import kotlinx.coroutines.launch

@Composable
fun CustomEditScreen(
    presetId: Long?,
    currentName: String,
    currentPrompt: String,
    currentRefAudioBase64: String,
    currentRefAudioMime: String,
    currentAvatarUri: String?,
    currentBackgroundUri: String?,
    onSave: (name: String, prompt: String, refAudioBase64: String, refAudioMime: String, avatarUri: String?, backgroundUri: String?) -> Unit,
    onStartCall: (name: String, prompt: String, refAudioBase64: String, refAudioMime: String, avatarUri: String?, backgroundUri: String?) -> Unit,
    onBack: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val shapes = MaterialTheme.shapes
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var name by remember { mutableStateOf(currentName) }
    var prompt by remember { mutableStateOf(currentPrompt) }
    var refAudioBase64 by remember { mutableStateOf(currentRefAudioBase64) }
    var refAudioMime by remember { mutableStateOf(currentRefAudioMime) }
    var refAudioName by remember { mutableStateOf(if (currentRefAudioBase64.isNotEmpty()) "已加载" else "") }
    var avatarUri by remember { mutableStateOf(currentAvatarUri) }
    var backgroundUri by remember { mutableStateOf(currentBackgroundUri) }
    var isExtractingAudio by remember { mutableStateOf(false) }
    var showSourceDialog by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()

    val avatarLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { avatarUri = it.toString() }
    }
    val backgroundLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { backgroundUri = it.toString() }
    }
    val audioLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            refAudioName = it.lastPathSegment ?: "audio"
            isExtractingAudio = true
            scope.launch {
                val result = AudioExtractor.extractWav(context, it)
                isExtractingAudio = false
                result.onSuccess { wavBytes ->
                    refAudioBase64 = Base64.encodeToString(wavBytes, Base64.NO_WRAP)
                    refAudioMime = "audio/wav"
                }
            }
        }
    }
    val videoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            refAudioName = it.lastPathSegment ?: "video"
            isExtractingAudio = true
            scope.launch {
                val result = AudioExtractor.extractWav(context, it)
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
            // 顶部栏
            Row(
                modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = colors.onSurface)
                }
                Text(
                    text = if (presetId != null) "编辑方案" else "新建方案",
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.onSurface,
                    modifier = Modifier.weight(1f)
                )
            }

            Column(
                modifier = Modifier.weight(1f).verticalScroll(scrollState).padding(horizontal = 24.dp)
            ) {
                // 方案名称
                Text("方案名称", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("给方案起个名字...", color = colors.onSurfaceVariant.copy(alpha = 0.4f)) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = colors.primary, unfocusedBorderColor = colors.outline,
                        focusedTextColor = colors.onSurface, unfocusedTextColor = colors.onSurface, cursorColor = colors.tertiary
                    ),
                    shape = shapes.small
                )

                Spacer(modifier = Modifier.height(16.dp))

                // 头像 + 背景
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    ImagePickerItem("头像", avatarUri) { avatarLauncher.launch("image/*") }
                    ImagePickerItem("背景", backgroundUri) { backgroundLauncher.launch("image/*") }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // 参考音频
                Text("参考音频（克隆音色）", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
                Text("上传5~15秒清晰人声", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant.copy(alpha = 0.6f), modifier = Modifier.padding(bottom = 8.dp))
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = { showSourceDialog = true },
                        modifier = Modifier.weight(1f), shape = shapes.small,
                        enabled = !isExtractingAudio
                    ) {
                        if (isExtractingAudio) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = colors.primary)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("提取中...")
                        } else {
                            Icon(Icons.Default.AudioFile, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(refAudioName.ifEmpty { "选择音频" })
                        }
                    }
                    if (refAudioBase64.isNotEmpty() && !isExtractingAudio) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(Icons.Default.Check, "已选择", tint = colors.tertiary, modifier = Modifier.size(24.dp))
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // 提示词
                Text("系统提示词", style = MaterialTheme.typography.titleMedium, color = colors.tertiary)
                Spacer(modifier = Modifier.height(4.dp))
                Text("留空则直接对话", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = prompt, onValueChange = { prompt = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp),
                    placeholder = { Text("输入系统提示词...", color = colors.onSurfaceVariant.copy(alpha = 0.4f)) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = colors.primary, unfocusedBorderColor = colors.outline,
                        focusedTextColor = colors.onSurface, unfocusedTextColor = colors.onSurface, cursorColor = colors.tertiary
                    ),
                    shape = shapes.medium
                )

                Spacer(modifier = Modifier.height(24.dp))
            }

            // 底部按钮
            Row(
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = { onSave(name, prompt, refAudioBase64, refAudioMime, avatarUri, backgroundUri) },
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = shapes.medium
                ) {
                    Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("保存")
                }
                Button(
                    onClick = { onStartCall(name, prompt, refAudioBase64, refAudioMime, avatarUri, backgroundUri) },
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = shapes.medium,
                    colors = ButtonDefaults.buttonColors(containerColor = colors.primary)
                ) {
                    Icon(Icons.Default.Phone, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("开始通话", fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    // 音频来源弹窗
    if (showSourceDialog) {
        AlertDialog(
            onDismissRequest = { showSourceDialog = false },
            title = { Text("选择音频来源") },
            text = {
                Column {
                    ListItem(
                        headlineContent = { Text("音频文件") },
                        supportingContent = { Text("WAV / MP3 / FLAC") },
                        leadingContent = { Icon(Icons.Default.AudioFile, null, tint = colors.primary) },
                        modifier = Modifier.clickable { showSourceDialog = false; audioLauncher.launch("audio/*") }
                    )
                    ListItem(
                        headlineContent = { Text("从视频提取") },
                        supportingContent = { Text("MP4 / MKV，自动转WAV") },
                        leadingContent = { Icon(Icons.Default.VideoFile, null, tint = colors.tertiary) },
                        modifier = Modifier.clickable { showSourceDialog = false; videoLauncher.launch("video/*") }
                    )
                }
            },
            confirmButton = {}
        )
    }
}

@Composable
private fun ImagePickerItem(label: String, imageUri: String?, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shapes = MaterialTheme.shapes

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier.size(100.dp).clip(shapes.large)
                .border(2.dp, if (imageUri != null) colors.primary.copy(alpha = 0.5f) else colors.outline, shapes.large)
                .background(colors.surfaceContainer).clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            if (imageUri != null) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current).data(imageUri).crossfade(true).build(),
                    contentDescription = label,
                    modifier = Modifier.fillMaxSize().clip(shapes.large),
                    contentScale = ContentScale.Crop
                )
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Add, "选择图片", tint = colors.onSurfaceVariant, modifier = Modifier.size(28.dp))
                    Text("选择", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                }
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, color = colors.onSurface)
    }
}
