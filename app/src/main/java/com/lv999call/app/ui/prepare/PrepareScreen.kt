package com.lv999call.app.ui.prepare

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.lv999call.app.domain.model.DialogMode

@Composable
fun PrepareScreen(
    mode: DialogMode,
    systemPrompt: String,
    backgroundUri: String?,
    onStartCall: () -> Unit,
    onBack: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val shapes = MaterialTheme.shapes
    val scrollState = rememberScrollState()

    Box(modifier = Modifier.fillMaxSize()) {
        if (!backgroundUri.isNullOrEmpty()) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(backgroundUri)
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                alpha = 0.3f
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            colors.background.copy(alpha = 0.85f),
                            colors.background.copy(alpha = 0.7f),
                            colors.background.copy(alpha = 0.9f)
                        )
                    )
                )
        )

        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = colors.onSurface
                    )
                }
                Text(
                    text = when (mode) {
                        DialogMode.QUICK -> "快速模式"
                        DialogMode.LONG -> "长提示词模式"
                        DialogMode.CUSTOM -> "自定义模式"
                    },
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.onSurface
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 24.dp)
                    .verticalScroll(scrollState)
            ) {
                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "系统提示词预览",
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.tertiary
                )

                Spacer(modifier = Modifier.height(12.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = shapes.medium,
                    colors = CardDefaults.cardColors(
                        containerColor = colors.surfaceContainer.copy(alpha = 0.8f)
                    )
                ) {
                    Text(
                        text = systemPrompt.ifEmpty { "（无系统提示词 - 直接对话）" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (systemPrompt.isEmpty()) colors.onSurfaceVariant else colors.onSurface,
                        modifier = Modifier.padding(16.dp),
                        lineHeight = 22.sp
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = shapes.medium,
                    colors = CardDefaults.cardColors(
                        containerColor = colors.surfaceContainerHigh.copy(alpha = 0.6f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = when (mode) {
                                DialogMode.QUICK -> "💡 快速模式使用简短的角色提示词，适合日常闲聊。"
                                DialogMode.LONG -> "📖 长提示词模式包含详细的角色设定和对话风格指南，提供更深度的角色扮演体验。"
                                DialogMode.CUSTOM -> "✏️ 自定义模式使用您在编辑页面配置的提示词和角色设定。"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant,
                            lineHeight = 22.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = 48.dp),
                contentAlignment = Alignment.Center
            ) {
                // M3: 使用 tonal elevation 而非 shadow elevation
                FilledTonalButton(
                    onClick = onStartCall,
                    modifier = Modifier.size(120.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = colors.primaryContainer,
                        contentColor = colors.onPrimaryContainer
                    )
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Phone,
                            contentDescription = "开始通话",
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "通话",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
