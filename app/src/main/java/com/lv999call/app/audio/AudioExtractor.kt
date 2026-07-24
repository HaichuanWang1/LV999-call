package com.lv999call.app.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

/**
 * 音频提取器
 * 从视频/音频文件中提取音频，降混为单声道，转WAV
 */
object AudioExtractor {

    private const val TAG = "AudioExtractor"
    private const val TIMEOUT_US = 10_000L
    private const val MAX_REF_DURATION_SEC = 15
    private const val MAX_BASE64_BYTES = 10 * 1024 * 1024

    /**
     * 从视频/音频文件中提取PCM数据
     * @return Triple(pcmData, sampleRate, channelCount) 或 null
     */
    suspend fun extractPcm(context: Context, uri: Uri): Result<Triple<ByteArray, Int, Int>> =
        withContext(Dispatchers.IO) {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(context, uri, null)

                val audioTrackIndex = findAudioTrack(extractor)
                    ?: return@withContext Result.failure(Exception("文件中未找到音频轨道"))

                extractor.selectTrack(audioTrackIndex)
                val format = extractor.getTrackFormat(audioTrackIndex)
                val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""

                Log.d(TAG, "源音频: $mime, 采样率=$sampleRate, 声道=$channelCount")

                val pcmData = decodeAudio(extractor, mime, format)

                extractor.release()
                Result.success(Triple(pcmData, sampleRate, channelCount))
            } catch (e: Exception) {
                Log.e(TAG, "音频提取失败: ${e.message}")
                extractor.release()
                Result.failure(e)
            }
        }

    /**
     * 从视频/音频文件提取并转为单声道WAV
     * 自动降混、裁剪、检查大小
     */
    suspend fun extractWav(context: Context, uri: Uri): Result<ByteArray> =
        withContext(Dispatchers.IO) {
            val result = extractPcm(context, uri)
            if (result.isFailure) return@withContext Result.failure(result.exceptionOrNull()!!)

            val (rawPcm, sampleRate, channelCount) = result.getOrThrow()

            // Step 1: 降混为单声道
            val monoPcm = if (channelCount > 1) {
                Log.d(TAG, "降混: ${channelCount}ch → 1ch")
                downmixToMono(rawPcm, channelCount)
            } else {
                rawPcm
            }

            // Step 2: 裁剪到最大时长（单声道16bit: 2 bytes/sample）
            val maxSamples = sampleRate * MAX_REF_DURATION_SEC
            val maxBytes = maxSamples * 2
            val trimmedPcm = if (monoPcm.size > maxBytes) {
                val origSec = monoPcm.size / (sampleRate * 2)
                Log.d(TAG, "裁剪: ${origSec}秒 → ${MAX_REF_DURATION_SEC}秒")
                monoPcm.copyOfRange(0, maxBytes)
            } else {
                monoPcm
            }

            val durationSec = trimmedPcm.size.toFloat() / (sampleRate * 2)
            Log.d(TAG, "最终音频: %.1f秒, 采样率=$sampleRate, 单声道, 字节=${trimmedPcm.size}".format(durationSec))

            // Step 3: 包装为WAV
            val wavData = pcmToWav(trimmedPcm, sampleRate, 1)

            // Step 4: 检查base64大小
            val estimatedBase64 = wavData.size * 4 / 3
            if (estimatedBase64 > MAX_BASE64_BYTES) {
                Log.e(TAG, "音频base64超限: ${estimatedBase64 / 1024 / 1024}MB > 10MB")
                return@withContext Result.failure(Exception("参考音频过大（>${MAX_BASE64_BYTES / 1024 / 1024}MB），请选择更短的音频"))
            }

            Result.success(wavData)
        }

    /**
     * 立体声/多声道PCM降混为单声道
     * 简单平均法：mono = (L + R) / 2
     */
    private fun downmixToMono(rawPcm: ByteArray, channelCount: Int): ByteArray {
        // 每个采样2字节(16bit)，每帧 = channelCount * 2字节
        val bytesPerFrame = channelCount * 2
        val totalFrames = rawPcm.size / bytesPerFrame
        val monoPcm = ByteArray(totalFrames * 2)

        for (frame in 0 until totalFrames) {
            var sum = 0L
            for (ch in 0 until channelCount) {
                val offset = frame * bytesPerFrame + ch * 2
                if (offset + 1 < rawPcm.size) {
                    val sample = (rawPcm[offset].toInt() and 0xFF) or
                        (rawPcm[offset + 1].toInt() shl 8)
                    sum += sample.toShort().toInt() // 保持有符号
                }
            }
            val monoSample = (sum / channelCount).toShort()
            val outOffset = frame * 2
            monoPcm[outOffset] = (monoSample.toInt() and 0xFF).toByte()
            monoPcm[outOffset + 1] = (monoSample.toInt() shr 8 and 0xFF).toByte()
        }

        return monoPcm
    }

    private fun findAudioTrack(extractor: MediaExtractor): Int? {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith("audio/")) return i
        }
        return null
    }

    private fun decodeAudio(extractor: MediaExtractor, mime: String, format: MediaFormat): ByteArray {
        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        val pcmOutput = ByteArrayOutputStream()
        val bufferInfo = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false

        while (!outputDone) {
            if (!inputDone) {
                val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                if (inputIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inputIndex)!!
                    val sampleSize = extractor.readSampleData(inputBuffer, 0)
                    if (sampleSize < 0) {
                        codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            if (outputIndex >= 0) {
                val outputBuffer = codec.getOutputBuffer(outputIndex)!!
                val chunk = ByteArray(bufferInfo.size)
                outputBuffer.get(chunk)
                pcmOutput.write(chunk)
                codec.releaseOutputBuffer(outputIndex, false)
                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
            }
        }

        codec.stop()
        codec.release()
        return pcmOutput.toByteArray()
    }

    fun pcmToWav(pcmData: ByteArray, sampleRate: Int, channels: Int): ByteArray {
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val dataSize = pcmData.size
        val totalSize = 36 + dataSize

        val header = ByteArray(44)
        header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
        writeInt(header, 4, totalSize)
        header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
        writeInt(header, 16, 16)
        writeShort(header, 20, 1)
        writeShort(header, 22, channels)
        writeInt(header, 24, sampleRate)
        writeInt(header, 28, byteRate)
        writeShort(header, 32, blockAlign)
        writeShort(header, 34, bitsPerSample)
        header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
        writeInt(header, 40, dataSize)

        return header + pcmData
    }

    private fun writeInt(arr: ByteArray, offset: Int, value: Int) {
        arr[offset] = (value and 0xFF).toByte()
        arr[offset + 1] = (value shr 8 and 0xFF).toByte()
        arr[offset + 2] = (value shr 16 and 0xFF).toByte()
        arr[offset + 3] = (value shr 24 and 0xFF).toByte()
    }

    private fun writeShort(arr: ByteArray, offset: Int, value: Int) {
        arr[offset] = (value and 0xFF).toByte()
        arr[offset + 1] = (value shr 8 and 0xFF).toByte()
    }
}
