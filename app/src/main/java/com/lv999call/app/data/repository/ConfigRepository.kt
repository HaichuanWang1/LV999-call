package com.lv999call.app.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.lv999call.app.data.remote.AsrApiService
import com.lv999call.app.domain.model.ApiConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "ultraflow_config")

/** 配置仓库 - 使用DataStore持久化 */
class ConfigRepository(private val context: Context) {

    companion object Keys {
        val LLM_BASE_URL = stringPreferencesKey("llm_base_url")
        val LLM_API_KEY = stringPreferencesKey("llm_api_key")
        val LLM_MODEL = stringPreferencesKey("llm_model")
        val MAX_CONTEXT_TOKENS = stringPreferencesKey("max_context_tokens")

        // LLM 采样参数（温度 / top_p / 最大输出）
        val LLM_TEMPERATURE = floatPreferencesKey("llm_temperature")
        val LLM_TOP_P = floatPreferencesKey("llm_top_p")
        val LLM_MAX_OUTPUT_TOKENS = stringPreferencesKey("llm_max_output_tokens")
        // 思考模式开关：不进设置页，但要持久化，避免每次 saveConfig 把它重置回默认
        val LLM_THINKING_ENABLED = stringPreferencesKey("llm_thinking_enabled")

        val ASR_PROVIDER = stringPreferencesKey("asr_provider")
        val ASR_BASE_URL = stringPreferencesKey("asr_base_url")
        val ASR_API_KEY = stringPreferencesKey("asr_api_key")
        val ASR_MODEL = stringPreferencesKey("asr_model")
        val ASR_LANGUAGE = stringPreferencesKey("asr_language")
        val ASR_VOSK_MODEL_ID = stringPreferencesKey("asr_vosk_model_id")

        val TTS_PROVIDER = stringPreferencesKey("tts_provider")
        val TTS_BASE_URL = stringPreferencesKey("tts_base_url")
        val TTS_API_KEY = stringPreferencesKey("tts_api_key")
        val TTS_MODEL = stringPreferencesKey("tts_model")
        val TTS_VOICE_ID = stringPreferencesKey("tts_voice_id")
        val TTS_SPEED = floatPreferencesKey("tts_speed")
        val TTS_PROMPT = stringPreferencesKey("tts_prompt")

        /**
         * 各内置角色的 TTS 风格提示词。
         *
         * 存成一个 JSON 对象（`{"silverwolf":"…","deepseek":"…"}`）而不是给每个角色
         * 开一个 key：角色是会增加的，动态 key 会让 DataStore 里散落一堆
         * `tts_prompt_xxx`，清理与迁移都麻烦。解析失败时静默回落到空表，
         * 不影响其他配置加载。
         */
        val CHARACTER_TTS_PROMPTS = stringPreferencesKey("character_tts_prompts")
        val TTS_REF_AUDIO_BASE64 = stringPreferencesKey("tts_ref_audio_base64")
        val TTS_REF_AUDIO_MIME = stringPreferencesKey("tts_ref_audio_mime")

        // 自定义模式专用参考音频
        val CUSTOM_TTS_REF_AUDIO_BASE64 = stringPreferencesKey("custom_tts_ref_audio_base64")
        val CUSTOM_TTS_REF_AUDIO_MIME = stringPreferencesKey("custom_tts_ref_audio_mime")

        val CHARACTER_AVATAR_URI = stringPreferencesKey("character_avatar_uri")
        val BACKGROUND_URI = stringPreferencesKey("background_uri")
        val CUSTOM_PROMPT = stringPreferencesKey("custom_prompt")
        val LIVE2D_ENABLED = stringPreferencesKey("live2d_enabled")
        val LIVE2D_TRANSFORM_ENABLED = stringPreferencesKey("live2d_transform_enabled")
    }

    val configFlow: Flow<ApiConfig> = context.dataStore.data.map { prefs ->
        ApiConfig(
            llmBaseUrl = prefs[LLM_BASE_URL] ?: "",
            llmApiKey = prefs[LLM_API_KEY] ?: "",
            llmModel = prefs[LLM_MODEL] ?: "mimo-v2.5",
            maxContextTokens = prefs[MAX_CONTEXT_TOKENS]?.toIntOrNull() ?: 200000,
            llmTemperature = prefs[LLM_TEMPERATURE] ?: 0.7f,
            llmTopP = prefs[LLM_TOP_P] ?: 1.0f,
            llmMaxOutputTokens = prefs[LLM_MAX_OUTPUT_TOKENS]?.toIntOrNull() ?: 2048,
            llmThinkingEnabled = prefs[LLM_THINKING_ENABLED]?.toBooleanStrictOrNull() ?: false,
            asrProvider = prefs[ASR_PROVIDER] ?: "custom",
            asrBaseUrl = prefs[ASR_BASE_URL] ?: "",
            asrApiKey = prefs[ASR_API_KEY] ?: "",
            asrModel = prefs[ASR_MODEL] ?: "",
            // 旧版本存的是 zh-CN，下发前会被归一化成 zh；这里顺手读出来就归一，
            // 让设置页显示的也是规范值（否则用户会以为 zh-CN 是服务端要的格式）
            asrLanguage = AsrApiService.normalizeLanguage(prefs[ASR_LANGUAGE] ?: "zh")
                .ifEmpty { "auto" },
            asrVoskModelId = prefs[ASR_VOSK_MODEL_ID] ?: "",
            ttsProvider = prefs[TTS_PROVIDER] ?: "mimo",
            ttsBaseUrl = prefs[TTS_BASE_URL] ?: "",
            ttsApiKey = prefs[TTS_API_KEY] ?: "",
            ttsModel = prefs[TTS_MODEL] ?: "mimo-v2.5-tts-voiceclone",
            ttsVoiceId = prefs[TTS_VOICE_ID] ?: "",
            ttsSpeed = prefs[TTS_SPEED] ?: 1.0f,
            ttsPrompt = prefs[TTS_PROMPT] ?: "",
            characterTtsPrompts = parseCharacterPrompts(prefs[CHARACTER_TTS_PROMPTS]),
            ttsReferenceAudioBase64 = prefs[TTS_REF_AUDIO_BASE64] ?: "",
            ttsReferenceAudioMime = prefs[TTS_REF_AUDIO_MIME] ?: "audio/wav",
            customTtsReferenceAudioBase64 = prefs[CUSTOM_TTS_REF_AUDIO_BASE64] ?: "",
            customTtsReferenceAudioMime = prefs[CUSTOM_TTS_REF_AUDIO_MIME] ?: "audio/wav",
            characterAvatarUri = prefs[CHARACTER_AVATAR_URI] ?: "",
            backgroundUri = prefs[BACKGROUND_URI] ?: "",
            customPrompt = prefs[CUSTOM_PROMPT] ?: "",
            live2dEnabled = prefs[LIVE2D_ENABLED]?.toBooleanStrictOrNull() ?: true,
            live2dTransformEnabled = prefs[LIVE2D_TRANSFORM_ENABLED]?.toBooleanStrictOrNull() ?: true
        )
    }

    suspend fun saveConfig(config: ApiConfig) {
        context.dataStore.edit { prefs ->
            prefs[LLM_BASE_URL] = config.llmBaseUrl
            prefs[LLM_API_KEY] = config.llmApiKey
            prefs[LLM_MODEL] = config.llmModel
            prefs[MAX_CONTEXT_TOKENS] = config.maxContextTokens.toString()
            prefs[LLM_TEMPERATURE] = config.llmTemperature
            prefs[LLM_TOP_P] = config.llmTopP
            prefs[LLM_MAX_OUTPUT_TOKENS] = config.llmMaxOutputTokens.toString()
            prefs[LLM_THINKING_ENABLED] = config.llmThinkingEnabled.toString()
            prefs[ASR_PROVIDER] = config.asrProvider
            prefs[ASR_BASE_URL] = config.asrBaseUrl
            prefs[ASR_API_KEY] = config.asrApiKey
            prefs[ASR_MODEL] = config.asrModel
            prefs[ASR_LANGUAGE] = config.asrLanguage
            prefs[ASR_VOSK_MODEL_ID] = config.asrVoskModelId
            prefs[TTS_PROVIDER] = config.ttsProvider
            prefs[TTS_BASE_URL] = config.ttsBaseUrl
            prefs[TTS_API_KEY] = config.ttsApiKey
            prefs[TTS_MODEL] = config.ttsModel
            prefs[TTS_VOICE_ID] = config.ttsVoiceId
            prefs[TTS_SPEED] = config.ttsSpeed
            prefs[TTS_PROMPT] = config.ttsPrompt
            prefs[CHARACTER_TTS_PROMPTS] = serializeCharacterPrompts(config.characterTtsPrompts)
            prefs[TTS_REF_AUDIO_BASE64] = config.ttsReferenceAudioBase64
            prefs[TTS_REF_AUDIO_MIME] = config.ttsReferenceAudioMime
            prefs[CUSTOM_TTS_REF_AUDIO_BASE64] = config.customTtsReferenceAudioBase64
            prefs[CUSTOM_TTS_REF_AUDIO_MIME] = config.customTtsReferenceAudioMime
            prefs[CHARACTER_AVATAR_URI] = config.characterAvatarUri
            prefs[BACKGROUND_URI] = config.backgroundUri
            prefs[CUSTOM_PROMPT] = config.customPrompt
            prefs[LIVE2D_ENABLED] = config.live2dEnabled.toString()
            prefs[LIVE2D_TRANSFORM_ENABLED] = config.live2dTransformEnabled.toString()
        }
    }

    suspend fun updateCustomPrompt(prompt: String) {
        context.dataStore.edit { prefs ->
            prefs[CUSTOM_PROMPT] = prompt
        }
    }

    suspend fun updateCharacterAvatar(uri: String) {
        context.dataStore.edit { prefs ->
            prefs[CHARACTER_AVATAR_URI] = uri
        }
    }

    suspend fun updateBackground(uri: String) {
        context.dataStore.edit { prefs ->
            prefs[BACKGROUND_URI] = uri
        }
    }

    /**
     * 只更新某个内置角色的 TTS 风格提示词，**不动其他配置**。
     *
     * 用 read-modify-write 而不是整体 [saveConfig]：后者要求调用方先把整份配置读出来，
     * 在准备页这种"改个输入框"的场景下极易把并发改动的其他字段覆盖回旧值。
     */
    suspend fun updateCharacterTtsPrompt(characterId: String, prompt: String) {
        context.dataStore.edit { prefs ->
            val current = parseCharacterPrompts(prefs[CHARACTER_TTS_PROMPTS]).toMutableMap()
            if (prompt.isEmpty()) current.remove(characterId) else current[characterId] = prompt
            prefs[CHARACTER_TTS_PROMPTS] = serializeCharacterPrompts(current)
        }
    }

    private fun parseCharacterPrompts(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return try {
            val obj = org.json.JSONObject(raw)
            buildMap {
                obj.keys().forEach { key ->
                    val v = obj.optString(key, "")
                    if (v.isNotEmpty()) put(key, v)
                }
            }
        } catch (e: Exception) {
            // 配置损坏不该让整份设置读不出来，退回空表即可
            android.util.Log.w("ConfigRepo", "角色 TTS 提示词解析失败: ${e.message}")
            emptyMap()
        }
    }

    private fun serializeCharacterPrompts(map: Map<String, String>): String {
        if (map.isEmpty()) return ""
        val obj = org.json.JSONObject()
        map.forEach { (k, v) -> obj.put(k, v) }
        return obj.toString()
    }
}
