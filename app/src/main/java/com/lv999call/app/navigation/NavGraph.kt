package com.lv999call.app.navigation

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
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
import com.lv999call.app.ui.custom.CustomEditViewModel
import com.lv999call.app.ui.history.HistoryScreen
import com.lv999call.app.ui.history.HistoryViewModel
import com.lv999call.app.ui.home.HomeScreen
import com.lv999call.app.ui.prepare.PrepareScreen
import com.lv999call.app.ui.prepare.PrepareViewModel
import com.lv999call.app.ui.settings.SettingsScreen
import com.lv999call.app.ui.settings.SettingsViewModel

/** 路由定义 */
object Routes {
    const val HOME = "home"
    const val PREPARE = "prepare/{mode}"
    const val CALL = "call/{mode}"
    const val CALL_CONTINUE = "call_continue/{sessionId}"
    const val HISTORY = "history/{sessionId}"
    const val CUSTOM_EDIT = "custom_edit"
    const val SETTINGS = "settings"

    fun prepare(mode: DialogMode) = "prepare/${mode.name}"
    fun call(mode: DialogMode) = "call/${mode.name}"
    fun callContinue(sessionId: String) = "call_continue/$sessionId"
    fun history(sessionId: String) = "history/$sessionId"
}

@Composable
fun NavGraph() {
    val navController = rememberNavController()
    val context = LocalContext.current
    val appModule = (context.applicationContext as App).appModule

    NavHost(
        navController = navController,
        startDestination = Routes.HOME
    ) {
        // ===== 首页 =====
        composable(Routes.HOME) {
            HomeScreen(
                onNavigateToPrepare = { mode ->
                    navController.navigate(Routes.prepare(mode))
                },
                onNavigateToSettings = {
                    navController.navigate(Routes.SETTINGS)
                }
            )
        }

        // ===== 对话准备页 =====
        composable(
            route = Routes.PREPARE,
            arguments = listOf(navArgument("mode") { type = NavType.StringType })
        ) { backStackEntry ->
            val modeName = backStackEntry.arguments?.getString("mode") ?: "QUICK"
            val mode = DialogMode.valueOf(modeName)

            val viewModel: PrepareViewModel = viewModel(
                factory = PrepareViewModel.Factory(appModule.startCallUseCase, appModule.configRepository)
            )

            LaunchedEffect(mode) {
                viewModel.loadPrompt(mode)
            }

            val systemPrompt by viewModel.systemPrompt.collectAsState()
            val config by viewModel.config.collectAsState()

            PrepareScreen(
                mode = mode,
                systemPrompt = systemPrompt,
                backgroundUri = config.backgroundUri,
                onStartCall = {
                    navController.navigate(Routes.call(mode))
                },
                onBack = { navController.popBackStack() }
            )
        }

        // ===== 通话页（新会话） =====
        composable(
            route = Routes.CALL,
            arguments = listOf(navArgument("mode") { type = NavType.StringType })
        ) { backStackEntry ->
            val modeName = backStackEntry.arguments?.getString("mode") ?: "QUICK"
            val mode = DialogMode.valueOf(modeName)

            val viewModel: CallViewModel = viewModel(
                factory = CallViewModel.Factory(appModule, context.applicationContext as android.app.Application)
            )

            val callState by viewModel.callState.collectAsState()
            val messages by viewModel.messages.collectAsState()
            val currentResponse by viewModel.currentResponse.collectAsState()
            val isMuted by viewModel.isMuted.collectAsState()
            val config by viewModel.config.collectAsState()

            // 启动通话
            LaunchedEffect(Unit) {
                viewModel.startCall(mode)
            }

            // 通话结束后跳转到历史页
            LaunchedEffect(callState) {
                if (callState == CallState.ENDED) {
                    val sessionId = viewModel.getSessionId()
                    if (sessionId != null) {
                        navController.navigate(Routes.history(sessionId)) {
                            popUpTo(Routes.HOME)
                        }
                    }
                }
            }

            CallScreen(
                callState = callState,
                messages = messages,
                currentResponse = currentResponse,
                audioLevel = 0f,
                avatarUri = config.characterAvatarUri,
                backgroundUri = config.backgroundUri,
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

            LaunchedEffect(Unit) {
                viewModel.continueSession(sessionId)
            }

            LaunchedEffect(callState) {
                if (callState == CallState.ENDED) {
                    val currentSessionId = viewModel.getSessionId()
                    if (currentSessionId != null) {
                        navController.navigate(Routes.history(currentSessionId)) {
                            popUpTo(Routes.HOME)
                        }
                    }
                }
            }

            CallScreen(
                callState = callState,
                messages = messages,
                currentResponse = currentResponse,
                audioLevel = 0f,
                avatarUri = config.characterAvatarUri,
                backgroundUri = config.backgroundUri,
                onHangUp = { viewModel.hangUp() },
                onToggleMute = { viewModel.toggleMute() },
                onSendText = { text -> viewModel.sendTextMessage(text) },
                isMuted = isMuted
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

            LaunchedEffect(sessionId) {
                viewModel.loadSession(sessionId)
            }

            val messages by viewModel.messages.collectAsState()

            HistoryScreen(
                messages = messages,
                onContinueSession = {
                    navController.navigate(Routes.callContinue(sessionId)) {
                        popUpTo(Routes.HOME)
                    }
                },
                onBackToHome = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.HOME) { inclusive = true }
                    }
                }
            )
        }

        // ===== 自定义编辑页 =====
        composable(Routes.CUSTOM_EDIT) {
            val viewModel: CustomEditViewModel = viewModel(
                factory = CustomEditViewModel.Factory(appModule.configRepository)
            )

            val config by viewModel.config.collectAsState()

            CustomEditScreen(
                currentPrompt = config.customPrompt,
                currentAvatarUri = config.characterAvatarUri.takeIf { it.isNotEmpty() },
                currentBackgroundUri = config.backgroundUri.takeIf { it.isNotEmpty() },
                currentRefAudioBase64 = config.customTtsReferenceAudioBase64,
                currentRefAudioMime = config.customTtsReferenceAudioMime,
                onSave = { prompt, avatarUri, backgroundUri, refAudioBase64, refAudioMime ->
                    viewModel.saveCustomConfig(prompt, avatarUri, backgroundUri, refAudioBase64, refAudioMime)
                    navController.popBackStack()
                },
                onBack = { navController.popBackStack() }
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
                onSave = { newConfig ->
                    viewModel.saveConfig(newConfig)
                    navController.popBackStack()
                },
                onFetchModels = { baseUrl, apiKey -> viewModel.fetchModelsWithContext(baseUrl, apiKey) },
                onDownloadVoskModel = { model -> viewModel.downloadVoskModel(model) },
                onDeleteVoskModel = { modelId -> viewModel.deleteVoskModel(modelId) },
                onResetDownloadState = { viewModel.resetDownloadState() },
                onBack = { navController.popBackStack() }
            )
        }
    }
}
