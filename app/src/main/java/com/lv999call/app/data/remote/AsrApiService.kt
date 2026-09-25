package com.lv999call.app.data.remote

import com.google.gson.annotations.SerializedName
import okhttp3.MultipartBody
import okhttp3.ResponseBody
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Url

/** ASR API 请求/响应模型 */
object AsrModels {

    data class TranscriptionResponse(
        val text: String = "",
        @SerializedName("result")
        val result: List<TranscriptionResult>? = null
    )

    data class TranscriptionResult(
        val text: String = ""
    )
}

/** ASR API 服务 */
interface AsrApiService {

    @Multipart
    @POST
    suspend fun transcribe(
        @Url url: String,
        @Header("Authorization") auth: String,
        @Part audio: MultipartBody.Part,
        @Part("model") model: okhttp3.RequestBody? = null,
        @Part("language") language: okhttp3.RequestBody? = null
    ): AsrModels.TranscriptionResponse

    companion object {
        fun buildTranscribeUrl(baseUrl: String): String {
            val base = baseUrl.trimEnd('/')
            return if (base.endsWith("/transcriptions") || base.endsWith("/transcribe")) {
                base
            } else {
                "$base/v1/audio/transcriptions"
            }
        }

        /**
         * 把语言码归一化成 OpenAI Whisper 要的 ISO-639-1 两位码。
         *
         * 设置页历史默认值是 `zh-CN`，而 `language` 参数要求 ISO-639-1（`zh`）——
         * 传 `zh-CN` 时部分服务端直接 400，另一些静默忽略并退回自动检测
         * （对短句中文尤其容易识别成英文）。
         *
         * `auto` / `自动` / 空 → 返回空串，调用方据此**不发** language part，走自动检测。
         */
        fun normalizeLanguage(raw: String): String {
            val s = raw.trim()
            if (s.isEmpty()) return ""
            if (s.equals("auto", ignoreCase = true) || s == "自动") return ""
            // zh-CN / zh_CN / zh-Hans-CN → zh
            val primary = s.split('-', '_').firstOrNull()?.trim().orEmpty()
            return if (primary.length in 2..3) primary.lowercase() else s
        }
    }
}
