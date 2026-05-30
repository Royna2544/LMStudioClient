package com.lmstudio.client.ui.chat

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.TextButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.lmstudio.client.R
import com.lmstudio.client.data.api.dto.briefContextLength
import com.lmstudio.client.ui.components.MessageBubble
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.absoluteValue
import kotlin.math.max
import kotlin.math.roundToInt

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
    val hapticFeedback = LocalHapticFeedback.current
    var showModelDropdown by remember { mutableStateOf(false) }
    var showModelInfo by remember { mutableStateOf(false) }
    var showChatSettings by remember { mutableStateOf(false) }
    var showAttachmentMenu by remember { mutableStateOf(false) }
    var followStreaming by remember { mutableStateOf(true) }
    var userPausedStreamingFollow by remember { mutableStateOf(false) }
    var hadActiveGeneration by remember { mutableStateOf(false) }
    var lastAutoScrolledChatId by rememberSaveable { mutableStateOf<String?>(null) }
    var scrollButtonDirection by remember { mutableStateOf<ScrollButtonDirection?>(null) }
    var showNotificationPermissionDialog by remember { mutableStateOf(false) }
    var pendingGenerationRequest by remember { mutableStateOf<PendingGenerationRequest?>(null) }
    val timeFormatter = remember { DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault()) }
    val dateFormatter = remember {
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault())
    }

    fun runGenerationRequest(request: PendingGenerationRequest) {
        when (request) {
            PendingGenerationRequest.Send -> viewModel.sendMessage()
            is PendingGenerationRequest.Retry -> viewModel.retryResponse(request.assistantMessageId)
        }
    }

    fun startGenerationWithNotificationGate(request: PendingGenerationRequest) {
        val needsNotificationPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        if (needsNotificationPermission) {
            pendingGenerationRequest = request
            showNotificationPermissionDialog = true
        } else {
            runGenerationRequest(request)
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        val request = pendingGenerationRequest
        pendingGenerationRequest = null
        request?.let(::runGenerationRequest)
    }

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

    val messageListSize = uiState.messages.size
    val bottomAnchorIndex = messageListSize
    val streamingContentLength = uiState.messages.lastOrNull()
        ?.takeIf { it.isStreaming }
        ?.let { it.content.length + it.thinkingContent.length }
        ?: 0
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
        if (uiState.currentChatId != lastAutoScrolledChatId && uiState.messages.isNotEmpty()) {
            lastAutoScrolledChatId = uiState.currentChatId
            followStreaming = false
            listState.scrollToItem(bottomAnchorIndex)
        }
    }

    LaunchedEffect(messageListSize, uiState.isStreaming) {
        if (uiState.isStreaming && uiState.messages.isNotEmpty()) {
            userPausedStreamingFollow = false
            followStreaming = true
            listState.animateScrollToItem(bottomAnchorIndex)
        }
    }

    LaunchedEffect(listState.isScrollInProgress, shouldFollowStreaming, uiState.isStreaming) {
        if (!uiState.isStreaming) {
            userPausedStreamingFollow = false
            followStreaming = false
        } else if (userPausedStreamingFollow && shouldFollowStreaming && !listState.isScrollInProgress) {
            userPausedStreamingFollow = false
            followStreaming = true
        } else if (userPausedStreamingFollow) {
            followStreaming = false
        } else if (shouldFollowStreaming) {
            followStreaming = true
        }
    }

    LaunchedEffect(streamingContentLength, uiState.isStreaming) {
        if (uiState.isStreaming &&
            uiState.messages.isNotEmpty() &&
            followStreaming &&
            !userPausedStreamingFollow &&
            !listState.isScrollInProgress
        ) {
            listState.scrollToItem(bottomAnchorIndex)
        }
    }

    LaunchedEffect(uiState.isStreaming) {
        if (hadActiveGeneration && !uiState.isStreaming) {
            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
        }
        hadActiveGeneration = uiState.isStreaming
    }

    LaunchedEffect(uiState.notice) {
        if (uiState.notice != null) {
            delay(2_500)
            viewModel.dismissNotice()
        }
    }

    LaunchedEffect(listState) {
        var previousPosition = 0
        snapshotFlow {
            listState.firstVisibleItemIndex * SCROLL_POSITION_MULTIPLIER +
                listState.firstVisibleItemScrollOffset
        }.collect { position ->
            if (listState.isScrollInProgress && listState.layoutInfo.totalItemsCount > 0) {
                scrollButtonDirection = when {
                    position > previousPosition -> ScrollButtonDirection.Bottom
                    position < previousPosition -> ScrollButtonDirection.Top
                    else -> scrollButtonDirection
                }
            }
            previousPosition = position
        }
    }

    if (showNotificationPermissionDialog) {
        AlertDialog(
            onDismissRequest = {
                showNotificationPermissionDialog = false
                pendingGenerationRequest = null
            },
            title = { Text(stringResource(R.string.enable_notifications)) },
            text = { Text(stringResource(R.string.notification_permission_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showNotificationPermissionDialog = false
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                ) {
                    Text(stringResource(R.string.ok))
                }
            }
        )
    }

    val mainContent: @Composable (Boolean, Modifier) -> Unit = { showHistoryButton, modifier ->
        Scaffold(
            modifier = modifier,
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        if (showHistoryButton) {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(Icons.Default.Menu, contentDescription = stringResource(R.string.chat_history_cd))
                            }
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
                                            uiState.isLoadingModels -> stringResource(R.string.loading_models)
                                            uiState.selectedModel.isEmpty() -> stringResource(R.string.select_model)
                                            else -> uiState.selectedModel.substringAfterLast('/')
                                        },
                                        style = MaterialTheme.typography.titleMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    val loadedLabel = stringResource(R.string.loaded)
                                    val temporaryLabel = stringResource(R.string.temporary)
                                    val briefInfo = selectedModelData?.let { m ->
                                        buildString {
                                            if (m.isLoaded) append(loadedLabel)
                                            m.quantization?.takeIf { it.isNotBlank() }?.let {
                                                if (isNotEmpty()) append(" · ")
                                                append(it)
                                            }
                                            val ctx = m.maxContextLength.briefContextLength()
                                            if (ctx.isNotBlank()) {
                                                if (isNotEmpty()) append(" · ")
                                                append(ctx)
                                            }
                                        }
                                    }.orEmpty()
                                    val statusInfo = buildString {
                                        if (uiState.isTemporaryChat) append(temporaryLabel)
                                        if (briefInfo.isNotBlank()) {
                                            if (isNotEmpty()) append(" · ")
                                            append(briefInfo)
                                        }
                                    }
                                    if (statusInfo.isNotBlank()) {
                                        Text(
                                            text = statusInfo,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1
                                        )
                                    }
                                }
                                Icon(
                                    imageVector = Icons.Default.ArrowDropDown,
                                    contentDescription = stringResource(R.string.model_picker_cd)
                                )
                            }

                            DropdownMenu(
                                expanded = showModelDropdown,
                                onDismissRequest = { showModelDropdown = false }
                            ) {
                                if (uiState.availableModels.isEmpty()) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.no_models_found)) },
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
                                    text = { Text(stringResource(R.string.refresh_models)) },
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
                                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.clear_chat_cd))
                            }
                        }
                        if (uiState.selectedModel.isNotEmpty()) {
                            IconButton(onClick = { showModelInfo = true }) {
                                Icon(Icons.Default.Info, contentDescription = stringResource(R.string.model_info_cd))
                            }
                            IconButton(onClick = { showChatSettings = true }) {
                                Icon(Icons.Default.Tune, contentDescription = stringResource(R.string.chat_settings_cd))
                            }
                        }
                        IconButton(
                            onClick = {
                                viewModel.discardTemporaryChat()
                                onNavigateToSettings()
                            }
                        ) {
                            Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings))
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
                uiState.notice?.let { notice ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFFFFF3CD)
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = notice,
                                color = Color(0xFF5F4300),
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall
                            )
                            IconButton(onClick = { viewModel.dismissNotice() }) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = stringResource(R.string.dismiss_cd),
                                    tint = Color(0xFF5F4300)
                                )
                            }
                        }
                    }
                }

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
                                    contentDescription = stringResource(R.string.dismiss_cd),
                                    tint = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    }
                }

                // Messages
                Box(modifier = Modifier.weight(1f)) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(uiState.isStreaming) {
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false)
                                    if (uiState.isStreaming) {
                                        userPausedStreamingFollow = true
                                        followStreaming = false
                                    }
                                }
                            },
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
                                            stringResource(R.string.app_name),
                                            style = MaterialTheme.typography.headlineSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(Modifier.height(8.dp))
                                        Text(
                                            if (uiState.selectedModel.isEmpty())
                                                stringResource(R.string.select_model_hint)
                                            else
                                                stringResource(R.string.start_chat_hint),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.outline
                                        )
                                    }
                                }
                            }
                        } else {
                            itemsIndexed(
                                items = uiState.messages,
                                key = { _, message -> message.id }
                            ) { index, message ->
                                if (message.role != "tool") {
                                    val currentDate = message.createdAtMillis.messageDate()
                                    val previousDate = uiState.messages
                                        .take(index)
                                        .lastOrNull { it.role != "tool" }
                                        ?.createdAtMillis
                                        ?.messageDate()
                                    if (currentDate != previousDate) {
                                        DateDivider(label = currentDate.format(dateFormatter))
                                    }
                                    MessageBubble(
                                        content = message.content,
                                        isUser = message.role == "user",
                                        attachments = message.requestAttachments,
                                        thinkingContent = message.thinkingContent,
                                        errorMessage = message.errorMessage,
                                        isCanceled = message.isCanceled,
                                        tttlSeconds = message.tttlSeconds(),
                                        generationSeconds = message.generationSeconds(),
                                        timestampLabel = message.createdAtMillis.messageTime(timeFormatter),
                                        toolCalls = message.toolCalls,
                                        isThinking = message.isThinking,
                                        isModelLoading = message.isModelLoading,
                                        isStreaming = message.isStreaming,
                                        foldThinkingByDefault = uiState.foldThinkingByDefault,
                                        canEditUserMessage = message.id == editableUserMessageId,
                                        onCopy = { copyResponseText(context, message.content) },
                                        onShare = { shareResponseText(context, message.content) },
                                        onEdit = { viewModel.editUserMessage(message.id) },
                                        onRetry = {
                                            startGenerationWithNotificationGate(
                                                PendingGenerationRequest.Retry(message.id)
                                            )
                                        }
                                    )
                                }
                            }
                            item(key = CHAT_BOTTOM_ANCHOR_KEY) {
                                Spacer(Modifier.height(1.dp))
                            }
                        }
                    }

                    val showScrollToTop = scrollButtonDirection == ScrollButtonDirection.Top &&
                        listState.canScrollBackward
                    val showScrollToBottom = scrollButtonDirection == ScrollButtonDirection.Bottom &&
                        listState.canScrollForward
                    if (showScrollToTop) {
                        FilledIconButton(
                            onClick = {
                                scope.launch { listState.animateScrollToItem(0) }
                            },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(16.dp)
                        ) {
                            Icon(
                                Icons.Default.KeyboardArrowUp,
                                contentDescription = stringResource(R.string.scroll_to_top_cd)
                            )
                        }
                    }
                    if (showScrollToBottom) {
                        FilledIconButton(
                            onClick = {
                                scope.launch { listState.animateScrollToItem(bottomAnchorIndex) }
                            },
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(16.dp)
                        ) {
                            Icon(
                                Icons.Default.KeyboardArrowDown,
                                contentDescription = stringResource(R.string.scroll_to_bottom_cd)
                            )
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
                                                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.remove_attachment_cd))
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
                                    Icon(Icons.Default.AttachFile, contentDescription = stringResource(R.string.attach_cd))
                                }
                                DropdownMenu(
                                    expanded = showAttachmentMenu,
                                    onDismissRequest = { showAttachmentMenu = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.photo_from_gallery)) },
                                        leadingIcon = { Icon(Icons.Default.Image, contentDescription = null) },
                                        onClick = {
                                            showAttachmentMenu = false
                                            galleryLauncher.launch(
                                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                            )
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.text_document)) },
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
                                placeholder = { Text(stringResource(R.string.message_placeholder)) },
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
                                    Icon(Icons.Default.Stop, contentDescription = stringResource(R.string.stop_generation_cd))
                                }
                            } else {
                                FilledIconButton(
                                    onClick = {
                                        startGenerationWithNotificationGate(PendingGenerationRequest.Send)
                                    },
                                    enabled = (uiState.inputText.isNotBlank() || uiState.pendingAttachments.isNotEmpty()) &&
                                        uiState.selectedModel.isNotEmpty()
                                ) {
                                    Icon(Icons.Default.Send, contentDescription = stringResource(R.string.send_cd))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val showPersistentHistory = maxWidth >= HISTORY_PANE_BREAKPOINT

        LaunchedEffect(showPersistentHistory) {
            if (showPersistentHistory && drawerState.isOpen) {
                drawerState.close()
            }
        }

        if (showPersistentHistory) {
            val historyPaneWeight = HISTORY_PANE_DEFAULT_RATIO
            val chatPaneWeight = 1f - historyPaneWeight

            Row(modifier = Modifier.fillMaxSize()) {
                ChatHistoryPane(
                    sessions = uiState.chatSessions,
                    currentChatId = uiState.currentChatId,
                    onNewChat = viewModel::startNewChat,
                    onTemporaryChat = viewModel::startTemporaryChat,
                    onSelectChat = viewModel::selectChat,
                    onPinChat = viewModel::pinChat,
                    onDeleteChat = viewModel::deleteChat,
                    modifier = Modifier
                        .weight(historyPaneWeight)
                        .fillMaxSize()
                )
                Box(
                    modifier = Modifier
                        .width(HISTORY_SPLITTER_WIDTH)
                        .fillMaxSize()
                ) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .width(HISTORY_SPLITTER_LINE_WIDTH)
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
                    )
                }
                mainContent(false, Modifier.weight(chatPaneWeight))
            }
        } else {
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
                        onTemporaryChat = {
                            viewModel.startTemporaryChat()
                            scope.launch { drawerState.close() }
                        },
                        onSelectChat = { chatId ->
                            viewModel.selectChat(chatId)
                            scope.launch { drawerState.close() }
                        },
                        onPinChat = viewModel::pinChat,
                        onDeleteChat = viewModel::deleteChat
                    )
                }
            ) {
                mainContent(true, Modifier.fillMaxSize())
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

@Composable
private fun DateDivider(label: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HorizontalDivider(modifier = Modifier.weight(1f))
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 10.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline
        )
        HorizontalDivider(modifier = Modifier.weight(1f))
    }
}

private fun Long.messageDate(): LocalDate =
    Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).toLocalDate()

private fun Long.messageTime(formatter: DateTimeFormatter): String =
    formatter.format(Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()))

private fun copyResponseText(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.clipboard_response_label), text))
}

private fun shareResponseText(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, context.getString(R.string.share_response)))
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
        val name = resolver.displayName(uri) ?: context.getString(R.string.default_image_name)
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalArgumentException(context.getString(R.string.attach_image_read_error))
        val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
        onAttach(name, mimeType, "data:$mimeType;base64,$encoded")
    } catch (e: Exception) {
        onError(context.getString(R.string.attach_image_error, e.message.orEmpty()))
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
        val name = resolver.displayName(uri) ?: context.getString(R.string.text_document)
        val text = resolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8).use { reader ->
            reader?.readText()
        } ?: throw IllegalArgumentException(context.getString(R.string.attach_text_read_error))
        onAttach(name, mimeType, text)
    } catch (e: Exception) {
        onError(context.getString(R.string.attach_text_error, e.message.orEmpty()))
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
private const val SCROLL_POSITION_MULTIPLIER = 100_000
private const val HISTORY_PANE_DEFAULT_RATIO = 0.3f
private const val HISTORY_PANE_DEFAULT_WIDTH_VALUE = 320f
private val HISTORY_PANE_WIDTH = HISTORY_PANE_DEFAULT_WIDTH_VALUE.dp
private val HISTORY_PANE_BREAKPOINT = 600.dp
private val HISTORY_SPLITTER_WIDTH = 1.dp
private val HISTORY_SPLITTER_LINE_WIDTH = 1.dp

private sealed class PendingGenerationRequest {
    object Send : PendingGenerationRequest()
    data class Retry(val assistantMessageId: String) : PendingGenerationRequest()
}

private enum class ScrollButtonDirection {
    Top,
    Bottom
}

@Composable
private fun ChatHistoryDrawer(
    sessions: List<ChatSession>,
    currentChatId: String,
    onNewChat: () -> Unit,
    onTemporaryChat: () -> Unit,
    onSelectChat: (String) -> Unit,
    onPinChat: (String) -> Unit,
    onDeleteChat: (String) -> Unit
) {
    ModalDrawerSheet(modifier = Modifier.width(HISTORY_PANE_WIDTH)) {
        ChatHistoryContent(
            sessions = sessions,
            currentChatId = currentChatId,
            onNewChat = onNewChat,
            onTemporaryChat = onTemporaryChat,
            onSelectChat = onSelectChat,
            onPinChat = onPinChat,
            onDeleteChat = onDeleteChat
        )
    }
}

@Composable
private fun ChatHistoryPane(
    sessions: List<ChatSession>,
    currentChatId: String,
    onNewChat: () -> Unit,
    onTemporaryChat: () -> Unit,
    onSelectChat: (String) -> Unit,
    onPinChat: (String) -> Unit,
    onDeleteChat: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface
    ) {
        ChatHistoryContent(
            sessions = sessions,
            currentChatId = currentChatId,
            onNewChat = onNewChat,
            onTemporaryChat = onTemporaryChat,
            onSelectChat = onSelectChat,
            onPinChat = onPinChat,
            onDeleteChat = onDeleteChat
        )
    }
}

@Composable
private fun ChatHistoryContent(
    sessions: List<ChatSession>,
    currentChatId: String,
    onNewChat: () -> Unit,
    onTemporaryChat: () -> Unit,
    onSelectChat: (String) -> Unit,
    onPinChat: (String) -> Unit,
    onDeleteChat: (String) -> Unit
) {
    val visibleSessions = sessions.filterNot { it.isTemporary }
    val pinnedSessions = visibleSessions.filter { it.isPinned }
    val recentSessions = visibleSessions.filterNot { it.isPinned }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 8.dp, top = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.chat_history),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium
            )
            TextButton(onClick = onTemporaryChat) {
                Text(stringResource(R.string.temporary))
            }
            IconButton(onClick = onNewChat) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.new_chat_cd))
            }
        }
        HorizontalDivider()
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
        ) {
            if (pinnedSessions.isNotEmpty()) {
                item(key = "pinned-section") {
                    DrawerSectionLabel(stringResource(R.string.pinned_chat))
                }
                items(pinnedSessions, key = { it.id }) { session ->
                    SwipeableChatHistoryItem(
                        session = session,
                        selected = session.id == currentChatId,
                        onSelectChat = onSelectChat,
                        onPinChat = onPinChat,
                        onDeleteChat = onDeleteChat
                    )
                }
            }
            if (recentSessions.isNotEmpty()) {
                if (pinnedSessions.isNotEmpty()) {
                    item(key = "recent-section") {
                        DrawerSectionLabel(stringResource(R.string.recent_chats))
                    }
                }
                items(recentSessions, key = { it.id }) { session ->
                    SwipeableChatHistoryItem(
                        session = session,
                        selected = session.id == currentChatId,
                        onSelectChat = onSelectChat,
                        onPinChat = onPinChat,
                        onDeleteChat = onDeleteChat
                    )
                }
            }
        }
    }
}

@Composable
private fun DrawerSectionLabel(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 6.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun SwipeableChatHistoryItem(
    session: ChatSession,
    selected: Boolean,
    onSelectChat: (String) -> Unit,
    onPinChat: (String) -> Unit,
    onDeleteChat: (String) -> Unit
) {
    var offsetX by remember(session.id) { mutableStateOf(0f) }
    val density = LocalDensity.current
    val revealLimitPx = with(density) { 112.dp.toPx() }
    val commitThresholdPx = with(density) { 104.dp.toPx() }
    val progress = (offsetX.absoluteValue / commitThresholdPx).coerceIn(0f, 1f)
    val isPinSwipe = offsetX > 0f
    val isDeleteSwipe = offsetX < 0f
    val pinColor = Color(0xFF1565C0)
    val deleteColor = MaterialTheme.colorScheme.error
    val backgroundColor = when {
        isPinSwipe -> pinColor.copy(alpha = 0.12f + (0.88f * progress))
        isDeleteSwipe -> deleteColor.copy(alpha = 0.12f + (0.88f * progress))
        else -> Color.Transparent
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(backgroundColor)
    ) {
        if (isPinSwipe) {
            Icon(
                imageVector = Icons.Default.PushPin,
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 20.dp),
                tint = Color.White.copy(alpha = progress.coerceAtLeast(0.35f))
            )
        } else if (isDeleteSwipe) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 20.dp),
                tint = Color.White.copy(alpha = progress.coerceAtLeast(0.35f))
            )
        }

        NavigationDrawerItem(
            modifier = Modifier
                .offset { IntOffset(offsetX.roundToInt(), 0) }
                .pointerInput(session.id) {
                    detectHorizontalDragGestures(
                        onDragCancel = { offsetX = 0f },
                        onDragEnd = {
                            val completedOffset = offsetX
                            offsetX = 0f
                            when {
                                completedOffset <= -commitThresholdPx -> onDeleteChat(session.id)
                                completedOffset >= commitThresholdPx -> onPinChat(session.id)
                            }
                        }
                    ) { change, dragAmount ->
                        change.consume()
                        offsetX = (offsetX + dragAmount).coerceIn(-revealLimitPx, revealLimitPx)
                    }
                },
            label = {
                Column {
                    Text(
                        text = session.title,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    when {
                        session.isTemporary -> Text(
                            text = stringResource(R.string.temporary),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        session.isPinned -> Text(
                            text = stringResource(R.string.pinned),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            selected = selected,
            onClick = { onSelectChat(session.id) }
        )
    }
}
