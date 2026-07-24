package com.lv999call.app.ui.custom

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lv999call.app.data.local.dao.PresetDao
import com.lv999call.app.data.local.entity.PresetEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class PresetViewModel(
    private val presetDao: PresetDao
) : ViewModel() {

    val presets: StateFlow<List<PresetEntity>> = presetDao.getAllPresets()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * 保存预设，返回新插入的ID（新建时）或原ID（更新时）
     */
    suspend fun savePresetAndGetId(
        id: Long?,
        name: String,
        prompt: String,
        refAudioBase64: String,
        refAudioMime: String,
        avatarUri: String,
        backgroundUri: String
    ): Long {
        val preset = PresetEntity(
            id = id ?: 0,
            name = name,
            prompt = prompt,
            refAudioBase64 = refAudioBase64,
            refAudioMime = refAudioMime,
            avatarUri = avatarUri,
            backgroundUri = backgroundUri
        )
        return if (id != null && id > 0) {
            presetDao.update(preset)
            id
        } else {
            presetDao.insert(preset)
        }
    }

    fun savePreset(
        id: Long?,
        name: String,
        prompt: String,
        refAudioBase64: String,
        refAudioMime: String,
        avatarUri: String,
        backgroundUri: String
    ) {
        viewModelScope.launch {
            val preset = PresetEntity(
                id = id ?: 0,
                name = name,
                prompt = prompt,
                refAudioBase64 = refAudioBase64,
                refAudioMime = refAudioMime,
                avatarUri = avatarUri,
                backgroundUri = backgroundUri
            )
            if (id != null && id > 0) {
                presetDao.update(preset)
            } else {
                presetDao.insert(preset)
            }
        }
    }

    fun deletePreset(id: Long) {
        viewModelScope.launch {
            presetDao.deleteById(id)
        }
    }

    suspend fun getPreset(id: Long): PresetEntity? {
        return presetDao.getPresetById(id)
    }

    class Factory(private val presetDao: PresetDao) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return PresetViewModel(presetDao) as T
        }
    }
}
