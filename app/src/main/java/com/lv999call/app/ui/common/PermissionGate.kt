package com.lv999call.app.ui.common

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/**
 * 运行时权限守卫 —— **用到的时候才申请**。
 *
 * ## 为什么要有它
 *
 * 项目里此前只有两处权限处理，而且都不完整：
 * · `AudioRecorder.startRecording()` 只 `checkSelfPermission`，没权限就 `return` 并打一条日志
 *   —— 麦克风权限从来没有被**申请**过。新装用户进通话页等于进了一个静音的功能：
 *   开场白照念、状态照转「聆听」，但麦克风永远录不到东西，界面上没有任何解释；
 * · 通知权限（POST_NOTIFICATIONS）由设置页开关与冷启动两条路径各弹一次。
 *
 * 这里把「检查 → 申请 → 拒绝后给出路」收敛成一个可复用的守卫：**进页面即申请**
 * （这正是"在调用时主动向系统申请"的落点），拒绝后不再反复弹系统对话框，
 * 而是给一块说明 + 「重新申请」/「去系统设置」/「返回」三个出口。
 *
 * ## 用法（返回值当闸门用）
 *
 * ```kotlin
 * // 没权限时它自己渲染说明页并返回 false —— 上层直接 return，
 * // 于是"启动通话"那句 LaunchedEffect 根本不会执行
 * if (!MicPermissionGuard(onBack = { navController.popBackStack() })) return@composable
 * ```
 *
 * 为什么必须是闸门、不能只"申请一下就继续"：被拒绝时若仍把通话页渲染出来，
 * 通话会照常建立、开场白照常念，只是麦克风录不到 —— 用户看到的是一个"她不理我"的坏功能。
 *
 * ## 返回系统设置后要重新检查
 *
 * 「去系统设置」是跳出去开权限再跳回来，`RequestPermission` 回调不会因此触发，
 * 所以这里挂一个 ON_RESUME 观察者：每次回到前台都重新读一次真实权限状态，
 * 用户开完权限回来就能直接进通话页，不用杀进程重进。
 *
 * @param permission 要申请的运行时权限
 * @param title 说明页标题
 * @param rationale 为什么需要它（写人话，别写权限名）
 * @param deniedHint 拒绝之后额外看到的提示（告诉他自己去系统设置也能开）
 * @param onBack 用户放弃时的出口（通常是 `popBackStack()`）
 * @return 权限是否已授予；false 时**说明页已经渲染好了**，调用方应当立刻 return
 */
@Composable
fun PermissionGate(
    permission: String,
    title: String,
    rationale: String,
    deniedHint: String,
    onBack: () -> Unit
): Boolean {
    val context = LocalContext.current

    var granted by remember(permission) { mutableStateOf(context.isPermissionGranted(permission)) }
    // 是否已经弹过一次系统对话框：用 rememberSaveable 扛住旋转/重组，避免反复弹窗骚扰
    var requested by rememberSaveable(permission) { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { result ->
        // result 只代表这次对话框的结果；被系统永久拒绝后 launch 会立刻回调 false，
        // 那条路径由下面的说明页接管（"去系统设置"就是为它准备的出口）
        granted = result
    }

    // 进页面即申请：这是"调用时主动申请"的核心一步。
    // 已经授予过就什么都不做（此时整块说明页根本不会渲染）
    LaunchedEffect(permission) {
        if (!granted && !requested) {
            requested = true
            launcher.launch(permission)
        }
    }

    // 从系统设置返回前台后重新读一次真实状态（用户可能刚在里面把权限打开）
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, permission) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                granted = context.isPermissionGranted(permission)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (granted) return true

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.Mic,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(56.dp)
        )
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = rationale,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 360.dp)
        )
        if (requested) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = deniedHint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 360.dp)
            )
        }
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = { launcher.launch(permission) }) {
            Text("允许使用麦克风")
        }
        Spacer(modifier = Modifier.height(8.dp))
        TextButton(onClick = { context.openAppSettings() }) {
            Text("打开系统设置")
        }
        TextButton(onClick = onBack) {
            Text("返回")
        }
    }
    return false
}

/**
 * 麦克风权限守卫（通话页的入口闸门）。
 *
 * 通话是**语音**通话：没有麦克风权限时她能说、你说了不算，功能等于废的。
 * 所以把申请放在进入通话页的那一刻 —— 用户点「开始通话」的意图最明确，
 * 这时弹权限对话框的接受率最高，也最容易理解"为什么需要它"。
 */
@Composable
fun MicPermissionGuard(onBack: () -> Unit): Boolean = PermissionGate(
    permission = Manifest.permission.RECORD_AUDIO,
    title = "需要麦克风权限",
    rationale = "语音通话要用麦克风听清你说的话。没有这个权限，她只能自说自话，听不到你。",
    deniedHint = "如果系统不再弹窗，可以点「打开系统设置」→ 权限 → 麦克风，手动允许后再回来。",
    onBack = onBack
)

/** 权限是否已授予（API 层差异由 ContextCompat 抹平） */
private fun Context.isPermissionGranted(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

/**
 * 跳到本应用的系统设置页。
 *
 * 从 Android 11 起，用户连续拒绝两次之后系统不再弹权限对话框（`launch` 立刻回调拒绝），
 * 这时唯一的路就是让用户自己去设置里开 —— 没有这个入口，说明页上的按钮会变成
 * "点了没反应"，比不给按钮更让人困惑。
 */
private fun Context.openAppSettings() {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", packageName, null)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { startActivity(intent) }
        .onFailure { android.util.Log.e("PermissionGate", "打开应用设置失败: ${it.message}") }
}
