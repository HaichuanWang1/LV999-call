package com.lv999call.app.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import com.lv999call.app.ui.call.HANGUP_TRANSFORM_MS
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.lv999call.app.App
import com.lv999call.app.domain.model.CallState
import com.lv999call.app.domain.model.DialogMode
import com.lv999call.app.preset.BuiltInCharacters
import com.lv999call.app.ui.call.CallScreen
import com.lv999call.app.ui.call.CallViewModel
import com.lv999call.app.ui.custom.CustomEditScreen
import com.lv999call.app.ui.custom.PresetViewModel
import com.lv999call.app.ui.history.HistoryScreen
import com.lv999call.app.ui.history.HistoryViewModel
import com.lv999call.app.ui.home.HomeScreen
import com.lv999call.app.ui.memory.MemoryRoute
import com.lv999call.app.ui.memory.MemoryViewModel
import com.lv999call.app.ui.prepare.PrepareScreen
import com.lv999call.app.ui.settings.SettingsScreen
import com.lv999call.app.ui.settings.SettingsViewModel

object Routes {
    const val HOME = "home"

    /**
     * 内置角色的准备页 / 通话页。
     *
     * 用**一个参数化路由**承载所有内置角色，而不是每个角色各写一条
     * （原来是 SILVERWOLF_PREPARE / SILVERWOLF_CALL 两条专属路由）——
     * 每加一个角色就多两条路由，NavGraph 会线性膨胀且到处是重复代码。
     * 角色差异全部由 [BuiltInCharacters] 的数据承载。
     */
    const val CHARACTER_PREPARE = "character_prepare/{characterId}"
    const val CHARACTER_CALL = "character_call/{characterId}"

    const val PRESET_EDIT = "preset_edit/{presetId}"
    const val PRESET_CALL = "preset_call/{presetId}"
    const val CALL_CONTINUE = "call_continue/{sessionId}"
    const val HISTORY = "history/{sessionId}"
    const val SETTINGS = "settings"

    /** 记忆库（plan4 §6.1）：独立页面而不是弹窗，记忆条目需要真正的列表区域 */
    const val MEMORY = "memory"

    fun characterPrepare(id: String) = "character_prepare/$id"
    fun characterCall(id: String) = "character_call/$id"
    fun presetEdit(id: Long) = "preset_edit/$id"
    fun presetCall(id: Long) = "preset_call/$id"
    fun callContinue(sessionId: String) = "call_continue/$sessionId"
    fun history(sessionId: String) = "history/$sessionId"
}

@Composable
fun NavGraph() {
    val navController = rememberNavController()
    val context = LocalContext.current
    val appModule = (context.applicationContext as App).appModule

    // 预设ViewModel（全局共享）
    val presetViewModel: PresetViewModel = viewModel(
        factory = PresetViewModel.Factory(appModule.presetDao)
    )

    NavHost(navController = navController, startDestination = Routes.HOME) {

        // ===== 首页 =====
        composable(Routes.HOME) {
            val presets by presetViewModel.presets.collectAsState()

            // 「🧠 记忆库（N 条）」的条数。subscribe 记忆流本身（而不是新加一个 count 查询）：
            // 记忆条目只有几十条，且清空/删除后首页的条数要跟着变。
            // ⚠️ Flow 实例必须 remember 住：每次重组新建一个 Flow 会让 collectAsState
            // 重启收集（Room 那边就等于重新注册一次查询）。
            val memoryFlow = remember { appModule.memoryRepository.getAllMemories() }
            val memories by memoryFlow.collectAsState(initial = emptyList())

            HomeScreen(
                presets = presets,
                builtInCharacters = BuiltInCharacters.ALL,
                onNavigateToCharacter = { id -> navController.navigate(Routes.characterPrepare(id)) },
                onNavigateToPreset = { presetId -> navController.navigate(Routes.presetEdit(presetId)) },
                onNavigateToNewPreset = { navController.navigate(Routes.presetEdit(0)) },
                onDeletePreset = { presetId -> presetViewModel.deletePreset(presetId) },
                onNavigateToSettings = { navController.navigate(Routes.SETTINGS) },
                memoryCount = memories.size,
                onNavigateToMemory = { navController.navigate(Routes.MEMORY) }
            )
        }

        // ===== 内置角色准备页（银狼 / DeepSeek 酱 共用）=====
        composable(
            route = Routes.CHARACTER_PREPARE,
            arguments = listOf(navArgument("characterId") { type = NavType.StringType })
        ) { backStackEntry ->
            val characterId = backStackEntry.arguments?.getString("characterId")
            val character = BuiltInCharacters.byId(characterId)
            if (character == null) {
                // 非法 id（旧版本深链、手改路由）不应白屏：直接退回首页
                LaunchedEffect(Unit) { navController.popBackStack() }
                return@composable
            }

            val configRepository = appModule.configRepository
            val config by configRepository.configFlow.collectAsState(initial = com.lv999call.app.domain.model.ApiConfig())
            val scope = rememberCoroutineScope()

            // TTS 风格提示词：优先用户为该角色单独设置的值，其次角色自带默认值。
            // 每个角色各存一份，互不污染（见 ApiConfig.characterTtsPrompts）。
            val effectiveTtsPrompt = config.getTtsPromptForCharacter(
                character.id, character.defaultTtsPrompt
            )

            PrepareScreen(
                mode = DialogMode.LONG,
                character = character,
                promptPreview = "",  // 内置提示词，不预览
                backgroundResId = character.backgroundResId,
                // 音色被角色锁定时，参考音频设置无意义（见 PrepareScreen 内部说明）
                hasCustomAudio = character.ttsPolicy !is com.lv999call.app.domain.model.TtsPolicy.PresetVoice &&
                    config.ttsReferenceAudioBase64.isNotEmpty(),
                ttsPrompt = effectiveTtsPrompt,
                onTtsPromptChange = { newPrompt ->
                    scope.launch {
                        // 只写该角色那一格，不碰其他角色的语气与全局配置
                        configRepository.updateCharacterTtsPrompt(character.id, newPrompt)
                    }
                },
                onStartCall = { navController.navigate(Routes.characterCall(character.id)) },
                onSelectAudio = { uri ->
                    // 在IO线程提取WAV并保存到配置
                    scope.launch {
                        val result = com.lv999call.app.audio.AudioExtractor.extractWav(context, uri)
                        result.onSuccess { wavBytes ->
                            val base64 = android.util.Base64.encodeToString(wavBytes, android.util.Base64.NO_WRAP)
                            val currentConfig = configRepository.configFlow.first()
                            configRepository.saveConfig(currentConfig.copy(
                                ttsReferenceAudioBase64 = base64,
                                ttsReferenceAudioMime = "audio/wav"
                            ))
                        }
                        result.onFailure { e ->
                            android.util.Log.e("NavGraph", "音频提取失败: ${e.message}")
                        }
                    }
                },
                onClearAudio = {
                    scope.launch {
                        val currentConfig = configRepository.configFlow.first()
                        configRepository.saveConfig(currentConfig.copy(
                            ttsReferenceAudioBase64 = "",
                            ttsReferenceAudioMime = "audio/wav"
                        ))
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }

        // ===== 内置角色通话页（银狼 / DeepSeek 酱 共用）=====
        composable(
            route = Routes.CHARACTER_CALL,
            arguments = listOf(navArgument("characterId") { type = NavType.StringType })
        ) { backStackEntry ->
            val characterId = backStackEntry.arguments?.getString("characterId") ?: ""
            val character = BuiltInCharacters.byId(characterId)

            val viewModel: CallViewModel = viewModel(
                factory = CallViewModel.Factory(appModule, context.applicationContext as android.app.Application)
            )
            val callState by viewModel.callState.collectAsState()
            val messages by viewModel.messages.collectAsState()
            val currentResponse by viewModel.currentResponse.collectAsState()
            val isThinkingResponse by viewModel.isThinkingResponse.collectAsState()
            val isMuted by viewModel.isMuted.collectAsState()
            val config by viewModel.config.collectAsState()
            val audioLevel by viewModel.audioLevel.collectAsState()
            val expressionCue by viewModel.expressionCue.collectAsState()
            val activeCharacter by viewModel.character.collectAsState()
            val asrRetryHint by viewModel.asrRetryHint.collectAsState()

            LaunchedEffect(characterId) { viewModel.startCharacterCall(characterId) }

            // 过场只在"角色确实有这套演出"时才等（见 BuiltInCharacter.hasTransform）
            val waitsForTransform = config.live2dEnabled && config.live2dTransformEnabled &&
                (character?.hasTransform ?: false)

            LaunchedEffect(callState) {
                if (callState == CallState.ENDED) {
                    val sessionId = viewModel.getSessionId()
                    if (sessionId != null) {
                        // 等挂断过场（"还原变身"）播完再跳，见 CallScreen.HANGUP_TRANSFORM_MS；
                        // 过场被关掉（或角色没有过场）时不延迟，保持即时跳转手感
                        if (waitsForTransform) {
                            delay(HANGUP_TRANSFORM_MS)
                        }
                        navController.navigate(Routes.history(sessionId)) { popUpTo(Routes.HOME) }
                    }
                }
            }

            CallScreen(
                callState = callState,
                messages = messages,
                currentResponse = currentResponse,
                isThinkingResponse = isThinkingResponse,
                audioLevel = audioLevel,
                expressionCue = expressionCue,
                live2dEnabled = config.live2dEnabled,
                transformEnabled = config.live2dTransformEnabled,
                // 路由参数里已经能同步拿到角色（同一个 BuiltInCharacters.byId），
                // 优先用它而不是等 ViewModel 的异步状态：否则首帧 activeCharacter 还是
                // null，Live2D 会先按 bridge.js 默认档位（银狼）建一次 WebView，
                // 角色到位后才发现档位不对而重载 —— 白闪一下。
                // activeCharacter 作兜底，它与路由参数等价（同一份数据）。
                character = activeCharacter ?: character,
                avatarUri = config.characterAvatarUri,
                avatarResId = character?.avatarResId ?: com.lv999call.app.R.drawable.touxiang,
                backgroundResId = character?.backgroundResId,
                onHangUp = { viewModel.hangUp() },
                onToggleMute = { viewModel.toggleMute() },
                onSendText = { text -> viewModel.sendTextMessage(text) },
                isMuted = isMuted,
                asrRetryHint = asrRetryHint,
                // 摸头：只打标记，由下一轮的提示词捎带一句反应
                onHeadPat = { viewModel.onHeadPat() }
            )
        }

        // ===== 预设编辑页（新建/编辑） =====
        composable(
            route = Routes.PRESET_EDIT,
            arguments = listOf(navArgument("presetId") { type = NavType.LongType })
        ) { backStackEntry ->
            val presetId = backStackEntry.arguments?.getLong("presetId") ?: 0L

            // 加载已有预设数据。⚠️ 加载完成前**不渲染编辑页**：编辑页把 current* 当作
            // 初始值 `remember` 下来，先渲染空表单再回填，会把用户已经敲进去的内容覆盖掉。
            // 用一个显式的 ready 闸门，比在编辑页里写"外部数据变化就重置本地状态"可靠。
            var loadedPreset by remember(presetId) { mutableStateOf<com.lv999call.app.data.local.entity.PresetEntity?>(null) }
            var isPresetLoaded by remember(presetId) { mutableStateOf(presetId <= 0) }
            LaunchedEffect(presetId) {
                if (presetId > 0) {
                    loadedPreset = presetViewModel.getPreset(presetId)
                    isPresetLoaded = true
                }
            }

            // 重名校验用的名字集合：排除当前这一条，否则编辑已有方案时永远"和自己重名"
            val presets by presetViewModel.presets.collectAsState()
            val otherPresetNames = remember(presets, presetId) {
                presets.filter { it.id != presetId }.map { it.name.trim() }.toSet()
            }

            if (!isPresetLoaded) {
                Box(modifier = androidx.compose.ui.Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                return@composable
            }
            CustomEditScreen(
                presetId = if (presetId > 0) presetId else null,
                currentName = loadedPreset?.name ?: "",
                currentPrompt = loadedPreset?.prompt ?: "",
                currentTtsPrompt = loadedPreset?.ttsPrompt ?: "",
                currentRefAudioBase64 = loadedPreset?.refAudioBase64 ?: "",
                currentRefAudioMime = loadedPreset?.refAudioMime ?: "audio/wav",
                currentAvatarUri = loadedPreset?.avatarUri,
                currentBackgroundUri = loadedPreset?.backgroundUri,
                existingNames = otherPresetNames,
                onSave = { name, prompt, ttsPrompt, refAudioBase64, refAudioMime, avatarUri, backgroundUri ->
                    // 写库完成后再返回：以前是"先 pop 再异步写"，用户手快时可能写库被页面销毁打断
                    presetViewModel.savePresetAndThen(
                        id = if (presetId > 0) presetId else null,
                        name = name,
                        prompt = prompt,
                        ttsPrompt = ttsPrompt,
                        refAudioBase64 = refAudioBase64,
                        refAudioMime = refAudioMime,
                        avatarUri = avatarUri ?: "",
                        backgroundUri = backgroundUri ?: ""
                    ) { navController.popBackStack() }
                },
                onStartCall = { name, prompt, ttsPrompt, refAudioBase64, refAudioMime, avatarUri, backgroundUri ->
                    // 先保存预设并拿到 id，再导航。整条链跑在 PresetViewModel 的
                    // viewModelScope 上 —— 不再手搓 CoroutineScope（那种 scope 不随页面取消，
                    // 页面离开后仍会写库并对已销毁的 NavController 触发导航）。
                    presetViewModel.savePresetAndThen(
                        id = if (presetId > 0) presetId else null,
                        name = name,
                        prompt = prompt,
                        ttsPrompt = ttsPrompt,
                        refAudioBase64 = refAudioBase64,
                        refAudioMime = refAudioMime,
                        avatarUri = avatarUri ?: "",
                        backgroundUri = backgroundUri ?: ""
                    ) { newId -> navController.navigate("preset_call/$newId") }
                },
                onBack = { navController.popBackStack() }
            )
        }

        // ===== 预设通话页 =====
        composable(
            route = Routes.PRESET_CALL,
            arguments = listOf(navArgument("presetId") { type = NavType.LongType })
        ) { backStackEntry ->
            val presetId = backStackEntry.arguments?.getLong("presetId") ?: 0L

            val viewModel: CallViewModel = viewModel(
                factory = CallViewModel.Factory(appModule, context.applicationContext as android.app.Application)
            )
            val callState by viewModel.callState.collectAsState()
            val messages by viewModel.messages.collectAsState()
            val currentResponse by viewModel.currentResponse.collectAsState()
            val isThinkingResponse by viewModel.isThinkingResponse.collectAsState()
            val isMuted by viewModel.isMuted.collectAsState()
            val config by viewModel.config.collectAsState()
            val audioLevel by viewModel.audioLevel.collectAsState()
            val expressionCue by viewModel.expressionCue.collectAsState()
            val asrRetryHint by viewModel.asrRetryHint.collectAsState()

            // 加载预设数据用于显示
            var presetBgUri by remember { mutableStateOf<String?>(null) }
            var presetAvatarUri by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(presetId) {
                val preset = presetViewModel.getPreset(presetId)
                presetBgUri = preset?.backgroundUri
                presetAvatarUri = preset?.avatarUri
                viewModel.startPresetCall(presetId)
            }

            LaunchedEffect(callState) {
                if (callState == CallState.ENDED) {
                    val sessionId = viewModel.getSessionId()
                    if (sessionId != null) {
                        // 自定义预设的形象走 bridge.js 默认档位（即银狼那档），是有过场的，
                        // 所以这里同样等待，保持改造前的观感
                        if (config.live2dEnabled && config.live2dTransformEnabled) {
                            delay(HANGUP_TRANSFORM_MS)
                        }
                        navController.navigate(Routes.history(sessionId)) { popUpTo(Routes.HOME) }
                    }
                }
            }

            CallScreen(
                callState = callState,
                messages = messages,
                currentResponse = currentResponse,
                isThinkingResponse = isThinkingResponse,
                audioLevel = audioLevel,
                expressionCue = expressionCue,
                live2dEnabled = config.live2dEnabled,
                transformEnabled = config.live2dTransformEnabled,
                // 自定义预设不属于任何内置角色：形象参数走 bridge.js 默认档，
                // 头像/背景用预设自己的
                character = null,
                avatarUri = presetAvatarUri?.ifEmpty { config.characterAvatarUri } ?: config.characterAvatarUri,
                avatarResId = com.lv999call.app.R.drawable.default_avatar,
                backgroundUri = presetBgUri?.ifEmpty { null },
                onHangUp = { viewModel.hangUp() },
                onToggleMute = { viewModel.toggleMute() },
                onSendText = { text -> viewModel.sendTextMessage(text) },
                isMuted = isMuted,
                asrRetryHint = asrRetryHint,
                onHeadPat = { viewModel.onHeadPat() }
            )
        }

        // ===== 通话页（继续会话） =====
        composable(
            route = Routes.CALL_CONTINUE,
            arguments = listOf(navArgument("sessionId") { type = NavType.StringType })
        ) { backStackEntry ->
            val sessionId = backStackEntry.arguments?.getString("sessionId") ?: return@composable

            val viewModel: CallViewModel = viewModel(
                factory = CallViewModel.Factory(appModule, context.applicationContext as android.app.Application)
            )
            val callState by viewModel.callState.collectAsState()
            val messages by viewModel.messages.collectAsState()
            val currentResponse by viewModel.currentResponse.collectAsState()
            val isThinkingResponse by viewModel.isThinkingResponse.collectAsState()
            val isMuted by viewModel.isMuted.collectAsState()
            val config by viewModel.config.collectAsState()
            val audioLevel by viewModel.audioLevel.collectAsState()
            val expressionCue by viewModel.expressionCue.collectAsState()
            val activeCharacter by viewModel.character.collectAsState()
            val presetVisuals by viewModel.presetVisuals.collectAsState()
            val asrRetryHint by viewModel.asrRetryHint.collectAsState()

            LaunchedEffect(Unit) { viewModel.continueSession(sessionId) }

            // 续聊时角色是异步反查出来的（见 CallViewModel.matchCharacterByPrompt），
            // 所以这里跟着 activeCharacter 重算。反查不到 = 自定义会话，
            // 按"有过场"处理（默认档位就是银狼那档），保持改造前观感
            val waitsForTransform = config.live2dEnabled && config.live2dTransformEnabled &&
                (activeCharacter?.hasTransform ?: true)

            LaunchedEffect(callState) {
                if (callState == CallState.ENDED) {
                    val currentSessionId = viewModel.getSessionId()
                    if (currentSessionId != null) {
                        if (waitsForTransform) {
                            delay(HANGUP_TRANSFORM_MS)
                        }
                        navController.navigate(Routes.history(currentSessionId)) { popUpTo(Routes.HOME) }
                    }
                }
            }

            CallScreen(
                callState = callState, messages = messages, currentResponse = currentResponse,
                isThinkingResponse = isThinkingResponse,
                audioLevel = audioLevel, live2dEnabled = config.live2dEnabled,
                transformEnabled = config.live2dTransformEnabled,
                character = activeCharacter,
                // 自定义方案（presetVisuals != null）：头像/背景从方案那一行恢复，
                // 与「开始通话」那条路由同一套口径；内置角色才用角色自带的那两张。
                // 以前这里一律用全局头像 + activeCharacter 的背景 —— 方案续聊会
                // 显示银狼头像、还丢掉方案自己的背景。
                avatarUri = presetVisuals?.avatarUri?.ifEmpty { config.characterAvatarUri }
                    ?: config.characterAvatarUri,
                avatarResId = if (presetVisuals != null) {
                    com.lv999call.app.R.drawable.default_avatar
                } else {
                    activeCharacter?.avatarResId ?: com.lv999call.app.R.drawable.touxiang
                },
                backgroundResId = activeCharacter?.backgroundResId,
                backgroundUri = presetVisuals?.backgroundUri?.ifEmpty { null },
                expressionCue = expressionCue,
                onHangUp = { viewModel.hangUp() }, onToggleMute = { viewModel.toggleMute() },
                onSendText = { text -> viewModel.sendTextMessage(text) }, isMuted = isMuted,
                asrRetryHint = asrRetryHint,
                onHeadPat = { viewModel.onHeadPat() }
            )
        }

        // ===== 历史记录页 =====
        composable(
            route = Routes.HISTORY,
            arguments = listOf(navArgument("sessionId") { type = NavType.StringType })
        ) { backStackEntry ->
            val sessionId = backStackEntry.arguments?.getString("sessionId") ?: return@composable

            val viewModel: HistoryViewModel = viewModel(
                factory = HistoryViewModel.Factory(appModule.manageSessionUseCase)
            )

            LaunchedEffect(sessionId) { viewModel.loadSession(sessionId) }

            val messages by viewModel.messages.collectAsState()

            HistoryScreen(
                messages = messages,
                onContinueSession = {
                    navController.navigate(Routes.callContinue(sessionId)) { popUpTo(Routes.HOME) }
                },
                onBackToHome = {
                    navController.navigate(Routes.HOME) { popUpTo(Routes.HOME) { inclusive = true } }
                }
            )
        }

        // ===== 设置页 =====
        composable(Routes.SETTINGS) {
            val viewModel: SettingsViewModel = viewModel(
                factory = SettingsViewModel.Factory(appModule.configRepository, appModule.chatRepository, appModule.voskModelManager)
            )
            val config by viewModel.config.collectAsState()
            val voskDownloadState by viewModel.voskDownloadState.collectAsState()

            SettingsScreen(
                config = config,
                voskModels = com.lv999call.app.audio.VoskModelManager.AVAILABLE_MODELS,
                voskDownloadState = voskDownloadState,
                isModelDownloaded = { modelId -> viewModel.isModelDownloaded(modelId) },
                onSave = { newConfig -> viewModel.saveConfig(newConfig); navController.popBackStack() },
                onFetchModels = { baseUrl, apiKey -> viewModel.fetchModelsWithContext(baseUrl, apiKey) },
                onDownloadVoskModel = { model -> viewModel.downloadVoskModel(model) },
                onDeleteVoskModel = { modelId -> viewModel.deleteVoskModel(modelId) },
                onResetDownloadState = { viewModel.resetDownloadState() },
                onBack = { navController.popBackStack() }
            )
        }

        // ===== 记忆库（plan4 §6.2）=====
        // ViewModel 绑定在这条路由的 back stack entry 上（与 HistoryViewModel 一样），
        // 离开页面就回收；「立即整理」自己跑在 Application scope 上，不受它影响。
        composable(Routes.MEMORY) {
            val viewModel: MemoryViewModel = viewModel(
                factory = MemoryViewModel.Factory(
                    memoryRepository = appModule.memoryRepository,
                    sessionRepository = appModule.sessionRepository,
                    summarizeMemoryUseCase = appModule.summarizeMemoryUseCase,
                    configRepository = appModule.configRepository,
                    presetDao = appModule.presetDao,
                    applicationScope = appModule.applicationScope
                )
            )

            MemoryRoute(
                viewModel = viewModel,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
