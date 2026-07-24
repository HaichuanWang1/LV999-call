package com.lv999call.app.di

import android.content.Context
import com.lv999call.app.audio.AudioPlayer
import com.lv999call.app.audio.AsrEngine
import com.lv999call.app.audio.VoskModelManager
import com.lv999call.app.data.local.AppDatabase
import com.lv999call.app.data.remote.AsrApiService
import com.lv999call.app.data.remote.LlmApiService
import com.lv999call.app.data.remote.ModelsApiService
import com.lv999call.app.data.remote.NetworkClient
import com.lv999call.app.data.remote.TtsApiService
import com.lv999call.app.data.repository.ChatRepository
import com.lv999call.app.data.repository.ConfigRepository
import com.lv999call.app.data.repository.SessionRepository
import com.lv999call.app.domain.usecase.ManageSessionUseCase
import com.lv999call.app.domain.usecase.ProcessAudioUseCase
import com.lv999call.app.domain.usecase.StartCallUseCase

class AppModule(private val context: Context) {

    val database: AppDatabase by lazy { AppDatabase.getInstance(context) }

    val sessionDao by lazy { database.sessionDao() }
    val messageDao by lazy { database.messageDao() }
    val presetDao by lazy { database.presetDao() }

    val llmApiService: LlmApiService by lazy { NetworkClient.createService(LlmApiService::class.java) }
    val asrApiService: AsrApiService by lazy { NetworkClient.createService(AsrApiService::class.java) }
    val ttsApiService: TtsApiService by lazy { NetworkClient.createService(TtsApiService::class.java) }
    val modelsApiService: ModelsApiService by lazy { NetworkClient.createService(ModelsApiService::class.java) }

    val configRepository: ConfigRepository by lazy { ConfigRepository(context) }
    val sessionRepository: SessionRepository by lazy { SessionRepository(sessionDao, messageDao) }
    val chatRepository: ChatRepository by lazy { ChatRepository(llmApiService, asrApiService, ttsApiService, modelsApiService) }

    val asrEngine: AsrEngine by lazy { AsrEngine(context) }
    val voskModelManager: VoskModelManager by lazy { VoskModelManager(context) }
    val audioPlayer: AudioPlayer by lazy { AudioPlayer() }

    /** 银狼内置参考音频（从assets加载） */
    val silverWolfRefAudioBase64: String by lazy {
        try {
            val bytes = context.assets.open("silverwolf/ref_voice.wav").use { it.readBytes() }
            android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        } catch (e: Exception) {
            android.util.Log.e("AppModule", "加载银狼音频失败: ${e.message}")
            ""
        }
    }

    val startCallUseCase: StartCallUseCase by lazy { StartCallUseCase(sessionRepository, configRepository, context) }
    val manageSessionUseCase: ManageSessionUseCase by lazy { ManageSessionUseCase(sessionRepository) }
    val processAudioUseCase: ProcessAudioUseCase by lazy { ProcessAudioUseCase(chatRepository, configRepository, asrEngine, audioPlayer, silverWolfRefAudioBase64) }
}
