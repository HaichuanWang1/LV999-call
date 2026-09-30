package com.lv999call.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 会话表。
 *
 * ⚠️ 后三列由手写的 `MIGRATION_3_4`（见 [com.lv999call.app.data.local.AppDatabase]）用
 * `ALTER TABLE ... ADD COLUMN ... NOT NULL DEFAULT ...` 加上，因此**这里故意不给默认值**：
 * 实体是数据层结构，读写都应该显式带上这三个字段，尤其是两个游标 ——
 * 映射函数漏写一次就会把游标重置为 0，让「待整理会话」查询永远命中全部历史（plan4 P7）。
 *
 * @param characterKey 记忆的角色隔离键（plan4 §2.3）：内置角色 = 角色 id，
 *        自定义预设 = `preset:<presetId>`，快速模式/推不出角色 = `default`。
 *        存量会话在迁移里统一填 `default`（历史数据推不出角色，且它们本来也没有记忆）
 * @param savedMemoryUpToTs 记忆总结游标 —— 本会话已被总结到的消息位置 (timestamp, id) 有序对。
 *        为什么是「有序对」而不是单个 timestamp：`ChatMessage.timestamp` 不唯一，
 *        同毫秒的两条消息会有一条永远落在严格 `>` 的游标外 → 永久漏总结（plan4 §2.2 / P6）
 * @param savedMemoryUpToId 同上，id 部分
 */
@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey
    val id: String,
    val mode: String,           // DialogMode枚举名
    val systemPrompt: String,
    val createdAt: Long,
    val characterKey: String,           // 记忆隔离键（角色 id / preset:<id> / default）
    val savedMemoryUpToTs: Long,        // 记忆游标：已总结到的消息 timestamp
    val savedMemoryUpToId: Long         // 记忆游标：已总结到的消息 id
)
