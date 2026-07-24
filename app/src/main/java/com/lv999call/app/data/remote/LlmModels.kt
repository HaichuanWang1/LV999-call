package com.lv999call.app.data.remote

import com.google.gson.annotations.SerializedName

/** LLM API 请求/响应模型 */
object LlmModels {

    data class ChatRequest(
        val model: String,
        val messages: List<Message>,
        val stream: Boolean = true,
        val temperature: Float = 0.7f,
        @SerializedName("max_tokens")
        val maxTokens: Int = 2048
    )

    data class Message(
        val role: String,
        val content: String
    )

    data class ChatResponse(
        val choices: List<Choice>
    )

    data class Choice(
        val delta: Delta?,
        val message: Message?
    )

    data class Delta(
        val content: String?
    )
}
