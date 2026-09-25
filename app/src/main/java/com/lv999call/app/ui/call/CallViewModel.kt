package com.lv999call.app.ui.call

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lv999call.app.audio.AudioPlayer
import com.lv999call.app.audio.AudioRecorder
import com.lv999call.app.di.AppModule
import com.lv999call.app.domain.model.*
import com.lv999call.app.domain.usecase.ManageSessionUseCase
import com.lv999call.app.domain.usecase.ProcessAudioUseCase
import com.lv999call.app.domain.usecase.StartCallUseCase
import com.lv999call.app.preset.BuiltInCharacters
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

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
     * 当前通话的内置角色（自定义预设通话时为 null）。
     *
     * UI 靠它决定 Live2D 模型路径与 profile、静态头像、背景图、署名与过场开关 ——
     * 这样 [CallScreen] 不需要知道"银狼"或"DeepSeek 酱"是谁，只认这个描述对象。
     */
    private val _character = MutableStateFlow<BuiltInCharacter?>(null)
    val character: StateFlow<BuiltInCharacter?> = _character.asStateFlow()

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
    private var currentMode: DialogMode = DialogMode.QUICK

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

    fun startCall(mode: DialogMode) {
        viewModelScope.launch {
            currentMode = mode
            currentCharacter = null
            currentSession = startCallUseCase.createSession(mode, null)
            systemPrompt = currentSession?.systemPrompt
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

            // 自动发送"你好"发起对话
            currentTtsPrompt = currentConfig.ttsPrompt
            beginResponseTurn()
            val greetingPcm = ByteArray(0) // 空音频，跳过ASR
            try {
                val (userMsg, assistantMsg) = processAudioUseCase.processAudio(
                    pcmData = greetingPcm,
                    systemPrompt = systemPrompt,
                    history = emptyList(),
                    mode = currentMode,
                    isAutoGreeting = true,
                    autoGreetingText = "你好",
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
                android.util.Log.e("CallVM", "打招呼失败: ${e.message}")
                _callState.value = CallState.ENDED
                return@launch
            }

            // 进入监听状态
            _callState.value = CallState.LISTENING
            startListening()
        }
    }

    fun continueSession(sessionId: String) {
        viewModelScope.launch {
            val session = manageSessionUseCase.getSession(sessionId)
            if (session != null) {
                currentMode = session.mode
                currentSession = session
                systemPrompt = session.systemPrompt
                // 续聊要恢复原角色的形象与发声策略。
                // 判据是提示词内容与内置角色的提示词一致 —— 会话表里没存角色 id
                // （加字段要走 Room 迁移，收益不抵成本），而提示词是角色的决定性特征。
                currentCharacter = matchCharacterByPrompt(session.systemPrompt)
                _messages.value = session.messages
                _callState.value = CallState.LISTENING
                startListening()
            } else {
                android.util.Log.e("CallVM", "会话不存在: $sessionId")
                _callState.value = CallState.ENDED
            }
        }
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
            currentMode = DialogMode.LONG
            currentCharacter = character
            // 自定义预设的残留要清掉，否则会串到内置角色上
            presetRefAudioBase64 = null
            presetRefAudioMime = null
            currentSession = startCallUseCase.createSession(DialogMode.LONG, character)
            systemPrompt = currentSession?.systemPrompt
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

            // TTS 风格提示词：角色有默认值就用角色的，否则跟随全局设置
            currentTtsPrompt = character.defaultTtsPrompt.ifEmpty { currentConfig.ttsPrompt }

            beginResponseTurn()
            try {
                val (userMsg, assistantMsg) = processAudioUseCase.processAudio(
                    pcmData = ByteArray(0),
                    systemPrompt = systemPrompt,
                    history = emptyList(),
                    mode = currentMode,
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
        }
    }

    /** 使用预设开始通话（从PresetDao加载数据） */
    fun startPresetCall(presetId: Long) {
        viewModelScope.launch {
            val preset = appModule.presetDao.getPresetById(presetId)
            if (preset != null) {
                // 自定义预设不属于任何内置角色：形象/表情/发声全部跟随设置与预设自身
                currentCharacter = null
                // 使用预设的提示词和音频
                systemPrompt = preset.prompt.ifEmpty { null }
                currentMode = DialogMode.CUSTOM
                currentSession = startCallUseCase.createSession(DialogMode.CUSTOM, null)
                _messages.value = emptyList()

                // 保存预设音频到ViewModel本地字段，不污染全局配置
                presetRefAudioBase64 = preset.refAudioBase64
                presetRefAudioMime = preset.refAudioMime
                currentTtsPrompt = preset.ttsPrompt

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
                        mode = currentMode,
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
                // 整体超时保护（60秒），防止ASR/LLM/TTS任一步骤卡死
                val result = kotlinx.coroutines.withTimeoutOrNull(60_000L) {
                    processAudioUseCase.processAudio(
                        pcmData = pcmData,
                        systemPrompt = systemPrompt,
                        history = _messages.value,
                        mode = currentMode,
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
                }
                if (result != null) {
                    val (userMessage, assistantMessage) = result
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
                } else {
                    android.util.Log.w("CallVM", "处理音频超时")
                    endResponseTurn()
                }
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
                    systemPrompt = systemPrompt,
                    history = _messages.value,
                    mode = currentMode,
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

        viewModelScope.launch {
            currentSession?.let { session ->
                if (_messages.value.isNotEmpty()) {
                    manageSessionUseCase.saveCallMessages(session.id, _messages.value)
                }
            }
        }
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
}
