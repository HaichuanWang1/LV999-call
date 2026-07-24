package com.ultraflow.silverwolf.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ultraflow.silverwolf.audio.VoskModelManager
import com.ultraflow.silverwolf.data.repository.ChatRepository
import com.ultraflow.silverwolf.data.repository.ConfigRepository
import com.ultraflow.silverwolf.domain.model.ApiConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val configRepository: ConfigRepository,
    private val chatRepository: ChatRepository,
    val voskModelManager: VoskModelManager
) : ViewModel() {

    val config: StateFlow<ApiConfig> = configRepository.configFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = ApiConfig()
        )

    // Vosk 模型下载状态
    private val _voskDownloadState = MutableStateFlow<VoskDownloadState>(VoskDownloadState.Idle)
    val voskDownloadState: StateFlow<VoskDownloadState> = _voskDownloadState.asStateFlow()

    sealed class VoskDownloadState {
        data object Idle : VoskDownloadState()
        data class Downloading(val modelId: String, val progress: Float) : VoskDownloadState()
        data class Success(val modelId: String) : VoskDownloadState()
        data class Error(val modelId: String, val message: String) : VoskDownloadState()
    }

    fun saveConfig(newConfig: ApiConfig) {
        viewModelScope.launch {
            configRepository.saveConfig(newConfig)
        }
    }

    /** 获取模型列表，返回 Pair(模型ID列表, 最大context_length) */
    suspend fun fetchModelsWithContext(baseUrl: String, apiKey: String): Pair<List<String>, Int> {
        val items = chatRepository.fetchModels(baseUrl, apiKey)
        val maxContext = items.mapNotNull { it.context_length }.maxOrNull() ?: 200000
        return Pair(items.map { it.id }, maxContext)
    }

    /** 下载 Vosk 模型 */
    fun downloadVoskModel(model: VoskModelManager.VoskModel) {
        viewModelScope.launch {
            _voskDownloadState.value = VoskDownloadState.Downloading(model.id, 0f)
            val result = voskModelManager.downloadModel(model) { progress ->
                _voskDownloadState.value = VoskDownloadState.Downloading(model.id, progress)
            }
            _voskDownloadState.value = if (result.isSuccess) {
                VoskDownloadState.Success(model.id)
            } else {
                VoskDownloadState.Error(model.id, result.exceptionOrNull()?.message ?: "下载失败")
            }
        }
    }

    /** 删除 Vosk 模型 */
    fun deleteVoskModel(modelId: String) {
        voskModelManager.deleteModel(modelId)
        // 如果删除的是当前选中的模型，清除配置
        if (config.value.asrVoskModelId == modelId) {
            viewModelScope.launch {
                configRepository.saveConfig(config.value.copy(asrVoskModelId = ""))
            }
        }
    }

    /** 重置下载状态 */
    fun resetDownloadState() {
        _voskDownloadState.value = VoskDownloadState.Idle
    }

    class Factory(
        private val configRepository: ConfigRepository,
        private val chatRepository: ChatRepository,
        private val voskModelManager: VoskModelManager
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SettingsViewModel(configRepository, chatRepository, voskModelManager) as T
        }
    }
}
