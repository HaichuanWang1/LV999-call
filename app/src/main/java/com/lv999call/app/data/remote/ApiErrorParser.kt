package com.lv999call.app.data.remote

import com.lv999call.app.domain.model.ApiFailure
import com.lv999call.app.domain.model.ApiFailureKind
import org.json.JSONObject
import retrofit2.HttpException

/**
 * 把「远端接口失败」翻译成一条**用户看得懂**的 [ApiFailure]。
 *
 * ## 为什么需要它
 *
 * 改造前 LLM 流式调用直接 `catch (e: Exception) -> emit(Failure(e.message))`，
 * 而 Retrofit 抛出的 [HttpException] 的 message 长这样：
 * `HTTP 401 Unauthorized` —— 服务端明明在响应体里写清了
 * `{"error":{"message":"You didn't provide an API key..."}}`，我们却一个字都没读。
 * 结果就是弹窗里只有一句没有信息量的状态行，等于"报错信息没正常填写"。
 *
 * 这里做两件事：
 * 1. **读响应体**：按各家的常见形态把 `error.message` 抠出来（OpenAI / DeepSeek /
 *    小米 MiMo / DashScope 都是 `{"error":{...}}` 这一套，但字段名与嵌套深度并不统一）；
 * 2. **分类**：以 HTTP 状态码为主判据，文案关键词兜底 —— 见 [ApiFailureKind]。
 */
object ApiErrorParser {

    /** 抠出来的错误说明最长留这么多字符，避免把整页 HTML 错误页塞进弹窗 */
    private const val MAX_DETAIL_CHARS = 300

    /** 各家把错误说明放在哪个键上（按优先级） */
    private val MESSAGE_KEYS = listOf(
        "message", "msg", "detail", "error_msg", "error_description", "reason", "code"
    )

    /**
     * 额度类关键词。
     *
     * 为什么状态码之外还要认文案：把额度问题塞进 400/500 的服务商不少
     * （有的只在 `error.message` 里写 "Insufficient Balance"），
     * 只认 429 会把这类漏成"其他错误"，用户就不知道该去充值。
     */
    private val QUOTA_KEYWORDS = listOf(
        "insufficient", "quota", "balance", "billing", "credit", "exceeded your current",
        "free tier", "余额", "额度", "欠费", "充值", "配额"
    )

    /**
     * key / 鉴权类关键词（状态码之外兜底）。
     *
     * 刻意不收「未提供」这种太泛的词：它同样会出现在"未提供模型参数"这类
     * 与鉴权无关的 400 里，误判会让用户去补一个本来就填好的 key。
     */
    private val KEY_KEYWORDS = listOf(
        "api key", "api_key", "apikey", "unauthorized", "authentication", "invalid key",
        "permission denied", "密钥", "鉴权", "认证失败"
    )

    /**
     * 从「HTTP 状态码 + 响应体」构造失败详情。
     *
     * @param errorBody 响应体原文（拿不到就传 null）
     */
    fun fromHttp(code: Int, reason: String, errorBody: String?): ApiFailure {
        // 响应体里抠不出东西时才回落到状态行。这里要防一手 Retrofit 的
        // HttpException.message —— 它本身就是 "HTTP 401 Unauthorized"，
        // 无脑拼成 "HTTP 401 HTTP 401 Unauthorized" 就闹笑话了。
        val fallback = when {
            reason.isBlank() -> "HTTP $code"
            reason.startsWith("HTTP ", ignoreCase = true) -> reason
            else -> "HTTP $code $reason"
        }
        val detail = extractMessage(errorBody) ?: fallback
        return ApiFailure(kind = classify(code, detail), detail = detail, httpCode = code)
    }

    /** 从任意异常构造失败详情；[HttpException] 会顺带把响应体读出来 */
    fun fromThrowable(e: Throwable): ApiFailure = when (e) {
        is HttpException -> {
            val body = runCatching { e.response()?.errorBody()?.string() }.getOrNull()
            fromHttp(e.code(), e.message().orEmpty(), body)
        }

        // 下面这几类不是"服务端说了什么"，而是"根本没连上"。给一句人话，
        // 而不是把 java.net.UnknownHostException 甩到弹窗里。
        is java.net.UnknownHostException ->
            ApiFailure(ApiFailureKind.OTHER, "无法连接服务器（域名解析失败）：${e.message}")

        is java.net.SocketTimeoutException ->
            ApiFailure(ApiFailureKind.OTHER, "请求超时：${e.message}")

        is java.net.ConnectException ->
            ApiFailure(ApiFailureKind.OTHER, "无法连接服务器：${e.message}")

        else -> ApiFailure(ApiFailureKind.OTHER, e.message ?: e.javaClass.simpleName)
    }

    /**
     * 本地就没填配置时用这个 —— **压根没发请求**，所以 httpCode 为 null。
     *
     * 它对应"返回没有填写 key"里最直接的那一半：key / baseUrl 是空的，
     * 请求发出去也只会得到一句 `Expected URL scheme 'http' or 'https'` 这种
     * 谁也看不懂的东西。
     */
    fun configMissing(detail: String): ApiFailure =
        ApiFailure(kind = ApiFailureKind.MISSING_KEY, detail = detail)

    /** TTS 没有可用音色（voiceclone 却没有参考音频）：本地配置问题 */
    fun voiceMissing(detail: String): ApiFailure =
        ApiFailure(kind = ApiFailureKind.TTS_VOICE_MISSING, detail = detail)

    /**
     * 从一个 **HTTP 200 的响应体**里认出错误。
     *
     * 为什么需要：失败并不总是非 2xx —— 不少网关是 `200 + {"error":{...}}`
     * （要么整个响应体就是错误 JSON，要么它是 SSE 流里的一个错误分块）。
     * 不认这一形态的话，这类失败会退化成"模型什么都没说"，静默吞掉。
     *
     * @return 认出错误就是失败详情；不是错误（没有 `error` 键 / 不是 JSON）返回 null
     */
    fun fromSuccessfulBody(raw: String): ApiFailure? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return null
        // 键不存在、或值为 JSON null，都不算错误
        if (json.isNull("error")) return null

        val detail = extractMessage(text) ?: text.take(MAX_DETAIL_CHARS)
        return ApiFailure(kind = classifyByText(detail), detail = detail, httpCode = null)
    }

    /**
     * 从一段可能是 JSON 的文本里抠出错误说明。
     *
     * 支持的形态（都是真实见过的）：
     * - `{"error":{"message":"..."}}`（OpenAI / DeepSeek / MiMo）
     * - `{"error":"..."}`
     * - `{"code":"InvalidApiKey","message":"..."}`（DashScope）
     * - `{"detail":"..."}`（FastAPI 系）
     * - 纯文本（网关返回的 HTML/字符串）→ 原样截断
     *
     * @return 抠到的说明；文本为空时返回 null
     */
    fun extractMessage(raw: String?): String? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null

        val fromJson = runCatching { JSONObject(text) }.getOrNull()?.let { json ->
            when (val err = json.opt("error")) {
                is JSONObject -> firstString(err)
                is String -> err.takeIf { it.isNotBlank() }
                else -> null
            } ?: firstString(json)
        }

        return (fromJson ?: text)
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(MAX_DETAIL_CHARS)
            .ifBlank { null }
    }

    /** 分类：状态码优先，文案兜底 */
    private fun classify(code: Int, detail: String): ApiFailureKind {
        // 401/403 就是"key 这一关没过"：没填、填错、或这个 key 没有该模型的权限
        if (code == 401 || code == 403) return ApiFailureKind.MISSING_KEY

        // 429 = 频率限制或额度用尽；402 = 部分服务商的"余额不足"。
        // 两者对用户是同一件事：不是 key 的问题，是钱/配额的问题。
        if (code == 429 || code == 402) return ApiFailureKind.QUOTA_EXHAUSTED

        return classifyByText(detail)
    }

    /**
     * 只有文案可依据时的分类（HTTP 200 的流内错误分块走这条）。
     *
     * 认不出来的**不硬猜**：宁可给 [ApiFailureKind.OTHER] 把服务端原话原样端出去，
     * 也不要瞎归类 —— 归错类会让用户去补一个本来就填好的 key。
     */
    private fun classifyByText(detail: String): ApiFailureKind {
        val lower = detail.lowercase()
        if (QUOTA_KEYWORDS.any { lower.contains(it) }) return ApiFailureKind.QUOTA_EXHAUSTED
        if (KEY_KEYWORDS.any { lower.contains(it) }) return ApiFailureKind.MISSING_KEY
        return ApiFailureKind.OTHER
    }

    /** 按 [MESSAGE_KEYS] 的顺序取第一个非空字符串 */
    private fun firstString(json: JSONObject): String? {
        for (key in MESSAGE_KEYS) {
            val value = json.optString(key, "")
            if (value.isNotBlank()) return value
        }
        return null
    }
}
