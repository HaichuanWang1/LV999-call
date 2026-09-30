package com.lv999call.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.lv999call.app.data.local.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    /**
     * 批量插入，**返回每条消息落库后的真实 rowId**（与入参同序）。
     *
     * 为什么要回传 id：长期记忆的总结游标是 `(timestamp, id)` 有序对，而挂断主路径
     * 手里只有内存里的 `ChatMessage`（没有 id）。如果拿"列表下标"当 id 写进游标，
     * 就与补总结查询比较的 `messages.id`（全库自增，必然远大于下标）成了两个坐标系 ——
     * 后果是每通电话都被永久判成"待整理"，把补总结的名额全部占满。
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessages(messages: List<MessageEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity): Long

    /**
     * 用 [messages] **整体替换** 某会话的消息（先清空再插入，同一事务内完成）。
     *
     * 为什么必须清空：消息表主键是自增 id，`OnConflictStrategy.REPLACE` 永远不可能
     * 命中冲突 —— 于是早期「保存整个消息列表」的调用每轮都会把已有消息**再追加一遍**，
     * 通话历史页里就出现了成对重复的气泡（第 2 轮库里变成 u1 a1 u1 a1 u2 a2）。
     *
     * @return 与 [messages] 同序的真实 rowId；传空列表时返回空表
     */
    @Transaction
    suspend fun replaceMessages(sessionId: String, messages: List<MessageEntity>): List<Long> {
        deleteMessagesBySession(sessionId)
        return if (messages.isNotEmpty()) insertMessages(messages) else emptyList()
    }

    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY timestamp ASC, id ASC")
    fun getMessagesBySession(sessionId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY timestamp ASC, id ASC")
    suspend fun getMessagesBySessionOnce(sessionId: String): List<MessageEntity>

    @Query("DELETE FROM messages WHERE sessionId = :sessionId")
    suspend fun deleteMessagesBySession(sessionId: String)
}
