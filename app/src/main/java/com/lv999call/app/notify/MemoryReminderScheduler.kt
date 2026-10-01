package com.lv999call.app.notify

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * 记忆提醒的调度入口（唯一一处入队/取消的地方）。
 *
 * 为什么收成一个对象而不是让 App 与设置页各自调 WorkManager：两边必须用**同一个**
 * unique name 与同一套约束，否则"设置页开的那个"和"App 启动时入队的那个"会变成两个
 * 独立任务，用户关掉开关只能取消掉其中一个，另一个照常在后台跑。
 *
 * 用 `enqueueUniquePeriodicWork` + [ExistingPeriodicWorkPolicy.KEEP]：
 * 重复调用是安全的，且不会把已经在跑的任务重置（重置会让"每 12 小时一次"变成
 * "每次 App 启动都重新计时"，用户可能一直等不到那一条）。
 */
object MemoryReminderScheduler {

    /** 与 Worker 同 TAG，日志一条线看下来 */
    private const val TAG = "MemoryReminder"

    /** 唯一任务名。改它等于放弃对所有已入队旧任务的取消能力，别随手改 */
    const val UNIQUE_WORK_NAME = "memory_reminder"

    /**
     * 周期 12 小时。
     *
     * 为什么不直接用 24 小时：WorkManager 的周期任务是**不精确**的，会被系统按省电策略
     * 合并、推迟（Doze 下可能晚几个小时）。用 24 小时的话，"每天一条"很容易变成"两天一条"；
     * 12 小时留出余量，真正的"一天最多一条"由 Worker 里的 24 小时时间戳频控兜底。
     *
     * ⚠️ PeriodicWorkRequest 的最小周期是 15 分钟，比它小的值会被自动抬到 15 分钟。
     */
    private const val INTERVAL_HOURS = 12L

    /**
     * 重试退避的初始延迟（秒）。
     *
     * 指数退避（[BackoffPolicy.EXPONENTIAL]）下每次重试翻倍。取 WorkRequest 允许的最小值
     * （`WorkRequest.MIN_BACKOFF_MILLIS` = 10 秒）而不是 WorkManager 默认的 30 秒：
     * 本任务的失败几乎全是网络抖动，早点重试大概率就成了，拖延只会让"今天该到的那条消息"
     * 更晚到。写死 10L 而不是引用那个常量，是为了让"这是最小值"这件事在代码里看得见
     * （写小了 WorkManager 会自动抬到 10 秒，静默改变行为反而更难查）。
     */
    private const val BACKOFF_SECONDS = 10L

    /**
     * 首次执行的延迟（分钟）。
     *
     * 见 [enqueue]：不设的话第一次要等满一个周期，用户开完开关半天看不到反馈。
     * 取 30 分钟而不是"立刻跑一次"：刚打开开关就弹一条会显得像在验证功能，
     * 而提醒的语义是"过一阵想起你"，留一点时间差才自然。
     */
    private const val INITIAL_DELAY_MINUTES = 30L

    /**
     * 开关状态的唯一同步入口：开启 → 入队；关闭 → 取消。
     *
     * 设置页的开关变化与 `App.onCreate` 都调它，保证"用户看到的开关状态"与
     * "后台有没有任务"永远一致。
     */
    fun sync(context: Context, enabled: Boolean) {
        if (enabled) enqueue(context) else cancel(context)
    }

    /** 入队（幂等）。任务已存在时保持原样，见类注释 */
    fun enqueue(context: Context) {
        try {
            val request = PeriodicWorkRequestBuilder<MemoryReminderWorker>(
                INTERVAL_HOURS, TimeUnit.HOURS
            )
                // 需要网络：生成文案要调 LLM。没网时让 WorkManager 把任务留到有网再跑，
                // 而不是让我们自己失败一次再退避（后者会白烧一次"失败-重试"的往返）
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    BACKOFF_SECONDS,
                    TimeUnit.SECONDS
                )
                // 首次延迟 30 分钟。
                //
                // 不设的话，PeriodicWorkRequest 的**第一次执行要等满一个周期（12 小时）**——
                // 用户刚打开开关，半天内看不到任何反馈，会以为功能是坏的（真机验证也没法等）。
                // 30 分钟足够让"刚聊完的那一通"沉淀出记忆，又不至于开关一打开就立刻弹一条。
                // 之后仍按 INTERVAL_HOURS 走；"一天最多一条"由 Worker 里的 24 小时频控兜底。
                .setInitialDelay(INITIAL_DELAY_MINUTES, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
            Log.d(TAG, "调度: 已入队 unique=$UNIQUE_WORK_NAME 周期=${INTERVAL_HOURS}h 退避=指数(${BACKOFF_SECONDS}s)")
        } catch (e: Exception) {
            // WorkManager 未初始化（例如被某个裁剪过的构建去掉 InitializationProvider）时
            // getInstance 会抛 IllegalStateException。这条链路是"锦上添花"的功能，
            // 绝不能因为它把 App 启动流程打挂
            Log.e(TAG, "调度: 入队失败: ${e.message}")
        }
    }

    /** 取消（幂等）。没有已入队的任务时是空操作 */
    fun cancel(context: Context) {
        try {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
            Log.d(TAG, "调度: 已取消 unique=$UNIQUE_WORK_NAME")
        } catch (e: Exception) {
            Log.e(TAG, "调度: 取消失败: ${e.message}")
        }
    }
}
