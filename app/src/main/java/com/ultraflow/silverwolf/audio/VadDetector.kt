package com.ultraflow.silverwolf.audio

import android.util.Log

/**
 * 简易VAD（语音活动检测）
 * 基于能量阈值的语音检测，检测说话→停顿→说话的模式
 */
class VadDetector {

    companion object {
        private const val TAG = "VadDetector"
        private const val ENERGY_THRESHOLD = 0.02f       // 能量阈值
        private const val SPEECH_MIN_FRAMES = 5          // 最少说话帧数（避免噪声误触发）
        private const val SILENCE_AFTER_SPEECH_FRAMES = 15 // 说话后静默帧数阈值（约300ms@16kHz/512）
        private const val MAX_SPEECH_FRAMES = 500        // 最大说话帧数（约10秒）
    }

    private var state = VadState.WAITING
    private var speechFrameCount = 0
    private var silenceFrameCount = 0
    private var totalSpeechFrames = 0

    enum class VadState {
        WAITING,    // 等待语音开始
        SPEAKING,   // 正在说话
        FINISHED    // 说完话（静默检测完成）
    }

    fun reset() {
        state = VadState.WAITING
        speechFrameCount = 0
        silenceFrameCount = 0
        totalSpeechFrames = 0
    }

    /**
     * 处理一帧音频数据，返回是否正在说话
     */
    fun isSpeaking(buffer: ShortArray, readSize: Int): Boolean {
        val energy = calculateEnergy(buffer, readSize)
        val isVoice = energy > ENERGY_THRESHOLD

        when (state) {
            VadState.WAITING -> {
                if (isVoice) {
                    speechFrameCount++
                    if (speechFrameCount >= SPEECH_MIN_FRAMES) {
                        state = VadState.SPEAKING
                        totalSpeechFrames = speechFrameCount
                        Log.d(TAG, "检测到语音开始")
                    }
                } else {
                    speechFrameCount = 0
                }
            }
            VadState.SPEAKING -> {
                if (isVoice) {
                    silenceFrameCount = 0
                    totalSpeechFrames++
                    if (totalSpeechFrames >= MAX_SPEECH_FRAMES) {
                        state = VadState.FINISHED
                        Log.d(TAG, "达到最大录音时长")
                    }
                } else {
                    silenceFrameCount++
                    if (silenceFrameCount >= SILENCE_AFTER_SPEECH_FRAMES) {
                        state = VadState.FINISHED
                        Log.d(TAG, "检测到语音结束（静默" + silenceFrameCount + "帧）")
                    }
                }
            }
            VadState.FINISHED -> {
                // 已结束，不做处理
            }
        }

        return state == VadState.SPEAKING
    }

    /**
     * 是否已完成语音检测（说完话后的停顿被检测到）
     */
    fun hasFinishedSpeaking(): Boolean {
        return state == VadState.FINISHED
    }

    fun getState(): VadState = state

    private fun calculateEnergy(buffer: ShortArray, readSize: Int): Float {
        var sum = 0.0
        for (i in 0 until readSize) {
            val normalized = buffer[i].toDouble() / Short.MAX_VALUE
            sum += normalized * normalized
        }
        return Math.sqrt(sum / readSize).toFloat()
    }
}
