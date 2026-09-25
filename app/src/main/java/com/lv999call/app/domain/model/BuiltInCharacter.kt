package com.lv999call.app.domain.model

/**
 * 内置角色的 TTS 策略。
 *
 * 为什么需要它
 * ------------
 * 全局设置里用户自己选 TTS 模型（默认 `mimo-v2.5-tts-voiceclone`，走参考音频克隆）。
 * 但某些内置角色有**固定音色**的要求 —— 例如 DeepSeek 酱必须用 MiMo 的预置少女音，
 * 与设置里选什么无关。
 *
 * 于是把"这个角色怎么发声"也做成一等字段，而不是在调用链里写 `if (isDeepSeek)`：
 *
 * - [Inherit]：完全跟随全局设置（银狼、自定义预设走这条，行为与改造前一致）
 * - [PresetVoice]：锁定某个预置音色模型（`mimo-v2.5-tts` + `voice` 传音色名）
 * - [CloneVoice]：锁定克隆音色，但参考音频由角色自带（不读用户设置）
 *
 * 三者都是纯数据，[com.lv999call.app.data.repository.ChatRepository] 只按策略
 * 组装请求体，不认识任何具体角色。
 */
sealed interface TtsPolicy {

    /** 跟随全局设置：模型、音色、参考音频全部来自 [ApiConfig] */
    data object Inherit : TtsPolicy

    /**
     * 强制使用**预置音色**模型。
     *
     * MiMo 的预置音色只能配 `mimo-v2.5-tts`（不支持克隆与音色设计），
     * `voice` 字段传纯音色名（如 `冰糖`），而不是 `data:...;base64,...`。
     *
     * @param modelId 强制使用的模型 ID
     * @param voice   预置音色名（MiMo 中文女声：`冰糖` / `茉莉`；男声：`苏打` / `白桦`）
     */
    data class PresetVoice(
        val modelId: String = DEFAULT_MODEL,
        val voice: String
    ) : TtsPolicy {
        companion object {
            const val DEFAULT_MODEL = "mimo-v2.5-tts"
        }
    }

    /**
     * 强制使用**克隆音色**，参考音频由角色自带（assets 里的 base64）。
     *
     * @param modelId 强制使用的模型 ID
     * @param refAudioAsset assets 下的路径，如 `silverwolf/ref_voice.wav`
     * @param refAudioMime 参考音频 MIME
     */
    data class CloneVoice(
        val modelId: String = "mimo-v2.5-tts-voiceclone",
        val refAudioAsset: String,
        val refAudioMime: String = "audio/wav"
    ) : TtsPolicy
}

/**
 * 一个「内置预设」的完整描述。
 *
 * 设计意图
 * --------
 * 早期「银狼」不是"一个预设"，而是散落在全项目的硬编码：模型路径写死在 bridge.js、
 * 提示词写死在 StartCallUseCase、音色兜底写死在 ProcessAudioUseCase、背景图写死在
 * 路由、头像兜底写死在 CallScreen、署名写死在 Live2DAuthorCredit……
 *
 * 加第二个并列角色时这些点会互相污染。因此这里把**所有可能因角色而异的东西**
 * 收敛成一个数据对象：
 *
 *   身份       [id] / [displayName] / [subtitle]
 *   人格       [promptAsset]（系统提示词）
 *   形象       [live2dProfileId] / [modelPath] / [expressions]
 *   外貌       [avatarResId]（通话头像）/ [cardIconResId]（首页卡片图标）
 *   背景       [backgroundResId]
 *   发声       [ttsPolicy] / [defaultTtsPrompt]
 *   演出       [hasTransform]（是否有"变身"过场）
 *   署名       [credit]
 *   文案       [prepareTitle] / [prepareDescription]
 *
 * 银狼与 DeepSeek 酱是这个表的**两行并列数据**；新增第三个角色 = 加一行 + 一个
 * assets 提示词 + 一个 bridge profile，不需要再动任何调用链。
 *
 * @param id 稳定标识，用于路由参数与 bridge profile 选择；一旦发布不可改
 * @param displayName 界面显示名
 * @param subtitle 首页卡片副标题
 * @param promptAsset assets 中的系统提示词文件名
 * @param live2dProfileId 传给 bridge.js 的形象参数档位 id（见 bridge.js 的 PROFILES）
 * @param modelPath Live2D 模型相对 assets/live2d 的路径
 * @param expressions 该角色可用的表情 / 姿势集
 * @param avatarResId 通话头像（静态头像降级与聊天气泡小头像共用）
 * @param cardIconResId 首页预设卡片图标
 * @param backgroundResId 通话/准备页背景图
 * @param ttsPolicy 发声策略
 * @param defaultTtsPrompt 该角色默认的 TTS 风格提示词（首次进入准备页的占位）
 * @param hasTransform 是否有"变身"一次性过场（没有则挂断不做等待、不播动作）
 * @param credit 模型作者署名（null 表示不需要展示）
 */
data class BuiltInCharacter(
    val id: String,
    val displayName: String,
    val subtitle: String,
    val promptAsset: String,
    val live2dProfileId: String,
    val modelPath: String,
    val expressions: ExpressionSet,
    val avatarResId: Int,
    val cardIconResId: Int,
    val backgroundResId: Int?,
    val ttsPolicy: TtsPolicy,
    val defaultTtsPrompt: String = "",
    val hasTransform: Boolean = false,
    val credit: ModelCredit? = null,
    val prepareTitle: String = displayName,
    val prepareDescription: String = ""
) {
    /** 角色介绍卡里的图标前缀（准备页顶部） */
    val emoji: String get() = when (id) {
        "silverwolf" -> "🐺"
        "deepseek" -> "🐳"
        else -> "✦"
    }
}

/**
 * Live2D 模型作者署名。
 *
 * 按作者要求标注来源，并给一个能直接点进主页的入口。
 * 用短链而不是写死 UID：短链由作者本人维护，指向哪儿、以后换不换主页都不用改代码。
 */
data class ModelCredit(
    /** 展示文案，如「模型作者：槿絮OuO @bilibili」 */
    val label: String,
    /** 可点击的短链 */
    val url: String
)
