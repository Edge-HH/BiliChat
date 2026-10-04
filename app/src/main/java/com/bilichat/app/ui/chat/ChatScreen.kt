package com.bilichat.app.ui.chat

import android.content.ClipData
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import coil3.compose.AsyncImage
import com.bilichat.app.domain.model.ChatMessage
import com.bilichat.app.domain.model.Emote
import com.bilichat.app.domain.model.MessageContent
import com.bilichat.app.ui.components.Avatar
import com.bilichat.app.ui.components.formatChatTime
import kotlinx.coroutines.launch

private sealed interface ChatItem {
    val key: String

    data class Time(val timestamp: Long, override val key: String) : ChatItem
    data class Message(val message: ChatMessage, override val key: String) : ChatItem
}

/** 间隔超过 5 分钟插入时间分隔；结果倒序，配合 reverseLayout 让最新消息在底部。 */
private fun buildItems(messages: List<ChatMessage>): List<ChatItem> {
    val items = ArrayList<ChatItem>(messages.size + 8)
    var previous = 0L
    messages.forEach { message ->
        if (message.timestamp - previous > 5 * 60) items += ChatItem.Time(message.timestamp, "t-${message.seqno}")
        previous = message.timestamp
        items += ChatItem.Message(message, "m-${message.seqno}")
    }
    return items.asReversed()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(viewModel: ChatViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val uriHandler = LocalUriHandler.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var viewerUrl by remember { mutableStateOf<String?>(null) }
    var pendingImage by remember { mutableStateOf<Uri?>(null) }
    var emotePanelOpen by rememberSaveable { mutableStateOf(false) }
    val emotePanel by viewModel.emotePanel.collectAsStateWithLifecycle()
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val inputFocus = remember { FocusRequester() }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) pendingImage = uri
    }

    BackHandler(enabled = emotePanelOpen) { emotePanelOpen = false }

    val openUrl: (String) -> Unit = { url -> runCatching { uriHandler.openUri(url) } }

    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.pollWhileVisible() }
    }
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    val items = remember(state.messages) { buildItems(state.messages) }
    val newest = state.messages.lastOrNull()?.seqno
    LaunchedEffect(newest) {
        // 停留在底部附近时，新消息到达后自动滚到最新。
        if (newest != null && listState.firstVisibleItemIndex <= 2) listState.animateScrollToItem(0)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(url = state.avatarUrl, name = state.title, size = 36.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(state.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                actions = {
                    if (!state.isGroup && state.canReply) {
                        IconButton(onClick = { openUrl("https://space.bilibili.com/${viewModel.talkerId}") }) {
                            Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = "打开个人空间")
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (state.canReply) {
                InputBar(
                    value = viewModel.input,
                    onValueChange = { viewModel.input = it },
                    sending = state.sending,
                    sendingImage = state.sendingImage,
                    emotePanelOpen = emotePanelOpen,
                    focusRequester = inputFocus,
                    onInputFocused = { emotePanelOpen = false },
                    onToggleEmotes = {
                        if (emotePanelOpen) {
                            emotePanelOpen = false
                            inputFocus.requestFocus()
                            keyboard?.show()
                        } else {
                            viewModel.loadEmotes()
                            keyboard?.hide()
                            focusManager.clearFocus()
                            emotePanelOpen = true
                        }
                    },
                    onPickImage = {
                        imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    onSend = viewModel::send,
                ) {
                    EmotePanel(
                        state = emotePanel,
                        onSelect = viewModel::onEmoteSelected,
                        onDelete = viewModel::deleteBackward,
                        onRetry = viewModel::loadEmotes,
                    )
                }
            } else {
                Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                    Text(
                        text = "该会话不支持回复",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .windowInsetsPadding(WindowInsets.navigationBars)
                            .padding(16.dp),
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.loadError != null && state.messages.isEmpty() -> Column(
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(state.loadError.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    Spacer(Modifier.size(16.dp))
                    FilledTonalButton(onClick = viewModel::retry) { Text("重试") }
                }
                else -> LazyColumn(
                    state = listState,
                    reverseLayout = true,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(items, key = { it.key }) { item ->
                        when (item) {
                            is ChatItem.Time -> TimeDivider(item.timestamp)
                            is ChatItem.Message -> {
                                val msg = item.message
                                val mine = msg.senderUid == viewModel.account.mid
                                val sender = if (state.isGroup) state.members[msg.senderUid] else null
                                MessageRow(
                                    message = msg,
                                    mine = mine,
                                    senderName = if (state.isGroup) sender?.name ?: "用户 ${msg.senderUid}" else state.title,
                                    senderAvatar = if (state.isGroup) sender?.avatarUrl else state.avatarUrl,
                                    showSenderName = state.isGroup && !mine,
                                    emotes = state.emotes,
                                    canWithdraw = viewModel.canWithdraw(msg),
                                    onWithdraw = { viewModel.withdraw(msg) },
                                    onOpenImage = { viewerUrl = it },
                                    onOpenUrl = openUrl,
                                )
                            }
                        }
                    }
                    if (state.hasOlder) {
                        item(key = "older") {
                            LaunchedEffect(state.messages.firstOrNull()?.seqno) { viewModel.loadOlder() }
                            Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                            }
                        }
                    }
                }
            }
        }
    }

    viewerUrl?.let { url -> ImageViewer(url) { viewerUrl = null } }

    pendingImage?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingImage = null },
            title = { Text("发送图片") },
            text = {
                AsyncImage(
                    model = uri,
                    contentDescription = "待发送的图片",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .clip(RoundedCornerShape(12.dp)),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.sendImage(uri)
                    pendingImage = null
                }) { Text("发送") }
            },
            dismissButton = {
                TextButton(onClick = { pendingImage = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun TimeDivider(timestamp: Long) {
    Text(
        text = formatChatTime(timestamp),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.outline,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
    )
}

@Composable
private fun CenterNotice(text: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageRow(
    message: ChatMessage,
    mine: Boolean,
    senderName: String,
    senderAvatar: String?,
    showSenderName: Boolean,
    emotes: Map<String, Emote>,
    canWithdraw: Boolean,
    onWithdraw: () -> Unit,
    onOpenImage: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    val content = message.content
    if (message.isWithdrawn) {
        CenterNotice(if (mine) "你撤回了一条消息" else "$senderName 撤回了一条消息")
        return
    }
    if (content is MessageContent.Tip) {
        CenterNotice(content.text)
        return
    }

    val windowWidth = LocalWindowInfo.current.containerSize.width
    val maxBubbleWidth = with(LocalDensity.current) { (windowWidth * 0.72f).toDp() }
    var menuOpen by remember { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val copyText = (content as? MessageContent.Text)?.text

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top,
    ) {
        if (!mine) {
            Avatar(url = senderAvatar, name = senderName, size = 36.dp)
            Spacer(Modifier.width(8.dp))
        }
        Column(
            horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
            modifier = Modifier.widthIn(max = maxBubbleWidth),
        ) {
            if (showSenderName) {
                Text(
                    text = senderName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
                )
            }
            Box {
                val clickModifier = Modifier.combinedClickable(
                    onClick = {
                        when (content) {
                            is MessageContent.Image -> onOpenImage(content.url)
                            is MessageContent.Card -> content.url?.let(onOpenUrl)
                            else -> Unit
                        }
                    },
                    onLongClick = { if (copyText != null || canWithdraw) menuOpen = true },
                )
                Bubble(content = content, mine = mine, emotes = emotes, modifier = clickModifier)
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (copyText != null) {
                        DropdownMenuItem(
                            text = { Text("复制") },
                            leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("message", copyText))) }
                            },
                        )
                    }
                    if (canWithdraw) {
                        DropdownMenuItem(
                            text = { Text("撤回") },
                            leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Undo, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                onWithdraw()
                            },
                        )
                    }
                }
            }
            if (message.isAutoReply) {
                Text(
                    text = "自动回复",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun Bubble(
    content: MessageContent,
    mine: Boolean,
    emotes: Map<String, Emote>,
    modifier: Modifier,
) {
    val shape: Shape = if (mine) {
        RoundedCornerShape(topStart = 18.dp, topEnd = 4.dp, bottomEnd = 18.dp, bottomStart = 18.dp)
    } else {
        RoundedCornerShape(topStart = 4.dp, topEnd = 18.dp, bottomEnd = 18.dp, bottomStart = 18.dp)
    }
    val container = if (mine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh
    val onContainer = if (mine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    val linkColor = if (mine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary

    when (content) {
        is MessageContent.Text -> {
            val large = singleLargeEmote(content.text, emotes)
            if (large != null) {
                AsyncImage(
                    model = large.url,
                    contentDescription = content.text,
                    modifier = modifier.clip(RoundedCornerShape(12.dp)).size(112.dp),
                )
            } else {
                Surface(shape = shape, color = container, modifier = Modifier.clip(shape).then(modifier)) {
                    EmoteText(
                        text = content.text,
                        emotes = emotes,
                        color = onContainer,
                        linkColor = linkColor,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                    )
                }
            }
        }
        is MessageContent.Image -> ImageContent(content, modifier = Modifier.clip(RoundedCornerShape(16.dp)).then(modifier))
        is MessageContent.Card -> Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.clip(RoundedCornerShape(16.dp)).then(modifier),
        ) {
            CardContent(content, emotes)
        }
        is MessageContent.Unsupported -> Surface(shape = shape, color = container, modifier = Modifier.clip(shape).then(modifier)) {
            Text(
                text = "[暂不支持的消息类型，请在哔哩哔哩 App 中查看]",
                color = onContainer,
                fontStyle = FontStyle.Italic,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
            )
        }
        is MessageContent.Withdraw, is MessageContent.Tip -> Unit
    }
}

@Composable
private fun InputBar(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    sending: Boolean,
    sendingImage: Boolean,
    emotePanelOpen: Boolean,
    focusRequester: FocusRequester,
    onInputFocused: () -> Unit,
    onToggleEmotes: () -> Unit,
    onPickImage: () -> Unit,
    onSend: () -> Unit,
    emotePanel: @Composable () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)),
        ) {
            if (sendingImage) LinearProgressIndicator(Modifier.fillMaxWidth())
            InputRow(
                value = value,
                onValueChange = onValueChange,
                sending = sending,
                sendingImage = sendingImage,
                emotePanelOpen = emotePanelOpen,
                focusRequester = focusRequester,
                onInputFocused = onInputFocused,
                onToggleEmotes = onToggleEmotes,
                onPickImage = onPickImage,
                onSend = onSend,
            )
            if (emotePanelOpen) emotePanel()
        }
    }
}

@Composable
private fun InputRow(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    sending: Boolean,
    sendingImage: Boolean,
    emotePanelOpen: Boolean,
    focusRequester: FocusRequester,
    onInputFocused: () -> Unit,
    onToggleEmotes: () -> Unit,
    onPickImage: () -> Unit,
    onSend: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        IconButton(onClick = onToggleEmotes, modifier = Modifier.padding(bottom = 8.dp)) {
            Icon(
                imageVector = if (emotePanelOpen) Icons.Outlined.Keyboard else Icons.Outlined.EmojiEmotions,
                contentDescription = if (emotePanelOpen) "键盘" else "表情",
            )
        }
        IconButton(onClick = onPickImage, enabled = !sendingImage, modifier = Modifier.padding(bottom = 8.dp)) {
            Icon(Icons.Outlined.Image, contentDescription = "发送图片")
        }
        TextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester)
                .onFocusChanged { if (it.isFocused) onInputFocused() },
            placeholder = { Text("发消息…") },
            maxLines = 5,
            shape = RoundedCornerShape(24.dp),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            colors = TextFieldDefaults.colors(
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            ),
        )
        Spacer(Modifier.width(8.dp))
        FilledIconButton(
            onClick = onSend,
            enabled = value.text.isNotBlank() && !sending,
            modifier = Modifier
                .padding(bottom = 4.dp)
                .size(48.dp),
        ) {
            if (sending) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送")
            }
        }
    }
}
