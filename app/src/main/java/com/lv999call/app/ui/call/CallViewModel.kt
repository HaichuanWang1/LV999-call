package com.lv999call.app.ui.call

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lv999call.app.audio.AudioPlayer
import com.lv999call.app.audio.AudioRecorder
import com.lv999call.app.data.local.entity.PresetEntity
import com.lv999call.app.di.AppModule
import com.lv999call.app.domain.model.*
import com.lv999call.app.domain.usecase.ManageSessionUseCase
import com.lv999call.app.domain.usecase.ProcessAudioUseCase
import com.lv999call.app.domain.usecase.StartCallUseCase
import com.lv999call.app.domain.usecase.SummarizeMemoryUseCase
import com.lv999call.app.preset.BuiltInCharacters
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 一次 LLM 触发的表情指令。
 *
 * 用自增 [seq] 而不是裸字符串：连说两句都「生气」时，Compose 侧如果只比较字符串，
 * LaunchedEffect 不会重启，第二次的保持时长就白算了。
 */
data class ExpressionCue(val modelName: String, val seq: Long)

class CallViewModel(
    private val appModule: AppModule,
    private val application: android.app.Application
) : ViewModel() {

    private val startCallUseCase: StartCallUseCase = appModule.startCallUseCase
    private val manageSessionUseCase: ManageSessionUseCase = appModule.manageSessionUseCase
    private val processAudioUseCase: ProcessAudioUseCase = appModule.processAudioUseCase
    private val configRepository = appModule.configRepository

    private val audioRecorder = AudioRecorder(application)
    private val audioPlayer = appModule.audioPlayer

    private val _callState = MutableStateFlow(CallState.IDLE)
    val callState: StateFlow<CallState> = _callState.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _currentResponse = MutableStateFlow("")
    val currentResponse: StateFlow<String> = _currentResponse.asStateFlow()

    /**
     * 是否处于「思考中占位」阶段 —— 特指 **语音识别结束 → LLM 首个可见字到达** 这段。
     *
     * 为什么单独开一个状态而不是让 UI 看 `currentResponse.isEmpty()`：
     * 那段等待里气泡是空的，UI 只能靠「有没有文字」猜，于是会出现
     * 「先弹出一个空气泡、过一会儿字才填进去」的突兀感。有了这个信号，
     * UI 可以先挂一个「气泡内转圈」的占位，等第一块正文到达时原地切换成流式文本，
     * 完成 plan1 要求的「加载动画 → 正式输出」的无缝衔接。
     */
    private val _isThinkingResponse = MutableStateFlow(false)
    val isThinkingResponse: StateFlow<Boolean> = _isThinkingResponse.asStateFlow()

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    /**
     * 「没听清」提示的计数器。
     *
     * 用自增计数而不是 Boolean：连续两次都没听清时，UI 侧的 LaunchedEffect
     * 只比较布尔值不会重启，第二次提示就弹不出来了（与表情 cue 同一个坑）。
     */
    private val _asrRetryHint = MutableStateFlow(0)
    val asrRetryHint: StateFlow<Int> = _asrRetryHint.asStateFlow()

    /**
     * 当前通话的内置角色（自定义预设通话时为 null）。
     *
     * UI 靠它决定 Live2D 模型路径与 profile、静态头像、背景图、署名与过场开关 ——
     * 这样 [CallScreen] 不需要知道"银狼"或"DeepSeek 酱"是谁，只认这个描述对象。
     */
    private val _character = MutableStateFlow<BuiltInCharacter?>(null)
    val character: StateFlow<BuiltInCharacter?> = _character.asStateFlow()

    /**
     * 自定义方案通话的形象信息（头像 / 背景），供 UI 在**续聊**时也能恢复方案的样子。
     *
     * 为什么必须由 ViewModel 提供：`CALL_CONTINUE/{sessionId}` 路由手里只有 sessionId，
     * 拿不到 presetId，而续聊时角色是异步反查出来的（自定义会话恒为 null）——
     * UI 没有别的渠道知道"这通属于哪个方案"。null 表示**不是**自定义方案通话
     * （内置角色 / 无角色），空串表示方案没配那一项。
     */
    data class PresetVisuals(val avatarUri: String = "", val backgroundUri: String = "")

    private val _presetVisuals = MutableStateFlow<PresetVisuals?>(null)
    val presetVisuals: StateFlow<PresetVisuals?> = _presetVisuals.asStateFlow()

    /**
     * 实时音量（0f ~ 1f），用于驱动 Live2D 口型同步。
     *
     * - SPEAKING：取 TTS 播放音量（口型跟着合成语音张合）
     * - 其他状态：取麦克风输入音量（可做呼吸/聆听反馈）
     *
     * 两路数据源：AudioRecorder.audioLevel 与 AudioPlayer.amplitude。
     */
    private val _audioLevel = MutableStateFlow(0f)
    val audioLevel: StateFlow<Float> = _audioLevel.asStateFlow()

    /**
     * LLM 通过回复里的 [[e:标签]] 触发的情绪表情（null 表示无）。
     *
     * 由 [ProcessAudioUseCase] 在流式解析时回调，UI 层负责保持一段时间后复位。
     */
    private val _expressionCue = MutableStateFlow<ExpressionCue?>(null)
    val expressionCue: StateFlow<ExpressionCue?> = _expressionCue.asStateFlow()
    private var expressionSeq = 0L

    init {
        // 常驻收集，不用 stateIn(WhileSubscribed)：
        // 后者在订阅者短暂断开（页面重组 / 切页）时会取消上游，
        // 实测会导致 TTS 播放期间口型完全收不到音量。
        viewModelScope.launch {
            combine(
                _callState,
                audioRecorder.audioLevel,
                audioPlayer.amplitude
            ) { state, micLevel, ttsLevel ->
                if (state == CallState.SPEAKING) ttsLevel else micLevel
            }.collect { level ->
                _audioLevel.value = level
            }
        }
    }

    private var currentSession: Session? = null

    /**
     * 当前通话的内置角色（自定义预设通话时为 null）。
     *
     * 提示词 / 表情集 / 发声策略 / 头像 / 背景 / 过场开关全部由它提供 ——
     * ViewModel 里不再出现任何"如果是银狼就……"的分支。
     *
     * 读写都走 [_character]，避免"内部字段"与"UI 可见状态"两份数据不同步。
     */
    private var currentCharacter: BuiltInCharacter?
        get() = _character.value
        set(value) { _character.value = value }
    private var systemPrompt: String? = null

    /**
     * 本通电话的【长期记忆】提示词块（plan4 §4.2）；null / 空串 = 没有可注入的记忆。
     *
     * 两条纪律写在这里，免得以后又被"顺手优化"掉：
     * 1. **绝不写回数据库**。它只拼进本次请求的 systemPrompt；一旦落进
     *    `sessions.systemPrompt`，续聊时 `continueSession()` 会把它当成角色设定读回来，
     *    再叠加新的一份 —— 越续越长，最终把上下文吃光（§4.1 的明确要求）。
     * 2. **开场问候轮不用它**。`isAutoGreeting = true` 那一轮只是打招呼，塞进几百字记忆
     *    会让首字延迟变长、还容易让模型一上来就翻旧账；从第二轮起才带（§4.1）。
     *    注意它与 [isOpeningTurn] 不是一回事，后者在续聊场景恒为 false（见那里的注释）。
     */
    private var memoryPromptBlock: String? = null
    @Volatile
    private var isProcessing = false
    // 监听超时Job，防止VAD卡死导致UI永久停在"聆听"
    private var listeningTimeoutJob: kotlinx.coroutines.Job? = null
    // 预设专用的TTS参考音频（不污染全局配置）
    private var presetRefAudioBase64: String? = null
    private var presetRefAudioMime: String? = null
    // 当前通话使用的TTS提示词（内置角色用角色默认，自定义预设用preset）
    private var currentTtsPrompt: String = ""

    /**
     * 本通电话的**记忆角色隔离键**（plan4 §2.3 / P1）。
     *
     * 与 `sessions.characterKey` 是同一个值，这里留一份内存副本是为了挂断时
     * 不必回头读库：三处入口都显式赋值，续聊从会话那一列读回 —— 自定义预设的
     * presetId 只在 [startPresetCall] 里存在，靠提示词反查永远推不出来。
     *
     * ⚠️ 读它的地方（挂断触发的总结）跑在 viewModelScope 之外的线程上，所以标 @Volatile。
     */
    @Volatile
    private var memoryCharacterKey: String = Session.CHARACTER_KEY_DEFAULT

    /**
     * 总结请求闸门（plan4 §5.3 第一级 · 页面去抖）。
     *
     * 挂断按钮与 NavGraph 的 ENDED 跳转可能连着触发两次总结；一个会话只放行一次。
     * 与新起的「续聊同一 ViewModel 实例」无关：每次通话都是一个新的 CallViewModel。
     */
    @Volatile
    private var summaryRequested = false

    /**
     * 当前通话的表情集。
     *
     * 自定义预设（[currentCharacter] 为 null）回落到**银狼那一套**：自定义预设的
     * 形象走 bridge.js 默认档位，也就是银狼模型 —— 表情名必须与真实加载的模型匹配，
     * 否则标签会静默失效。改造前这里是全局单例枚举，对所有模式一视同仁；
     * 若在这里返回空集，等于把自定义预设的表情驱动悄悄删掉。
     */
    private val currentExpressions: ExpressionSet
        get() = currentCharacter?.expressions ?: Live2DExpressions.SILVERWOLF

    /**
     * 当前角色的发声策略。
     *
     * 内置角色若锁定了音色（如 DeepSeek 酱强制 MiMo 预置少女音），这里会返回
     * 对应策略并**覆盖设置里的 TTS 模型选择**；银狼/自定义预设返回 null，
     * 完全跟随全局设置（与改造前行为一致）。
     */
    private val currentTtsPolicy: TtsPolicy?
        get() = appModule.resolveTtsPolicy(currentCharacter)

    /**
     * 当前通话的预设参考音频（仅自定义预设用）。
     *
     * 内置角色**不走这里** —— 它们自带音色走 [characterRefAudio]，优先级更低，
     * 这样用户在准备页里选的音频始终能覆盖内置音色。
     */
    private val presetRefAudio: String?
        get() = presetRefAudioBase64

    private val presetRefAudioMimeValue: String?
        get() = presetRefAudioMime

    /** 角色自带参考音频（仅 [TtsPolicy.CloneVoice] 的角色有，如银狼） */
    private val characterRefAudio: String?
        get() = appModule.cloneRefAudio(currentCharacter)

    private val characterRefAudioMime: String?
        get() = appModule.cloneRefAudioMime(currentCharacter)

    val config: StateFlow<ApiConfig> = configRepository.configFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = ApiConfig()
        )

    /**
     * 收到 LLM 表情标签。
     *
     * 只做记录 + 递增序号，实际下发与保持时长由 UI 层决定
     * （静默音频、页面不可见等情况不该由 ViewModel 猜）。
     */
    private fun cueExpression(expression: Live2DExpression) {
        // 首轮（开场问候）强制"普通脸"：
        // 实测 LLM 打招呼时几乎必然挑 `06 0.0`（圆眼圈嘴），而提示词里的招呼示例
        // 恰好就是 0.0 —— 等于我把它教成了每通电话开场都摆这个傻脸。
        // 开场白不需要额外表情演出，直接忽略标签、保持模型默认表情。
        // 标签在解析层已经被剥掉，所以忽略它也绝不会被念出来。
        if (isOpeningTurn()) {
            android.util.Log.d("CallVM", "首轮表情被忽略（强制普通脸）: ${expression.key}")
            return
        }
        expressionSeq += 1
        _expressionCue.value = ExpressionCue(expression.modelName, expressionSeq)
        android.util.Log.d("CallVM", "LLM 表情: ${expression.key} → ${expression.modelName}")
    }

    /**
     * 统一的流式回调装配。
     *
     * 三件事必须一起做，否则会出现「思考中转圈」与「流式文本」同时挂在屏幕上的重叠：
     * 1. [CallState.THINKING] 期间还**没有**可见文本 → 打开占位动画；
     * 2. 第一块可见文本到达 → 关掉占位，同一帧内接上流式文本（同一个气泡，无跳变）；
     * 3. 进入 SPEAKING（或本轮结束）→ 无论有没有文本都关掉占位，
     *    避免模型只输出标签/空回复时转圈永远停不下来。
     */
    private fun onState(state: CallState) {
        _callState.value = state
        if (state != CallState.THINKING) _isThinkingResponse.value = false
    }

    private fun onPartial(partial: String) {
        if (partial.isNotEmpty()) _isThinkingResponse.value = false
        _currentResponse.value = partial
    }

    /**
     * 本轮开始前清场：占位打开、流式文本清空、状态切到思考。
     *
     * 占位在**收到语音 / 点击发送的瞬间**就打开，而不是等 ASR 回来 —— ASR 本身也要时间，
     * 那段时间同样应该有个「在忙」的反馈。
     */
    private fun beginResponseTurn() {
        _currentResponse.value = ""
        _isThinkingResponse.value = true
        _callState.value = CallState.THINKING
    }

    private fun endResponseTurn() {
        _isThinkingResponse.value = false
        _currentResponse.value = ""
    }

    /**
     * 用户气泡提早上屏。
     *
     * 由 [ProcessAudioUseCase.onUserMessage] 在 ASR 出结果的瞬间回调，
     * 而不是等整轮结束再和助手回复一起追加 —— 否则屏幕上会先出现对方的回答，
     * 再出现自己刚说的话，顺序是反的。
     *
     * 幂等：`processAudio` 结束时返回的 userMessage 会再次走到这里，
     * 靠时间戳判断是否已经加过，避免同一条消息出现两个气泡。
     */
    private fun appendUserMessage(message: ChatMessage) {
        if (_messages.value.any { it.timestamp == message.timestamp && it.role == message.role }) return
        _messages.value = _messages.value + message
    }

    /** 是否处于本通电话的首轮
     *
     * 判据：消息列表里还没有任何助手消息。首轮生成期间列表仍然是空的 —— 助手消息
     * 要等 processAudio 返回后才写入（见各调用点），所以这个判据可靠；
     * 而 continueSession 是从历史会话续聊，列表非空，不会被误判成首轮。
     */
    private fun isOpeningTurn(): Boolean =
        _messages.value.none { it.role == "assistant" }

    /**
     * 开聊前装配记忆块（plan4 §4.1：`createSession()` 之后、`beginResponseTurn()` 之前）。
     *
     * 顺序不是随意的：记忆块必须在第一次 [beginResponseTurn] 之前就位，否则开场轮
     * 之后的那一轮（`processUserAudio` 读 [systemPrompt]）会拿到一个空的记忆块 ——
     * 表现为"第一通电话永远不带记忆"。
     *
     * 失败一律当作"没有记忆"：装配异常绝不该影响一通话能不能打起来。
     */
    private suspend fun loadMemoryPromptBlock(characterKey: String) {
        val injection = try {
            appModule.loadMemoryUseCase.loadForInjection(characterKey)
        } catch (e: Exception) {
            android.util.Log.e("CallVM", "装配长期记忆失败，本次不注入: ${e.message}")
            null
        }
        memoryPromptBlock = injection?.promptBlock
        if (injection == null) {
            android.util.Log.d("CallVM", "开聊: 无长期记忆可注入 character=$characterKey")
        }
    }

    /**
     * 实际下发给 LLM 的 system prompt = 角色提示词 + 记忆块。
     *
     * 只有这里会把两者拼起来：**数据库里的 `systemPrompt` 始终是干净的原始提示词**
     * （见 [memoryPromptBlock] 第 1 条纪律）。没记忆时原样返回，连多余的空行都没有。
     */
    private val effectiveSystemPrompt: String?
        get() {
            val base = systemPrompt
            val block = memoryPromptBlock
            if (block.isNullOrEmpty()) return base
            if (base.isNullOrEmpty()) return block
            return base + "\n" + block
        }

    fun continueSession(sessionId: String) {
        viewModelScope.launch {
            val session = manageSessionUseCase.getSession(sessionId)
            if (session != null) {
                currentSession = session
                // 记忆角色键只能从会话那一列读回来：续聊手里只有 sessionId，
                // 自定义预设靠提示词反查推不出来（plan4 §2.3 / P1）
                memoryCharacterKey = session.characterKey
                val currentConfig = configRepository.configFlow.first()
                // 续聊要恢复原角色的形象、提示词与发声策略。判据**优先用会话上那一列角色键**：
                // 它落库时就写明了"这通属于谁"，比拿提示词全文去和 assets 比对可靠 ——
                // 后者只要方案的提示词与某个内置角色逐字相同就会串味（用错头像/音色）。
                val preset = Session.presetIdFromCharacterKey(session.characterKey)
                    ?.let { appModule.presetDao.getPresetById(it) }
                if (preset != null) {
                    // 自定义方案：提示词 / 音色 / TTS 语气 / 头像背景全部从 presets 表那一行恢复。
                    // 缺了这一步，方案通话一续聊就退化成"裸模型 + 全局音色 + 银狼头像"。
                    restorePresetContext(preset)
                } else {
                    // 内置角色（或推不出角色的存量会话）：提示词是角色的决定性特征
                    // （会话表里没存角色 id，加字段要走 Room 迁移，收益不抵成本）
                    currentCharacter = matchCharacterByPrompt(session.systemPrompt)
                    systemPrompt = session.systemPrompt.ifEmpty { null }
                    // TTS 语气同样要恢复，口径与 startCharacterCall 一致：
                    // 准备页为该角色单独设的那一格 → 角色自带默认 → 全局兜底
                    currentTtsPrompt = currentCharacter?.let { c ->
                        currentConfig.getTtsPromptForCharacter(c.id, c.defaultTtsPrompt)
                            .ifEmpty { currentConfig.ttsPrompt }
                    } ?: currentConfig.ttsPrompt
                    _presetVisuals.value = null
                }
                // 续聊同样要带记忆（plan4 §4.1）。这里只是**追加**到本次请求，
                // 绝不写回 session —— 写回去就是每续一次长一截，越续越长。
                // 续聊没有"开场问候轮"（列表非空），所以这里装好就直接生效。
                loadMemoryPromptBlock(memoryCharacterKey)
                _messages.value = session.messages
                _callState.value = CallState.LISTENING
                startListening()
                // 这个角色的历史会话里可能还有没总结的（上通断网/被杀），后台补掉
                requestMemoryCatchUp(memoryCharacterKey)
            } else {
                android.util.Log.e("CallVM", "会话不存在: $sessionId")
                _callState.value = CallState.ENDED
            }
        }
    }

    /**
     * 装配一通**自定义方案**通话的上下文（开聊与续聊共用同一条路径）。
     *
     * 全部取自 `presets` 表那一行，而不是会话快照：方案是用户随时可改的数据，
     * 改了提示词/音色后续聊应当按**最新**的方案说话。（内置角色相反 ——
     * 它们的提示词是随版本发布的 assets 资产，续聊以库里那份为准，见 [continueSession]。）
     */
    private fun restorePresetContext(preset: PresetEntity) {
        // 自定义方案不属于任何内置角色：形象/表情/发声策略全部跟随设置与方案自身
        currentCharacter = null
        systemPrompt = preset.prompt.ifEmpty { null }
        presetRefAudioBase64 = preset.refAudioBase64
        presetRefAudioMime = preset.refAudioMime
        currentTtsPrompt = preset.ttsPrompt
        _presetVisuals.value = PresetVisuals(preset.avatarUri, preset.backgroundUri)
    }

    /** 按会话的系统提示词反查内置角色（匹配不上返回 null，即当作自定义会话） */
    private suspend fun matchCharacterByPrompt(prompt: String): BuiltInCharacter? {
        if (prompt.isBlank()) return null
        for (c in BuiltInCharacters.ALL) {
            val asset = try {
                application.assets.open(c.promptAsset).bufferedReader().use { it.readText() }
            } catch (e: Exception) {
                continue
            }
            if (asset == prompt) return c
        }
        return null
    }

    /**
     * 使用内置角色开始通话（首页「内置预设」入口）。
     *
     * 提示词、表情集、发声策略、参考音频全部来自 [BuiltInCharacter]，
     * 因此银狼与 DeepSeek 酱走的是**同一条代码路径**，区别只在数据。
     */
    fun startCharacterCall(characterId: String) {
        val character = BuiltInCharacters.byId(characterId)
        if (character == null) {
            android.util.Log.e("CallVM", "未知内置角色: $characterId")
            _callState.value = CallState.ENDED
            return
        }
        viewModelScope.launch {
            currentCharacter = character
            // 自定义预设的残留要清掉，否则会串到内置角色上
            presetRefAudioBase64 = null
            presetRefAudioMime = null
            _presetVisuals.value = null
            // 内置角色的记忆桶 = 角色 id（plan4 §2.3 第一档）
            memoryCharacterKey = character.id
            currentSession = startCallUseCase.createSession(DialogMode.LONG, character, memoryCharacterKey)
            systemPrompt = currentSession?.systemPrompt
            // 记忆块就位后再开轮（plan4 §4.1）；开场问候轮仍然用原始 systemPrompt
            loadMemoryPromptBlock(memoryCharacterKey)
            _messages.value = emptyList()

            val currentConfig = configRepository.configFlow.first()
            if (currentConfig.asrProvider == "vosk") {
                val asrEngine = appModule.asrEngine
                val modelId = currentConfig.asrVoskModelId.ifEmpty { "vosk-model-small-cn-0.22" }
                if (!asrEngine.initVoskModel(modelId)) {
                    _callState.value = CallState.ENDED
                    return@launch
                }
            }

            // TTS 风格提示词：准备页为该角色单独设的那一格 → 角色自带默认 → 全局兜底。
            // ⚠️ 中间那一档（characterTtsPrompts）以前漏读了：准备页写进去、通话却只读
            // defaultTtsPrompt，于是"改了没反应"；银狼（默认语气为空）还会回落到**全局**
            // ttsPrompt，等于把别的角色的语气串过来。
            currentTtsPrompt = currentConfig.getTtsPromptForCharacter(character.id, character.defaultTtsPrompt)
                .ifEmpty { currentConfig.ttsPrompt }

            beginResponseTurn()
            try {
                val (userMsg, assistantMsg) = processAudioUseCase.processAudio(
                    pcmData = ByteArray(0),
                    systemPrompt = systemPrompt,
                    history = emptyList(),
                    isAutoGreeting = true,
                    autoGreetingText = "你好",
                    // 角色自带音色只作为兜底：用户在准备页里选的音频优先级更高
                    fallbackRefAudioBase64 = characterRefAudio,
                    fallbackRefAudioMime = characterRefAudioMime,
                    ttsPrompt = currentTtsPrompt,
                    expressions = currentExpressions,
                    ttsPolicy = currentTtsPolicy,
                    onStateChange = { state -> onState(state) },
                    onUserMessage = ::appendUserMessage,
                    onPartialResponse = { partial -> onPartial(partial) },
                    onExpression = ::cueExpression
                )
                val newMessages = mutableListOf(userMsg)
                if (assistantMsg != null) newMessages.add(assistantMsg)
                _messages.value = newMessages
                endResponseTurn()
                currentSession?.let { session ->
                    manageSessionUseCase.saveCallMessages(session.id, _messages.value)
                }
            } catch (e: Exception) {
                android.util.Log.e("CallVM", "角色打招呼失败(${character.id}): ${e.message}")
                _callState.value = CallState.ENDED
                return@launch
            }

            _callState.value = CallState.LISTENING
            startListening()
            requestMemoryCatchUp(memoryCharacterKey)
        }
    }

    /** 使用预设开始通话（从PresetDao加载数据） */
    fun startPresetCall(presetId: Long) {
        viewModelScope.launch {
            val preset = appModule.presetDao.getPresetById(presetId)
            if (preset != null) {
                // 提示词 / 音频 / TTS 语气 / 头像背景全部来自方案本身（不污染全局配置）。
                // 与续聊共用同一条装配路径，保证"开聊"与"续聊"看到的方案完全一致。
                restorePresetContext(preset)
                // 自定义预设的记忆隔离键：只有这条路径知道 presetId，
                // 续聊时靠 session 上那一列读回来（plan4 §2.3 / P1）
                memoryCharacterKey = Session.presetCharacterKey(presetId)
                currentSession = startCallUseCase.createSession(
                    mode = DialogMode.CUSTOM,
                    character = null,
                    characterKey = memoryCharacterKey,
                    // ⚠️ 方案提示词必须真的落进 sessions.systemPrompt。只放内存字段的话，
                    // 通话中看着正常，一旦「继续对话」从库里读回的就是空串 —— 人设全丢。
                    systemPromptOverride = preset.prompt
                )
                // 以库里那一份为准：保证"本次请求用的"与"续聊读回的"是同一个字符串
                systemPrompt = currentSession?.systemPrompt?.ifEmpty { null }
                // 预设桶的记忆在开轮前就位（plan4 §4.1）；开场问候轮用原始 systemPrompt
                loadMemoryPromptBlock(memoryCharacterKey)
                _messages.value = emptyList()

                // 如果使用 Vosk，初始化模型
                val currentConfig = configRepository.configFlow.first()
                if (currentConfig.asrProvider == "vosk") {
                    val asrEngine = appModule.asrEngine
                    val modelId = currentConfig.asrVoskModelId.ifEmpty { "vosk-model-small-cn-0.22" }
                    if (!asrEngine.initVoskModel(modelId)) {
                        _callState.value = CallState.ENDED
                        return@launch
                    }
                }

                // 发送打招呼
                beginResponseTurn()
                try {
                    val (userMsg, assistantMsg) = processAudioUseCase.processAudio(
                        pcmData = ByteArray(0),
                        systemPrompt = systemPrompt,
                        history = emptyList(),
                        isAutoGreeting = true,
                        autoGreetingText = "你好",
                        overrideRefAudioBase64 = preset.refAudioBase64,
                        overrideRefAudioMime = preset.refAudioMime,
                        ttsPrompt = currentTtsPrompt,
                        expressions = currentExpressions,
                        ttsPolicy = currentTtsPolicy,
                        onStateChange = { state -> onState(state) },
                        onUserMessage = ::appendUserMessage,
                        onPartialResponse = { partial -> onPartial(partial) },
                        onExpression = ::cueExpression
                    )
                    val newMessages = mutableListOf(userMsg)
                    if (assistantMsg != null) newMessages.add(assistantMsg)
                    _messages.value = newMessages
                    endResponseTurn()
                    currentSession?.let { session ->
                        manageSessionUseCase.saveCallMessages(session.id, _messages.value)
                    }
                } catch (e: Exception) {
                    android.util.Log.e("CallVM", "预设打招呼失败: ${e.message}")
                    _callState.value = CallState.ENDED
                    return@launch
                }

                _callState.value = CallState.LISTENING
                startListening()
                requestMemoryCatchUp(memoryCharacterKey)
            } else {
                android.util.Log.e("CallVM", "预设不存在: $presetId")
                _callState.value = CallState.ENDED
            }
        }
    }

    private fun startListening() {
        if (_callState.value == CallState.ENDED) return
        // 回到聆听 = 本轮彻底结束：占位动画与流式文本一起收干净。
        // 放在这个唯一入口上，是为了不依赖每个调用点都记得清理
        // （VAD 静音、超时、异常、静音键恢复……都从这里回聆听）。
        endResponseTurn()

        // 启动监听超时（30秒无响应自动重启，防止VAD卡死）
        listeningTimeoutJob?.cancel()
        listeningTimeoutJob = viewModelScope.launch {
            kotlinx.coroutines.delay(30_000L)
            if (_callState.value == CallState.LISTENING && !isProcessing) {
                android.util.Log.w("CallVM", "监听超时，自动重启录音")
                startListening()
            }
        }

        audioRecorder.startRecording(
            onSpeechEnd = { pcmData ->
                listeningTimeoutJob?.cancel()
                if (!isProcessing) {
                    isProcessing = true
                    // 用户说完的瞬间就挂上「思考中」占位：ASR 也要时间，
                    // 这段空白同样需要一个「在忙」的反馈（plan1 一-1 的起点）。
                    beginResponseTurn()
                    processUserAudio(pcmData)
                }
            },
            onSilence = {
                if (_callState.value != CallState.ENDED) {
                    _callState.value = CallState.LISTENING
                    viewModelScope.launch { startListening() }
                }
            }
        )
    }

    private fun processUserAudio(pcmData: ByteArray) {
        viewModelScope.launch {
            try {
                // 这里刻意不再用 withTimeoutOrNull 包住整轮：
                // 整轮耗时由各组成部分自己的超时兜底（ASR / HTTP 走 OkHttp 超时，
                // TTS 朗读走设置页可调的「朗读超时」，见 ProcessAudioUseCase）。外层再套一个
                // 更短的整体超时只会在超时点取消协程，而播放跑在 AudioPlayer 自己的
                // scope 里不会随之停下 —— 结果就是麦克风开着去录 AI 还在播的声音
                // （自听自说，AI 会回应自己刚说的话）。
                val (userMessage, assistantMessage) = processAudioUseCase.processAudio(
                    pcmData = pcmData,
                    // 从第二轮起才带记忆（plan4 §4.1）：开场问候轮走的是 systemPrompt 本体，
                    // 这里读的是"提示词 + 记忆块"的合成体。
                    systemPrompt = effectiveSystemPrompt,
                    history = _messages.value,
                    // 预设音频优先级最高；内置角色音色作为兜底
                    overrideRefAudioBase64 = presetRefAudio,
                    overrideRefAudioMime = presetRefAudioMimeValue,
                    fallbackRefAudioBase64 = characterRefAudio,
                    fallbackRefAudioMime = characterRefAudioMime,
                    ttsPrompt = currentTtsPrompt,
                    expressions = currentExpressions,
                    ttsPolicy = currentTtsPolicy,
                    onStateChange = { state -> onState(state) },
                    onUserMessage = ::appendUserMessage,
                    onPartialResponse = { partial -> onPartial(partial) },
                    onExpression = ::cueExpression
                )
                // userMessage 在 ASR 出来时就已上屏（见 appendUserMessage），
                // 这里只补助手回复；用时间戳去重，防止重复气泡。
                val newMessages = mutableListOf<ChatMessage>()
                if (_messages.value.none { it.timestamp == userMessage.timestamp && it.role == userMessage.role }) {
                    newMessages.add(userMessage)
                }
                if (assistantMessage != null) newMessages.add(assistantMessage)
                if (newMessages.isNotEmpty()) _messages.value = _messages.value + newMessages
                endResponseTurn()
                currentSession?.let { session ->
                    manageSessionUseCase.saveCallMessages(session.id, _messages.value)
                }
            } catch (e: AsrEmptyException) {
                // 没听清：不写任何消息（否则历史里会多一条假发言），
                // 只把状态收回聆听让用户重说，并给一个短暂提示
                android.util.Log.w("CallVM", "语音识别为空，等待用户重说")
                endResponseTurn()
                _asrRetryHint.value = _asrRetryHint.value + 1
            } catch (e: Exception) {
                endResponseTurn()
            } finally {
                isProcessing = false
                if (_callState.value != CallState.ENDED && !_isMuted.value) {
                    _callState.value = CallState.LISTENING
                    startListening()
                } else if (_callState.value != CallState.ENDED) {
                    _callState.value = CallState.LISTENING
                }
            }
        }
    }

    /** 文字输入发送（跳过ASR，直接调LLM+TTS） */
    fun sendTextMessage(text: String) {
        if (text.isBlank() || _callState.value == CallState.ENDED || isProcessing) return
        isProcessing = true
        audioRecorder.stopRecording() // 停止录音，避免与文字输入冲突
        beginResponseTurn()
        viewModelScope.launch {
            try {
                val (userMsg, assistantMsg) = processAudioUseCase.processAudio(
                    pcmData = ByteArray(0),
                    // 文字输入不是"开场问候"（isAutoGreeting 在这里只是"跳过 ASR、直接用这段文字"
                    // 的开关），所以照常带记忆（plan4 §4.1 只豁免问候轮）
                    systemPrompt = effectiveSystemPrompt,
                    history = _messages.value,
                    isAutoGreeting = true,
                    autoGreetingText = text,
                    // 预设音频优先级最高；内置角色音色作为兜底
                    overrideRefAudioBase64 = presetRefAudio,
                    overrideRefAudioMime = presetRefAudioMimeValue,
                    fallbackRefAudioBase64 = characterRefAudio,
                    fallbackRefAudioMime = characterRefAudioMime,
                    ttsPrompt = currentTtsPrompt,
                    expressions = currentExpressions,
                    ttsPolicy = currentTtsPolicy,
                    onStateChange = { state -> onState(state) },
                    onUserMessage = ::appendUserMessage,
                    onPartialResponse = { partial -> onPartial(partial) },
                    onExpression = ::cueExpression
                )
                // 用户消息已在上面的回调里提早上屏，这里只补助手回复
                val newMessages = mutableListOf<ChatMessage>()
                if (assistantMsg != null) newMessages.add(assistantMsg)
                if (newMessages.isNotEmpty()) _messages.value = _messages.value + newMessages
                endResponseTurn()
                currentSession?.let { session -> manageSessionUseCase.saveCallMessages(session.id, _messages.value) }
            } catch (e: Exception) {
                endResponseTurn()
            } finally {
                isProcessing = false
                if (_callState.value != CallState.ENDED && !_isMuted.value) {
                    _callState.value = CallState.LISTENING
                    startListening()
                } else if (_callState.value != CallState.ENDED) {
                    _callState.value = CallState.LISTENING
                }
            }
        }
    }

    fun hangUp() {
        _callState.value = CallState.ENDED
        endResponseTurn()
        listeningTimeoutJob?.cancel()
        audioRecorder.stopRecording()
        audioPlayer.stopCurrentPlayback()

        // 挂断这一刻把"这通电话"的快照钉死：
        // · messages 传值（不是等协程里去读 _messages.value）—— 它们本来就是挂断时刻的完整列表，
        //   而总结任务真正跑起来时 ViewModel 可能早就没了；
        // · P3 的判据也必须在**此刻**取：等进了协程 isProcessing 可能已经被 finally 复位成 false，
        //   那就变成"给一段被打断的对话写记忆"了。
        val session = currentSession
        val messages = _messages.value
        val characterKey = memoryCharacterKey
        val interrupted = isProcessing

        // 不能再用 viewModelScope：挂断后 NavGraph 立刻跳历史页 → onCleared() 会把这个协程连根取消，
        // 最后一轮消息就永远不落库（既有 bug，也是 plan4 §5.2(b) 的顺序坑）。改挂 Application 级 scope。
        appModule.applicationScope.launch {
            val sessionId = session?.id
            // 落库回传的真实 rowId，与 messages 同序 —— 总结的游标必须是真实 messages.id，
            // 用列表下标会与补总结查询的坐标系错位（见 MessageDao.insertMessages 的说明）。
            var messageIds: List<Long> = emptyList()
            if (sessionId != null && messages.isNotEmpty()) {
                // NonCancellable：Application scope 虽然在 ViewModel 之外，
                // 但落库这一步仍然不该被任何取消打断 —— 它是"先落库、再总结"里的前半句。
                withContext(NonCancellable) {
                    messageIds = manageSessionUseCase.saveCallMessages(sessionId, messages)
                }
            }
            if (sessionId == null || interrupted) {
                android.util.Log.d(
                    "CallVM",
                    "挂断: 跳过总结 session=${sessionId?.take(8)} interrupted=$interrupted" +
                        "（消息已落库，交给下次开聊的补总结）"
                )
                return@launch
            }
            requestMemorySummary(sessionId, characterKey, messages, messageIds)
        }
    }

    /**
     * 挂断时触发一次记忆总结（plan4 §5.2 / §5.3）。
     *
     * 三条实现约束都在这里落地：
     * 1. **直接用内存里的消息**（§5.2(b) 的推荐做法）：挂断那一刻这个列表已经是完整的，
     *    不必等数据库写完再读回来。"先落库、再总结"的竞态（读到旧消息 → 写出一条残缺记忆
     *    并把游标推到底 → 这段对话永久总结不全）就此彻底绕开。补总结那条路径才需要读库。
     * 2. 闸门只放行一次（§5.3 第一级）：重复调用直接跳过。
     * 3. 总开关关掉时**连写都不写**（§5.7）：不读不写，已有记忆原样保留。
     */
    private suspend fun requestMemorySummary(
        sessionId: String,
        characterKey: String,
        messages: List<ChatMessage>,
        messageIds: List<Long>
    ) {
        if (summaryRequested) {
            android.util.Log.d("CallVM", "挂断: 总结闸门已放行过，跳过重复请求 session=${sessionId.take(8)}")
            return
        }
        if (!isMemoryEnabled()) {
            android.util.Log.d("CallVM", "挂断: 长期记忆总开关关闭 → 不总结 session=${sessionId.take(8)}")
            return
        }
        summaryRequested = true
        android.util.Log.d(
            "CallVM",
            "挂断: 触发总结 session=${sessionId.take(8)} character=$characterKey " +
                "内存消息=${messages.size} rowId=${messageIds.size}"
        )
        // 超时 = 失败 = 游标不动，内容会在下次开聊的补总结里被捡回来。
        val result = withTimeoutOrNull(AUTO_SUMMARY_TIMEOUT_MS) {
            appModule.summarizeMemoryUseCase.summarizeFromMemory(
                sessionId = sessionId,
                characterId = characterKey,
                messages = messages,
                messageIds = messageIds
            )
        }
        android.util.Log.d("CallVM", "挂断总结结果: ${result ?: "等待超时(Mutex 未获取)"}")
    }

    /**
     * 开聊时后台补总结（plan4 §5.5，必做项）。
     *
     * 三个丢记忆的场景都由它兜底：挂断时断网、进程被杀/崩溃、通话卡死被回收 ——
     * 那些通话的消息已经落库（每轮成功路径都存过），只是没被总结。
     *
     * 三道闸缺一不可：
     * · 总开关关掉 → 完全不补（§5.7「不读不写」）；
     * · 跳过 createdAt 距今 < [CATCHUP_MIN_SESSION_AGE_MS] 的会话 —— 它很可能就是**当前这一通**
     *   （会话是开聊时刚建的），不排除的话就是"自己总结自己"；
     * · 只取最近 [MAX_CATCHUP_SESSIONS] 通（DAO 已按 createdAt 倒序），补的时候按时间正序串行。
     *
     * 必须用 applicationScope：这个任务是"顺手补作业"，不该因为用户立刻挂断/离开而半途被取消。
     * 串行与幂等由 [SummarizeMemoryUseCase] 内部的 Mutex 保证；失败就停在那一条，下次继续。
     */
    private fun requestMemoryCatchUp(characterKey: String) {
        appModule.applicationScope.launch {
            if (!isMemoryEnabled()) {
                android.util.Log.d("CallVM", "补总结: 长期记忆总开关关闭 → 跳过 character=$characterKey")
                return@launch
            }
            val pending = appModule.sessionRepository.getSessionsWithPendingMemory(characterKey)
            val now = System.currentTimeMillis()
            val backlog = pending
                .filter { now - it.createdAt >= CATCHUP_MIN_SESSION_AGE_MS }
                .take(MAX_CATCHUP_SESSIONS)
                .asReversed() // 倒序取、正序补：时间顺序不能反，否则后一条记忆会缺上下文
            if (backlog.isEmpty()) {
                android.util.Log.d("CallVM", "补总结: 无待整理会话 character=$characterKey（候选=${pending.size}）")
                return@launch
            }
            android.util.Log.d(
                "CallVM",
                "补总结: 待整理=${backlog.size} character=$characterKey（候选=${pending.size}，最多补 $MAX_CATCHUP_SESSIONS 通）"
            )
            for (session in backlog) {
                // 失败（网络/超时/输出非法）就停在这里 —— 继续往下补只会把后面的也一起打挂
                if (!isActive) return@launch
                val result = appModule.summarizeMemoryUseCase.summarize(sessionId = session.id)
                android.util.Log.d("CallVM", "补总结: session=${session.id.take(8)} → $result")
                if (result is SummarizeMemoryUseCase.SummarizeResult.Failed) {
                    android.util.Log.w("CallVM", "补总结: 失败即停（游标未动，下次开聊继续）")
                    return@launch
                }
            }
        }
    }

    /**
     * 长期记忆总开关（§5.7）。
     *
     * 读配置失败时按**关闭**处理：拿不准的状态下"不写用户画像"比"悄悄写一条"更安全。
     */
    private suspend fun isMemoryEnabled(): Boolean = try {
        configRepository.configFlow.first().memoryAutoSummarizeEnabled
    } catch (e: Exception) {
        android.util.Log.e("CallVM", "读取长期记忆开关失败，按关闭处理: ${e.message}")
        false
    }

    fun toggleMute() {
        _isMuted.value = !_isMuted.value
        if (_isMuted.value) {
            audioRecorder.stopRecording()
        } else if (_callState.value == CallState.LISTENING) {
            startListening()
        }
    }

    fun getSessionId(): String? = currentSession?.id

    override fun onCleared() {
        super.onCleared()
        audioRecorder.release()
        // audioPlayer是共享单例，只停止播放不释放资源
        audioPlayer.stopCurrentPlayback()
        presetRefAudioBase64 = null
        presetRefAudioMime = null
        _presetVisuals.value = null
    }

    class Factory(
        private val appModule: AppModule,
        private val application: android.app.Application
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return CallViewModel(appModule, application) as T
        }
    }

    private companion object {
        /** 挂断总结的等待上限：比用例内部的 30s 略宽，只防 Mutex 长时间抢不到 */
        const val AUTO_SUMMARY_TIMEOUT_MS = 35_000L

        /**
         * 补总结跳过「太新」的会话（plan4 §5.5）。
         *
         * 会话是在**开聊那一刻**建好的，所以当前这通电话必然落在 1 分钟以内 ——
         * 不排除它就是"自己总结自己"，而那时助手连开场问候都还没说完。
         */
        const val CATCHUP_MIN_SESSION_AGE_MS = 60_000L

        /** 一次开聊最多补几通（plan4 §5.5）；配合迁移里"存量游标初始化到末尾"才是完整的闸 */
        const val MAX_CATCHUP_SESSIONS = 3
    }
}
