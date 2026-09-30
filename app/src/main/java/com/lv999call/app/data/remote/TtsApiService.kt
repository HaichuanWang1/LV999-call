package com.lv999call.app.data.remote

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
        // "user" 的 content 放**自然语言风格指令**（可为空串）, "assistant" 放要合成的文本
        val role: String,
        val content: String
    )

    /**
     * audio 对象只认三个字段：`format` / `voice` / `optimize_text_preview`。
     *
     * 历史上这里多塞过 `speed` 与 `prompt` —— 服务端根本没有这两个字段，填了不生效。
     * 风格控制只有两条官方路径：自然语言指令放 user 消息，音频标签直接写进
     * assistant 正文（如 `（叹气）` / `[笑]`）。
     */
    data class TtsAudioConfig(
        val format: String = "wav",   // 文档支持: wav, mp3, pcm, pcm16（流式必须 pcm16）
        val voice: String             // 预置音色名（如「冰糖」）或 "data:{MIME};base64,{BASE64_AUDIO}"
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
        @Header("Authorization") auth: String,
        @Header("api-key") apiKey: String,
        @Body request: TtsModels.TtsChatRequest
    ): retrofit2.Response<ResponseBody>

    companion object {
        fun buildUrl(baseUrl: String): String {
            val base = baseUrl.trimEnd('/')
            if (base.endsWith("/chat/completions")) return base
            val versionMatch = Regex("(.*/v\\d+)$").find(base)
            return if (versionMatch != null) {
                "${versionMatch.groupValues[1]}/chat/completions"
            } else {
                "$base/v1/chat/completions"
            }
        }
    }
}
