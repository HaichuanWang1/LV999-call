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
    }
}
