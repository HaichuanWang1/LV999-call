package com.lv999call.app.data.remote

import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Streaming
import retrofit2.http.Url

/** LLM API 服务 - 支持SSE流式，同时支持 Authorization 和 api-key 两种认证 */
interface LlmApiService {

    @POST
    @Streaming
    suspend fun chatCompletionStream(
        @Url url: String,
        @Header("Authorization") auth: String,
        @Header("api-key") apiKey: String,
        @Body request: LlmModels.ChatRequest
    ): ResponseBody

    companion object {
        fun buildFullUrl(baseUrl: String): String {
            val base = baseUrl.trimEnd('/')
            // 已包含完整端点
            if (base.endsWith("/chat/completions")) return base
            // 提取版本前缀（如 /v1, /v4, /api/paas/v4 等），在其后追加 /chat/completions
            val versionMatch = Regex("(.*/v\\d+)$").find(base)
            return if (versionMatch != null) {
                "${versionMatch.groupValues[1]}/chat/completions"
            } else {
                "$base/v1/chat/completions"
            }
        }
    }
}
