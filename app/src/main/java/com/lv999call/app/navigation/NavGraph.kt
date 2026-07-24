package com.lv999call.app.navigation

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import kotlinx.coroutines.launch
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.lv999call.app.App
import com.lv999call.app.domain.model.CallState
import com.lv999call.app.domain.model.DialogMode
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
    const val SILVERWOLF_PREPARE = "silverwolf_prepare"
    const val SILVERWOLF_CALL = "silverwolf_call"
    const val PRESET_EDIT = "preset_edit/{presetId}"
    const val PRESET_CALL = "preset_call/{presetId}"
    const val CALL_CONTINUE = "call_continue/{sessionId}"
    const val HISTORY = "history/{sessionId}"
    const val SETTINGS = "settings"

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
                onNavigateToSilverWolf = { navController.navigate(Routes.SILVERWOLF_PREPARE) },
                onNavigateToPreset = { presetId -> navController.navigate(Routes.presetEdit(presetId)) },
                onNavigateToNewPreset = { navController.navigate(Routes.presetEdit(0)) },
                onDeletePreset = { presetId -> presetViewModel.deletePreset(presetId) },
                onNavigateToSettings = { navController.navigate(Routes.SETTINGS) }
            )
        }

        // ===== 银狼准备页 =====
        composable(Routes.SILVERWOLF_PREPARE) {
            PrepareScreen(
                mode = DialogMode.LONG,
                promptPreview = "",  // 内置提示词，不预览
                backgroundResId = com.lv999call.app.R.drawable.silverwolf_bg,
                onStartCall = { navController.navigate(Routes.SILVERWOLF_CALL) },
                onBack = { navController.popBackStack() }
            )
        }

        // ===== 银狼通话页 =====
        composable(Routes.SILVERWOLF_CALL) {
            val viewModel: CallViewModel = viewModel(
                factory = CallViewModel.Factory(appModule, context.applicationContext as android.app.Application)
            )
            val callState by viewModel.callState.collectAsState()
            val messages by viewModel.messages.collectAsState()
            val currentResponse by viewModel.currentResponse.collectAsState()
            val isMuted by viewModel.isMuted.collectAsState()
            val config by viewModel.config.collectAsState()

            LaunchedEffect(Unit) { viewModel.startCall(DialogMode.LONG) }

            LaunchedEffect(callState) {
                if (callState == CallState.ENDED) {
                    val sessionId = viewModel.getSessionId()
                    if (sessionId != null) {
                        navController.navigate(Routes.history(sessionId)) { popUpTo(Routes.HOME) }
                    }
                }
            }

            CallScreen(
                callState = callState,
                messages = messages,
                currentResponse = currentResponse,
                audioLevel = 0f,
                avatarUri = config.characterAvatarUri,
                backgroundResId = com.lv999call.app.R.drawable.silverwolf_bg,
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
                currentRefAudioBase64 = loadedPreset?.refAudioBase64 ?: "",
                currentRefAudioMime = loadedPreset?.refAudioMime ?: "audio/wav",
                currentAvatarUri = loadedPreset?.avatarUri,
                currentBackgroundUri = loadedPreset?.backgroundUri,
                onSave = { name, prompt, refAudioBase64, refAudioMime, avatarUri, backgroundUri ->
                    presetViewModel.savePreset(
                        id = if (presetId > 0) presetId else null,
                        name = name,
                        prompt = prompt,
                        refAudioBase64 = refAudioBase64,
                        refAudioMime = refAudioMime,
                        avatarUri = avatarUri ?: "",
                        backgroundUri = backgroundUri ?: ""
                    )
                    navController.popBackStack()
                },
                onStartCall = { name, prompt, refAudioBase64, refAudioMime, avatarUri, backgroundUri ->
                    // 先保存预设并获取ID，再导航
                    val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main)
                    scope.launch {
                        val newId = presetViewModel.savePresetAndGetId(
                            id = if (presetId > 0) presetId else null,
                            name = name,
                            prompt = prompt,
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
            val isMuted by viewModel.isMuted.collectAsState()
            val config by viewModel.config.collectAsState()

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
                        navController.navigate(Routes.history(sessionId)) { popUpTo(Routes.HOME) }
                    }
                }
            }

            CallScreen(
                callState = callState,
                messages = messages,
                currentResponse = currentResponse,
                audioLevel = 0f,
                avatarUri = presetAvatarUri?.ifEmpty { config.characterAvatarUri } ?: config.characterAvatarUri,
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
            val isMuted by viewModel.isMuted.collectAsState()
            val config by viewModel.config.collectAsState()

            LaunchedEffect(Unit) { viewModel.continueSession(sessionId) }

            LaunchedEffect(callState) {
                if (callState == CallState.ENDED) {
                    val currentSessionId = viewModel.getSessionId()
                    if (currentSessionId != null) {
                        navController.navigate(Routes.history(currentSessionId)) { popUpTo(Routes.HOME) }
                    }
                }
            }

            CallScreen(
                callState = callState, messages = messages, currentResponse = currentResponse,
                audioLevel = 0f, avatarUri = config.characterAvatarUri,
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
