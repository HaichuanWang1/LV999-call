package com.lv999call.app.domain.model

/**
 * LLM 可以调用的 Live2D 表情白名单。
 *
 * 背景：银狼模型的 `Expressions[].Name` 是中文、带编号空格且格式不统一
 * （`"01黑脸"` / `"02 脸红爱心"` / `"月卡"`），让 LLM 原样复述极易写错一个字符就整条失效；
 * 因此这里做一层「稳定短标签 → 模型真实名称」映射，提示词里只暴露 [key]。
 *
 * 只收录**面部表情**。模型里另外 5 条（吹泡泡 / 外套关闭 / 抱胸手 / 捧心手 / 要饭手）
 * 实质是手部与服装的状态切换而非瞬时情绪，混入情绪表达会互相覆盖，
 * 故不开放给 LLM（仍可通过 `L2D.setExpression` 手动传真实名称触发）。
 */
enum class Live2DExpression(
    /** 提示词中暴露给 LLM 的稳定标签 */
    val key: String,
    /** 模型 model3.json 里 Expressions[].Name 的真实值 */
    val modelName: String,
    /** 该表情传达的情绪，写进提示词供 LLM 选择 */
    val hint: String
) {
    BLACK_FACE("黑脸", "01黑脸", "无语、嫌弃、瞪人"),
    BLUSH("脸红", "02 脸红爱心", "害羞、被夸或被拆穿傲娇"),
    ANGRY("生气", "03 生气", "恼火、炸毛、不服气"),
    DIZZY("晕", "04 晕", "被绕晕、听不懂、脑子过载"),
    SQUEEZE("＞＜", "05 ＞＜", "夸张抗拒、受不了、表情崩坏"),
    DEADPAN("0.0", "06 0.0", "死鱼眼、懒得搭理、冷漠"),
    STAR_EYES("星星眼", "07 星星眼", "兴奋、期待、很想要"),
    TEARS("流泪", "08 流泪", "委屈、装哭、卖惨"),
    SMUG("月卡", "月卡", "得意、炫耀、心情大好");

    companion object {
        private val BY_KEY: Map<String, Live2DExpression> = entries.associateBy { it.key }

        /** 按标签取表情；未知标签返回 null（调用方负责把它从文本里剥掉） */
        fun byKey(key: String): Live2DExpression? = BY_KEY[key.trim()]

        /**
         * 标签词法：`[[e:生气]]`
         *
         * 冒号半角全角都收，标签内允许空格；中段禁止出现方括号，
         * 否则会把两个相邻标签「贪婪」地并成一个。
         */
        val TAG_REGEX = Regex("""\[\[\s*[eE]\s*[:：]\s*([^\[\]]{1,16}?)\s*]]""")

        /**
         * 注入 system prompt 的表情指令块。
         *
         * 只拼一次、跟着用户自己的角色提示词走，因此对 QUICK / LONG / 自定义预设
         * 一视同仁：谁开了 Live2D 谁才注入，关掉 Live2D 时 LLM 完全不知道这套机制。
         *
         * 少量 few-shot 示例是刻意的 —— 只给规则不给例子时，模型很容易自创标签格式。
         */
        fun promptBlock(): String = buildString {
            appendLine()
            appendLine("# 表情标签（系统机制，优先于角色扮演）")
            appendLine("你的回复会实时转成语音，并驱动虚拟形象的表情。为了让形象跟上情绪，")
            appendLine("可以在回复最前面插入一个表情标签，格式形如 [[e:黑脸]]：")
            appendLine()
            appendLine("规则：")
            appendLine("1. 标签只能出现在整段回复的最开头，每次最多 1 个。")
            appendLine("2. 表情只能从下表选择，必须逐字照抄，不要改写、翻译、加空格或标点。")
            appendLine("3. 标签不会被朗读、也不会展示给玩家，只是内部指令，不要解释这个机制。")
            appendLine("4. 标签后面必须紧跟你要对玩家说的话，绝不能只输出一个标签。")
            appendLine("5. 情绪不明显时不要加；宁可少加，也不要加错。")
            appendLine()
            appendLine("可用表情：")
            for (e in entries) {
                appendLine("- [[e:${e.key}]] ${e.hint}")
            }
            appendLine()
            appendLine("示例：")
            appendLine("玩家「我抽了80发还没出，心态炸了。」 → [[e:月卡]]哈？80发？我10连就出了。喏，截图发你。")
            appendLine("玩家「你明明就是担心我。」 → [[e:脸红]]……你最近是不是装了读心插件？")
            appendLine("玩家「银狼救命！我卡关了！」 → [[e:0.0]]叫爸爸，我就告诉你隐藏通道在哪。")
        }
    }
}
