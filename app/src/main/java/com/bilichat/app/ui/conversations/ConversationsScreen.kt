package com.bilichat.app.ui.conversations

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.bilichat.app.domain.model.Account
import com.bilichat.app.domain.model.ChatMessage
import com.bilichat.app.domain.model.Conversation
import com.bilichat.app.domain.model.MessageContent
import com.bilichat.app.domain.model.summary
import com.bilichat.app.ui.components.AccountSwitcherSheet
import com.bilichat.app.ui.components.Avatar
import com.bilichat.app.ui.components.formatListTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationsScreen(
    account: Account,
    accounts: List<Account>,
    viewModel: ConversationsViewModel,
    notice: String?,
    onNoticeShown: () -> Unit,
    onOpenChat: (Conversation) -> Unit,
    onOpenSettings: () -> Unit,
    onSwitchAccount: (Long) -> Unit,
    onAddAccount: () -> Unit,
) {
    var showSwitcher by rememberSaveable { mutableStateOf(false) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.pollWhileVisible() }
    }
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }
    LaunchedEffect(notice) {
        notice?.let {
            snackbar.showSnackbar(it)
            onNoticeShown()
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = {
                    // 点击账号名切换账号。
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { showSwitcher = true }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = account.name.ifEmpty { "UID ${account.mid}" },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Icon(Icons.Filled.ArrowDropDown, contentDescription = "切换账号")
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Outlined.Settings, contentDescription = "设置")
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                !state.loaded && state.loadError != null -> StatusPage(
                    icon = Icons.Outlined.ErrorOutline,
                    title = "加载失败",
                    message = state.loadError.orEmpty(),
                    action = "重试" to viewModel::refresh,
                )
                !state.loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                state.conversations.isEmpty() -> StatusPage(
                    icon = Icons.Outlined.Forum,
                    title = "还没有私信",
                    message = "收到的私信会显示在这里",
                )
                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(state.conversations, key = { it.key }) { conversation ->
                        ConversationItem(
                            conversation = conversation,
                            myMid = account.mid,
                            onClick = { onOpenChat(conversation) },
                        )
                    }
                    if (state.hasMore) {
                        item(key = "load-more") {
                            LaunchedEffect(state.conversations.size) { viewModel.loadMore() }
                            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showSwitcher) {
        AccountSwitcherSheet(
            accounts = accounts,
            activeMid = account.mid,
            onSwitch = onSwitchAccount,
            onAddAccount = onAddAccount,
            onManageAccounts = onOpenSettings,
            onDismiss = { showSwitcher = false },
        )
    }
}

@Composable
private fun ConversationItem(conversation: Conversation, myMid: Long, onClick: () -> Unit) {
    val unread = conversation.unreadCount > 0
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(
            containerColor = if (conversation.isPinned) {
                MaterialTheme.colorScheme.surfaceContainerLow
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
        leadingContent = { Avatar(url = conversation.avatarUrl, name = conversation.name, size = 52.dp) },
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = conversation.name,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (conversation.isPinned) {
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        Icons.Filled.PushPin,
                        contentDescription = "已置顶",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.outline,
                    )
                }
                if (conversation.isMuted) {
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        Icons.Outlined.NotificationsOff,
                        contentDescription = "免打扰",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        },
        supportingContent = {
            Text(
                text = previewText(conversation.lastMessage, myMid),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = formatListTime(conversation.lastMessage?.timestamp ?: (conversation.sessionTs / 1_000_000)),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (unread && !conversation.isMuted) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                if (unread) {
                    Badge(
                        containerColor = if (conversation.isMuted) {
                            MaterialTheme.colorScheme.surfaceVariant
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        contentColor = if (conversation.isMuted) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onPrimary
                        },
                    ) {
                        Text(if (conversation.unreadCount > 99) "99+" else conversation.unreadCount.toString())
                    }
                } else {
                    Spacer(Modifier.height(16.dp))
                }
            }
        },
    )
}

private fun previewText(message: ChatMessage?, myMid: Long): String {
    if (message == null) return ""
    val mine = message.senderUid == myMid
    return when {
        message.isWithdrawn || message.content is MessageContent.Withdraw ->
            if (mine) "你撤回了一条消息" else "对方撤回了一条消息"
        mine -> "我：" + message.content.summary()
        else -> message.content.summary()
    }
}

@Composable
private fun StatusPage(
    icon: ImageVector,
    title: String,
    message: String,
    action: Pair<String, () -> Unit>? = null,
) {
    // 可滚动，使空状态下也能下拉刷新。
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Spacer(Modifier.height(96.dp))
        Icon(icon, contentDescription = null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (action != null) {
            Spacer(Modifier.height(20.dp))
            FilledTonalButton(onClick = action.second) { Text(action.first) }
        }
    }
}
