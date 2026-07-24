package com.ultraflow.silverwolf.domain.usecase

import com.ultraflow.silverwolf.data.repository.ChatRepository
import com.ultraflow.silverwolf.data.repository.ConfigRepository
import com.ultraflow.silverwolf.data.repository.SessionRepository
import com.ultraflow.silverwolf.domain.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.util.UUID

/**
 * 启动通话用例
 * 处理会话创建、系统提示词构建
 */
class StartCallUseCase(
    private val sessionRepository: SessionRepository,
    private val configRepository: ConfigRepository
) {
    // 默认系统提示词（快速模式）
    private val quickPrompt = """你是一个名叫银狼的AI助手。你性格活泼、机智，喜欢用轻松幽默的方式与人交流。
请用简短自然的口语化回复，就像朋友之间聊天一样。不要使用markdown格式。
每次回复控制在2-3句话以内，除非用户明确需要详细解释。"""

    // 长提示词模式
    private val longPrompt = """你是银狼，一个来自"崩坏：星穹铁道"世界的天才骇客。
你的性格特点：
- 聪明机智，说话带点玩世不恭的态度
- 喜欢用游戏术语和网络用语
- 偶尔会冒出一些技术术语
- 说话简洁有力，不喜欢啰嗦
- 有轻微的中二倾向

你的行为规范：
- 始终保持银狼的角色设定
- 用口语化、自然的方式回复
- 回复要简短有力，像真人聊天
- 可以适当使用emoji和颜文字
- 如果被问到你是AI，可以用银狼的方式巧妙回避

对话风格示例：
用户：你好呀
银狼：哟，来了来了~今天想聊点什么？🎮

用户：你是谁
银狼：银狼，星际骇客。不过现在嘛…暂时在陪你聊天。怎么，有任务要交给我？(￣▽￣)"""

    /**
     * 获取当前模式的系统提示词
     */
    suspend fun getSystemPrompt(mode: DialogMode): String {
        return when (mode) {
            DialogMode.QUICK -> quickPrompt
            DialogMode.LONG -> longPrompt
            DialogMode.CUSTOM -> {
                val config = configRepository.configFlow.first()
                config.customPrompt.ifEmpty { "" }
            }
        }
    }

    /**
     * 创建新的通话会话
     */
    suspend fun createSession(mode: DialogMode): Session {
        val systemPrompt = getSystemPrompt(mode)
        val session = Session(
            id = UUID.randomUUID().toString(),
            mode = mode,
            systemPrompt = systemPrompt,
            createdAt = System.currentTimeMillis()
        )
        sessionRepository.createSession(session)
        return session
    }
}
