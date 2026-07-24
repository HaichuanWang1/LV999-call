package com.ultraflow.silverwolf.audio

import android.content.Context
import android.util.Log
import com.ultraflow.silverwolf.data.remote.AsrApiService
import com.ultraflow.silverwolf.data.remote.NetworkClient
import com.ultraflow.silverwolf.domain.model.ApiConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.vosk.Model
import org.vosk.Recognizer

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
    private var voskRecognizer: Recognizer? = null
    private var currentVoskModelId: String? = null

    /**
     * 初始化 Vosk 模型和 Recognizer（复用，不每次重建）
     */
    suspend fun initVoskModel(modelId: String): Boolean {
        if (voskModel != null && currentVoskModelId == modelId) return true

        releaseVoskModel()

        val modelPath = modelManager.ensureModelReady(modelId)
        if (modelPath == null) {
            Log.e(TAG, "Vosk模型不可用: $modelId")
            return false
        }

        return try {
            voskModel = Model(modelPath)
            voskRecognizer = Recognizer(voskModel!!, VOSK_SAMPLE_RATE).apply {
                setMaxAlternatives(0)
                setWords(true)
                setPartialWords(false)
            }
            currentVoskModelId = modelId
            Log.d(TAG, "Vosk模型+Recognizer加载成功: $modelId")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Vosk模型加载失败: ${e.message}")
            voskModel = null
            voskRecognizer = null
            currentVoskModelId = null
            false
        }
    }

    fun releaseVoskModel() {
        voskRecognizer?.close()
        voskRecognizer = null
        voskModel?.close()
        voskModel = null
        currentVoskModelId = null
    }

    /**
     * 语音识别入口 — 根据配置自动选择 HTTP 或 Vosk
     */
    suspend fun transcribe(config: ApiConfig, pcmData: ByteArray): String {
        return if (config.asrProvider == "vosk") {
            transcribeVosk(pcmData)
        } else {
            transcribeHttp(config, pcmData)
        }
    }

    // ===== Vosk 离线识别 =====

    /**
     * 使用 Vosk 进行离线语音识别
     * 复用 Recognizer，送入音频后获取结果，然后重置
     */
    private suspend fun transcribeVosk(pcmData: ByteArray): String {
        return withContext(Dispatchers.IO) {
            val recognizer = voskRecognizer
            if (recognizer == null) {
                Log.e(TAG, "Vosk Recognizer未初始化")
                return@withContext ""
            }

            try {
                // 分块送入音频数据（每块4096字节 = 2048 samples）
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
                Log.d(TAG, "Vosk原始结果: $finalResult")

                // 解析结果
                val text = parseVoskResult(finalResult)
                Log.d(TAG, "Vosk识别: '$text' (输入${pcmData.size}字节)")

                text
            } catch (e: Exception) {
                Log.e(TAG, "Vosk识别异常: ${e.message}")
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
            Log.e(TAG, "Vosk结果解析失败: $json, err=${e.message}")
            ""
        }
    }

    // ===== HTTP API 识别 =====

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
                val text = response.text.ifEmpty { response.result?.firstOrNull()?.text ?: "" }
                Log.d(TAG, "HTTP ASR: $text")
                text
            } catch (e: Exception) {
                Log.e(TAG, "HTTP ASR失败: ${e.message}")
                ""
            }
        }
    }

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
        writeShort(header, 20, 1); writeShort(header, 22, channels)
        writeInt(header, 24, sampleRate); writeInt(header, 28, byteRate)
        writeShort(header, 32, channels * 2); writeShort(header, 34, 16)
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
