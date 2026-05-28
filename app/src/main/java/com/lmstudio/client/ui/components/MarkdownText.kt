package com.lmstudio.client.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun MarkdownText(
    content: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
    fontSize: TextUnit = 15.sp
) {
    val blocks = parseMarkdownBlocks(content)
    val codeBackground = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)
    val linkColor = MaterialTheme.colorScheme.primary

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.Paragraph -> InlineText(block.text, color, fontSize, linkColor, codeBackground)
                is MarkdownBlock.Heading -> Text(
                    text = buildInlineMarkdown(block.text, color, linkColor, codeBackground),
                    style = TextStyle(
                        color = color,
                        fontSize = block.headingSize(),
                        fontWeight = FontWeight.Bold,
                        lineHeight = block.headingLineHeight()
                    )
                )
                is MarkdownBlock.Code -> CodeBlock(block.text, color, codeBackground)
                is MarkdownBlock.Quote -> Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = codeBackground,
                    shape = RoundedCornerShape(6.dp)
                ) {
                    InlineText(
                        text = block.text,
                        color = color.copy(alpha = 0.86f),
                        fontSize = fontSize,
                        linkColor = linkColor,
                        codeBackground = MaterialTheme.colorScheme.surfaceVariant
                    )
                }
                is MarkdownBlock.UnorderedList -> block.items.forEach { item ->
                    ListRow(marker = "-", text = item, color = color, fontSize = fontSize, linkColor = linkColor, codeBackground = codeBackground)
                }
                is MarkdownBlock.OrderedList -> block.items.forEachIndexed { index, item ->
                    ListRow(marker = "${index + 1}.", text = item, color = color, fontSize = fontSize, linkColor = linkColor, codeBackground = codeBackground)
                }
                MarkdownBlock.Rule -> HorizontalDivider(color = color.copy(alpha = 0.24f))
            }
        }
    }
}

@Composable
private fun InlineText(
    text: String,
    color: Color,
    fontSize: TextUnit,
    linkColor: Color,
    codeBackground: Color,
    modifier: Modifier = Modifier
) {
    Text(
        text = buildInlineMarkdown(text, color, linkColor, codeBackground),
        modifier = modifier,
        style = TextStyle(
            color = color,
            fontSize = fontSize,
            lineHeight = (fontSize.value * 1.42f).sp
        )
    )
}

@Composable
private fun ListRow(
    marker: String,
    text: String,
    color: Color,
    fontSize: TextUnit,
    linkColor: Color,
    codeBackground: Color
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = marker,
            color = color,
            fontSize = fontSize,
            lineHeight = (fontSize.value * 1.42f).sp
        )
        Spacer(Modifier.width(8.dp))
        InlineText(
            text = text,
            color = color,
            fontSize = fontSize,
            linkColor = linkColor,
            codeBackground = codeBackground,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun CodeBlock(text: String, color: Color, background: Color) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = background,
        shape = RoundedCornerShape(6.dp)
    ) {
        Text(
            text = text.trimEnd(),
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 8.dp),
            style = TextStyle(
                color = color,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                fontFamily = FontFamily.Monospace
            )
        )
    }
}

private fun buildInlineMarkdown(
    text: String,
    color: Color,
    linkColor: Color,
    codeBackground: Color
): AnnotatedString = AnnotatedString.Builder().apply {
    var index = 0
    while (index < text.length) {
        when {
            text[index] == '`' -> {
                val end = text.indexOf('`', startIndex = index + 1)
                if (end > index) {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)) {
                        append(text.substring(index + 1, end))
                    }
                    index = end + 1
                } else {
                    append(text[index])
                    index++
                }
            }
            text.startsWith("**", index) -> {
                val end = text.indexOf("**", startIndex = index + 2)
                if (end > index) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(text.substring(index + 2, end))
                    }
                    index = end + 2
                } else {
                    append(text[index])
                    index++
                }
            }
            text[index] == '*' -> {
                val end = text.indexOf('*', startIndex = index + 1)
                if (end > index) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(text.substring(index + 1, end))
                    }
                    index = end + 1
                } else {
                    append(text[index])
                    index++
                }
            }
            text[index] == '[' -> {
                val labelEnd = text.indexOf("](", startIndex = index + 1)
                val urlEnd = if (labelEnd > index) text.indexOf(')', startIndex = labelEnd + 2) else -1
                if (urlEnd > labelEnd) {
                    withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) {
                        append(text.substring(index + 1, labelEnd))
                    }
                    index = urlEnd + 1
                } else {
                    append(text[index])
                    index++
                }
            }
            else -> {
                append(text[index])
                index++
            }
        }
    }
}.toAnnotatedString()

private fun parseMarkdownBlocks(markdown: String): List<MarkdownBlock> {
    val lines = markdown.replace("\r\n", "\n").split('\n')
    val blocks = mutableListOf<MarkdownBlock>()
    val paragraph = mutableListOf<String>()
    var index = 0

    fun flushParagraph() {
        if (paragraph.isNotEmpty()) {
            blocks += MarkdownBlock.Paragraph(paragraph.joinToString("\n").trim())
            paragraph.clear()
        }
    }

    while (index < lines.size) {
        val line = lines[index]
        val trimmed = line.trim()

        when {
            trimmed.isEmpty() -> {
                flushParagraph()
                index++
            }
            trimmed.startsWith("```") || trimmed.startsWith("~~~") -> {
                flushParagraph()
                val fence = trimmed.take(3)
                index++
                val code = mutableListOf<String>()
                while (index < lines.size && !lines[index].trim().startsWith(fence)) {
                    code += lines[index]
                    index++
                }
                if (index < lines.size) index++
                blocks += MarkdownBlock.Code(code.joinToString("\n"))
            }
            HEADING_REGEX.matches(trimmed) -> {
                flushParagraph()
                val match = HEADING_REGEX.matchEntire(trimmed)!!
                blocks += MarkdownBlock.Heading(match.groupValues[1].length, match.groupValues[2].trim())
                index++
            }
            RULE_REGEX.matches(trimmed) -> {
                flushParagraph()
                blocks += MarkdownBlock.Rule
                index++
            }
            QUOTE_REGEX.matches(line) -> {
                flushParagraph()
                val quoteLines = mutableListOf<String>()
                while (index < lines.size && QUOTE_REGEX.matches(lines[index])) {
                    quoteLines += QUOTE_REGEX.matchEntire(lines[index])!!.groupValues[1]
                    index++
                }
                blocks += MarkdownBlock.Quote(quoteLines.joinToString("\n").trim())
            }
            UNORDERED_LIST_REGEX.matches(line) -> {
                flushParagraph()
                val items = mutableListOf<String>()
                while (index < lines.size && UNORDERED_LIST_REGEX.matches(lines[index])) {
                    items += UNORDERED_LIST_REGEX.matchEntire(lines[index])!!.groupValues[1].trim()
                    index++
                }
                blocks += MarkdownBlock.UnorderedList(items)
            }
            ORDERED_LIST_REGEX.matches(line) -> {
                flushParagraph()
                val items = mutableListOf<String>()
                while (index < lines.size && ORDERED_LIST_REGEX.matches(lines[index])) {
                    items += ORDERED_LIST_REGEX.matchEntire(lines[index])!!.groupValues[1].trim()
                    index++
                }
                blocks += MarkdownBlock.OrderedList(items)
            }
            else -> {
                paragraph += line
                index++
            }
        }
    }

    flushParagraph()
    return blocks
}

private fun MarkdownBlock.Heading.headingSize(): TextUnit = when (level) {
    1 -> 22.sp
    2 -> 20.sp
    3 -> 18.sp
    else -> 16.sp
}

private fun MarkdownBlock.Heading.headingLineHeight(): TextUnit = when (level) {
    1 -> 28.sp
    2 -> 26.sp
    3 -> 24.sp
    else -> 22.sp
}

@Immutable
private sealed interface MarkdownBlock {
    data class Paragraph(val text: String) : MarkdownBlock
    data class Heading(val level: Int, val text: String) : MarkdownBlock
    data class Code(val text: String) : MarkdownBlock
    data class Quote(val text: String) : MarkdownBlock
    data class UnorderedList(val items: List<String>) : MarkdownBlock
    data class OrderedList(val items: List<String>) : MarkdownBlock
    data object Rule : MarkdownBlock
}

private val HEADING_REGEX = Regex("^(#{1,6})\\s+(.+)$")
private val RULE_REGEX = Regex("^([-*_])\\1\\1+$")
private val QUOTE_REGEX = Regex("^\\s*>\\s?(.*)$")
private val UNORDERED_LIST_REGEX = Regex("^\\s*[-*+]\\s+(.+)$")
private val ORDERED_LIST_REGEX = Regex("^\\s*\\d+[.)]\\s+(.+)$")
