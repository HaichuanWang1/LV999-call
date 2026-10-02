package com.lv999call.app.domain.model

/**
 * 一次远端调用（LLM / TTS）失败的原因分类。
 *
 * ## 为什么要有分类，而不是直接甩一句 `e.message`
 *
 * 改造前失败信息只进 Logcat，用户看到的是**角色突然不说话**，或者更糟：
 * 老实现把 `[错误: xxx]` 当正文念出来。现在失败要弹窗，但"弹什么"必须分得清 ——
 * 「key 没填」和「额度用尽」用户要做的事完全不同（一个去设置页补 key，
 * 一个去服务商后台充值），给同一句 `HTTP 401 Unauthorized` 等于没说。
 *
 * 判据以**服务端返回的状态码**为准（401/403 → key，429/402 → 额度），
 * 文案关键词只作为兜底：有些服务商把额度问题塞在 400/500 里。
 */
enum class ApiFailureKind {
    /** key 没填 / 无效 / 没权限（HTTP 401、403），或本地压根没填 */
    MISSING_KEY,

    /** 额度耗尽 / 余额不足（HTTP 429、402，或错误文案命中"余额/额度"） */
    QUOTA_EXHAUSTED,

    /** TTS 没有可用音色：voiceclone 模型却没有参考音频（本地配置问题，根本没发请求） */
    TTS_VOICE_MISSING,

    /** 其他失败：原样把服务端的话带给用户，不做二次解释 */
    OTHER
}

/**
 * 一次远端调用的失败详情。
 *
 * [detail] 是**给用户看的**服务端原话（解析自响应体的 error.message 等字段），
 * 不是异常类名、也不是 `HTTP 401` 这种没有信息量的状态行 —— 后者正是
 * "报错信息没正常填写"的病灶。
 *
 * @param kind 失败分类，决定弹窗标题与引导文案
 * @param detail 服务端返回的错误说明（解析不到时回落为状态行/异常说明）
 * @param httpCode HTTP 状态码；本地配置问题（没发请求）时为 null
 */
data class ApiFailure(
    val kind: ApiFailureKind,
    val detail: String,
    val httpCode: Int? = null
)
