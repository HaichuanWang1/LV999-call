package com.lv999call.app.audio

import android.content.Context
import android.util.Log
import com.lv999call.app.data.remote.AsrApiService
import com.lv999call.app.data.remote.NetworkClient
import com.lv999call.app.domain.model.ApiConfig
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

        /** Vosk 分块大小：4096 字节 = 2048 采样点 = 128ms @16kHz */
        private const val VOSK_CHUNK_BYTES = 4096
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
     * 使用 Vosk 进行离线语音识别。
     *
     * ## 为什么必须「边送边收」
     *
     * Vosk 的 `AcceptWaveform` 在检测到端点（一段话结束的停顿）时返回 true，并把内部状态
     * 置为 `RECOGNIZER_ENDPOINT`；而 `FinalResult()` 的第一行是
     * `if (state_ != RECOGNIZER_RUNNING) return StoreEmptyReturn();`
     * —— 也就是说**音频中间只要出现过一次端点，后面再调 finalResult 只会拿到空串**。
     *
     * 旧实现全程只调 `acceptWaveForm`、最后才取一次 `finalResult`，等于「用户说话中间有停顿
     * 就整段丢字」，这正是离线模式时准时不准的根因。官方示例
     * （vosk-api/python/example/test_simple.py）的标准写法就是：
     * `if rec.AcceptWaveform(data): print(rec.Result())`，边送边把已定稿的段取走。
     *
     * 另外中文模型的词表是**单字**（graph 目录下的 fst 里全是长度 1 的符号），
     * 结果形如「你 好 世 界」，需要去掉汉字之间的空格再交给 LLM。
     */
    private suspend fun transcribeVosk(pcmData: ByteArray): String {
        return withContext(Dispatchers.IO) {
            val recognizer = voskRecognizer
            if (recognizer == null) {
                Log.e(TAG, "Vosk Recognizer未初始化")
                return@withContext ""
            }
            if (pcmData.isEmpty()) return@withContext ""

            try {
                // 重置Recognizer，避免连续识别结果累积
                recognizer.reset()

                val segments = StringBuilder()
                var offset = 0
                while (offset < pcmData.size) {
                    val end = minOf(offset + VOSK_CHUNK_BYTES, pcmData.size)
                    val chunk = pcmData.copyOfRange(offset, end)
                    // 返回 true = 检测到端点，这一段已经定稿，必须立刻取走，
                    // 否则会被后续的 finalResult 整段丢弃
                    if (recognizer.acceptWaveForm(chunk, chunk.size)) {
                        val seg = parseVoskResult(recognizer.result)
                        if (seg.isNotEmpty()) {
                            if (segments.isNotEmpty()) segments.append(' ')
                            segments.append(seg)
                        }
                    }
                    offset = end
                }

                // 收尾：把最后一段（没有端点、缓冲区里残留的）取出来
                val tail = parseVoskResult(recognizer.finalResult)
                if (tail.isNotEmpty()) {
                    if (segments.isNotEmpty()) segments.append(' ')
                    segments.append(tail)
                }

                val text = normalizeVoskText(segments.toString())
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

    /**
     * 归一化 Vosk 输出。
     *
     * 中文模型的词表是单字，`text` 里汉字被空格分开（「你 好 世 界」）。
     * 直接喂给 LLM 既浪费 token 也影响语义切分，这里去掉**两个汉字之间**的空格；
     * 汉字与英文/数字之间的空格保留（「我 用 API」→「我用 API」）。
     */
    internal fun normalizeVoskText(raw: String): String {
        val s = raw.trim()
        if (s.isEmpty()) return ""
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == ' ') {
                val prev = sb.lastOrNull()
                val next = s.getOrNull(i + 1)
                if (prev != null && next != null && isCjk(prev) && isCjk(next)) {
                    i++      // 汉字之间的空格：丢掉
                    continue
                }
            }
            sb.append(c)
            i++
        }
        // 折叠连续空格（Vosk 分段拼接处可能出现多个）
        return sb.toString().replace(Regex("\\s+"), " ").trim()
    }

    private fun isCjk(c: Char): Boolean {
        val code = c.code
        return code in 0x4E00..0x9FFF ||    // CJK 统一表意文字
            code in 0x3400..0x4DBF ||       // 扩展 A
            code in 0xF900..0xFAFF          // 兼容表意文字
    }

    // ===== HTTP API 识别 =====

    /**
     * HTTP ASR：失败重试一次，仍失败则在**离线模型已加载**时回退 Vosk。
     *
     * 不在失败时现场加载 Vosk 模型：首次要从 assets 解压 ~50MB，会把这一轮通话卡死几十秒，
     * 比直接告诉用户「没听清」更糟。
     */
    private suspend fun transcribeHttp(config: ApiConfig, pcmData: ByteArray): String {
        if (pcmData.isEmpty()) return ""

        val wavData = pcmToWav(pcmData)
        var text = postTranscription(config, wavData)
        if (text.isBlank()) {
            Log.w(TAG, "HTTP ASR 首次为空/失败，重试一次")
            text = postTranscription(config, wavData)
        }
        if (text.isBlank() && voskRecognizer != null) {
            val local = transcribeVosk(pcmData)
            if (local.isNotBlank()) {
                Log.w(TAG, "HTTP ASR 失败，回退 Vosk 成功: $local")
                return local
            }
        }
        return text
    }

    private suspend fun postTranscription(config: ApiConfig, wavData: ByteArray): String {
        return withContext(Dispatchers.IO) {
            try {
                val requestFile = wavData.toRequestBody("audio/wav".toMediaType())
                val audioPart = MultipartBody.Part.createFormData("file", "audio.wav", requestFile)
                // 语言码归一化为 ISO-639-1：Whisper 要的是 zh，而设置页默认值历史上写的是
                // zh-CN，直接下发会被部分服务端拒绝或静默忽略（退回自动检测）。
                // 归一化后为空（auto）则**不发**这个 part，走服务端自动检测 ——
                // 发一个空的 language 字段同样会被部分服务端当成非法参数。
                val normalizedLang = AsrApiService.normalizeLanguage(config.asrLanguage)
                val languageBody = normalizedLang.takeIf { it.isNotEmpty() }
                    ?.toRequestBody("text/plain".toMediaType())
                // model 是 OpenAI /v1/audio/transcriptions 的**必填**参数，缺失直接 400。
                // 留空时不发这个 part，交给服务端用默认模型（自建 whisper.cpp 等常不需要）。
                val modelBody = config.asrModel.trim().takeIf { it.isNotEmpty() }
                    ?.toRequestBody("text/plain".toMediaType())
                val url = AsrApiService.buildTranscribeUrl(config.asrBaseUrl)
                val auth = "Bearer ${config.asrApiKey}"
                val response = networkAsrApi.transcribe(
                    url, auth, audioPart, model = modelBody, language = languageBody
                )
                val text = response.text.ifEmpty { response.result?.firstOrNull()?.text ?: "" }.trim()
                Log.d(TAG, "HTTP ASR: '$text' (model=${config.asrModel}, lang=${config.asrLanguage})")
                text
            } catch (e: retrofit2.HttpException) {
                val body = runCatching { e.response()?.errorBody()?.string()?.take(300) }.getOrNull()
                Log.e(TAG, "HTTP ASR 失败: HTTP ${e.code()} $body")
                ""
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
