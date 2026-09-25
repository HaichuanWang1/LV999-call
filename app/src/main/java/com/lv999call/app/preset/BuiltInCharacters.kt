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
        prepareDescription = "使用银狼专属提示词和音色，开启沉浸式角色扮演语音对话。"
    )

    /**
     * DeepSeek 酱（大肥鱼）。
     *
     * 形象来自 B 站 UP 主「氵六青」无偿分享的 DS鲸鱼娘 Live2D 模型（鼠控版）。
     * 发声锁定 MiMo 预置少女音「冰糖」—— 见 [TtsPolicy.PresetVoice]，
     * 通话时无视设置里选的 TTS 模型。
     */
    val DEEPSEEK = BuiltInCharacter(
        id = "deepseek",
        displayName = "DeepSeek酱",
        subtitle = "傲娇干饭鲸鱼娘",
        promptAsset = "deepseek_prompt.txt",
        live2dProfileId = "deepseek",
        modelPath = "models/deepseek/c_0120.model3.json",
        expressions = Live2DExpressions.DEEPSEEK,
        avatarResId = R.drawable.deepseek_avatar,
        cardIconResId = R.drawable.deepseek_avatar,
        backgroundResId = R.drawable.deepseek_bg,
        // 强制锁定 mimo 预置音色模型的少女音，无论设置里选了什么
        ttsPolicy = TtsPolicy.PresetVoice(voice = "冰糖"),
        defaultTtsPrompt = "用清亮软糯的少女音说话，语速稍快，带点懒洋洋的傲娇感，偶尔像饿了一样有气无力。",
        // 该模型没有"变身"这类一次性演出（只有 idle / 吹泡泡 / 自拍等循环动作），
        // 硬播会在接通瞬间定格一个怪动作，所以关闭过场
        hasTransform = false,
        credit = ModelCredit(
            label = "模型作者：氵六青 @bilibili",
            url = "https://space.bilibili.com/11272072"
        ),
        prepareTitle = "DeepSeek酱",
        prepareDescription = "使用 DeepSeek 酱专属提示词与少女音，和爱干饭的鲸鱼娘聊天。"
    )

    /** 全部内置预设，顺序即首页展示顺序 */
    val ALL: List<BuiltInCharacter> = listOf(SILVERWOLF, DEEPSEEK)

    /** 按 id 查；未知 id 返回 null（路由参数非法时由调用方兜底） */
    fun byId(id: String?): BuiltInCharacter? = ALL.firstOrNull { it.id == id }
}
