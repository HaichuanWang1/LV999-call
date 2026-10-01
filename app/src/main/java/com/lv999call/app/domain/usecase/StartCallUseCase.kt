package com.lv999call.app.domain.usecase

import android.content.Context
import com.lv999call.app.data.repository.SessionRepository
import com.lv999call.app.domain.model.*
import com.lv999call.app.preset.BuiltInCharacters
import java.util.UUID

/**
 * 建立一次通话会话。
 *
 * 提示词来源按 [DialogMode] 分派：
 * - [DialogMode.LONG]：**内置角色**的专属提示词（从 assets 读，按角色 id 定位）
 * - [DialogMode.QUICK]：内置角色的精简提示词（未单独提供时用一句话兜底）
 * - [DialogMode.CUSTOM]：没有内置提示词可给 —— 自定义方案的提示词由调用方通过
 *   [createSession] 的 `systemPromptOverride` 显式传入（见那里的说明）
 *
 * 改造要点：原先 `longPrompt` 写死读 `silverwolf_prompt.txt`，等于"银狼"不是一个
 * 可并列的预设而是一段硬编码。现在按 [BuiltInCharacter.promptAsset] 读取，
 * 银狼与 DeepSeek 酱走同一条路径。
 */
class StartCallUseCase(
    private val sessionRepository: SessionRepository,
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
            // 自定义方案没有"内置提示词"这回事：正文由调用方从 presets 表读出来，
            // 经 systemPromptOverride 传进来。这里返回空串，绝不去读任何全局配置 ——
            // 早期版本读 `config.customPrompt`（一个没有任何 UI 能写的死配置，恒为空），
            // 于是方案提示词只活在 ViewModel 内存里、`sessions.systemPrompt` 存的是空串，
            // 续聊时方案人设直接消失。
            DialogMode.CUSTOM -> ""
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

    /**
     * 建立一次通话会话。
     *
     * @param characterKey 记忆的角色隔离键（plan4 §2.3）。默认由 [character] 推导：
     *        内置角色 = 角色 id，无角色 = [Session.CHARACTER_KEY_DEFAULT]。
     *        ⚠️ 自定义预设必须由调用方显式传 [Session.presetCharacterKey]——
     *        预设不属于任何内置角色，这里推不出来；漏传就是所有预设共用 `default` 一个桶。
     * @param systemPromptOverride 自定义方案的提示词正文（来自 `presets` 表）。
     *
     *        ⚠️ 这个参数存在的唯一理由：**方案的提示词必须真的落进 `sessions.systemPrompt`**。
     *        早期实现把 `preset.prompt` 只放在 ViewModel 的内存字段里，建会话时按
     *        [DialogMode.CUSTOM] 去读全局配置，于是库里存的是空串 —— 通话中看起来正常
     *        （用的是内存那份），一旦「继续对话」`continueSession` 从库里读回来，
     *        方案人设、语气全部消失。传 null 表示"用 [getSystemPrompt] 推导"（内置角色路径）。
     */
    suspend fun createSession(
        mode: DialogMode,
        character: BuiltInCharacter?,
        characterKey: String = character?.id ?: Session.CHARACTER_KEY_DEFAULT,
        systemPromptOverride: String? = null
    ): Session {
        val systemPrompt = systemPromptOverride ?: getSystemPrompt(mode, character)
        val session = Session(
            id = UUID.randomUUID().toString(),
            mode = mode,
            systemPrompt = systemPrompt,
            createdAt = System.currentTimeMillis(),
            // 落库而不是只留在内存：续聊路径只有 sessionId，靠提示词反查推不出自定义预设
            characterKey = characterKey
        )
        sessionRepository.createSession(session)
        return session
    }

    /** 按角色 id 直接建会话（内置角色路由用） */
    suspend fun createSessionForCharacter(characterId: String): Session =
        createSession(DialogMode.LONG, BuiltInCharacters.byId(characterId))
}
