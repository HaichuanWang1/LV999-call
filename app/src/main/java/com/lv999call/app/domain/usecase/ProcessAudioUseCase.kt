package com.lv999call.app.domain.usecase

import android.util.Log
import com.lv999call.app.audio.AudioPlayer
import com.lv999call.app.audio.AsrEngine
import com.lv999call.app.data.repository.ChatRepository
import com.lv999call.app.data.repository.ConfigRepository
import com.lv999call.app.domain.model.ApiConfig
import com.lv999call.app.domain.model.ApiFailure
import com.lv999call.app.domain.model.AsrEmptyException
import com.lv999call.app.domain.model.CallState
import com.lv999call.app.domain.model.ChatMessage
import com.lv999call.app.domain.model.EmotionVoiceStyles
import com.lv999call.app.domain.model.ExpressionSet
import com.lv999call.app.domain.model.Live2DExpression
import com.lv999call.app.domain.model.TtsPolicy
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * 处理音频用例
 * 流程：ASR → LLM流式生成(文字实时显示) → 完整响应单次TTS → 播放
 *
 * 角色无关：系统提示词、表情集、发声策略全部由调用方按当前角色传入
 * （见 [com.lv999call.app.preset.BuiltInCharacters]），本类不认识任何具体角色。
 */
class ProcessAudioUseCase(
    private val chatRepository: ChatRepository,
    private val configRepository: ConfigRepository,
    private val asrEngine: AsrEngine,
    private val audioPlayer: AudioPlayer
) {
    companion object {
        private const val TAG = "ProcessAudioUseCase"
    }

    private fun estimateTokens(text: String): Int {
        val chineseChars = text.count { it.code > 0x4E00 }
        val otherChars = text.length - chineseChars
        return (chineseChars * 0.7 + otherChars * 0.25).toInt() + 4
    }

    /**
     * 按 token 预算裁剪历史。
     *
     * [reserveForResponse] 必须取用户配置的**最大输出长度**而不是写死值：
     * 两者不一致时，用户把回复上限调大就会把上下文窗口挤爆（请求被服务端拒），
     * 调小又白白浪费上下文。见 [ApiConfig.llmMaxOutputTokens]。
     */
    private fun truncateHistory(
        history: List<ChatMessage>,
        maxTokens: Int,
        reserveForResponse: Int
    ): List<ChatMessage> {
        val budget = (maxTokens - reserveForResponse).coerceAtLeast(0)
        var usedTokens = 0
        val result = mutableListOf<ChatMessage>()
        for (msg in history.reversed()) {
            val msgTokens = estimateTokens(msg.content)
            if (usedTokens + msgTokens > budget) break
            result.add(msg)
            usedTokens += msgTokens
        }
        return result.reversed()
    }

    suspend fun processAudio(
        pcmData: ByteArray,
        systemPrompt: String?,
        history: List<ChatMessage>,
        isAutoGreeting: Boolean = false,
        autoGreetingText: String = "",
        overrideRefAudioBase64: String? = null,
        overrideRefAudioMime: String? = null,
        /**
         * 角色自带参考音频，作为**最后**兜底（优先级见下方 Step 3）。
         *
         * 与 [overrideRefAudioBase64] 的区别：预设的 override 优先级最高，
         * 而角色自带音色要让位给用户在准备页/设置里主动选的音频 ——
         * 否则用户换了音色却发现没生效。
         */
        fallbackRefAudioBase64: String? = null,
        fallbackRefAudioMime: String? = null,
        ttsPrompt: String = "",
        /**
         * 该角色的表情集。为空集（或 Live2D 关闭）时不向 LLM 注入标签协议。
         *
         * 与 [ttsPolicy] 一样属于"按角色传入"的字段 —— 早期这里是全局单例枚举，
         * 加入并列角色后无法共存，故上移为参数。
         */
        expressions: ExpressionSet = ExpressionSet.EMPTY,
        /**
         * 该角色的发声策略（null / [TtsPolicy.Inherit] 表示跟随全局设置）。
         *
         * 内置角色可以借此锁定自己的模型与音色，例如 DeepSeek 酱强制使用
         * MiMo 预置少女音，无视设置里选的 TTS 模型。
         */
        ttsPolicy: TtsPolicy? = null,
        onStateChange: (CallState) -> Unit,
        /**
         * 用户这句话一确定就回调（ASR 出结果 / 文字输入）。
         *
         * 让「用户气泡」在**这一刻**就上屏，而不是等整轮（LLM+TTS）跑完才和
         * 助手回复一起出现 —— 后者会先看到对方的回答、再看到自己说了什么，
         * 顺序颠倒，观感很怪。
         */
        onUserMessage: (ChatMessage) -> Unit = {},
        onPartialResponse: (String) -> Unit,
        /**
         * LLM / TTS 失败回调（含"没填 key""额度耗尽"这类需要用户去补配置的失败）。
         *
         * 为什么必须回调出去，而不是像改造前那样只写日志：失败在 UI 上的表现是
         * 「角色突然不说话」，用户完全无从判断是没网、key 没填、还是额度用完了。
         * 失败详情由 [com.lv999call.app.data.remote.ApiErrorParser] 从服务端响应体里
         * 解析出来，这里只负责往上送，**不在这里决定弹什么**（那是 UI 的事）。
         */
        onApiFailure: (ApiFailure) -> Unit = {},
        /**
         * LLM 通过 [[e:标签]] 触发表情时回调（Live2D 关闭时不会被触发）。
         *
         * **返回值 = 宿主有没有真的用上这个表情**：首轮开场问候 CallViewModel 会故意
         * 忽略标签（强制普通脸）并返回 false，本用例据此决定要不要让**语气**也跟着走。
         * 详见 [ExpressionTagParser] 的构造参数说明。
         */
        onExpression: (Live2DExpression) -> Boolean = { true }
    ): Pair<ChatMessage, ChatMessage?> {
        val config = configRepository.configFlow.first()

        // Step 1: 获取用户文本
        val userText = if (isAutoGreeting) {
            autoGreetingText
        } else {
            onStateChange(CallState.THINKING)
            val text = asrEngine.transcribe(config, pcmData)
            if (text.isBlank()) {
                // 抛异常而不是伪造一条「（语音识别失败）」的用户消息：
                // 后者会被写进消息列表并持久化到历史，识别失败一次就永久多一条假发言，
                // 还会被当成真实用户输入送进 LLM 上下文。交给上层提示重说。
                Log.w(TAG, "ASR识别结果为空（音频 ${pcmData.size} 字节）")
                throw AsrEmptyException()
            }
            text
        }

        val userMessage = ChatMessage(role = "user", content = userText)
        Log.d(TAG, "用户说: $userText")
        // 用户气泡立刻上屏（AI 侧此时还挂着「思考中」占位）
        onUserMessage(userMessage)

        // Step 2: LLM流式生成（文字实时更新UI）
        onStateChange(CallState.THINKING)
        val contextMessages = truncateHistory(
            history = history,
            maxTokens = config.maxContextTokens,
            reserveForResponse = config.llmMaxOutputTokens
        )
        val fullResponse = StringBuilder()

        // Live2D 开着、且该角色确实有表情表时，才把标签协议拼进 system prompt：
        // 关掉形象（或角色没有可用表情）时 LLM 根本不知道这套机制，
        // 既省 token 也不会跑偏。
        var effectivePrompt = systemPrompt?.let {
            if (config.live2dEnabled && !expressions.isEmpty) it + expressions.promptBlock() else it
        }

        // 摸头**不进提示词**（plan5 §3.4）：它是一次纯视觉的即兴互动，
        // 反应全部由 bridge.js 当场演出（部件命中盒 + 连点档位），不产生语音、
        // 不进历史、也不影响这一轮的对话。旧实现往这里注入过一段"玩家刚刚摸了
        // 你的头"，那是全局单例标记：挂断不清、跨角色串味，而且"刚刚"可能
        // 已经是几分钟前 —— 已整条删除。
        //
        // 边收边剥离表情标签：UI 显示与 TTS 用同一个干净文本，标签不会被念出来。
        //
        // 顺手记下本轮的表情 key：表情标签此前**只驱动 Live2D**，声音一个字都不变。
        // 这里把它留给 Step 3 —— TTS 是整段文本收完之后才发起的，所以开嗓之前一定
        // 已经拿到了这一轮的情绪，可以据此改这一句的语气（见 [EmotionVoiceStyles]）。
        // 标签协议规定每轮最多 1 个，所以直接覆盖即可，不需要队列。
        var turnEmotionKey: String? = null
        val tagParser = ExpressionTagParser(expressions) { expression ->
            val applied = onExpression(expression)
            // 只有宿主**真的用上了**这个表情，语气才跟着走。首轮开场问候会被
            // CallViewModel 故意忽略（强制普通脸），那时若仍按标签改语气，就会出现
            // "脸是普通的、声音却冷淡敷衍"的错位 —— 真机上正是这么暴露的。
            if (applied) turnEmotionKey = expression.key
            applied
        }
        // 边收边剥离推理块：思考内容既不显示也不朗读（详见 ReasoningStripper）
        val reasoningStripper = ReasoningStripper()
        /** 是否已经出现过可见正文 —— 它决定 UI 是「思考中转圈」还是「流式打字」 */
        var sawVisible = false
        /**
         * 本轮 LLM 的失败详情，**只认流内的 [ChatRepository.StreamEvent.Failure]**。
         *
         * 为什么不用仓库上那个共享字段（原实现在下方读 `chatRepository.lastStreamError`）：
         * ChatRepository 是单例，而 plan4 的记忆总结流**开始收集时会清空**那个共享字段，
         * 于是「对话侧刚失败、总结流紧接着启动」会把失败记录抹掉，这里就以为本轮没出错，
         * 继续拿一段残缺回复去 TTS 并落库。错误必须跟着"这一次调用"走，不能挂在单例上
         * （plan4 §3.2 Step 5a / §7.3 / P5）。
         */
        var streamFailure: ApiFailure? = null

        try {
            chatRepository.streamChatCompletion(
                config, effectivePrompt, contextMessages + userMessage
            ).collect { event ->
                when (event) {
                    is ChatRepository.StreamEvent.Text -> {
                        val chunk = event.value
                        fullResponse.append(chunk)
                        val visible = tagParser.consume(reasoningStripper.feed(chunk)).trim()
                        if (visible.isNotEmpty()) {
                            sawVisible = true
                            onPartialResponse(visible)
                        } else if (sawVisible) {
                            // 极罕见：标签/推理块让已显示文本回退为空。转发空串让 UI 清空气泡，
                            // 而不是把上一块旧文本留在屏幕上。
                            onPartialResponse("")
                        }
                        // 尚未见到正文时不回调：UI 保持「思考中」占位动画。
                        // 这段等待正是 plan1 要求的「语音识别结束 → 正式回应出现」之间的转圈动画。
                    }

                    is ChatRepository.StreamEvent.Failure -> {
                        streamFailure = event.error
                        Log.e(TAG, "LLM 流式失败: kind=${event.error.kind} detail=${event.error.detail}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "LLM调用失败: ${e.message}")
        }

        // 本轮 LLM 出过错就到此为止：不合成、不朗读、不写历史。
        // 否则会把一段残缺回复（或干脆是空的）拿去 TTS，听起来像 AI 突然敷衍一句。
        val failure = streamFailure
        if (failure != null) {
            Log.w(TAG, "本轮 LLM 失败，跳过 TTS 与落库: kind=${failure.kind} detail=${failure.detail}")
            // 把服务端原话交给 UI 弹窗（key 没填 / 额度耗尽 / 其他报错）。
            // 改造前这里只有一行日志，用户那边就是"她一句话都不说"。
            onApiFailure(failure)
            return Pair(userMessage, null)
        }

        // 定稿：推理块的未闭合残渣会被丢弃，表情标签剥掉
        val strippedResponse = reasoningStripper.finish()
        val aiResponse = tagParser.finish(strippedResponse).trim()
        if (aiResponse.isBlank()) {
            // 两种情况必须区分开：模型真的什么都没说，
            // 还是它只吐了个表情标签 / 整段回答都在 <think> 里被剥掉了。
            Log.w(
                TAG,
                "LLM响应为空 rawLen=${fullResponse.length} strippedLen=${strippedResponse.length} " +
                    "raw=${fullResponse.toString().take(300)}"
            )
            return Pair(userMessage, null)
        }
        Log.d(TAG, "AI回复: $aiResponse")

        // Step 3: 单次TTS合成完整响应并播放
        onStateChange(CallState.SPEAKING)

        /**
         * 本轮朗读的总预算（设置页「朗读超时」，默认 180s）。
         *
         * 计时**从发起 TTS 请求那一刻**开始，而不是从开始播放算起：
         * 「请求发出去了、服务端一个字都不下发」正是最需要兜底的卡死形态，
         * 只在播放阶段计时会让这种卡死白等满一个 OkHttp readTimeout（120s）才收场。
         */
        val playbackBudgetMs = config.ttsPlaybackTimeoutSec * 1000L
        val speakRequestedAt = android.os.SystemClock.uptimeMillis()

        // 本轮播放是否已正常收尾（含尾音延迟）。
        //
        // 为什么需要这个标志：finally 里要兜底停播（取消/超时路径），但**不能**在
        // 正常路径上停 —— awaitPlaybackEnd 只等解码任务结束，此刻 AudioTrack 里
        // 还排着最后一段音频，无条件 stop 会把每句话的尾音硬切掉。
        var playbackSettled = false
        try {
            // 发声来源优先级：
            //   1. 角色锁定的发声策略（如 DeepSeek 酱强制 MiMo 预置少女音）——
            //      这一档会**无视设置里选的 TTS 模型**，是"强制锁定"的落点；
            //   2. 预设自带的参考音频（override，自定义预设用）；
            //   3. 用户在设置/准备页里主动选的参考音频；
            //   4. 角色自带的参考音频（如银狼内置音色）作为兜底 ——
            //      放在最后是为了让用户的主动选择始终生效。
            //
            // ⚠️ 第 3 档曾经按对话模式分成"自定义模式专用音频 / 全局音频"两份
            // （`getRefAudioForMode`）。那条模式入口已不存在、专用那份也没有 UI 能写，
            // 于是分支只剩"读全局"，徒增一个可能读错配置的口子，已收敛成一份。
            val refAudio: String = overrideRefAudioBase64?.takeIf { it.isNotEmpty() }
                ?: config.ttsReferenceAudioBase64.takeIf { it.isNotEmpty() }
                ?: fallbackRefAudioBase64?.takeIf { it.isNotEmpty() }
                ?: ""
            val refMime: String = overrideRefAudioMime?.takeIf { overrideRefAudioBase64?.isNotEmpty() == true }
                ?: config.ttsReferenceAudioMime.takeIf { config.ttsReferenceAudioBase64.isNotEmpty() }
                ?: fallbackRefAudioMime?.takeIf { fallbackRefAudioBase64?.isNotEmpty() == true }
                ?: "audio/wav"
            Log.d(TAG, "TTS: textLen=${aiResponse.length}, refAudioLen=${refAudio.length}, refMime=$refMime, policy=${ttsPolicy ?: "inherit"}")

            // 刻意**不**给合成套 withTimeoutOrNull：超时正好落在「管道已建好、值还没交回
            // 调用方」那一瞬的话，这个流就再也没人 close，解码协程会永远堵在写管道上
            // （socket 与协程一起泄漏）。请求阶段本身有 OkHttp 的 connect/read 超时兜底，
            // 这里只要把**剩余**预算交给播放阶段即可：预算被请求吃光时 remaining=0，
            // 播放会立刻被判超时，等价于"本轮不朗读"。

            // 语气跟着本轮情绪走：把角色的基础风格与本轮表情对应的语气拼成这一次的提示词。
            //
            // 没有表情标签时（提示词里要求"情绪不明显就宁可少加"）[EmotionVoiceStyles.styleFor]
            // 返回 null，拼出来就是原样的 ttsPrompt —— 行为与加这个功能之前完全一致，
            // 所以这条路径对"她本来就不怎么用标签"的角色零影响。
            //
            // 开关关掉时同样拼不出情绪段，但**表情照常变** —— 那是 Live2D 那条路径的事，
            // 与本开关无关。用户要的正是"脸可以变，别连声音一起改"。
            val voiceStyle = if (config.emotionVoiceEnabled) {
                EmotionVoiceStyles.styleFor(turnEmotionKey)
            } else {
                null
            }
            if (!config.emotionVoiceEnabled) {
                // 只在"本来会有语气"时才吭声，否则每轮都刷一条无意义的日志
                if (turnEmotionKey != null) {
                    Log.d(TAG, "语气: 表情=$turnEmotionKey → 开关已关，声音不跟着情绪走")
                }
            } else if (turnEmotionKey != null && voiceStyle == null) {
                // 表里漏了这个键 → 静默退回角色原本的语气。这一条日志是唯一的发现手段
                // （键名写错不会报错，只会"怎么没效果"）。
                Log.w(
                    TAG,
                    "本轮表情「$turnEmotionKey」在 EmotionVoiceStyles 里没有配语气，" +
                        "沿用角色基础风格（表里现有 ${EmotionVoiceStyles.knownKeys().size} 个键）"
                )
            }
            val turnTtsPrompt = EmotionVoiceStyles.composePrompt(ttsPrompt, voiceStyle)
            if (voiceStyle != null) {
                Log.d(TAG, "语气: 表情=$turnEmotionKey → $voiceStyle")
            }

            val speechResult = chatRepository.synthesizeSpeech(
                config, aiResponse, refAudio, refMime, turnTtsPrompt, voiceOverride = ttsPolicy
            )
            if (speechResult is ChatRepository.SpeechResult.Audio) {
                audioPlayer.playStream(speechResult.stream)
                // 等到本轮播放真正结束再返回。
                // 这里刻意不轮询 isPlaying：音频是边收边播的，首块到达时间不确定，
                // 轮询既会误判「没开声」，也会在服务端一块都没下发时白等一个超时。
                // 开声时机由 AudioPlayer 自己打日志（开始出声: 自playStream=Xms）。
                //
                // 只把**剩余**预算给播放阶段：请求阶段已经花掉的那部分不能重复计时，
                // 否则一次朗读的真实上限会变成"超时 × 2"。
                val remainingMs =
                    (playbackBudgetMs - (android.os.SystemClock.uptimeMillis() - speakRequestedAt))
                        .coerceAtLeast(0L)
                val finished = audioPlayer.awaitPlaybackEnd(remainingMs)
                if (!finished) {
                    Log.w(
                        TAG,
                        "TTS 朗读超时（上限 ${config.ttsPlaybackTimeoutSec}s，" +
                            "已等 ${android.os.SystemClock.uptimeMillis() - speakRequestedAt}ms），强制停止"
                    )
                    audioPlayer.stopCurrentPlayback()
                }
                // TTS播放结束后短暂延迟，避免麦克风拾取尾音
                kotlinx.coroutines.delay(300)
                playbackSettled = true
            } else {
                if (android.os.SystemClock.uptimeMillis() - speakRequestedAt >= playbackBudgetMs) {
                    Log.w(
                        TAG,
                        "TTS 朗读超时（合成阶段就等满 ${config.ttsPlaybackTimeoutSec}s），本轮不朗读"
                    )
                }
                // 没合成出音频（无参考音频 / key 没填 / 服务端报错），本轮没有播放要等。
                // 失败要**弹窗说明**：否则现象就是"她忽然不出声了"，用户只能瞎猜。
                // Skipped 不算失败（洗完之后没内容可念），静默收场。
                if (speechResult is ChatRepository.SpeechResult.Failed) {
                    Log.w(
                        TAG,
                        "TTS 失败: kind=${speechResult.error.kind} detail=${speechResult.error.detail}"
                    )
                    onApiFailure(speechResult.error)
                }
                playbackSettled = true
            }
        } catch (e: Exception) {
            Log.e(TAG, "TTS播放失败: ${e.message}")
        } finally {
            // 兜底停播：只在**没正常收尾**时做（超时、协程被取消、中途抛异常）。
            //
            // 为什么必须兜：播放跑在 AudioPlayer 自己的 scope 里，本协程被取消
            // 不会让它停下。而调用方在 processAudio 返回后会立刻开麦录音
            // （AudioRecorder 刻意关掉了 AEC），于是 AI 会把自己还在放的声音
            // 当成用户输入 —— 也就是「AI 自听自说」。
            if (!playbackSettled) {
                withContext(NonCancellable) { runCatching { audioPlayer.stopCurrentPlayback() } }
            }
        }

        return Pair(userMessage, ChatMessage(role = "assistant", content = aiResponse))
    }

    fun stopTts() {
        audioPlayer.stopCurrentPlayback()
    }
}
