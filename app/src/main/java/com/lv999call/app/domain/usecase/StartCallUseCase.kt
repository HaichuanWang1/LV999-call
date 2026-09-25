package com.lv999call.app.domain.usecase

import android.content.Context
import com.lv999call.app.data.repository.ConfigRepository
import com.lv999call.app.data.repository.SessionRepository
import com.lv999call.app.domain.model.*
import com.lv999call.app.preset.BuiltInCharacters
import kotlinx.coroutines.flow.first
import java.util.UUID

/**
 * 建立一次通话会话。
 *
 * 提示词来源按 [DialogMode] 分派：
 * - [DialogMode.LONG]：**内置角色**的专属提示词（从 assets 读，按角色 id 定位）
 * - [DialogMode.QUICK]：内置角色的精简提示词（未单独提供时用一句话兜底）
 * - [DialogMode.CUSTOM]：用户配置里的自定义提示词
 *
 * 改造要点：原先 `longPrompt` 写死读 `silverwolf_prompt.txt`，等于"银狼"不是一个
 * 可并列的预设而是一段硬编码。现在按 [BuiltInCharacter.promptAsset] 读取，
 * 银狼与 DeepSeek 酱走同一条路径。
 */
class StartCallUseCase(
    private val sessionRepository: SessionRepository,
    private val configRepository: ConfigRepository,
    private val context: Context
) {
    /** 无内置提示词时的兜底（避免 LLM 完全没有角色设定） */
    private val fallbackPrompt =
        "你是一个友好、活泼的AI助手。请用简短自然的口语化回复，就像朋友之间聊天一样。" +
            "不要使用markdown格式，不要输出动作描写。每次回复控制在2-3句话以内，除非用户明确需要详细解释。"

    /** 按角色 + 模式取系统提示词 */
    suspend fun getSystemPrompt(mode: DialogMode, character: BuiltInCharacter?): String {
        return when (mode) {
            DialogMode.QUICK -> character?.let { readAsset(it.promptAsset) } ?: fallbackPrompt
            DialogMode.LONG -> character?.let { readAsset(it.promptAsset) } ?: fallbackPrompt
            DialogMode.CUSTOM -> {
                val config = configRepository.configFlow.first()
                config.customPrompt.ifEmpty { "" }
            }
        }
    }

    /**
     * 读 assets 里的提示词。
     *
     * 失败时回落到 [fallbackPrompt] 而不是抛异常：提示词缺失只应让角色"性格变淡"，
     * 不该让整通电话打不起来。
     */
    private fun readAsset(name: String): String = try {
        context.assets.open(name).bufferedReader().use { it.readText() }
    } catch (e: Exception) {
        android.util.Log.e("StartCallUseCase", "加载提示词失败 $name: ${e.message}")
        fallbackPrompt
    }

    suspend fun createSession(mode: DialogMode, character: BuiltInCharacter?): Session {
        val systemPrompt = getSystemPrompt(mode, character)
        val session = Session(
            id = UUID.randomUUID().toString(),
            mode = mode,
            systemPrompt = systemPrompt,
            createdAt = System.currentTimeMillis()
        )
        sessionRepository.createSession(session)
        return session
    }

    /** 按角色 id 直接建会话（内置角色路由用） */
    suspend fun createSessionForCharacter(characterId: String): Session =
        createSession(DialogMode.LONG, BuiltInCharacters.byId(characterId))
}
