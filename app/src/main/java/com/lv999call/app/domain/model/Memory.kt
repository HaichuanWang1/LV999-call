package com.lv999call.app.domain.model

/**
 * 一条长期记忆（跨会话）。
 *
 * 为什么必须独立于 [ChatMessage]：记忆表达的是「跨会话的事实」，而消息天然绑死在
 * 某一次通话上（`messages.sessionId` 外键指向单个会话）。把总结塞进消息表会污染
 * 历史页、也会被每轮的「整体替换」冲掉（plan4 §2.1 已否决该方案）。
 *
 * @param characterId 角色隔离键，取值见 [Session.characterKey]：
 *        内置角色 = 角色 id，自定义预设 = `preset:<presetId>`，其余 = [Session.CHARACTER_KEY_DEFAULT]
 * @param createdAt 生成时刻（毫秒）
 * @param sessionId 来源会话 —— 只用于展示「来自哪通电话」；会话被删时记忆随之级联消失
 * @param content 总结正文（遵循 plan4 §3.4 的提示词协议，≤200 字）
 * @param contentHash 归一化（去首尾空白 / 压缩内部空白 / 全角转半角）后的内容哈希。
 *        与 [characterId] 组成唯一索引，负责「同一角色在两通电话里总结出同一句话」的去重，
 *        这是游标防不住的一类重复。留空时由 [com.lv999call.app.data.repository.MemoryRepository] 兜底计算
 * @param category 类型。当前只写 [CATEGORY_SUMMARY]；命中指令式措辞的自生成注入降级为
 *        [CATEGORY_SUMMARY_FLAGGED]（仍然可见可删，只是不注入上下文）
 * @param importance 0~10，由总结时的 LLM 顺带打分。只用于超限折叠，**不参与排序**
 * @param lastUsedAt 最近一次被注入上下文的时刻（0 = 从未），当前只作观测
 * @param sourceFromTs 本条覆盖的消息区间起点 (timestamp, id) 的 timestamp
 * @param sourceFromId 同上，id 部分
 * @param sourceToTs 区间终点 timestamp —— 写库时它同时是会话的总结游标
 * @param sourceToId 区间终点 id
 */
data class Memory(
    val id: Long = 0,
    val characterId: String,
    val createdAt: Long = System.currentTimeMillis(),
    val sessionId: String,
    val content: String,
    val contentHash: String = "",
    val category: String = CATEGORY_SUMMARY,
    val importance: Int = DEFAULT_IMPORTANCE,
    val lastUsedAt: Long = 0,
    val sourceFromTs: Long = 0,
    val sourceFromId: Long = 0,
    val sourceToTs: Long = 0,
    val sourceToId: Long = 0
) {
    companion object {
        /** 正常总结 */
        const val CATEGORY_SUMMARY = "summary"

        /** 含指令式措辞的总结：不注入上下文，但保留在库里供用户查看/删除 */
        const val CATEGORY_SUMMARY_FLAGGED = "summary_flagged"

        /** LLM 没给出可解析的重要度时的兜底值（不要为它单独再发一次请求） */
        const val DEFAULT_IMPORTANCE = 5
    }
}
