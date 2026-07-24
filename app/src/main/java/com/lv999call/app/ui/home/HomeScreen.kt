package com.lv999call.app.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lv999call.app.data.local.entity.PresetEntity

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    presets: List<PresetEntity>,
    onNavigateToSilverWolf: () -> Unit,
    onNavigateToPreset: (Long) -> Unit,
    onNavigateToNewPreset: () -> Unit,
    onDeletePreset: (Long) -> Unit,
    onNavigateToSettings: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    var showDeleteDialog by remember { mutableStateOf<Long?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(colors.background, colors.surface, colors.background))
            )
        )

        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp).statusBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(48.dp))

            Text("lv999call", style = MaterialTheme.typography.displayLarge, color = colors.tertiary, fontSize = 36.sp)
            Text("ultraflow", style = MaterialTheme.typography.titleMedium, color = colors.onSurfaceVariant, letterSpacing = 4.sp)

            Spacer(modifier = Modifier.height(48.dp))

            // 银狼（内置，不可删除）
            PresetCard(
                title = "银狼",
                subtitle = "角色扮演语音对话",
                isBuiltIn = true,
                onClick = onNavigateToSilverWolf,
                onLongClick = {}
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 用户自定义预设
            presets.forEach { preset ->
                PresetCard(
                    title = preset.name,
                    subtitle = "自定义方案",
                    isBuiltIn = false,
                    onClick = { onNavigateToPreset(preset.id) },
                    onLongClick = { showDeleteDialog = preset.id }
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            // 新建预设按钮
            OutlinedButton(
                onClick = onNavigateToNewPreset,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("新建自定义方案")
            }

            Spacer(modifier = Modifier.weight(1f))

            Column(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
                Text("by 超重氢", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant.copy(alpha = 0.6f))
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(onClick = { uriHandler.openUri("https://github.com/HaichuanWang1/LV999-call") }) {
                    Text("GitHub", style = MaterialTheme.typography.labelLarge, color = colors.primary.copy(alpha = 0.8f))
                }
            }
        }

        IconButton(
            onClick = onNavigateToSettings,
            modifier = Modifier.align(Alignment.BottomEnd).padding(24.dp).navigationBarsPadding()
                .size(56.dp).clip(CircleShape).background(colors.surfaceVariant)
        ) {
            Icon(Icons.Default.Settings, "设置", tint = colors.onSurfaceVariant, modifier = Modifier.size(28.dp))
        }
    }

    showDeleteDialog?.let { presetId ->
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            title = { Text("删除方案") },
            text = { Text("确定要删除这个自定义方案吗？") },
            confirmButton = {
                TextButton(onClick = { onDeletePreset(presetId); showDeleteDialog = null }) {
                    Text("删除", color = colors.error)
                }
            },
            dismissButton = { TextButton(onClick = { showDeleteDialog = null }) { Text("取消") } }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PresetCard(
    title: String, subtitle: String, isBuiltIn: Boolean,
    onClick: () -> Unit, onLongClick: () -> Unit
) {
    val colors = MaterialTheme.colorScheme

    Card(
        modifier = Modifier.fillMaxWidth().height(72.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceContainer)
    ) {
        Row(
            modifier = Modifier.fillMaxSize()
                .background(Brush.horizontalGradient(listOf(
                    if (isBuiltIn) colors.secondary.copy(alpha = 0.15f) else colors.tertiary.copy(alpha = 0.1f),
                    colors.surfaceContainer
                )))
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(40.dp).clip(CircleShape)
                    .background(if (isBuiltIn) colors.secondary.copy(alpha = 0.2f) else colors.tertiary.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (isBuiltIn) "🐺" else "✦",
                    fontSize = if (isBuiltIn) 20.sp else 18.sp,
                    color = if (isBuiltIn) colors.secondary else colors.tertiary
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium, color = colors.onSurface, fontWeight = FontWeight.Bold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            Spacer(modifier = Modifier.weight(1f))
            if (!isBuiltIn) {
                Text("长按删除", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant.copy(alpha = 0.5f))
            }
        }
    }
}
