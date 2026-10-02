package com.lv999call.app.ui.call

/**
 * 离线语音模型（Vosk）的准备状态。
 *
 * ## 为什么需要这一层
 *
 * 默认 ASR 走的是随包分发的 Vosk 模型，而它是 **assets 资产** —— 第一次要用它，
 * 必须先把约 65 MB 从 APK 解压到本机内部存储，再把模型读进内存。
 *
 * 旧实现没有这一段反馈：解压发生在 `startCharacterCall()` 里，界面上什么都不显示，
 * 用户看到的是「点了开始通话就没反应」；更糟的是失败时直接 `CallState.ENDED`
 * —— 人被踢回上一页，也不知道为什么。
 *
 * 现在这段等待有一个带进度和动画的遮罩（[VoskPrepareOverlay]），
 * 并且**失败不再中断通话**：语音输入用不了，文字输入照常。
 */
sealed interface VoskPrepareState {

    /**
     * 不需要准备。
     *
     * 三种情况会落到这里：当前不用 Vosk、模型已经载入内存、或者用户已经
     * 关掉了失败提示。
     */
    data object Idle : VoskPrepareState

    /**
     * 正在准备。
     *
     * [progress] 是解压进度 0f..1f。到 1f 表示解压已经完成，正在把模型读进内存
     * —— 这一段由 Kaldi 内部完成，没有细粒度进度可报，界面上换个文案即可。
     */
    data class Preparing(val progress: Float) : VoskPrepareState

    /**
     * 准备失败（assets 里没有这个模型、磁盘写满、模型文件损坏…）。
     *
     * 遮罩上给「重试」与「先打字聊」两个出口 —— 后者只是把状态清回 [Idle]，
     * 通话本身继续，不结束。
     */
    data class Failed(val message: String) : VoskPrepareState
}
