package com.lmstudio.client.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lmstudio.client.data.preferences.AppPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val baseUrl: String = AppPreferences.DEFAULT_BASE_URL,
    val bearerToken: String = ""
)

class SettingsViewModel(
    private val preferences: AppPreferences
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val url = preferences.baseUrl.first()
            _uiState.update { it.copy(baseUrl = url) }
        }
        viewModelScope.launch {
            val token = preferences.bearerToken.first()
            _uiState.update { it.copy(bearerToken = token) }
        }
    }

    fun updateBaseUrl(url: String) {
        _uiState.update { it.copy(baseUrl = url) }
    }

    fun updateBearerToken(token: String) {
        _uiState.update { it.copy(bearerToken = token) }
    }

    fun saveAndClose(onDone: () -> Unit) {
        viewModelScope.launch {
            preferences.saveBaseUrl(_uiState.value.baseUrl.trim())
            preferences.saveBearerToken(_uiState.value.bearerToken.trim())
            onDone()
        }
    }

    class Factory(private val preferences: AppPreferences) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SettingsViewModel(preferences) as T
    }
}
