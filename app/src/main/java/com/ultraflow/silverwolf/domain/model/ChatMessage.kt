package com.ultraflow.silverwolf.domain.model

/** 聊天消息 */
data class ChatMessage(
    val role: String,           // "user" | "assistant" | "system"
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
)
