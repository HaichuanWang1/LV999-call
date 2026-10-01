package com.lv999call.app.domain.model

/**
 * 会话。
 *
 * @param characterKey 记忆的角色隔离键（plan4 §2.3）：内置角色 = [BuiltInCharacter.id]，
 *        自定义预设 = [presetCharacterKey]，其余 = [CHARACTER_KEY_DEFAULT]。
 *        它落在 sessions 表里而不是只存在 ViewModel 字段里 —— 续聊路径
 *        （`CALL_CONTINUE/{sessionId}`）手里只有 sessionId，靠提示词反查推不出自定义预设，
 *        事实来源必须是数据库那一列
 * @param savedMemoryUpToTs / [savedMemoryUpToId] 记忆总结游标 (timestamp, id)；
 *        0 表示「本会话还没有被总结过」。注意语义上它同时表示「已总结到这里」，
 *        迁移会把存量会话初始化为各自最后一条消息（否则升级后会被批量重总结）
 */
data class Session(
    val id: String,
    val mode: DialogMode,
    val systemPrompt: String,
    val createdAt: Long = System.currentTimeMillis(),
    val messages: List<ChatMessage> = emptyList(),
    val characterKey: String = CHARACTER_KEY_DEFAULT,
    val savedMemoryUpToTs: Long = 0,
    val savedMemoryUpToId: Long = 0
) {
    companion object {
        /** 快速模式 / 推不出角色的会话共用的记忆桶（plan4 §2.3） */
        const val CHARACTER_KEY_DEFAULT = "default"

        /** 自定义预设的记忆键前缀 */
        const val CHARACTER_KEY_PRESET_PREFIX = "preset:"

        /**
         * 自定义预设的记忆隔离键。
         *
         * 写成函数而不是让各处拼字符串：写入侧（StartCallUseCase）与读取侧（补总结/注入）
         * 一旦拼法不一致，记忆就会流进另一个桶，而这种错误在真机上表现为「它记不住」，
         * 极难定位。
         */
        fun presetCharacterKey(presetId: Long): String = "$CHARACTER_KEY_PRESET_PREFIX$presetId"

        /**
         * [presetCharacterKey] 的逆运算：从角色键反查 presetId；不是预设桶就返回 null。
         *
         * 续聊路径（`CALL_CONTINUE/{sessionId}`）只有 sessionId，而 `characterKey` 是会话表里
         * **唯一**记录「这通属于哪个自定义方案」的列。续聊要恢复方案的提示词 / 音色 / 语气 /
         * 头像背景，就得靠它反查回 `presets` 那一行 —— 否则方案通话一续聊就退化成裸模型。
         *
         * 与写入侧共用同一套前缀，避免"写入用 preset:、读取按别的格式解析"这种静默失配。
         */
        fun presetIdFromCharacterKey(characterKey: String): Long? {
            if (!characterKey.startsWith(CHARACTER_KEY_PRESET_PREFIX)) return null
            return characterKey.removePrefix(CHARACTER_KEY_PRESET_PREFIX).toLongOrNull()
        }
    }
}
