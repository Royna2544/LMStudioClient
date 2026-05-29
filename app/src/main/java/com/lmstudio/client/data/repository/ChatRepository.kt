package com.lmstudio.client.data.repository

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.lmstudio.client.data.api.dto.ChatRequest
import com.lmstudio.client.data.api.dto.ChatResponse
import com.lmstudio.client.data.api.dto.ChatStreamEvent
import com.lmstudio.client.data.api.dto.ModelData
import com.lmstudio.client.data.api.dto.NativeModelListResponse
import com.lmstudio.client.data.api.dto.StreamToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.IOException
import java.util.concurrent.TimeUnit

class ChatRepository(
    private val okHttpClient: OkHttpClient,
    private val gson: Gson
) {
    // Separate client for SSE — no read timeout so the stream stays open indefinitely
    private val streamingClient: OkHttpClient = okHttpClient.newBuilder()
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    suspend fun getModels(baseUrl: String, bearerToken: String = ""): Result<List<ModelData>> = withContext(Dispatchers.IO) {
        try {
            val url = "${baseUrl.trimEnd('/')}/api/v1/models"
            val request = Request.Builder().url(url).get()
                .applyAuth(bearerToken)
                .build()
            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("Server returned ${response.code}"))
            }
            val body = response.body?.string()
                ?: return@withContext Result.failure(Exception("Empty response body"))
            val modelList = gson.fromJson(body, NativeModelListResponse::class.java)
            Result.success(
                modelList.models
                    .filter { it.type != "embedding" }
                    .map { it.toModelData() }
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun streamChat(baseUrl: String, request: ChatRequest, bearerToken: String = ""): Flow<StreamToken> =
        if (request.stream) streamChatCompletion(baseUrl, request, bearerToken) else completeChat(baseUrl, request, bearerToken)

    private fun streamChatCompletion(baseUrl: String, request: ChatRequest, bearerToken: String): Flow<StreamToken> = callbackFlow {
        val url = "${baseUrl.trimEnd('/')}/api/v1/chat"
        val json = gson.toJson(request)
        val httpRequest = Request.Builder()
            .url(url)
            .post(json.toRequestBody("application/json".toMediaType()))
            .header("Accept", "text/event-stream")
            .applyAuth(bearerToken)
            .build()

        val factory = EventSources.createFactory(streamingClient)
        val listener = object : EventSourceListener() {
            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String
            ) {
                if (data.trim() == "[DONE]") {
                    close()
                    return
                }
                try {
                    val event = gson.fromJson(data, ChatStreamEvent::class.java)
                    val eventType = type ?: event.type
                    when (eventType) {
                        "reasoning.delta" -> {
                            if (!event.content.isNullOrEmpty()) {
                                trySend(StreamToken(event.content, isThinking = true))
                            }
                        }
                        "message.delta" -> {
                            if (!event.content.isNullOrEmpty()) {
                                trySend(StreamToken(event.content, isThinking = false))
                            }
                        }
                        "error" -> {
                            close(IOException(event.errorMessage() ?: "LM Studio stream error"))
                        }
                        "chat.end" -> {
                            event.result?.responseId?.let { responseId ->
                                trySend(StreamToken(text = "", responseId = responseId))
                            }
                            close()
                        }
                    }
                } catch (_: Exception) {
                    // Skip malformed SSE chunks
                }
            }

            override fun onFailure(
                eventSource: EventSource,
                t: Throwable?,
                response: Response?
            ) {
                val message = when {
                    response != null && !response.isSuccessful -> response.errorMessage()
                    !t?.message.isNullOrBlank() -> t?.message
                    response?.isSuccessful == true -> "LM Studio closed the stream before returning a response."
                    else -> "Stream failed"
                } ?: "Stream failed"
                close(IOException(message))
            }

            override fun onClosed(eventSource: EventSource) {
                close()
            }
        }

        val eventSource = factory.newEventSource(httpRequest, listener)
        awaitClose { eventSource.cancel() }
    }

    private fun completeChat(baseUrl: String, request: ChatRequest, bearerToken: String): Flow<StreamToken> = flow {
        val completion = withContext(Dispatchers.IO) {
            val url = "${baseUrl.trimEnd('/')}/api/v1/chat"
            val json = gson.toJson(request)
            val httpRequest = Request.Builder()
                .url(url)
                .post(json.toRequestBody("application/json".toMediaType()))
                .applyAuth(bearerToken)
                .build()

            okHttpClient.newCall(httpRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException(response.errorMessage() ?: "Server returned ${response.code}")
                }
                val body = response.body?.string() ?: throw IOException("Empty response body")
                gson.fromJson(body, ChatResponse::class.java)
            }
        }

        completion.output.forEach { item ->
            when (item.type) {
                "reasoning" -> if (!item.content.isNullOrEmpty()) {
                    emit(StreamToken(item.content, isThinking = true))
                }
                "message" -> if (!item.content.isNullOrEmpty()) {
                    emit(StreamToken(item.content, isThinking = false))
                }
            }
        }
        completion.responseId?.let { emit(StreamToken(text = "", responseId = it)) }
    }
}

private fun Request.Builder.applyAuth(token: String): Request.Builder =
    if (token.isNotBlank()) header("Authorization", "Bearer $token") else this

private fun ChatStreamEvent.errorMessage(): String? =
    error?.message?.takeIf { it.isNotBlank() }

private fun Response.errorMessage(): String? {
    val bodyText = body?.string() ?: return codeMessage()
    return extractErrorMessage(bodyText) ?: codeMessage()
}

private fun Response.codeMessage(): String = "Server returned $code"

private fun extractErrorMessage(bodyText: String): String? {
    try {
        val root = JsonParser.parseString(bodyText)
        if (!root.isJsonObject) return null
        val error = root.asJsonObject.get("error")
        val message = when {
            error == null || error.isJsonNull -> null
            error.isJsonPrimitive -> error.asString
            error.isJsonObject -> error.asJsonObject.get("message")?.asString
            else -> null
        }
        return message?.takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        return null
    }
}
