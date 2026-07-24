package com.lv999call.app.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lv999call.app.domain.model.ChatMessage
import com.lv999call.app.domain.usecase.ManageSessionUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class HistoryViewModel(
    private val manageSessionUseCase: ManageSessionUseCase
) : ViewModel() {

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private var sessionId: String? = null

    fun loadSession(sessionId: String) {
        this.sessionId = sessionId
        viewModelScope.launch {
            val session = manageSessionUseCase.getSession(sessionId)
            _messages.value = session?.messages ?: emptyList()
        }
    }

    fun getSessionId(): String? = sessionId

    class Factory(
        private val manageSessionUseCase: ManageSessionUseCase
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return HistoryViewModel(manageSessionUseCase) as T
        }
    }
}
