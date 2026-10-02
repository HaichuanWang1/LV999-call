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
    /**
     * 语音识别走哪条路：`"custom"`（HTTP 端点）或 `"vosk"`（本机离线模型）。
     *
     * 默认是 [ASR_PROVIDER_VOSK]。理由：HTTP 那条路必须自己填 baseUrl + key，
     * 新装完就是不可用状态；离线模型随包分发，装完就能说话，语音也不出设备。
     */
    val asrProvider: String = DEFAULT_ASR_PROVIDER,
    val asrBaseUrl: String = "",
    val asrApiKey: String = "",

    /**
     * ASR 模型名（如 `whisper-1` / `whisper-large-v3` / `FunAudioLLM/SenseVoiceSmall`）。
     *
     * OpenAI 的 `/v1/audio/transcriptions` 把 `model` 列为**必填**，缺失直接 400；
     * 旧实现虽然声明了这个 part 却从来没下发过，导致官方端点必然失败。
     * 留空 = 不发该字段，交给自建服务端用自己的默认模型。
     */
    val asrModel: String = "",

    /**
     * 识别语言，ISO-639-1（`zh` / `en`）或 `auto`。
     *
     * 历史上默认 `zh-CN`，但 Whisper 只认两位码，下发前由
     * [com.lv999call.app.data.remote.AsrApiService.normalizeLanguage] 归一化。
     */
    val asrLanguage: String = "zh",

    /**
     * Vosk 离线模型 id（对应 `assets/vosk-models/<id>`）。
     *
     * 默认 [DEFAULT_VOSK_MODEL_ID]（随包分发的那一个），这样设置页的模型列表
     * 一开始就是「已选中」而不是空白。
     */
    val asrVoskModelId: String = DEFAULT_VOSK_MODEL_ID,

    // TTS配置 (MiMo-V2.5-TTS 系列)
    // provider / baseUrl / voiceId / speed 曾经也在这里，但请求体里从来没有真正生效过
    // （端点与格式由 ChatRepository 锁死 MiMo），留着只会让人以为改得动，故撤掉。
    val ttsApiKey: String = "",
    val ttsModel: String = "mimo-v2.5-tts-voiceclone",

    /**
     * 各内置角色的 TTS 风格提示词，键为 [BuiltInCharacter.id]。
     *
     * 未设置时回落到该角色的 [BuiltInCharacter.defaultTtsPrompt]。
     *
     * ⚠️ 这里曾经还有一份**全局** `ttsPrompt`，作为"角色没配语气时的兜底"。
     * 它是一条实打实的跨角色污染通道：银狼的默认语气是空的，于是会一路回落到全局那份
     * —— 只要全局那一格里有任何内容（历史上确实有 UI 能写它），银狼就会用**别人的**语气说话，
     * 而且从准备页完全看不出来。现在语气只有两个来源：这一张表（按角色）与自定义方案自带的
     * `PresetEntity.ttsPrompt`，谁都不许串到别人身上。
     */
    val characterTtsPrompts: Map<String, String> = emptyMap(),

    /**
     * 全局默认参考音频（设置页管理）。
     *
     * 优先级（见 ProcessAudioUseCase 的发声来源说明）：
     * 角色锁定策略 > 自定义方案自带音频 > 这一份 > 角色自带兜底音色。
     * 早期还有一份 `customTtsReferenceAudioBase64`（"自定义模式专用"），但那条模式入口
     * 已不存在、也没有任何 UI 能写它，属于死配置，已撤掉。
     */
    val ttsReferenceAudioBase64: String = "",
    val ttsReferenceAudioMime: String = "audio/wav",

    /**
     * 一次 TTS 朗读的等待上限（秒）。
     *
     * 从「开口」到「放完最后一段音频」整段计时，超时立刻停播并回到聆听。
     * 它兜的是两类卡死：服务端接了请求却一直不下发音频（一个字都没出声），
     * 以及音频放了一半后再也不来新数据（解码协程挂在 socket 上）。
     *
     * 之所以做成可配置：这个上限同时也是**正常长回复的天花板** —— 调小了会把
     * 说到一半的回复硬切掉，调大了卡死时要干等更久。默认 [DEFAULT_TTS_PLAYBACK_TIMEOUT_SEC]
     * 与旧版本写死的值一致，升级后行为不变。
     */
    val ttsPlaybackTimeoutSec: Int = DEFAULT_TTS_PLAYBACK_TIMEOUT_SEC,

    // 角色配置
    val characterAvatarUri: String = "",
    val backgroundUri: String = "",

    // 通话界面使用 Live2D 动态形象（关闭后回退到静态头像）
    val live2dEnabled: Boolean = true,

    // 接通/挂断时播放"变身"过场（模型自带的划卡变身，约 2.3s+2.3s）
    // 纯演出，关掉不影响待机动作与表情/口型
    val live2dTransformEnabled: Boolean = true,

    /**
     * 声音跟着情绪走（默认**关**）：把本轮表情标签对应的语气拼进这一次的 TTS 提示词。
     *
     * 关掉后**表情照常变**，只是朗读沿用角色一贯的语气 —— 脸是脸、声音是声音。
     *
     * 默认关是刻意的：情绪语气是**叠加在基础风格之上的第二段指令**，而基础风格
     * （角色的 `ttsPrompt`）常常是空的（例如 DeepSeek 酱默认不再预置语气）——
     * 两段里只剩情绪段时，模型很容易把它当成"整段风格"来演，听感上像是这一句
     * 换了个人。它是个加分项，不该是默认行为；想要"笑着说出很凶的话"的用户
     * 自己去设置页打开即可（Live2D 子开关，见 [live2dEnabled]）。
     *
     * 依赖 [live2dEnabled]：表情标签协议只在 Live2D 打开时注入（见
     * [com.lv999call.app.domain.usecase.ProcessAudioUseCase] 拼提示词处），
     * 所以 Live2D 关着时本开关**无从生效** —— 设置页因此把它做成 Live2D 的子开关，
     * 而不是给一个"开着却什么都不做"的独立开关。
     */
    val emotionVoiceEnabled: Boolean = false,

    // ---------- 长期记忆（plan4 §5.7） ----------

    /**
     * 长期记忆总开关（默认**开**）。
     *
     * 关掉后：挂断不总结、开聊不注入、也不补总结 —— 但**已有记忆原样保留**（只是不读不写）。
     * 想要真正清空请走记忆库页面，这样"停用"与"删除"是两个互不牵连的动作。
     */
    val memoryAutoSummarizeEnabled: Boolean = true,

    /**
     * 短通话也总结（默认关）。
     *
     * 关：只有一轮真实发言的通话不进记忆库，避免记忆被"用户打了个招呼"刷屏；
     * 开：门槛整体降一档（真实用户发言 ≥ 1 轮 / 用户文本合计 ≥ 8 字）——
     * 打开它的用户要的就是"一句不落"，降档要真的明显，见
     * [com.lv999call.app.domain.usecase.SummarizeMemoryUseCase] 的门槛判据。
     */
    val memorySummarizeShortCalls: Boolean = false,

    /**
     * 记忆提醒通知开关（默认**开**）。
     *
     * 默认开会带来一个必须配套解决的问题：API 33+ 的 POST_NOTIFICATIONS 是**运行时权限**，
     * 而请求权限只能在前台做 —— 推送发生在后台 Worker 里，它没有任何办法弹对话框。
     * 于是"开关默认开"会造出一个坏状态：开关是开的、权限没有，
     * [com.lv999call.app.notify.MemoryReminderWorker] 每轮都卡在 `canPost` 上静默跳过，
     * 用户看到的是一个"开着却永远收不到"的功能。
     *
     * 所以 [com.lv999call.app.MainActivity] 里配了一条请求路径补上这个缺口：冷启动时
     * 「开关开着 + 还没有权限 + 已经有过至少一次会话」就请求一次；**被拒绝则把本开关
     * 落回 false**，免得设置页显示一个骗人的"已开启"。
     *
     * 为什么门槛要卡"已经有过会话"：Worker 在一条会话都没有时本来就会跳过（没东西可提醒），
     * 那时弹权限对话框纯属打扰 —— 用户刚装完 App，还不知道它是干嘛的。
     */
    val memoryReminderEnabled: Boolean = true,

    /**
     * 上次**成功推送**提醒的时刻（毫秒，0 = 从未），用于 24 小时频控。
     *
     * 语义严格限定为"通知真的发出去了"：只有 [ReminderNotifier] 返回 true 之后才写。
     * 提前写会让一次失败的尝试白吃掉一整天的配额，用户那天就再也收不到提醒了 ——
     * 而失败最常见的原因（没网、LLM 抽风）本来下一轮就能自愈。
     */
    val memoryReminderLastAt: Long = 0
) {
    companion object {
        /** ASR：走 HTTP 端点（OpenAI `/v1/audio/transcriptions` 兼容协议） */
        const val ASR_PROVIDER_CUSTOM = "custom"

        /** ASR：走随包分发的 Vosk 离线模型 */
        const val ASR_PROVIDER_VOSK = "vosk"

        /**
         * 默认 ASR 档位。
         *
         * 之所以默认离线：在线那条路要求用户自己填 baseUrl 与 key，装完是**不可用**的；
         * 而离线模型是 assets 资产，装完即可说话，且录音不出设备。
         * 代价是首次使用要把它解压到内部存储（约 65 MB），这一段有进度遮罩兜着 ——
         * 见 [com.lv999call.app.ui.call.VoskPrepareState]。
         */
        const val DEFAULT_ASR_PROVIDER = ASR_PROVIDER_VOSK

        /**
         * 随包分发的离线模型 id（`assets/vosk-models/<id>`）。
         *
         * 这个字符串在配置默认值、模型列表、通话准备三条路上都必须一致，
         * 所以只在这里写一次。
         */
        const val DEFAULT_VOSK_MODEL_ID = "vosk-model-small-cn-0.22"

        /** 朗读超时默认值（秒）—— 与旧版本写死的 180s 一致 */
        const val DEFAULT_TTS_PLAYBACK_TIMEOUT_SEC = 180

        /**
         * 朗读超时可调范围（秒）。
         *
         * 下限 30s：比这更短的话，正常的长回复也会被腰斩，用户会以为"TTS 坏了"；
         * 上限 600s：再长就等于没有超时，卡死时用户只能挂断。
         */
        const val MIN_TTS_PLAYBACK_TIMEOUT_SEC = 30
        const val MAX_TTS_PLAYBACK_TIMEOUT_SEC = 600

        /** 滑杆档位（秒）：30/60/…/600，避免滑出 137 秒这种怪数字 */
        const val TTS_PLAYBACK_TIMEOUT_STEP_SEC = 30

        /**
         * 把任意来源的秒数夹进合法范围。
         *
         * 读配置与设置页保存**都要过这一道**：DataStore 里可能是手改过的旧值
         * （0 / 负数 / 巨大值），直接乘 1000 交给 `withTimeoutOrNull` 会得到
         * 一个立刻超时或永不超时的行为，两者都不是用户想要的。
         */
        fun clampTtsPlaybackTimeoutSec(sec: Int): Int =
            sec.coerceIn(MIN_TTS_PLAYBACK_TIMEOUT_SEC, MAX_TTS_PLAYBACK_TIMEOUT_SEC)
    }

    /**
     * 取某内置角色的 TTS 风格提示词。
     *
     * 优先用户为该角色单独设置的值，其次角色自带的默认值（由调用方传入，
     * 因为它来自 [BuiltInCharacter] 而非配置本身）。
     *
     * 取不到就返回空串 —— 空串是**合法且常见**的结果（例如银狼、以及默认不再预置
     * 语气的 DeepSeek 酱）：TTS 请求里那条风格指令留空，模型按它自己的默认语气念。
     * 这里**不再往全局配置回落**，见 [characterTtsPrompts] 的说明。
     */
    fun getTtsPromptForCharacter(characterId: String, default: String = ""): String =
        characterTtsPrompts[characterId]?.takeIf { it.isNotEmpty() } ?: default
}
