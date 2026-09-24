package com.lv999call.app.data.repository

import com.google.gson.Gson
import com.lv999call.app.audio.AudioPipe
import com.lv999call.app.data.remote.AsrApiService
import com.lv999call.app.data.remote.LlmApiService
import com.lv999call.app.data.remote.LlmModels
import com.lv999call.app.data.remote.ModelsApiService
import com.lv999call.app.data.remote.ModelsResponse
import com.lv999call.app.data.remote.TtsApiService
import com.lv999call.app.data.remote.TtsModels
import com.lv999call.app.domain.model.ApiConfig
import com.lv999call.app.domain.model.ChatMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import java.io.InputStream

/** 对话仓库 - 处理LLM/ASR/TTS的网络调用 */
class ChatRepository(
    private val llmApi: LlmApiService,
    private val asrApi: AsrApiService,
    private val ttsApi: TtsApiService,
    private val modelsApi: ModelsApiService
) {
    private val gson = Gson()

    /**
     * TTS 流式解码用的后台作用域。
     *
     * 为什么不用调用方的作用域：解码要「边收边喂」给播放器，生命周期跟着音频流走，
     * 而不是跟着某一次 processAudio 的调用走（后者会在主线程上等）。
     */
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        // 匹配LLM thinking标签
        private val REGEX_THINKING = Regex("<think>[\\s\\S]*?</think>|<thinking>[\\s\\S]*?</thinking>")
        // 匹配语气/风格标注括号: (温柔), （慵懒）, [笑声] 等
        private val REGEX_STYLE_ANNOTATION = Regex("[（(][^）)]{1,10}[）)]|\\[[^\\]]{1,10}]")

        /** 流式播放管道容量：写满即阻塞（背压），64KB ≈ 1.3s @24kHz/mono */
        private const val TTS_PIPE_BUFFER_BYTES = 64 * 1024
    }

    /** 获取可用模型列表 */
    suspend fun fetchModels(baseUrl: String, apiKey: String): Result<List<ModelsResponse.ModelItem>> {
        val url = ModelsApiService.buildUrl(baseUrl)
        android.util.Log.d("ChatRepo", "获取模型: $url")

        return withContext(Dispatchers.IO) {
            try {
                val request = okhttp3.Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("api-key", apiKey)
                    .get()
                    .build()

                val call = com.lv999call.app.data.remote.NetworkClient.okHttpClient.newCall(request)
                val response = try {
                    kotlinx.coroutines.suspendCancellableCoroutine<okhttp3.Response> { cont ->
                        cont.invokeOnCancellation { call.cancel() }
                        call.enqueue(object : okhttp3.Callback {
                            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                                cont.resumeWith(kotlin.Result.success(response))
                            }
                            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                                cont.resumeWith(kotlin.Result.failure(e))
                            }
                        })
                    }
                } catch (e: Exception) {
                    return@withContext Result.failure(Exception("网络请求失败: ${e.message}"))
                }

                response.use { resp ->
                    val body = resp.body?.string() ?: ""
                    android.util.Log.d("ChatRepo", "模型原始响应(${resp.code}): ${body.take(300)}")

                    if (!resp.isSuccessful) {
                        return@withContext Result.failure(Exception("请求失败 (${resp.code}): ${resp.message}"))
                    }

                    val jsonObj = org.json.JSONObject(body)
                    val dataArray = jsonObj.optJSONArray("data")
                    if (dataArray == null || dataArray.length() == 0) {
                        return@withContext Result.success(emptyList())
                    }

                    val models = mutableListOf<ModelsResponse.ModelItem>()
                    for (i in 0 until dataArray.length()) {
                        val item = dataArray.getJSONObject(i)
                        models.add(
                            ModelsResponse.ModelItem(
                                id = item.optString("id", ""),
                                `object` = item.optString("object", "model"),
                                owned_by = item.optString("owned_by", ""),
                                context_length = if (item.has("context_length")) item.optInt("context_length") else null
                            )
                        )
                    }
                    android.util.Log.d("ChatRepo", "解析到 ${models.size} 个模型")
                    Result.success(models)
                }
            } catch (e: Exception) {
                android.util.Log.e("ChatRepo", "获取模型异常: ${e.message}")
                Result.failure(Exception("获取模型失败: ${e.message}"))
            }
        }
    }

    /**
     * 流式调用LLM，逐字返回文本
     */
    fun streamChatCompletion(
        config: ApiConfig,
        systemPrompt: String?,
        history: List<ChatMessage>
    ): Flow<String> = flow {
        val messages = mutableListOf<LlmModels.Message>()

        // 添加系统提示词
        if (!systemPrompt.isNullOrBlank()) {
            messages.add(LlmModels.Message(role = "system", content = systemPrompt))
        }

        // 添加历史消息
        history.forEach { msg ->
            messages.add(LlmModels.Message(role = msg.role, content = msg.content))
        }

        val request = LlmModels.ChatRequest(
            model = config.llmModel,
            messages = messages,
            stream = true,
            temperature = config.llmTemperature,
            topP = config.llmTopP,
            maxTokens = config.llmMaxOutputTokens,
            // 思考开关只在确认支持的服务商上发送：未知字段会让严格校验的接口直接 400。
            // 关闭时不发、开启时发 {type:"enabled"}；字段为 null 时 Gson 不序列化。
            thinking = if (config.llmThinkingEnabled && LlmModels.supportsThinkingSwitch(config.llmBaseUrl)) {
                LlmModels.ThinkingConfig(type = "enabled")
            } else if (!config.llmThinkingEnabled && LlmModels.supportsThinkingSwitch(config.llmBaseUrl)) {
                LlmModels.ThinkingConfig(type = "disabled")
            } else {
                null
            }
        )

        val url = LlmApiService.buildFullUrl(config.llmBaseUrl)
        val auth = "Bearer ${config.llmApiKey}"

        try {
            val responseBody = llmApi.chatCompletionStream(url, auth, config.llmApiKey, request)
            try {
                val reader = responseBody.byteStream().bufferedReader()
                try {
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val currentLine = line ?: continue
                        if (currentLine.startsWith("data: ")) {
                            val data = currentLine.removePrefix("data: ").trim()
                            if (data == "[DONE]") break
                            try {
                                val chunk = gson.fromJson(data, LlmModels.ChatResponse::class.java)
                                val content = chunk.choices?.firstOrNull()?.delta?.content
                                if (!content.isNullOrEmpty()) emit(content)
                            } catch (e: Exception) {
                                android.util.Log.w("ChatRepo", "SSE解析跳过: ${e.message}")
                            }
                        }
                    }
                } finally {
                    reader.close()
                }
            } finally {
                responseBody.close()
            }
        } catch (e: Exception) {
            emit("[错误: ${e.message}]")
        }
    }.flowOn(Dispatchers.IO)

    /**
     * 调用ASR将音频转为文本
     */
    suspend fun transcribeAudio(
        config: ApiConfig,
        audioData: ByteArray,
        mimeType: String = "audio/wav"
    ): String {
        return try {
            val requestFile = audioData.toRequestBody(mimeType.toMediaTypeOrNull())
            val audioPart = MultipartBody.Part.createFormData("file", "audio.wav", requestFile)

            val languageBody = config.asrLanguage.toRequestBody("text/plain".toMediaTypeOrNull())

            val url = AsrApiService.buildTranscribeUrl(config.asrBaseUrl)
            val auth = "Bearer ${config.asrApiKey}"

            val response = asrApi.transcribe(url, auth, audioPart, language = languageBody)
            response.text.ifEmpty {
                response.result?.firstOrNull()?.text ?: ""
            }
        } catch (e: Exception) {
            "[ASR错误: ${e.message}]"
        }
    }

    /**
     * 调用TTS合成语音，返回**边收边播**的PCM音频流（调用方读完即 EOF）。
     *
     * 两个历史坑：
     * 1. 老实现把整段 SSE 音频解析进 ByteArrayOutputStream 才返回 —— 「开口前的静默期」
     *    等于整段合成时长，句子越长越明显；
     * 2. 这段解析是同步阻塞的，而调用链是 viewModelScope（主线程），
     *    于是整段合成期间主线程被占死，表现出来就是「开口前 UI 卡一下」，很像死锁。
     *
     * 现在：请求/响应头阶段在 IO 线程；音频交给后台协程边解码边写管道，播放器边读边放。
     * 管道写满自然阻塞形成背压，内存占用有上限（[TTS_PIPE_BUFFER_BYTES]），不会攒整段音频。
     *
     * MiMo-V2.5-TTS: 通过 chat completions 端点，文本放 assistant 消息，参考音频放 audio.voice
     * @param refAudioBase64 模式对应的参考音频base64（为空则使用默认音色）
     * @param refAudioMime 参考音频MIME类型
     */
    suspend fun synthesizeSpeech(
        config: ApiConfig,
        text: String,
        refAudioBase64: String = config.ttsReferenceAudioBase64,
        refAudioMime: String = config.ttsReferenceAudioMime,
        ttsPrompt: String = ""
    ): InputStream? {
        // 去除emoji、特殊符号、LLM推理标签、语气标注，TTS无法处理会导致乱音/卡顿
        val cleanText = text
            .replace(REGEX_THINKING, "")
            .replace(REGEX_STYLE_ANNOTATION, " ")
            .replace("~", "，")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (cleanText.isBlank()) return null

        // 请求体里塞着整段参考音频（~900KB base64），构建 + 网络 + 响应头都在 IO 线程做
        return withContext(Dispatchers.IO) {
            try {
                val voiceUri = if (refAudioBase64.isNotEmpty()) {
                    // MiMo限制: base64不超过10MB
                    if (refAudioBase64.length > 10 * 1024 * 1024) {
                        android.util.Log.e("ChatRepo", "参考音频base64超限: ${refAudioBase64.length / 1024 / 1024}MB > 10MB, 请重新选择较短的音频")
                        return@withContext null
                    }
                    "data:$refAudioMime;base64,$refAudioBase64"
                } else {
                    // voiceclone模型必须有参考音频，无音频则跳过TTS
                    android.util.Log.w("ChatRepo", "无参考音频，voiceclone模型无法工作，跳过TTS")
                    return@withContext null
                }

                val request = TtsModels.TtsChatRequest(
                    model = config.ttsModel.ifEmpty { "mimo-v2.5-tts-voiceclone" },
                    messages = listOf(
                        TtsModels.TtsMessage(role = "user", content = ""),
                        TtsModels.TtsMessage(role = "assistant", content = cleanText)
                    ),
                    audio = TtsModels.TtsAudioConfig(
                        format = "wav",  // 文档仅支持 wav/mp3；AudioPlayer 已自动检测 WAV 头并跳过
                        voice = voiceUri,
                        speed = config.ttsSpeed,
                        prompt = ttsPrompt.ifEmpty { null }
                    ),
                    stream = true
                )

                // TTS锁死MiMo端点，当前只支持MiMo-V2.5-TTS-VoiceClone格式
                val url = "https://api.xiaomimimo.com/v1/chat/completions"
                val voicePreview = voiceUri.take(60)
                android.util.Log.d("ChatRepo", "TTS: url=$url, model=${request.model}, text=${text.take(20)}..., voice=$voicePreview..., voiceLen=${voiceUri.length}")

                val response = ttsApi.synthesizeStream(url, "Bearer ${config.ttsApiKey}", config.ttsApiKey, request)
                if (!response.isSuccessful) {
                    val errorBody = response.errorBody()?.string()?.take(500) ?: "无响应体"
                    android.util.Log.e("ChatRepo", "TTS API错误: HTTP ${response.code()}, $errorBody")
                    return@withContext null
                }
                val responseBody = response.body() ?: run {
                    android.util.Log.e("ChatRepo", "TTS API返回空响应体")
                    return@withContext null
                }
                openPcmPipe(responseBody)
            } catch (e: Exception) {
                android.util.Log.e("ChatRepo", "TTS合成异常: ${e.message}", e)
                null
            }
        }
    }

    /**
     * 把 SSE 音频响应接到一根管道上：后台协程边解析边写，播放器边读边放。
     *
     * 消费端（AudioPlayer）提前关闭流时，写入会抛 IOException，属正常打断路径。
     * 连接由解码协程统一收尾，避免上游 socket 泄漏。
     */
    private fun openPcmPipe(responseBody: ResponseBody): InputStream {
        val pipe = AudioPipe(TTS_PIPE_BUFFER_BYTES)
        val source = responseBody.byteStream()

        ioScope.launch {
            val startedAt = android.os.SystemClock.uptimeMillis()
            try {
                val bytes = decodeTtsSseToPcm(source, pipe)
                android.util.Log.d(
                    "ChatRepo",
                    "TTS流式解码完成: 字节=$bytes, 耗时=${android.os.SystemClock.uptimeMillis() - startedAt}ms"
                )
            } catch (e: Exception) {
                // 挂断/打断时消费端先关流，这里必然报错，不当异常处理
                android.util.Log.d("ChatRepo", "TTS流式解码中断: ${e.message}")
            } finally {
                runCatching { pipe.closeWriter() }
                runCatching { responseBody.close() }
            }
        }
        return pipe
    }

    /**
     * 解析MiMo TTS的SSE流式响应，解码出的 PCM 立刻写进 [out]（不再整段缓存）。
     * 兼容两种格式: delta.audio 为字符串 或 delta.audio.data 为字符串
     * @return 写入的字节数
     */
    private fun decodeTtsSseToPcm(inputStream: InputStream, out: AudioPipe): Int {
        val reader = inputStream.bufferedReader()
        var line: String?
        var lineCount = 0
        var chunkCount = 0
        var byteCount = 0

        try {
            while (reader.readLine().also { line = it } != null) {
                val currentLine = line ?: continue
                lineCount++

                if (currentLine.startsWith("data: ")) {
                    val data = currentLine.removePrefix("data: ").trim()
                    if (data == "[DONE]") break

                    try {
                        // 使用 org.json 手动解析，兼容 audio 为字符串或对象两种格式
                        val jsonObj = org.json.JSONObject(data)
                        val choices = jsonObj.optJSONArray("choices")
                        if (choices == null || choices.length() == 0) continue
                        val delta = choices.getJSONObject(0).optJSONObject("delta") ?: continue

                        // 兼容: "audio": "base64string" 或 "audio": {"data": "base64string"}
                        val audio = delta.opt("audio")
                        val base64Data: String? = when (audio) {
                            is String -> audio
                            is org.json.JSONObject -> audio.optString("data", "").takeIf { it.isNotEmpty() }
                            else -> null
                        }

                        if (!base64Data.isNullOrEmpty()) {
                            val decoded = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)
                            // 管道满则阻塞在这里 → 背压，播放多快就解码多快
                            out.write(decoded)
                            chunkCount++
                            byteCount += decoded.size
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("ChatRepo", "TTS JSON解析失败: ${data.take(200)}, 原因: ${e.message}")
                    }
                }
            }
        } finally {
            reader.close()
            inputStream.close()
        }

        android.util.Log.d("ChatRepo", "TTS解析: 行=$lineCount, 块=$chunkCount, 字节=$byteCount")
        return byteCount
    }
}
