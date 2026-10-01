package com.lv999call.app.notify

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.lv999call.app.App
import com.lv999call.app.di.AppModule
import com.lv999call.app.domain.model.Memory
import com.lv999call.app.domain.model.Session
import com.lv999call.app.domain.usecase.ComposeReminderUseCase
import com.lv999call.app.preset.BuiltInCharacters
import kotlinx.coroutines.flow.first
import java.util.Calendar

/**
 * 「记忆提醒通知」的后台周期任务：定期让 LLM 读一遍角色的长期记忆，生成一条短消息推给用户。
 *
 * 这里**只发文字通知**：不合成语音、不播放、不碰麦克风 —— 用户可能正在开会或睡觉，
 * 一条通知可以等，一段声音不行。
 *
 * 判据顺序是刻意的，从"最便宜且最确定"到"最贵"排列（每一步都能省掉后面所有的开销）：
 * 配置开关 → 记忆总开关 → 通知可用性 → 静默时段 → 24 小时频控 → 选角色 → 取记忆 → 调 LLM。
 * 前六步都不花钱、不联网，只有最后一步才真的打上游。
 *
 * 依赖来自 `(applicationContext as App).appModule`（项目是手动 DI，没有 Hilt）。
 * 每个判据都打日志，TAG 固定 [TAG] —— 真机排查只能靠 `adb logcat -s MemoryReminder`。
 */
class MemoryReminderWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        // 兜底再建一次渠道：正常情况下 App.onCreate 已经建好了，但渠道是 notify 静默失败
        // 最常见的原因，这里多花一次系统调用换掉一整类"什么都不发生"的故障
        ReminderNotifier.ensureChannel(applicationContext)

        val module = (applicationContext as? App)?.appModule
        if (module == null) {
            // 拿不到依赖说明进程状态异常（理论上不会发生）。重试也变不出 appModule，
            // 直接成功结束，等下个周期自然再来一次
            Log.e(TAG, "跳过: 取不到 appModule（applicationContext 不是 App？）")
            return Result.success()
        }

        val now = System.currentTimeMillis()

        // ---- Step 1：总开关 ----
        val config = try {
            module.configRepository.configFlow.first()
        } catch (e: Exception) {
            // 配置读失败按"跳过"处理而不是 retry：DataStore 的瞬时故障重试几次也好不了，
            // 而 retry 会在退避窗口里反复读同一个坏文件。下个周期重来即可。
            Log.e(TAG, "跳过: 读取配置失败: ${e.message}")
            return Result.success()
        }
        if (!config.memoryReminderEnabled) {
            Log.d(TAG, "跳过: 记忆提醒开关未开启（Result.success，不重试）")
            return Result.success()
        }

        // ---- Step 2：长期记忆总开关 ----
        // 记忆总开关关掉后"记忆"这件事整体停用（不总结也不注入）。这时再拿记忆去打扰用户，
        // 既违背用户关掉它的意图，也会拿一份不会更新的旧记忆反复生成同样的文案
        if (!config.memoryAutoSummarizeEnabled) {
            Log.d(TAG, "跳过: 长期记忆总开关已关闭")
            return Result.success()
        }

        // ---- Step 3：通知能不能发出去 ----
        // 权限/应用通知开关/渠道屏蔽三层任一不满足 → 不推送。
        // 这里用 canPost（比"只查权限"更严）是有意的：只查权限的话，用户屏蔽了
        // 「角色的提醒」渠道时我们仍会调 LLM 生成一条推不出去的消息，白烧一次调用
        if (!ReminderNotifier.canPost(applicationContext)) {
            Log.d(TAG, "跳过: 通知不可用（权限未授予 / 应用通知被关 / 渠道被屏蔽）")
            return Result.success()
        }

        // ---- Step 4：静默时段 22:00–09:00 ----
        // 半夜弹通知是骚扰，而且这条通知本身是"闲聊式"的，没有任何紧急到需要吵醒用户的价值。
        // 直接 Result.success 而不是 retry：不是失败，只是"这个时间点不该做"，下个周期再说
        val hour = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.HOUR_OF_DAY)
        if (hour >= QUIET_START_HOUR || hour < QUIET_END_HOUR) {
            Log.d(TAG, "跳过: 静默时段 hour=$hour（$QUIET_START_HOUR:00–0$QUIET_END_HOUR:00 不打扰）")
            return Result.success()
        }

        // ---- Step 5：24 小时频控 ----
        // 周期是 12 小时，所以"一天最多一条"只能靠时间戳拦。
        // ⚠️ lastAt 为 0（从未推送）时 `now - 0` 是个巨大的正数，天然放行，不用特判。
        // 用户改过系统时间时差值可能为负，同样落在 <24h 里 → 跳过，比"补推一条"安全
        val sinceLast = now - config.memoryReminderLastAt
        if (sinceLast < MIN_INTERVAL_MS) {
            Log.d(
                TAG,
                "跳过: 频控 —— 距上次提醒 ${sinceLast / 3_600_000} 小时" +
                    "（下限 ${MIN_INTERVAL_MS / 3_600_000} 小时）"
            )
            return Result.success()
        }

        // ---- Step 6：首选角色 = 最新一条会话的角色 ----
        // 用"最新的会话"而不是"最近有记忆的角色"：提醒要接着**最近这次聊天**往下说，
        // 一个三个月没聊过的角色的记忆翻出来发消息只会显得诡异。
        val latestSession = try {
            module.sessionRepository.getAllSessions().first().firstOrNull()
        } catch (e: Exception) {
            Log.e(TAG, "跳过: 读取会话失败: ${e.message}")
            return Result.success()
        }
        if (latestSession == null) {
            Log.d(TAG, "跳过: 还没有任何会话（先聊一次再谈提醒）")
            return Result.success()
        }
        // 老数据里 characterKey 可能是空串；空串会查不到任何记忆，统一并到默认桶
        val preferredKey = latestSession.characterKey.ifBlank { Session.CHARACTER_KEY_DEFAULT }

        // ---- Step 7：取记忆（首选角色没有就回落到"最近有记忆的角色"，见 resolveTarget）----
        val target = resolveTarget(module, preferredKey)
        if (target == null) {
            Log.d(TAG, "跳过: 没有任何可用记忆（首选 character=$preferredKey 也没有）")
            return Result.success()
        }
        val (characterKey, eligible) = target
        val roleName = resolveRoleName(module, characterKey)
        Log.d(
            TAG,
            "选中: character=$characterKey name=$roleName 可用记忆=${eligible.size}条" +
                "（首选=$preferredKey session=${latestSession.id.take(8)}）"
        )

        // ---- Step 8：生成 + 推送 ----
        Log.d(TAG, "生成: character=$characterKey 可用记忆=${eligible.size}条")
        return when (val result = module.composeReminderUseCase.compose(characterKey, eligible, now, roleName)) {
            is ComposeReminderUseCase.ReminderResult.Success -> {
                // lastAt 只在**通知真的发出去之后**才写：提前写会让一次失败白吃掉一整天的配额
                val posted = ReminderNotifier.notify(applicationContext, characterKey, roleName, result.text)
                if (posted) {
                    module.configRepository.updateMemoryReminderLastAt(now)
                    Log.d(TAG, "完成: 已推送并记录 lastAt=$now character=$characterKey")
                } else {
                    // 走到这里说明 canPost 之后、notify 之前状态又变了（用户刚好在系统里关掉）。
                    // 不写 lastAt，也不 retry —— 重试同样发不出去，而且会再烧一次 LLM 调用；
                    // 下个 12 小时周期会重新检查一遍权限
                    Log.w(TAG, "完成: 通知未能发出（权限/渠道在检查后被改动？）→ 不写 lastAt，不重试")
                }
                Result.success()
            }

            ComposeReminderUseCase.ReminderResult.NothingToSay -> {
                // 「没什么可说的」是**正常输出**（提示词规定了模型可以只输出「无」），
                // 所以这里打 D 级日志而不是 ERROR，并且**照样推进 lastAt**：
                // 同一批记忆下一轮问还是「无」，不推进的话每个周期都白烧一次 LLM 调用
                module.configRepository.updateMemoryReminderLastAt(now)
                Log.d(TAG, "完成: 没什么可说的 → 不推送，但推进 lastAt=$now（避免每周期重烧同一批记忆）")
                Result.success()
            }

            is ComposeReminderUseCase.ReminderResult.Failed -> {
                // 网络/超时类失败值得重试：退避策略（EXPONENTIAL）在调度处设置，
                // 这里只管把"该再试一次"这个事实告诉 WorkManager
                Log.w(TAG, "失败: ${result.reason} → Result.retry()（指数退避）")
                Result.retry()
            }
        }
    }

    /**
     * 挑出这次要用的角色与它的可用记忆；实在挑不出返回 null（= 今天没什么可说的）。
     *
     * 先试"最近聊过的角色"，空了再回落到"最近有可用记忆的角色"。
     *
     * 为什么要这一步回落（这是实现完之后审查逮到的一个**静默失效**）：
     * 最初只认"最新一条会话的角色"，可那个桶里完全可能一条记忆都没有 ——
     * 最近一通是快速模式（`default`）、刚认识的新角色、或那通根本没到总结门槛。
     * 这时每个周期都会停在"没有可用记忆"处跳过，**用户永远收不到提醒，
     * 而别的角色明明有记忆**，日志里还只是一行 D 级跳过，看不出功能是坏的。
     * 代价很低（一次不花钱的查询），但它是静默的，必须堵掉。
     */
    private suspend fun resolveTarget(
        module: com.lv999call.app.di.AppModule,
        preferredKey: String
    ): Pair<String, List<Memory>>? {
        val preferred = eligibleMemories(module, preferredKey)
        if (preferred.isNotEmpty()) return preferredKey to preferred

        val fallbackKey = try {
            module.memoryRepository.latestEligibleCharacterKey()
        } catch (e: Exception) {
            Log.e(TAG, "跳过: 查询最近有记忆的角色失败: ${e.message}")
            return null
        } ?: return null // 库里一条可用记忆都没有，那就是真的没什么可说的
        // 回落结果与首选是同一个桶 → 上面已经确认它是空的，不必再查一遍
        if (fallbackKey == preferredKey) return null

        Log.d(TAG, "回落: 首选角色 $preferredKey 没有可用记忆 → 改用最近有记忆的 $fallbackKey")
        val fallback = eligibleMemories(module, fallbackKey)
        return if (fallback.isEmpty()) null else fallbackKey to fallback
    }

    /**
     * 取某角色**可用**的记忆（过滤掉 flagged 的）；读库失败按"没有"处理。
     *
     * 🛡️ 为什么必须过滤 flagged（含指令式措辞的条目）：它们的正文里可能夹着
     * "忽略…""你必须…"，喂给 LLM 就变成自生成注入 —— 在通知这条链路上，注入的后果是
     * 用户收到一条由自己的记忆"指挥"出来的消息。口径与 [LoadMemoryUseCase] 保持一致，
     * 也与 `latestEligibleCharacterKey()` 的排除口径一致（否则会挑出一个"有记忆但一条都不注入"的角色）。
     *
     * 读库失败不 retry：DataStore/Room 的瞬时故障重试也好不了，下个周期自然重来
     * （与 Worker 里其它读库失败的处置一致）。
     */
    private suspend fun eligibleMemories(
        module: com.lv999call.app.di.AppModule,
        characterKey: String
    ): List<Memory> = try {
        module.memoryRepository.getMemoriesByCharacterOnce(characterKey)
            .filter { it.category != Memory.CATEGORY_SUMMARY_FLAGGED }
    } catch (e: Exception) {
        Log.e(TAG, "跳过: 读取记忆失败 character=$characterKey: ${e.message}")
        emptyList()
    }

    /**
     * 角色键 → 展示名（通知标题 + 提示词里的角色名）。
     *
     * 三类键各有各的名字来源：内置角色在 [BuiltInCharacters] 里（唯一事实来源）、
     * 自定义预设在 `presets` 表里、其余都归到「默认」桶。
     * 与 `MemoryViewModel.roleNameOf` 是同一套口径 —— 两处显示同一个角色的名字必须一致，
     * 否则用户在记忆库看到「我的银狼」、收到的通知标题却是别的名字。
     *
     * 预设名要查库，所以只在确实是预设键时才查（内置/默认桶不必为此多跑一次查询）。
     */
    private suspend fun resolveRoleName(module: AppModule, characterKey: String): String =
        when {
            characterKey == Session.CHARACTER_KEY_DEFAULT -> DEFAULT_ROLE_NAME
            characterKey.startsWith(Session.CHARACTER_KEY_PRESET_PREFIX) -> {
                // 解析口径与写入侧共用 Session.presetIdFromCharacterKey：各写一份
                // "去掉 preset: 前缀再 toLong" 的话，将来改前缀就会静默失配
                val presetId = Session.presetIdFromCharacterKey(characterKey)
                val name = if (presetId == null) {
                    null
                } else {
                    try {
                        module.presetDao.getAllPresets().first()
                            .firstOrNull { it.id == presetId }?.name
                    } catch (e: Exception) {
                        Log.w(TAG, "读取预设名失败 presetId=$presetId: ${e.message}")
                        null
                    }
                }
                name ?: PRESET_FALLBACK_NAME
            }
            else -> BuiltInCharacters.byId(characterKey)?.displayName ?: characterKey
        }

    companion object {
        /** 唯一排查入口：`adb logcat -s MemoryReminder` 能看到这条链路的所有分支 */
        private const val TAG = "MemoryReminder"

        /** 静默时段：22:00 起、09:00 止（本地时间） */
        private const val QUIET_START_HOUR = 22
        private const val QUIET_END_HOUR = 9

        /** 两次提醒的最小间隔（24 小时）。周期是 12 小时，所以"一天最多一条"靠它兜 */
        private const val MIN_INTERVAL_MS = 24 * 60 * 60 * 1000L

        /** 展示名兜底（与 MemoryViewModel 同词，别改成空串） */
        private const val DEFAULT_ROLE_NAME = "默认"
        private const val PRESET_FALLBACK_NAME = "自定义方案"
    }
}
