package com.lmstudio.client.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lmstudio.client.data.preferences.AppPreferences
import com.lmstudio.client.ui.chat.LOCAL_TOOL_INFOS
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val baseUrl: String = AppPreferences.DEFAULT_BASE_URL,
    val bearerToken: String = "",
    val localToolRounds: Int = AppPreferences.DEFAULT_LOCAL_TOOL_ROUNDS,
    val enabledLocalTools: Set<String> = LOCAL_TOOL_INFOS.map { it.name }.toSet()
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
        viewModelScope.launch {
            val disabledTools = preferences.disabledLocalToolNames.first()
            _uiState.update {
                it.copy(enabledLocalTools = LOCAL_TOOL_INFOS.map { tool -> tool.name }.toSet() - disabledTools)
            }
        }
        viewModelScope.launch {
            val rounds = preferences.localToolRounds.first()
            _uiState.update { it.copy(localToolRounds = rounds) }
        }
    }

    fun updateBaseUrl(url: String) {
        _uiState.update { it.copy(baseUrl = url) }
    }

    fun updateBearerToken(token: String) {
        _uiState.update { it.copy(bearerToken = token) }
    }

    fun updateLocalToolEnabled(name: String, enabled: Boolean) {
        _uiState.update { current ->
            val updated = if (enabled) {
                current.enabledLocalTools + name
            } else {
                current.enabledLocalTools - name
            }
            current.copy(enabledLocalTools = updated)
        }
    }

    fun updateLocalToolRounds(rounds: Int) {
        _uiState.update {
            it.copy(
                localToolRounds = rounds.coerceIn(
                    AppPreferences.MIN_LOCAL_TOOL_ROUNDS,
                    AppPreferences.MAX_LOCAL_TOOL_ROUNDS
                )
            )
        }
    }

    fun saveAndClose(onDone: () -> Unit) {
        viewModelScope.launch {
            preferences.saveBaseUrl(_uiState.value.baseUrl.trim())
            preferences.saveBearerToken(_uiState.value.bearerToken.trim())
            preferences.saveLocalToolRounds(_uiState.value.localToolRounds)
            preferences.saveDisabledLocalToolNames(
                LOCAL_TOOL_INFOS.map { it.name }.toSet() - _uiState.value.enabledLocalTools
            )
            onDone()
        }
    }

    class Factory(private val preferences: AppPreferences) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SettingsViewModel(preferences) as T
    }
}
