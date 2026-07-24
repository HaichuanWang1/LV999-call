package com.ultraflow.silverwolf.di

import android.content.Context
import com.ultraflow.silverwolf.audio.AudioPlayer
import com.ultraflow.silverwolf.audio.AsrEngine
import com.ultraflow.silverwolf.audio.VoskModelManager
import com.ultraflow.silverwolf.data.local.AppDatabase
import com.ultraflow.silverwolf.data.remote.AsrApiService
import com.ultraflow.silverwolf.data.remote.LlmApiService
import com.ultraflow.silverwolf.data.remote.ModelsApiService
import com.ultraflow.silverwolf.data.remote.NetworkClient
import com.ultraflow.silverwolf.data.remote.TtsApiService
import com.ultraflow.silverwolf.data.repository.ChatRepository
import com.ultraflow.silverwolf.data.repository.ConfigRepository
import com.ultraflow.silverwolf.data.repository.SessionRepository
import com.ultraflow.silverwolf.domain.usecase.ManageSessionUseCase
import com.ultraflow.silverwolf.domain.usecase.ProcessAudioUseCase
import com.ultraflow.silverwolf.domain.usecase.StartCallUseCase

/**
 * 手动依赖注入模块
 */
class AppModule(private val context: Context) {

    // ===== 数据库 =====
    val database: AppDatabase by lazy { AppDatabase.getInstance(context) }

    // ===== DAO =====
    val sessionDao by lazy { database.sessionDao() }
    val messageDao by lazy { database.messageDao() }

    // ===== 网络服务 =====
    val llmApiService: LlmApiService by lazy {
        NetworkClient.createService(LlmApiService::class.java)
    }
    val asrApiService: AsrApiService by lazy {
        NetworkClient.createService(AsrApiService::class.java)
    }
    val ttsApiService: TtsApiService by lazy {
        NetworkClient.createService(TtsApiService::class.java)
    }
    val modelsApiService: ModelsApiService by lazy {
        NetworkClient.createService(ModelsApiService::class.java)
    }

    // ===== 仓库 =====
    val configRepository: ConfigRepository by lazy { ConfigRepository(context) }
    val sessionRepository: SessionRepository by lazy { SessionRepository(sessionDao, messageDao) }
    val chatRepository: ChatRepository by lazy { ChatRepository(llmApiService, asrApiService, ttsApiService, modelsApiService) }

    // ===== 音频引擎 =====
    val asrEngine: AsrEngine by lazy { AsrEngine(context) }
    val voskModelManager: VoskModelManager by lazy { VoskModelManager(context) }
    val audioPlayer: AudioPlayer by lazy { AudioPlayer() }

    // ===== 用例 =====
    val startCallUseCase: StartCallUseCase by lazy {
        StartCallUseCase(sessionRepository, configRepository)
    }
    val manageSessionUseCase: ManageSessionUseCase by lazy {
        ManageSessionUseCase(sessionRepository)
    }
    val processAudioUseCase: ProcessAudioUseCase by lazy {
        ProcessAudioUseCase(chatRepository, configRepository, asrEngine, audioPlayer)
    }
}
