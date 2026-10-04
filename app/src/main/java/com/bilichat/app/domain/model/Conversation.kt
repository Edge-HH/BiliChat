package com.bilichat.app.domain.model

/** 一个私信会话。`sessionType` 为 1 表示用户（含系统会话），2 表示粉丝团。 */
data class Conversation(
    val talkerId: Long,
    val sessionType: Int,
    val name: String,
    val avatarUrl: String?,
    val lastMessage: ChatMessage?,
    val unreadCount: Int,
    /** 微秒级时间戳，用于排序与翻页。 */
    val sessionTs: Long,
    /** 置顶时间（微秒），未置顶为 0。 */
    val topTs: Long,
    val isMuted: Boolean,
    val ackSeqno: Long,
    val maxSeqno: Long,
    val isSystem: Boolean,
) {
    val key: String get() = conversationKey(sessionType, talkerId)
    val isPinned: Boolean get() = topTs > 0
    val isGroup: Boolean get() = sessionType == SESSION_GROUP

    companion object {
        const val SESSION_USER = 1
        const val SESSION_GROUP = 2
    }
}

fun conversationKey(sessionType: Int, talkerId: Long): String = "$sessionType-$talkerId"
