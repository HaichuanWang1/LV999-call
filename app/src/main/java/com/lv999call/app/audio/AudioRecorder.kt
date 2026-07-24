package com.lv999call.app.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream

/**
 * 音频录制器
 * 使用AudioRecord进行PCM录音，支持VAD检测自动停止
 */
class AudioRecorder(private val context: Context) {

    companion object {
        private const val TAG = "AudioRecorder"
        const val SAMPLE_RATE = 16000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    // 每次录音使用独立的scope，避免release后复用失效
    private var scope: CoroutineScope? = null

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

        // 先停止之前的录音（防止重叠）
        stopRecording()

        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "无法获取合适的缓冲区大小")
            return
        }

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize * 2
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord初始化失败")
                release()
                return
            }

            audioRecord?.startRecording()
            _isRecording.value = true
            vadDetector.reset()

            // 每次录音创建新的scope（先取消旧的避免泄漏）
            scope?.cancel()
            val newScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            scope = newScope

            recordingJob = newScope.launch {
                // 使用 ByteArrayOutputStream 避免 O(n²) 复制
                val allAudioData = ByteArrayOutputStream()
                val buffer = ShortArray(bufferSize / 2)
                var silenceFrames = 0
                val maxSilenceFrames = (SAMPLE_RATE * 3.0 / (bufferSize / 2)).toInt()

                while (isActive && _isRecording.value) {
                    val readSize = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                    if (readSize > 0) {
                        val rms = calculateRMS(buffer, readSize)
                        _audioLevel.value = rms

                        // 直接写入ByteArrayOutputStream，避免逐字节复制
                        val byteBuffer = shortsToBytes(buffer, readSize)
                        allAudioData.write(byteBuffer, 0, byteBuffer.size)

                        val isSpeaking = vadDetector.isSpeaking(buffer, readSize)
                        if (!isSpeaking) {
                            silenceFrames++
                            if (silenceFrames > maxSilenceFrames) {
                                withContext(Dispatchers.Main) { onSilence() }
                                break
                            }
                        } else {
                            silenceFrames = 0
                        }

                        if (vadDetector.hasFinishedSpeaking()) {
                            _isRecording.value = false
                            val pcmData = allAudioData.toByteArray()
                            withContext(Dispatchers.Main) { onSpeechEnd(pcmData) }
                            break
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "录音启动失败: ${e.message}")
            release()
        }
    }

    fun stopRecording() {
        _isRecording.value = false
        recordingJob?.cancel()
        // 等待录音协程退出后再操作AudioRecord，避免并发
        try {
            kotlinx.coroutines.runBlocking { recordingJob?.join() }
        } catch (_: Exception) {}
        try {
            audioRecord?.stop()
        } catch (_: Exception) {}
    }

    fun release() {
        stopRecording()
        scope?.cancel()
        scope = null
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
