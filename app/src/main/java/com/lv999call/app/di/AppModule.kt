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
import com.lv999call.app.data.repository.MemoryRepository
import com.lv999call.app.data.repository.SessionRepository
import com.lv999call.app.domain.model.TtsPolicy
import com.lv999call.app.domain.usecase.ComposeReminderUseCase
import com.lv999call.app.domain.usecase.LoadMemoryUseCase
import com.lv999call.app.domain.usecase.ManageSessionUseCase
import com.lv999call.app.domain.usecase.ProcessAudioUseCase
import com.lv999call.app.domain.usecase.StartCallUseCase
import com.lv999call.app.domain.usecase.SummarizeMemoryUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class AppModule(private val context: Context) {

    val database: AppDatabase by lazy { AppDatabase.getInstance(context) }

    val sessionDao by lazy { database.sessionDao() }
    val messageDao by lazy { database.messageDao() }
    val presetDao by lazy { database.presetDao() }
    val memoryDao by lazy { database.memoryDao() }

    val llmApiService: LlmApiService by lazy { NetworkClient.createService(LlmApiService::class.java) }
    // ASR 不需要在这里建：它走 AsrEngine（要同时支持 Vosk 离线，不能只有 HTTP 一条路），
    // AsrEngine 自己持有 HTTP 服务实例
    val ttsApiService: TtsApiService by lazy { NetworkClient.createService(TtsApiService::class.java) }
    val modelsApiService: ModelsApiService by lazy { NetworkClient.createService(ModelsApiService::class.java) }

    val configRepository: ConfigRepository by lazy { ConfigRepository(context) }
    val sessionRepository: SessionRepository by lazy { SessionRepository(sessionDao, messageDao) }

    /**
     * 长期记忆仓库。
     *
     * 这里把 [AppDatabase] 一起传进去是有意的：plan4 §3.2 Step 7/8 要求「写记忆」与
     * 「推会话游标」落在同一个 Room 事务里，而这两条写操作分属 memories / sessions 两张表，
     * 只能在仓库层用 `database.withTransaction` 兜住。除此以外仓库不碰 database。
     */
    val memoryRepository: MemoryRepository by lazy { MemoryRepository(memoryDao, sessionDao, database) }
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

    val startCallUseCase: StartCallUseCase by lazy { StartCallUseCase(sessionRepository, context) }
    val manageSessionUseCase: ManageSessionUseCase by lazy { ManageSessionUseCase(sessionRepository) }
    val processAudioUseCase: ProcessAudioUseCase by lazy {
        ProcessAudioUseCase(chatRepository, configRepository, asrEngine, audioPlayer)
    }

    /**
     * Application 级作用域 —— 目前只服务「挂断后跑记忆总结」。
     *
     * 为什么不能用 `viewModelScope`：用户挂断后立刻回历史页，通话页 ViewModel 随即被
     * `onCleared()` 清掉，挂在它上面的总结协程会跟着被取消（一次总结要 5~10 秒）。
     * 用 `SupervisorJob` 是让"某一通总结失败/被取消"不要连带把后续的总结任务一起带走。
     *
     * ⚠️ 这个 scope 与 Activity/ViewModel 生命周期无关，所以启动的任务必须自己保证
     * 不碰任何 UI 与音频资源（总结只走 LLM 文本通道，见 [SummarizeMemoryUseCase]）。
     */
    val applicationScope: CoroutineScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.IO) }

    /**
     * 对话总结用例（plan4 阶段2）。
     *
     * 启动点（挂断、开聊时的补总结）必须落在 [applicationScope] 上，不能用 viewModelScope；
     * 用例内部自带 Mutex，保证同一时刻只有一个总结在跑（总结之间串行）。
     */
    val summarizeMemoryUseCase: SummarizeMemoryUseCase by lazy {
        SummarizeMemoryUseCase(
            chatRepository = chatRepository,
            sessionRepository = sessionRepository,
            memoryRepository = memoryRepository,
            configRepository = configRepository,
            context = context
        )
    }

    /**
     * 记忆加载用例（plan4 阶段4）。
     *
     * 与总结用例相反：它在**开聊前**同步跑一次（几十毫秒），把记忆装配成一段提示词块，
     * 所以不需要 Application 级 scope，也不需要 Mutex —— 它不调 LLM、不写内容，
     * 只读记忆并把结果回写 `lastUsedAt`。
     */
    val loadMemoryUseCase: LoadMemoryUseCase by lazy {
        LoadMemoryUseCase(
            memoryRepository = memoryRepository,
            configRepository = configRepository
        )
    }

    /**
     * 记忆提醒文案的生成用例。
     *
     * 与总结用例同一条红线：只走 LLM 文本通道，永不碰 TTS/麦克风/播放器。
     * 它跑在 WorkManager 的 Worker 里（`MemoryReminderWorker`），生命周期由系统管，
     * 所以**不**需要 [applicationScope]，也没有 Mutex —— 一次周期任务只会有一个实例，
     * 且它与"挂断后总结"并发时本来也不共享任何状态（各自收集自己的流）。
     */
    val composeReminderUseCase: ComposeReminderUseCase by lazy {
        ComposeReminderUseCase(
            chatRepository = chatRepository,
            configRepository = configRepository,
            context = context
        )
    }
}
