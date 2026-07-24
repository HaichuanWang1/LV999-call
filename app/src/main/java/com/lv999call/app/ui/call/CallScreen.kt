package com.lv999call.app.ui.call

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.lv999call.app.domain.model.CallState
import com.lv999call.app.domain.model.ChatMessage
import com.lv999call.app.ui.theme.UltraFlowTheme
import kotlinx.coroutines.delay

@Composable
fun CallScreen(
    callState: CallState,
    messages: List<ChatMessage>,
    currentResponse: String,
    audioLevel: Float,
    avatarUri: String?,
    backgroundUri: String? = null,
    backgroundResId: Int? = null,
    onHangUp: () -> Unit,
    onToggleMute: () -> Unit,
    onSendText: (String) -> Unit,
    isMuted: Boolean = false
) {
    val colors = MaterialTheme.colorScheme
    val shapes = MaterialTheme.shapes
    val ext = UltraFlowTheme.extendedColors
    val listState = rememberLazyListState()
    var inputText by remember { mutableStateOf("") }

    LaunchedEffect(messages.size, currentResponse) {
        if (messages.isNotEmpty() || currentResponse.isNotEmpty()) {
            delay(100)
            listState.animateScrollToItem(
                (messages.size + if (currentResponse.isNotEmpty()) 1 else 0).coerceAtLeast(0)
            )
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // 背景图
        when {
            backgroundResId != null -> {
                Image(
                    painter = painterResource(id = backgroundResId),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().blur(6.dp),
                    contentScale = ContentScale.Crop,
                    alpha = 0.3f
                )
            }
            !backgroundUri.isNullOrEmpty() -> {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current).data(backgroundUri).crossfade(true).build(),
                    contentDescription = null, modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop, alpha = 0.25f
                )
            }
        }

        Box(
            modifier = Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    colors = listOf(colors.background.copy(alpha = 0.8f), colors.background.copy(alpha = 0.6f), colors.background.copy(alpha = 0.9f))
                )
            )
        )

        Column(
            modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
        ) {
            Spacer(modifier = Modifier.height(24.dp))

            // 头像
            Box(modifier = Modifier.fillMaxWidth().padding(top = 16.dp), contentAlignment = Alignment.Center) {
                val infiniteTransition = rememberInfiniteTransition(label = "breathing")
                val breathScale by infiniteTransition.animateFloat(
                    initialValue = 1f,
                    targetValue = if (callState == CallState.SPEAKING) 1.08f else 1.02f,
                    animationSpec = infiniteRepeatable(animation = tween(1500, easing = FastOutSlowInEasing), repeatMode = RepeatMode.Reverse),
                    label = "breathScale"
                )
                Box(
                    modifier = Modifier.scale(breathScale).size(100.dp).clip(CircleShape).background(
                        Brush.radialGradient(
                            colors = when (callState) {
                                CallState.LISTENING -> listOf(ext.listening.copy(alpha = 0.4f), ext.listening.copy(alpha = 0.1f))
                                CallState.THINKING -> listOf(ext.thinking.copy(alpha = 0.4f), ext.thinking.copy(alpha = 0.1f))
                                CallState.SPEAKING -> listOf(ext.speaking.copy(alpha = 0.4f), ext.speaking.copy(alpha = 0.1f))
                                else -> listOf(colors.primary.copy(alpha = 0.3f), colors.primary.copy(alpha = 0.1f))
                            }
                        )
                    ),
                    contentAlignment = Alignment.Center
                ) {
                    if (!avatarUri.isNullOrEmpty()) {
                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current).data(avatarUri).crossfade(true).build(),
                            contentDescription = "角色头像",
                            modifier = Modifier.size(88.dp).clip(CircleShape), contentScale = ContentScale.Crop
                        )
                    } else {
                        Image(
                            painter = painterResource(id = com.lv999call.app.R.drawable.touxiang),
                            contentDescription = "角色头像",
                            modifier = Modifier.size(88.dp).clip(CircleShape),
                            contentScale = ContentScale.Crop
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            CallStatusIndicator(callState)
            Spacer(modifier = Modifier.height(16.dp))

            // 消息列表
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
                state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(messages) { MessageBubble(message = it) }
                if (currentResponse.isNotEmpty()) {
                    item { MessageBubble(message = ChatMessage(role = "assistant", content = currentResponse), isStreaming = true) }
                }
            }

            // 文字输入栏
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("输入消息...", color = colors.onSurfaceVariant.copy(alpha = 0.5f)) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = colors.primary, unfocusedBorderColor = colors.outline.copy(alpha = 0.5f),
                        focusedTextColor = colors.onSurface, unfocusedTextColor = colors.onSurface, cursorColor = colors.tertiary
                    ),
                    shape = shapes.large,
                    enabled = callState != CallState.ENDED
                )
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(
                    onClick = {
                        if (inputText.isNotBlank()) {
                            onSendText(inputText.trim())
                            inputText = ""
                        }
                    },
                    enabled = inputText.isNotBlank() && callState != CallState.ENDED,
                    modifier = Modifier.size(48.dp).clip(CircleShape).background(
                        if (inputText.isNotBlank()) colors.primary else colors.surfaceVariant
                    )
                ) {
                    Icon(
                        Icons.Default.Send, "发送",
                        tint = if (inputText.isNotBlank()) colors.onPrimary else colors.onSurfaceVariant,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            // 底部控制栏
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onToggleMute,
                    modifier = Modifier.size(56.dp).clip(CircleShape).background(colors.surfaceVariant)
                ) {
                    Icon(if (isMuted) Icons.Default.MicOff else Icons.Default.Mic, if (isMuted) "取消静音" else "静音",
                        tint = if (isMuted) colors.error else colors.onSurface, modifier = Modifier.size(28.dp))
                }
                Spacer(modifier = Modifier.width(32.dp))
                IconButton(
                    onClick = onHangUp,
                    modifier = Modifier.size(72.dp).clip(CircleShape).background(ext.callEnd)
                ) {
                    Icon(Icons.Default.CallEnd, "挂断", tint = colors.onError, modifier = Modifier.size(36.dp))
                }
                Spacer(modifier = Modifier.width(32.dp))
                Spacer(modifier = Modifier.size(56.dp))
            }
        }
    }
}

@Composable
private fun CallStatusIndicator(callState: CallState) {
    val colors = MaterialTheme.colorScheme
    val ext = UltraFlowTheme.extendedColors
    val (text, color) = when (callState) {
        CallState.IDLE -> "准备就绪" to colors.onSurfaceVariant
        CallState.LISTENING -> "聆听中..." to ext.listening
        CallState.THINKING -> "思考中..." to ext.thinking
        CallState.SPEAKING -> "说话中..." to ext.speaking
        CallState.ENDED -> "通话结束" to colors.onSurfaceVariant
    }
    AnimatedVisibility(visible = callState != CallState.IDLE, enter = fadeIn() + slideInVertically(), exit = fadeOut()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
            if (callState == CallState.LISTENING || callState == CallState.SPEAKING) {
                val infiniteTransition = rememberInfiniteTransition(label = "pulse")
                val alpha by infiniteTransition.animateFloat(
                    initialValue = 0.3f, targetValue = 1f,
                    animationSpec = infiniteRepeatable(animation = tween(600), repeatMode = RepeatMode.Reverse), label = "alpha"
                )
                Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(color.copy(alpha = alpha)))
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(text = text, style = MaterialTheme.typography.bodyMedium, color = color, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage, isStreaming: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    val shapes = MaterialTheme.shapes
    val ext = UltraFlowTheme.extendedColors
    val isUser = message.role == "user"
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
        if (!isUser) {
            Image(
                painter = painterResource(id = com.lv999call.app.R.drawable.touxiang),
                contentDescription = null,
                modifier = Modifier.size(32.dp).clip(CircleShape),
                contentScale = ContentScale.Crop
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        Card(
            shape = shapes.large,
            colors = CardDefaults.cardColors(containerColor = if (isUser) ext.userBubble else ext.aiBubble),
            modifier = Modifier.widthIn(max = 280.dp)
        ) {
            Text(text = message.content, style = MaterialTheme.typography.bodyMedium, color = colors.onSurface,
                modifier = Modifier.padding(12.dp), lineHeight = 20.sp)
        }
        if (isUser) { Spacer(modifier = Modifier.width(8.dp)) }
    }
}
