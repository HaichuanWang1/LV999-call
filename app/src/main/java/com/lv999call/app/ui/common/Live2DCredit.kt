package com.lv999call.app.ui.common

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp

/**
 * Live2D 模型作者署名。
 *
 * 该形象（银狼 Live2D 模型）的作者是 B 站 UP 主「槿絮OuO」，按作者要求标注来源，
 * 并给一个能直接点进主页的入口。
 *
 * 用 [Uri] 打开 `https://b23.tv/...` 短链而不是写死 UID：短链由作者本人维护，
 * 指向哪儿、以后换不换主页都不用改代码；系统里装了 B 站客户端时
 * 会由客户端接管（`b23.tv` 是其官方短链域名），没装则落到浏览器。
 *
 * 视觉上刻意压低存在感：半透明小字，不跟界面主体抢视线。
 */
@Composable
fun Live2DAuthorCredit(
    modifier: Modifier = Modifier,
    /** 展示文案，可被调用方按版面需要改写 */
    label: String = "模型作者：槿絮OuO @bilibili"
) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme

    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = colors.onSurfaceVariant.copy(alpha = 0.45f),
        textDecoration = TextDecoration.Underline,
        modifier = modifier
            .clickable {
                try {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(Live2DAuthorCreditUrl))
                            // 从非 Activity 上下文启动时要带 NEW_TASK；这里虽是 Activity，
                            // 带上不影响，且能兼容以后被别处复用
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (e: ActivityNotFoundException) {
                    // 没有浏览器 / 没有可处理该链接的应用：忽略即可，
                    // 这只是个署名入口，不值得为它弹错误提示
                    Log.w("Live2DCredit", "无法打开作者主页: ${e.message}")
                }
            }
            .padding(vertical = 2.dp)
    )
}

/** 作者 B 站主页短链（作者本人维护，随其账号变动自动生效） */
const val Live2DAuthorCreditUrl = "https://b23.tv/5bDRwj4"

/** 作者主页的完整地址；`b23.tv/5bDRwj4` 实际跳转到此空间 */
const val Live2DAuthorHomePage = "https://space.bilibili.com/2201725"
