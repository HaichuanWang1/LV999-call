package com.lv999call.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.lv999call.app.data.local.entity.SessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: SessionEntity)

    @Query("SELECT * FROM sessions ORDER BY createdAt DESC")
    fun getAllSessions(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE id = :sessionId")
    suspend fun getSessionById(sessionId: String): SessionEntity?

    @Query("DELETE FROM sessions WHERE id = :sessionId")
    suspend fun deleteSession(sessionId: String)

    @Query("DELETE FROM sessions")
    suspend fun deleteAllSessions()

    /**
     * 推进记忆总结游标。
     *
     * ⚠️ 只在记忆成功写库后调用，且必须与写记忆处于同一事务
     * （见 [com.lv999call.app.data.repository.MemoryRepository.saveMemoryAndAdvanceCursor]）：
     * 否则中途崩溃会留下「记忆写了、游标没动」，下次重复生成同一条记忆。
     */
    @Query("UPDATE sessions SET savedMemoryUpToTs = :upToTs, savedMemoryUpToId = :upToId WHERE id = :sessionId")
    suspend fun updateMemoryCursor(sessionId: String, upToTs: Long, upToId: Long)

    /**
     * 「待整理」会话：游标之后**还有消息**的会话，按 createdAt 倒序（plan4 §5.5 补总结用）。
     *
     * 判据是 (timestamp, id) 的字典序，与 `MessageDao` 的 `ORDER BY timestamp ASC, id ASC`
     * 口径一致，也与调用方切分「新消息」的判据一致 —— 只比 timestamp 会漏掉同毫秒的消息。
     *
     * @param characterKey 传 null = 不限角色（「有 N 通还没整理」的总数用它）；传具体键 =
     *        只找该角色的会话（针对某角色开聊/续聊时的补总结用它）
     */
    @Query(
        """
        SELECT s.* FROM sessions s
        WHERE (:characterKey IS NULL OR s.characterKey = :characterKey)
          AND EXISTS (
              SELECT 1 FROM messages m
              WHERE m.sessionId = s.id
                AND (
                    m.timestamp > s.savedMemoryUpToTs
                    OR (m.timestamp = s.savedMemoryUpToTs AND m.id > s.savedMemoryUpToId)
                )
          )
        ORDER BY s.createdAt DESC
        """
    )
    suspend fun getSessionsWithPendingMemory(characterKey: String?): List<SessionEntity>

    /** 按角色键取该角色的全部会话，新的在前（补总结扫描用） */
    @Query("SELECT * FROM sessions WHERE characterKey = :characterKey ORDER BY createdAt DESC")
    suspend fun getSessionsByCharacterKey(characterKey: String): List<SessionEntity>
}
