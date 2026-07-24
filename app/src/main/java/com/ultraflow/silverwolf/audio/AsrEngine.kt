package com.ultraflow.silverwolf.audio

import android.content.Context
import android.util.Log
import com.ultraflow.silverwolf.data.remote.AsrApiService
import com.ultraflow.silverwolf.data.remote.NetworkClient
import com.ultraflow.silverwolf.domain.model.ApiConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.vosk.Model
import org.vosk.Recognizer
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

/**
 * ASR引擎
 * 支持 HTTP API 和 Vosk 离线两种模式
 */
class AsrEngine(private val context: Context) {

    companion object {
        private const val TAG = "AsrEngine"
        private const val VOSK_SAMPLE_RATE = 16000f
    }

    private val networkAsrApi = NetworkClient.createService(AsrApiService::class.java)
    val modelManager = VoskModelManager(context)

    private var voskModel: Model? = null
    private var currentVoskModelId: String? = null

    /**
     * 初始化 Vosk 模型（优先从 assets 解压，已加载则复用）
     */
    suspend fun initVoskModel(modelId: String): Boolean {
        if (voskModel != null && currentVoskModelId == modelId) return true

        releaseVoskModel()

        // 确保模型文件就绪（assets 解压或已存在）
        val modelPath = modelManager.ensureModelReady(modelId)
        if (modelPath == null) {
            Log.e(TAG, "Vosk模型不可用: $modelId")
            return false
        }

        return try {
            voskModel = Model(modelPath)
            currentVoskModelId = modelId
            Log.d(TAG, "Vosk模型加载成功: $modelId")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Vosk模型加载失败: ${e.message}")
            voskModel = null
            currentVoskModelId = null
            false
        }
    }

    /**
     * 释放 Vosk 模型资源
     */
    fun releaseVoskModel() {
        voskModel?.close()
        voskModel = null
        currentVoskModelId = null
    }

    /**
     * 语音识别入口 — 根据配置自动选择 HTTP 或 Vosk
     */
    suspend fun transcribe(config: ApiConfig, pcmData: ByteArray): String {
        return if (config.asrProvider == "vosk") {
            transcribeVosk(config, pcmData)
        } else {
            transcribeHttp(config, pcmData)
        }
    }

    // ===== Vosk 离线识别 =====

    /**
     * 使用 Vosk 进行离线语音识别
     */
    private suspend fun transcribeVosk(config: ApiConfig, pcmData: ByteArray): String {
        return withContext(Dispatchers.IO) {
            val model = voskModel
            if (model == null) {
                Log.e(TAG, "Vosk模型未初始化")
                return@withContext ""
            }

            try {
                val recognizer = Recognizer(model, VOSK_SAMPLE_RATE)
                recognizer.setMaxAlternatives(0)
                recognizer.setWords(true)
                recognizer.setPartialWords(false)

                // 分块送入音频数据
                val chunkSize = 4096
                var offset = 0
                while (offset < pcmData.size) {
                    val end = minOf(offset + chunkSize, pcmData.size)
                    val chunk = pcmData.copyOfRange(offset, end)
                    recognizer.acceptWaveForm(chunk, chunk.size)
                    offset = end
                }

                // 获取最终结果
                val finalResult = recognizer.finalResult
                recognizer.close()

                // 解析JSON结果提取文本
                parseVoskResult(finalResult)
            } catch (e: Exception) {
                Log.e(TAG, "Vosk识别失败: ${e.message}")
                ""
            }
        }
    }

    /**
     * 解析 Vosk JSON 结果
     * 格式: {"text": "识别结果文本"}
     */
    private fun parseVoskResult(json: String): String {
        return try {
            val obj = org.json.JSONObject(json)
            obj.optString("text", "").trim()
        } catch (e: Exception) {
            Log.e(TAG, "Vosk结果解析失败: $json")
            ""
        }
    }

    // ===== HTTP API 识别 =====

    /**
     * 通过 HTTP API 进行语音识别
     */
    private suspend fun transcribeHttp(config: ApiConfig, pcmData: ByteArray): String {
        return withContext(Dispatchers.IO) {
            try {
                val wavData = pcmToWav(pcmData)

                val requestFile = wavData.toRequestBody("audio/wav".toMediaType())
                val audioPart = MultipartBody.Part.createFormData("file", "audio.wav", requestFile)
                val languageBody = config.asrLanguage.toRequestBody("text/plain".toMediaType())

                val url = AsrApiService.buildTranscribeUrl(config.asrBaseUrl)
                val auth = "Bearer ${config.asrApiKey}"

                val response = networkAsrApi.transcribe(url, auth, audioPart, language = languageBody)
                val text = response.text.ifEmpty {
                    response.result?.firstOrNull()?.text ?: ""
                }

                Log.d(TAG, "HTTP ASR识别结果: $text")
                text
            } catch (e: Exception) {
                Log.e(TAG, "HTTP ASR识别失败: ${e.message}")
                ""
            }
        }
    }

    // ===== WAV 转换工具 =====

    fun pcmToWav(pcmData: ByteArray, sampleRate: Int = 16000, channels: Int = 1): ByteArray {
        val totalDataLen = pcmData.size + 36
        val byteRate = sampleRate * channels * 2

        val header = ByteArray(44)
        header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
        writeInt(header, 4, totalDataLen)
        header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
        writeInt(header, 16, 16)
        writeShort(header, 20, 1)
        writeShort(header, 22, channels)
        writeInt(header, 24, sampleRate)
        writeInt(header, 28, byteRate)
        writeShort(header, 32, channels * 2)
        writeShort(header, 34, 16)
        header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
        writeInt(header, 40, pcmData.size)

        return header + pcmData
    }

    fun release() {
        releaseVoskModel()
    }
}

private fun writeInt(header: ByteArray, offset: Int, value: Int) {
    header[offset] = (value and 0xFF).toByte()
    header[offset + 1] = (value shr 8 and 0xFF).toByte()
    header[offset + 2] = (value shr 16 and 0xFF).toByte()
    header[offset + 3] = (value shr 24 and 0xFF).toByte()
}

private fun writeShort(header: ByteArray, offset: Int, value: Int) {
    header[offset] = (value and 0xFF).toByte()
    header[offset + 1] = (value shr 8 and 0xFF).toByte()
}
