package com.lv999call.app.data.remote

import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Streaming
import retrofit2.http.Url

/** LLM API 服务 - 支持SSE流式 */
interface LlmApiService {

    @POST
    @Streaming
    suspend fun chatCompletionStream(
        @Url url: String,
        @Header("Authorization") auth: String,
        @Body request: LlmModels.ChatRequest
    ): ResponseBody

    companion object {
        fun buildFullUrl(baseUrl: String): String {
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
