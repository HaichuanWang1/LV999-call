package com.lv999call.app.data.repository

import com.lv999call.app.domain.model.ChatMessage

/**
 * 老版本「累积式保存」留下的脏数据修复。
 *
 * 背景（1.1.0 及更早）：通话每轮结束都会把**当前完整消息列表**整体写库，
 * 而消息表主键是自增 id，`OnConflictStrategy.REPLACE` 永远命不中冲突 ——
 * 于是每一轮都把已有消息原样**再追加一遍**。第 n 轮结束时库里长这样：
 *
 * ```
 * L1 ++ L2 ++ ... ++ Ln        （Lk = 第 k 轮结束时的完整列表，是 Ln 的前缀）
 * ```
 * 因为每轮只会往列表尾部追加消息，`L1 ⊂ L2 ⊂ ... ⊂ Ln` 恒成立，
 * 所以**真身就是最后一块 `Ln`**，且它必然是整段数据的后缀。
 *
 * 修复策略：从最长的后缀开始试，检查「全量数据能否被拆成若干块，
 * 每块都是该后缀的前缀、且块长严格递增」。这个检查是**结构性精确匹配** ——
 * 拆完必须逐条等于原数据，普通（哪怕是重复内容较多的）正常记录几乎不可能恰好满足，
 * 所以不会误伤。找不到就原样返回。
 *
 * 只做读取期修复、不写回：错误判断的代价仅限一次显示，不会污染数据库。
 */
internal object MessageHistoryRepair {

    /**
     * 若 [messages] 是老版本累积保存的产物，返回去重后的真身，否则原样返回。
     */
    fun repair(messages: List<ChatMessage>): List<ChatMessage> {
        if (messages.size < 3) return messages

        // 从长到短试后缀：取「最不激进」的可行解，宁可少删不多删
        for (tailSize in messages.size - 1 downTo 1) {
            val tail = messages.subList(messages.size - tailSize, messages.size)
            if (isCumulativeChain(messages, tail)) {
                android.util.Log.w(
                    "MsgRepair",
                    "检测到累积式重复消息：${messages.size} 条 → 修复为 $tailSize 条"
                )
                return tail.toList()
            }
        }
        return messages
    }

    /**
     * [all] 能否拆成 ≥2 块，每块都是 [tail] 的前缀、块长严格递增，且首尾相接正好覆盖 [all]。
     *
     * 用 DFS + 记忆化：块数很少（每轮一块），状态空间是 (位置, 上一块长度)，规模可控。
     */
    private fun isCumulativeChain(all: List<ChatMessage>, tail: List<ChatMessage>): Boolean {
        val visited = HashSet<Long>()

        fun dfs(pos: Int, prevLen: Int, blockCount: Int): Boolean {
            if (pos == all.size) return blockCount >= 2
            val key = (pos.toLong() shl 32) or (prevLen.toLong() and 0xFFFFFFFFL)
            if (!visited.add(key)) return false

            for (len in (prevLen + 1)..tail.size) {
                if (pos + len > all.size) break
                if (!prefixMatches(all, pos, tail, len)) continue
                if (dfs(pos + len, len, blockCount + 1)) return true
            }
            return false
        }

        return dfs(pos = 0, prevLen = 0, blockCount = 0)
    }

    /** 比较角色与内容（时间戳不参与：同一轮的消息时间戳一致，但语义去重不该依赖它） */
    private fun prefixMatches(
        all: List<ChatMessage>,
        start: Int,
        tail: List<ChatMessage>,
        len: Int
    ): Boolean {
        for (i in 0 until len) {
            val a = all[start + i]
            val b = tail[i]
            if (a.role != b.role || a.content != b.content) return false
        }
        return true
    }
}
