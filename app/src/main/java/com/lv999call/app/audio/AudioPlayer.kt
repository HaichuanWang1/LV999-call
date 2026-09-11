package com.lv999call.app.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.InputStream

/**
 * 流式音频播放器
 * 使用AudioTrack进行PCM流式播放，支持边接收边播放
 */
class AudioPlayer {

    companion object {
        private const val TAG = "AudioPlayer"
        private const val SAMPLE_RATE = 24000  // TTS通常输出24kHz
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private var audioTrack: AudioTrack? = null

    /** 当前播放任务；[awaitPlaybackEnd] 会跨线程读它，必须 volatile */
    @Volatile
    private var playbackJob: Job? = null

    /** 当前正在消费的音频流；[stopCurrentPlayback] 需要跨线程 close 它来解除 read 阻塞 */
    @Volatile
    private var currentStream: InputStream? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val trackLock = Any() // 保护audioTrack的并发访问

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    /**
     * 等当前这次播放结束。
     *
     * 比起「轮询 isPlaying」：TTS 是边收边播的，如果服务端一块音频都没下发，
     * isPlaying 会在一帧内 true→false，轮询很容易整个错过、白等一个超时。
     * 直接 join 播放任务则不会漏掉这种「没出声就结束」的失败路径。
     *
     * @return false 表示超时（被打断不算，被打断时任务同样会结束）
     */
    suspend fun awaitPlaybackEnd(timeoutMs: Long): Boolean {
        val job = playbackJob ?: return true
        if (job.isCompleted) return true
        return withTimeoutOrNull(timeoutMs) {
            job.join()
            true
        } ?: false
    }

    /**
     * 当前播放音量（16bit PCM 的 RMS，归一化到 0f~1f）
     * 供 Live2D 口型同步使用；停止播放时归零。
     */
    private val _amplitude = MutableStateFlow(0f)
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    private val _isInitialized = MutableStateFlow(false)

    init {
        initAudioTrack()
    }

    private fun initAudioTrack() {
        try {
            val bufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(CHANNEL_CONFIG)
                        .setEncoding(AUDIO_FORMAT)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            _isInitialized.value = true
        } catch (e: Exception) {
            Log.e(TAG, "AudioTrack初始化失败: ${e.message}")
        }
    }

    /**
     * 流式播放音频输入流
     * 自动检测WAV格式（跳过44字节头）或直接播放PCM
     */
    fun playStream(inputStream: InputStream) {
        if (!_isInitialized.value) {
            initAudioTrack()
        }

        stopCurrentPlayback()

        currentStream = inputStream

        // 用于量「开口前等了多久」：TTS 是边收边播的，第一个音频块到得越晚，
        // 用户听到的第一个字就越晚。这条链路出问题时先看这个数。
        val requestedAt = android.os.SystemClock.uptimeMillis()

        playbackJob = scope.launch {
            try {
                // 检测是否为WAV格式（前4字节为"RIFF"）
                val headerBuf = ByteArray(44)
                var headerRead = 0
                while (headerRead < 44) {
                    val n = inputStream.read(headerBuf, headerRead, 44 - headerRead)
                    if (n == -1) break
                    headerRead += n
                }

                val isWav = headerRead >= 4 &&
                    headerBuf[0] == 'R'.code.toByte() &&
                    headerBuf[1] == 'I'.code.toByte() &&
                    headerBuf[2] == 'F'.code.toByte() &&
                    headerBuf[3] == 'F'.code.toByte()

                var logSampleRate = SAMPLE_RATE
                var logChannels = 1

                if (isWav && headerRead == 44) {
                    // 从WAV头解析采样率和声道数
                    val sampleRate = (headerBuf[24].toInt() and 0xFF) or
                        ((headerBuf[25].toInt() and 0xFF) shl 8) or
                        ((headerBuf[26].toInt() and 0xFF) shl 16) or
                        ((headerBuf[27].toInt() and 0xFF) shl 24)
                    val channels = (headerBuf[22].toInt() and 0xFF) or
                        ((headerBuf[23].toInt() and 0xFF) shl 8)
                    logSampleRate = sampleRate
                    logChannels = channels

                    // 用WAV头中的参数重新配置AudioTrack
                    reinitWithParams(sampleRate, channels)
                }

                Log.d(
                    TAG,
                    "音频流就绪: wav=$isWav headerRead=$headerRead sr=$logSampleRate ch=$logChannels " +
                        "首块等待=${android.os.SystemClock.uptimeMillis() - requestedAt}ms"
                )

                if (audioTrack?.playState != AudioTrack.PLAYSTATE_PLAYING) {
                    audioTrack?.play()
                }
                _isPlaying.value = true

                // 非WAV格式：把已读取的字节作为音频数据写入（避免丢失开头）
                if (!isWav && headerRead > 0 && _isPlaying.value) {
                    audioTrack?.write(headerBuf, 0, headerRead)
                }

                val buffer = ByteArray(4096)
                var bytesRead: Int
                var firstFrameLogged = false

                while (isActive) {
                    bytesRead = inputStream.read(buffer)
                    if (bytesRead == -1) break

                    if (_isPlaying.value) {
                        audioTrack?.write(buffer, 0, bytesRead)
                        _amplitude.value = calculateRms16(buffer, bytesRead)
                        if (!firstFrameLogged) {
                            firstFrameLogged = true
                            Log.d(
                                TAG,
                                "开始出声: 自playStream=${android.os.SystemClock.uptimeMillis() - requestedAt}ms"
                            )
                        }
                    } else {
                        break
                    }
                }

                delay(100)
            } catch (e: Exception) {
                Log.e(TAG, "播放错误: ${e.message}")
            } finally {
                try { inputStream.close() } catch (_: Exception) {}
                if (currentStream === inputStream) currentStream = null
                _isPlaying.value = false
                _amplitude.value = 0f
            }
        }
    }

    private fun reinitWithParams(sampleRate: Int, channels: Int) {
        synchronized(trackLock) {
            try {
                audioTrack?.stop()
                audioTrack?.release()
            val channelConfig = if (channels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
            val bufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT)
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelConfig)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            } catch (e: Exception) {
                Log.e(TAG, "AudioTrack重初始化失败: ${e.message}")
            }
        }
    }

    /**
     * 播放PCM字节数据
     */
    fun playPcmData(pcmData: ByteArray) {
        if (!_isInitialized.value) {
            initAudioTrack()
        }

        stopCurrentPlayback()

        playbackJob = scope.launch {
            try {
                audioTrack?.play()
                _isPlaying.value = true
                audioTrack?.write(pcmData, 0, pcmData.size)
                _amplitude.value = calculateRms16(pcmData, pcmData.size)
                delay(100)
            } catch (e: Exception) {
                Log.e(TAG, "播放错误: ${e.message}")
            } finally {
                _isPlaying.value = false
            }
        }
    }

    /**
     * 暂停播放（可打断）
     */
    fun pause() {
        _isPlaying.value = false
        _amplitude.value = 0f
        try {
            audioTrack?.pause()
            audioTrack?.flush()
        } catch (_: Exception) {}
    }

    /**
     * 停止当前播放
     */
    fun stopCurrentPlayback() {
        _isPlaying.value = false
        _amplitude.value = 0f
        playbackJob?.cancel()
        // 关掉输入流：播放线程可能正阻塞在 read 上等下一块音频，
        // 取消协程叫不醒阻塞中的读，只有 close 才能让它立刻收摊（AudioPipe 会唤醒读写两端）。
        runCatching { currentStream?.close() }
        currentStream = null
        synchronized(trackLock) {
            try {
                audioTrack?.stop()
                audioTrack?.flush()
            } catch (_: Exception) {}
        }
    }

    /**
     * 计算 16bit 小端 PCM 的 RMS 音量，归一化到 0f~1f
     * 与 AudioRecorder.calculateRMS 保持一致的量纲。
     */
    private fun calculateRms16(data: ByteArray, length: Int): Float {
        if (length < 2) return 0f
        var sum = 0.0
        var count = 0
        var i = 0
        while (i + 1 < length) {
            // 小端序：低字节在前
            val sample = (((data[i + 1].toInt() and 0xFF) shl 8) or (data[i].toInt() and 0xFF)).toShort().toInt()
            sum += sample.toDouble() * sample
            count++
            i += 2
        }
        if (count == 0) return 0f
        return (Math.sqrt(sum / count) / Short.MAX_VALUE).toFloat().coerceIn(0f, 1f)
    }

    /**
     * 释放资源
     */
    fun release() {
        stopCurrentPlayback()
        audioTrack?.release()
        audioTrack = null
        scope.cancel()
    }
}
