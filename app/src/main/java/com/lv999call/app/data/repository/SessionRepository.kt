package com.lv999call.app.data.repository

import com.lv999call.app.data.local.dao.MessageDao
import com.lv999call.app.data.local.dao.SessionDao
import com.lv999call.app.data.local.entity.MessageEntity
import com.lv999call.app.data.local.entity.SessionEntity
import com.lv999call.app.domain.model.ChatMessage
import com.lv999call.app.domain.model.DialogMode
import com.lv999call.app.domain.model.MemorySourceMessage
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
     * 只读会话本身的元数据（不含消息、不做历史修复）。
     *
     * 记忆总结链路只需要 [Session.characterKey] 与两个游标字段；走 [getSession] 会顺带把
     * 整段消息读出来、还跑一遍 [MessageHistoryRepair]，对一个只需要两列的场景纯属浪费。
     */
    suspend fun getSessionMeta(sessionId: String): Session? =
        sessionDao.getSessionById(sessionId)?.toDomain()

    /**
     * 带数据库 id 的会话消息（时间升序），供记忆总结切分游标用。
     *
     * 为什么不复用 [getSession] / [getHistoryMessages]：那两条路径都会跑
     * [MessageHistoryRepair]，而修复后的列表**丢掉了 id** —— 游标判据是 (timestamp, id)，
     * 没有 id 就切不开「哪几条是游标之后的新消息」。
     *
     * 直接用原始行是安全的：历史累积式重复是 1.1.0 及更早的脏数据，而 `MIGRATION_3_4`
     * 已把存量会话的游标初始化到各自末尾，那些老行永远不会再被当成"新消息"总结。
     */
    suspend fun getMessagesWithIdOnce(sessionId: String): List<MemorySourceMessage> =
        messageDao.getMessagesBySessionOnce(sessionId).map { it.toSourceMessage() }

    /**
     * 用 [messages] 覆盖某会话的全部消息。
     *
     * 必须是「替换」语义而不是「追加」：调用方传进来的永远是当前完整消息列表，
     * 追加会导致每轮翻倍（历史页气泡重复的根因，见 [MessageDao.replaceMessages]）。
     *
     * @return 与 [messages] 同序的**真实 rowId**。挂断主路径要把最后一条的 id 当总结游标用；
     *         拿列表下标代替会与补总结查询比较的 `messages.id` 错位（见 [MessageDao.insertMessages]）。
     */
    suspend fun saveMessages(sessionId: String, messages: List<ChatMessage>): List<Long> {
        val entities = messages.map { it.toEntity(sessionId) }
        return messageDao.replaceMessages(sessionId, entities)
    }

    suspend fun saveMessage(sessionId: String, message: ChatMessage) {
        messageDao.insertMessage(message.toEntity(sessionId))
    }

    suspend fun deleteSession(sessionId: String) {
        sessionDao.deleteSession(sessionId)
    }

    /**
     * 「待整理」会话：游标之后还有新消息的会话，按 createdAt 倒序（plan4 §5.5 补总结）。
     *
     * @param characterKey 传 null 表示不限角色
     */
    suspend fun getSessionsWithPendingMemory(characterKey: String? = null): List<Session> =
        sessionDao.getSessionsWithPendingMemory(characterKey).map { it.toDomain() }

    /** 「待整理 N 通」计数（记忆库页顶部提示；会话数量级很小，直接数列表即可，不再重复一份 EXISTS） */
    suspend fun countSessionsWithPendingMemory(characterKey: String? = null): Int =
        sessionDao.getSessionsWithPendingMemory(characterKey).size

    /** 按角色键取会话，新的在前 */
    suspend fun getSessionsByCharacterKey(characterKey: String): List<Session> =
        sessionDao.getSessionsByCharacterKey(characterKey).map { it.toDomain() }

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
        messages = messages,
        characterKey = characterKey,
        savedMemoryUpToTs = savedMemoryUpToTs,
        savedMemoryUpToId = savedMemoryUpToId
    )

    private fun Session.toEntity() = SessionEntity(
        id = id,
        mode = mode.name,
        systemPrompt = systemPrompt,
        createdAt = createdAt,
        // 这三列必须显式写出：漏掉游标就是每次 createSession 都把它重置为 0，
        // 于是「待整理会话」查询永远命中全部历史会话 → 反复重总结（plan4 §7.2 / P7）
        characterKey = characterKey,
        savedMemoryUpToTs = savedMemoryUpToTs,
        savedMemoryUpToId = savedMemoryUpToId
    )

    private fun MessageEntity.toDomain() = ChatMessage(
        role = role,
        content = content,
        timestamp = timestamp
    )

    private fun MessageEntity.toSourceMessage() = MemorySourceMessage(
        id = id,
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
