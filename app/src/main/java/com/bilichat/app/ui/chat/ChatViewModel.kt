package com.bilichat.app.ui.chat

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bilichat.app.data.ChatRepository
import com.bilichat.app.data.ImagePreparer
import com.bilichat.app.data.SettingsRepository
import com.bilichat.app.data.bili.MessagePage
import com.bilichat.app.domain.model.Account
import com.bilichat.app.domain.model.ChatMessage
import com.bilichat.app.domain.model.Conversation
import com.bilichat.app.domain.model.Emote
import com.bilichat.app.domain.model.EmoteItem
import com.bilichat.app.domain.model.EmotePackage
import com.bilichat.app.domain.model.MessageContent
import com.bilichat.app.domain.model.UserCard
import com.bilichat.app.domain.model.conversationKey
import com.bilichat.app.notify.MessageNotifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ChatUiState(
    val title: String = "",
    val avatarUrl: String? = null,
    val isGroup: Boolean = false,
    /** 系统会话（如“UP主小助手”）不能回复。 */
    val canReply: Boolean = true,
    /** 按时间正序，已去掉撤回信令。 */
    val messages: List<ChatMessage> = emptyList(),
    val emotes: Map<String, Emote> = emptyMap(),
    val members: Map<Long, UserCard> = emptyMap(),
    val loading: Boolean = true,
    val loadError: String? = null,
    val hasOlder: Boolean = false,
    val sending: Boolean = false,
    /** 正在上传并发送图片。 */
    val sendingImage: Boolean = false,
)

sealed interface EmotePanelState {
    data object Loading : EmotePanelState
    data class Ready(val packages: List<EmotePackage>) : EmotePanelState
    data class Failed(val message: String) : EmotePanelState
}

class ChatViewModel(
    private val chat: ChatRepository,
    private val settings: SettingsRepository,
    private val notifier: MessageNotifier,
    private val images: ImagePreparer,
    val account: Account,
    val talkerId: Long,
    val sessionType: Int,
) : ViewModel() {
    private val key = conversationKey(sessionType, talkerId)

    private val _state = MutableStateFlow(ChatUiState(isGroup = sessionType == Conversation.SESSION_GROUP))
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    var input by mutableStateOf(TextFieldValue(""))

    private val _emotePanel = MutableStateFlow<EmotePanelState>(EmotePanelState.Loading)
    val emotePanel: StateFlow<EmotePanelState> = _emotePanel.asStateFlow()

    private val mutex = Mutex()
    private var loadingOlder = false
    private var ackedSeqno = 0L

    init {
        viewModelScope.launch { loadHeader() }
        viewModelScope.launch { loadInitial() }
    }

    /** 聊天界面可见期间：标记为当前会话（不弹通知），并按刷新间隔拉取新消息。 */
    suspend fun pollWhileVisible() {
        chat.activeConversationKey = key
        notifier.cancel(account.mid, key)
        try {
            settings.settings.map { it.refreshIntervalMillis }.distinctUntilChanged().collectLatest { interval ->
                while (true) {
                    delay(interval)
                    // 轮询失败（如短暂断网）不打扰用户，下个周期重试。
                    try {
                        fetchNewer()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                    }
                }
            }
        } finally {
            if (chat.activeConversationKey == key) chat.activeConversationKey = null
        }
    }

    fun retry() {
        _state.update { it.copy(loading = true, loadError = null) }
        viewModelScope.launch { loadInitial() }
    }

    fun loadOlder() {
        val oldest = _state.value.messages.firstOrNull()?.seqno ?: return
        if (loadingOlder || !_state.value.hasOlder) return
        loadingOlder = true
        viewModelScope.launch {
            runSafely {
                mutex.withLock {
                    val page = chat.fetchMessages(talkerId, sessionType, size = PAGE_SIZE, endSeqno = oldest)
                    merge(page, olderPage = true)
                }
            }
            loadingOlder = false
        }
    }

    fun send() {
        val text = input.text.trim()
        if (text.isEmpty() || _state.value.sending) return
        sendText(text, clearInput = true)
    }

    private fun sendText(text: String, clearInput: Boolean) {
        _state.update { it.copy(sending = true) }
        viewModelScope.launch {
            try {
                val emotes = chat.sendText(talkerId, sessionType, text)
                if (clearInput) input = TextFieldValue("")
                _state.update { it.copy(emotes = it.emotes + emotes) }
                fetchNewer()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _message.value = e.message ?: "发送失败"
            } finally {
                _state.update { it.copy(sending = false) }
            }
        }
    }

    fun sendImage(uri: Uri) {
        if (_state.value.sendingImage) return
        _state.update { it.copy(sendingImage = true) }
        viewModelScope.launch {
            try {
                val image = images.prepare(uri)
                chat.sendImage(talkerId, sessionType, image)
                fetchNewer()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _message.value = "图片发送失败：${e.message ?: "未知错误"}"
            } finally {
                _state.update { it.copy(sendingImage = false) }
            }
        }
    }

    /** 打开表情面板时加载当前账号的表情包。 */
    fun loadEmotes() {
        if (_emotePanel.value is EmotePanelState.Ready) return
        _emotePanel.value = EmotePanelState.Loading
        viewModelScope.launch {
            try {
                val packages = chat.emotePackages()
                _emotePanel.value = EmotePanelState.Ready(packages)
                // 让自己刚发出的表情在服务器返回解析结果前也能显示为图片。
                val known = packages.flatMap { it.emotes }
                    .filter { it.url != null }
                    .associate { it.text to Emote(it.url!!, it.large) }
                _state.update { it.copy(emotes = known + it.emotes) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _emotePanel.value = EmotePanelState.Failed(e.message ?: "表情加载失败")
            }
        }
    }

    /** 大表情直接作为一条消息发送；小表情与颜文字插入到输入框光标处。 */
    fun onEmoteSelected(emote: EmoteItem) {
        if (emote.large) {
            if (!_state.value.sending) sendText(emote.text, clearInput = false)
            return
        }
        val value = input
        val start = minOf(value.selection.start, value.selection.end)
        val end = maxOf(value.selection.start, value.selection.end)
        val text = value.text.replaceRange(start, end, emote.text)
        input = TextFieldValue(text, TextRange(start + emote.text.length))
    }

    fun deleteBackward() {
        val value = input
        if (value.selection.collapsed.not()) {
            val start = minOf(value.selection.start, value.selection.end)
            input = TextFieldValue(value.text.removeRange(start, maxOf(value.selection.start, value.selection.end)), TextRange(start))
            return
        }
        val cursor = value.selection.start
        if (cursor == 0) return
        // 光标前是完整的 [表情] 时整体删除。
        val before = value.text.substring(0, cursor)
        val emoteStart = if (before.endsWith("]")) before.lastIndexOf('[') else -1
        val start = if (emoteStart >= 0 && cursor - emoteStart <= 26) emoteStart else before.offsetByCodePoints(cursor, -1)
        input = TextFieldValue(value.text.removeRange(start, cursor), TextRange(start))
    }

    fun withdraw(message: ChatMessage) {
        viewModelScope.launch {
            runSafely {
                chat.withdraw(talkerId, sessionType, message.key)
                markWithdrawn(setOf(message.key))
                fetchNewer()
            }
        }
    }

    fun canWithdraw(message: ChatMessage): Boolean =
        message.senderUid == account.mid &&
            !message.isWithdrawn &&
            (message.type == ChatMessage.TYPE_TEXT || message.type == ChatMessage.TYPE_IMAGE) &&
            System.currentTimeMillis() / 1000 - message.timestamp < WITHDRAW_WINDOW_SECONDS

    fun consumeMessage() {
        _message.value = null
    }

    private suspend fun loadHeader() {
        val conversation = chat.conversation(key)
            ?: runCatching { chat.refreshSessions(maxAgeMillis = 10_000) }.getOrNull()?.firstOrNull { it.key == key }
        if (conversation != null) {
            _state.update {
                it.copy(title = conversation.name, avatarUrl = conversation.avatarUrl, canReply = !conversation.isSystem)
            }
            return
        }
        if (sessionType == Conversation.SESSION_USER) {
            val user = chat.user(talkerId)
            _state.update { it.copy(title = user?.name ?: "用户 $talkerId", avatarUrl = user?.avatarUrl) }
        } else {
            _state.update { it.copy(title = "粉丝团") }
        }
    }

    private suspend fun loadInitial() {
        try {
            mutex.withLock {
                val page = chat.fetchMessages(talkerId, sessionType, size = PAGE_SIZE)
                _state.update { it.copy(messages = emptyList(), hasOlder = page.hasMore) }
                merge(page, olderPage = true)
            }
            _state.update { it.copy(loading = false) }
            markRead()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(loading = false, loadError = e.message ?: "加载失败") }
        }
    }

    private suspend fun fetchNewer() {
        mutex.withLock {
            val newest = _state.value.messages.lastOrNull()?.seqno ?: 0L
            val page = chat.fetchMessages(talkerId, sessionType, size = 50, beginSeqno = newest)
            merge(page, olderPage = false)
        }
        markRead()
    }

    private suspend fun merge(page: MessagePage, olderPage: Boolean) {
        val withdrawn = page.messages
            .filter { it.isWithdrawSignal }
            .mapNotNull { (it.content as? MessageContent.Withdraw)?.targetKey }
            .toSet()
        val visible = page.messages.filterNot { it.isWithdrawSignal }
        if (sessionType == Conversation.SESSION_GROUP) chat.ensureUsers(visible.map { it.senderUid })
        _state.update { current ->
            val merged = (current.messages + visible)
                .associateBy { it.seqno }
                .values
                .sortedBy { it.seqno }
                .map { if (it.key in withdrawn) it.copy(status = ChatMessage.STATUS_WITHDRAWN) else it }
            current.copy(
                messages = merged,
                emotes = current.emotes + page.emotes,
                hasOlder = if (olderPage) page.hasMore else current.hasOlder,
                members = if (sessionType == Conversation.SESSION_GROUP) {
                    merged.mapNotNull { chat.cachedUser(it.senderUid) }.associateBy { it.mid }
                } else {
                    current.members
                },
            )
        }
    }

    private fun markWithdrawn(keys: Set<String>) {
        _state.update { current ->
            current.copy(
                messages = current.messages.map {
                    if (it.key in keys) it.copy(status = ChatMessage.STATUS_WITHDRAWN) else it
                },
            )
        }
    }

    private suspend fun markRead() {
        val latest = _state.value.messages.lastOrNull()?.seqno ?: return
        val conversation = chat.conversation(key)
        val needsAck = latest > ackedSeqno && (conversation == null || conversation.unreadCount > 0 || latest > conversation.ackSeqno)
        if (!needsAck) return
        runCatching { chat.markRead(talkerId, sessionType, latest) }.onSuccess { ackedSeqno = latest }
        notifier.cancel(account.mid, key)
    }

    private suspend fun runSafely(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _message.value = e.message ?: "操作失败"
        }
    }

    private companion object {
        const val PAGE_SIZE = 30
        const val WITHDRAW_WINDOW_SECONDS = 120
    }
}
