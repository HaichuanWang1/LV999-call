package com.lv999call.app.ui.memory

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lv999call.app.data.local.dao.PresetDao
import com.lv999call.app.data.repository.ConfigRepository
import com.lv999call.app.data.repository.MemoryRepository
import com.lv999call.app.data.repository.SessionRepository
import com.lv999call.app.domain.model.Memory
import com.lv999call.app.domain.model.Session
import com.lv999call.app.domain.usecase.SummarizeMemoryUseCase
import com.lv999call.app.preset.BuiltInCharacters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 记忆库页面（plan4 §6.2）的状态与操作。
 *
 * 三条刻意的设计：
 * 1. 所有展示用的派生数据（角色名、来源会话时间、筛选后的列表）都从**同一份**
 *    [source] 快照流上 map 出来。若每个 StateFlow 各自去订阅一次 Room 查询，
 *    同一张表会被注册好几遍查询（Room 的 Flow 是"每次收集各查一次"），页面一开就是四份。
 * 2. 删除只作用于 `memories` 这一行（[MemoryRepository.deleteMemory]），**不走删会话路径** ——
 *    用户的诉求通常是"忘掉这件事"，而不是"删掉那通电话"（plan4 §6.3）。
 * 3. 「立即整理」跑在 Application 级 scope 上（[applicationScope]），与挂断/开聊的自动总结
 *    同一条路径、同一把 Mutex：用户点完就走人，整理不该被 `onCleared()` 取消。
 */
class MemoryViewModel(
    private val memoryRepository: MemoryRepository,
    private val sessionRepository: SessionRepository,
    private val summarizeMemoryUseCase: SummarizeMemoryUseCase,
    private val configRepository: ConfigRepository,
    private val presetDao: PresetDao,
    /** [com.lv999call.app.di.AppModule.applicationScope]：整理是后台补作业，不绑页面生命周期 */
    private val applicationScope: CoroutineScope
) : ViewModel() {

    /** 三份只读快照的合并体；派生流只订阅它，不重复订阅数据库 */
    private data class Source(
        val memories: List<Memory>,
        val sessions: List<Session>,
        val presetNames: Map<Long, String>
    )

    private val source: StateFlow<Source> = combine(
        memoryRepository.getAllMemories(),
        sessionRepository.getAllSessions(),
        presetDao.getAllPresets()
    ) { memories, sessions, presets ->
        Source(
            memories = memories,
            sessions = sessions,
            // 预设名只在页面上用来把 `preset:<id>` 显示成人能认的名字
            presetNames = presets.associate { it.id to it.name }
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
        initialValue = Source(emptyList(), emptyList(), emptyMap())
    )

    private val _filter = MutableStateFlow<MemoryFilter>(MemoryFilter.All)

    /** 当前角色筛选（plan4 §6.2 的那排 FilterChip） */
    val filter: StateFlow<MemoryFilter> = _filter.asStateFlow()

    /** **筛选后**的记忆列表，新的在前（DAO 已按 createdAt DESC, id DESC 排序） */
    val memories: StateFlow<List<Memory>> = combine(source, _filter) { src, current ->
        src.memories.filter { current.matches(it.characterId) }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
        initialValue = emptyList()
    )

    /** 各角色条数与总数（顶栏条数、筛选栏计数、`未注入` 提示都要用） */
    val counts: StateFlow<MemoryCounts> = source.map { src ->
        MemoryCounts(
            total = src.memories.size,
            byCharacterKey = src.memories.groupingBy { it.characterId }.eachCount(),
            presetTotal = src.memories.count { it.characterId.startsWith(Session.CHARACTER_KEY_PRESET_PREFIX) },
            flaggedTotal = src.memories.count { it.category == Memory.CATEGORY_SUMMARY_FLAGGED }
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
        initialValue = MemoryCounts(0, emptyMap(), 0, 0)
    )

    /** 角色键 → 展示名（内置角色用显示名、自定义预设用预设名、其余归到「默认」） */
    val characterNames: StateFlow<Map<String, String>> = source.map { src ->
        src.memories.map { it.characterId }.distinct()
            .associateWith { roleNameOf(it, src.presetNames) }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
        initialValue = emptyMap()
    )

    /** 会话 id → 创建时刻，「来自哪通电话」用它。查不到 = 会话已不在（外键级联下不该发生，兜底用） */
    val sessionCreatedAt: StateFlow<Map<String, Long>> = source.map { src ->
        src.sessions.associate { it.id to it.createdAt }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
        initialValue = emptyMap()
    )

    /** 筛选栏：全部 / 各内置角色 / 自定义 / 默认，各自带条数 */
    val filterChips: StateFlow<List<MemoryFilterChip>> = combine(source, _filter) { src, current ->
        buildChips(src, current)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
        initialValue = emptyList()
    )

    /**
     * 「有 N 通对话还没整理」的 N（plan4 §5.5 的补充信号）。
     *
     * Room 不会为"候选会话查询"发通知（它不是一次可观察查询），所以只在订阅时与每次
     * 整理结束后各算一次 —— 这两个时点正好是用户能看见它的全部时点。
     */
    private val pendingRefresh = MutableStateFlow(0)

    val pendingCount: StateFlow<Int> = pendingRefresh
        .map { sessionRepository.countSessionsWithPendingMemory() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
            initialValue = 0
        )

    private val _isSummarizing = MutableStateFlow(false)

    /** 整理进行中（按钮显示进度、禁止重复点击） */
    val isSummarizing: StateFlow<Boolean> = _isSummarizing.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)

    /** 一次性提示（整理/导出结果）；UI 用 Snackbar 展示后调 [consumeStatusMessage] 清掉 */
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    fun consumeStatusMessage() {
        _statusMessage.value = null
    }

    fun selectFilter(newFilter: MemoryFilter) {
        _filter.value = newFilter
    }

    /** 单条删除：精确删 `memories` 这一行，不碰会话 */
    fun deleteMemory(id: Long) {
        viewModelScope.launch { memoryRepository.deleteMemory(id) }
    }

    /** 一键清空 */
    fun clearAll() {
        viewModelScope.launch { memoryRepository.clearAllMemories() }
    }

    /** 按角色清空（当前 UI 只暴露了"一键清空"，这条留给后续的"清掉某个角色"入口） */
    fun clearByCharacter(characterId: String) {
        viewModelScope.launch { memoryRepository.clearMemoriesByCharacter(characterId) }
    }

    /**
     * 「立即整理」：手动跑一遍 §5.5 的补总结。
     *
     * 与开聊时的自动补总结有三处刻意的不同：
     * · 不限角色：用户点的是"把待整理的都整理掉"，页面本来就是全局视角；
     * · 不排除"刚建的会话"：这一刻没有通话在进行，不存在"自己总结自己"；
     * · 一次最多 [MAX_MANUAL_SUMMARIES] 通，且从**最老**的开始：每通是一次付费 LLM 调用，
     *   积压几十通时一次点下去代价太不可控。剩下的下次再点（提示里会报还剩几通）。
     */
    fun summarizePending() {
        if (_isSummarizing.value) return
        _isSummarizing.value = true
        _statusMessage.value = null
        applicationScope.launch {
            try {
                // 总开关关掉 = 不读不写（plan4 §5.7）。手动整理属于"补总结"的另一种触发点，
                // 同样受它约束；不然用户以为停用了，后台却还在写用户画像。
                if (!isMemoryEnabled()) {
                    _statusMessage.value = "长期记忆总开关已关闭（设置 → 🧠 长期记忆）"
                    return@launch
                }
                val backlog = sessionRepository.getSessionsWithPendingMemory()
                    // DAO 是 createdAt 倒序；翻成时间正序再补：后一条记忆要靠前一条的上下文，
                    // 顺序反了会让"更早的对话"变得像是刚发生的
                    .asReversed()
                    .take(MAX_MANUAL_SUMMARIES)
                if (backlog.isEmpty()) {
                    _statusMessage.value = "没有需要整理的对话"
                    return@launch
                }
                Log.d(TAG, "立即整理: 待整理=${backlog.size} 通（一次最多 $MAX_MANUAL_SUMMARIES 通，失败即停）")
                var done = 0
                for (session in backlog) {
                    when (val result = summarizeMemoryUseCase.summarize(sessionId = session.id)) {
                        is SummarizeMemoryUseCase.SummarizeResult.Success -> done++
                        is SummarizeMemoryUseCase.SummarizeResult.Failed -> {
                            // 失败即停：继续往下只会把后面的也一起打挂（与自动补总结同一套策略）
                            Log.w(TAG, "立即整理: 失败即停 session=${session.id.take(8)} 原因=${result.reason}")
                            _statusMessage.value = "整理中断：${result.reason}（已完成 $done 通，可稍后再试）"
                            return@launch
                        }
                        // 门槛没过 / 游标已在末尾：不算失败，接着看下一条
                        else -> Log.d(TAG, "立即整理: 跳过 session=${session.id.take(8)} → $result")
                    }
                }
                val left = sessionRepository.countSessionsWithPendingMemory()
                _statusMessage.value = when {
                    done == 0 -> "这 ${backlog.size} 通还没到值得记录的门槛"
                    left > 0 -> "已整理 $done 通，还剩 $left 通（可再点一次）"
                    else -> "已整理 $done 通"
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // 读配置/读库失败必须让用户看见，否则"点了没反应"和"整理完了"长得一模一样
                Log.e(TAG, "立即整理异常: ${e.message}")
                _statusMessage.value = "整理失败：${e.message ?: e.javaClass.simpleName}"
            } finally {
                _isSummarizing.value = false
                // 整理会推游标，"待整理"条数必须重算
                pendingRefresh.value++
            }
        }
    }

    /**
     * 生成导出文本（角色 · 时间 · 正文 · 来源会话），由 UI 落盘。
     *
     * 为什么导出**全部**记忆而不是当前筛选结果：用户在筛选状态下点"导出"，
     * 想拿到的通常是"我这个 App 到底记了我什么"的完整备份（plan4 §6.2 / R10 的可审阅底线），
     * 少了一半的备份比多导几条危险得多。筛选状态在导出内容的标题行里有交代。
     *
     * 这里一次性读库而不是复用 [memories] 流：导出要的是"此刻落盘的一份快照"，
     * 流里的值在写文件期间还可能被后台总结改掉。
     */
    suspend fun buildExportText(): String {
        val all = memoryRepository.getAllMemories().first()
        val sessionAt = sessionRepository.getAllSessions().first().associate { it.id to it.createdAt }
        val presetNames = presetDao.getAllPresets().first().associate { it.id to it.name }
        val formatter = SimpleDateFormat(EXPORT_TIME_PATTERN, Locale.getDefault())
        val flagged = all.count { it.category == Memory.CATEGORY_SUMMARY_FLAGGED }

        val sb = StringBuilder()
        sb.append("lv999call 记忆库导出\n")
        sb.append("导出时间：").append(formatter.format(Date())).append('\n')
        sb.append("共 ").append(all.size).append(" 条")
        if (flagged > 0) sb.append("（其中 ").append(flagged).append(" 条标记为「未注入」，不会进入上下文）")
        sb.append('\n')
        if (_filter.value != MemoryFilter.All) {
            sb.append("（注意：这是全部记忆，不是你当前筛选出来的那部分）\n")
        }
        if (all.isEmpty()) {
            sb.append("\n（记忆库是空的）\n")
            return sb.toString()
        }
        for (memory in all) {
            sb.append("\n────────────────────\n")
            sb.append('[').append(roleNameOf(memory.characterId, presetNames)).append("] ")
            sb.append(formatter.format(Date(memory.createdAt)))
            sb.append(" · 重要度 ").append(memory.importance)
            if (memory.category == Memory.CATEGORY_SUMMARY_FLAGGED) sb.append(" · 未注入")
            sb.append('\n')
            val sessionAtMillis = sessionAt[memory.sessionId]
            sb.append("来自 ")
                .append(if (sessionAtMillis != null) "${formatter.format(Date(sessionAtMillis))} 的通话" else "已找不到的通话")
                .append('\n')
            sb.append(memory.content.trim()).append('\n')
        }
        return sb.toString()
    }

    /** SAF 的默认文件名（用户仍能在系统选择器里改）；带时间戳，免得反复导出互相覆盖 */
    fun exportFileName(): String =
        "记忆库-" + SimpleDateFormat(FILE_TIME_PATTERN, Locale.getDefault()).format(Date()) + ".txt"

    // --- 辅助 ---

    /** 长期记忆总开关；读配置失败按**关闭**处理（拿不准时"不写用户画像"更安全，与 CallViewModel 同口径） */
    private suspend fun isMemoryEnabled(): Boolean = try {
        configRepository.configFlow.first().memoryAutoSummarizeEnabled
    } catch (e: Exception) {
        Log.e(TAG, "读取长期记忆开关失败，按关闭处理: ${e.message}")
        false
    }

    /**
     * 角色键 → 展示名。
     *
     * 三类键各有各的名字来源：内置角色在 [BuiltInCharacters] 里（唯一事实来源）、
     * 自定义预设在 `presets` 表里、其余都归到「默认」桶。
     * 预设被删掉后它的记忆**不会**跟着消失（memories 只外键到 sessions），所以查不到名字时
     * 要给一个通用名而不是空白 —— 那种记忆仍然要能被看见、被删掉。
     */
    private fun roleNameOf(characterKey: String, presetNames: Map<Long, String>): String = when {
        characterKey == Session.CHARACTER_KEY_DEFAULT -> DEFAULT_ROLE_NAME
        characterKey.startsWith(Session.CHARACTER_KEY_PRESET_PREFIX) -> {
            // 解析口径与写入侧共用 Session.presetIdFromCharacterKey：两处各写一份
            // "去掉 preset: 前缀再 toLong" 的话，将来改前缀就会静默失配
            val presetId = Session.presetIdFromCharacterKey(characterKey)
            (presetId?.let { presetNames[it] }) ?: PRESET_FALLBACK_NAME
        }
        else -> BuiltInCharacters.byId(characterKey)?.displayName ?: characterKey
    }

    /**
     * 拼筛选栏。
     *
     * 显示规则不是"有记忆才出现"一刀切：
     * · 内置角色**永远**占一格 —— 它们是主要的记忆桶，固定出现才不会让筛选栏"时有时无"；
     * · 自定义 / 默认只在有内容（或正被选中）时出现，0 条时列出来纯属噪声；
     * · 正被选中的那一格即便归零也要留着，否则删掉最后一条记忆时选中态会被悄悄重置成"全部",
     *   列表突然跳成另一批内容。
     * · 还有一类键：曾经的内置角色（改过 id / 已下线）。它们没有对应的 chip 就会变成
     *   "只有切到全部才看得见"的记忆，所以也补一格，名字退回键本身。
     */
    private fun buildChips(src: Source, selected: MemoryFilter): List<MemoryFilterChip> {
        val byKey = src.memories.groupingBy { it.characterId }.eachCount()
        val presetTotal = src.memories.count { it.characterId.startsWith(Session.CHARACTER_KEY_PRESET_PREFIX) }
        val defaultFilter = MemoryFilter.Single(Session.CHARACTER_KEY_DEFAULT)

        val chips = mutableListOf(MemoryFilterChip(MemoryFilter.All, "全部", src.memories.size))
        BuiltInCharacters.ALL.forEach { character ->
            chips += MemoryFilterChip(
                filter = MemoryFilter.Single(character.id),
                label = character.displayName,
                count = byKey[character.id] ?: 0
            )
        }
        if (presetTotal > 0 || selected == MemoryFilter.Presets) {
            chips += MemoryFilterChip(MemoryFilter.Presets, "自定义", presetTotal)
        }
        val defaultTotal = byKey[Session.CHARACTER_KEY_DEFAULT] ?: 0
        if (defaultTotal > 0 || selected == defaultFilter) {
            chips += MemoryFilterChip(defaultFilter, DEFAULT_ROLE_NAME, defaultTotal)
        }
        val knownKeys = BuiltInCharacters.ALL.map { it.id }.toSet() + Session.CHARACTER_KEY_DEFAULT
        byKey.keys
            .filterNot { it in knownKeys || it.startsWith(Session.CHARACTER_KEY_PRESET_PREFIX) }
            .forEach { legacyKey ->
                chips += MemoryFilterChip(
                    filter = MemoryFilter.Single(legacyKey),
                    label = roleNameOf(legacyKey, src.presetNames),
                    count = byKey[legacyKey] ?: 0
                )
            }
        return chips
    }

    class Factory(
        private val memoryRepository: MemoryRepository,
        private val sessionRepository: SessionRepository,
        private val summarizeMemoryUseCase: SummarizeMemoryUseCase,
        private val configRepository: ConfigRepository,
        private val presetDao: PresetDao,
        private val applicationScope: CoroutineScope
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return MemoryViewModel(
                memoryRepository = memoryRepository,
                sessionRepository = sessionRepository,
                summarizeMemoryUseCase = summarizeMemoryUseCase,
                configRepository = configRepository,
                presetDao = presetDao,
                applicationScope = applicationScope
            ) as T
        }
    }

    companion object {
        private const val TAG = "MemoryVM"

        /** 与其它页面的 `stateIn` 保持一致：停止订阅 5 秒后才真的断流，避免转屏时重新查库 */
        private const val SUBSCRIPTION_TIMEOUT_MS = 5_000L

        /** 一次「立即整理」最多补几通：每通都是一次付费 LLM 调用，不能一次点下去没上限 */
        private const val MAX_MANUAL_SUMMARIES = 10

        private const val DEFAULT_ROLE_NAME = "默认"
        private const val PRESET_FALLBACK_NAME = "自定义方案"

        private const val EXPORT_TIME_PATTERN = "yyyy-MM-dd HH:mm"
        private const val FILE_TIME_PATTERN = "yyyyMMdd-HHmm"
    }
}

/**
 * 角色筛选。
 *
 * 用密封接口而不是"一个字符串 + 到处特判"：`preset:` 前缀桶（所有自定义预设合成一格）
 * 与单个角色键是两种粒度，字符串表达不了这种差异。
 */
sealed interface MemoryFilter {

    /** 该筛选是否包含这个角色键 */
    fun matches(characterKey: String): Boolean

    /** 全部 */
    data object All : MemoryFilter {
        override fun matches(characterKey: String) = true
    }

    /** 单个角色键（内置角色 id / `preset:<id>` / `default`） */
    data class Single(val key: String) : MemoryFilter {
        override fun matches(characterKey: String) = characterKey == key
    }

    /** 全部自定义预设（合并所有 `preset:` 桶） */
    data object Presets : MemoryFilter {
        override fun matches(characterKey: String) =
            characterKey.startsWith(Session.CHARACTER_KEY_PRESET_PREFIX)
    }
}

/** 筛选栏上的一格 */
data class MemoryFilterChip(
    val filter: MemoryFilter,
    val label: String,
    val count: Int
)

/** 记忆条数统计（顶栏、筛选栏、`未注入` 提示共用） */
data class MemoryCounts(
    val total: Int,
    /** 各角色键的条数（含 `preset:<id>` 这种细分键，不合并） */
    val byCharacterKey: Map<String, Int>,
    /** 所有自定义预设合起来的条数 */
    val presetTotal: Int,
    /** 被标记为 `summary_flagged`（不注入上下文）的条数 */
    val flaggedTotal: Int
)
