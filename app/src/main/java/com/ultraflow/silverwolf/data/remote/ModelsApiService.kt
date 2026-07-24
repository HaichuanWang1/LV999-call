package com.ultraflow.silverwolf.data.remote

import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Url

/** /v1/models 响应 */
object ModelsResponse {
    data class ModelList(
        val data: List<ModelItem>
    )

    data class ModelItem(
        val id: String,
        val `object`: String = "model",
        val owned_by: String = "",
        val context_length: Int? = null  // 模型上下文窗口大小（token）
    )
}

/** 模型列表 API */
interface ModelsApiService {

    @GET
    suspend fun listModels(
        @Url url: String,
        @Header("api-key") apiKey: String,
        @Header("Authorization") auth: String
    ): ModelsResponse.ModelList

    companion object {
        fun buildUrl(baseUrl: String): String {
            val base = baseUrl.trimEnd('/')
            return if (base.endsWith("/v1/models")) {
                base
            } else if (base.endsWith("/v1")) {
                "$base/models"
            } else {
                "$base/v1/models"
            }
        }
    }
}
