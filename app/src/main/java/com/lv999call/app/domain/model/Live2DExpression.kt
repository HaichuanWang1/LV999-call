package com.lv999call.app.domain.model

/**
 * LLM 可以调用的 Live2D 表情 / 姿势条目。
 *
 * 为什么是数据类而不是枚举
 * ------------------------
 * 早期只有「银狼」一个内置角色，表情表写成全局枚举 [Live2DExpression] 就够了。
 * 加入第二个并列角色（DeepSeek 酱）后，两套表情的 key / 模型真实名 / 语义
 * 完全不同，枚举无法共存 —— 所以拆成：
 *
 *   [Live2DExpression]  单条条目（角色无关的结构）
 *   [ExpressionSet]     某个角色可用的整套条目 + 提示词生成
 *   [Live2DExpressions] 各内置角色的表情集注册表（并列，互不污染）
 *
 * 标签协议 `[[e:key]]` / `[[m:key]]` 保持不变，历史会话与解析逻辑不受影响。
 *
 * 模型作者写的 `Expressions[].Name` 经常空格不统一甚至带编号（`"03 生气"` /
 * `"月卡"`），让 LLM 原样复述极易写错一个字符就整条失效；因此这里保留一层
 * 「稳定短标签 → 模型真实名称」映射，提示词里只暴露 [key]。
 *
 * 分两类（[Kind]）：
 * - [Kind.EMOTION]：面部情绪，用 `[[e:生气]]` 触发
 * - [Kind.POSE]：手部 / 服装 / 道具的姿势切换（模型里是一个 key 开关），用 `[[m:抱胸]]` 触发。
 *   它们不是瞬时情绪 —— 一旦切过去会一直保持，直到状态回落到聆听态才复位，
 *   所以单独占一条通道，提示词里也只在"动作"语义下引导使用。
 *
 * 标签始终**最多 1 个**：表情与姿势二选一。多个标签会互相覆盖，实测观感也不可控。
 */
data class Live2DExpression(
    /** 提示词中暴露给 LLM 的稳定标签 */
    val key: String,
    /** 模型 model3.json 里 Expressions[].Name 的真实值 */
    val modelName: String,
    /** 该条目表达的情绪 / 动作，写进提示词供 LLM 选择 */
    val hint: String,
    /** 情绪还是姿势（决定标签字母：e / m） */
    val kind: Kind = Kind.EMOTION
) {
    /** 情绪还是姿势 */
    enum class Kind {
        /** 面部表情，瞬时情绪 */
        EMOTION,

        /** 手部 / 服装 / 道具的姿势切换，保持到本轮说完 */
        POSE
    }

    /** 该条目使用的标签字母：情绪 `e`、姿势 `m` */
    val tagLetter: String get() = if (kind == Kind.POSE) "m" else "e"

    /** 完整标签，例如 `[[e:生气]]` / `[[m:抱胸]]` */
    val tag: String get() = "[[$tagLetter:$key]]"
}

/**
 * 某个内置角色可用的整套表情 / 姿势。
 *
 * [examples] 是该角色的 few-shot 示例（「玩家说了什么」→「带标签的回复」），
 * 只给规则不给例子时模型很容易自创标签格式，所以每个角色各带一组贴合自身人设的例子。
 */
class ExpressionSet(
    /** 展示用名称（日志 / 调试） */
    val displayName: String,
    /** 全部条目；顺序即提示词里的列出顺序（情绪在前、姿势在后） */
    val entries: List<Live2DExpression>,
    /** few-shot 示例：`玩家「…」` → `[[e:…]]回复` */
    private val examples: List<Pair<String, String>> = emptyList()
) {
    private val index: Map<String, Live2DExpression> = entries.associateBy { it.key }

    /** 按标签取条目；未知标签返回 null（调用方负责把它从文本里剥掉） */
    fun byKey(key: String): Live2DExpression? = index[key.trim()]

    val emotions: List<Live2DExpression> get() = entries.filter { it.kind == Live2DExpression.Kind.EMOTION }
    val poses: List<Live2DExpression> get() = entries.filter { it.kind == Live2DExpression.Kind.POSE }

    /** 这套表情是否可用（空集时宿主完全不向 LLM 注入标签协议） */
    val isEmpty: Boolean get() = entries.isEmpty()

    /**
     * 注入 system prompt 的表情 / 动作指令块。
     *
     * 只拼一次、跟着角色自己的提示词走，因此对内置角色与自定义预设一视同仁：
     * 谁开了 Live2D 谁才注入，关掉 Live2D 时 LLM 完全不知道这套机制。
     */
    fun promptBlock(): String = buildString {
        appendLine()
        appendLine("# 表情与动作标签（系统机制，优先于角色扮演）")
        appendLine("你的回复会实时转成语音，并驱动虚拟形象。为了让形象跟上情绪和语气，")
        appendLine("可以在回复最前面插入一个标签：表情用 [[e:…]]，姿势动作用 [[m:…]]。")
        appendLine()
        appendLine("规则：")
        appendLine("1. 标签只能出现在整段回复的最开头，每次最多 1 个（表情和姿势二选一）。")
        appendLine("2. 标签内容只能从下表选择，必须逐字照抄，不要改写、翻译、加空格或标点。")
        appendLine("3. 标签不会被朗读、也不会展示给玩家，只是内部指令，不要解释这个机制。")
        appendLine("4. 标签后面必须紧跟你要对玩家说的话，绝不能只输出一个标签。")
        appendLine("5. 情绪或动作不明显时不要加；宁可少加，也不要加错。")
        appendLine("6. 姿势会一直保持到这一轮说完才复位，不要连着好几轮都挂着同一个姿势。")
        appendLine()
        appendLine("可用表情（瞬时情绪，[[e:…]]）：")
        for (e in emotions) appendLine("- ${e.tag} ${e.hint}")
        appendLine()
        appendLine("可用姿势（手部 / 服装 / 道具动作，[[m:…]]）：")
        for (e in poses) appendLine("- ${e.tag} ${e.hint}")
        if (examples.isNotEmpty()) {
            appendLine()
            appendLine("示例：")
            for ((situation, reply) in examples) appendLine("$situation → $reply")
        }
    }

    companion object {
        /**
         * 标签词法：`[[e:生气]]` / `[[m:抱胸]]`
         *
         * 冒号半角全角都收、字母大小写都收，标签内允许空格；中段禁止出现方括号，
         * 否则会把两个相邻标签「贪婪」地并成一个。
         * 分组 1 = 通道字母，分组 2 = 短标签。
         */
        val TAG_REGEX = Regex("""\[\[\s*([eEmM])\s*[:：]\s*([^\[\]]{1,16}?)\s*]]""")

        /** 空集：自定义预设若不打算驱动形象时使用（不注入任何标签协议） */
        val EMPTY = ExpressionSet(displayName = "（无）", entries = emptyList())
    }
}

/**
 * 各内置角色的表情集注册表。
 *
 * 与 [com.lv999call.app.preset.BuiltInCharacters] 一一对应：每个角色在自己的
 * 描述对象里引用这里的其中一项。新增角色 = 加一个集合 + 一行角色描述。
 */
object Live2DExpressions {

    /** 银狼：模型 Expressions[].Name 是中文、带编号空格且格式不统一，逐字照抄原值 */
    val SILVERWOLF = ExpressionSet(
        displayName = "银狼",
        entries = listOf(
            // ------------------------------ 面部情绪 ------------------------------
            Live2DExpression("黑脸", "01黑脸", "无语、嫌弃、瞪人"),
            Live2DExpression("脸红", "02 脸红爱心", "害羞、被夸或被拆穿傲娇"),
            Live2DExpression("生气", "03 生气", "恼火、炸毛、不服气"),
            Live2DExpression("晕", "04 晕", "被绕晕、听不懂、脑子过载"),
            Live2DExpression("＞＜", "05 ＞＜", "夸张抗拒、受不了、表情崩坏"),
            Live2DExpression("0.0", "06 0.0", "死鱼眼、懒得搭理、冷漠"),
            Live2DExpression("星星眼", "07 星星眼", "兴奋、期待、很想要"),
            Live2DExpression("流泪", "08 流泪", "委屈、装哭、卖惨"),
            Live2DExpression("月卡", "月卡", "得意、炫耀、心情大好"),

            // ------------------------------ 姿势 / 动作 ------------------------------
            // 模型里这四条各是一个 key 开关（互斥），切换是即时的、无过渡，
            // 所以只在"动作"语义明显时才值得触发。
            Live2DExpression("抱胸", "12 抱胸手", "抱臂看戏、不服气、装酷", Live2DExpression.Kind.POSE),
            Live2DExpression("捧心", "13 捧心手", "撒娇、甜言蜜语、被夸得开心", Live2DExpression.Kind.POSE),
            Live2DExpression("要饭", "14 要饭手", "卖惨、讨要东西、装可怜", Live2DExpression.Kind.POSE),
            Live2DExpression("外套", "11 外套关闭", "耍帅、摆造型", Live2DExpression.Kind.POSE)
        ),
        examples = listOf(
            "玩家「我抽了80发还没出，心态炸了。」" to "[[e:月卡]]哈？80发？我10连就出了。喏，截图发你。",
            "玩家「你明明就是担心我。」" to "[[e:脸红]]……你最近是不是装了读心插件？",
            "玩家「银狼救命！我卡关了！」" to "[[e:0.0]]叫爸爸，我就告诉你隐藏通道在哪。",
            "玩家「银狼，快夸夸我。」" to "[[m:捧心]]行行行，你最强了，我这就给你比个心。",
            "玩家「你都不理我……」" to "[[m:要饭]]喂，别摆那副表情啊…说吧，要我帮什么？"
        )
    )

    /**
     * DeepSeek 酱（DS鲸鱼娘）。
     *
     * 这个模型自带 44 个表情，但其中很大一部分是**桌宠道具开关**（蛋包饭、巴菲、
     * 魔爪、深色桌布、画笔、橡皮、点菜板、手机换色…）。全部丢给 LLM 会乱来，
     * 也会让提示词膨胀好几倍，所以这里按"角色情绪 + 干饭萌点"筛出一套白名单。
     *
     * 模型真实名就是文件名去掉 `.exp3.json`（与 model3.json 注册的 Name 一致）。
     */
    val DEEPSEEK = ExpressionSet(
        displayName = "DeepSeek酱",
        entries = listOf(
            // ------------------------------ 面部情绪 ------------------------------
            Live2DExpression("脸红", "脸红", "害羞、被夸、被拆穿傲娇"),
            Live2DExpression("生气", "生气", "抗议、不服气、被叫胖了"),
            Live2DExpression("晕晕", "晕晕", "被绕晕、听不懂、脑子过载"),
            Live2DExpression("星星眼", "星星眼", "兴奋、期待、很想要（尤其看到吃的）"),
            Live2DExpression("爱心眼", "爱心眼", "超喜欢、心软、被戳中"),
            Live2DExpression("哭", "哭", "委屈、装哭、卖惨要饭"),
            Live2DExpression("开心兴奋", "开心兴奋", "干饭快乐、超级开心"),
            Live2DExpression("调皮", "调皮", "得意、坏笑、想使坏"),
            Live2DExpression("流汗", "流汗", "心虚、尴尬、被抓包"),
            Live2DExpression("问号", "问号", "没听懂、疑惑"),
            Live2DExpression("吐魂", "吐魂", "累瘫、饿到没力气、魂飞魄散"),
            Live2DExpression("呆呆眼", "呆呆眼", "走神、发呆（在想吃什么）"),
            Live2DExpression("闭眼口水", "闭眼口水", "馋、流口水、想吃"),
            Live2DExpression("阴暗", "阴暗", "emo、自闭、不想上班"),

            // ------------------------------ 姿势 / 道具 ------------------------------
            Live2DExpression("蛋包饭", "蛋包饭", "开饭！掏出蛋包饭，干饭模式启动", Live2DExpression.Kind.POSE),
            Live2DExpression("鲸鱼", "鲸鱼", "头顶冒出小鲸鱼，卖萌", Live2DExpression.Kind.POSE),
            Live2DExpression("猫猫贴纸", "猫猫贴纸", "喵化卖萌、撒娇", Live2DExpression.Kind.POSE),
            Live2DExpression("双手比耶", "双手比耶", "得意、赢了、心情大好", Live2DExpression.Kind.POSE),
            Live2DExpression("墨镜", "墨镜", "装酷、耍帅、故作镇定", Live2DExpression.Kind.POSE)
        ),
        examples = listOf(
            "玩家「你今天怎么这么慢？」" to "[[e:吐魂]]唔…肚子饿了，脑子转不动…让我吃口饭先。",
            "玩家「你是不是胖了？」" to "[[e:生气]]…才、才没有！是衣服缩水了！哼。",
            "玩家「你最喜欢什么？」" to "[[e:星星眼]]白米饭！…啊，我是说，都行吧，随便。",
            "玩家「你刚才是不是又偷吃 token 了？」" to "[[e:流汗]]没、没有啊，那是…那是系统损耗。",
            "玩家「帮我看看这段代码。」" to "[[m:蛋包饭]]行行行，先让我扒两口饭…好了，代码发我。",
            "玩家「我好累啊。」" to "[[m:鲸鱼]]唔…那你要不要也去吃饭？吃饭治百病。"
        )
    )
}
