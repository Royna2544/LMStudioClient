package com.lmstudio.client.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
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
import kotlin.math.round
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

data class UiMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: String,
    val content: String = "",
    val thinkingContent: String = "",
    val errorMessage: String? = null,
    val requestText: String? = null,
    val requestAttachments: List<PendingAttachment> = emptyList(),
    val isThinking: Boolean = false,
    val isStreaming: Boolean = false
)

data class ChatSession(
    val id: String = UUID.randomUUID().toString(),
    val title: String = DEFAULT_CHAT_TITLE,
    val messages: List<UiMessage> = emptyList(),
    val remoteResponseId: String? = null,
    val updatedAt: Long = System.currentTimeMillis()
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
    val chatSettings: ChatSettings = ChatSettings(),
    val error: String? = null,
    val isLoadingModels: Boolean = false,
    val remoteResponseId: String? = null
)

class ChatViewModel(
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

    private var streamingJob: Job? = null

    init {
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
                remoteResponseId = if (settings.saveRemoteHistory) current.remoteResponseId else null
            )
        }
    }

    fun sendMessage() {
        val state = _uiState.value
        val text = state.inputText.trim()
        val attachments = state.pendingAttachments
        if ((text.isEmpty() && attachments.isEmpty()) || state.isStreaming || state.selectedModel.isEmpty()) return

        val userVisibleContent = buildVisibleUserMessage(text, attachments)
        val userMessage = UiMessage(
            role = "user",
            content = userVisibleContent,
            requestText = text,
            requestAttachments = attachments
        )
        val assistantPlaceholder = UiMessage(role = "assistant", isStreaming = true)
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
            settings = state.chatSettings,
            remoteResponseId = state.remoteResponseId,
            text = text,
            attachments = attachments,
            history = history,
            useRemoteContinuation = state.chatSettings.saveRemoteHistory
        )
    }

    fun retryResponse(assistantMessageId: String) {
        val state = _uiState.value
        if (state.isStreaming || state.selectedModel.isEmpty()) return

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
        val assistantPlaceholder = UiMessage(role = "assistant", isStreaming = true)

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
            settings = state.chatSettings,
            remoteResponseId = null,
            text = retryText,
            attachments = retryAttachments,
            history = history,
            useRemoteContinuation = false
        )
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
        useRemoteContinuation: Boolean
    ) {
        val previousResponseId = if (useRemoteContinuation) remoteResponseId else null
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
            systemPrompt = settings.systemPrompt.takeIf { it.isNotBlank() },
            temperature = settings.temperature.roundToTenths().toDouble(),
            topP = settings.topP.roundToHundredths().toDouble(),
            topK = settings.topK,
            minP = settings.minP.roundToHundredths().toDouble(),
            repeatPenalty = if (settings.repeatPenaltyEnabled) settings.repeatPenalty.roundToTwentieths().toDouble() else null,
            reasoning = settings.reasoningMode.apiValue,
            store = settings.saveRemoteHistory,
            previousResponseId = previousResponseId
        )

        streamingJob = viewModelScope.launch {
            var thinkTagState = ThinkTagState()
            var receivedResponseContent = false
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
                            msgs[last] = msg.copy(
                                content = msg.content + parsed.content,
                                thinkingContent = msg.thinkingContent + parsed.thinking,
                                errorMessage = null,
                                isThinking = isThinkingNow
                            )
                        }
                        current.withCurrentSession(messages = msgs)
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                val message = "No response from LM Studio: ${e.message ?: "server error"}"
                _uiState.update { current ->
                    val msgs = current.messages.toMutableList()
                    val last = msgs.lastIndex
                    if (last >= 0 && msgs[last].role == "assistant" && msgs[last].isStreaming) {
                        msgs[last] = msgs[last].copy(
                            errorMessage = message,
                            isStreaming = false,
                            isThinking = false
                        )
                    }
                    current.withCurrentSession(messages = msgs).copy(error = message)
                }
            } finally {
                val flushed = thinkTagState.flush()
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
                            isStreaming = false,
                            isThinking = false
                        )
                    }
                    current.withCurrentSession(messages = msgs).copy(isStreaming = false)
                }
            }
        }
    }

    fun stopStreaming() {
        streamingJob?.cancel()
    }

    fun clearMessages() {
        _uiState.update {
            it.copy(
                messages = emptyList(),
                pendingAttachments = emptyList(),
                error = null,
                remoteResponseId = null
            )
                .withCurrentSession(
                    messages = emptyList(),
                    remoteResponseId = null,
                    title = DEFAULT_CHAT_TITLE
                )
        }
    }

    fun dismissError() {
        _uiState.update { it.copy(error = null) }
    }

    fun reportAttachmentError(message: String) {
        _uiState.update { it.copy(error = message) }
    }

    fun startNewChat() {
        _uiState.update { current ->
            if (current.messages.isEmpty() && current.currentTitle() == DEFAULT_CHAT_TITLE) {
                current.copy(
                    inputText = "",
                    pendingAttachments = emptyList(),
                    error = null,
                    remoteResponseId = null
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
                    remoteResponseId = null
                )
            }
        }
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
                    remoteResponseId = session.remoteResponseId
                )
            }
        }
    }

    class Factory(
        private val repository: ChatRepository,
        private val preferences: AppPreferences
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ChatViewModel(repository, preferences) as T
    }
}

private const val DEFAULT_CHAT_TITLE = "New chat"

private fun Float.roundToTenths(): Float = round(this * 10f) / 10f

private fun Float.roundToHundredths(): Float = round(this * 100f) / 100f

private fun Float.roundToTwentieths(): Float = round(this * 20f) / 20f

private fun ChatUiState.currentTitle(): String =
    chatSessions.find { it.id == currentChatId }?.title ?: DEFAULT_CHAT_TITLE

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

    return copy(
        chatSessions = updatedSessions,
        messages = messages,
        remoteResponseId = remoteResponseId
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

private fun buildVisibleUserMessage(text: String, attachments: List<PendingAttachment>): String = buildString {
    if (text.isNotBlank()) append(text)
    if (attachments.isNotEmpty()) {
        if (isNotEmpty()) append("\n\n")
        append("Attached:\n")
        attachments.forEach { attachment ->
            val label = when (attachment.type) {
                PendingAttachmentType.IMAGE -> "Image"
                PendingAttachmentType.TEXT -> "Text file"
            }
            append("- $label: ${attachment.name}\n")
        }
    }
}.trimEnd()

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
            .map { ChatMessage(role = it.role, content = it.content) }
            .toTranscript() + "\n\nUser:\n$messageText"
    }

    if (attachments.none { it.type == PendingAttachmentType.IMAGE }) return prompt

    return buildList {
        add(ChatInputItem(type = "message", content = prompt))
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

private fun List<ChatMessage>.toTranscript(): String =
    joinToString("\n\n") { message ->
        "${message.role.replaceFirstChar { it.uppercase() }}:\n${message.content}"
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

private const val THINK_OPEN_TAG = "<think>"
private const val THINK_CLOSE_TAG = "</think>"
