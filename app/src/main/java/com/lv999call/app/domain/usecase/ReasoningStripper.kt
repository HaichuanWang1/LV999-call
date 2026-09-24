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
 * 策略（逐块状态机，非正则）：
 * - 维护「已确认可显示文本」，[feed] 返回其**全量**（UI 侧就是覆盖式刷新）；
 * - 处于推理块内时全部丢弃，只留可能尚未收全的关闭标签前缀；
 * - 处于正文时正常累积，但**从句尾最后一个 `<` 起临时扣住** —— 标签可能被
 *   chunk 切成 `"<thi"` + `"nk>"`，不扣住就会漏出半截标签；下一块到达即自动放行；
 * - [finish] 时会放行扣住的内容；若此时仍在推理块内，则余下内容直接丢弃，
 *   保证「截断的推理永远不会进 TTS」。
 *
 * 线程约束：仅在单条 LLM 收集协程内串行使用，不加锁。
 */
class ReasoningStripper {

    /** 已接收的原始文本（用于跨块查找标签边界） */
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
     * 仍在推理块内时余下内容一律丢弃；单纯被扣住的尾巴则会放行（不能吞正文）。
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
                if (close != null && close.range.first == cursor) {
                    cursor = close.range.last + 1
                    inReasoning = false
                    continue
                }
                // 还没收全关闭标签
                if (final) {
                    cursor = end
                    return
                }
                cursor = holdFrom(cursor, end)
                return
            }

            val open = openRegex.find(raw, cursor)
            if (open != null && open.range.first == cursor) {
                // 推理开始：把标签之前的正文收进 safe
                safe.append(raw, cursor, open.range.first)
                cursor = open.range.last + 1
                inReasoning = true
                continue
            }

            if (final) {
                // 放行剩余（含被扣住的尾部）
                safe.append(raw, cursor, end)
                cursor = end
                return
            }
            val safeEnd = holdFrom(cursor, end)
            safe.append(raw, cursor, safeEnd)
            cursor = safeEnd
            return
        }
    }

    /**
     * 从句尾最后一个 `<` 起扣住（上限 [MAX_TAG_LEN] 字符）。
     *
     * 只有在「尾部确实可能正在接收一个标签」时才值得扣：标签最长 `</analysis>`，
     * 所以超过这个长度还没等到 `>` 的 `<` 已被 [openRegex]/[closeRegex] 判定为
     * 普通文本，直接放行，避免正文里写个 `<` 就把后面十几字卡住不放。
     */
    private fun holdFrom(from: Int, end: Int): Int {
        val lt = raw.lastIndexOf('<', end - 1)
        if (lt < from) return end
        return if (end - lt <= MAX_TAG_LEN) lt else end
    }

    private companion object {
        /** 标签最长形态 `</analysis>` = 11，留点余量 */
        const val MAX_TAG_LEN = 16

        private const val NAMES = "think|thinking|reasoning|analysis"

        val openRegex = Regex("<\\s*(?:$NAMES)\\s*>", RegexOption.IGNORE_CASE)
        val closeRegex = Regex("</\\s*(?:$NAMES)\\s*>", RegexOption.IGNORE_CASE)
    }
}
