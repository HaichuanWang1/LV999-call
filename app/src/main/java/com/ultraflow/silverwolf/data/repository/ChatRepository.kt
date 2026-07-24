package com.ultraflow.silverwolf.data.repository

import com.google.gson.Gson
import com.ultraflow.silverwolf.data.remote.AsrApiService
import com.ultraflow.silverwolf.data.remote.LlmApiService
import com.ultraflow.silverwolf.data.remote.LlmModels
import com.ultraflow.silverwolf.data.remote.ModelsApiService
import com.ultraflow.silverwolf.data.remote.ModelsResponse
import com.ultraflow.silverwolf.data.remote.TtsApiService
import com.ultraflow.silverwolf.data.remote.TtsModels
import com.ultraflow.silverwolf.domain.model.ApiConfig
import com.ultraflow.silverwolf.domain.model.ChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader
import java.io.InputStream

/** 对话仓库 - 处理LLM/ASR/TTS的网络调用 */
class ChatRepository(
    private val llmApi: LlmApiService,
    private val asrApi: AsrApiService,
    private val ttsApi: TtsApiService,
    private val modelsApi: ModelsApiService
) {
    private val gson = Gson()

    companion object {
        // 匹配emoji和特殊符号（TTS无法处理的）— 逐字符列出，避免代理对正则在Android上崩溃
        private val REGEX_EMOJI = Regex(
            "[☀☁☂☃☄★☇☈☉☊☋☌☍☎☏☐☑☒☓☔☕☖☘☙☚☛☜☝☞☟☠☡☢☣☤☥☦☧☨☩☪☫☬☭☮☯☰☱" +
            "☲☳☴☵☶☷☸☹☺☻☼☽☾☿♀♁♂♃♄♅♆♇♈♉♊♋♌♍♎♏♐♑♒♓♔♕♖♗♘♙♚♛♜♝♞♟" +
            "♠♡♢♣♤♥♦♧♨♩♪♫♬♭♮♯♰♱♲♻♾♿⚀⚁⚂⚃⚄⚅⚆⚈⚉⚐⚑⚒⚓⚔⚕⚖⚗⚘⚙⚚⚛⚜⚝⚞⚟" +
            "⚠⚡⚢⚣⚤⚥⚦⚧⚨⚩⚪⚫⚬⚭⚮⚯⚰⚱⚲⚳⚴⚵⚶⚷⚸⚹⚺⚻⚼⚽⚾⚿⛀⛁⛂⛃⛏⛍⛎⛏⛐⛑⛒⛓⛔⛕⛖⛗⛘⛙⛚⛛⛜⛝⛞⛟⛠⛡⛢⛣⛤⛥⛦⛧⛨⛩⛪⛫⛬⛭⛮⛯" +
            "⛰⛱⛲⛳⛴⛵⛶⛷⛸⛹⛺⛻⛼⛽⛾⛿✀✁✂✃✄✅✆✇✈✉✊✋✌✍✎✏✐✑✒✓✔✕✖✗✘✙✚✛✜✝✞✟✠✡✢✣✤✥✦✧✨✩✪✫✬✭✮✯✰✱✲✳✴✵✶✷✸✹✺✻✼" +
            "✽✾✿❀❁❂❃❄❅❆❇❈❉❊❋❌❍❎❏❐❑❒❓❔❕❖❗❘❙❚❛❜❝❞❟❠❡❢❣❤❥❦❧❨❩❪❫❬❭❮❯❰❱❲❳❴❵❶❷❸❹❺❻❼❽❾❿➀➁➂➃➄➅➆➇➈➉" +
            "➊➋➌➍➎➏➐➑➒➓➔➕➖➗➘➙➚➛➜➝➞➟➠➡➢➣➤➥➦➧➨➩➪➫➬➭➮➯"
        )
        // 匹配LLM thinking标签
        private val REGEX_THINKING = Regex("<think>[\\s\\S]*?</think>|<thinking>[\\s\\S]*?</thinking>")
        // 匹配语气/风格标注括号: (温柔), （慵懒）, [笑声] 等
        private val REGEX_STYLE_ANNOTATION = Regex("[（(][^）)]{1,10}[）)]|\\[[^\\]]{1,10}]")
    }

    /**
     * 获取可用模型列表
     * @param baseUrl API基础URL（LLM或TTS）
     * @param apiKey API密钥
     */
    suspend fun fetchModels(baseUrl: String, apiKey: String): List<ModelsResponse.ModelItem> {
        return try {
            val url = ModelsApiService.buildUrl(baseUrl)
            android.util.Log.d("ChatRepo", "获取模型: $url")
            val response = modelsApi.listModels(url, apiKey, "Bearer $apiKey")
            android.util.Log.d("ChatRepo", "模型响应: ${response.data.size}个模型")
            response.data.forEach {
                android.util.Log.d("ChatRepo", "  模型: ${it.id}, ctx=${it.context_length}")
            }
            response.data
        } catch (e: Exception) {
            android.util.Log.e("ChatRepo", "获取模型失败: ${e.message}")
            // 返回MiMo全系列模型作为备选
            listOf(
                ModelsResponse.ModelItem(id = "mimo-v2.5"),
                ModelsResponse.ModelItem(id = "mimo-v2.5-tts-voiceclone"),
                ModelsResponse.ModelItem(id = "mimo-v2.5-tts"),
                ModelsResponse.ModelItem(id = "mimo-v2.5-tts-voicedesign")
            )
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
            stream = true
        )

        val url = LlmApiService.buildFullUrl(config.llmBaseUrl)
        val auth = "Bearer ${config.llmApiKey}"

        try {
            val responseBody = llmApi.chatCompletionStream(url, auth, request)
            try {
                val stream = responseBody.byteStream()
                parseSSEStream(stream).collect { emit(it) }
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
     * 调用TTS合成语音，返回PCM音频流
     * MiMo-V2.5-TTS: 通过 chat completions 端点，文本放 assistant 消息，参考音频放 audio.voice
     * @param refAudioBase64 模式对应的参考音频base64（为空则使用默认音色）
     * @param refAudioMime 参考音频MIME类型
     */
    suspend fun synthesizeSpeech(
        config: ApiConfig,
        text: String,
        refAudioBase64: String = config.ttsReferenceAudioBase64,
        refAudioMime: String = config.ttsReferenceAudioMime
    ): InputStream? {
        // 去除emoji、特殊符号、LLM推理标签、语气标注，TTS无法处理会导致乱音/卡顿
        val cleanText = text
            .replace(REGEX_THINKING, "")
            .replace(REGEX_STYLE_ANNOTATION, "")
            .replace(REGEX_EMOJI, "")
            .replace("~", "，")
            .trim()
        if (cleanText.isBlank()) return null

        return try {
            val voiceUri = if (refAudioBase64.isNotEmpty()) {
                // MiMo限制: base64不超过10MB
                if (refAudioBase64.length > 10 * 1024 * 1024) {
                    android.util.Log.e("ChatRepo", "参考音频base64超限: ${refAudioBase64.length / 1024 / 1024}MB > 10MB, 请重新选择较短的音频")
                    return null
                }
                "data:$refAudioMime;base64,$refAudioBase64"
            } else {
                // voiceclone模型必须有参考音频，无音频则跳过TTS
                android.util.Log.w("ChatRepo", "无参考音频，voiceclone模型无法工作，跳过TTS")
                return null
            }

            val request = TtsModels.TtsChatRequest(
                model = config.ttsModel.ifEmpty { "mimo-v2.5-tts-voiceclone" },
                messages = listOf(
                    TtsModels.TtsMessage(role = "user", content = ""),
                    TtsModels.TtsMessage(role = "assistant", content = cleanText)
                ),
                audio = TtsModels.TtsAudioConfig(
                    format = "pcm16",  // 流式必须用pcm16
                    voice = voiceUri
                ),
                stream = true
            )

            val url = TtsApiService.buildUrl(config.ttsBaseUrl)
            val voicePreview = voiceUri.take(60)
            android.util.Log.d("ChatRepo", "TTS: url=$url, model=${request.model}, text=${text.take(20)}..., voice=$voicePreview..., voiceLen=${voiceUri.length}")

            val responseBody = ttsApi.synthesizeStream(url, config.ttsApiKey, request)
            try {
                parseTtsAudioStream(responseBody.byteStream())
            } finally {
                responseBody.close()
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 解析MiMo TTS的SSE流式响应，提取base64音频块并解码为字节流
     */
    private fun parseTtsAudioStream(inputStream: InputStream): InputStream {
        val audioOutput = java.io.ByteArrayOutputStream()
        val reader = inputStream.bufferedReader()
        var line: String?
        var lineCount = 0
        var chunkCount = 0

        try {
            while (reader.readLine().also { line = it } != null) {
                val currentLine = line ?: continue
                lineCount++

                if (currentLine.startsWith("data: ")) {
                    val data = currentLine.removePrefix("data: ").trim()
                    if (data == "[DONE]") break

                    try {
                        val chunk = gson.fromJson(data, TtsModels.TtsStreamResponse::class.java)
                        val audioData = chunk.choices?.firstOrNull()?.delta?.audioData?.data
                        if (!audioData.isNullOrEmpty()) {
                            val decoded = android.util.Base64.decode(audioData, android.util.Base64.DEFAULT)
                            audioOutput.write(decoded)
                            chunkCount++
                        }
                    } catch (_: Exception) {}
                }
            }
        } finally {
            reader.close()
            inputStream.close()
        }

        val result = audioOutput.toByteArray()
        android.util.Log.d("ChatRepo", "TTS解析: 行=$lineCount, 块=$chunkCount, 字节=${result.size}")
        return result.inputStream()
    }

    /**
     * 解析SSE流式响应，提取content文本
     */
    private fun parseSSEStream(inputStream: InputStream): Flow<String> = flow {
        val reader = inputStream.bufferedReader()
        var line: String?

        try {
            while (reader.readLine().also { line = it } != null) {
                val currentLine = line ?: continue

                if (currentLine.startsWith("data: ")) {
                    val data = currentLine.removePrefix("data: ").trim()

                    if (data == "[DONE]") {
                        break
                    }

                    try {
                        val chunk = gson.fromJson(data, LlmModels.ChatResponse::class.java)
                        val content = chunk.choices.firstOrNull()?.delta?.content
                        if (!content.isNullOrEmpty()) {
                            emit(content)
                        }
                    } catch (_: Exception) {
                        // 跳过解析错误的行
                    }
                }
            }
        } finally {
            reader.close()
            inputStream.close()
        }
    }.flowOn(Dispatchers.IO)
}
