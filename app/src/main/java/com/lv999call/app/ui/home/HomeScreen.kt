package com.lv999call.app.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lv999call.app.data.local.entity.PresetEntity
import com.lv999call.app.domain.model.BuiltInCharacter

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    presets: List<PresetEntity>,
    /** 内置角色（银狼 / DeepSeek 酱…），来自 [BuiltInCharacters.ALL]，按顺序并列展示 */
    builtInCharacters: List<BuiltInCharacter>,
    onNavigateToCharacter: (String) -> Unit,
    onNavigateToPreset: (Long) -> Unit,
    onNavigateToNewPreset: () -> Unit,
    onDeletePreset: (Long) -> Unit,
    onNavigateToSettings: () -> Unit,
    /** 记忆库当前条数（plan4 §6.1 的「🧠 记忆库（N 条）」入口要显示它） */
    memoryCount: Int,
    onNavigateToMemory: () -> Unit
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

            // 内置角色（不可删除）。用可滚动列表承载：
            // 内置角色数量是会增长的（现在两个），写死 Column 迟早会溢出屏幕。
            androidx.compose.foundation.lazy.LazyColumn(
                modifier = Modifier.weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(builtInCharacters, key = { "builtin-${it.id}" }) { character ->
                    PresetCard(
                        title = character.displayName,
                        subtitle = character.subtitle,
                        isBuiltIn = true,
                        iconResId = character.cardIconResId,
                        onClick = { onNavigateToCharacter(character.id) },
                        onLongClick = {}
                    )
                }

                // 用户自定义预设
                items(presets, key = { "preset-${it.id}" }) { preset ->
                    PresetCard(
                        title = preset.name,
                        subtitle = "自定义方案",
                        isBuiltIn = false,
                        iconResId = null,
                        onClick = { onNavigateToPreset(preset.id) },
                        onLongClick = { showDeleteDialog = preset.id }
                    )
                }

                item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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

                        // 记忆库入口（plan4 §6.1）。选独立页面而不是弹窗：记忆可能几十条，
                        // 需要真正的列表区域；设置页也已经很长，往里塞不划算。
                        // 条数直接写在按钮上，用户不点进去也知道 App 记了多少东西。
                        OutlinedButton(
                            onClick = onNavigateToMemory,
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Text("🧠 记忆库（$memoryCount 条）")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

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
            // 说清楚"删方案 ≠ 删记忆"：记忆行只挂在会话上（外键），不会随方案一起消失，
            // 不写这句用户会以为删了方案记忆就没了（或反过来，以为记忆被悄悄删了）
            text = { Text("确定要删除这个自定义方案吗？\n它留下的记忆仍会保留在「🧠 记忆库」里，可在那里单独删除。") },
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
    title: String,
    subtitle: String,
    isBuiltIn: Boolean,
    iconResId: Int?,
    onClick: () -> Unit,
    onLongClick: () -> Unit
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
                if (iconResId != null) {
                    Image(
                        painter = painterResource(id = iconResId),
                        contentDescription = title,
                        modifier = Modifier.size(40.dp).clip(CircleShape),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Text(
                        text = "✦",
                        fontSize = 18.sp,
                        color = colors.tertiary
                    )
                }
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
