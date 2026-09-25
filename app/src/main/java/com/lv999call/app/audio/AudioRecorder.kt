package com.lv999call.app.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream

/**
 * 音频录制器
 *
 * ## 送进 ASR 的音频是怎么来的
 *
 * 旧实现把「按下录音 → 检测到停顿」之间的**全部原始数据**直接交给 ASR，于是：
 * - 开头最多 3 秒静音一起被送走 —— 这是 Whisper 类模型最经典的幻觉触发器，
 *   喂纯静音它会凭空输出一段内容；
 * - VAD 需要连续若干帧超阈值才认定「开始说话」，那几帧之前的声音被丢掉，
 *   表现为**第一个字经常被吃掉**；
 * - 结尾的静默同样白送。
 *
 * 现在改为：录音全程写进内存，VAD 给出语音起止帧索引后，**只裁剪出
 * [起点-预滚, 终点+尾音] 这一段**再交给 ASR。预滚让第一个字完整保留，
 * 裁剪让开头/结尾静默不再诱发幻觉。
 *
 * ## 采集参数
 *
 * 麦克风源优先 `VOICE_RECOGNITION`：该路径下系统**必须关闭** AGC 与降噪
 * （见 AOSP《音频预处理》），而这正是 ASR 想要的 —— AGC 会在句间停顿把底噪顶上来，
 * 让固定阈值的 VAD 一直误触发。设备不支持时回退 `MIC`，并显式关闭三个音效
 * （能拿到 session id 的前提下）。
 */
class AudioRecorder(private val context: Context) {

    companion object {
        private const val TAG = "AudioRecorder"
        const val SAMPLE_RATE = 16000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        /** 预滚时长：VAD 判定「开始说话」需要若干帧，这几帧必须补回来，否则吃掉第一个字 */
        private const val PRE_ROLL_MS = 300

        /** 尾音保留时长：留一点自然收尾，避免最后一个字的韵尾被切掉 */
        private const val TAIL_PAD_MS = 200

        /**
         * 总录音上限，兜底防止缓冲区无限增长（VAD 自己也有 30s 上限，
         * 这里留足冗余：VAD 未触发但录音没停的异常路径不该吃光内存）。
         */
        private const val MAX_TOTAL_MS = 45_000
    }

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    // 每次录音使用独立的scope，避免release后复用失效
    private var scope: CoroutineScope? = null
    // 保证 read() 和 stop() 不并发
    private val recordMutex = Mutex()

    /** 显式持有音效对象，避免被 GC 回收导致效果中途失效（关闭失败时保持 null） */
    private var noiseSuppressor: NoiseSuppressor? = null
    private var gainControl: AutomaticGainControl? = null
    private var echoCanceler: AcousticEchoCanceler? = null

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _audioLevel = MutableStateFlow(0f)
    val audioLevel: StateFlow<Float> = _audioLevel.asStateFlow()

    private val vadDetector = VadDetector()

    /**
     * 开始录音并进行VAD检测
     */
    fun startRecording(
        onSpeechEnd: (pcmData: ByteArray) -> Unit,
        onSilence: () -> Unit = {}
    ) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "没有录音权限")
            return
        }

        // 非阻塞停止旧录音
        _isRecording.value = false
        recordingJob?.cancel()

        // 每次录音创建新的scope（先取消旧的避免泄漏）
        scope?.cancel()
        val newScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope = newScope

        recordingJob = newScope.launch {
            val bufferSize: Int
            val frameSamples: Int
            // Mutex 保证旧 AudioRecord 已 stop 后再创建新的
            recordMutex.withLock {
                try { audioRecord?.stop() } catch (_: Exception) {}
                releaseAudioEffects()

                bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
                if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
                    Log.e(TAG, "无法获取合适的缓冲区大小")
                    return@launch
                }

                // 每次读一帧 = minBufferSize 的样本数，但至少 320 采样点（20ms），
                // 避免个别机型返回极小的 minBufferSize 导致 VAD 帧率过高。
                // 缓冲区字节数仍取 max(帧大小, minBufferSize)：AudioRecord 要求
                // 缓冲区不小于 minBufferSize，否则构造失败
                frameSamples = maxOf(bufferSize / 2, SAMPLE_RATE / 50)

                try {
                    audioRecord = createAudioRecord(maxOf(frameSamples * 2, bufferSize))
                    if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                        Log.e(TAG, "AudioRecord初始化失败")
                        release()
                        return@launch
                    }

                    applyAudioEffects(audioRecord!!.audioSessionId)

                    audioRecord?.startRecording()
                    _isRecording.value = true
                    vadDetector.configure(frameSamples, SAMPLE_RATE)
                    vadDetector.reset()
                } catch (e: Exception) {
                    Log.e(TAG, "录音启动失败: ${e.message}")
                    release()
                    return@launch
                }
            }

            // 捕获本协程持有的 AudioRecord 实例，防止被新协程覆盖后误停
            val myRecord = audioRecord

            val allAudioData = ByteArrayOutputStream()
            val buffer = ShortArray(frameSamples)
            /**
             * 每帧在 [allAudioData] 里的起始字节偏移。
             *
             * 不能直接用「帧号 × 固定帧长」换算：`AudioRecord.read()` 允许返回**少于**
             * 请求长度的样本数（尤其收尾帧），一旦发生，按固定帧长推算出的偏移就会
             * 整体错位，裁出来的语音片段要么缺字要么串音。
             * 末位补上总字节数，让第 i 帧的区间是 [offsets[i], offsets[i+1])。
             */
            val frameOffsets = ArrayList<Int>(1024)

            val maxTotalBytes = SAMPLE_RATE * 2 * MAX_TOTAL_MS / 1000
            var totalBytes = 0

            while (isActive && _isRecording.value) {
                val readSize = recordMutex.withLock {
                    audioRecord?.read(buffer, 0, buffer.size) ?: -1
                }
                if (readSize <= 0) continue

                _audioLevel.value = calculateRMS(buffer, readSize)

                frameOffsets.add(totalBytes)
                val frameBytes = shortsToBytes(buffer, readSize)
                allAudioData.write(frameBytes, 0, frameBytes.size)
                totalBytes += frameBytes.size

                val wasWaiting = vadDetector.getState() == VadDetector.VadState.WAITING
                val isSpeaking = vadDetector.isSpeaking(buffer, readSize)

                // 还没进入 SPEAKING 时，长时间没有有效语音 → 视为本轮无人说话
                if (!isSpeaking && wasWaiting && totalBytes >= maxTotalBytes) {
                    Log.w(TAG, "等待语音超时（${MAX_TOTAL_MS}ms 无有效语音）")
                    withContext(Dispatchers.Main) { onSilence() }
                    break
                }

                if (vadDetector.hasFinishedSpeaking()) {
                    _isRecording.value = false
                    frameOffsets.add(totalBytes)
                    val pcmData = extractSpeechSegment(
                        all = allAudioData.toByteArray(),
                        frameOffsets = frameOffsets,
                        frameSamples = frameSamples,
                        startFrame = vadDetector.speechStartFrame(),
                        endFrame = vadDetector.speechEndFrame()
                    )
                    withContext(Dispatchers.Main) { onSpeechEnd(pcmData) }
                    break
                }

                if (totalBytes >= maxTotalBytes) {
                    Log.w(TAG, "录音达到总时长上限，强制结束")
                    _isRecording.value = false
                    withContext(Dispatchers.Main) { onSilence() }
                    break
                }
            }

            // 退出循环后安全停止本协程的 AudioRecord
            recordMutex.withLock {
                try { myRecord?.stop() } catch (_: Exception) {}
                releaseAudioEffects()
            }
        }
    }

    /** 单帧时长（毫秒） */
    private fun frameMs(frameSamples: Int): Float =
        frameSamples * 1000f / SAMPLE_RATE

    /**
     * 按帧索引裁剪出语音片段。
     *
     * 用 [frameOffsets] 里记录的真实字节边界换算，而不是「帧号 × 固定帧长」：
     * 后者在 `read()` 返回短帧时会整体错位（见调用处的注释）。
     *
     * @param all 完整录音
     * @param frameOffsets 第 i 帧的起始字节偏移，末位是总长度
     * @param frameSamples 每帧采样点数（用于把预滚/尾音的毫秒换算成帧数）
     * @param startFrame VAD 给出的语音起始帧
     * @param endFrame VAD 给出的语音结束帧（不含尾部静默）
     */
    private fun extractSpeechSegment(
        all: ByteArray,
        frameOffsets: List<Int>,
        frameSamples: Int,
        startFrame: Int,
        endFrame: Int
    ): ByteArray {
        // 裁剪失败（索引异常 / 数据太短）时退回原始音频，宁可多送静音也不能送空
        if (startFrame < 0 || endFrame <= startFrame || all.isEmpty() ||
            frameOffsets.size < 2
        ) {
            Log.w(TAG, "语音片段索引异常(start=$startFrame, end=$endFrame)，退回完整音频")
            return all
        }

        val msPerFrame = frameMs(frameSamples)
        val preRollFrames = (PRE_ROLL_MS / msPerFrame).toInt().coerceAtLeast(1)
        val tailPadFrames = (TAIL_PAD_MS / msPerFrame).toInt().coerceAtLeast(0)

        // 帧号 → 真实字节偏移；索引一律夹到合法范围，避免越界
        fun offsetOf(frame: Int): Int =
            frameOffsets[frame.coerceIn(0, frameOffsets.size - 1)]

        val startByte = offsetOf(startFrame - preRollFrames)
        val endByte = offsetOf(endFrame + tailPadFrames).coerceAtMost(all.size)
        if (endByte <= startByte) {
            Log.w(TAG, "裁剪区间为空(start=$startByte, end=$endByte)，退回完整音频")
            return all
        }

        val segment = all.copyOfRange(startByte, endByte)
        Log.d(
            TAG,
            "语音片段裁剪: 完整=${all.size}字节(${all.size / 2 * 1000 / SAMPLE_RATE}ms) → " +
                "片段=${segment.size}字节(${segment.size / 2 * 1000 / SAMPLE_RATE}ms), " +
                "startFrame=$startFrame, endFrame=$endFrame"
        )
        return segment
    }

    /**
     * 优先 `VOICE_RECOGNITION`：该路径下系统关闭 AGC/降噪，最适合 ASR。
     * 设备不支持时回退 `MIC`。
     */
    private fun createAudioRecord(bufferBytes: Int): AudioRecord? {
        val source = MediaRecorder.AudioSource.VOICE_RECOGNITION
        val recorder = try {
            AudioRecord(source, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferBytes)
        } catch (e: Exception) {
            Log.w(TAG, "VOICE_RECOGNITION 初始化异常: ${e.message}")
            null
        }
        if (recorder != null && recorder.state == AudioRecord.STATE_INITIALIZED) {
            Log.d(TAG, "使用 VOICE_RECOGNITION 音源")
            return recorder
        }
        runCatching { recorder?.release() }
        Log.w(TAG, "VOICE_RECOGNITION 不可用，回退 MIC")
        return try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferBytes
            ).takeIf { it.state == AudioRecord.STATE_INITIALIZED }
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord 创建失败: ${e.message}")
            null
        }
    }

    /**
     * 关闭可能被系统默认打开的音效。
     *
     * `VOICE_RECOGNITION` 路径下这三者本该是关的，但个别 ROM 不遵守；
     * 而 AGC 一旦开启，句间停顿的底噪会被顶起来，固定阈值的 VAD 就会一直误判。
     * 拿不到 session id 或设备不支持时静默跳过。
     */
    private fun applyAudioEffects(sessionId: Int) {
        if (sessionId == 0) return
        runCatching {
            if (AutomaticGainControl.isAvailable()) {
                gainControl = AutomaticGainControl.create(sessionId)?.apply { enabled = false }
                Log.d(TAG, "已关闭 AGC")
            }
        }.onFailure { Log.d(TAG, "AGC 关闭失败: ${it.message}") }
        runCatching {
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(sessionId)?.apply { enabled = false }
                Log.d(TAG, "已关闭降噪")
            }
        }.onFailure { Log.d(TAG, "降噪关闭失败: ${it.message}") }
        runCatching {
            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = AcousticEchoCanceler.create(sessionId)?.apply { enabled = false }
                Log.d(TAG, "已关闭回声消除")
            }
        }.onFailure { Log.d(TAG, "回声消除关闭失败: ${it.message}") }
    }

    private fun releaseAudioEffects() {
        runCatching { noiseSuppressor?.release() }
        runCatching { gainControl?.release() }
        runCatching { echoCanceler?.release() }
        noiseSuppressor = null
        gainControl = null
        echoCanceler = null
    }

    fun stopRecording() {
        _isRecording.value = false
        recordingJob?.cancel()
    }

    fun release() {
        stopRecording()
        scope?.cancel()
        scope = null
        releaseAudioEffects()
        try {
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null
    }

    private fun calculateRMS(buffer: ShortArray, readSize: Int): Float {
        var sum = 0.0
        for (i in 0 until readSize) {
            sum += buffer[i] * buffer[i]
        }
        val rms = Math.sqrt(sum / readSize)
        return (rms / Short.MAX_VALUE).toFloat().coerceIn(0f, 1f)
    }

    private fun shortsToBytes(buffer: ShortArray, readSize: Int): ByteArray {
        val bytes = ByteArray(readSize * 2)
        for (i in 0 until readSize) {
            bytes[i * 2] = (buffer[i].toInt() and 0xFF).toByte()
            bytes[i * 2 + 1] = (buffer[i].toInt() shr 8 and 0xFF).toByte()
        }
        return bytes
    }
}
