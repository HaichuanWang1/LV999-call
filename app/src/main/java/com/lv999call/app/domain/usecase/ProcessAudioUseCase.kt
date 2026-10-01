package com.lv999call.app.domain.usecase

import android.util.Log
import com.lv999call.app.audio.AudioPlayer
import com.lv999call.app.audio.AsrEngine
import com.lv999call.app.data.repository.ChatRepository
import com.lv999call.app.data.repository.ConfigRepository
import com.lv999call.app.domain.model.ApiConfig
import com.lv999call.app.domain.model.AsrEmptyException
import com.lv999call.app.domain.model.CallState
import com.lv999call.app.domain.model.ChatMessage
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
    private val audioPlayer: AudioPlayer,
    /**
     * 「刚被摸过头」标记（见 [com.lv999call.app.di.AppModule.headPatPending]）。
     *
     * 由触摸回调置位、由本用例在下一轮**读一次就清** —— 摸头是即兴轻互动，
     * 当场跑一整轮 LLM+TTS 会打断节奏、还会给历史塞进一轮莫名其妙的好话，
     * 所以只把它捎带进下一轮的提示词，让角色自然带一句。
     */
    private val headPatPending: java.util.concurrent.atomic.AtomicBoolean =
        java.util.concurrent.atomic.AtomicBoolean(false)
) {
    companion object {
        private const val TAG = "ProcessAudioUseCase"

        /**
         * 「刚被摸头」捎带进本轮提示词的一段系统说明。
         *
         * 刻意写成"可选的反应"，并明确要求不要解释机制、不要只回应这个 ——
         * 否则用户只是顺手摸一下，角色却会把正题丢在一边只回一句"你摸我干嘛"。
         *
         * 用字符串模板拼：`const val` 不允许调用 `trimEnd()` 之类的函数。
         */
        private val HEAD_PAT_NOTE = """
            |
            |# 临时状态（系统注入，不要向玩家解释这段机制）
            |玩家刚刚摸了一下你的头。
            |这不代表任何对话内容，只是一个亲昵的小动作。请在这一轮回复里自然地带上一点反应
            |（害羞、嘴硬、得意、抗议、或者干脆当作没看见地继续正题都可以，按你的性格来），
            |但**不要**因此跑题：玩家真正想说的那件事仍然是重点。
            |""".trimMargin()
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
        /** LLM 通过 [[e:标签]] 触发表情时回调（Live2D 关闭时不会被触发） */
        onExpression: (Live2DExpression) -> Unit = {}
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

        // 摸头：用户点了形象的头（由 CallViewModel.onHeadPat 打的标记）。
        //
        // 刻意**当场不发请求**：摸头是即兴轻互动，为它跑一整轮 LLM+TTS 会打断
        // 对话节奏、还会给聊天记录塞进一轮莫名其妙的对话。所以只把这件事
        // 捎带进下一轮的提示词，让她在正常回复里自然带一句。
        //
        // 读一次就清（getAndSet）：只影响紧随其后的这一轮，摸两下不会念两次。
        // 刻意在**这里**清而不是等回复成功：万一这一轮 LLM 失败，宁可丢掉
        // 这次摸头的口应，也不要让它挂到好几轮之后突然冒出来。
        if (headPatPending.getAndSet(false)) {
            effectivePrompt = (effectivePrompt ?: "") + HEAD_PAT_NOTE
            Log.d(TAG, "本轮捎带摸头提示")
        }
        // 边收边剥离表情标签：UI 显示与 TTS 用同一个干净文本，标签不会被念出来
        val tagParser = ExpressionTagParser(expressions, onExpression)
        // 边收边剥离推理块：思考内容既不显示也不朗读（详见 ReasoningStripper）
        val reasoningStripper = ReasoningStripper()
        /** 是否已经出现过可见正文 —— 它决定 UI 是「思考中转圈」还是「流式打字」 */
        var sawVisible = false
        /**
         * 本轮 LLM 的失败原因，**只认流内的 [ChatRepository.StreamEvent.Failure]**。
         *
         * 为什么不用仓库上那个共享字段（原实现在下方读 `chatRepository.lastStreamError`）：
         * ChatRepository 是单例，而 plan4 的记忆总结流**开始收集时会清空**那个共享字段，
         * 于是「对话侧刚失败、总结流紧接着启动」会把失败记录抹掉，这里就以为本轮没出错，
         * 继续拿一段残缺回复去 TTS 并落库。错误必须跟着"这一次调用"走，不能挂在单例上
         * （plan4 §3.2 Step 5a / §7.3 / P5）。
         */
        var streamFailure: String? = null

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
                        streamFailure = event.reason
                        Log.e(TAG, "LLM 流式失败: ${event.reason}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "LLM调用失败: ${e.message}")
        }

        // 本轮 LLM 出过错就到此为止：不合成、不朗读、不写历史。
        // 否则会把一段残缺回复（或干脆是空的）拿去 TTS，听起来像 AI 突然敷衍一句。
        val failureReason = streamFailure
        if (failureReason != null) {
            Log.w(TAG, "本轮 LLM 失败，跳过 TTS 与落库: $failureReason")
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
            val audioStream = chatRepository.synthesizeSpeech(
                config, aiResponse, refAudio, refMime, ttsPrompt, voiceOverride = ttsPolicy
            )
            if (audioStream != null) {
                audioPlayer.playStream(audioStream)
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
                // 没合成出音频（无参考音频 / 服务端报错），本轮没有播放要等
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
