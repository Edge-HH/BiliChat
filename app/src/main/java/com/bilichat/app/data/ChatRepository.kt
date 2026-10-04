package com.bilichat.app.data

import com.bilichat.app.data.bili.BiliApi
import com.bilichat.app.data.bili.MessagePage
import com.bilichat.app.data.bili.RawSession
import com.bilichat.app.domain.model.Conversation
import com.bilichat.app.domain.model.Emote
import com.bilichat.app.domain.model.EmotePackage
import com.bilichat.app.domain.model.UserCard
import com.bilichat.app.domain.model.conversationKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

data class SessionsState(
    val conversations: List<Conversation> = emptyList(),
    val hasMore: Boolean = false,
    val loaded: Boolean = false,
)

/**
 * 某个账号的会话列表与消息的唯一数据源。界面与后台通知服务共享同一份会话状态。
 */
class ChatRepository(private val api: BiliApi) {
    private val users = ConcurrentHashMap<Long, UserCard>()
    private val sessionsMutex = Mutex()
    private val emoteMutex = Mutex()

    @Volatile
    private var emotes: List<EmotePackage>? = null

    private val _sessions = MutableStateFlow(SessionsState())
    val sessions: StateFlow<SessionsState> = _sessions.asStateFlow()

    @Volatile
    private var lastRefreshAt = 0L

    /** 正在聊天界面中查看的会话，不为其发送通知。 */
    @Volatile
    var activeConversationKey: String? = null

    /**
     * 刷新第一页会话并与已加载的更早会话合并。
     * 若距离上次刷新不足 [maxAgeMillis]，直接返回缓存，避免界面与后台服务重复请求。
     */
    suspend fun refreshSessions(maxAgeMillis: Long = 0): List<Conversation> = sessionsMutex.withLock {
        val current = _sessions.value
        if (current.loaded && System.currentTimeMillis() - lastRefreshAt < maxAgeMillis) {
            return@withLock current.conversations
        }
        val page = api.getSessions()
        val first = toConversations(page.sessions)
        val firstKeys = first.mapTo(HashSet()) { it.key }
        val oldest = first.filterNot { it.isPinned }.minOfOrNull { it.sessionTs } ?: Long.MAX_VALUE
        val older = current.conversations.filter { it.key !in firstKeys && !it.isPinned && it.sessionTs < oldest }
        val merged = sort(first + older)
        _sessions.value = SessionsState(
            conversations = merged,
            hasMore = if (older.isEmpty()) page.hasMore else current.hasMore,
            loaded = true,
        )
        lastRefreshAt = System.currentTimeMillis()
        merged
    }

    suspend fun loadMoreSessions() = sessionsMutex.withLock {
        val current = _sessions.value
        if (!current.hasMore) return@withLock
        val endTs = current.conversations.filterNot { it.isPinned }.minOfOrNull { it.sessionTs } ?: return@withLock
        val page = api.getSessions(endTs = endTs)
        val more = toConversations(page.sessions)
        val merged = (current.conversations + more).distinctBy { it.key }
        _sessions.value = current.copy(
            conversations = sort(merged),
            hasMore = page.hasMore && merged.size > current.conversations.size,
        )
    }

    fun conversation(key: String): Conversation? = _sessions.value.conversations.firstOrNull { it.key == key }

    suspend fun fetchMessages(
        talkerId: Long,
        sessionType: Int,
        size: Int = 30,
        beginSeqno: Long? = null,
        endSeqno: Long? = null,
    ): MessagePage = api.fetchMessages(talkerId, sessionType, size, beginSeqno, endSeqno)

    suspend fun sendText(talkerId: Long, sessionType: Int, text: String): Map<String, Emote> =
        api.sendText(talkerId, sessionType, text)

    suspend fun withdraw(talkerId: Long, sessionType: Int, msgKey: String) =
        api.withdraw(talkerId, sessionType, msgKey)

    /** 上传图片并作为图片私信发送。 */
    suspend fun sendImage(talkerId: Long, sessionType: Int, image: PreparedImage) {
        val uploaded = api.uploadImage(image.bytes, image.mimeType, image.fileName)
        api.sendImage(talkerId, sessionType, uploaded, image.imageType)
    }

    /** 表情面板数据，首次获取后缓存在内存中。 */
    suspend fun emotePackages(): List<EmotePackage> = emoteMutex.withLock {
        emotes ?: api.emotePackages().also { emotes = it }
    }

    suspend fun markRead(talkerId: Long, sessionType: Int, seqno: Long) {
        api.updateAck(talkerId, sessionType, seqno)
        val key = conversationKey(sessionType, talkerId)
        _sessions.update { state ->
            state.copy(
                conversations = state.conversations.map {
                    if (it.key == key) it.copy(unreadCount = 0, ackSeqno = maxOf(it.ackSeqno, seqno)) else it
                },
            )
        }
    }

    fun cachedUser(mid: Long): UserCard? = users[mid]

    /** 确保这些用户的昵称与头像已缓存，失败时静默忽略。 */
    suspend fun ensureUsers(uids: Collection<Long>) {
        val missing = uids.filter { it > 0 && !users.containsKey(it) }.distinct()
        if (missing.isEmpty()) return
        runCatching { api.userCards(missing) }.getOrNull()?.forEach { users[it.mid] = it }
    }

    suspend fun user(mid: Long): UserCard? {
        ensureUsers(listOf(mid))
        return users[mid]
    }

    fun clear() {
        users.clear()
        _sessions.value = SessionsState()
        lastRefreshAt = 0
        activeConversationKey = null
    }

    private suspend fun toConversations(raw: List<RawSession>): List<Conversation> {
        ensureUsers(raw.filter { it.presetName == null && it.sessionType == Conversation.SESSION_USER }.map { it.talkerId })
        return raw.map { session ->
            val card = users[session.talkerId].takeIf { session.sessionType == Conversation.SESSION_USER }
            Conversation(
                talkerId = session.talkerId,
                sessionType = session.sessionType,
                name = session.presetName ?: card?.name?.ifEmpty { null } ?: "用户 ${session.talkerId}",
                avatarUrl = session.presetAvatar ?: card?.avatarUrl,
                lastMessage = session.lastMessage,
                unreadCount = session.unreadCount,
                sessionTs = session.sessionTs,
                topTs = session.topTs,
                isMuted = session.isMuted,
                ackSeqno = session.ackSeqno,
                maxSeqno = session.maxSeqno,
                isSystem = session.systemMsgType != 0,
            )
        }
    }

    private fun sort(list: List<Conversation>): List<Conversation> =
        list.sortedWith(compareByDescending<Conversation> { it.topTs }.thenByDescending { it.sessionTs })
}
