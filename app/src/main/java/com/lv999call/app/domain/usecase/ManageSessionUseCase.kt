package com.lv999call.app.domain.usecase

import com.lv999call.app.data.repository.SessionRepository
import com.lv999call.app.domain.model.ChatMessage
import com.lv999call.app.domain.model.Session
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
     *
     * @return 与 [messages] 同序的真实 rowId：挂断主路径要拿最后一条的 id 当记忆总结游标
     *         （用列表下标代替会与补总结查询比较的 `messages.id` 错位）
     */
    suspend fun saveCallMessages(sessionId: String, messages: List<ChatMessage>): List<Long> {
        return sessionRepository.saveMessages(sessionId, messages)
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
