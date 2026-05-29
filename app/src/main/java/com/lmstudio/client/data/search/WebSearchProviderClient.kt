package com.lmstudio.client.data.search

import com.lmstudio.client.data.preferences.SearchProvider
import com.lmstudio.client.data.repository.ChatRepository

interface WebSearchProviderClient {
    val toolName: String
    val toolDescription: String
    val parameters: String

    fun isRequirementsMet(): Boolean

    suspend fun search(arguments: Map<String, String>): Result<String>
}

fun SearchProvider.toWebSearchProviderClient(
    braveSearchApiKey: String,
    repository: ChatRepository
): WebSearchProviderClient =
    when (this) {
        SearchProvider.BRAVE -> BraveSearchProviderClient(
            apiKey = braveSearchApiKey,
            repository = repository
        )
        SearchProvider.DISABLED -> DisabledSearchProviderClient
    }

private object DisabledSearchProviderClient : WebSearchProviderClient {
    override val toolName: String = "web.search"
    override val toolDescription: String = "Search the web for current information using the configured search provider."
    override val parameters: String = "{}"

    override fun isRequirementsMet(): Boolean = false

    override suspend fun search(arguments: Map<String, String>): Result<String> =
        Result.failure(IllegalStateException("Web search is disabled."))
}

private class BraveSearchProviderClient(
    private val apiKey: String,
    private val repository: ChatRepository
) : WebSearchProviderClient {
    override val toolName: String = "web.search"
    override val toolDescription: String = "Search the web for current information using Brave Search."
    override val parameters: String =
        """{"query":"string","max_results":"optional integer 1-10","freshness":"optional any|day|week|month|year","site":"optional domain"}"""

    override fun isRequirementsMet(): Boolean = apiKey.isNotBlank()

    override suspend fun search(arguments: Map<String, String>): Result<String> {
        val query = arguments["query"].orEmpty()
        val maxResults = arguments["max_results"]?.toIntOrNull()?.coerceIn(1, 10) ?: 5
        val freshness = arguments["freshness"]
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.isNotBlank() && it != "any" }
        val site = arguments["site"]?.takeIf { it.isNotBlank() }

        return repository.braveWebSearch(
            apiKey = apiKey,
            query = query,
            maxResults = maxResults,
            freshness = freshness,
            site = site
        )
    }
}
