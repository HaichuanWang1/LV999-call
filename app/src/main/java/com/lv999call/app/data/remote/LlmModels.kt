package com.lv999call.app.data.remote

import com.google.gson.annotations.SerializedName

/** LLM API 请求/响应模型 */
object LlmModels {

    data class ChatRequest(
        val model: String,
        val messages: List<Message>,
        val stream: Boolean = true,
        /** 采样温度（OpenAI 兼容接口通用） */
        val temperature: Float = 0.7f,
        /** 核采样（OpenAI 兼容接口通用） */
        @SerializedName("top_p")
        val topP: Float = 1.0f,
        @SerializedName("max_tokens")
        val maxTokens: Int = 2048,
        /**
         * 私有思考模式开关，**只在确认支持的服务商上发送**。
         *
         * 早期版本无条件带上 `thinking:{type:"disabled"}`，那是小米 MiMo 的私有字段：
         * 对 OpenAI / Groq 这类严格校验请求体的接口，未知字段可能直接 400。
         * 现在由 [supportsThinkingSwitch] 判定，不支持的接口字段为 null，
         * Gson 默认不会序列化 null，即请求体里根本不出现这个键。
         */
        val thinking: ThinkingConfig? = null
    )

    data class ThinkingConfig(
        val type: String  // "enabled" or "disabled"
    )

    data class Message(
        val role: String,
        val content: String
    )

    data class ChatResponse(
        val choices: List<Choice>?
    )

    data class Choice(
        val delta: Delta?,
        val message: Message?
    )

    data class Delta(
        val content: String?,
        /**
         * 部分服务商把推理内容单独放在 delta.reasoning_content（DeepSeek / 硅基流动等）。
         * 这里显式声明出来只为「识别并丢弃」，避免它被当成正文显示。
         */
        @SerializedName("reasoning_content")
        val reasoningContent: String? = null
    )

    /**
     * 该接口是否认 `thinking` 开关。
     *
     * 目前只有小米 MiMo 的 chat completions 支持这个字段（也是本项目 TTS/LLM 的
     * 默认服务商）。其余一律不发，避免因为一个未知字段把请求打死。
     */
    fun supportsThinkingSwitch(baseUrl: String): Boolean =
        baseUrl.contains("xiaomimimo", ignoreCase = true)
}
