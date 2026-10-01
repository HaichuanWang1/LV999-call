package com.lv999call.app.notify

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.lv999call.app.MainActivity
import com.lv999call.app.R

/**
 * 记忆提醒的系统通知（渠道 + 推送 + 权限判断）。
 *
 * 为什么单独成一个对象而不是塞进 Worker：权限判断与渠道状态**设置页也要用**
 * （开关打开时必须先确认"通知真的能弹出来"，否则用户开了开关却什么都不会发生）。
 * 两处各写一份 `checkSelfPermission` + `areNotificationsEnabled` 的判据，
 * 迟早会分叉成"设置页说能推、实际推不出去"这种最难查的不一致。
 *
 * ⚠️ 与语音/通话链路**完全无关**：这里不碰 TTS、不碰麦克风，只发一条文字通知。
 */
object ReminderNotifier {

    /** 真机排查只看日志；与 Worker 用同一个 TAG，grep 一次能看到整条链路 */
    private const val TAG = "MemoryReminder"

    const val CHANNEL_ID = "memory_reminder"

    /** 渠道名会显示在系统设置的通知列表里，写人话，别写 id */
    private const val CHANNEL_NAME = "角色的提醒"

    private const val CHANNEL_DESCRIPTION = "角色偶尔根据长期记忆主动发来的一条短消息"

    /**
     * 点通知带出去的额外数据：角色隔离键。
     *
     * **本次不做路由**（只把 App 打开即可），带上它纯粹是为了将来做深链
     * （比如直接跳到该角色的通话页）时不用改推送侧的代码 —— 通知一旦发出去就收不回来，
     * 那时候再补 extra 只能等用户升级。
     */
    const val EXTRA_CHARACTER_KEY = "extra_character_key"

    /**
     * 固定通知 id：新提醒**替换**旧的那条，而不是并排堆在通知栏里。
     *
     * 频控是"每天最多一条"，所以通知栏里同时存在两条提醒本身就说明出了问题；
     * 用固定 id 至少不会让通知越堆越多。
     */
    private const val NOTIFICATION_ID = 9001

    /**
     * 创建通知渠道。
     *
     * minSdk 26 起步，渠道是**必须**的：API 26+ 不建渠道直接 notify 会静默失败
     * （不抛异常、不显示、日志里也没有痕迹）。放在 App.onCreate 里是因为渠道要在
     * 任何一次推送之前就存在，而推送发生在后台 Worker 里，那时再建有可能与首条通知竞态。
     *
     * 重复创建同一个 id 是安全的：系统按 id 覆盖，用户已经改过的渠道设置**不会被重置**
     * （这也是不能靠"改重要性"来触发刷新的原因）。
     */
    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        if (manager == null) {
            Log.e(TAG, "通知渠道: 拿不到 NotificationManager，无法创建渠道")
            return
        }
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            // DEFAULT 而不是 HIGH：提醒是"偶尔一条消息"，不该像来电那样横幅+响铃打断用户；
            // 也不该用 LOW（会被折叠到静音区，等于白推）
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = CHANNEL_DESCRIPTION
        }
        manager.createNotificationChannel(channel)
    }

    /**
     * API 33+ 的通知权限是否已授予。
     *
     * 单独拆出来是给设置页用的：那里要区分"没权限（可以弹系统对话框要）"与
     * "有权限但通知被关了（只能引导去系统设置）"，两种情况的处理完全不同。
     */
    fun hasPostPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    /**
     * 现在到底能不能把通知弹出来（权限 + 应用级通知开关 + 本渠道是否被屏蔽）。
     *
     * 三层缺一不可：
     * · API 33+ 没授予 POST_NOTIFICATIONS → notify 静默失败；
     * · 用户在系统里关掉整个 App 的通知 → `areNotificationsEnabled()` 为 false；
     * · 用户只屏蔽了「角色的提醒」这一条渠道 → 渠道 importance == NONE。
     * 渠道那一层最容易被漏掉，而它恰好是用户"嫌吵"时最常用的操作 ——
     * 只查前两层的话，Worker 会以为推送成功并写掉 lastAt，用户却什么都没收到。
     */
    fun canPost(context: Context): Boolean {
        if (!hasPostPermission(context)) return false
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return false
        val channel = manager.getNotificationChannel(CHANNEL_ID) ?: return false
        return channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    /**
     * 推送一条提醒。
     *
     * @param characterKey 角色隔离键，仅作为点击通知的 extra 带出去（本次不路由）
     * @param title 通知标题（角色展示名，由调用方解析好）
     * @param text LLM 生成的那句话
     * @return true = 真的发出去了（调用方据此决定要不要写 `memoryReminderLastAt`）。
     *         没有权限 / 通知被关 / 渠道被屏蔽一律返回 false，**不抛异常也不崩**：
     *         后台 Worker 里抛出去只会变成一次无意义的 retry，而重试并不能变出权限来。
     */
    // 权限已由 canPost() 逐层检查，这里再让 lint 报一次 MissingPermission 没有意义
    @SuppressLint("MissingPermission")
    fun notify(context: Context, characterKey: String, title: String, text: String): Boolean {
        if (!canPost(context)) {
            Log.w(
                TAG,
                "推送: 跳过 —— 通知不可用 character=$characterKey " +
                    "权限=${hasPostPermission(context)} " +
                    "应用通知开关=${NotificationManagerCompat.from(context).areNotificationsEnabled()}"
            )
            return false
        }
        val intent = Intent(context, MainActivity::class.java).apply {
            // CLEAR_TOP：App 已在后台时复用已有实例，而不是在它上面再叠一个 MainActivity
            // （否则用户返回一次会看到同一个界面两次）。NEW_TASK 是"从通知启动 Activity"必需的。
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_CHARACTER_KEY, characterKey)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            // requestCode 用角色键的哈希：不同角色的 extra 不会互相覆盖。
            // 全部用同一个 requestCode 时，FLAG_UPDATE_CURRENT 会把前一条通知的 extra 改掉。
            characterKey.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_reminder)
            .setContentTitle(title)
            .setContentText(text)
            // 正文可能到 80 字，折叠态只显示一行，展开后要能看全
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            // 消息类通知：让系统在锁屏/免打扰策略里按"消息"处理，也方便用户按类别屏蔽
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        return try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
            Log.d(TAG, "推送: 已发出 character=$characterKey title=$title len=${text.length}")
            true
        } catch (e: Exception) {
            // 极少数 ROM 上 notify 会抛（渠道被系统回收、通知数超限等）。
            // 不写 lastAt，让下一个周期再试，而不是把这次算成"已经提醒过用户了"。
            Log.e(TAG, "推送: 失败 character=$characterKey: ${e.message}")
            false
        }
    }
}
