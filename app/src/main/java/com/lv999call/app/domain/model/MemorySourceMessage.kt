package com.lv999call.app.domain.model

/**
 * 记忆总结用的会话消息 —— 比 [ChatMessage] 多一个数据库 id。
 *
 * 为什么不能直接用 [ChatMessage]：记忆游标的判据是 **(timestamp, id) 有序对**
 * （plan4 §2.2 / P6）—— `ChatMessage.timestamp` 不唯一，同一毫秒落库的两条消息里
 * 会有一条永远落在严格 `>` 的游标之外，于是永久漏总结。而 [ChatMessage] 里**没有 id**，
 * 它是"发给 LLM 的内容"，给几十个构造点都塞一个恒为 0 的 id 只会让语义变脏。
 * 所以这个 id 单独由本模型承载，只为记忆游标服务。
 *
 * @param id 消息表自增主键。注意 `replaceMessages()` 是**先删后插**，整批重排后 id 会变，
 *        所以它不能单独作游标，必须与 [timestamp] 组成有序对
 * @param timestamp 消息时间（毫秒）
 */
data class MemorySourceMessage(
    val id: Long,
    val role: String,           // "user" | "assistant"
    val content: String,
    val timestamp: Long
)
