package com.lv999call.app

import android.Manifest
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.lv999call.app.navigation.NavGraph
import com.lv999call.app.notify.ReminderNotifier
import com.lv999call.app.ui.theme.UltraFlowTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    /**
     * 通知权限请求（API 33+ 的 POST_NOTIFICATIONS）。
     *
     * 这与设置页里那个 launcher 是**两条独立的路径**，因为「记忆提醒通知」现在默认开启：
     * 设置页那条只有用户主动去拨开关时才走得到，而默认开的用户根本不会去拨它 ——
     * 于是新装用户会永远停在"开关开着、权限没有"的状态，Worker 每轮静默跳过。
     *
     * 必须注册在 Activity 进入 STARTED 之前，所以放成属性初始化（不是丢进 onCreate 里）。
     */
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            // 拒绝 → 把开关落回关。
            // 不能留一个"开着却永远发不出去"的开关：设置页会显示已开启，而 Worker 每轮都在
            // canPost 处静默跳过，用户看到的是一个坏了的功能。落回关至少说了实话；
            // 用户改主意可以去设置页重新打开，那里会再走一次权限流程。
            appModule().applicationScope.launch {
                appModule().configRepository.updateMemoryReminderEnabled(false)
                Log.d(TAG, "用户拒绝了通知权限，记忆提醒开关已落回关闭")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        askNotificationPermissionIfNeeded()
        setContent {
            UltraFlowTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    NavGraph()
                }
            }
        }
    }

    private fun appModule() = (application as App).appModule

    /**
     * 冷启动时按需请求通知权限。
     *
     * 三个前置条件缺一不可，都是为了"只在真的有意义时才打扰用户"：
     * · **开关开着** —— 关着就没有任何东西要推送，问了也是白问；
     * · **还没有权限** —— 已授权就什么都不用做。API 33 以下 [ReminderNotifier.hasPostPermission]
     *   恒为 true，所以老系统在这条自然短路，不用额外判版本；
     * · **已经有过至少一次会话** —— Worker 在一条会话都没有时会以"还没有任何会话"跳过，
     *   也就是说这时提醒**根本发不出来**。用户刚装完 App、还没跟角色说过话就弹一个通知权限
     *   对话框，既没有上下文、也大概率被拒，纯属白白消耗掉系统只给两次的弹窗机会。
     *
     * 读配置/查会话都是磁盘 IO，不能压在 onCreate 里（会拖慢冷启动、可能 ANR），
     * 所以整体丢进 applicationScope，只在真正要弹框时切回主线程。
     */
    private fun askNotificationPermissionIfNeeded() {
        val module = appModule()
        module.applicationScope.launch {
            val config = try {
                module.configRepository.configFlow.first()
            } catch (e: Exception) {
                // 读失败就当"这次不问"：权限对话框是打扰用户的操作，拿不准状态时宁可不弹。
                // 下次冷启动还会再试一次
                Log.e(TAG, "读取配置失败，本次不请求通知权限: ${e.message}")
                return@launch
            }
            if (!config.memoryReminderEnabled) return@launch
            if (ReminderNotifier.hasPostPermission(this@MainActivity)) return@launch

            val hasSession = try {
                module.sessionRepository.getAllSessions().first().firstOrNull() != null
            } catch (e: Exception) {
                Log.e(TAG, "读取会话失败，本次不请求通知权限: ${e.message}")
                return@launch
            }
            if (!hasSession) {
                Log.d(TAG, "还没有任何会话，提醒本来就发不出去，暂不请求通知权限")
                return@launch
            }

            withContext(Dispatchers.Main) {
                // 上面几次 IO 期间 Activity 可能已经被销毁，这时 launch 会抛
                if (isFinishing || isDestroyed) return@withContext
                Log.d(TAG, "请求通知权限（记忆提醒默认开启且尚无权限）")
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    companion object {
        private const val TAG = "MemoryReminder"
    }
}
