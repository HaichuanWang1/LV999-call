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

    /** 「保存并跳走」的重复提交闸门（见 [savePresetAndThen]），只跑在主线程，无需加锁 */
    private var isSaving = false

    /**
     * 保存预设，返回新插入的ID（新建时）或原ID（更新时）
     */
    suspend fun savePresetAndGetId(
        id: Long?,
        name: String,
        prompt: String,
        ttsPrompt: String,
        refAudioBase64: String,
        refAudioMime: String,
        avatarUri: String,
        backgroundUri: String
    ): Long {
        val preset = PresetEntity(
            id = id ?: 0,
            name = name,
            prompt = prompt,
            ttsPrompt = ttsPrompt,
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

    /**
     * 保存预设，并在写库**完成后**回调（新建时回传新 id，更新时回传原 id）。
     *
     * 为什么把"保存 → 拿 id → 跳转"整条链收进 ViewModel：调用方（NavGraph）以前
     * 自己 `CoroutineScope(Dispatchers.Main).launch { … }` 手搓一个 scope，
     * 它不随页面生命周期取消 —— 用户点完"开始通话"立刻返回首页，协程仍会写库并
     * 在已经离开的 NavController 上触发一次导航。用 [viewModelScope] 则页面销毁即取消，
     * 且回调天然跑在主线程（可以直接 navigate）。
     *
     * ⚠️ 回调里不要再做耗时操作：它跑在 Main 上，只该用来导航/关页面。
     *
     * 带一个"正在保存"闸门：编辑页的两个按钮都是"保存后再跳走"，双击会连着触发两次
     * —— 新建场景下那就是**建出两个一模一样的方案**（外加一次多余导航）。
     * 闸门在写库期间只放行一次；失败会自动复位，用户可以再点。
     */
    fun savePresetAndThen(
        id: Long?,
        name: String,
        prompt: String,
        ttsPrompt: String,
        refAudioBase64: String,
        refAudioMime: String,
        avatarUri: String,
        backgroundUri: String,
        onSaved: (Long) -> Unit
    ) {
        if (isSaving) return
        isSaving = true
        viewModelScope.launch {
            try {
                val savedId = savePresetAndGetId(
                    id = id,
                    name = name,
                    prompt = prompt,
                    ttsPrompt = ttsPrompt,
                    refAudioBase64 = refAudioBase64,
                    refAudioMime = refAudioMime,
                    avatarUri = avatarUri,
                    backgroundUri = backgroundUri
                )
                onSaved(savedId)
            } finally {
                isSaving = false
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
