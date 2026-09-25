package com.lv999call.app.audio

import android.util.Log
import kotlin.math.max
import kotlin.math.sqrt

/**
 * 语音活动检测（VAD）。
 *
 * ## 相比旧版的三个关键改动
 *
 * 1. **所有时间常量改成毫秒**。旧版用「帧数」写死（`SILENCE_AFTER_SPEECH_FRAMES = 45`
 *    注释说约 1.5s），但帧长来自 `AudioRecord.getMinBufferSize()/2`，不同机型在
 *    40ms~128ms 之间浮动 —— 同一份代码在不同手机上「说完等 1.8 秒」还是「等 5.8 秒」
 *    完全看运气，最长录音时长同理（注释 30 秒，实际可能 60~190 秒）。
 *
 * 2. **自适应噪声底 + 迟滞双阈值**。旧版写死 `ENERGY_THRESHOLD = 0.02`（约 -34 dBFS）：
 *    说话偏轻的人根本触发不了（前面几个字被裁掉），嘈杂环境又会被底噪一直触发。
 *    现在按环境噪声实时估计噪声底，进入阈值 = 噪声底 × 倍数，退出阈值 = 进入阈值的一半，
 *    避免在阈值附近抖动导致一句话被切成好几段。
 *
 * 3. **暴露语音起止帧索引**。录音侧据此裁掉开头静音与结尾静默，只上传真正有语音的片段。
 *    开头那段静音是 Whisper 类模型最经典的幻觉触发器（喂纯静音会凭空输出内容）。
 *
 * 线程模型：只在录音协程里单线程调用，无需加锁。
 */
class VadDetector {

    companion object {
        private const val TAG = "VadDetector"

        // ---------- 时间常量（毫秒，与采样率/帧长解耦）----------

        /** 判定「开始说话」需要的连续语音时长：太短会被咳嗽/键盘声触发 */
        private const val SPEECH_ONSET_MS = 120

        /** 说话结束后，静默多久算「说完了」。1.2s 兼顾自然停顿与响应速度 */
        private const val SILENCE_HANG_MS = 1200

        /** 最长单次录音时长（超过则强制结束，交给 ASR） */
        private const val MAX_SPEECH_MS = 30_000

        /** 开头这一段用于估计噪声底（此时用户刚听完 TTS，通常还没开口） */
        private const val NOISE_CALIBRATION_MS = 400

        // ---------- 能量阈值 ----------

        /** 启动时的默认噪声底（约 -46 dBFS），校准后会被真实环境值替换 */
        private const val DEFAULT_NOISE_FLOOR = 0.005f

        /** 绝对下限：低于这个能量一律不算语音，避免极安静环境下噪声底趋零后乱触发 */
        private const val MIN_SPEECH_ENERGY = 0.012f

        /**
         * 噪声底上限。既是「房间极吵」的兜底，也是校准期的安全阀：
         * 万一用户在校准窗口内就开口了，均值会被抬到人声水平，
         * 卡在 0.02 可保证进入阈值最高只到 0.06（约 -24 dBFS），正常说话仍能触发。
         */
        private const val MAX_NOISE_FLOOR = 0.02f

        /** 进入阈值 = max(噪声底 × 该倍数, MIN_SPEECH_ENERGY)，即高出噪声底约 9.5dB */
        private const val NOISE_ONSET_FACTOR = 3.0f

        /**
         * 噪声底自适应速度。
         *
         * WAITING 阶段要**快**：用户还没开口，这几帧就是纯环境噪声，尽快收敛才能避免
         * 「噪声一直超过阈值 → 误判成说话」。SPEAKING 阶段要**慢**，否则人声的
         * 低能量音素会把噪声底慢慢顶上去。
         */
        private const val NOISE_ADAPT_ALPHA_WAITING = 0.2f
        private const val NOISE_ADAPT_ALPHA_SPEAKING = 0.02f

        /**
         * 一帧超过噪声底多少倍时认为它是人声、不参与噪声底更新。
         *
         * 必须明显大于 [NOISE_ONSET_FACTOR]，否则会出现死锁：噪声一旦超过进入阈值就
         * 再也无法更新噪声底，噪声底永远升不上去，于是持续误触发。
         * 取 6 倍后，「既不触发语音、也不更新噪声底」的空档（3~6 倍之间）正好被自适应覆盖。
         */
        private const val NOISE_UPDATE_MAX_FACTOR = 6.0f
    }

    enum class VadState {
        WAITING,    // 等待语音开始
        SPEAKING,   // 正在说话
        FINISHED    // 说完话（静默检测完成）
    }

    private var state = VadState.WAITING

    /** 帧时长（毫秒），由 [configure] 按真实帧长设定 */
    private var frameMs: Float = 32f

    private var noiseFloor = DEFAULT_NOISE_FLOOR
    private var calibratedFrames = 0

    /** 校准期能量累加（用均值而不是最小值：最小值会被偶发的极安静帧拉到 0） */
    private var calibrationSum = 0f
    private var calibrationCount = 0

    /** 当前进入阈值（随噪声底浮动） */
    private var onsetThreshold = MIN_SPEECH_ENERGY

    /** 连续语音帧数 / 连续静默帧数 / 已累计语音帧数 */
    private var voicedRunFrames = 0
    private var silenceRunFrames = 0
    private var totalVoicedFrames = 0

    /** 语音片段起止帧索引（含预滚前的真实起点），供录音侧裁剪 */
    private var speechStartFrame = -1
    private var speechEndFrame = -1

    /** 已处理的总帧数 */
    private var frameIndex = 0

    /** 最近一次的能量值，供 UI 显示 */
    var lastEnergy: Float = 0f
        private set

    /**
     * 按真实帧长初始化。必须在 [reset] 之前或之后立刻调用一次。
     *
     * @param frameSamples 每帧采样点数（AudioRecord.read 返回的最大样本数）
     * @param sampleRate 采样率
     */
    fun configure(frameSamples: Int, sampleRate: Int) {
        if (frameSamples > 0 && sampleRate > 0) {
            frameMs = frameSamples * 1000f / sampleRate
        }
    }

    fun reset() {
        state = VadState.WAITING
        noiseFloor = DEFAULT_NOISE_FLOOR
        calibratedFrames = 0
        calibrationSum = 0f
        calibrationCount = 0
        onsetThreshold = MIN_SPEECH_ENERGY
        voicedRunFrames = 0
        silenceRunFrames = 0
        totalVoicedFrames = 0
        speechStartFrame = -1
        speechEndFrame = -1
        frameIndex = 0
        lastEnergy = 0f
    }

    /** 判定「开始说话」所需帧数（至少 1 帧） */
    private fun onsetFrames(): Int =
        max(1, (SPEECH_ONSET_MS / frameMs).toInt())

    /** 判定「说完」所需静默帧数（至少 1 帧） */
    private fun hangFrames(): Int =
        max(1, (SILENCE_HANG_MS / frameMs).toInt())

    /** 最长语音帧数 */
    private fun maxSpeechFrames(): Int =
        max(1, (MAX_SPEECH_MS / frameMs).toInt())

    /**
     * 处理一帧音频数据，返回是否正在说话。
     */
    fun isSpeaking(buffer: ShortArray, readSize: Int): Boolean {
        if (readSize <= 0) return state == VadState.SPEAKING

        val energy = calculateEnergy(buffer, readSize)
        lastEnergy = energy

        // 开头若干帧（用户通常还没开口）用能量均值估计噪声底，让阈值贴合实际环境。
        // 用均值而不是最小值：最小值会被偶发的极安静帧拉到接近 0，导致阈值退化成
        // 绝对下限，在嘈杂环境里就会一直误触发。
        if (calibratedFrames < (NOISE_CALIBRATION_MS / frameMs).toInt()) {
            calibrationSum += energy
            calibrationCount++
            calibratedFrames++
            if (calibrationCount > 0) {
                noiseFloor = (calibrationSum / calibrationCount).coerceIn(1e-5f, MAX_NOISE_FLOOR)
                onsetThreshold = max(noiseFloor * NOISE_ONSET_FACTOR, MIN_SPEECH_ENERGY)
            }
        }

        val enter = energy > onsetThreshold
        // 退出阈值取进入阈值的一半：迟滞，防止一句话在阈值附近被切碎
        val exit = energy > onsetThreshold * 0.5f
        // 校准窗口内由均值估计主导，不再叠加慢速自适应（否则两个估计互相拉扯）
        val calibrating = calibratedFrames <= (NOISE_CALIBRATION_MS / frameMs).toInt()

        when (state) {
            VadState.WAITING -> {
                if (enter) {
                    voicedRunFrames++
                    if (voicedRunFrames >= onsetFrames()) {
                        state = VadState.SPEAKING
                        // 起点回退到这一串连续语音的第一帧，保证「第一个字」不被裁掉
                        speechStartFrame = frameIndex - voicedRunFrames + 1
                        totalVoicedFrames = voicedRunFrames
                        Log.d(
                            TAG,
                            "检测到语音开始: frame=$speechStartFrame, " +
                                "energy=$energy, floor=$noiseFloor, thr=$onsetThreshold"
                        )
                    }
                } else {
                    voicedRunFrames = 0
                    if (!calibrating) adaptNoiseFloor(energy, fast = true)
                }
            }

            VadState.SPEAKING -> {
                if (exit) {
                    silenceRunFrames = 0
                    totalVoicedFrames++
                    if (totalVoicedFrames >= maxSpeechFrames()) {
                        speechEndFrame = frameIndex
                        state = VadState.FINISHED
                        Log.d(TAG, "达到最大录音时长 (${MAX_SPEECH_MS}ms)")
                    }
                } else {
                    silenceRunFrames++
                    if (!calibrating) adaptNoiseFloor(energy, fast = false)
                    if (silenceRunFrames >= hangFrames()) {
                        // 语音终点 = 静默开始处（不含这段静默）
                        speechEndFrame = frameIndex - silenceRunFrames
                        state = VadState.FINISHED
                        Log.d(
                            TAG,
                            "检测到语音结束: frame=$speechEndFrame, " +
                                "静默=${silenceRunFrames}帧(${(silenceRunFrames * frameMs).toInt()}ms)"
                        )
                    }
                }
            }

            VadState.FINISHED -> Unit
        }

        frameIndex++
        return state == VadState.SPEAKING
    }

    /**
     * 慢速跟踪噪声底。
     *
     * 只在「能量不超过噪声底 [NOISE_UPDATE_MAX_FACTOR] 倍」的帧上更新 —— 更高的一律
     * 当作人声，不让它污染噪声底。这个倍数必须大于进入阈值倍数，否则噪声一旦超过
     * 阈值就再也更新不了噪声底，会一直误触发（见常量注释）。
     *
     * @param fast WAITING 阶段用大步长快速收敛；SPEAKING 阶段用小步长避免人声顶高噪声底
     */
    private fun adaptNoiseFloor(energy: Float, fast: Boolean) {
        if (energy > noiseFloor * NOISE_UPDATE_MAX_FACTOR) return
        val alpha = if (fast) NOISE_ADAPT_ALPHA_WAITING else NOISE_ADAPT_ALPHA_SPEAKING
        noiseFloor += (energy - noiseFloor) * alpha
        noiseFloor = noiseFloor.coerceIn(1e-5f, MAX_NOISE_FLOOR)
        onsetThreshold = max(noiseFloor * NOISE_ONSET_FACTOR, MIN_SPEECH_ENERGY)
    }

    /** 是否已完成语音检测（说完话后的停顿被检测到） */
    fun hasFinishedSpeaking(): Boolean = state == VadState.FINISHED

    fun getState(): VadState = state

    /**
     * 语音起始帧索引；尚未检测到语音时为 -1。
     */
    fun speechStartFrame(): Int = speechStartFrame

    /**
     * 语音结束帧索引（不含尾部静默）；尚未结束时为 -1。
     */
    fun speechEndFrame(): Int = speechEndFrame

    /**
     * 当前进入阈值（供日志/调试观察自适应效果）。
     */
    fun currentThreshold(): Float = onsetThreshold

    private fun calculateEnergy(buffer: ShortArray, readSize: Int): Float {
        var sum = 0.0
        for (i in 0 until readSize) {
            val normalized = buffer[i].toDouble() / Short.MAX_VALUE
            sum += normalized * normalized
        }
        return sqrt(sum / readSize).toFloat()
    }
}
