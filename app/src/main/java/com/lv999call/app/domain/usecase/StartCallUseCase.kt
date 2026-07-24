package com.lv999call.app.domain.usecase

import android.content.Context
import com.lv999call.app.data.repository.ConfigRepository
import com.lv999call.app.data.repository.SessionRepository
import com.lv999call.app.domain.model.*
import kotlinx.coroutines.flow.first
import java.util.UUID

class StartCallUseCase(
    private val sessionRepository: SessionRepository,
    private val configRepository: ConfigRepository,
    private val context: Context
) {
    private val quickPrompt = "你是一个名叫银狼的AI助手。你性格活泼、机智，喜欢用轻松幽默的方式与人交流。请用简短自然的口语化回复，就像朋友之间聊天一样。不要使用markdown格式。每次回复控制在2-3句话以内，除非用户明确需要详细解释。"

    // 从assets加载长提示词（避免Kotlin三引号字符串的编码问题）
    private val longPrompt: String by lazy {
        try {
            context.assets.open("silverwolf_prompt.txt").bufferedReader().readText()
        } catch (e: Exception) {
            quickPrompt // fallback
        }
    }

    suspend fun getSystemPrompt(mode: DialogMode): String {
        return when (mode) {
            DialogMode.QUICK -> quickPrompt
            DialogMode.LONG -> longPrompt
            DialogMode.CUSTOM -> {
                val config = configRepository.configFlow.first()
                config.customPrompt.ifEmpty { "" }
            }
        }
    }

    suspend fun createSession(mode: DialogMode): Session {
        val systemPrompt = getSystemPrompt(mode)
        val session = Session(
            id = UUID.randomUUID().toString(),
            mode = mode,
            systemPrompt = systemPrompt,
            createdAt = System.currentTimeMillis()
        )
        sessionRepository.createSession(session)
        return session
    }
}
