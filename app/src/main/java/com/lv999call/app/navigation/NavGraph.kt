package com.lv999call.app.navigation

import androidx.compose.runtime.*
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

            HomeScreen(
                presets = presets,
                builtInCharacters = BuiltInCharacters.ALL,
                onNavigateToCharacter = { id -> navController.navigate(Routes.characterPrepare(id)) },
                onNavigateToPreset = { presetId -> navController.navigate(Routes.presetEdit(presetId)) },
                onNavigateToNewPreset = { navController.navigate(Routes.presetEdit(0)) },
                onDeletePreset = { presetId -> presetViewModel.deletePreset(presetId) },
                onNavigateToSettings = { navController.navigate(Routes.SETTINGS) }
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

            // TTS 风格提示词：角色有默认值时用它作为首次进入的初值。
            // 这里不写回配置 —— 角色默认值属于角色，不该污染全局设置。
            val effectiveTtsPrompt = config.ttsPrompt.ifEmpty { character.defaultTtsPrompt }

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
                        val currentConfig = configRepository.configFlow.first()
                        configRepository.saveConfig(currentConfig.copy(ttsPrompt = newPrompt))
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
                character = activeCharacter,
                avatarUri = config.characterAvatarUri,
                avatarResId = character?.avatarResId ?: com.lv999call.app.R.drawable.touxiang,
                backgroundResId = character?.backgroundResId,
                onHangUp = { viewModel.hangUp() },
                onToggleMute = { viewModel.toggleMute() },
                onSendText = { text -> viewModel.sendTextMessage(text) },
                isMuted = isMuted
            )
        }

        // ===== 预设编辑页（新建/编辑） =====
        composable(
            route = Routes.PRESET_EDIT,
            arguments = listOf(navArgument("presetId") { type = NavType.LongType })
        ) { backStackEntry ->
            val presetId = backStackEntry.arguments?.getLong("presetId") ?: 0L

            // 加载已有预设数据
            var loadedPreset by remember { mutableStateOf<com.lv999call.app.data.local.entity.PresetEntity?>(null) }
            LaunchedEffect(presetId) {
                if (presetId > 0) {
                    loadedPreset = presetViewModel.getPreset(presetId)
                }
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
                onSave = { name, prompt, ttsPrompt, refAudioBase64, refAudioMime, avatarUri, backgroundUri ->
                    presetViewModel.savePreset(
                        id = if (presetId > 0) presetId else null,
                        name = name,
                        prompt = prompt,
                        ttsPrompt = ttsPrompt,
                        refAudioBase64 = refAudioBase64,
                        refAudioMime = refAudioMime,
                        avatarUri = avatarUri ?: "",
                        backgroundUri = backgroundUri ?: ""
                    )
                    navController.popBackStack()
                },
                onStartCall = { name, prompt, ttsPrompt, refAudioBase64, refAudioMime, avatarUri, backgroundUri ->
                    // 先保存预设并获取ID，再导航
                    val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main)
                    scope.launch {
                        val newId = presetViewModel.savePresetAndGetId(
                            id = if (presetId > 0) presetId else null,
                            name = name,
                            prompt = prompt,
                            ttsPrompt = ttsPrompt,
                            refAudioBase64 = refAudioBase64,
                            refAudioMime = refAudioMime,
                            avatarUri = avatarUri ?: "",
                            backgroundUri = backgroundUri ?: ""
                        )
                        navController.navigate("preset_call/$newId")
                    }
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
                        // 自定义预设没有"变身"过场（模型由用户自选），不做延迟
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
                isMuted = isMuted
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

            LaunchedEffect(Unit) { viewModel.continueSession(sessionId) }

            // 续聊时角色是异步反查出来的（见 CallViewModel.matchCharacterByPrompt），
            // 所以这里跟着 activeCharacter 重算
            val waitsForTransform = config.live2dEnabled && config.live2dTransformEnabled &&
                (activeCharacter?.hasTransform ?: false)

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
                avatarUri = config.characterAvatarUri,
                avatarResId = activeCharacter?.avatarResId ?: com.lv999call.app.R.drawable.touxiang,
                backgroundResId = activeCharacter?.backgroundResId,
                expressionCue = expressionCue,
                onHangUp = { viewModel.hangUp() }, onToggleMute = { viewModel.toggleMute() },
                onSendText = { text -> viewModel.sendTextMessage(text) }, isMuted = isMuted
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
    }
}
