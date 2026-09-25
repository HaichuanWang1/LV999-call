package com.lv999call.app.domain.usecase

import android.util.Log
import com.lv999call.app.audio.AudioPlayer
import com.lv999call.app.audio.AsrEngine
import com.lv999call.app.data.repository.ChatRepository
import com.lv999call.app.data.repository.ConfigRepository
import com.lv999call.app.domain.model.CallState
import com.lv999call.app.domain.model.ChatMessage
import com.lv999call.app.domain.model.DialogMode
import com.lv999call.app.domain.model.ExpressionSet
import com.lv999call.app.domain.model.Live2DExpression
import com.lv999call.app.domain.model.TtsPolicy
import kotlinx.coroutines.flow.first

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
        mode: DialogMode,
        isAutoGreeting: Boolean = false,
        autoGreetingText: String = "",
        overrideRefAudioBase64: String? = null,
        overrideRefAudioMime: String? = null,
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
                Log.w(TAG, "ASR识别结果为空")
                return Pair(ChatMessage(role = "user", content = "（语音识别失败）"), null)
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
        val effectivePrompt = systemPrompt?.let {
            if (config.live2dEnabled && !expressions.isEmpty) it + expressions.promptBlock() else it
        }
        // 边收边剥离表情标签：UI 显示与 TTS 用同一个干净文本，标签不会被念出来
        val tagParser = ExpressionTagParser(expressions, onExpression)
        // 边收边剥离推理块：思考内容既不显示也不朗读（详见 ReasoningStripper）
        val reasoningStripper = ReasoningStripper()
        /** 是否已经出现过可见正文 —— 它决定 UI 是「思考中转圈」还是「流式打字」 */
        var sawVisible = false

        try {
            chatRepository.streamChatCompletion(
                config, effectivePrompt, contextMessages + userMessage
            ).collect { chunk ->
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
        } catch (e: Exception) {
            Log.e(TAG, "LLM调用失败: ${e.message}")
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
        try {
            // 发声来源优先级：
            //   1. 角色锁定的发声策略（如 DeepSeek 酱强制 MiMo 预置少女音）——
            //      这一档会**无视设置里选的 TTS 模型**，是"强制锁定"的落点；
            //   2. 预设自带的参考音频（override，自定义预设用）；
            //   3. 全局配置里对应模式的参考音频。
            // 三者都为空时交给 ChatRepository 按策略决定（预置音色不需要参考音频）。
            val refAudio: String = overrideRefAudioBase64?.takeIf { it.isNotEmpty() }
                ?: config.getRefAudioForMode(mode).takeIf { it.isNotEmpty() }
                ?: ""
            val refMime: String = overrideRefAudioMime?.takeIf { overrideRefAudioBase64?.isNotEmpty() == true }
                ?: config.getRefAudioMimeForMode(mode).takeIf { config.getRefAudioForMode(mode).isNotEmpty() }
                ?: "audio/wav"
            Log.d(TAG, "TTS: textLen=${aiResponse.length}, refAudioLen=${refAudio.length}, refMime=$refMime, policy=${ttsPolicy ?: "inherit"}")

            val audioStream = chatRepository.synthesizeSpeech(
                config, aiResponse, refAudio, refMime, ttsPrompt, voiceOverride = ttsPolicy
            )
            if (audioStream != null) {
                val speakRequestedAt = android.os.SystemClock.uptimeMillis()
                audioPlayer.playStream(audioStream)
                // 等到本轮播放真正结束再返回。
                // 这里刻意不轮询 isPlaying：音频是边收边播的，首块到达时间不确定，
                // 轮询既会误判「没开声」，也会在服务端一块都没下发时白等一个超时。
                // 开声时机由 AudioPlayer 自己打日志（开始出声: 自playStream=Xms）。
                val finished = audioPlayer.awaitPlaybackEnd(120_000L)
                if (!finished) {
                    Log.w(
                        TAG,
                        "TTS 播放超时（已等 ${android.os.SystemClock.uptimeMillis() - speakRequestedAt}ms），强制停止"
                    )
                    audioPlayer.stopCurrentPlayback()
                }
                // TTS播放结束后短暂延迟，避免麦克风拾取尾音
                kotlinx.coroutines.delay(300)
            }
        } catch (e: Exception) {
            Log.e(TAG, "TTS播放失败: ${e.message}")
        }

        return Pair(userMessage, ChatMessage(role = "assistant", content = aiResponse))
    }

    fun stopTts() {
        audioPlayer.stopCurrentPlayback()
    }
}
