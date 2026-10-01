package com.lv999call.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.lv999call.app.data.local.entity.MemoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoryDao {

    /**
     * 写入一条记忆。
     *
     * ⚠️ 必须是 `IGNORE` 而不是 `REPLACE`/`ABORT`：内容哈希（+ 角色键）唯一索引命中时，
     * 我们要的是「静默丢掉这条重复内容」，而不是覆盖已有记忆（会换掉它的 createdAt 与
     * 来源区间），更不能抛异常打断整个总结流程。
     *
     * @return 新行 id；命中去重被忽略时返回 -1（调用方可据此回读已有那条的 id）
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMemory(memory: MemoryEntity): Long

    /** 某角色的记忆，新的在前（注入时按时间倒序取最近若干条） */
    @Query("SELECT * FROM memories WHERE characterId = :characterId ORDER BY createdAt DESC, id DESC")
    fun getMemoriesByCharacter(characterId: String): Flow<List<MemoryEntity>>

    /** 同上的一次性读取（注入装配、时间闸门用；Flow 版在协程里首值也可，但这样语义更直白） */
    @Query("SELECT * FROM memories WHERE characterId = :characterId ORDER BY createdAt DESC, id DESC")
    suspend fun getMemoriesByCharacterOnce(characterId: String): List<MemoryEntity>

    /** 全部记忆（记忆库页面用） */
    @Query("SELECT * FROM memories ORDER BY createdAt DESC, id DESC")
    fun getAllMemories(): Flow<List<MemoryEntity>>

    /** 按内容哈希找已有记忆：去重命中后回读它的 id，用于把结果如实回报给上层 */
    @Query("SELECT * FROM memories WHERE characterId = :characterId AND contentHash = :contentHash LIMIT 1")
    suspend fun getMemoryByHash(characterId: String, contentHash: String): MemoryEntity?

    @Query("SELECT COUNT(*) FROM memories")
    suspend fun countMemories(): Int

    /**
     * 该角色最近一次生成记忆的时刻（毫秒）；从未生成过返回 null。
     *
     * 时间闸门必须是**按角色**而不是按会话（plan4 §5.3 / P4）：按会话算恒成立（一个会话
     * 只总结一次），挡不住「挂断隔 3 秒又打过来」这种连击。
     */
    @Query("SELECT MAX(createdAt) FROM memories WHERE characterId = :characterId")
    suspend fun getLatestCreatedAt(characterId: String): Long?

    /** 批量回写注入时间。当前只作观测/未来淘汰依据，不参与排序（避免「用过就更容易被用」的正反馈） */
    @Query("UPDATE memories SET lastUsedAt = :usedAt WHERE id IN (:ids)")
    suspend fun updateLastUsedAt(ids: List<Long>, usedAt: Long)

    /**
     * 最近一条**可注入**记忆属于哪个角色；一条都没有时返回 null。
     *
     * 给「记忆提醒通知」挑角色时做**回落**用。那条链路本来优先选"最近聊过的角色"
     * （提醒要接着最近这次聊天往下说），但那个桶里完全可能一条记忆都没有 ——
     * 最近一通是快速模式（`default`）、刚认识的新角色、或那通没到总结门槛。
     * 只认首选的话就会**每个周期都在"没有可用记忆"处跳过，用户永远收不到提醒，
     * 而别的角色明明有记忆**，日志里还只是一行 D 级跳过，看不出是功能坏了。
     *
     * 排除的类别由调用方传入，而不是在 SQL 里写死 `'summary_flagged'`：那个字面量是
     * [com.lv999call.app.domain.model.Memory.CATEGORY_SUMMARY_FLAGGED] 的值，
     * 在 SQL 里再写一份等于把同一个事实存了两处，改一处漏一处。
     *
     * 排序口径与 [getMemoriesByCharacterOnce] 一致（`createdAt DESC, id DESC`）——
     * `createdAt` 不唯一，不带 id 决胜的话同毫秒两条的先后不可复现。
     */
    @Query(
        """
        SELECT characterId FROM memories
        WHERE category != :excludeCategory
        ORDER BY createdAt DESC, id DESC LIMIT 1
        """
    )
    suspend fun getLatestCharacterKeyExcluding(excludeCategory: String): String?

    @Query("DELETE FROM memories WHERE id = :id")
    suspend fun deleteMemory(id: Long)

    @Query("DELETE FROM memories WHERE characterId = :characterId")
    suspend fun deleteMemoriesByCharacter(characterId: String)

    @Query("DELETE FROM memories")
    suspend fun deleteAllMemories()
}
