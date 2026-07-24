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
import java.util.concurrent.LinkedBlockingQueue

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
    private var playbackJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val audioQueue = LinkedBlockingQueue<ByteArray>()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

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

                if (isWav && headerRead == 44) {
                    // 从WAV头解析采样率和声道数
                    val sampleRate = (headerBuf[24].toInt() and 0xFF) or
                        ((headerBuf[25].toInt() and 0xFF) shl 8) or
                        ((headerBuf[26].toInt() and 0xFF) shl 16) or
                        ((headerBuf[27].toInt() and 0xFF) shl 24)
                    val channels = (headerBuf[22].toInt() and 0xFF) or
                        ((headerBuf[23].toInt() and 0xFF) shl 8)

                    // 用WAV头中的参数重新配置AudioTrack
                    reinitWithParams(sampleRate, channels)
                }

                if (audioTrack?.playState != AudioTrack.PLAYSTATE_PLAYING) {
                    audioTrack?.play()
                }
                _isPlaying.value = true

                val buffer = ByteArray(4096)
                var bytesRead: Int

                while (isActive) {
                    bytesRead = inputStream.read(buffer)
                    if (bytesRead == -1) break

                    if (_isPlaying.value) {
                        audioTrack?.write(buffer, 0, bytesRead)
                    } else {
                        break
                    }
                }

                delay(100)
            } catch (e: Exception) {
                Log.e(TAG, "播放错误: ${e.message}")
            } finally {
                try { inputStream.close() } catch (_: Exception) {}
                _isPlaying.value = false
            }
        }
    }

    private fun reinitWithParams(sampleRate: Int, channels: Int) {
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
        playbackJob?.cancel()
        audioQueue.clear()
        try {
            audioTrack?.stop()
            audioTrack?.flush()
        } catch (_: Exception) {}
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
