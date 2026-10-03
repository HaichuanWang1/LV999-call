package com.lv999call.app.preset

import com.lv999call.app.R
import com.lv999call.app.domain.model.BuiltInCharacter
import com.lv999call.app.domain.model.Live2DExpressions
import com.lv999call.app.domain.model.ModelCredit
import com.lv999call.app.domain.model.TtsPolicy

/**
 * 内置角色注册表 —— 首页「内置预设」列表的唯一事实来源。
 *
 * 新增一个内置角色只需在 [ALL] 里加一行，并在 assets 里放好提示词文件；
 * 形象参数档位在同名 id 的 bridge.js `PROFILES` 里对应配置。
 * 调用链（首页 / 准备页 / 通话页 / 路由）全部按 [BuiltInCharacter] 的字段驱动，
 * 不出现任何针对具体角色的分支。
 */
object BuiltInCharacters {

    /** 银狼：行为、音色、提示词与改造前完全一致（改造目标是"可并列"，不是"改行为"） */
    val SILVERWOLF = BuiltInCharacter(
        id = "silverwolf",
        displayName = "银狼",
        subtitle = "角色扮演语音对话",
        promptAsset = "silverwolf_prompt.txt",
        live2dProfileId = "silverwolf",
        modelPath = "models/silverwolf/silverwolf.model3.json",
        expressions = Live2DExpressions.SILVERWOLF,
        avatarResId = R.drawable.touxiang,
        cardIconResId = R.drawable.touxiang,
        backgroundResId = R.drawable.silverwolf_bg,
        // 保持原行为：银狼用内置参考音频克隆，模型跟随全局设置
        ttsPolicy = TtsPolicy.CloneVoice(
            modelId = "mimo-v2.5-tts-voiceclone",
            refAudioAsset = "silverwolf/ref_voice.wav"
        ),
        hasTransform = true,
        credit = ModelCredit(
            label = "模型作者：槿絮OuO @bilibili",
            url = "https://b23.tv/5bDRwj4"
        ),
        prepareTitle = "银狼",
        prepareDescription = "使用银狼专属提示词和音色，开启沉浸式角色扮演语音对话。",
        emoji = "🐺"
    )

    /**
     * DeepSeek 酱（大肥鱼）。
     *
     * 形象来自 B 站 UP 主「狐宫静」无偿分享的「大肥鱼」Live2D 模型（VTube Studio 皮套）。
     * 安装见 `tools/setup_dafeiyu_model.py` —— 模型目录不入库，脚本才是唯一事实来源。
     * 发声锁定 MiMo 预置少女音「冰糖」—— 见 [TtsPolicy.PresetVoice]，
     * 通话时无视设置里选的 TTS 模型。
     */
    val DEEPSEEK = BuiltInCharacter(
        id = "deepseek",
        displayName = "DeepSeek酱",
        subtitle = "傲娇干饭鲸鱼娘",
        promptAsset = "deepseek_prompt.txt",
        // ⚠️ profile id 与模型目录名**故意不一致**：它是角色 ↔ 形象的绑定键，
        // 自定义预设也会把这个字符串存进数据库，改名会让已有预设找不到档位。
        // 形象本身换成了 models/dafeiyu/（见 bridge.js 的 deepseek 档）。
        live2dProfileId = "deepseek",
        modelPath = "models/dafeiyu/dafeiyu.model3.json",
        expressions = Live2DExpressions.DEEPSEEK,
        avatarResId = R.drawable.deepseek_avatar,
        cardIconResId = R.drawable.deepseek_avatar,
        backgroundResId = R.drawable.deepseek_bg,
        // 强制锁定 mimo 预置音色模型的少女音，无论设置里选了什么
        ttsPolicy = TtsPolicy.PresetVoice(voice = "冰糖"),
        // 默认**不带**风格提示词：预置音色「冰糖」本身就是这个角色的声音，
        // 再叠一段"清亮软糯、语速稍快、懒洋洋"的指令，等于在音色之上又压了一层表演，
        // 实际听感会更飘、也更难和角色的语气表（EmotionVoiceStyles）配合。
        // 留空 = TTS 请求里那条风格指令是空串，模型按预置音色自己的自然读法念；
        // 想调语气的用户在准备页里写自己的那一格即可（存进 characterTtsPrompts）。
        defaultTtsPrompt = "",
        // 大肥鱼同样没有"变身"这类一次性演出：它自带的是吃饭 / 吃token / token转 /
        // sleep / Scene1 五条道具动画（注册在 Action 组，目前没有代码播它们），
        // 硬当变身过场播会在接通瞬间定格一个怪动作，所以保持关闭。
        hasTransform = false,
        credit = ModelCredit(
            label = "模型作者：狐宫静 @bilibili",
            url = "https://space.bilibili.com/261589131"
        ),
        prepareTitle = "DeepSeek酱",
        prepareDescription = "使用 DeepSeek 酱专属提示词与少女音，和爱干饭的鲸鱼娘聊天。",
        emoji = "🐳"
    )

    /**
     * 流萤。
     *
     * 形象走**静态头像降级**这条路：没有合法可再分发的 Live2D 模型（见 plan10 的
     * 「阶段 2 预备方案」），所以 [modelPath] 指向的目录**故意不存在** ——
     * WebView 加载失败 → `Live2DStatus.ERROR` → `live2dActive = false` →
     * [com.lv999call.app.ui.call.CallScreen] 的 `StaticAvatar`，
     * 也就是用 [avatarResId] 那张头像，而不是白屏。这条降级路径是既有的、已测过的。
     *
     * 提示词里刻意**不含**表情标签协议：[expressions] 用的是空集，而
     * `ProcessAudioUseCase` 只在 `!expressions.isEmpty` 时才注入标签协议，
     * 所以 LLM 完全不知道这套机制 —— 这比注入一套模型里不存在的表情
     * （必然"标签触发了但脸没变"的静默失效）安全得多。
     *
     * 音色：参考音频从游戏档案语音里剪出来（见 `tools/make_ref_voice.py`），
     * 取的是「嗨，又见面啦…叫我「流萤」吧」那段打招呼 + 一段日常闲聊，
     * 不用剧情独白 —— 参考音频会连语气一起带过去。
     */
    val FIREFLY = BuiltInCharacter(
        id = "firefly",
        displayName = "流萤",
        subtitle = "温柔的火萤少女",
        promptAsset = "firefly_prompt.txt",
        live2dProfileId = "firefly",
        // ⚠️ 模型不存在，故意如此 —— 见类注释。阶段 2 拿到分层 PSD 后再补目录。
        modelPath = "models/firefly/firefly.model3.json",
        expressions = Live2DExpressions.FIREFLY,
        avatarResId = R.drawable.firefly_avatar,
        cardIconResId = R.drawable.firefly_avatar,
        backgroundResId = R.drawable.firefly_bg,
        // 克隆音色，参考音频由角色自带（与银狼同一条路），不读用户设置里的音色
        ttsPolicy = TtsPolicy.CloneVoice(
            modelId = "mimo-v2.5-tts-voiceclone",
            refAudioAsset = "firefly/ref_voice.wav"
        ),
        // 与银狼、DeepSeek 酱一致：不预置基础语气，克隆音色自己带着她的读法。
        // 想调语气的用户在准备页里写自己的那一格（存进 characterTtsPrompts）。
        defaultTtsPrompt = "",
        // 没有模型自然也没有"变身"过场
        hasTransform = false,
        // 模型还没有，没有作者可署名；阶段 2 补
        credit = null,
        prepareTitle = "流萤",
        prepareDescription = "使用流萤专属提示词与音色，和她聊聊今天过得怎么样。",
        emoji = "🦋"
    )

    /** 全部内置预设，顺序即首页展示顺序 */
    val ALL: List<BuiltInCharacter> = listOf(SILVERWOLF, DEEPSEEK, FIREFLY)

    /** 按 id 查；未知 id 返回 null（路由参数非法时由调用方兜底） */
    fun byId(id: String?): BuiltInCharacter? = ALL.firstOrNull { it.id == id }
}
