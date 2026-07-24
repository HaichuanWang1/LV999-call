package com.ultraflow.silverwolf.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey
    val id: String,
    val mode: String,           // DialogMode枚举名
    val systemPrompt: String,
    val createdAt: Long
)
