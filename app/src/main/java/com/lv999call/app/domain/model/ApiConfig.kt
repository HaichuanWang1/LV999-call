package com.lv999call.app.domain.model

/** API配置 */
data class ApiConfig(
    // LLM配置
    val llmBaseUrl: String = "",
    val llmApiKey: String = "",
    val llmModel: String = "mimo-v2.5",
    val maxContextTokens: Int = 200000,  // 上下文窗口上限（token）

    // ---------- LLM 采样参数 ----------
    // 只放「几乎所有 OpenAI 兼容接口都认」的三个，避免给模型塞不支持的字段导致 400。
    // 各家私有的开关（如 MiMo 的 thinking、Qwen 的 enable_thinking）一律不进设置页，
    // 需要时在代码里按 provider 分支处理。

    /** 采样温度：越低越稳定，越高越发散（0.0 ~ 2.0） */
    val llmTemperature: Float = 0.7f,

    /** 核采样（top_p）：与温度二选一调即可（0.0 ~ 1.0） */
    val llmTopP: Float = 1.0f,

    /** 单次回复的最大输出 token 数 */
    val llmMaxOutputTokens: Int = 2048,

    /**
     * 是否允许模型进入「思考模式」（默认关闭）。
     *
     * 刻意**不做进设置页**：这是各家私有协议（MiMo 的 `thinking`、Qwen 的
     * `enable_thinking`…），放出来只会让用户在不支持的模型上踩 400。
     * 这里的开关只影响请求体，**无论如何推理内容都不会显示、也不会被朗读**
     * （显示与 TTS 侧有独立的剥离逻辑，见 ProcessAudioUseCase）。
     */
    val llmThinkingEnabled: Boolean = false,

    // ASR配置
    val asrProvider: String = "custom",  // "custom" | "vosk"
    val asrBaseUrl: String = "",
    val asrApiKey: String = "",
    val asrLanguage: String = "zh-CN",
    val asrVoskModelId: String = "",  // Vosk离线模型ID

    // TTS配置 (MiMo-V2.5-TTS-VoiceClone)
    val ttsProvider: String = "mimo",
    val ttsBaseUrl: String = "",
    val ttsApiKey: String = "",
    val ttsModel: String = "mimo-v2.5-tts-voiceclone",
    val ttsVoiceId: String = "",
    val ttsSpeed: Float = 1.0f,

    /**
     * 全局 TTS 风格提示词（自定义预设 / 快速模式用）。
     *
     * 内置角色**不用**这一项：每个角色有自己的 [characterTtsPrompts]，
     * 否则在 DeepSeek酱 页面改一句语气，会连带把银狼的语气也改掉 ——
     * 这正是"并列预设"要避免的互相污染。
     */
    val ttsPrompt: String = "",

    /**
     * 各内置角色的 TTS 风格提示词，键为 [BuiltInCharacter.id]。
     *
     * 未设置时回落到该角色的 [BuiltInCharacter.defaultTtsPrompt]。
     */
    val characterTtsPrompts: Map<String, String> = emptyMap(),

    // 全局默认参考音频（设置页管理，快速/长提示词模式使用）
    val ttsReferenceAudioBase64: String = "",
    val ttsReferenceAudioMime: String = "audio/wav",

    // 自定义模式专用参考音频（自定义编辑页管理，仅自定义模式使用）
    val customTtsReferenceAudioBase64: String = "",
    val customTtsReferenceAudioMime: String = "audio/wav",

    // 角色配置
    val characterAvatarUri: String = "",
    val backgroundUri: String = "",

    // 自定义模式提示词
    val customPrompt: String = "",

    // TTS播放完毕后才开始录音（防止录到TTS声音）
    val waitTtsBeforeRecord: Boolean = true,

    // 通话界面使用 Live2D 动态形象（关闭后回退到静态头像）
    val live2dEnabled: Boolean = true,

    // 接通/挂断时播放"变身"过场（模型自带的划卡变身，约 2.3s+2.3s）
    // 纯演出，关掉不影响待机动作与表情/口型
    val live2dTransformEnabled: Boolean = true
) {
    /** 根据对话模式获取对应的参考音频 */
    fun getRefAudioForMode(mode: DialogMode): String {
        return if (mode == DialogMode.CUSTOM && customTtsReferenceAudioBase64.isNotEmpty()) {
            customTtsReferenceAudioBase64
        } else {
            ttsReferenceAudioBase64
        }
    }

    /** 根据对话模式获取对应的参考音频MIME */
    fun getRefAudioMimeForMode(mode: DialogMode): String {
        return if (mode == DialogMode.CUSTOM && customTtsReferenceAudioBase64.isNotEmpty()) {
            customTtsReferenceAudioMime
        } else {
            ttsReferenceAudioMime
        }
    }

    /**
     * 取某内置角色的 TTS 风格提示词。
     *
     * 优先用户为该角色单独设置的值，其次角色自带的默认值（由调用方传入，
     * 因为它来自 [BuiltInCharacter] 而非配置本身）。
     */
    fun getTtsPromptForCharacter(characterId: String, default: String = ""): String =
        characterTtsPrompts[characterId]?.takeIf { it.isNotEmpty() } ?: default

    /** 覆写某内置角色的 TTS 风格提示词 */
    fun withCharacterTtsPrompt(characterId: String, prompt: String): ApiConfig =
        copy(characterTtsPrompts = characterTtsPrompts + (characterId to prompt))
}
