package com.lv999call.app.di

import android.content.Context
import com.lv999call.app.audio.AudioPlayer
import com.lv999call.app.audio.AsrEngine
import com.lv999call.app.audio.VoskModelManager
import com.lv999call.app.data.local.AppDatabase
import com.lv999call.app.data.remote.LlmApiService
import com.lv999call.app.data.remote.ModelsApiService
import com.lv999call.app.data.remote.NetworkClient
import com.lv999call.app.data.remote.TtsApiService
import com.lv999call.app.data.repository.ChatRepository
import com.lv999call.app.data.repository.ConfigRepository
import com.lv999call.app.data.repository.SessionRepository
import com.lv999call.app.domain.model.TtsPolicy
import com.lv999call.app.domain.usecase.ManageSessionUseCase
import com.lv999call.app.domain.usecase.ProcessAudioUseCase
import com.lv999call.app.domain.usecase.StartCallUseCase

class AppModule(private val context: Context) {

    val database: AppDatabase by lazy { AppDatabase.getInstance(context) }

    val sessionDao by lazy { database.sessionDao() }
    val messageDao by lazy { database.messageDao() }
    val presetDao by lazy { database.presetDao() }

    val llmApiService: LlmApiService by lazy { NetworkClient.createService(LlmApiService::class.java) }
    // ASR 不需要在这里建：它走 AsrEngine（要同时支持 Vosk 离线，不能只有 HTTP 一条路），
    // AsrEngine 自己持有 HTTP 服务实例
    val ttsApiService: TtsApiService by lazy { NetworkClient.createService(TtsApiService::class.java) }
    val modelsApiService: ModelsApiService by lazy { NetworkClient.createService(ModelsApiService::class.java) }

    val configRepository: ConfigRepository by lazy { ConfigRepository(context) }
    val sessionRepository: SessionRepository by lazy { SessionRepository(sessionDao, messageDao) }
    val chatRepository: ChatRepository by lazy { ChatRepository(llmApiService, ttsApiService, modelsApiService) }

    val asrEngine: AsrEngine by lazy { AsrEngine(context) }
    val voskModelManager: VoskModelManager by lazy { VoskModelManager(context) }
    val audioPlayer: AudioPlayer by lazy { AudioPlayer() }

    /**
     * 角色自带参考音频的缓存（assets 路径 → base64）。
     *
     * 早期这里叫 `silverWolfRefAudioBase64`，只服务银狼一个角色。加入并列角色后
     * 改成按 [BuiltInCharacter.ttsPolicy] 里声明的 asset 路径按需加载并缓存：
     * 每个角色各自带自己的音色，互不干扰；预置音色策略（如 DeepSeek 酱）
     * 根本不需要参考音频，这里也就不会被调用。
     */
    private val refAudioCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun refAudioBase64(assetPath: String): String = refAudioCache.getOrPut(assetPath) {
        try {
            val bytes = context.assets.open(assetPath).use { it.readBytes() }
            android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        } catch (e: Exception) {
            android.util.Log.e("AppModule", "加载角色参考音频失败 $assetPath: ${e.message}")
            ""
        }
    }

    /**
     * 解析某角色的 TTS 策略为"可直接下发给请求层"的形态。
     *
     * - [TtsPolicy.PresetVoice]：原样返回（预置音色不需要参考音频）
     * - [TtsPolicy.CloneVoice]：读出该角色自带的参考音频并返回
     * - [TtsPolicy.Inherit] / null：返回 null，交由调用方跟随全局设置
     */
    fun resolveTtsPolicy(character: com.lv999call.app.domain.model.BuiltInCharacter?): TtsPolicy? {
        return when (val p = character?.ttsPolicy) {
            null, is TtsPolicy.Inherit -> null
            else -> p
        }
    }

    /** 取角色自带的参考音频 base64（仅 [TtsPolicy.CloneVoice] 有意义） */
    fun cloneRefAudio(character: com.lv999call.app.domain.model.BuiltInCharacter?): String? {
        val p = character?.ttsPolicy
        return if (p is TtsPolicy.CloneVoice) refAudioBase64(p.refAudioAsset) else null
    }

    /** 取角色自带参考音频的 MIME（仅 [TtsPolicy.CloneVoice] 有意义） */
    fun cloneRefAudioMime(character: com.lv999call.app.domain.model.BuiltInCharacter?): String? {
        val p = character?.ttsPolicy
        return if (p is TtsPolicy.CloneVoice) p.refAudioMime else null
    }

    val startCallUseCase: StartCallUseCase by lazy { StartCallUseCase(sessionRepository, configRepository, context) }
    val manageSessionUseCase: ManageSessionUseCase by lazy { ManageSessionUseCase(sessionRepository) }
    val processAudioUseCase: ProcessAudioUseCase by lazy { ProcessAudioUseCase(chatRepository, configRepository, asrEngine, audioPlayer) }
}
