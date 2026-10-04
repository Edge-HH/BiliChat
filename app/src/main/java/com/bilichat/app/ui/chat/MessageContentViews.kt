package com.bilichat.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import com.bilichat.app.domain.model.Emote
import com.bilichat.app.domain.model.MessageContent

private val TOKEN = Regex("""(https?://[^\s\[\]<>"]+)|(\[[^\[\]\n]{1,24}])""")

/** 若整条消息只是一个大表情，返回该表情。 */
internal fun singleLargeEmote(text: String, emotes: Map<String, Emote>): Emote? =
    emotes[text.trim()]?.takeIf { it.large }

/** 支持 B 站表情（行内图片）与可点击链接的文本。 */
@Composable
internal fun EmoteText(
    text: String,
    emotes: Map<String, Emote>,
    color: Color,
    linkColor: Color,
    modifier: Modifier = Modifier,
) {
    val (annotated, inline) = remember(text, emotes, linkColor) { buildEmoteText(text, emotes, linkColor) }
    Text(
        text = annotated,
        inlineContent = inline,
        color = color,
        style = MaterialTheme.typography.bodyLarge,
        modifier = modifier,
    )
}

private fun buildEmoteText(
    text: String,
    emotes: Map<String, Emote>,
    linkColor: Color,
): Pair<AnnotatedString, Map<String, InlineTextContent>> {
    val inline = mutableMapOf<String, InlineTextContent>()
    val annotated = buildAnnotatedString {
        var cursor = 0
        TOKEN.findAll(text).forEach { match ->
            append(text.substring(cursor, match.range.first))
            val url = match.groups[1]?.value
            val emote = emotes[match.value]
            when {
                url != null -> withLink(
                    LinkAnnotation.Url(
                        url,
                        TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)),
                    ),
                ) { append(url) }
                emote != null -> {
                    val size = if (emote.large) 2.6.em else 1.4.em
                    inline.getOrPut(match.value) {
                        InlineTextContent(Placeholder(size, size, PlaceholderVerticalAlign.Center)) {
                            AsyncImage(model = emote.url, contentDescription = match.value, modifier = Modifier.fillMaxSize())
                        }
                    }
                    appendInlineContent(match.value, match.value)
                }
                else -> append(match.value)
            }
            cursor = match.range.last + 1
        }
        append(text.substring(cursor))
    }
    return annotated to inline
}

@Composable
internal fun ImageContent(image: MessageContent.Image, modifier: Modifier = Modifier) {
    val width = image.width.takeIf { it > 0 } ?: 300
    val height = image.height.takeIf { it > 0 } ?: 300
    val ratio = (width.toFloat() / height).coerceIn(0.5f, 2.5f)
    val displayWidth = if (ratio >= 1f) 220.dp else (220 * ratio).coerceAtLeast(120f).dp
    AsyncImage(
        model = image.url,
        contentDescription = "图片",
        contentScale = ContentScale.Crop,
        modifier = modifier
            .width(displayWidth)
            .aspectRatio(ratio)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    )
}

@Composable
internal fun CardContent(card: MessageContent.Card, emotes: Map<String, Emote>) {
    Column(modifier = Modifier.width(260.dp)) {
        if (card.coverUrl != null) {
            AsyncImage(
                model = card.coverUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            )
        }
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = card.label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            if (card.title.isNotBlank()) {
                Text(
                    text = card.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (card.text.isNotBlank()) {
                EmoteText(
                    text = card.text.lines().take(6).joinToString("\n"),
                    emotes = emotes,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    linkColor = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/** 全屏查看图片：双指缩放、双击放大、单击关闭。 */
@Composable
internal fun ImageViewer(url: String, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { onDismiss() },
                        onDoubleTap = {
                            scale = if (scale > 1f) 1f else 2.5f
                            offset = Offset.Zero
                        },
                    )
                }
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 5f)
                        offset = if (scale == 1f) Offset.Zero else offset + pan
                    }
                },
        ) {
            AsyncImage(
                model = url,
                contentDescription = "图片",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
            )
        }
    }
}
