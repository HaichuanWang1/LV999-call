package com.lv999call.app.data.repository

import com.lv999call.app.data.local.dao.MessageDao
import com.lv999call.app.data.local.dao.SessionDao
import com.lv999call.app.data.local.entity.MessageEntity
import com.lv999call.app.data.local.entity.SessionEntity
import com.lv999call.app.domain.model.ChatMessage
import com.lv999call.app.domain.model.DialogMode
import com.lv999call.app.domain.model.Session
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** 会话仓库 - 管理会话和消息的持久化 */
class SessionRepository(
    private val sessionDao: SessionDao,
    private val messageDao: MessageDao
) {

    fun getAllSessions(): Flow<List<Session>> {
        return sessionDao.getAllSessions().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    suspend fun getSession(sessionId: String): Session? {
        val sessionEntity = sessionDao.getSessionById(sessionId) ?: return null
        val messages = messageDao.getMessagesBySessionOnce(sessionId)
        // 读取期修复老版本的累积式重复（详见 MessageHistoryRepair）
        return sessionEntity.toDomain(MessageHistoryRepair.repair(messages.map { it.toDomain() }))
    }

    suspend fun createSession(session: Session) {
        sessionDao.insertSession(session.toEntity())
    }

    /**
     * 用 [messages] 覆盖某会话的全部消息。
     *
     * 必须是「替换」语义而不是「追加」：调用方传进来的永远是当前完整消息列表，
     * 追加会导致每轮翻倍（历史页气泡重复的根因，见 [MessageDao.replaceMessages]）。
     */
    suspend fun saveMessages(sessionId: String, messages: List<ChatMessage>) {
        val entities = messages.map { it.toEntity(sessionId) }
        messageDao.replaceMessages(sessionId, entities)
    }

    suspend fun saveMessage(sessionId: String, message: ChatMessage) {
        messageDao.insertMessage(message.toEntity(sessionId))
    }

    suspend fun deleteSession(sessionId: String) {
        sessionDao.deleteSession(sessionId)
    }

    suspend fun getHistoryMessages(sessionId: String, maxMessages: Int = 50): List<ChatMessage> {
        val messages = messageDao.getMessagesBySessionOnce(sessionId)
        return MessageHistoryRepair.repair(messages.map { it.toDomain() }).takeLast(maxMessages)
    }

    // --- 映射函数 ---

    private fun SessionEntity.toDomain(messages: List<ChatMessage> = emptyList()) = Session(
        id = id,
        mode = DialogMode.valueOf(mode),
        systemPrompt = systemPrompt,
        createdAt = createdAt,
        messages = messages
    )

    private fun Session.toEntity() = SessionEntity(
        id = id,
        mode = mode.name,
        systemPrompt = systemPrompt,
        createdAt = createdAt
    )

    private fun MessageEntity.toDomain() = ChatMessage(
        role = role,
        content = content,
        timestamp = timestamp
    )

    private fun ChatMessage.toEntity(sessionId: String) = MessageEntity(
        sessionId = sessionId,
        role = role,
        content = content,
        timestamp = timestamp
    )
}
