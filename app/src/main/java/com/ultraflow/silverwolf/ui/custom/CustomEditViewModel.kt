package com.ultraflow.silverwolf.ui.custom

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ultraflow.silverwolf.data.repository.ConfigRepository
import com.ultraflow.silverwolf.domain.model.ApiConfig
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class CustomEditViewModel(
    private val configRepository: ConfigRepository
) : ViewModel() {

    val config: StateFlow<ApiConfig> = configRepository.configFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = ApiConfig()
        )

    fun saveCustomConfig(
        prompt: String,
        avatarUri: String?,
        backgroundUri: String?,
        refAudioBase64: String,
        refAudioMime: String
    ) {
        viewModelScope.launch {
            val current = config.value
            configRepository.saveConfig(
                current.copy(
                    customPrompt = prompt,
                    characterAvatarUri = avatarUri ?: current.characterAvatarUri,
                    backgroundUri = backgroundUri ?: current.backgroundUri,
                    customTtsReferenceAudioBase64 = refAudioBase64,
                    customTtsReferenceAudioMime = refAudioMime.ifEmpty { "audio/wav" }
                )
            )
        }
    }

    class Factory(
        private val configRepository: ConfigRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return CustomEditViewModel(configRepository) as T
        }
    }
}
