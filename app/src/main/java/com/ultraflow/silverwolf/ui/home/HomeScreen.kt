package com.ultraflow.silverwolf.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ultraflow.silverwolf.domain.model.DialogMode

@Composable
fun HomeScreen(
    onNavigateToPrepare: (DialogMode) -> Unit,
    onNavigateToSettings: () -> Unit
) {
    val colors = MaterialTheme.colorScheme

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(colors.background, colors.surface, colors.background)
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                .statusBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(48.dp))

            Text(
                text = "银狼AI通话",
                style = MaterialTheme.typography.displayLarge,
                color = colors.tertiary,
                fontSize = 36.sp
            )

            Text(
                text = "ultraflow",
                style = MaterialTheme.typography.titleMedium,
                color = colors.onSurfaceVariant,
                letterSpacing = 4.sp
            )

            Spacer(modifier = Modifier.height(64.dp))

            ModeCard(
                title = "银狼",
                subtitle = "角色扮演语音对话",
                icon = Icons.Default.Description,
                containerColor = colors.secondary,
                onClick = { onNavigateToPrepare(DialogMode.LONG) }
            )

            Spacer(modifier = Modifier.height(16.dp))

            ModeCard(
                title = "自定义",
                subtitle = "自定义角色与提示词",
                icon = Icons.Default.Edit,
                containerColor = colors.tertiary,
                onClick = { onNavigateToPrepare(DialogMode.CUSTOM) }
            )

            Spacer(modifier = Modifier.weight(1f))

            // 底部信息
            Column(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
                Text(
                    text = "by 超重氢",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant.copy(alpha = 0.6f)
                )
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(
                    onClick = { uriHandler.openUri("https://github.com/HaichuanWang1/LV999-call") }
                ) {
                    Text(
                        text = "GitHub",
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.primary.copy(alpha = 0.8f)
                    )
                }
            }
        }

        IconButton(
            onClick = onNavigateToSettings,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(24.dp)
                .navigationBarsPadding()
                .size(56.dp)
                .clip(CircleShape)
                .background(colors.surfaceVariant)
        ) {
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = "设置",
                tint = colors.onSurfaceVariant,
                modifier = Modifier.size(28.dp)
            )
        }
    }
}

@Composable
private fun ModeCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    containerColor: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val shapes = MaterialTheme.shapes

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(100.dp)
            .clickable(onClick = onClick),
        shape = shapes.large,
        colors = CardDefaults.cardColors(containerColor = colors.surfaceContainer)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(containerColor.copy(alpha = 0.15f), containerColor.copy(alpha = 0.05f))
                    )
                )
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(containerColor.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = containerColor,
                    modifier = Modifier.size(28.dp)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            Icon(
                imageVector = Icons.Default.KeyboardArrowRight,
                contentDescription = null,
                tint = colors.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
