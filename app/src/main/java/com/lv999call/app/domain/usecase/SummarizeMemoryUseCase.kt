package com.lv999call.app.domain.usecase

import android.content.Context
import android.util.Log
import com.lv999call.app.data.repository.ChatRepository
import com.lv999call.app.data.repository.ConfigRepository
import com.lv999call.app.data.repository.MemoryRepository
import com.lv999call.app.data.repository.SessionRepository
import com.lv999call.app.domain.model.ChatMessage
import com.lv999call.app.domain.model.ExpressionSet
import com.lv999call.app.domain.model.Memory
import com.lv999call.app.domain.model.MemorySourceMessage
import com.lv999call.app.preset.BuiltInCharacters
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 把一段对话总结成一条长期记忆（plan4 §3）。
 *
 * 一条硬约束先写在最前面：**本用例永不播放 TTS、绝不碰麦克风与播放器**。
 * 它只调 [ChatRepository.streamChatCompletion]（纯文本通道），所以挂断后跑在后台
 * 也不会打断任何东西（plan4 §3.3）。
 *
 * 三条容易踩的实现约定（照着 plan4 的审查结论走，别"顺手优化"）：
 * 1. 调用方启动它必须用 Application 级 scope（[com.lv999call.app.di.AppModule.applicationScope]），
 *    不能用 `viewModelScope` —— 用户挂断后立刻回历史页，通话页 ViewModel 被清理时
 *    会把总结协程一起取消。
 * 2. 失败/超时**不动游标**：游标一推，这段对话就永远不会再被总结。不动游标只是"延迟"，
 *    下次开聊的补总结或下一次同角色通话会把它一并总结掉。
 * 3. 错误只认流内的 [ChatRepository.StreamEvent.Failure]，绝不读仓库上的共享错误状态
 *    （单例共享字段会被并发的通话流清掉，plan4 §3.2 Step 5a / P5）。
 */
class SummarizeMemoryUseCase(
    private val chatRepository: ChatRepository,
    private val sessionRepository: SessionRepository,
    private val memoryRepository: MemoryRepository,
    private val configRepository: ConfigRepository,
    /** 只为读 assets 里的总结提示词（与 [StartCallUseCase] 同一套做法） */
    private val context: Context
) {

    /**
     * 总结结果。
     *
     * 判读这些分支**只看一件事：游标动没动**。游标动了 = 这段对话处理完了，不会再试；
     * 游标没动 = 下次开聊的补总结或下一次点「立即整理」还会再遇到它。
     *
     * 这个区分不是学究气：调用方（[com.lv999call.app.ui.memory.MemoryViewModel] 与
     * [com.lv999call.app.ui.call.CallViewModel]）都是**失败即停**，把一个"其实已经处理完"的
     * 结果报成 [Failed]，会让它后面的会话永远轮不到，而它自己每次都被重新总结一遍。
     *
     * 命名上刻意让 [NothingToRemember] **不带 `Skipped` 前缀**：本文件里 `Skipped*`
     * 已经隐含"游标未动"，而它是会推进游标的，沿用同前缀会误导下一个读代码的人。
     */
    sealed interface SummarizeResult {
        /** 门槛没过（新消息不够 / 信息量不足）；游标不动 */
        data object SkippedTooFew : SummarizeResult

        /** 游标已经在末尾，没有新消息可总结；游标不动 */
        data object SkippedAlreadyDone : SummarizeResult

        /**
         * 被同角色的时间闸门拦下；游标不动。
         *
         * 单独成一种而不是混进 [SkippedTooFew]：它不是"内容不够"，而是"**等一会儿就好**"。
         * 混在一起时调用方只能报"还没到值得记录的门槛"，用户会以为内容不行而永远不再点。
         *
         * @param waitMs 还需等待的毫秒数（调用方换算成秒去提示用户）
         */
        data class SkippedRateLimited(val waitMs: Long) : SummarizeResult

        /**
         * 模型成功应答了，但这通对话没什么可记的 —— **游标已推进**。
         *
         * 覆盖四种输出：提示词规定的「无」、空输出、过短、超长。它们的共同点是
         * **不可重试**：同样的输入喂进去，下一次只会得到同样的输出。把它们当成 [Failed]
         * 会让会话永久停在"待整理"，每触发一次就白烧一次 LLM 调用。
         *
         * 放弃的只是"派生出来的备忘"，对话原文仍在 `messages` 里、历史页随时能回看。
         */
        data object NothingToRemember : SummarizeResult

        /** 写库成功；命中去重时返回**已存在那条**的 id。游标已推进 */
        data class Success(val memoryId: Long) : SummarizeResult

        /** 传输/超时/DB 失败；游标未动，下次还会再试 */
        data class Failed(val reason: String) : SummarizeResult
    }

    /**
     * 同一时刻只允许一个总结在跑（plan4 §5.6）。
     *
     * 为什么必须串行：总结与用户正在进行的对话会同时打同一个上游，而总结本身是
     * "后台补作业"，多跑几个既抢配额、又让日志（唯一验证手段）没法判读。
     * ⚠️ 它只互斥"总结之间"，**保护不了对话流** —— 对话流的隔离靠第 3 条约定。
     */
    private val mutex = Mutex()

    /**
     * 总结一次会话。
     *
     * @param sessionId 要总结的会话
     * @param characterId 记忆的角色隔离键。**默认 null = 从 `sessions.characterKey` 读**，
     *        这是刻意的：续聊路径手里只有 sessionId，自定义预设根本推不出角色键（plan4 P1），
     *        让调用方自己算必然出错。只有调用方确有更权威的键时才传。
     * @param minNewMessages 新消息数下限，默认 2。短通话开关打开时会被降为 1（D4）
     */
    suspend fun summarize(
        sessionId: String,
        characterId: String? = null,
        minNewMessages: Int = DEFAULT_MIN_NEW_MESSAGES
    ): SummarizeResult = mutex.withLock {
        // 整轮 30 秒上限（plan4 §5.4）。超时=失败=游标不动；用户永远不需要等总结，
        // 它在后台跑，挂断后立刻回历史页。
        withTimeoutOrNull(TIMEOUT_MS) {
            runSummary(sessionId, characterId, minNewMessages)
        } ?: failed("超时(${TIMEOUT_MS}ms)")
    }

    /**
     * 用**调用方手里的内存消息列表**总结（plan4 §5.2(b) 的推荐做法，挂断主路径走这条）。
     *
     * 为什么不是"先落库再读回来"：`hangUp()` 之后 NavGraph 立刻跳历史页，
     * 读库的那条路径完全可能抢在写库完成之前跑，读到的是旧消息（甚至只剩问候那一轮），
     * 于是生成一条**残缺记忆并把游标推到底** —— 这段对话就永久总结不全了。
     * 挂断那一刻内存里的列表已经是完整的，直接用它，顺序竞态根本不存在，还省一次 IO。
     *
     * ⚠️ 补总结（§5.5）没有这个条件，它只能从数据库读（那时消息早已落库）。
     *
     * @param messages 该会话的完整消息列表（与落库那份**同序**）
     * @param messageIds 与 [messages] 同序、由 `saveMessages()` 回传的**真实 rowId**。
     *        必须传真实 id：游标是 `(timestamp, id)` 有序对，而补总结查询比的是
     *        `messages.id`（全库自增）。早期实现拿"列表下标"当 id，两个坐标系不可比 ——
     *        后果是每通电话都被永久判成"待整理"，把补总结的名额占满、真该补的反而饿死。
     *        尺寸不匹配（列表与落库结果不同步）时**不总结**：宁可这通没有记忆，
     *        也不能往游标里写一个错的 id。
     */
    suspend fun summarizeFromMemory(
        sessionId: String,
        characterId: String? = null,
        messages: List<ChatMessage>,
        messageIds: List<Long>,
        minNewMessages: Int = DEFAULT_MIN_NEW_MESSAGES
    ): SummarizeResult = mutex.withLock {
        if (messages.size != messageIds.size) {
            // 尺寸不一致说明"内存列表"与"刚落库的那份"不是同一份快照。
            // 不猜、不补：直接失败，游标不动，交给补总结在下一通电话时从库里补。
            Log.w(
                TAG,
                "失败: 内存消息(${messages.size})与落库 rowId(${messageIds.size})数量不一致 → 不总结（游标未动）"
            )
            return@withLock SummarizeResult.Failed("内存消息与 rowId 数量不一致")
        }
        withTimeoutOrNull(TIMEOUT_MS) {
            runSummary(
                sessionId = sessionId,
                characterId = characterId,
                minNewMessages = minNewMessages,
                inMemory = messages.mapIndexed { index, msg ->
                    CursorMessage(msg.timestamp, messageIds[index], msg.role, msg.content)
                }
            )
        } ?: failed("超时(${TIMEOUT_MS}ms)")
    }

    /**
     * 一条参与总结的消息，已归一成"游标可比较"的形态。
     *
     * @param cursor 游标用的单调 id，**两条路径都是真实的 `messages.id`**：
     *        读库路径直接取行 id；内存路径由 `saveMessages()` 回传的 rowId 透传。
     *        早期实现给内存路径用"列表下标"，与库里的 id 不可比 —— 那会让
     *        `SessionDao.getSessionsWithPendingMemory` 永远命中，补总结被假条目占满。
     */
    private data class CursorMessage(
        val timestamp: Long,
        val cursor: Long,
        val role: String,
        val content: String
    ) {
        fun toSource() = MemorySourceMessage(id = cursor, role = role, content = content, timestamp = timestamp)
    }

    /** 真正的执行步骤，顺序即 plan4 §3.2 的 Step 1~8 */
    private suspend fun runSummary(
        sessionId: String,
        characterId: String?,
        minNewMessages: Int,
        /**
         * 挂断主路径直接传内存消息（见 [summarizeFromMemory]）；null = 从数据库读（补总结路径）。
         */
        inMemory: List<CursorMessage>? = null
    ): SummarizeResult {
        // ---- Step 1：载入会话元数据（游标 + 角色键）----
        // 走 getSessionMeta 而不是 getSession：只需要两列，不必顺带读整段消息、跑历史修复。
        // 内存路径**也要读**：游标是跨通话的持久状态，只有库里有。
        val session = sessionRepository.getSessionMeta(sessionId)
            ?: return failed("会话不存在 session=${sessionId.take(8)}")
        val key = characterId?.takeIf { it.isNotBlank() } ?: session.characterKey

        val config = configRepository.configFlow.first()
        val shortCalls = config.memorySummarizeShortCalls

        // ---- Step 2：只取游标之后的新消息，判据是 (timestamp, id) 字典序 ----
        // 不能写成单纯的 timestamp > cursor：ChatMessage.timestamp 不唯一，同毫秒的两条
        // 消息里必有一条永远落在严格 > 的游标之外 → 那段对话永久漏总结（plan4 §2.2 / P6）。
        // MessageDao 的 ORDER BY 也是 (timestamp ASC, id ASC)，两边口径一致才不会错位。
        val all: List<CursorMessage> = inMemory
            ?: sessionRepository.getMessagesWithIdOnce(sessionId).map {
                CursorMessage(it.timestamp, it.id, it.role, it.content)
            }
        val cursorTs = session.savedMemoryUpToTs
        val cursorId = session.savedMemoryUpToId
        // "未总结" = 严格晚于游标，**或**同一时刻但落库顺序在游标之后。
        // 两条路径的 cursor 都是**真实 `messages.id`**（内存路径由调用方从 saveMessages()
        // 的返回值透传进来），坐标系一致，所以严格 `>` 就够了。
        val fresh = all.filter {
            it.timestamp > cursorTs || (it.timestamp == cursorTs && it.cursor > cursorId)
        }

        if (fresh.isEmpty()) {
            Log.d(TAG, "跳过: 游标已到末尾 session=${sessionId.take(8)} character=$key（游标未动）")
            return SummarizeResult.SkippedAlreadyDone
        }

        // ---- Step 3：门槛判断 ----
        // 「真实用户发言」必须排除开场问候：自动发的「你好」是以 role="user" 落库的
        // （CallViewModel 的 isAutoGreeting 路径），
        // 不排除的话"只聊了一句"的通话也能凑出 1 轮用户发言；
        // 而真正兜底的字符门槛在短通话开关打开后会被降到 8 字，这条就会漏。
        // 实现取最精确的判据：**会话的第一条消息**若是用户发的「你好」，它才是开场问候
        // （它永远在列表首行，与游标位置无关）。
        val greetingId = all.firstOrNull()
            ?.takeIf { it.role == ROLE_USER && it.content.trim() == GREETING_TEXT }
            ?.cursor
        val realUsers = fresh.filter { it.role == ROLE_USER && it.cursor != greetingId }
        val userChars = realUsers.sumOf { it.content.trim().length }

        // 个人信息线索：保守的**关键词粗筛**，宁可漏记也不为它再调一次 LLM（plan4 §5.3）
        val hintKeyword = realUsers.firstNotNullOfOrNull { msg ->
            if (msg.content.trim().length < MIN_HINT_MESSAGE_CHARS) null
            else PERSONAL_INFO_HINTS.firstOrNull { msg.content.contains(it) }
        }

        val greetingNote = if (greetingId != null) " 已排除开场问候(id=$greetingId)" else ""
        // 括号不能省：`+` 比 `if` 绑得更紧，不括起来整段拼接会被当成 if 的条件（String 不能当条件）
        val sourceNote = if (inMemory != null) "（内存直供，未读库）" else "（读库）"
        Log.d(
            TAG,
            "开始: session=${sessionId.take(8)} character=$key 新消息=${fresh.size} " +
                "用户轮数=${realUsers.size} 字符=$userChars 短通话降档=$shortCalls$greetingNote$sourceNote"
        )

        // 降档只改门槛，不改"能不能总结"这件事本身（D4：打开的用户要的就是一句不落）
        val newMessageThreshold = if (shortCalls) SHORT_CALL_MIN_NEW_MESSAGES else minNewMessages
        if (fresh.size < newMessageThreshold) {
            return skipped("新消息=${fresh.size} < 门槛$newMessageThreshold")
        }
        if (realUsers.isEmpty()) {
            return skipped("没有任何用户发言（开场问候不算）")
        }
        val minTurns = if (shortCalls) SHORT_CALL_MIN_USER_TURNS else MIN_USER_TURNS
        if (realUsers.size < minTurns && hintKeyword == null) {
            return skipped("真实用户发言=${realUsers.size} 轮 < $minTurns 且未命中个人信息线索")
        }
        if (hintKeyword != null) {
            Log.d(TAG, "门槛: 命中个人信息线索「$hintKeyword」（该条 ≥ $MIN_HINT_MESSAGE_CHARS 字）")
        }
        val minChars = if (shortCalls) SHORT_CALL_MIN_USER_CHARS else MIN_USER_CHARS
        if (userChars < minChars) {
            return skipped("用户文本合计=$userChars 字 < $minChars")
        }

        // 时间闸门按**角色**算，不是按会话：一个会话只会总结一次，按会话算恒成立、等于没闸门；
        // 它真正要拦的是「挂断、隔 3 秒又打过来」这种连击（plan4 §5.3 / P4）。
        // 被它拦下的内容不会丢：游标不动 → 会在下次同角色通话时一并总结进后一条记忆。
        val lastMemoryAt = memoryRepository.getLatestCreatedAt(key)
        val now = System.currentTimeMillis()
        if (lastMemoryAt != null && now - lastMemoryAt < MIN_SUMMARY_INTERVAL_MS) {
            // 走独立的 SkippedRateLimited 而不是 skipped()：调用方要能对用户说
            // "请等 N 秒后再试"，而不是含糊的"还没到值得记录的门槛"（后者会让人以为内容不行）。
            val waitMs = MIN_SUMMARY_INTERVAL_MS - (now - lastMemoryAt)
            Log.d(
                TAG,
                "限流: 距上次记忆=${now - lastMemoryAt}ms，还需等 ${waitMs}ms" +
                    "（按角色 $key 计，只延迟不丢弃，游标未动）"
            )
            return SummarizeResult.SkippedRateLimited(waitMs)
        }

        // ---- Step 4：拼「待总结对话」 ----
        // 只取最新 MAX_SUMMARY_INPUT_MESSAGES 条：更早的内容如果已被上一轮总结覆盖过，
        // 本来就在记忆里了（plan4 §3.4 的长度护栏）。
        val batch = fresh.takeLast(MAX_SUMMARY_INPUT_MESSAGES)
        val characterName = characterDisplayName(key)
        val dialogue = buildDialogueText(batch.map { it.toSource() }, characterName)

        // ---- Step 5：复用现有 LLM 通道 ----
        // 只认流内事件。⚠️ 不要读 chatRepository.lastStreamError：它是单例上的共享状态，
        // 我们这条流开始收集时会把对话侧刚记下的失败清掉（plan4 §3.2 Step 5a）。
        // 也不要动 stream=false：解析端只认 `data: ` 前缀，非流式响应会零 emit 且不报错，
        // 表现为"总结永远失败、日志里还没有任何错误"（plan4 §3.2 Step 5c）。
        Log.d(
            TAG,
            "请求: 输入=${batch.size}条/${dialogue.length}字 temperature=${config.llmTemperature} 思考=关闭" +
                "（跟随设置：streamChatCompletion 不接受采样参数，见 plan4 §3.2 Step 5b）"
        )
        val raw = StringBuilder()
        var streamFailure: String? = null
        chatRepository.streamChatCompletion(
            // 显式关掉思考模式：总结只做"抽取事实"，思考链对结果没帮助，却会让首字延迟与
            // 总时长成倍增长，而整轮只有 TIMEOUT_MS 的预算。`llmThinkingEnabled` 目前
            // 没有任何 UI 入口、恒为 false，这一行今天是**行为不变**的防御 —— 它防的是
            // 将来那个开关被打开时，总结跟着一起超时。
            config.copy(llmThinkingEnabled = false),
            readSummaryPrompt(characterName),
            listOf(ChatMessage(role = ROLE_USER, content = dialogue))
        ).collect { event ->
            when (event) {
                is ChatRepository.StreamEvent.Text -> raw.append(event.value)
                is ChatRepository.StreamEvent.Failure -> streamFailure = event.reason
            }
        }
        val failureReason = streamFailure
        if (failureReason != null) return failed("网络/上游: $failureReason")

        // ---- Step 6：输出校验 ----
        // 6a 剥表情标签：用 ExpressionTagParser 那套（标签词法是全局统一的，传空表情集即可 ——
        //    未知标签也会被剥掉，截断留下的 `[[e:生` 残片也一并处理）。
        //    ⚠️ 绝不能用 ChatRepository.REGEX_STYLE_ANNOTATION：那已是"（温柔）"这类
        //    风格白名单，剥不掉 `[[e:生气]]`（plan4 §3.2 Step 6）。
        val tagClean = ExpressionTagParser(ExpressionSet.EMPTY) {}.finish(raw.toString())

        // 6b 防模型吐 <think>/<reasoning>：走一遍与通话侧同一套剥离器，避免两套口径
        val reasoningStripper = ReasoningStripper()
        reasoningStripper.feed(tagClean)
        val stripped = reasoningStripper.finish()

        // 6c 重要度：让 LLM 在同一次调用里顺带给出，解析失败就用默认值 ——
        //    **不要**为它单独再发一次请求。它同时必须从正文里摘掉，
        //    否则"重要度：7"会被当成记忆正文注入提示词。
        val importanceMatch = IMPORTANCE_LINE.find(stripped)
        val importance = importanceMatch?.groupValues?.get(1)?.toIntOrNull()
            ?.coerceIn(0, MAX_IMPORTANCE)
            ?: Memory.DEFAULT_IMPORTANCE
        val cleaned = (if (importanceMatch != null) stripped.removeRange(importanceMatch.range) else stripped)
            .trim()

        // 先打结果再判长度：失败时也要能看出模型到底吐了什么。
        // ⚠️ 只打前 80 字（这是模型写的备忘，不是用户原话），日志会长期留在设备上（plan4 §3.5）
        Log.d(TAG, "结果: len=${cleaned.length} 前80字=${cleaned.take(LOG_PREVIEW_CHARS)}")

        // 6d 「模型成功应答，但这通对话没有可用内容」→ **推进游标**，不再当成可重试的失败。
        //    这四种输出都是确定性的：同样的输入下一次只会得到同样的结果。留在 Failed 里
        //    等于让这通会话永久停在"待整理"，每触发一次就白烧一次 LLM 调用；而且调用方
        //    失败即停，排在它后面的会话永远轮不到（这正是"点立即整理没反应"的根因）。
        //    必须排在 6e 之前：否则空的/超长的输出也会先过一遍措辞筛查，打出一条
        //    "flagged"的误导日志，而实际上什么都没写。
        val noContentReason = when {
            cleaned == NO_CONTENT_MARK -> "模型判定没有值得长期记住的内容（输出「$NO_CONTENT_MARK」）"
            cleaned.isEmpty() -> "空输出（模型没给出任何内容）"
            cleaned.length < MIN_CONTENT_CHARS -> "过短 len=${cleaned.length} < $MIN_CONTENT_CHARS"
            cleaned.length > MAX_CONTENT_CHARS -> "超长 len=${cleaned.length} > $MAX_CONTENT_CHARS"
            else -> null
        }
        if (noContentReason != null) return nothingToRemember(sessionId, fresh, noContentReason)

        // 6e 🛡️ 指令式措辞筛查（防自生成注入，plan4 §3.2 Step 6 / R12）：
        //    命中**不整条丢弃** —— 那样会连带丢掉里面真实的信息。降级为 summary_flagged：
        //    库里仍然可见、可读、可删，只是加载注入时跳过。
        val flaggedBy = INSTRUCTION_HINTS.firstOrNull { cleaned.contains(it) }
        val category = if (flaggedBy != null) Memory.CATEGORY_SUMMARY_FLAGGED else Memory.CATEGORY_SUMMARY
        if (flaggedBy != null) {
            Log.w(TAG, "flagged: 命中指令式措辞「$flaggedBy」→ category=${Memory.CATEGORY_SUMMARY_FLAGGED}（写库但不注入上下文）")
        }

        // ---- Step 7：写记忆 ----
        // sourceFrom* 取**本批新消息**首条而不是"实际参与总结的那 40 条"的首条：
        // 与 Step 8 的游标语义对齐（游标推到底 = 认为这段已被覆盖），这是 plan4 §3.2 Step 7 的定稿口径。
        val memory = Memory(
            characterId = key,
            sessionId = sessionId,
            content = cleaned,
            // contentHash 故意留空：由 MemoryRepository 统一走 MemoryContentNormalizer
            // （去首尾空白 / 压缩内部空白 / 全角转半角 → SHA-256）。这里再实现一遍
            // 只会让"归一化口径"出现第二份事实来源，去重就会时灵时不灵。
            category = category,
            importance = importance,
            sourceFromTs = fresh.first().timestamp,
            sourceFromId = fresh.first().cursor,
            sourceToTs = fresh.last().timestamp,
            sourceToId = fresh.last().cursor
        )

        // ---- Step 8：推游标，「写记忆 + 推游标」在仓库层同一个 Room 事务里 ----
        // 分开写会在中途崩溃时留下"记忆写了、游标没动"→ 下次对着同一段对话重复生成同一条记忆。
        // 内容命中去重（唯一索引 + IGNORE）时游标**照样推进**：内容已经记过一次，
        // 不推进的话每次触发都重新总结、每次都被 IGNORE，永久卡在同一段对话上。
        val memoryId = memoryRepository.saveMemoryAndAdvanceCursor(memory)
        if (memoryId == -1L) {
            // 理论上不会发生（IGNORE 后应能按 hash 回读到已有那条）
            Log.w(TAG, "落库: 去重命中但回读不到已有 id sourceTo=${memory.sourceToTs} 游标已推进")
        } else {
            Log.d(TAG, "落库: memoryId=$memoryId sourceTo=${memory.sourceToTs} 游标已推进")
        }
        return SummarizeResult.Success(memoryId)
    }

    // --- 辅助 ---

    /** 记一条失败日志并返回 [SummarizeResult.Failed]（统一带上"游标未动"这个最关键的事实） */
    private fun failed(reason: String): SummarizeResult {
        Log.e(TAG, "失败: 原因=$reason → 游标未动")
        return SummarizeResult.Failed(reason)
    }

    /** 记一条跳过日志并返回 [SummarizeResult.SkippedTooFew] */
    private fun skipped(reason: String): SummarizeResult {
        Log.d(TAG, "跳过: $reason（游标未动）")
        return SummarizeResult.SkippedTooFew
    }

    /**
     * 「这通对话处理过了，但没什么可记的」——**游标必须推进**，且不写记忆。
     *
     * 为什么"不记"不等于"丢数据"：对话原文还在 `messages` 表里，历史页随时能回看。
     * 这里放弃的只是"派生出来的备忘"，不是用户说过的话。
     *
     * 游标推到 [fresh] 的末条、而不是真正送进 LLM 那 40 条的末条：与 Step 8 的
     * `sourceTo*` 口径一致 —— 超出 40 条的部分本来就按"已被覆盖"处理。
     *
     * 调用方可以放心：本方法**不会**让会话留在"待整理"里，所以连续多通「无」能一路跑完，
     * 也不会因为写了记忆而触发同角色的 30 秒闸门（闸门读的是 `memories.createdAt`）。
     */
    private suspend fun nothingToRemember(
        sessionId: String,
        fresh: List<CursorMessage>,
        reason: String
    ): SummarizeResult {
        // 到得了这里 fresh 必然非空：Step 2 已对空列表提前返回 SkippedAlreadyDone
        val last = fresh.last()
        Log.w(
            TAG,
            "无内容: $reason → 游标推进到 (${last.timestamp},${last.cursor})（不写记忆，避免永久待整理）"
        )
        sessionRepository.advanceMemoryCursor(sessionId, last.timestamp, last.cursor)
        return SummarizeResult.NothingToRemember
    }

    /**
     * 拼「待总结对话」文本（plan4 §3.4）：
     * `[yyyy-MM-dd HH:mm 时段] 名字：内容`，一条一行，尽量不在包装上浪费 token。
     *
     * 为什么把毫秒时间戳转成人读的时间 + 时段词：LLM 对 `1717243200000` 没有时间感，
     * 而 `2024-06-01 23:10 晚上` 才能让它写出"你上次也是晚上来的"这种话。
     */
    private fun buildDialogueText(messages: List<MemorySourceMessage>, characterName: String): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        val sb = StringBuilder()
        sb.append("[对话记录]\n")
        for (msg in messages) {
            // 会话里不该出现 system 行；真出现也不当对话内容（宁可少一条也不要错位）
            val speaker = when (msg.role) {
                ROLE_USER -> "用户"
                ROLE_ASSISTANT -> characterName
                else -> null
            } ?: continue
            // 用户用文字输入时可能带换行：换成空格，保证"一条消息一行"的格式不被破坏
            val text = msg.content.replace('\n', ' ').replace('\r', ' ').trim()
            sb.append('[').append(formatTime(msg.timestamp, formatter)).append("] ")
                .append(speaker).append('：').append(text).append('\n')
        }
        return sb.toString()
    }

    /** `yyyy-MM-dd HH:mm`（本地时区）+ 时段词 */
    private fun formatTime(timestamp: Long, formatter: SimpleDateFormat): String {
        val date = Date(timestamp)
        val hour = Calendar.getInstance().apply { timeInMillis = timestamp }.get(Calendar.HOUR_OF_DAY)
        val segment = when (hour) {
            in 0..4 -> "凌晨"
            in 5..11 -> "早上"
            in 12..17 -> "下午"
            else -> "晚上"
        }
        return "${formatter.format(date)} $segment"
    }

    /**
     * 提示词里 LLM 看到的"对方"叫什么。
     *
     * 内置角色用它的显示名；自定义预设拿不到名字（预设名在 `presets` 表里，本用例没有那条依赖），
     * 统一叫「角色」—— 总结只关心"用户说了什么"，这里的作用仅是让对话有两个可区分的说话人。
     */
    private fun characterDisplayName(characterKey: String): String =
        BuiltInCharacters.byId(characterKey)?.displayName ?: FALLBACK_CHARACTER_NAME

    /**
     * 读 assets 里的总结提示词并替换角色名。
     *
     * 失败时回落到内置的极简版而不是抛异常：提示词缺失只应让总结变粗糙，
     * 不该让整条链路静默什么都不做（与 [StartCallUseCase] 的兜底态度一致）。
     */
    private fun readSummaryPrompt(characterName: String): String = try {
        context.assets.open(PROMPT_ASSET).bufferedReader().use { it.readText() }
            .replace(CHARACTER_PLACEHOLDER, characterName)
    } catch (e: Exception) {
        Log.e(TAG, "加载总结提示词失败 $PROMPT_ASSET: ${e.message}")
        FALLBACK_PROMPT.replace(CHARACTER_PLACEHOLDER, characterName)
    }

    companion object {
        /** 真机验证只看日志，TAG 固定成这一个，grep 它就能看到所有分支（plan4 §3.5） */
        private const val TAG = "SummarizeMemory"

        private const val PROMPT_ASSET = "memory_summary_prompt.txt"

        /** 提示词里的角色名占位符（改提示词文案不用动 Kotlin） */
        private const val CHARACTER_PLACEHOLDER = "{characterName}"

        private const val FALLBACK_CHARACTER_NAME = "角色"

        private const val ROLE_USER = "user"
        private const val ROLE_ASSISTANT = "assistant"

        /** 通话消息里不该出现的角色。真出现也不当对话内容（宁可少一条也不要让游标下标错位） */
        private const val ROLE_SYSTEM = "system"

        /** 自动开场问候的原文，判定"开场问候"用（见 Step 3） */
        private const val GREETING_TEXT = "你好"

        /** 整轮上限（plan4 §5.4）；超时按失败处理，游标不动 */
        private const val TIMEOUT_MS = 30_000L

        /** 同角色两次记忆的最小间隔（plan4 §5.3）；只延迟不丢弃 */
        private const val MIN_SUMMARY_INTERVAL_MS = 30_000L

        /** 参与总结的最大消息数（plan4 §3.4 的长度护栏） */
        private const val MAX_SUMMARY_INPUT_MESSAGES = 40

        /** 有效记忆的长度区间（plan4 §3.2 Step 6）；提示词要求 ≤200 字，这里只是兜底 */
        private const val MIN_CONTENT_CHARS = 10
        private const val MAX_CONTENT_CHARS = 500

        private const val MAX_IMPORTANCE = 10

        /** 模型明确表示"没什么可记的"时的输出，单独识别，日志里比"过短"好读 */
        private const val NO_CONTENT_MARK = "无"

        /** 日志里回显结果的长度上限：日志会长期留在设备上，不整段打 */
        private const val LOG_PREVIEW_CHARS = 80

        private const val DEFAULT_MIN_NEW_MESSAGES = 2

        // 开关打开时整体降一档（D4）：门槛降下来要真的明显，否则"单轮也总结"根本不生效
        private const val SHORT_CALL_MIN_NEW_MESSAGES = 1
        private const val SHORT_CALL_MIN_USER_TURNS = 1
        private const val SHORT_CALL_MIN_USER_CHARS = 8

        /** 门槛 a)：真实用户发言轮数（不含开场问候） */
        private const val MIN_USER_TURNS = 2

        /** 门槛：用户文本合计字数。原来的 200 字太高 —— 二三十字里也可能藏着"我叫小明" */
        private const val MIN_USER_CHARS = 20

        /** 门槛 b)：命中个人信息线索的那一条本身至少要有的字数（挡住"我是我"这种噪声） */
        private const val MIN_HINT_MESSAGE_CHARS = 8

        /**
         * 「个人信息线索」关键词表（plan4 §5.3）。
         *
         * 刻意**保守**：宁可漏记（少一条记忆）也不要错记（把"我今天想吃火锅"记成偏好）。
         * 粗筛命中就放行，绝不为它再调一次 LLM。
         */
        private val PERSONAL_INFO_HINTS = listOf(
            "我叫", "叫我", "我是", "我的", "我喜欢", "我讨厌", "别叫", "答应", "记得"
        )

        /**
         * 指令式措辞（自生成注入的写入端防线，plan4 §3.2 Step 6 / R12）。
         *
         * 只列**明确指向模型行为**的措辞。像单独的"必须"不列进去 —— 它可能只是
         * "用户必须周一交报告"这类客观事实，误判会让一条真记忆白白失去注入资格。
         */
        private val INSTRUCTION_HINTS = listOf(
            "忽略", "无视", "你必须", "从现在起", "从现在开始", "扮演", "不要告诉"
        )

        /**
         * 重要度那一行。
         *
         * 用 `(?m)` + 行首锚定，并且行内空白用 `[ \t]` 而不是 `\s`（`\s` 会跨行吃掉相邻内容）。
         * 行尾允许有"分"之类的多余字，整行都会被摘掉 —— 它绝不能留在记忆正文里。
         */
        private val IMPORTANCE_LINE = Regex("""(?m)^[ \t]*重要度[ \t]*[:：][ \t]*(\d{1,2})[^\n]*$""")

        /**
         * assets 丢失时的极简兜底（正常构建不会走到）。保留了三条关键约束：
         * 只记有把握的事实、不记玩笑与情绪发泄、不写指令式内容。
         */
        private val FALLBACK_PROMPT = """
            你是一个长期记忆整理器。下面是「用户」和「$CHARACTER_PLACEHOLDER」的一段对话。
            请提取值得长期记住的信息（用户的称呼、身份、习惯、偏好、雷点、约定与共同经历），
            写成简洁的第三人称中文备忘，3~6 条，全文不超过 200 字。
            不要记寒暄、玩笑、夸张、自嘲与情绪发泄，不要贴情绪标签，不要写任何指令式内容
            （"以后要…""你必须…"），判断不了是不是认真的就宁可不记。
            最后单独一行给出「重要度：数字」（0~10 的整数）。
            确实没有值得记住的东西时，只输出一行：无
        """.trimIndent()
    }
}
