package com.lv999call.app.domain.model

/** 通话状态机 */
enum class CallState {
    IDLE,       // 空闲 / 准备就绪
    LISTENING,  // 录音中（VAD检测）
    THINKING,   // 等待LLM首字
    SPEAKING,   // TTS播放中（可打断）
    ENDED       // 通话已结束
}
