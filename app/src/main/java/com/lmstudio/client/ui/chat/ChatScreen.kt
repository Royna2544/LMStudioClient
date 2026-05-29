package com.lmstudio.client.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lmstudio.client.data.api.dto.briefContextLength
import com.lmstudio.client.ui.components.MessageBubble
import kotlinx.coroutines.launch
import kotlin.math.max

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onNavigateToSettings: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var showModelDropdown by remember { mutableStateOf(false) }
    var showModelInfo by remember { mutableStateOf(false) }
    var showChatSettings by remember { mutableStateOf(false) }
    var showAttachmentMenu by remember { mutableStateOf(false) }
    var followStreaming by remember { mutableStateOf(true) }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let {
            attachImageFromUri(
                context = context,
                uri = it,
                onAttach = viewModel::addImageAttachment,
                onError = viewModel::reportAttachmentError
            )
        }
    }
    val textFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            attachTextFromUri(
                context = context,
                uri = it,
                onAttach = viewModel::addTextAttachment,
                onError = viewModel::reportAttachmentError
            )
        }
    }

    val selectedModelData = remember(uiState.availableModels, uiState.selectedModel) {
        uiState.availableModels.find { it.id == uiState.selectedModel }
    }

    val messageListSize by remember {
        derivedStateOf {
            uiState.messages.size
        }
    }
    val bottomAnchorIndex = uiState.messages.size
    val streamingContentLength by remember {
        derivedStateOf {
            val last = uiState.messages.lastOrNull()
            if (last?.isStreaming == true) {
                last.content.length + last.thinkingContent.length
            } else {
                0
            }
        }
    }
    val shouldFollowStreaming by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val visibleItems = layoutInfo.visibleItemsInfo
            val lastVisibleIndex = visibleItems.lastOrNull()?.index ?: return@derivedStateOf true
            val lastItemIndex = max(0, layoutInfo.totalItemsCount - 1)
            val lastVisibleItem = visibleItems.lastOrNull()
            val viewportBottom = layoutInfo.viewportEndOffset
            val lastItemBottom = lastVisibleItem?.let { it.offset + it.size } ?: 0
            lastVisibleIndex >= lastItemIndex - 1 && lastItemBottom <= viewportBottom + 96
        }
    }
    val editableUserMessageId = remember(uiState.messages, uiState.isStreaming) {
        if (uiState.isStreaming) null else uiState.messages.lastOrNull { it.role == "user" }?.id
    }

    LaunchedEffect(uiState.currentChatId, messageListSize) {
        if (uiState.messages.isNotEmpty()) {
            followStreaming = true
            listState.animateScrollToItem(bottomAnchorIndex)
        }
    }

    LaunchedEffect(listState.isScrollInProgress, shouldFollowStreaming) {
        if (listState.isScrollInProgress) {
            followStreaming = shouldFollowStreaming
        } else if (shouldFollowStreaming) {
            followStreaming = true
        }
    }

    LaunchedEffect(streamingContentLength) {
        if (uiState.messages.isNotEmpty() && followStreaming && !listState.isScrollInProgress) {
            listState.scrollToItem(bottomAnchorIndex)
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ChatHistoryDrawer(
                sessions = uiState.chatSessions,
                currentChatId = uiState.currentChatId,
                onNewChat = {
                    viewModel.startNewChat()
                    scope.launch { drawerState.close() }
                },
                onSelectChat = { chatId ->
                    viewModel.selectChat(chatId)
                    scope.launch { drawerState.close() }
                }
            )
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "Chat history")
                        }
                    },
                    title = {
                        Box {
                            Row(
                                modifier = Modifier.clickable { showModelDropdown = true },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = when {
                                            uiState.isLoadingModels -> "Loading models…"
                                            uiState.selectedModel.isEmpty() -> "Select a model"
                                            else -> uiState.selectedModel.substringAfterLast('/')
                                        },
                                        style = MaterialTheme.typography.titleMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    val briefInfo = selectedModelData?.let { m ->
                                        buildString {
                                            m.quantization?.takeIf { it.isNotBlank() }?.let { append(it) }
                                            val ctx = m.maxContextLength.briefContextLength()
                                            if (ctx.isNotBlank()) {
                                                if (isNotEmpty()) append(" · ")
                                                append(ctx)
                                            }
                                        }
                                    }.orEmpty()
                                    if (briefInfo.isNotBlank()) {
                                        Text(
                                            text = briefInfo,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1
                                        )
                                    }
                                }
                                Icon(
                                    imageVector = Icons.Default.ArrowDropDown,
                                    contentDescription = "Model picker"
                                )
                            }

                            DropdownMenu(
                                expanded = showModelDropdown,
                                onDismissRequest = { showModelDropdown = false }
                            ) {
                                if (uiState.availableModels.isEmpty()) {
                                    DropdownMenuItem(
                                        text = { Text("No models found") },
                                        onClick = { showModelDropdown = false }
                                    )
                                } else {
                                    uiState.availableModels.forEach { model ->
                                        val briefInfo = buildString {
                                            model.quantization?.takeIf { it.isNotBlank() }?.let { append(it) }
                                            val ctx = model.maxContextLength.briefContextLength()
                                            if (ctx.isNotBlank()) {
                                                if (isNotEmpty()) append("  ")
                                                append(ctx)
                                            }
                                        }
                                        DropdownMenuItem(
                                            text = {
                                                Column {
                                                    Text(
                                                        text = model.id.substringAfterLast('/'),
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                    if (briefInfo.isNotBlank()) {
                                                        Text(
                                                            text = briefInfo,
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                    }
                                                }
                                            },
                                            onClick = {
                                                viewModel.selectModel(model.id)
                                                showModelDropdown = false
                                            },
                                            trailingIcon = if (model.id == uiState.selectedModel) {
                                                { Icon(Icons.Default.Check, contentDescription = null) }
                                            } else null
                                        )
                                    }
                                }
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text("Refresh models") },
                                    leadingIcon = {
                                        Icon(Icons.Default.Refresh, contentDescription = null)
                                    },
                                    onClick = {
                                        viewModel.loadModels()
                                        showModelDropdown = false
                                    }
                                )
                            }
                        }
                    },
                    actions = {
                        if (uiState.messages.isNotEmpty()) {
                            IconButton(onClick = { viewModel.clearMessages() }) {
                                Icon(Icons.Default.Delete, contentDescription = "Clear chat")
                            }
                        }
                        if (uiState.selectedModel.isNotEmpty()) {
                            IconButton(onClick = { showModelInfo = true }) {
                                Icon(Icons.Default.Info, contentDescription = "Model info")
                            }
                            IconButton(onClick = { showChatSettings = true }) {
                                Icon(Icons.Default.Tune, contentDescription = "Chat settings")
                            }
                        }
                        IconButton(onClick = onNavigateToSettings) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings")
                        }
                    }
                )
            }
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                // Error banner
                uiState.error?.let { error ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = error,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall
                            )
                            IconButton(onClick = { viewModel.dismissError() }) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Dismiss",
                                    tint = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    }
                }

                // Messages
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    if (uiState.messages.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillParentMaxSize()
                                    .padding(32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        "LM Studio Client",
                                        style = MaterialTheme.typography.headlineSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        if (uiState.selectedModel.isEmpty())
                                            "Select a model from the toolbar above"
                                        else
                                            "Type a message to start chatting",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                }
                            }
                        }
                    } else {
                        items(uiState.messages, key = { it.id }) { message ->
                            MessageBubble(
                                content = message.content,
                                isUser = message.role == "user",
                                attachments = message.requestAttachments,
                                thinkingContent = message.thinkingContent,
                                errorMessage = message.errorMessage,
                                tttlSeconds = message.tttlSeconds(),
                                generationSeconds = message.generationSeconds(),
                                isThinking = message.isThinking,
                                isStreaming = message.isStreaming,
                                canEditUserMessage = message.id == editableUserMessageId,
                                onCopy = { copyResponseText(context, message.content) },
                                onShare = { shareResponseText(context, message.content) },
                                onEdit = { viewModel.editUserMessage(message.id) },
                                onRetry = { viewModel.retryResponse(message.id) }
                            )
                        }
                        item(key = CHAT_BOTTOM_ANCHOR_KEY) {
                            Spacer(Modifier.height(1.dp))
                        }
                    }
                }

                // Input bar
                Surface(
                    tonalElevation = 3.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .imePadding()
                    ) {
                        if (uiState.pendingAttachments.isNotEmpty()) {
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                contentPadding = PaddingValues(bottom = 6.dp)
                            ) {
                                items(uiState.pendingAttachments, key = { it.id }) { attachment ->
                                    InputChip(
                                        selected = false,
                                        onClick = { },
                                        label = {
                                            Text(
                                                text = attachment.name,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.widthIn(max = 180.dp)
                                            )
                                        },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = if (attachment.type == PendingAttachmentType.IMAGE)
                                                    Icons.Default.Image else Icons.Default.Description,
                                                contentDescription = null
                                            )
                                        },
                                        trailingIcon = {
                                            IconButton(onClick = { viewModel.removeAttachment(attachment.id) }) {
                                                Icon(Icons.Default.Close, contentDescription = "Remove attachment")
                                            }
                                        }
                                    )
                                }
                            }
                        }
                        Row(verticalAlignment = Alignment.Bottom) {
                            Box {
                                IconButton(
                                    onClick = { showAttachmentMenu = true },
                                    enabled = !uiState.isStreaming
                                ) {
                                    Icon(Icons.Default.AttachFile, contentDescription = "Attach")
                                }
                                DropdownMenu(
                                    expanded = showAttachmentMenu,
                                    onDismissRequest = { showAttachmentMenu = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Photo from gallery") },
                                        leadingIcon = { Icon(Icons.Default.Image, contentDescription = null) },
                                        onClick = {
                                            showAttachmentMenu = false
                                            galleryLauncher.launch(
                                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                            )
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Text document") },
                                        leadingIcon = { Icon(Icons.Default.Description, contentDescription = null) },
                                        onClick = {
                                            showAttachmentMenu = false
                                            textFileLauncher.launch(
                                                arrayOf("text/*", "application/json", "application/xml")
                                            )
                                        }
                                    )
                                }
                            }
                            OutlinedTextField(
                                value = uiState.inputText,
                                onValueChange = { viewModel.updateInputText(it) },
                                placeholder = { Text("Message…") },
                                modifier = Modifier.weight(1f),
                                maxLines = 5,
                                enabled = !uiState.isStreaming,
                                shape = RoundedCornerShape(24.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            if (uiState.isStreaming) {
                                FilledIconButton(
                                    onClick = { viewModel.stopStreaming() },
                                    colors = IconButtonDefaults.filledIconButtonColors(
                                        containerColor = MaterialTheme.colorScheme.error
                                    )
                                ) {
                                    Icon(Icons.Default.Stop, contentDescription = "Stop generation")
                                }
                            } else {
                                FilledIconButton(
                                    onClick = { viewModel.sendMessage() },
                                    enabled = (uiState.inputText.isNotBlank() || uiState.pendingAttachments.isNotEmpty()) &&
                                        uiState.selectedModel.isNotEmpty()
                                ) {
                                    Icon(Icons.Default.Send, contentDescription = "Send")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Bottom sheets
    if (showModelInfo && selectedModelData != null) {
        ModelInfoSheet(
            model = selectedModelData,
            onDismiss = { showModelInfo = false }
        )
    }

    if (showChatSettings) {
        ChatSettingsSheet(
            settings = uiState.chatSettings,
            onSettingsChange = { viewModel.updateChatSettings(it) },
            onDismiss = { showChatSettings = false }
        )
    }
}

private fun UiMessage.tttlSeconds(): Double? {
    val startedAt = responseStartedAtMillis ?: return null
    val firstTokenAt = firstTokenAtMillis ?: return null
    return (firstTokenAt - startedAt).coerceAtLeast(0L) / 1000.0
}

private fun UiMessage.generationSeconds(): Double? {
    val startedAt = responseStartedAtMillis ?: return null
    val completedAt = responseCompletedAtMillis ?: return null
    return (completedAt - startedAt).coerceAtLeast(0L) / 1000.0
}

private fun copyResponseText(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("LM Studio response", text))
}

private fun shareResponseText(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, "Share response"))
}

private fun attachImageFromUri(
    context: Context,
    uri: Uri,
    onAttach: (String, String, String) -> Unit,
    onError: (String) -> Unit
) {
    try {
        val resolver = context.contentResolver
        val mimeType = resolver.getType(uri) ?: "image/*"
        val name = resolver.displayName(uri) ?: "Image"
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalArgumentException("Could not read selected image")
        val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
        onAttach(name, mimeType, "data:$mimeType;base64,$encoded")
    } catch (e: Exception) {
        onError("Could not attach image: ${e.message}")
    }
}

private fun attachTextFromUri(
    context: Context,
    uri: Uri,
    onAttach: (String, String, String) -> Unit,
    onError: (String) -> Unit
) {
    try {
        val resolver = context.contentResolver
        val mimeType = resolver.getType(uri) ?: "text/plain"
        val name = resolver.displayName(uri) ?: "Text document"
        val text = resolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8).use { reader ->
            reader?.readText()
        } ?: throw IllegalArgumentException("Could not read selected text document")
        onAttach(name, mimeType, text)
    } catch (e: Exception) {
        onError("Could not attach text document: ${e.message}")
    }
}

private fun android.content.ContentResolver.displayName(uri: Uri): String? {
    query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0) return cursor.getString(index)
        }
    }
    return uri.lastPathSegment
}

private const val CHAT_BOTTOM_ANCHOR_KEY = "chat-bottom-anchor"

@Composable
private fun ChatHistoryDrawer(
    sessions: List<ChatSession>,
    currentChatId: String,
    onNewChat: () -> Unit,
    onSelectChat: (String) -> Unit
) {
    ModalDrawerSheet(modifier = Modifier.width(320.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 8.dp, top = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Chat History",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium
            )
            IconButton(onClick = onNewChat) {
                Icon(Icons.Default.Add, contentDescription = "New chat")
            }
        }
        HorizontalDivider()
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
        ) {
            items(sessions, key = { it.id }) { session ->
                NavigationDrawerItem(
                    label = {
                        Text(
                            text = session.title,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    selected = session.id == currentChatId,
                    onClick = { onSelectChat(session.id) }
                )
            }
        }
    }
}
