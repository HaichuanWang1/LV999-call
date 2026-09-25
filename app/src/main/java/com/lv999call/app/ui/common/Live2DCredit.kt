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
 * 每个内置角色的模型作者不同（银狼：槿絮OuO；DeepSeek 酱：氵六青），
 * 所以文案与链接都由 [com.lv999call.app.domain.model.BuiltInCharacter.credit]
 * 传入，而不是写死在这里 —— 署名信息属于角色数据，不属于渲染逻辑。
 *
 * 用 [Uri] 打开短链/主页而不是写死 UID：短链由作者本人维护，
 * 指向哪儿、以后换不换主页都不用改代码；系统里装了 B 站客户端时
 * 会由客户端接管，没装则落到浏览器。
 *
 * 视觉上刻意压低存在感：半透明小字，不跟界面主体抢视线。
 */
@Composable
fun Live2DAuthorCredit(
    modifier: Modifier = Modifier,
    /** 展示文案，由角色的 credit 提供 */
    label: String = "模型作者：槿絮OuO @bilibili",
    /** 点击跳转的地址，由角色的 credit 提供 */
    url: String = Live2DAuthorCreditUrl
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
                        Intent(Intent.ACTION_VIEW, Uri.parse(url))
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
