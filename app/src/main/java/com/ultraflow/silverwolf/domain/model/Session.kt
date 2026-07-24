package com.ultraflow.silverwolf.domain.model

/** 会话 */
data class Session(
    val id: String,
    val mode: DialogMode,
    val systemPrompt: String,
    val createdAt: Long = System.currentTimeMillis(),
    val messages: List<ChatMessage> = emptyList()
)
