package com.ultraflow.silverwolf.domain.model

/** API配置 */
data class ApiConfig(
    // LLM配置
    val llmBaseUrl: String = "",
    val llmApiKey: String = "",
    val llmModel: String = "mimo-v2.5",
    val maxContextTokens: Int = 200000,  // 上下文窗口上限（token）

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
    val customPrompt: String = ""
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
}
