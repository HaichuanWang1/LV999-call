package com.ultraflow.silverwolf.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ultraflow.silverwolf.domain.model.ApiConfig
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

        val ASR_PROVIDER = stringPreferencesKey("asr_provider")
        val ASR_BASE_URL = stringPreferencesKey("asr_base_url")
        val ASR_API_KEY = stringPreferencesKey("asr_api_key")
        val ASR_LANGUAGE = stringPreferencesKey("asr_language")
        val ASR_VOSK_MODEL_ID = stringPreferencesKey("asr_vosk_model_id")

        val TTS_PROVIDER = stringPreferencesKey("tts_provider")
        val TTS_BASE_URL = stringPreferencesKey("tts_base_url")
        val TTS_API_KEY = stringPreferencesKey("tts_api_key")
        val TTS_MODEL = stringPreferencesKey("tts_model")
        val TTS_VOICE_ID = stringPreferencesKey("tts_voice_id")
        val TTS_SPEED = floatPreferencesKey("tts_speed")
        val TTS_REF_AUDIO_BASE64 = stringPreferencesKey("tts_ref_audio_base64")
        val TTS_REF_AUDIO_MIME = stringPreferencesKey("tts_ref_audio_mime")

        // 自定义模式专用参考音频
        val CUSTOM_TTS_REF_AUDIO_BASE64 = stringPreferencesKey("custom_tts_ref_audio_base64")
        val CUSTOM_TTS_REF_AUDIO_MIME = stringPreferencesKey("custom_tts_ref_audio_mime")

        val CHARACTER_AVATAR_URI = stringPreferencesKey("character_avatar_uri")
        val BACKGROUND_URI = stringPreferencesKey("background_uri")
        val CUSTOM_PROMPT = stringPreferencesKey("custom_prompt")
    }

    val configFlow: Flow<ApiConfig> = context.dataStore.data.map { prefs ->
        ApiConfig(
            llmBaseUrl = prefs[LLM_BASE_URL] ?: "",
            llmApiKey = prefs[LLM_API_KEY] ?: "",
            llmModel = prefs[LLM_MODEL] ?: "mimo-v2.5",
            maxContextTokens = prefs[MAX_CONTEXT_TOKENS]?.toIntOrNull() ?: 200000,
            asrProvider = prefs[ASR_PROVIDER] ?: "custom",
            asrBaseUrl = prefs[ASR_BASE_URL] ?: "",
            asrApiKey = prefs[ASR_API_KEY] ?: "",
            asrLanguage = prefs[ASR_LANGUAGE] ?: "zh-CN",
            asrVoskModelId = prefs[ASR_VOSK_MODEL_ID] ?: "",
            ttsProvider = prefs[TTS_PROVIDER] ?: "mimo",
            ttsBaseUrl = prefs[TTS_BASE_URL] ?: "",
            ttsApiKey = prefs[TTS_API_KEY] ?: "",
            ttsModel = prefs[TTS_MODEL] ?: "mimo-v2.5-tts-voiceclone",
            ttsVoiceId = prefs[TTS_VOICE_ID] ?: "",
            ttsSpeed = prefs[TTS_SPEED] ?: 1.0f,
            ttsReferenceAudioBase64 = prefs[TTS_REF_AUDIO_BASE64] ?: "",
            ttsReferenceAudioMime = prefs[TTS_REF_AUDIO_MIME] ?: "audio/wav",
            customTtsReferenceAudioBase64 = prefs[CUSTOM_TTS_REF_AUDIO_BASE64] ?: "",
            customTtsReferenceAudioMime = prefs[CUSTOM_TTS_REF_AUDIO_MIME] ?: "audio/wav",
            characterAvatarUri = prefs[CHARACTER_AVATAR_URI] ?: "",
            backgroundUri = prefs[BACKGROUND_URI] ?: "",
            customPrompt = prefs[CUSTOM_PROMPT] ?: ""
        )
    }

    suspend fun saveConfig(config: ApiConfig) {
        context.dataStore.edit { prefs ->
            prefs[LLM_BASE_URL] = config.llmBaseUrl
            prefs[LLM_API_KEY] = config.llmApiKey
            prefs[LLM_MODEL] = config.llmModel
            prefs[MAX_CONTEXT_TOKENS] = config.maxContextTokens.toString()
            prefs[ASR_PROVIDER] = config.asrProvider
            prefs[ASR_BASE_URL] = config.asrBaseUrl
            prefs[ASR_API_KEY] = config.asrApiKey
            prefs[ASR_LANGUAGE] = config.asrLanguage
            prefs[ASR_VOSK_MODEL_ID] = config.asrVoskModelId
            prefs[TTS_PROVIDER] = config.ttsProvider
            prefs[TTS_BASE_URL] = config.ttsBaseUrl
            prefs[TTS_API_KEY] = config.ttsApiKey
            prefs[TTS_MODEL] = config.ttsModel
            prefs[TTS_VOICE_ID] = config.ttsVoiceId
            prefs[TTS_SPEED] = config.ttsSpeed
            prefs[TTS_REF_AUDIO_BASE64] = config.ttsReferenceAudioBase64
            prefs[TTS_REF_AUDIO_MIME] = config.ttsReferenceAudioMime
            prefs[CUSTOM_TTS_REF_AUDIO_BASE64] = config.customTtsReferenceAudioBase64
            prefs[CUSTOM_TTS_REF_AUDIO_MIME] = config.customTtsReferenceAudioMime
            prefs[CHARACTER_AVATAR_URI] = config.characterAvatarUri
            prefs[BACKGROUND_URI] = config.backgroundUri
            prefs[CUSTOM_PROMPT] = config.customPrompt
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
}
