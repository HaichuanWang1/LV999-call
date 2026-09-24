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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessages(messages: List<MessageEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity)

    /**
     * 用 [messages] **整体替换** 某会话的消息（先清空再插入，同一事务内完成）。
     *
     * 为什么必须清空：消息表主键是自增 id，`OnConflictStrategy.REPLACE` 永远不可能
     * 命中冲突 —— 于是早期「保存整个消息列表」的调用每轮都会把已有消息**再追加一遍**，
     * 通话历史页里就出现了成对重复的气泡（第 2 轮库里变成 u1 a1 u1 a1 u2 a2）。
     */
    @Transaction
    suspend fun replaceMessages(sessionId: String, messages: List<MessageEntity>) {
        deleteMessagesBySession(sessionId)
        if (messages.isNotEmpty()) insertMessages(messages)
    }

    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY timestamp ASC, id ASC")
    fun getMessagesBySession(sessionId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY timestamp ASC, id ASC")
    suspend fun getMessagesBySessionOnce(sessionId: String): List<MessageEntity>

    @Query("DELETE FROM messages WHERE sessionId = :sessionId")
    suspend fun deleteMessagesBySession(sessionId: String)
}
