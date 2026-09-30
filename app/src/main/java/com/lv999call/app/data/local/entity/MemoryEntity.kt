package com.lv999call.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 长期记忆表（跨会话）。
 *
 * ⚠️ 本实体的列顺序 / 类型 / 外键 / 索引名与 [com.lv999call.app.data.local.AppDatabase]
 * 里手写的 `MIGRATION_3_4` **必须逐字一致**：`exportSchema = false` 只关掉了 schema
 * 文件导出，**没有**关掉运行时校验 —— Room 首次打开数据库时会用这里推导出的期望
 * schema 去比对真实库结构，且去掉破坏性兜底之后没有自愈机会，不一致就是启动即崩。
 *
 * 外键 `sessionId → sessions.id` + `CASCADE` 的意图：会话被清理时，它派生的记忆一起消失，
 * 不留说不清来源的孤儿。注意当前全仓没有任何 UI 调用 `deleteSession`，
 * 所以这条级联现在是一张永不执行的安全网，**不是清理手段**（plan4 §2.2 / P10）。
 *
 * 唯一索引 `(characterId, contentHash)` 是幂等的第二道：游标只能防「同一会话被重复总结」，
 * 防不住「同一角色在两通电话里总结出同一句话」，靠 `INSERT OR IGNORE` 吃掉后者。
 *
 * @param characterId 角色隔离键（角色 id / `preset:<id>` / `default`）
 * @param contentHash 归一化后的内容哈希，见 [com.lv999call.app.domain.model.Memory.contentHash]
 * @param importance 0~10，折叠时用来决定「先丢谁」，不参与排序
 * @param lastUsedAt 最近一次被注入上下文的时刻（0 = 从未）
 * @param sourceFromTs / [sourceFromId] / [sourceToTs] / [sourceToId]
 *        本条覆盖的消息区间 (timestamp, id) 有序对；`sourceTo*` 同时是会话的总结游标
 */
@Entity(
    tableName = "memories",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["sessionId"]),
        Index(value = ["characterId"]),
        Index(value = ["characterId", "contentHash"], unique = true)
    ]
)
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val characterId: String,
    val createdAt: Long,
    val sessionId: String,
    val content: String,
    val contentHash: String,
    val category: String,
    val importance: Int,
    val lastUsedAt: Long,
    val sourceFromTs: Long,
    val sourceFromId: Long,
    val sourceToTs: Long,
    val sourceToId: Long
)
