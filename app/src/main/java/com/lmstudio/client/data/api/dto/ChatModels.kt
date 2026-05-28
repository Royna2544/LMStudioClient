package com.lmstudio.client.data.api.dto

import com.google.gson.annotations.SerializedName

data class ChatMessage(
    val role: String,
    val content: String
)

data class ChatRequest(
    val model: String,
    val input: Any,
    val stream: Boolean = true,
    @SerializedName("system_prompt") val systemPrompt: String? = null,
    val temperature: Double = 0.7,
    @SerializedName("top_p") val topP: Double? = null,
    @SerializedName("top_k") val topK: Int? = null,
    @SerializedName("min_p") val minP: Double? = null,
    @SerializedName("repeat_penalty") val repeatPenalty: Double? = null,
    val reasoning: String? = null,
    val store: Boolean = false,
    @SerializedName("previous_response_id") val previousResponseId: String? = null
)

data class ChatInputItem(
    val type: String,
    val content: String? = null,
    @SerializedName("data_url") val dataUrl: String? = null
)

// isThinking=true for dedicated reasoning/thinking tokens and inline <think>...</think> content.
data class StreamToken(
    val text: String,
    val isThinking: Boolean = false,
    val responseId: String? = null
)

data class ChatResponse(
    @SerializedName("model_instance_id") val modelInstanceId: String? = null,
    val output: List<ChatOutputItem> = emptyList(),
    @SerializedName("response_id") val responseId: String? = null
)

data class ChatOutputItem(
    val type: String,
    val content: String? = null
)

data class ChatStreamEvent(
    val type: String,
    val content: String? = null,
    val error: ChatStreamError? = null,
    val result: ChatResponse? = null
)

data class ChatStreamError(
    val type: String? = null,
    val message: String? = null,
    val code: String? = null,
    val param: String? = null
)
