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

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

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
    private var systemPrompt: String? = null
    @Volatile
    private var isProcessing = false
    // 监听超时Job，防止VAD卡死导致UI永久停在"聆听"
    private var listeningTimeoutJob: kotlinx.coroutines.Job? = null
    // 预设专用的TTS参考音频（不污染全局配置）
    private var presetRefAudioBase64: String? = null
    private var presetRefAudioMime: String? = null
    // 当前通话使用的TTS提示词（银狼模式用config，自定义模式用preset）
    private var currentTtsPrompt: String = ""

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
     * 是否是本通电话的首轮
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
            currentSession = startCallUseCase.createSession(mode)
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
            _callState.value = CallState.THINKING
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
                    onStateChange = { state -> _callState.value = state },
                    onPartialResponse = { partial -> _currentResponse.value = partial },
                    onExpression = ::cueExpression
                )

                val newMessages = mutableListOf(userMsg)
                if (assistantMsg != null) newMessages.add(assistantMsg)
                _messages.value = newMessages
                _currentResponse.value = ""

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
                _messages.value = session.messages
                _callState.value = CallState.LISTENING
                startListening()
            } else {
                android.util.Log.e("CallVM", "会话不存在: $sessionId")
                _callState.value = CallState.ENDED
            }
        }
    }

    /** 使用预设开始通话（从PresetDao加载数据） */
    fun startPresetCall(presetId: Long) {
        viewModelScope.launch {
            val preset = appModule.presetDao.getPresetById(presetId)
            if (preset != null) {
                // 使用预设的提示词和音频
                systemPrompt = preset.prompt.ifEmpty { null }
                currentMode = DialogMode.CUSTOM
                currentSession = startCallUseCase.createSession(DialogMode.CUSTOM)
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
                _callState.value = CallState.THINKING
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
                        onStateChange = { state -> _callState.value = state },
                        onPartialResponse = { partial -> _currentResponse.value = partial },
                        onExpression = ::cueExpression
                    )
                    val newMessages = mutableListOf(userMsg)
                    if (assistantMsg != null) newMessages.add(assistantMsg)
                    _messages.value = newMessages
                    _currentResponse.value = ""
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
                        overrideRefAudioBase64 = presetRefAudioBase64,
                        overrideRefAudioMime = presetRefAudioMime,
                        ttsPrompt = currentTtsPrompt,
                        onStateChange = { state -> _callState.value = state },
                        onPartialResponse = { partial -> _currentResponse.value = partial },
                        onExpression = ::cueExpression
                    )
                }
                if (result != null) {
                    val (userMessage, assistantMessage) = result
                    val newMessages = mutableListOf(userMessage)
                    if (assistantMessage != null) newMessages.add(assistantMessage)
                    _messages.value = _messages.value + newMessages
                    _currentResponse.value = ""
                    currentSession?.let { session ->
                        manageSessionUseCase.saveCallMessages(session.id, _messages.value)
                    }
                } else {
                    android.util.Log.w("CallVM", "处理音频超时")
                    _currentResponse.value = ""
                }
            } catch (e: Exception) {
                _currentResponse.value = ""
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
        viewModelScope.launch {
            try {
                val (userMsg, assistantMsg) = processAudioUseCase.processAudio(
                    pcmData = ByteArray(0),
                    systemPrompt = systemPrompt,
                    history = _messages.value,
                    mode = currentMode,
                    isAutoGreeting = true,
                    autoGreetingText = text,
                    overrideRefAudioBase64 = presetRefAudioBase64,
                    overrideRefAudioMime = presetRefAudioMime,
                    ttsPrompt = currentTtsPrompt,
                    onStateChange = { state -> _callState.value = state },
                    onPartialResponse = { partial -> _currentResponse.value = partial },
                    onExpression = ::cueExpression
                )
                val newMessages = mutableListOf(userMsg)
                if (assistantMsg != null) newMessages.add(assistantMsg)
                _messages.value = _messages.value + newMessages
                _currentResponse.value = ""
                currentSession?.let { session -> manageSessionUseCase.saveCallMessages(session.id, _messages.value) }
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
