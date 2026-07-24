package com.ultraflow.silverwolf.data.remote

import com.google.gson.annotations.SerializedName
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Streaming
import retrofit2.http.Url

/**
 * TTS API 请求/响应模型
 *
 * MiMo-V2.5-TTS-VoiceClone 使用 chat completions 端点进行语音合成：
 * - 文本放在 assistant 消息的 content 中
 * - 参考音频以 base64 data URI 放在 audio.voice 中
 * - 响应为流式音频（SSE 或二进制流）
 */
object TtsModels {

    /** MiMo TTS 请求 — 复用 chat completions 格式 */
    data class TtsChatRequest(
        val model: String = "mimo-v2.5-tts-voiceclone",
        val messages: List<TtsMessage>,
        val audio: TtsAudioConfig,
        val stream: Boolean = true
    )

    data class TtsMessage(
        val role: String,       // "user" content 为空, "assistant" content 为要合成的文本
        val content: String
    )

    data class TtsAudioConfig(
        val format: String = "pcm16",
        val voice: String       // "data:{MIME};base64,{BASE64_AUDIO}"
    )

    /** 流式响应中的音频块 */
    data class TtsStreamResponse(
        val choices: List<TtsChoice>?
    )

    data class TtsChoice(
        val delta: TtsDelta?
    )

    data class TtsDelta(
        val content: String?,
        @SerializedName("audio")
        val audioData: TtsAudioData?
    )

    data class TtsAudioData(
        val data: String?       // base64 encoded audio chunk
    )
}

/** TTS API 服务 — MiMo chat completions 格式 */
interface TtsApiService {

    @POST
    @Streaming
    suspend fun synthesizeStream(
        @Url url: String,
        @Header("api-key") apiKey: String,
        @Body request: TtsModels.TtsChatRequest
    ): ResponseBody

    companion object {
        fun buildUrl(baseUrl: String): String {
            val base = baseUrl.trimEnd('/')
            return if (base.endsWith("/v1/chat/completions")) {
                base
            } else if (base.endsWith("/v1")) {
                "$base/chat/completions"
            } else {
                "$base/v1/chat/completions"
            }
        }
    }
}
