package com.lmstudio.client.ui.chat

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.lmstudio.client.data.api.dto.ChatInputItem
import com.lmstudio.client.data.api.dto.ChatMessage
import com.lmstudio.client.data.api.dto.ChatRequest
import com.lmstudio.client.data.api.dto.ModelData
import com.lmstudio.client.data.api.dto.StreamToken
import com.lmstudio.client.data.preferences.AppPreferences
import com.lmstudio.client.data.repository.ChatRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode
import java.io.File
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

enum class ReasoningMode(val label: String, val apiValue: String?) {
    OFF("Off", "off"),
    AUTO("Auto", null),
    ON("On", "on")
}

data class ChatSettings(
    val systemPrompt: String = "",
    val stream: Boolean = true,
    val temperature: Float = 0.7f,
    val topP: Float = 0.95f,
    val topK: Int = 40,
    val minP: Float = 0.05f,
    val repeatPenaltyEnabled: Boolean = false,
    val repeatPenalty: Float = 1.1f,
    val reasoningMode: ReasoningMode = ReasoningMode.AUTO,
    val saveRemoteHistory: Boolean = false
)

data class LocalToolInfo(
    val name: String,
    val description: String
)

val LOCAL_TOOL_INFOS = listOf(
    LocalToolInfo("current_time.time", "Return the current local date, time, UTC offset, and timezone."),
    LocalToolInfo("system.device", "Return basic Android device manufacturer, brand, model, and hardware identifiers."),
    LocalToolInfo("system.os", "Return Android version, SDK level, build ID, security patch, timezone, and locale."),
    LocalToolInfo("system.battery", "Return battery level, charging state, health, temperature, voltage, and saver mode."),
    LocalToolInfo("system.cpu", "Return CPU core count, supported ABIs, architecture, and best-effort CPU model."),
    LocalToolInfo("system.memory", "Return system memory and app heap usage."),
    LocalToolInfo("system.display", "Return display resolution, density, orientation, and font scale."),
    LocalToolInfo("system.network", "Return current network connectivity, transport type, and metered status."),
    LocalToolInfo("system.thermal", "Return Android thermal status when supported."),
    LocalToolInfo("system.app", "Return this app's package name, version, and build code.")
)

data class UiMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: String,
    val content: String = "",
    val thinkingContent: String = "",
    val toolCalls: List<UiToolCall> = emptyList(),
    val errorMessage: String? = null,
    val requestText: String? = null,
    val requestAttachments: List<PendingAttachment> = emptyList(),
    val responseStartedAtMillis: Long? = null,
    val firstTokenAtMillis: Long? = null,
    val responseCompletedAtMillis: Long? = null,
    val isThinking: Boolean = false,
    val isStreaming: Boolean = false
)

data class UiToolCall(
    val index: Int,
    val name: String,
    val output: String,
    val durationMillis: Long,
    val succeeded: Boolean
)

data class ChatSession(
    val id: String = UUID.randomUUID().toString(),
    val title: String = DEFAULT_CHAT_TITLE,
    val messages: List<UiMessage> = emptyList(),
    val remoteResponseId: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
    val isTemporary: Boolean = false,
    val isPinned: Boolean = false
)

enum class PendingAttachmentType {
    IMAGE,
    TEXT
}

data class PendingAttachment(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val type: PendingAttachmentType,
    val mimeType: String,
    val dataUrl: String? = null,
    val text: String? = null
)

data class ChatUiState(
    val chatSessions: List<ChatSession> = emptyList(),
    val currentChatId: String = "",
    val messages: List<UiMessage> = emptyList(),
    val inputText: String = "",
    val pendingAttachments: List<PendingAttachment> = emptyList(),
    val isStreaming: Boolean = false,
    val availableModels: List<ModelData> = emptyList(),
    val selectedModel: String = "",
    val baseUrl: String = AppPreferences.DEFAULT_BASE_URL,
    val bearerToken: String = "",
    val enabledLocalTools: Set<String> = LOCAL_TOOL_INFOS.map { it.name }.toSet(),
    val localToolRounds: Int = AppPreferences.DEFAULT_LOCAL_TOOL_ROUNDS,
    val chatSettings: ChatSettings = ChatSettings(),
    val error: String? = null,
    val isLoadingModels: Boolean = false,
    val remoteResponseId: String? = null,
    val isTemporaryChat: Boolean = false,
    val temporarySession: ChatSession? = null
)

class ChatViewModel(
    private val applicationContext: Context,
    private val repository: ChatRepository,
    private val preferences: AppPreferences
) : ViewModel() {

    private val initialSession = ChatSession()
    private val _uiState = MutableStateFlow(
        ChatUiState(
            chatSessions = listOf(initialSession),
            currentChatId = initialSession.id
        )
    )
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val historyGson = Gson()
    private var streamingJob: Job? = null
    private var historyLoaded = false
    private var persistHistoryJob: Job? = null

    init {
        viewModelScope.launch {
            val savedSessions = parseSavedSessions(preferences.chatHistoryJson.first())
            val sessions = savedSessions.ifEmpty { listOf(initialSession) }
            val currentSession = sessions.first()
            _uiState.update { current ->
                current.copy(
                    chatSessions = sessions,
                    currentChatId = currentSession.id,
                    messages = currentSession.messages,
                    remoteResponseId = currentSession.remoteResponseId,
                    isTemporaryChat = currentSession.isTemporary
                )
            }
            historyLoaded = true
            persistChatHistory()
        }
        viewModelScope.launch {
            // Await first values so loadModels() uses the real saved URL/token
            val url = preferences.baseUrl.first()
            val token = preferences.bearerToken.first()
            _uiState.update { it.copy(baseUrl = url, bearerToken = token) }
            loadModels()
        }
        viewModelScope.launch {
            preferences.baseUrl.collect { url ->
                _uiState.update { it.copy(baseUrl = url) }
            }
        }
        viewModelScope.launch {
            preferences.selectedModel.collect { model ->
                _uiState.update { it.copy(selectedModel = model) }
            }
        }
        viewModelScope.launch {
            preferences.bearerToken.collect { token ->
                _uiState.update { it.copy(bearerToken = token) }
            }
        }
        viewModelScope.launch {
            preferences.disabledLocalToolNames.collect { disabledTools ->
                val enabledTools = LOCAL_TOOL_INFOS.map { it.name }.toSet() - disabledTools
                _uiState.update { it.copy(enabledLocalTools = enabledTools) }
            }
        }
        viewModelScope.launch {
            preferences.localToolRounds.collect { rounds ->
                _uiState.update { it.copy(localToolRounds = rounds) }
            }
        }
    }

    fun loadModels() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingModels = true, error = null) }
            repository.getModels(_uiState.value.baseUrl, _uiState.value.bearerToken).fold(
                onSuccess = { models ->
                    val ids = models.map { it.id }
                    val current = _uiState.value.selectedModel
                    val selected = if (current in ids) current else ids.firstOrNull() ?: ""
                    _uiState.update {
                        it.copy(availableModels = models, selectedModel = selected, isLoadingModels = false)
                    }
                    if (selected.isNotEmpty()) preferences.saveSelectedModel(selected)
                },
                onFailure = { e ->
                    _uiState.update {
                        it.copy(error = "Could not reach LM Studio: ${e.message}", isLoadingModels = false)
                    }
                }
            )
        }
    }

    fun selectModel(model: String) {
        _uiState.update { current ->
            current.withCurrentSession(remoteResponseId = null).copy(selectedModel = model)
        }
        persistChatHistory()
        viewModelScope.launch { preferences.saveSelectedModel(model) }
    }

    fun updateInputText(text: String) {
        _uiState.update { it.copy(inputText = text) }
    }

    fun addImageAttachment(name: String, mimeType: String, dataUrl: String) {
        _uiState.update { current ->
            current.copy(
                pendingAttachments = current.pendingAttachments + PendingAttachment(
                    name = name,
                    type = PendingAttachmentType.IMAGE,
                    mimeType = mimeType,
                    dataUrl = dataUrl
                )
            )
        }
    }

    fun addTextAttachment(name: String, mimeType: String, text: String) {
        _uiState.update { current ->
            current.copy(
                pendingAttachments = current.pendingAttachments + PendingAttachment(
                    name = name,
                    type = PendingAttachmentType.TEXT,
                    mimeType = mimeType,
                    text = text
                )
            )
        }
    }

    fun removeAttachment(id: String) {
        _uiState.update { current ->
            current.copy(pendingAttachments = current.pendingAttachments.filterNot { it.id == id })
        }
    }

    fun updateChatSettings(settings: ChatSettings) {
        _uiState.update { current ->
            current.copy(
                chatSettings = settings,
                remoteResponseId = if (settings.saveRemoteHistory && !current.isTemporaryChat) {
                    current.remoteResponseId
                } else {
                    null
                }
            )
        }
        persistChatHistory()
    }

    fun sendMessage() {
        val state = _uiState.value
        val text = state.inputText.trim()
        val attachments = state.pendingAttachments
        if ((text.isEmpty() && attachments.isEmpty()) || state.isStreaming || state.selectedModel.isEmpty()) return
        val requestSettings = state.requestSettings()

        val userVisibleContent = buildVisibleUserMessage(text)
        val userMessage = UiMessage(
            role = "user",
            content = userVisibleContent,
            requestText = text,
            requestAttachments = attachments
        )
        val assistantPlaceholder = UiMessage(
            role = "assistant",
            responseStartedAtMillis = System.currentTimeMillis(),
            isThinking = true,
            isStreaming = true
        )
        val history = state.messages + userMessage
        val generatedTitle = if (state.messages.none { it.role == "user" }) {
            generateChatTitle(text, attachments)
        } else {
            null
        }

        _uiState.update { current ->
            current.copy(
                messages = history + assistantPlaceholder,
                inputText = "",
                pendingAttachments = emptyList(),
                isStreaming = true,
                error = null
            ).withCurrentSession(
                messages = history + assistantPlaceholder,
                title = generatedTitle
            )
        }

        startResponseStream(
            baseUrl = state.baseUrl,
            bearerToken = state.bearerToken,
            selectedModel = state.selectedModel,
            settings = requestSettings,
            remoteResponseId = if (state.isTemporaryChat) null else state.remoteResponseId,
            text = text,
            attachments = attachments,
            history = history,
            useRemoteContinuation = requestSettings.saveRemoteHistory,
            enabledLocalTools = state.enabledLocalTools,
            remainingLocalToolRounds = state.localToolRounds,
            allowLocalTools = true
        )
    }

    fun retryResponse(assistantMessageId: String) {
        val state = _uiState.value
        if (state.isStreaming || state.selectedModel.isEmpty()) return
        val requestSettings = state.requestSettings()

        val assistantIndex = state.messages.indexOfFirst {
            it.id == assistantMessageId && it.role == "assistant"
        }
        if (assistantIndex <= 0) return

        val userIndex = state.messages
            .take(assistantIndex)
            .indexOfLast { it.role == "user" }
        if (userIndex < 0) return

        val userMessage = state.messages[userIndex]
        val retryText = userMessage.requestText ?: userMessage.content
        val retryAttachments = userMessage.requestAttachments
        val history = state.messages.take(assistantIndex)
        val assistantPlaceholder = UiMessage(
            role = "assistant",
            responseStartedAtMillis = System.currentTimeMillis(),
            isThinking = true,
            isStreaming = true
        )

        _uiState.update { current ->
            current.copy(
                messages = history + assistantPlaceholder,
                isStreaming = true,
                error = null
            ).withCurrentSession(
                messages = history + assistantPlaceholder,
                remoteResponseId = null
            )
        }

        startResponseStream(
            baseUrl = state.baseUrl,
            bearerToken = state.bearerToken,
            selectedModel = state.selectedModel,
            settings = requestSettings,
            remoteResponseId = null,
            text = retryText,
            attachments = retryAttachments,
            history = history,
            useRemoteContinuation = false,
            enabledLocalTools = state.enabledLocalTools,
            remainingLocalToolRounds = state.localToolRounds,
            allowLocalTools = true
        )
    }

    fun editUserMessage(messageId: String) {
        _uiState.update { current ->
            if (current.isStreaming) return@update current

            val messageIndex = current.messages.indexOfFirst {
                it.id == messageId && it.role == "user"
            }
            if (messageIndex < 0) return@update current
            val hasNewerUserMessage = current.messages
                .drop(messageIndex + 1)
                .any { it.role == "user" }
            if (hasNewerUserMessage) return@update current

            val message = current.messages[messageIndex]
            val remainingMessages = current.messages.take(messageIndex)
            val restoredTitle = if (remainingMessages.none { it.role == "user" }) {
                if (current.isTemporaryChat) TEMPORARY_CHAT_TITLE else DEFAULT_CHAT_TITLE
            } else {
                null
            }

            current.copy(
                messages = remainingMessages,
                inputText = message.requestText ?: message.content,
                pendingAttachments = message.requestAttachments,
                error = null,
                remoteResponseId = null
            ).withCurrentSession(
                messages = remainingMessages,
                remoteResponseId = null,
                title = restoredTitle
            )
        }
        persistChatHistory()
    }

    private fun startResponseStream(
        baseUrl: String,
        bearerToken: String,
        selectedModel: String,
        settings: ChatSettings,
        remoteResponseId: String?,
        text: String,
        attachments: List<PendingAttachment>,
        history: List<UiMessage>,
        useRemoteContinuation: Boolean,
        enabledLocalTools: Set<String>,
        remainingLocalToolRounds: Int,
        allowLocalTools: Boolean
    ) {
        val previousResponseId = if (useRemoteContinuation) remoteResponseId else null
        val activeLocalTools = if (allowLocalTools && remainingLocalToolRounds > 0) {
            buildLocalTools(applicationContext).filter { it.info.name in enabledLocalTools }
        } else {
            emptyList()
        }
        val input = buildNativeInput(
            text = text,
            attachments = attachments,
            history = history,
            useRemoteContinuation = useRemoteContinuation && previousResponseId != null
        )

        val request = ChatRequest(
            model = selectedModel,
            input = input,
            stream = settings.stream,
            systemPrompt = buildSystemPrompt(
                userPrompt = settings.systemPrompt,
                localTools = activeLocalTools
            ),
            temperature = settings.temperature.toApiDecimal(scale = 2),
            topP = settings.topP.toApiDecimal(scale = 2),
            topK = settings.topK,
            minP = settings.minP.toApiDecimal(scale = 2),
            repeatPenalty = if (settings.repeatPenaltyEnabled) settings.repeatPenalty.toApiDecimal(scale = 2) else null,
            reasoning = settings.reasoningMode.apiValue,
            store = settings.saveRemoteHistory,
            previousResponseId = previousResponseId
        )

        streamingJob = viewModelScope.launch {
            var thinkTagState = ThinkTagState()
            var receivedResponseContent = false
            var streamFailed = false
            try {
                repository.streamChat(baseUrl, request, bearerToken).collect { token ->
                    if (token.responseId != null) {
                        _uiState.update { it.withCurrentSession(remoteResponseId = token.responseId) }
                        return@collect
                    }
                    val parsed = if (token.isThinking) {
                        ParsedContent(content = "", thinking = token.text, state = thinkTagState)
                    } else {
                        parseContentToken(token.text, thinkTagState).also { thinkTagState = it.state }
                    }
                    if (parsed.content.isNotEmpty() || parsed.thinking.isNotEmpty()) {
                        receivedResponseContent = true
                    }
                    val isThinkingNow = token.isThinking || parsed.state.inThink
                    _uiState.update { current ->
                        val msgs = current.messages.toMutableList()
                        val last = msgs.lastIndex
                        if (last >= 0 && msgs[last].role == "assistant") {
                            val msg = msgs[last]
                            val firstTokenAt = if (msg.firstTokenAtMillis == null &&
                                (parsed.content.isNotEmpty() || parsed.thinking.isNotEmpty())
                            ) {
                                System.currentTimeMillis()
                            } else {
                                msg.firstTokenAtMillis
                            }
                            msgs[last] = msg.copy(
                                content = msg.content + parsed.content,
                                thinkingContent = msg.thinkingContent + parsed.thinking,
                                errorMessage = null,
                                firstTokenAtMillis = firstTokenAt,
                                isThinking = isThinkingNow
                            )
                        }
                        current.withCurrentSession(messages = msgs)
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                streamFailed = true
                val message = e.message ?: "LM Studio did not return a response."
                _uiState.update { current ->
                    val msgs = current.messages.toMutableList()
                    val last = msgs.lastIndex
                    if (last >= 0 && msgs[last].role == "assistant" && msgs[last].isStreaming) {
                        msgs[last] = msgs[last].copy(
                            errorMessage = message,
                            responseCompletedAtMillis = System.currentTimeMillis(),
                            isStreaming = false,
                            isThinking = false
                        )
                    }
                    current.withCurrentSession(messages = msgs).copy(error = message)
                }
                persistChatHistory()
            } finally {
                val flushed = thinkTagState.flush()
                val pendingToolCalls = if (!streamFailed && activeLocalTools.isNotEmpty()) {
                    val lastMessage = _uiState.value.messages.lastOrNull()
                    lastMessage
                        ?.takeIf { it.role == "assistant" && it.isStreaming }
                        ?.let {
                            parseLocalToolCalls(
                                it.content + flushed.content + "\n" + it.thinkingContent + flushed.thinking
                            )
                        }
                        .orEmpty()
                } else {
                    emptyList()
                }

                if (pendingToolCalls.isNotEmpty()) {
                    val firstToolIndex = nextToolCallIndex(_uiState.value.messages)
                    val toolLines = pendingToolCalls.mapIndexed { offset, call ->
                        buildToolThinkingLine(firstToolIndex + offset, call.name)
                    }
                    _uiState.update { current ->
                        val msgs = current.messages.toMutableList()
                        val last = msgs.lastIndex
                        val previousThinking = if (last >= 0 && msgs[last].isStreaming) {
                            val previousAssistant = msgs.removeAt(last)
                            (previousAssistant.thinkingContent + flushed.thinking).withoutToolCallBlocks()
                        } else {
                            flushed.thinking.withoutToolCallBlocks()
                        }
                        val thinkingContent = previousThinking.withToolThinkingLines(toolLines)
                        val now = System.currentTimeMillis()
                        val assistantPlaceholder = UiMessage(
                            role = "assistant",
                            thinkingContent = thinkingContent,
                            responseStartedAtMillis = now,
                            isThinking = true,
                            isStreaming = true
                        )
                        current.withCurrentSession(messages = msgs + assistantPlaceholder)
                            .copy(isStreaming = true)
                    }
                    val toolResults = pendingToolCalls.mapIndexed { offset, call ->
                        executeLocalTool(firstToolIndex + offset, call, activeLocalTools)
                    }
                    val uiToolCalls = toolResults.map { it.toUiToolCall() }
                    val toolPrompt = buildLocalToolResultPrompt(toolResults)
                    _uiState.update { current ->
                        val msgs = current.messages.toMutableList()
                        val last = msgs.lastIndex
                        if (last >= 0 && msgs[last].isStreaming) {
                            val msg = msgs[last]
                            msgs[last] = msg.copy(toolCalls = msg.toolCalls + uiToolCalls)
                        }
                        current.withCurrentSession(messages = msgs).copy(isStreaming = true)
                    }
                    persistChatHistory()
                    startResponseStream(
                        baseUrl = baseUrl,
                        bearerToken = bearerToken,
                        selectedModel = selectedModel,
                        settings = settings.copy(saveRemoteHistory = false),
                        remoteResponseId = null,
                        text = toolPrompt,
                        attachments = emptyList(),
                        history = _uiState.value.messages,
                        useRemoteContinuation = false,
                        enabledLocalTools = _uiState.value.enabledLocalTools,
                        remainingLocalToolRounds = remainingLocalToolRounds - 1,
                        allowLocalTools = true
                    )
                    return@launch
                }

                _uiState.update { current ->
                    val msgs = current.messages.toMutableList()
                    val last = msgs.lastIndex
                    if (last >= 0 && msgs[last].isStreaming) {
                        val msg = msgs[last]
                        val hasFlushedContent = flushed.content.isNotEmpty() || flushed.thinking.isNotEmpty()
                        msgs[last] = msg.copy(
                            content = msg.content + flushed.content,
                            thinkingContent = msg.thinkingContent + flushed.thinking,
                            errorMessage = if (!receivedResponseContent && !hasFlushedContent)
                                msg.errorMessage ?: "LM Studio did not return a response."
                            else msg.errorMessage,
                            firstTokenAtMillis = if (msg.firstTokenAtMillis == null && hasFlushedContent)
                                System.currentTimeMillis()
                            else msg.firstTokenAtMillis,
                            responseCompletedAtMillis = System.currentTimeMillis(),
                            isStreaming = false,
                            isThinking = false
                        )
                    }
                    current.withCurrentSession(messages = msgs).copy(isStreaming = false)
                }
                persistChatHistory()
            }
        }
    }

    fun stopStreaming() {
        streamingJob?.cancel()
    }

    fun clearMessages() {
        _uiState.update {
            val title = if (it.isTemporaryChat) TEMPORARY_CHAT_TITLE else DEFAULT_CHAT_TITLE
            it.copy(
                messages = emptyList(),
                pendingAttachments = emptyList(),
                error = null,
                remoteResponseId = null
            )
                .withCurrentSession(
                    messages = emptyList(),
                    remoteResponseId = null,
                    title = title
                )
        }
        persistChatHistory()
    }

    fun dismissError() {
        _uiState.update { it.copy(error = null) }
    }

    fun reportAttachmentError(message: String) {
        _uiState.update { it.copy(error = message) }
    }

    fun startNewChat() {
        _uiState.update { current ->
            if (!current.isTemporaryChat && current.messages.isEmpty() && current.currentTitle() == DEFAULT_CHAT_TITLE) {
                current.copy(
                    inputText = "",
                    pendingAttachments = emptyList(),
                    error = null,
                    remoteResponseId = null,
                    isTemporaryChat = false,
                    temporarySession = null
                )
            } else {
                val session = ChatSession()
                current.copy(
                    chatSessions = listOf(session) + current.chatSessions,
                    currentChatId = session.id,
                    messages = emptyList(),
                    inputText = "",
                    pendingAttachments = emptyList(),
                    error = null,
                    remoteResponseId = null,
                    isTemporaryChat = false,
                    temporarySession = null
                )
            }
        }
        persistChatHistory()
    }

    fun startTemporaryChat() {
        _uiState.update { current ->
            if (current.isStreaming) return@update current

            val session = ChatSession(
                title = TEMPORARY_CHAT_TITLE,
                isTemporary = true
            )
            current.copy(
                currentChatId = session.id,
                messages = emptyList(),
                inputText = "",
                pendingAttachments = emptyList(),
                error = null,
                remoteResponseId = null,
                isTemporaryChat = true,
                temporarySession = session
            )
        }
    }

    fun discardTemporaryChat() {
        if (!_uiState.value.isTemporaryChat) return
        streamingJob?.cancel()
        _uiState.update { current ->
            if (!current.isTemporaryChat) return@update current

            val session = ChatSession()
            current.copy(
                chatSessions = listOf(session) + current.chatSessions,
                currentChatId = session.id,
                messages = emptyList(),
                inputText = "",
                pendingAttachments = emptyList(),
                error = null,
                remoteResponseId = null,
                isTemporaryChat = false,
                temporarySession = null,
                isStreaming = false
            )
        }
        persistChatHistory()
    }

    fun selectChat(chatId: String) {
        _uiState.update { current ->
            if (current.isStreaming) {
                current
            } else {
                val session = current.chatSessions.find { it.id == chatId } ?: return@update current
                current.copy(
                    currentChatId = session.id,
                    messages = session.messages,
                    inputText = "",
                    pendingAttachments = emptyList(),
                    error = null,
                    remoteResponseId = session.remoteResponseId,
                    isTemporaryChat = false,
                    temporarySession = null
                )
            }
        }
    }

    fun pinChat(chatId: String) {
        _uiState.update { current ->
            if (current.isStreaming) return@update current

            current.copy(
                chatSessions = current.chatSessions
                    .map { session ->
                        if (session.id == chatId) {
                            session.copy(isPinned = true, updatedAt = System.currentTimeMillis())
                        } else {
                            session
                        }
                    }
                    .sortedByDescending { it.updatedAt }
            )
        }
        persistChatHistory()
    }

    fun deleteChat(chatId: String) {
        _uiState.update { current ->
            if (current.isStreaming) return@update current

            val remainingSessions = current.chatSessions.filterNot { it.id == chatId }
            if (chatId == current.currentChatId) {
                val freshSession = ChatSession()
                current.copy(
                    chatSessions = listOf(freshSession) + remainingSessions,
                    currentChatId = freshSession.id,
                    messages = emptyList(),
                    inputText = "",
                    pendingAttachments = emptyList(),
                    error = null,
                    remoteResponseId = null,
                    isTemporaryChat = false,
                    temporarySession = null
                )
            } else {
                current.copy(chatSessions = remainingSessions)
            }
        }
        persistChatHistory()
    }

    private fun persistChatHistory(state: ChatUiState = _uiState.value) {
        if (!historyLoaded) return

        val savedSessions = state.chatSessions
            .filterNot { it.isTemporary }
            .filter { it.messages.isNotEmpty() || it.title != DEFAULT_CHAT_TITLE }
            .map { it.forSavedHistory() }
            .sortedByDescending { it.updatedAt }

        persistHistoryJob?.cancel()
        persistHistoryJob = viewModelScope.launch {
            preferences.saveChatHistoryJson(historyGson.toJson(savedSessions))
        }
    }

    class Factory(
        private val applicationContext: Context,
        private val repository: ChatRepository,
        private val preferences: AppPreferences
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ChatViewModel(applicationContext, repository, preferences) as T
    }
}

private const val DEFAULT_CHAT_TITLE = "New chat"
private const val TEMPORARY_CHAT_TITLE = "Temporary chat"
private val CHAT_SESSION_LIST_TYPE = object : TypeToken<List<ChatSession>>() {}.type

private fun Float.toApiDecimal(scale: Int): Double =
    BigDecimal.valueOf(toDouble())
        .setScale(scale, RoundingMode.HALF_UP)
        .toDouble()

private fun ChatUiState.currentTitle(): String =
    chatSessions.find { it.id == currentChatId }?.title ?: DEFAULT_CHAT_TITLE

private fun ChatUiState.requestSettings(): ChatSettings =
    if (isTemporaryChat) chatSettings.copy(saveRemoteHistory = false) else chatSettings

private fun ChatUiState.withCurrentSession(
    messages: List<UiMessage> = this.messages,
    remoteResponseId: String? = this.remoteResponseId,
    title: String? = null
): ChatUiState {
    val updatedSessions = chatSessions.map { session ->
        if (session.id == currentChatId) {
            session.copy(
                title = title ?: session.title,
                messages = messages,
                remoteResponseId = remoteResponseId,
                updatedAt = System.currentTimeMillis()
            )
        } else {
            session
        }
    }.sortedByDescending { it.updatedAt }

    if (isTemporaryChat) {
        val updatedTemporarySession = temporarySession?.copy(
            title = title ?: temporarySession.title,
            messages = messages,
            remoteResponseId = remoteResponseId,
            updatedAt = System.currentTimeMillis()
        )
        return copy(
            messages = messages,
            remoteResponseId = remoteResponseId,
            temporarySession = updatedTemporarySession,
            isTemporaryChat = true
        )
    }

    return copy(
        chatSessions = updatedSessions,
        messages = messages,
        remoteResponseId = remoteResponseId,
        isTemporaryChat = false,
        temporarySession = null
    )
}

private fun parseSavedSessions(json: String): List<ChatSession> = try {
    if (json.isBlank()) {
        emptyList()
    } else {
        val sessions = Gson().fromJson<List<ChatSession>>(json, CHAT_SESSION_LIST_TYPE).orEmpty()
        sessions
            .filterNot { it.isTemporary }
            .mapNotNull { it.sanitizeForHistory() }
            .sortedByDescending { it.updatedAt }
    }
} catch (_: Exception) {
    emptyList()
}

private fun ChatSession.forSavedHistory(): ChatSession =
    copy(
        title = runCatching { title }.getOrNull()?.takeIf { it.isNotBlank() } ?: DEFAULT_CHAT_TITLE,
        messages = runCatching { messages }.getOrNull()
            ?.mapNotNull { it.sanitizeForHistory() }
            .orEmpty(),
        isTemporary = false
    )

private fun ChatSession.sanitizeForHistory(): ChatSession? {
    val safeId = runCatching { id }.getOrNull()?.takeIf { it.isNotBlank() }
        ?: UUID.randomUUID().toString()
    val safeTitle = runCatching { title }.getOrNull()?.takeIf { it.isNotBlank() }
        ?: DEFAULT_CHAT_TITLE
    val safeMessages = runCatching { messages }.getOrNull()
        ?.mapNotNull { it.sanitizeForHistory() }
        .orEmpty()
    if (safeMessages.isEmpty() && safeTitle == DEFAULT_CHAT_TITLE) return null

    return copy(
        id = safeId,
        title = safeTitle,
        messages = safeMessages,
        remoteResponseId = runCatching { remoteResponseId }.getOrNull(),
        updatedAt = runCatching { updatedAt }.getOrDefault(System.currentTimeMillis()),
        isTemporary = false
    )
}

private fun UiMessage.sanitizeForHistory(): UiMessage? {
    val safeRole = runCatching { role }.getOrNull()
        ?.takeIf { it == "user" || it == "assistant" }
        ?: return null
    val safeAttachments = runCatching { requestAttachments }.getOrNull()
        ?.mapNotNull { it.sanitizeForHistory() }
        .orEmpty()
    val safeToolCalls = runCatching { toolCalls }.getOrNull()
        ?.mapNotNull { it.sanitizeForHistory() }
        .orEmpty()

    return copy(
        id = runCatching { id }.getOrNull()?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
        role = safeRole,
        content = runCatching { content }.getOrNull().orEmpty(),
        thinkingContent = runCatching { thinkingContent }.getOrNull().orEmpty(),
        toolCalls = safeToolCalls,
        errorMessage = runCatching { errorMessage }.getOrNull(),
        requestText = runCatching { requestText }.getOrNull(),
        requestAttachments = safeAttachments,
        isThinking = false,
        isStreaming = false
    )
}

private fun UiToolCall.sanitizeForHistory(): UiToolCall? {
    val safeIndex = runCatching { index }.getOrNull()?.takeIf { it > 0 } ?: return null
    val safeName = runCatching { name }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
    return copy(
        index = safeIndex,
        name = safeName,
        output = runCatching { output }.getOrNull().orEmpty(),
        durationMillis = runCatching { durationMillis }.getOrNull()?.coerceAtLeast(0L) ?: 0L,
        succeeded = runCatching { succeeded }.getOrDefault(false)
    )
}

private fun PendingAttachment.sanitizeForHistory(): PendingAttachment? {
    val safeType = runCatching { type }.getOrNull() ?: return null
    return copy(
        id = runCatching { id }.getOrNull()?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
        name = runCatching { name }.getOrNull()?.takeIf { it.isNotBlank() } ?: "Attachment",
        type = safeType,
        mimeType = runCatching { mimeType }.getOrNull()?.takeIf { it.isNotBlank() } ?: "application/octet-stream",
        dataUrl = runCatching { dataUrl }.getOrNull(),
        text = runCatching { text }.getOrNull()
    )
}

private fun generateChatTitle(text: String, attachments: List<PendingAttachment>): String {
    val normalized = text.replace(Regex("\\s+"), " ").trim()
    if (normalized.isEmpty()) {
        val firstAttachment = attachments.firstOrNull() ?: return DEFAULT_CHAT_TITLE
        return when (firstAttachment.type) {
            PendingAttachmentType.IMAGE -> "Image: ${firstAttachment.name}"
            PendingAttachmentType.TEXT -> "Document: ${firstAttachment.name}"
        }.limitTitle()
    }
    return normalized.limitTitle()
}

private fun String.limitTitle(): String =
    if (length <= 48) this else take(45).trimEnd() + "..."

private fun buildVisibleUserMessage(text: String): String = text.trim()

private fun buildNativeInput(
    text: String,
    attachments: List<PendingAttachment>,
    history: List<UiMessage>,
    useRemoteContinuation: Boolean
): Any {
    val messageText = buildRequestMessageText(text, attachments)
    val prompt = when {
        useRemoteContinuation -> messageText
        attachments.isNotEmpty() -> messageText
        history.size == 1 -> messageText
        else -> history.dropLast(1)
            .mapNotNull { it.toTranscriptMessageOrNull() }
            .toTranscript() + "\n\nUser:\n$messageText"
    }

    if (attachments.none { it.type == PendingAttachmentType.IMAGE }) return prompt

    return buildList {
        add(ChatInputItem(type = "text", content = prompt))
        attachments.filter { it.type == PendingAttachmentType.IMAGE }.forEach { attachment ->
            attachment.dataUrl?.let { dataUrl ->
                add(ChatInputItem(type = "image", dataUrl = dataUrl))
            }
        }
    }
}

private fun buildRequestMessageText(text: String, attachments: List<PendingAttachment>): String = buildString {
    val prompt = text.trim()
    if (prompt.isNotEmpty()) append(prompt)

    val textAttachments = attachments.filter { it.type == PendingAttachmentType.TEXT }
    if (textAttachments.isNotEmpty()) {
        if (isNotEmpty()) append("\n\n")
        textAttachments.forEachIndexed { index, attachment ->
            if (index > 0) append("\n\n")
            append("Attached text file: ${attachment.name}\n\n")
            append("```text\n")
            append(attachment.text.orEmpty())
            append("\n```")
        }
    }

    if (isEmpty()) append("Please review the attached file.")
}

private fun buildSystemPrompt(userPrompt: String, localTools: List<LocalToolDefinition>): String? {
    val parts = buildList {
        userPrompt.trim().takeIf { it.isNotBlank() }?.let { add(it) }
        if (localTools.isNotEmpty()) add(buildLocalToolPrompt(localTools))
    }
    return parts.joinToString("\n\n").takeIf { it.isNotBlank() }
}

private fun UiMessage.toTranscriptMessageOrNull(): ChatMessage? =
    when (role) {
        "user", "assistant" -> ChatMessage(role = role, content = content)
        else -> null
    }

private fun List<ChatMessage>.toTranscript(): String =
    joinToString("\n\n") { message ->
        "${message.role.replaceFirstChar { it.uppercase() }}:\n${message.content}"
    }

private data class LocalToolDefinition(
    val info: LocalToolInfo,
    val parameters: String = "{}",
    val execute: (Map<String, String>) -> String
)

private data class LocalToolCall(
    val name: String,
    val arguments: Map<String, String>
)

private data class LocalToolResult(
    val index: Int,
    val call: LocalToolCall,
    val output: String,
    val succeeded: Boolean,
    val durationMillis: Long
)

private fun buildLocalTools(context: Context): List<LocalToolDefinition> {
    val appContext = context.applicationContext
    return listOf(
        LocalToolDefinition(toolInfo("current_time.time")) {
            currentTimeInfo()
        },
        LocalToolDefinition(toolInfo("system.device")) {
            deviceInfo()
        },
        LocalToolDefinition(toolInfo("system.os")) {
            osInfo()
        },
        LocalToolDefinition(toolInfo("system.battery")) {
            batteryInfo(appContext)
        },
        LocalToolDefinition(toolInfo("system.cpu")) {
            cpuInfo()
        },
        LocalToolDefinition(toolInfo("system.memory")) {
            memoryInfo(appContext)
        },
        LocalToolDefinition(toolInfo("system.display")) {
            displayInfo(appContext)
        },
        LocalToolDefinition(toolInfo("system.network")) {
            networkInfo(appContext)
        },
        LocalToolDefinition(toolInfo("system.thermal")) {
            thermalInfo(appContext)
        },
        LocalToolDefinition(toolInfo("system.app")) {
            appInfo(appContext)
        }
    )
}

private fun toolInfo(name: String): LocalToolInfo =
    LOCAL_TOOL_INFOS.first { it.name == name }

private fun buildLocalToolPrompt(localTools: List<LocalToolDefinition>): String = buildString {
    appendLine("Local tools are available. To call one or more tools, respond only with one or more blocks:")
    appendLine("<tool_call>{\"name\":\"tool.name\",\"arguments\":{}}</tool_call>")
    appendLine("Do not add prose around tool calls. After tool results are provided, answer normally.")
    appendLine("Available local tools:")
    localTools.forEach { tool ->
        appendLine("- ${tool.info.name}: ${tool.info.description} Parameters JSON schema: ${tool.parameters}")
    }
}

private fun currentTimeInfo(): String {
    val now = ZonedDateTime.now()
    return "Current local time: ${DateTimeFormatter.RFC_1123_DATE_TIME.format(now)}; timezone: ${now.zone.id}"
}

private fun deviceInfo(): String = buildLines(
    "manufacturer" to Build.MANUFACTURER,
    "brand" to Build.BRAND,
    "model" to Build.MODEL,
    "device" to Build.DEVICE,
    "product" to Build.PRODUCT,
    "board" to Build.BOARD,
    "hardware" to Build.HARDWARE,
    "supported_abis" to Build.SUPPORTED_ABIS.joinToString(", ")
)

private fun osInfo(): String = buildLines(
    "android_release" to Build.VERSION.RELEASE,
    "sdk_level" to Build.VERSION.SDK_INT.toString(),
    "build_id" to Build.ID,
    "security_patch" to Build.VERSION.SECURITY_PATCH,
    "timezone" to ZonedDateTime.now().zone.id,
    "locale" to Locale.getDefault().toLanguageTag()
)

private fun batteryInfo(context: Context): String {
    val batteryStatus = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        ?: return "Battery information is unavailable."
    val level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
    val percent = if (level >= 0 && scale > 0) "${(level * 100) / scale}%" else "unknown"
    val status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1).batteryStatusLabel()
    val plugged = batteryStatus.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0).pluggedLabel()
    val health = batteryStatus.getIntExtra(BatteryManager.EXTRA_HEALTH, -1).batteryHealthLabel()
    val tempTenths = batteryStatus.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
    val temp = if (tempTenths != Int.MIN_VALUE) "%.1f C".format(tempTenths / 10.0) else "unknown"
    val voltage = batteryStatus.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
        .takeIf { it >= 0 }
        ?.let { "${it}mV" }
        ?: "unknown"
    val powerManager = context.getSystemService(PowerManager::class.java)
    return buildLines(
        "level" to percent,
        "status" to status,
        "plugged" to plugged,
        "health" to health,
        "temperature" to temp,
        "voltage" to voltage,
        "battery_saver" to (powerManager?.isPowerSaveMode?.toString() ?: "unknown")
    )
}

private fun cpuInfo(): String {
    val cpuModel = runCatching {
        File("/proc/cpuinfo").readLines()
            .firstNotNullOfOrNull { line ->
                val key = line.substringBefore(':').trim().lowercase()
                val value = line.substringAfter(':', "").trim()
                if (key in setOf("hardware", "model name", "processor") && value.isNotBlank()) value else null
            }
    }.getOrNull() ?: "unknown"
    return buildLines(
        "cores" to Runtime.getRuntime().availableProcessors().toString(),
        "architecture" to System.getProperty("os.arch").orEmpty().ifBlank { "unknown" },
        "supported_abis" to Build.SUPPORTED_ABIS.joinToString(", "),
        "cpu_model" to cpuModel
    )
}

private fun memoryInfo(context: Context): String {
    val activityManager = context.getSystemService(ActivityManager::class.java)
    val memoryInfo = ActivityManager.MemoryInfo()
    activityManager?.getMemoryInfo(memoryInfo)
    val runtime = Runtime.getRuntime()
    return buildLines(
        "system_total" to memoryInfo.totalMem.formatBytes(),
        "system_available" to memoryInfo.availMem.formatBytes(),
        "system_low_memory" to memoryInfo.lowMemory.toString(),
        "app_heap_max" to runtime.maxMemory().formatBytes(),
        "app_heap_total" to runtime.totalMemory().formatBytes(),
        "app_heap_free" to runtime.freeMemory().formatBytes()
    )
}

private fun displayInfo(context: Context): String {
    val metrics = context.resources.displayMetrics
    val config = context.resources.configuration
    val orientation = when (config.orientation) {
        android.content.res.Configuration.ORIENTATION_LANDSCAPE -> "landscape"
        android.content.res.Configuration.ORIENTATION_PORTRAIT -> "portrait"
        else -> "unknown"
    }
    return buildLines(
        "resolution_px" to "${metrics.widthPixels}x${metrics.heightPixels}",
        "density" to "%.2f".format(metrics.density),
        "density_dpi" to metrics.densityDpi.toString(),
        "scaled_density" to "%.2f".format(metrics.scaledDensity),
        "font_scale" to "%.2f".format(config.fontScale),
        "orientation" to orientation
    )
}

private fun networkInfo(context: Context): String {
    val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
        ?: return "Network information is unavailable."
    val network = connectivityManager.activeNetwork
    val capabilities = network?.let { connectivityManager.getNetworkCapabilities(it) }
    val transports = capabilities?.transportLabels().orEmpty()
    return buildLines(
        "connected" to (capabilities != null).toString(),
        "transports" to transports.ifBlank { "none" },
        "metered" to connectivityManager.isActiveNetworkMetered.toString()
    )
}

private fun thermalInfo(context: Context): String {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        return "Thermal status is unavailable before Android 10."
    }
    val powerManager = context.getSystemService(PowerManager::class.java)
    return buildLines(
        "thermal_status" to (powerManager?.currentThermalStatus?.thermalStatusLabel() ?: "unknown")
    )
}

@Suppress("DEPRECATION")
private fun appInfo(context: Context): String {
    val packageManager = context.packageManager
    val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
    } else {
        packageManager.getPackageInfo(context.packageName, 0)
    }
    val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        packageInfo.longVersionCode
    } else {
        packageInfo.versionCode.toLong()
    }
    return buildLines(
        "package_name" to context.packageName,
        "version_name" to (packageInfo.versionName ?: "unknown"),
        "version_code" to versionCode.toString()
    )
}

private fun parseLocalToolCalls(content: String): List<LocalToolCall> =
    content.toolCallJsonBlocks().flatMap { rawJson ->
        parseLocalToolCallsJson(rawJson)
    }

private fun parseLocalToolCallsJson(rawJson: String): List<LocalToolCall> = try {
    val parser = com.google.gson.JsonStreamParser(rawJson)
    buildList {
        while (parser.hasNext()) {
            addAll(parser.next().toLocalToolCalls())
        }
    }
} catch (_: Exception) {
    emptyList()
}

private fun com.google.gson.JsonElement.toLocalToolCalls(): List<LocalToolCall> =
    when {
        isJsonObject -> listOfNotNull(asJsonObject.toLocalToolCallOrNull())
        isJsonArray -> asJsonArray.mapNotNull { element ->
            element.takeIf { it.isJsonObject }?.asJsonObject?.toLocalToolCallOrNull()
        }
        else -> emptyList()
    }

private fun com.google.gson.JsonObject.toLocalToolCallOrNull(): LocalToolCall? {
    val name = listOf("name", "tool")
        .firstNotNullOfOrNull { key ->
            get(key)?.takeIf { it.isJsonPrimitive }?.asString
        }
        ?.takeIf { it.isNotBlank() }
        ?: return null
    val args = get("arguments")
        ?.takeIf { it.isJsonObject }
        ?.asJsonObject
        ?.entrySet()
        ?.associate { (key, value) ->
            val argValue = if (value.isJsonPrimitive) value.asString else value.toString()
            key to argValue
        }
        .orEmpty()

    return LocalToolCall(name = name, arguments = args)
}

private fun String.toolCallJsonBlocks(): List<String> {
    val blocks = mutableListOf<String>()
    var searchStart = 0
    while (searchStart < length) {
        val openStart = indexOf(TOOL_CALL_OPEN_TAG, startIndex = searchStart, ignoreCase = true)
        if (openStart < 0) break

        val contentStart = openStart + TOOL_CALL_OPEN_TAG.length
        val closeStart = indexOf(TOOL_CALL_CLOSE_TAG, startIndex = contentStart, ignoreCase = true)
        if (closeStart < 0) break

        substring(contentStart, closeStart).trim()
            .takeIf { it.isNotBlank() }
            ?.let { blocks.add(it) }
        searchStart = closeStart + TOOL_CALL_CLOSE_TAG.length
    }
    return blocks
}

private fun String.withoutToolCallBlocks(): String {
    val cleaned = StringBuilder()
    var searchStart = 0
    while (searchStart < length) {
        val openStart = indexOf(TOOL_CALL_OPEN_TAG, startIndex = searchStart, ignoreCase = true)
        if (openStart < 0) {
            cleaned.append(substring(searchStart))
            break
        }

        cleaned.append(substring(searchStart, openStart))
        val contentStart = openStart + TOOL_CALL_OPEN_TAG.length
        val closeStart = indexOf(TOOL_CALL_CLOSE_TAG, startIndex = contentStart, ignoreCase = true)
        if (closeStart < 0) {
            break
        }
        searchStart = closeStart + TOOL_CALL_CLOSE_TAG.length
    }
    return cleaned.toString().trim()
}

private fun executeLocalTool(
    index: Int,
    call: LocalToolCall,
    localTools: List<LocalToolDefinition>
): LocalToolResult {
    val startedAtMillis = System.currentTimeMillis()
    val tool = localTools.firstOrNull { it.info.name == call.name }
        ?: return LocalToolResult(
            index = index,
            call = call,
            output = "Local tool is not enabled or does not exist: ${call.name}",
            succeeded = false,
            durationMillis = System.currentTimeMillis() - startedAtMillis
        )

    return try {
        LocalToolResult(
            index = index,
            call = call,
            output = tool.execute(call.arguments),
            succeeded = true,
            durationMillis = System.currentTimeMillis() - startedAtMillis
        )
    } catch (e: Exception) {
        LocalToolResult(
            index = index,
            call = call,
            output = e.message ?: "Local tool failed.",
            succeeded = false,
            durationMillis = System.currentTimeMillis() - startedAtMillis
        )
    }
}

private fun buildLocalToolResultPrompt(results: List<LocalToolResult>): String = buildString {
    appendLine("Local tool results")
    results.forEach { result ->
        appendLine()
        appendLine("tool_call_index: ${result.index}")
        appendLine("tool: ${result.call.name}")
        appendLine("status: ${if (result.succeeded) "success" else "failure"}")
        appendLine("duration_ms: ${result.durationMillis}")
        appendLine("output:")
        appendLine(result.output)
    }
    appendLine()
    append("Use these results to answer the user's previous request. Do not call another local tool unless a new tool result is needed.")
}

private fun LocalToolResult.toUiToolCall(): UiToolCall =
    UiToolCall(
        index = index,
        name = call.name,
        output = output,
        durationMillis = durationMillis,
        succeeded = succeeded
    )

private fun nextToolCallIndex(messages: List<UiMessage>): Int =
    messages.sumOf { message ->
        runCatching { message.toolCalls.size }.getOrDefault(0)
    } + 1

private fun buildToolThinkingLine(index: Int, name: String): String =
    "Tool called #$index: $name"

private fun String.withToolThinkingLines(toolLines: List<String>): String =
    if (isBlank()) {
        toolLines.joinToString("\n\n")
    } else {
        trimEnd() + "\n\n" + toolLines.joinToString("\n\n")
    }

private data class ThinkTagState(
    val inThink: Boolean = false,
    val pending: String = ""
)

private data class ParsedContent(
    val content: String,
    val thinking: String,
    val state: ThinkTagState
)

// Parses inline <think>...</think> tags even when tags are split across stream chunks.
private fun parseContentToken(text: String, state: ThinkTagState): ParsedContent {
    val buffer = state.pending + text
    val content = StringBuilder()
    val thinking = StringBuilder()
    var inThink = state.inThink
    var index = 0

    while (index < buffer.length) {
        val tag = if (inThink) THINK_CLOSE_TAG else THINK_OPEN_TAG
        val tagIndex = buffer.indexOf(tag, startIndex = index, ignoreCase = true)

        if (tagIndex >= 0) {
            appendSegment(buffer.substring(index, tagIndex), inThink, content, thinking)
            index = tagIndex + tag.length
            inThink = !inThink
            if (!inThink) {
                while (index < buffer.length && (buffer[index] == '\r' || buffer[index] == '\n')) {
                    index++
                }
            }
        } else {
            val remainder = buffer.substring(index)
            val pendingLength = remainder.tagPrefixSuffixLength(tag)
            val emitLength = remainder.length - pendingLength
            if (emitLength > 0) {
                appendSegment(remainder.substring(0, emitLength), inThink, content, thinking)
            }
            return ParsedContent(
                content = content.toString(),
                thinking = thinking.toString(),
                state = ThinkTagState(inThink = inThink, pending = remainder.takeLast(pendingLength))
            )
        }
    }

    return ParsedContent(
        content = content.toString(),
        thinking = thinking.toString(),
        state = ThinkTagState(inThink = inThink)
    )
}

private fun ThinkTagState.flush(): ParsedContent =
    if (pending.isEmpty()) {
        ParsedContent(content = "", thinking = "", state = this)
    } else if (inThink) {
        ParsedContent(content = "", thinking = pending, state = copy(pending = ""))
    } else {
        ParsedContent(content = pending, thinking = "", state = copy(pending = ""))
    }

private fun appendSegment(
    text: String,
    inThink: Boolean,
    content: StringBuilder,
    thinking: StringBuilder
) {
    if (inThink) thinking.append(text) else content.append(text)
}

private fun String.tagPrefixSuffixLength(tag: String): Int {
    val max = minOf(length, tag.length - 1)
    for (size in max downTo 1) {
        if (endsWith(tag.take(size), ignoreCase = true)) return size
    }
    return 0
}

private fun buildLines(vararg pairs: Pair<String, String>): String =
    pairs.joinToString("\n") { (key, value) -> "$key: ${value.ifBlank { "unknown" }}" }

private fun Long.formatBytes(): String {
    if (this <= 0L) return "unknown"
    val mib = this / (1024.0 * 1024.0)
    return if (mib >= 1024.0) {
        "%.2f GiB".format(mib / 1024.0)
    } else {
        "%.1f MiB".format(mib)
    }
}

private fun Int.batteryStatusLabel(): String =
    when (this) {
        BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
        BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
        BatteryManager.BATTERY_STATUS_FULL -> "full"
        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not_charging"
        else -> "unknown"
    }

private fun Int.pluggedLabel(): String =
    when (this) {
        BatteryManager.BATTERY_PLUGGED_AC -> "ac"
        BatteryManager.BATTERY_PLUGGED_USB -> "usb"
        BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
        0 -> "none"
        else -> "other"
    }

private fun Int.batteryHealthLabel(): String =
    when (this) {
        BatteryManager.BATTERY_HEALTH_COLD -> "cold"
        BatteryManager.BATTERY_HEALTH_DEAD -> "dead"
        BatteryManager.BATTERY_HEALTH_GOOD -> "good"
        BatteryManager.BATTERY_HEALTH_OVERHEAT -> "overheat"
        BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "over_voltage"
        BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "unspecified_failure"
        else -> "unknown"
    }

private fun NetworkCapabilities.transportLabels(): String =
    buildList {
        if (hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("wifi")
        if (hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("cellular")
        if (hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("ethernet")
        if (hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) add("bluetooth")
        if (hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("vpn")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            hasTransport(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
        ) {
            add("wifi_aware")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1 &&
            hasTransport(NetworkCapabilities.TRANSPORT_LOWPAN)
        ) {
            add("lowpan")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            hasTransport(NetworkCapabilities.TRANSPORT_USB)
        ) {
            add("usb")
        }
    }.joinToString(", ")

private fun Int.thermalStatusLabel(): String =
    when (this) {
        PowerManager.THERMAL_STATUS_NONE -> "none"
        PowerManager.THERMAL_STATUS_LIGHT -> "light"
        PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
        PowerManager.THERMAL_STATUS_SEVERE -> "severe"
        PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
        else -> "unknown"
    }

private const val THINK_OPEN_TAG = "<think>"
private const val THINK_CLOSE_TAG = "</think>"
private const val TOOL_CALL_OPEN_TAG = "<tool_call>"
private const val TOOL_CALL_CLOSE_TAG = "</tool_call>"
