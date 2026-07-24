package com.lv999call.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "presets")
data class PresetEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,                    // 预设名称
    val prompt: String = "",             // 系统提示词
    val refAudioBase64: String = "",     // 参考音频base64
    val refAudioMime: String = "audio/wav",
    val avatarUri: String = "",          // 角色头像URI
    val backgroundUri: String = "",      // 背景图URI
    val createdAt: Long = System.currentTimeMillis()
)
