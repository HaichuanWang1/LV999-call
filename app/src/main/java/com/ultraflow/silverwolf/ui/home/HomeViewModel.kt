package com.ultraflow.silverwolf.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ultraflow.silverwolf.data.repository.ConfigRepository
import com.ultraflow.silverwolf.domain.model.ApiConfig
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class HomeViewModel(
    private val configRepository: ConfigRepository
) : ViewModel() {

    val config: StateFlow<ApiConfig> = configRepository.configFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = ApiConfig()
        )
}
