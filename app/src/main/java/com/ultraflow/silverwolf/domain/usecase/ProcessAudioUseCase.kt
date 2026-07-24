package com.ultraflow.silverwolf.domain.usecase

import android.util.Log
import com.ultraflow.silverwolf.audio.AudioPlayer
import com.ultraflow.silverwolf.audio.AsrEngine
import com.ultraflow.silverwolf.data.repository.ChatRepository
import com.ultraflow.silverwolf.data.repository.ConfigRepository
import com.ultraflow.silverwolf.domain.model.CallState
import com.ultraflow.silverwolf.domain.model.ChatMessage
import com.ultraflow.silverwolf.domain.model.DialogMode
import kotlinx.coroutines.flow.first

/**
 * 处理音频用例
 * 流程：ASR → LLM流式生成(文字实时显示) → 完整响应单次TTS → 播放
 */
class ProcessAudioUseCase(
    private val chatRepository: ChatRepository,
    private val configRepository: ConfigRepository,
    private val asrEngine: AsrEngine,
    private val audioPlayer: AudioPlayer
) {
    companion object {
        private const val TAG = "ProcessAudioUseCase"
        private const val RESPONSE_TOKEN_RESERVE = 2048
    }

    private fun estimateTokens(text: String): Int {
        val chineseChars = text.count { it.code > 0x4E00 }
        val otherChars = text.length - chineseChars
        return (chineseChars * 0.7 + otherChars * 0.25).toInt() + 4
    }

    private fun truncateHistory(history: List<ChatMessage>, maxTokens: Int): List<ChatMessage> {
        val budget = maxTokens - RESPONSE_TOKEN_RESERVE
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
        onStateChange: (CallState) -> Unit,
        onPartialResponse: (String) -> Unit
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

        // Step 2: LLM流式生成（文字实时更新UI）
        onStateChange(CallState.THINKING)
        val contextMessages = truncateHistory(history, config.maxContextTokens)
        val fullResponse = StringBuilder()

        try {
            chatRepository.streamChatCompletion(
                config, systemPrompt, contextMessages + userMessage
            ).collect { chunk ->
                fullResponse.append(chunk)
                onPartialResponse(fullResponse.toString())
            }
        } catch (e: Exception) {
            Log.e(TAG, "LLM调用失败: ${e.message}")
        }

        val aiResponse = fullResponse.toString()
        if (aiResponse.isBlank()) {
            Log.w(TAG, "LLM响应为空")
            return Pair(userMessage, null)
        }
        Log.d(TAG, "AI回复: $aiResponse")

        // Step 3: 单次TTS合成完整响应并播放
        onStateChange(CallState.SPEAKING)
        try {
            val refAudio = config.getRefAudioForMode(mode)
            val refMime = config.getRefAudioMimeForMode(mode)
            Log.d(TAG, "TTS: textLen=${aiResponse.length}, refAudioLen=${refAudio.length}, refMime=$refMime")

            val audioStream = chatRepository.synthesizeSpeech(config, aiResponse, refAudio, refMime)
            if (audioStream != null) {
                audioPlayer.playStream(audioStream)
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
