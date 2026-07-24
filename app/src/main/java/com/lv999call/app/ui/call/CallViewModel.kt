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

    private var currentSession: Session? = null
    private var currentMode: DialogMode = DialogMode.QUICK
    private var systemPrompt: String? = null
    @Volatile
    private var isProcessing = false

    val config: StateFlow<ApiConfig> = configRepository.configFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = ApiConfig()
        )

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
            _callState.value = CallState.THINKING
            val greetingPcm = ByteArray(0) // 空音频，跳过ASR
            val (userMsg, assistantMsg) = processAudioUseCase.processAudio(
                pcmData = greetingPcm,
                systemPrompt = systemPrompt,
                history = emptyList(),
                mode = currentMode,
                isAutoGreeting = true,
                autoGreetingText = "你好",
                onStateChange = { state -> _callState.value = state },
                onPartialResponse = { partial -> _currentResponse.value = partial }
            )

            val newMessages = mutableListOf(userMsg)
            if (assistantMsg != null) newMessages.add(assistantMsg)
            _messages.value = newMessages
            _currentResponse.value = ""

            currentSession?.let { session ->
                manageSessionUseCase.saveCallMessages(session.id, _messages.value)
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

                // 将预设音频临时覆盖到config中
                val currentConfig = configRepository.configFlow.first()
                configRepository.saveConfig(currentConfig.copy(
                    customTtsReferenceAudioBase64 = preset.refAudioBase64,
                    customTtsReferenceAudioMime = preset.refAudioMime
                ))

                _callState.value = CallState.LISTENING
                startListening()
            }
        }
    }

    private fun startListening() {
        if (_callState.value == CallState.ENDED) return

        audioRecorder.startRecording(
            onSpeechEnd = { pcmData ->
                if (!isProcessing) {
                    isProcessing = true
                    processUserAudio(pcmData)
                }
            },
            onSilence = {
                if (_callState.value != CallState.ENDED) {
                    _callState.value = CallState.LISTENING
                    startListening()
                }
            }
        )
    }

    private fun processUserAudio(pcmData: ByteArray) {
        viewModelScope.launch {
            try {
                val (userMessage, assistantMessage) = processAudioUseCase.processAudio(
                    pcmData = pcmData,
                    systemPrompt = systemPrompt,
                    history = _messages.value,
                    mode = currentMode,
                    onStateChange = { state -> _callState.value = state },
                    onPartialResponse = { partial -> _currentResponse.value = partial }
                )

                // 始终添加用户消息
                val newMessages = mutableListOf(userMessage)
                if (assistantMessage != null) newMessages.add(assistantMessage)

                _messages.value = _messages.value + newMessages
                _currentResponse.value = ""

                currentSession?.let { session ->
                    manageSessionUseCase.saveCallMessages(session.id, _messages.value)
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
                    onStateChange = { state -> _callState.value = state },
                    onPartialResponse = { partial -> _currentResponse.value = partial }
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
