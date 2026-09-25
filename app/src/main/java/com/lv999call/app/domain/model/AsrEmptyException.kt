package com.lv999call.app.domain.model

/**
 * 语音识别没有拿到任何文本（音频太短、纯噪声、服务端失败、模型没加载…）。
 *
 * ## 为什么需要单独一个异常
 *
 * 旧实现遇到这种情况会**伪造一条用户消息** `（语音识别失败）` 并返回，
 * 于是它会被 [com.lv999call.app.ui.call.CallViewModel] 写进消息列表、
 * 持久化进历史记录 —— 识别失败一次，聊天记录里就永久多一条假发言，
 * 而且还会把它当作"用户真的说了这句话"送进 LLM 上下文。
 *
 * 正确做法是把「没听清」和「用户说了什么」区分开：抛这个异常，
 * 由上层决定提示用户重说，而不是污染对话。
 */
class AsrEmptyException(message: String = "语音识别结果为空") : Exception(message)
