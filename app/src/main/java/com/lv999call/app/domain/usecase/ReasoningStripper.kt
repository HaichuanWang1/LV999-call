package com.lv999call.app.domain.usecase

/**
 * 流式剥离模型推理块（`<think>…</think>` / `<thinking>` / `<reasoning>` / `<analysis>`）。
 *
 * 为什么不能再用一句正则：`Regex("<think>[\\s\\S]*?</think>")` 只认**闭合**标签。
 * 流式中间态里思考块还开着，正文一个字都还没出来，正则匹配不到 → 整段推理
 * 原样出现在气泡里实时滚动；模型被截断时还会连着半截标签一起进 TTS 被念出来。
 * 这是「思考模式即使开着也不能输出」这条要求真正要解决的地方
 * —— 光靠请求体里的 `thinking:{type:"disabled"}` 挡不住不认这个私有字段的模型。
 *
 * 实现是一个逐块增量词法器，而不是正则替换：
 * - 维护「已确认可显示文本」[safe]，[feed] 返回其**全量**（UI 侧即覆盖式刷新）；
 * - 遇到开标签 → 进入推理态，直到闭合标签为止的内容全部丢弃；
 * - 标签可能被 chunk 切成 `"<thi"` + `"nk>"`：尾部若是**合法的标签前缀**就扣住不放，
 *   下一块到达自然补齐；不像标签的 `<`（如 `3 < 5`）当普通正文直接输出；
 * - [finish] 时放行被扣住的正文；若流在推理块中途结束，余下内容一律丢弃，
 *   保证「被截断的推理永远不会进 TTS」。
 *
 * 线程约束：仅在单条 LLM 收集协程内串行使用，不加锁。
 */
class ReasoningStripper {

    /** 已接收的原始文本（跨块查找标签边界） */
    private val raw = StringBuilder()

    /** 原始文本已处理到的位置 */
    private var cursor = 0

    /** 当前是否处于推理块内部 */
    private var inReasoning = false

    /** 已确认可显示的文本 */
    private val safe = StringBuilder()

    /** 传入一个增量 chunk，返回「截至目前的全部可显示文本」 */
    fun feed(chunk: String): String {
        if (chunk.isNotEmpty()) raw.append(chunk)
        drain(final = false)
        return safe.toString()
    }

    /**
     * 流结束收尾，返回最终可显示文本。
     *
     * 仍在推理块内时余下内容一律丢弃；单纯被扣住的正文则放行（不能吞字）。
     */
    fun finish(): String {
        drain(final = true)
        return safe.toString()
    }

    private fun drain(final: Boolean) {
        val end = raw.length
        while (true) {
            if (inReasoning) {
                val close = closeRegex.find(raw, cursor)
                if (close != null) {
                    // 推理块内不需要保留任何内容，直接跳到闭合标签之后
                    cursor = close.range.last + 1
                    inReasoning = false
                    continue
                }
                if (final) {
                    // 流结束了还没闭合：余下的推理内容整体丢弃
                    cursor = end
                    return
                }
                // 闭合标签可能正被 chunk 切断：扣住可能是标签前缀的尾巴，其余丢弃
                cursor = holdPotentialTag(cursor, end) ?: end
                return
            }

            val lt = raw.indexOf('<', cursor)
            if (lt < 0) {
                safe.append(raw, cursor, end)
                cursor = end
                return
            }

            // 标签之前的正文先收下
            if (lt > cursor) safe.append(raw, cursor, lt)

            // 1) 完整的开标签 → 进入推理态
            val open = openRegex.find(raw, lt)
            if (open != null && open.range.first == lt) {
                cursor = open.range.last + 1
                inReasoning = true
                continue
            }

            // 2) 完整的闭标签 → 丢弃（模型漏了开标签时也不该把 </think> 念出来）
            val close = closeRegex.find(raw, lt)
            if (close != null && close.range.first == lt) {
                cursor = close.range.last + 1
                continue
            }

            // 3) 不完整的标签前缀 → 扣住等下一块
            val held = holdPotentialTag(lt, end)
            if (held != null && !final) {
                cursor = held
                return
            }
            if (final && held != null) {
                // 流已结束，残缺标签直接丢弃
                cursor = end
                return
            }

            // 4) 正文里普通的 '<'：原样输出，继续往后扫
            safe.append('<')
            cursor = lt + 1
        }
    }

    /**
     * 若 [raw] 从 [from] 到末尾的片段"看起来是半个标签"，返回该起点；否则返回 null。
     *
     * 判据（三个条件同时满足）：
     * - 长度不超过 [MAX_TAG_LEN]（最长标签 `</analysis>` 只有 11 字符，
     *   超了还没等到 `>` 的 `<` 已不可能构成标签，必须当正文放行，
     *   否则正文里写个 `<` 就能把后面十几个字一直卡住）；
     * - 还没出现 `>`；
     * - `<` 之后只有可选的 `/`、空白与字母 —— `3 < 5` 这种带数字的立刻判定为正文。
     */
    private fun holdPotentialTag(from: Int, end: Int): Int? {
        if (from >= end) return null
        val tail = raw.substring(from, end)
        if (tail.length > MAX_TAG_LEN) return null
        return if (POTENTIAL_TAG_PREFIX.matches(tail)) from else null
    }

    private companion object {
        /** 最长标签 `</analysis>` 共 11 字符，留点余量 */
        const val MAX_TAG_LEN = 16

        private const val NAMES = "think|thinking|reasoning|analysis"

        val openRegex = Regex("<\\s*(?:$NAMES)\\s*>", RegexOption.IGNORE_CASE)
        val closeRegex = Regex("</\\s*(?:$NAMES)\\s*>", RegexOption.IGNORE_CASE)

        /** 形如 `<` / `</` / `<thi` / `</thin` 的未完成前缀（不含 `>`，不含非字母） */
        val POTENTIAL_TAG_PREFIX = Regex("^</?\\s*[a-zA-Z]{0,12}$")
    }
}
