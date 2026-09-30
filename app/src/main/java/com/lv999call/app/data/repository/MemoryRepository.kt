package com.lv999call.app.data.repository

import androidx.room.withTransaction
import com.lv999call.app.data.local.AppDatabase
import com.lv999call.app.data.local.dao.MemoryDao
import com.lv999call.app.data.local.dao.SessionDao
import com.lv999call.app.data.local.entity.MemoryEntity
import com.lv999call.app.domain.model.Memory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 长期记忆仓库：领域模型 ↔ 实体映射 + 对外读写方法。
 *
 * 为什么这里要持有 [AppDatabase]：plan4 §3.2 Step 7/8 要求「写记忆」与「推会话游标」
 * 落在**同一个 Room 事务**里。两条写操作分属 [MemoryDao] 与 [SessionDao]，
 * DAO 之间无法互相调用（`@Transaction` 的默认方法只能操作自己那张表），
 * 所以只能在仓库层用 `database.withTransaction` 把它们包起来 —— 这是本文件唯一
 * 需要 database 的地方，别把它当成可以随便扩散的口子。
 *
 * 事务的必要性：中途崩溃若只写了一半，会出现「记忆写了、游标没动」→ 下次对着同一段
 * 对话重复生成同一条记忆（虽然有哈希去重兜住内容，但游标会一直停在原地）。
 */
class MemoryRepository(
    private val memoryDao: MemoryDao,
    private val sessionDao: SessionDao,
    private val database: AppDatabase
) {

    /** 只写记忆、不动游标（手动整理/测试路径用；正常总结链路走 [saveMemoryAndAdvanceCursor]） */
    suspend fun saveMemory(memory: Memory): Long = memoryDao.insertMemory(memory.toEntity())

    /**
     * 「写记忆 + 推游标」原子完成（plan4 §3.2 Step 7/8）。
     *
     * @param toTs / [toId] 游标推进到的消息位置，默认就是本条记忆覆盖区间的终点 `sourceTo*`
     * @return 新记忆 id；内容命中去重时返回**已存在那条**的 id；理论上不会返回 -1
     *
     * ⚠️ 去重命中（rowId == -1）时游标**照样推进**：内容已经记过一次，这段对话不需要
     * 再总结；若不推进，每次触发都会重新总结、每次都被 IGNORE，永久卡在同一段对话上。
     */
    suspend fun saveMemoryAndAdvanceCursor(
        memory: Memory,
        toTs: Long = memory.sourceToTs,
        toId: Long = memory.sourceToId
    ): Long {
        val entity = memory.toEntity()
        return database.withTransaction {
            val rowId = memoryDao.insertMemory(entity)
            sessionDao.updateMemoryCursor(memory.sessionId, toTs, toId)
            if (rowId != -1L) {
                rowId
            } else {
                memoryDao.getMemoryByHash(entity.characterId, entity.contentHash)?.id ?: -1L
            }
        }
    }

    /** 某角色的记忆流（记忆库页按角色筛选用），新的在前 */
    fun getMemoriesByCharacter(characterId: String): Flow<List<Memory>> =
        memoryDao.getMemoriesByCharacter(characterId).map { list -> list.map { it.toDomain() } }

    /** 全部记忆流 */
    fun getAllMemories(): Flow<List<Memory>> =
        memoryDao.getAllMemories().map { list -> list.map { it.toDomain() } }

    /** 某角色的记忆（一次性），新的在前 —— 注入装配用 */
    suspend fun getMemoriesByCharacterOnce(characterId: String): List<Memory> =
        memoryDao.getMemoriesByCharacterOnce(characterId).map { it.toDomain() }

    /** 记忆总条数（首页「🧠 记忆库（N 条）」入口用） */
    suspend fun countMemories(): Int = memoryDao.countMemories()

    /** 该角色最近一次生成记忆的时刻；从未生成过返回 null（时间闸门用） */
    suspend fun getLatestCreatedAt(characterId: String): Long? =
        memoryDao.getLatestCreatedAt(characterId)

    /** 批量回写注入时间 */
    suspend fun markUsed(ids: List<Long>, usedAt: Long = System.currentTimeMillis()) {
        if (ids.isEmpty()) return
        memoryDao.updateLastUsedAt(ids, usedAt)
    }

    /**
     * 删除单条记忆。
     *
     * 精确作用于 `memories` 这一行，**不走「删会话」路径**：用户的诉求常常是
     * 「忘掉这件事」而不是「删掉那通电话」（plan4 §6.3）。
     */
    suspend fun deleteMemory(id: Long) = memoryDao.deleteMemory(id)

    /** 清空某角色的全部记忆 */
    suspend fun clearMemoriesByCharacter(characterId: String) = memoryDao.deleteMemoriesByCharacter(characterId)

    /** 一键清空 */
    suspend fun clearAllMemories() = memoryDao.deleteAllMemories()

    // --- 映射函数 ---

    private fun MemoryEntity.toDomain() = Memory(
        id = id,
        characterId = characterId,
        createdAt = createdAt,
        sessionId = sessionId,
        content = content,
        contentHash = contentHash,
        category = category,
        importance = importance,
        lastUsedAt = lastUsedAt,
        sourceFromTs = sourceFromTs,
        sourceFromId = sourceFromId,
        sourceToTs = sourceToTs,
        sourceToId = sourceToId
    )

    private fun Memory.toEntity() = MemoryEntity(
        id = id,
        characterId = characterId,
        createdAt = createdAt,
        sessionId = sessionId,
        content = content,
        // 哈希留空时在这里兜底算出来：唯一索引靠它生效，漏了就等于去重静默失效
        contentHash = contentHash.ifBlank { MemoryContentNormalizer.hashOf(content) },
        category = category,
        importance = importance,
        lastUsedAt = lastUsedAt,
        sourceFromTs = sourceFromTs,
        sourceFromId = sourceFromId,
        sourceToTs = sourceToTs,
        sourceToId = sourceToId
    )
}

/**
 * 记忆内容的归一化与哈希（plan4 §2.2「去重」）。
 *
 * 归一化很关键：不归一化的话「用户 喜欢深夜写代码」与「用户喜欢深夜写代码」会被当成
 * 两条不同记忆。这里严格按方案做三件事 —— 去首尾空白、压缩内部空白、全角转半角；
 * **不做**大小写折叠之类的额外加工，宁可漏去重（留下两条近义记忆）也不能误去重
 * （丢掉不同信息）。
 */
internal object MemoryContentNormalizer {

    /** 连续空白（含全角空格转过来的普通空格）压成一个 */
    private val whitespace = Regex("\\s+")

    /** 全角 ASCII 段（！ 到 ～）：减 0xFEE0 即得对应半角字符 */
    private const val FULLWIDTH_START = 0xFF01
    private const val FULLWIDTH_END = 0xFF5E
    private const val FULLWIDTH_OFFSET = 0xFEE0

    fun normalize(content: String): String {
        val sb = StringBuilder(content.length)
        for (ch in content) {
            sb.append(
                when {
                    // 表意空格（U+3000）不在上面那个区间里，单独处理
                    ch == '\u3000' -> ' '
                    ch.code in FULLWIDTH_START..FULLWIDTH_END -> (ch.code - FULLWIDTH_OFFSET).toChar()
                    else -> ch
                }
            )
        }
        return sb.toString().replace(whitespace, " ").trim()
    }

    /** 归一化后的内容的哈希（SHA-256 小写十六进制）；只作去重键，不需要抗碰撞之外的性质 */
    fun hashOf(content: String): String {
        val bytes = java.security.MessageDigest.getInstance("SHA-256")
            .digest(normalize(content).toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            if (v < 0x10) sb.append('0')
            sb.append(v.toString(16))
        }
        return sb.toString()
    }
}
