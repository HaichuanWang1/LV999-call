package com.lv999call.app.ui.prepare

import androidx.compose.foundation.Image
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lv999call.app.domain.model.DialogMode

@Composable
fun PrepareScreen(
    mode: DialogMode,
    promptPreview: String = "",
    backgroundResId: Int? = null,
    backgroundUri: String? = null,
    onStartCall: () -> Unit,
    onBack: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val shapes = MaterialTheme.shapes
    val scrollState = rememberScrollState()

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
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = colors.onSurface)
                }
                Text(
                    text = when (mode) {
                        DialogMode.QUICK -> "快速模式"
                        DialogMode.LONG -> "银狼"
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
                    // 银狼模式
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = shapes.medium,
                        colors = CardDefaults.cardColors(containerColor = colors.surfaceContainerHigh.copy(alpha = 0.6f))
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "🐺 银狼模式",
                                style = MaterialTheme.typography.titleMedium,
                                color = colors.secondary
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "使用银狼专属提示词和音色，开启沉浸式角色扮演语音对话。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.onSurfaceVariant,
                                lineHeight = 22.sp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))
            }

            // 开始通话按钮
            Box(
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 48.dp),
                contentAlignment = Alignment.Center
            ) {
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
                        Icon(Icons.Default.Phone, "开始通话", modifier = Modifier.size(36.dp))
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("通话", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
