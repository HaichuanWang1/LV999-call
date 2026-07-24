package com.ultraflow.silverwolf.domain.usecase

import com.ultraflow.silverwolf.data.repository.SessionRepository
import com.ultraflow.silverwolf.domain.model.ChatMessage
import com.ultraflow.silverwolf.domain.model.Session
import kotlinx.coroutines.flow.Flow

/**
 * 管理会话用例
 * 处理会话的保存、加载、历史消息管理
 */
class ManageSessionUseCase(
    private val sessionRepository: SessionRepository
) {
    /**
     * 获取所有历史会话
     */
    fun getAllSessions(): Flow<List<Session>> {
        return sessionRepository.getAllSessions()
    }

    /**
     * 获取指定会话详情（含消息）
     */
    suspend fun getSession(sessionId: String): Session? {
        return sessionRepository.getSession(sessionId)
    }

    /**
     * 保存通话消息到数据库
     */
    suspend fun saveCallMessages(sessionId: String, messages: List<ChatMessage>) {
        sessionRepository.saveMessages(sessionId, messages)
    }

    /**
     * 加载历史消息作为上下文
     */
    suspend fun loadHistoryMessages(sessionId: String, maxMessages: Int = 50): List<ChatMessage> {
        return sessionRepository.getHistoryMessages(sessionId, maxMessages)
    }

    /**
     * 删除会话
     */
    suspend fun deleteSession(sessionId: String) {
        sessionRepository.deleteSession(sessionId)
    }
}
