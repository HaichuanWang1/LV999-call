package com.lv999call.app.domain.usecase

import com.lv999call.app.domain.model.ExpressionSet
import com.lv999call.app.domain.model.Live2DExpression

/**
 * 从 LLM 流式输出中解析并剥离表情 / 动作标签（[ExpressionSet.TAG_REGEX]）。
 *
 * 要解决两个现实问题：
 *
 * 1. **标签会被 chunk 切断**（`"[[e:生"` + `"气]]"`）。未闭合的标签必须整段扣住不显示，
 *    否则玩家会看到半截标签一闪而过；同理也绝不能把它送进 TTS，否则会被念出来。
 * 2. **每次收到 chunk 都会重扫全文**（与既有 `<think>` 剥离逻辑一致，回复长度在千字级，
 *    重扫成本可以忽略），但表情只能触发一次，因此用 [emitted] 记录已触发的标签数，
 *    只回调新增的那些。
 *
 * 解析本身与角色无关（标签词法是全局统一的 `[[e:key]]`），但**查表**必须走当前角色的
 * [ExpressionSet] —— 银狼的 `[[e:月卡]]` 和 DeepSeek 酱的 `[[e:脸红]]` 是两套不同的
 * 名字空间，混用会让标签静默失效。
 *
 * 线程约束：仅在单条 LLM 收集协程内串行使用，不加锁。
 */
class ExpressionTagParser(
    private val expressions: ExpressionSet,
    private val onExpression: (Live2DExpression) -> Unit
) {
    /** 已经处理过的完整标签数量，用来避免同一条标签被重复触发 */
    private var emitted = 0

    /**
     * 传入「累积至今的原始全文」，返回可展示 / 可送 TTS 的干净文本。
     *
     * 尾部未闭合的标签会被暂时扣住，等后续 chunk 补齐后自然补全。
     */
    fun consume(raw: String): String {
        val matches = ExpressionSet.TAG_REGEX.findAll(raw).toList()

        for (i in emitted until matches.size) {
            expressions.byKey(matches[i].groupValues[2])?.let(onExpression)
        }
        emitted = matches.size

        // 剥离所有完整标签（未知标签也一并剥掉，避免 LLM 自创的标签被念出来）
        val cleaned = StringBuilder(raw.length)
        var cursor = 0
        for (m in matches) {
            cleaned.append(raw, cursor, m.range.first)
            cursor = m.range.last + 1
        }
        cleaned.append(raw, cursor, raw.length)

        return stripDanglingTag(cleaned.toString())
    }

    /**
     * 流结束时的收尾清洗。
     *
     * 与 [consume] 相同：模型被截断时留下的残缺标签直接丢弃 ——
     * 少一个表情好过把 `[[e:生` 念出来。
     */
    fun finish(raw: String): String = consume(raw)

    /** 扣住可能正在接收中的标签起始片段 */
    private fun stripDanglingTag(text: String): String {
        val open = text.lastIndexOf("[[")
        if (open < 0) {
            // 单个残留 '['，可能是 "[[" 的前半截
            var cut = text.length
            while (cut > 0 && text[cut - 1] == '[') cut--
            return text.substring(0, cut)
        }
        // 只有从该 "[[" 一直到结尾都还是个合法标签前缀时才扣住；
        // 否则说明这是正文里的普通方括号，原样保留。
        val tail = text.substring(open)
        return if (DANGLING_TAG.matchEntire(tail) != null) text.substring(0, open) else text
    }

    private companion object {
        /** 形如 `[[` / `[[e` / `[[m:` / `[[e:生` 的未闭合前缀 */
        val DANGLING_TAG = Regex("""\[\[\s*[eEmM]?\s*[:：]?\s*[^\[\]]{0,16}""")
    }
}
