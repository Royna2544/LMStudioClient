package com.lmstudio.client.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "app_settings")

enum class SearchProvider(
    val id: String,
    val label: String,
    val description: String
) {
    DISABLED("disabled", "Disabled", "Web search tools are not advertised."),
    BRAVE("brave", "Brave Search", "Use Brave Search API for web results.");

    companion object {
        fun fromId(id: String): SearchProvider =
            entries.firstOrNull { it.id == id } ?: DISABLED
    }
}

class AppPreferences(context: Context) {

    private val dataStore = context.dataStore

    companion object {
        private val BASE_URL_KEY = stringPreferencesKey("base_url")
        private val SELECTED_MODEL_KEY = stringPreferencesKey("selected_model")
        private val BEARER_TOKEN_KEY = stringPreferencesKey("bearer_token")
        private val CHAT_HISTORY_KEY = stringPreferencesKey("chat_history")
        private val DISABLED_LOCAL_TOOLS_KEY = stringSetPreferencesKey("disabled_local_tools")
        private val LOCAL_TOOL_ROUNDS_KEY = intPreferencesKey("local_tool_rounds")
        private val SEARCH_PROVIDER_KEY = stringPreferencesKey("search_provider")
        private val BRAVE_SEARCH_API_KEY_KEY = stringPreferencesKey("brave_search_api_key")
        const val DEFAULT_BASE_URL = "http://10.0.2.2:1234"
        const val DEFAULT_LOCAL_TOOL_ROUNDS = 8
        const val MIN_LOCAL_TOOL_ROUNDS = 1
        const val MAX_LOCAL_TOOL_ROUNDS = 20
    }

    val baseUrl: Flow<String> = dataStore.data.map { prefs ->
        prefs[BASE_URL_KEY] ?: DEFAULT_BASE_URL
    }

    val selectedModel: Flow<String> = dataStore.data.map { prefs ->
        prefs[SELECTED_MODEL_KEY] ?: ""
    }

    val bearerToken: Flow<String> = dataStore.data.map { prefs ->
        prefs[BEARER_TOKEN_KEY] ?: ""
    }

    val chatHistoryJson: Flow<String> = dataStore.data.map { prefs ->
        prefs[CHAT_HISTORY_KEY] ?: ""
    }

    val disabledLocalToolNames: Flow<Set<String>> = dataStore.data.map { prefs ->
        prefs[DISABLED_LOCAL_TOOLS_KEY] ?: emptySet()
    }

    val localToolRounds: Flow<Int> = dataStore.data.map { prefs ->
        (prefs[LOCAL_TOOL_ROUNDS_KEY] ?: DEFAULT_LOCAL_TOOL_ROUNDS)
            .coerceIn(MIN_LOCAL_TOOL_ROUNDS, MAX_LOCAL_TOOL_ROUNDS)
    }

    val searchProvider: Flow<SearchProvider> = dataStore.data.map { prefs ->
        SearchProvider.fromId(prefs[SEARCH_PROVIDER_KEY] ?: SearchProvider.DISABLED.id)
    }

    val braveSearchApiKey: Flow<String> = dataStore.data.map { prefs ->
        prefs[BRAVE_SEARCH_API_KEY_KEY] ?: ""
    }

    suspend fun saveBaseUrl(url: String) {
        dataStore.edit { prefs -> prefs[BASE_URL_KEY] = url }
    }

    suspend fun saveSelectedModel(model: String) {
        dataStore.edit { prefs -> prefs[SELECTED_MODEL_KEY] = model }
    }

    suspend fun saveBearerToken(token: String) {
        dataStore.edit { prefs -> prefs[BEARER_TOKEN_KEY] = token }
    }

    suspend fun saveChatHistoryJson(json: String) {
        dataStore.edit { prefs -> prefs[CHAT_HISTORY_KEY] = json }
    }

    suspend fun saveDisabledLocalToolNames(names: Set<String>) {
        dataStore.edit { prefs -> prefs[DISABLED_LOCAL_TOOLS_KEY] = names }
    }

    suspend fun saveLocalToolRounds(rounds: Int) {
        dataStore.edit { prefs ->
            prefs[LOCAL_TOOL_ROUNDS_KEY] = rounds.coerceIn(MIN_LOCAL_TOOL_ROUNDS, MAX_LOCAL_TOOL_ROUNDS)
        }
    }

    suspend fun saveSearchProvider(provider: SearchProvider) {
        dataStore.edit { prefs -> prefs[SEARCH_PROVIDER_KEY] = provider.id }
    }

    suspend fun saveBraveSearchApiKey(apiKey: String) {
        dataStore.edit { prefs -> prefs[BRAVE_SEARCH_API_KEY_KEY] = apiKey }
    }
}
