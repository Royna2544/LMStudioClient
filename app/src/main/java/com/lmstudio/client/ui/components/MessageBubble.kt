package com.lmstudio.client.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun MessageBubble(
    content: String,
    isUser: Boolean,
    thinkingContent: String = "",
    errorMessage: String? = null,
    tttlSeconds: Double? = null,
    generationSeconds: Double? = null,
    isThinking: Boolean = false,
    isStreaming: Boolean = false,
    onCopy: () -> Unit = {},
    onShare: () -> Unit = {},
    onRetry: () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier = Modifier.widthIn(max = 300.dp),
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
        ) {
            if (!isUser && (thinkingContent.isNotEmpty() || isThinking)) {
                ThinkingBlock(content = thinkingContent, isThinking = isThinking)
            }

            if (content.isNotEmpty() || errorMessage != null || !isThinking) {
                val hasError = errorMessage != null
                Surface(
                    color = when {
                        isUser -> MaterialTheme.colorScheme.primary
                        hasError -> MaterialTheme.colorScheme.errorContainer
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    },
                    shape = RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart = if (isUser) 16.dp else 4.dp,
                        bottomEnd = if (isUser) 4.dp else 16.dp
                    )
                ) {
                    Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        if (isUser) {
                            MarkdownText(
                                content = content,
                                color = MaterialTheme.colorScheme.onPrimary,
                                fontSize = 15.sp
                            )
                        } else {
                            val displayText = if (isStreaming && !isThinking) "$content▊" else content
                            MarkdownText(
                                content = errorMessage ?: displayText,
                                color = if (hasError) MaterialTheme.colorScheme.onErrorContainer
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 15.sp
                            )
                        }
                    }
                }
            }

            if (!isUser && !isStreaming) {
                ResponseStats(
                    tttlSeconds = tttlSeconds,
                    generationSeconds = generationSeconds
                )
                ResponseActions(
                    canCopyOrShare = content.isNotBlank(),
                    onCopy = onCopy,
                    onShare = onShare,
                    onRetry = onRetry
                )
            }
        }
    }
}

@Composable
private fun ResponseStats(
    tttlSeconds: Double?,
    generationSeconds: Double?
) {
    val stats = buildList {
        tttlSeconds?.let { add("First Tok %.1fs".format(it)) }
        generationSeconds?.let { add("Generated %.1fs".format(it)) }
    }
    if (stats.isEmpty()) return

    Text(
        text = stats.joinToString(" · "),
        modifier = Modifier.padding(top = 5.dp, start = 2.dp),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.outline
    )
}

@Composable
private fun ResponseActions(
    canCopyOrShare: Boolean,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onRetry: () -> Unit
) {
    Row(
        modifier = Modifier.padding(top = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(enabled = canCopyOrShare, onClick = onCopy) {
            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text("Copy", style = MaterialTheme.typography.labelSmall)
        }
        TextButton(enabled = canCopyOrShare, onClick = onShare) {
            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text("Share", style = MaterialTheme.typography.labelSmall)
        }
        TextButton(onClick = onRetry) {
            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text("Retry", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun ThinkingBlock(content: String, isThinking: Boolean) {
    var expanded by remember { mutableStateOf(true) }

    LaunchedEffect(isThinking) {
        if (!isThinking) expanded = false
    }

    Column(modifier = Modifier.widthIn(max = 300.dp).padding(bottom = 4.dp)) {
        Row(
            modifier = Modifier
                .clickable(enabled = content.isNotEmpty()) { expanded = !expanded }
                .padding(vertical = 4.dp, horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isThinking) {
                CircularProgressIndicator(
                    modifier = Modifier.size(11.dp),
                    strokeWidth = 1.5.dp,
                    color = MaterialTheme.colorScheme.outline
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "Thinking…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            } else {
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand reasoning",
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.outline
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    "Reasoning",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }

        AnimatedVisibility(visible = expanded && content.isNotEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                MarkdownText(
                    content = content,
                    modifier = Modifier.padding(8.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
            }
        }
    }
}
