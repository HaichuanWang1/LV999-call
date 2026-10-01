package com.lv999call.app

import android.app.Application
import android.util.Log
import com.lv999call.app.di.AppModule
import com.lv999call.app.notify.MemoryReminderScheduler
import com.lv999call.app.notify.ReminderNotifier
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class App : Application() {

    lateinit var appModule: AppModule
        private set

    override fun onCreate() {
        super.onCreate()
        appModule = AppModule(this)

        // 通知渠道必须在**任何一次推送之前**存在：API 26+ 没有渠道的 notify 是静默失败的
        // （不抛异常、不显示、日志里也没痕迹）。放在这里是因为推送发生在后台 Worker 里，
        // 而 Worker 进程一定先跑过 Application.onCreate，这里建是唯一的确定时机
        ReminderNotifier.ensureChannel(this)

        // 只在配置已开启时才入队。
        // ⚠️ 这里**不能阻塞** onCreate（读 DataStore 是一次磁盘 IO，会拖慢冷启动、可能触发 ANR），
        // 所以丢到 applicationScope 里异步读一次配置。
        // 用 applicationScope 而不是 GlobalScope：它的 SupervisorJob 保证这次读取失败
        // 不会影响后续任何任务，语义上也归属这个 App 实例。
        appModule.applicationScope.launch {
            val enabled = try {
                appModule.configRepository.configFlow.first().memoryReminderEnabled
            } catch (e: Exception) {
                // 读失败时**什么都不做**（既不入队也不取消）：拿不准的状态下取消掉，
                // 可能把用户刚在设置页打开的任务误杀，而它下次启动还会再试一次
                Log.e(TAG, "读取记忆提醒开关失败，本次不同步调度: ${e.message}")
                return@launch
            }
            // sync 内部是幂等的：开启 → 入队（已存在则保持），关闭 → 取消
            MemoryReminderScheduler.sync(this@App, enabled)
        }
    }

    companion object {
        private const val TAG = "MemoryReminder"
    }
}
