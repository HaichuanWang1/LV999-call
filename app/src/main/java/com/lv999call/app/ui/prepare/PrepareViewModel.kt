package com.lv999call.app.ui.prepare

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lv999call.app.data.repository.ConfigRepository
import com.lv999call.app.domain.model.ApiConfig
import com.lv999call.app.domain.model.DialogMode
import com.lv999call.app.domain.usecase.StartCallUseCase
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class PrepareViewModel(
    private val startCallUseCase: StartCallUseCase,
    private val configRepository: ConfigRepository
) : ViewModel() {

    private val _systemPrompt = MutableStateFlow("")
    val systemPrompt: StateFlow<String> = _systemPrompt.asStateFlow()

    val config: StateFlow<ApiConfig> = configRepository.configFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = ApiConfig()
        )

    fun loadPrompt(mode: DialogMode) {
        viewModelScope.launch {
            _systemPrompt.value = startCallUseCase.getSystemPrompt(mode)
        }
    }

    class Factory(
        private val startCallUseCase: StartCallUseCase,
        private val configRepository: ConfigRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return PrepareViewModel(startCallUseCase, configRepository) as T
        }
    }
}
